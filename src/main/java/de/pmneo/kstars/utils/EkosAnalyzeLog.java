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

import de.pmneo.kstars.SessionHistory.CapturedImage;
import de.pmneo.kstars.SessionHistory.GuideDeltaSample;
import de.pmneo.kstars.SessionHistory.HfrSample;
import de.pmneo.kstars.SessionHistory.TimelineEvent;

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

    /** Must match KStarsCluster.PRIMARY_TRAIN — kept as a separate literal instead of importing it
     *  to avoid a de.pmneo.kstars.utils -> de.pmneo.kstars -> de.pmneo.kstars.utils dependency
     *  cycle. Used whenever a row has no train field at all (see CaptureComplete/AutofocusComplete
     *  below) — every KStarsCluster session before this log format change recorded everything
     *  under "Primary" anyway, so this is exactly the behavior those old rows already had. */
    private static final String DEFAULT_TRAIN = "Primary";

    public static class ParsedHistory {
        public final Map<String, List<CapturedImage>> images = new LinkedHashMap<>();
        public final Map<String, List<HfrSample>> hfrSamples = new LinkedHashMap<>();
        public final List<GuideDeltaSample> guideSamples = new ArrayList<>();
        /** guide/mount/align/scheduler lane events — see AlignState/GuideState/MountState/
         *  SchedulerJobStart/SchedulerJobEnd parsing below. Lets the session timeline show a
         *  session's full guide/mount/align/scheduler history right after a restart, the same way
         *  images/hfrSamples/guideSamples already do, instead of only starting to fill in from
         *  whenever live D-Bus signals resume. */
        public final List<TimelineEvent> timelineEvents = new ArrayList<>();

        private void addImage( String train, CapturedImage img ) {
            images.computeIfAbsent( train, t -> new ArrayList<>() ).add( img );
        }

        private void addHfrSample( String train, HfrSample sample ) {
            hfrSamples.computeIfAbsent( train, t -> new ArrayList<>() ).add( sample );
        }

        private void addTimelineEvent( long ts, String lane, String label ) {
            timelineEvents.add( new TimelineEvent( ts, lane, label ) );
        }

        public int totalImages() {
            return images.values().stream().mapToInt( List::size ).sum();
        }

        public int totalHfrSamples() {
            return hfrSamples.values().stream().mapToInt( List::size ).sum();
        }

        /** Prepends an older file's per-train lists onto this (newer-first-then-merged-backwards)
         *  result, keeping each train's own samples chronological — same merge order parseRecent
         *  already used for the flat lists before this became per-train. */
        private void prependAll( ParsedHistory older ) {
            older.images.forEach( ( train, imgs ) -> images.computeIfAbsent( train, t -> new ArrayList<>() ).addAll( 0, imgs ) );
            older.hfrSamples.forEach( ( train, samples ) -> hfrSamples.computeIfAbsent( train, t -> new ArrayList<>() ).addAll( 0, samples ) );
            guideSamples.addAll( 0, older.guideSamples );
            timelineEvents.addAll( 0, older.timelineEvents );
        }
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
            result.prependAll( fromFile );

            if( result.totalImages() >= minImages && result.totalHfrSamples() >= minHfr && result.guideSamples.size() >= minGuide ) {
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
                    case "AlignState":
                        parseAlignState( result, ts, parts );
                        break;
                    case "GuideState":
                        parseGuideState( result, ts, parts );
                        break;
                    case "MountState":
                        parseMountState( result, ts, parts );
                        break;
                    case "SchedulerJobStart":
                        parseSchedulerJobStart( result, ts, parts );
                        break;
                    case "SchedulerJobEnd":
                        parseSchedulerJobEnd( result, ts, parts );
                        break;
                    default:
                        //other event types (Temperature, MountCoords, ...) aren't needed for this replay
                }
            }
        }

        return result;
    }

    /** Old rows have exactly 9 fields (0-8, ending at eccentricity); a train name got appended
     *  as a 10th field afterwards. Reading it off the end (rather than assuming a fixed total
     *  width) means old rows without it still parse the same as always, train just falls back to
     *  DEFAULT_TRAIN — same for parseAutofocusComplete's 10-vs-11 check below. */
    private static final int CAPTURE_COMPLETE_FIELDS_BEFORE_TRAIN = 9;
    private static final int AUTOFOCUS_COMPLETE_FIELDS_BEFORE_TRAIN = 10;

    private static void parseCaptureComplete( ParsedHistory result, long ts, String[] parts ) {
        // CaptureComplete,<offsetSec>,<exposure>,<filter>,<hfr>,<filepath>,<binx>,<biny>,<eccentricity>[,<train>]
        if( parts.length < 6 || parts[5].isBlank() ) {
            return; //no file was actually saved (e.g. an aborted/preview capture)
        }

        String filepath = parts[5];
        String train = parts.length > CAPTURE_COMPLETE_FIELDS_BEFORE_TRAIN ? parts[parts.length - 1] : DEFAULT_TRAIN;

        Map<String,Object> m = new LinkedHashMap<>();
        // CapturedImage.filename is actually the full path by convention (same as the real
        // captureComplete signal's metadata) — resolveKnownCapturedFile() does `new File(filename)`
        // directly, so trimming this to a basename would make the thumbnail endpoint 404 forever.
        m.put( "filename", filepath );
        m.put( "filter", parts[3] );
        m.put( "exposure", parseD( parts[2] ) );
        m.put( "hfr", parseD( parts[4] ) );
        m.put( "type", inferFrameType( filepath ) );

        result.addImage( train, new CapturedImage( ts, m ) );
    }

    private static void parseAutofocusComplete( ParsedHistory result, long ts, String[] parts ) {
        // AutofocusComplete,<offsetSec>,<temperature>,<pointCount>,<?>,<filter>,<pos>|<hfr>|<weight>|<flag>|...,...,<solutionDescription>[,<train>]
        // Confirmed against a real KStars 3.8.4 analyze log (Analyze log version 1.0) — the
        // points list is index 6, not 3, and each sample is a 4-tuple, not a pair (position,
        // HFR, weight, flag). Getting either wrong used to silently yield zero HFR samples,
        // since parts[3] alone (just the point count, e.g. "9") has nothing to pair up.
        if( parts.length < 7 ) {
            return;
        }

        String train = parts.length > AUTOFOCUS_COMPLETE_FIELDS_BEFORE_TRAIN ? parts[parts.length - 1] : DEFAULT_TRAIN;

        String[] points = parts[6].split( "\\|" );
        for( int i = 0; i + 1 < points.length; i += 4 ) {
            try {
                int position = (int) Double.parseDouble( points[i] );
                double hfr = Double.parseDouble( points[i + 1] );
                result.addHfrSample( train, new HfrSample( ts, hfr, position ) );
            }
            catch( NumberFormatException e ) {
                //skip malformed quadruple
            }
        }
    }

    private static void parseGuideStats( ParsedHistory result, long ts, String[] parts ) {
        // GuideStats,<offsetSec>,<raError>,<decError>,... — no train field; guiding is tracked
        // app-wide regardless of which train is currently capturing (see SessionHistory.guideDeltaHistory).
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

    /** AlignState/GuideState/MountState rows carry KStars' own *localized* status text (e.g.
     *  German "Kalibrierung" for "Calibrating") rather than the enum constant name the live
     *  D-Bus signal handlers record (see KStarsState.handleGuideStatus et al.) — confirmed
     *  against real analyze logs written under both German and English KStars locales. Translated
     *  here so the session timeline's lane coloring (keyed on the English constant name) works
     *  the same regardless of whether an event came from a live signal or this replay. An
     *  unrecognized value (a locale/version not seen yet) falls through as its raw text — the
     *  frontend just renders it in the neutral/idle color rather than failing to parse. */
    private static final Map<String,String> ALIGN_STATE_LABELS = Map.ofEntries(
        Map.entry( "Inaktiv", "ALIGN_IDLE" ), Map.entry( "Idle", "ALIGN_IDLE" ),
        Map.entry( "Beendet", "ALIGN_COMPLETE" ), Map.entry( "Complete", "ALIGN_COMPLETE" ),
        Map.entry( "Fehlgeschlagen", "ALIGN_FAILED" ), Map.entry( "Failed", "ALIGN_FAILED" ),
        Map.entry( "Abgebrochen", "ALIGN_ABORTED" ), Map.entry( "Aborted", "ALIGN_ABORTED" ),
        Map.entry( "In Bearbeitung", "ALIGN_PROGRESS" ), Map.entry( "In Progress", "ALIGN_PROGRESS" ),
        Map.entry( "Erfolgreich", "ALIGN_SUCCESSFUL" ), Map.entry( "Successful", "ALIGN_SUCCESSFUL" ),
        Map.entry( "Abgleichen", "ALIGN_SYNCING" ), Map.entry( "Syncing", "ALIGN_SYNCING" ),
        Map.entry( "Schwenken", "ALIGN_SLEWING" ), Map.entry( "Slewing", "ALIGN_SLEWING" ),
        Map.entry( "Rotierend", "ALIGN_ROTATING" ), Map.entry( "Rotating", "ALIGN_ROTATING" ),
        Map.entry( "Angehalten", "ALIGN_SUSPENDED" ), Map.entry( "Suspended", "ALIGN_SUSPENDED" )
    );

    private static final Map<String,String> GUIDE_STATE_LABELS = Map.ofEntries(
        Map.entry( "Inaktiv", "GUIDE_IDLE" ), Map.entry( "Idle", "GUIDE_IDLE" ),
        Map.entry( "Abgebrochen", "GUIDE_ABORTED" ), Map.entry( "Aborted", "GUIDE_ABORTED" ),
        Map.entry( "Aufnahme läuft", "GUIDE_CAPTURE" ), Map.entry( "Capture", "GUIDE_CAPTURE" ),
        Map.entry( "Looping", "GUIDE_LOOPING" ),
        Map.entry( "Stern auswählen", "GUIDE_STAR_SELECT" ), Map.entry( "Star Select", "GUIDE_STAR_SELECT" ),
        Map.entry( "Kalibrierung", "GUIDE_CALIBRATING" ), Map.entry( "Calibrating", "GUIDE_CALIBRATING" ),
        Map.entry( "Kalibrierungsfehler", "GUIDE_CALIBRATION_ERROR" ), Map.entry( "Calibration error", "GUIDE_CALIBRATION_ERROR" ),
        Map.entry( "Kalibriert", "GUIDE_CALIBRATION_SUCESS" ), Map.entry( "Calibration successful", "GUIDE_CALIBRATION_SUCESS" ),
        Map.entry( "Nachführung", "GUIDE_GUIDING" ), Map.entry( "Guiding", "GUIDE_GUIDING" ),
        Map.entry( "Reacquiring", "GUIDE_REACQUIRE" ),
        Map.entry( "Dithering", "GUIDE_DITHERING" ),
        Map.entry( "Dithering error", "GUIDE_DITHERING_ERROR" ),
        Map.entry( "Dithering successful", "GUIDE_DITHERING_SUCCESS" ),
        Map.entry( "Settling", "GUIDE_DITHERING_SETTLE" )
    );

    private static final Map<String,String> MOUNT_STATE_LABELS = Map.ofEntries(
        Map.entry( "Inaktiv", "MOUNT_IDLE" ), Map.entry( "Idle", "MOUNT_IDLE" ),
        Map.entry( "Moving", "MOUNT_MOVING" ),
        Map.entry( "Schwenken", "MOUNT_SLEWING" ), Map.entry( "Slewing", "MOUNT_SLEWING" ),
        Map.entry( "Verfolgung", "MOUNT_TRACKING" ), Map.entry( "Tracking", "MOUNT_TRACKING" ),
        Map.entry( "Parking", "MOUNT_PARKING" ),
        Map.entry( "Geparkt", "MOUNT_PARKED" ), Map.entry( "Parked", "MOUNT_PARKED" ),
        Map.entry( "Fehler", "MOUNT_ERROR" ), Map.entry( "Error", "MOUNT_ERROR" )
    );

    private static void parseAlignState( ParsedHistory result, long ts, String[] parts ) {
        if( parts.length < 3 ) {
            return;
        }
        result.addTimelineEvent( ts, "align", ALIGN_STATE_LABELS.getOrDefault( parts[2], parts[2] ) );
    }

    private static void parseGuideState( ParsedHistory result, long ts, String[] parts ) {
        if( parts.length < 3 ) {
            return;
        }
        result.addTimelineEvent( ts, "guide", GUIDE_STATE_LABELS.getOrDefault( parts[2], parts[2] ) );
    }

    private static void parseMountState( ParsedHistory result, long ts, String[] parts ) {
        if( parts.length < 3 ) {
            return;
        }
        result.addTimelineEvent( ts, "mount", MOUNT_STATE_LABELS.getOrDefault( parts[2], parts[2] ) );
    }

    /** SchedulerJobStart,<offsetSec>,<jobName> — a job starting in the analyze log means it's
     *  actually executing, same as the live "scheduler" lane's JOB_BUSY state, so the label is
     *  built the same way (job name + "(JOB_BUSY)") for the frontend's opacity/name handling to
     *  treat both sources identically. */
    private static void parseSchedulerJobStart( ParsedHistory result, long ts, String[] parts ) {
        if( parts.length < 3 ) {
            return;
        }
        result.addTimelineEvent( ts, "scheduler", parts[2] + " (JOB_BUSY)" );
    }

    /** SchedulerJobEnd,<offsetSec>,<jobName>,<reason> — the reason (e.g. "twilight") isn't needed
     *  here, just that the scheduler goes back to idle. */
    private static void parseSchedulerJobEnd( ParsedHistory result, long ts, String[] parts ) {
        result.addTimelineEvent( ts, "scheduler", "idle" );
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
