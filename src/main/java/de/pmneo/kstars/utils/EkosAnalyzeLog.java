package de.pmneo.kstars.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.pmneo.kstars.KStarsState.CapturedImage;
import de.pmneo.kstars.KStarsState.GuideDeltaSample;
import de.pmneo.kstars.KStarsState.HfrSample;

/**
 * Parses Ekos's own "Analyze" session log (~/.local/share/kstars/analyze/ekos-*.analyze) to
 * recover recent capture/focus/guide history across a restart of *this* server — KStars/Ekos
 * itself keeps running (and keeps appending to that file) independently of our JVM, but the
 * in-memory ring buffers we build from live D-Bus signals start empty every time we restart.
 *
 * Format: one CSV row per event, "EventType,secondsSinceStart,field...". The very first data
 * row (AnalyzeStartTime) gives the absolute wall-clock time every later row's second column is
 * an offset from.
 */
public class EkosAnalyzeLog {

    private static final DateTimeFormatter START_TIME_FORMAT = DateTimeFormatter.ofPattern( "yyyy-MM-dd HH:mm:ss.SSS" );

    public static class ParsedHistory {
        public final List<CapturedImage> images = new ArrayList<>();
        public final List<HfrSample> hfrSamples = new ArrayList<>();
        public final List<GuideDeltaSample> guideSamples = new ArrayList<>();
    }

    /**
     * The single most recent file is often a short guiding-only test session with zero captures
     * — walks newest-to-oldest instead, merging in each file's history (oldest-first, so the
     * result stays chronological) until all three minimums are met or maxFiles is hit, whichever
     * comes first. Bounds the worst case (some ancient file being huge) without giving up early
     * just because the very latest session happened to be short.
     */
    public static ParsedHistory parseRecent( File analyzeDir, int minImages, int minHfr, int minGuide, int maxFiles ) throws IOException {
        ParsedHistory result = new ParsedHistory();

        File[] files = analyzeDir.listFiles( ( d, n ) -> n.endsWith( ".analyze" ) );
        if( files == null || files.length == 0 ) {
            return result;
        }
        Arrays.sort( files, Comparator.comparingLong( File::lastModified ).reversed() );

        for( int i = 0; i < files.length && i < maxFiles; i++ ) {
            ParsedHistory fromFile = parse( files[i] );

            result.images.addAll( 0, fromFile.images );
            result.hfrSamples.addAll( 0, fromFile.hfrSamples );
            result.guideSamples.addAll( 0, fromFile.guideSamples );

            if( result.images.size() >= minImages && result.hfrSamples.size() >= minHfr && result.guideSamples.size() >= minGuide ) {
                break;
            }
        }

        return result;
    }

    public static ParsedHistory parse( File file ) throws IOException {
        ParsedHistory result = new ParsedHistory();
        long startEpochMillis = -1;

        try( BufferedReader r = new BufferedReader( new FileReader( file ) ) ) {
            String line;
            while( (line = r.readLine()) != null ) {
                if( line.isBlank() || line.startsWith( "#" ) ) {
                    continue;
                }

                String[] parts = line.split( ",", -1 );
                String type = parts[0];

                if( "AnalyzeStartTime".equals( type ) ) {
                    try {
                        LocalDateTime ldt = LocalDateTime.parse( parts[1], START_TIME_FORMAT );
                        startEpochMillis = ldt.atZone( ZoneId.systemDefault() ).toInstant().toEpochMilli();
                    }
                    catch( Throwable t ) {
                        //leave startEpochMillis unset — every later row is skipped without it
                    }
                    continue;
                }

                if( startEpochMillis < 0 || parts.length < 2 ) {
                    continue;
                }

                long ts;
                try {
                    ts = startEpochMillis + Math.round( Double.parseDouble( parts[1] ) * 1000 );
                }
                catch( NumberFormatException e ) {
                    continue;
                }

                switch( type ) {
                    case "CaptureComplete":
                        parseCaptureComplete( result, ts, parts );
                        break;
                    case "AutofocusComplete":
                        parseAutofocusComplete( result, ts, parts );
                        break;
                    case "GuideStats":
                        parseGuideStats( result, ts, parts );
                        break;
                    default:
                        //other event types (MountState, AlignState, ...) aren't needed for this replay
                }
            }
        }

        return result;
    }

    private static void parseCaptureComplete( ParsedHistory result, long ts, String[] parts ) {
        // CaptureComplete,<offsetSec>,<exposure>,<filter>,<hfr>,<filepath>,...
        if( parts.length < 6 || parts[5].isBlank() ) {
            return; //no file was actually saved (e.g. an aborted/preview capture)
        }

        String filepath = parts[5];

        Map<String,Object> m = new LinkedHashMap<>();
        // CapturedImage.filename is actually the full path by convention (same as the real
        // captureComplete signal's metadata) — resolveKnownCapturedFile() does `new File(filename)`
        // directly, so trimming this to a basename would make the thumbnail endpoint 404 forever.
        m.put( "filename", filepath );
        m.put( "filter", parts[3] );
        m.put( "exposure", parseD( parts[2] ) );
        m.put( "hfr", parseD( parts[4] ) );
        m.put( "type", inferFrameType( filepath ) );

        result.images.add( new CapturedImage( ts, m ) );
    }

    private static void parseAutofocusComplete( ParsedHistory result, long ts, String[] parts ) {
        // AutofocusComplete,<offsetSec>,<temperature>,<pointCount>,<?>,<filter>,<pos>|<hfr>|<weight>|<flag>|...
        // Confirmed against a real KStars 3.8.4 analyze log (Analyze log version 1.0) — the
        // points list is index 6, not 3, and each sample is a 4-tuple, not a pair (position,
        // HFR, weight, flag). Getting either wrong used to silently yield zero HFR samples,
        // since parts[3] alone (just the point count, e.g. "9") has nothing to pair up.
        if( parts.length < 7 ) {
            return;
        }

        String[] points = parts[6].split( "\\|" );
        for( int i = 0; i + 1 < points.length; i += 4 ) {
            try {
                int position = (int) Double.parseDouble( points[i] );
                double hfr = Double.parseDouble( points[i + 1] );
                result.hfrSamples.add( new HfrSample( ts, hfr, position ) );
            }
            catch( NumberFormatException e ) {
                //skip malformed quadruple
            }
        }
    }

    private static void parseGuideStats( ParsedHistory result, long ts, String[] parts ) {
        // GuideStats,<offsetSec>,<raError>,<decError>,...
        if( parts.length < 4 ) {
            return;
        }
        try {
            result.guideSamples.add( new GuideDeltaSample( ts, Double.parseDouble( parts[2] ), Double.parseDouble( parts[3] ) ) );
        }
        catch( NumberFormatException e ) {
            //skip malformed row
        }
    }

    private static int inferFrameType( String filepath ) {
        // No frame-type field in this row — KStars' own default capture layout always puts the
        // frame type in the path (.../<Target>/<Type>/<Filter>/...), so infer it from that.
        String p = filepath.toLowerCase();
        if( p.contains( "/flat/" ) ) return 3;
        if( p.contains( "/dark/" ) ) return 2;
        if( p.contains( "/bias/" ) ) return 1;
        return 0; //Light — also the default when the type can't be inferred from the path
    }

    private static double parseD( String s ) {
        try {
            return Double.parseDouble( s );
        }
        catch( Throwable t ) {
            return -1;
        }
    }
}
