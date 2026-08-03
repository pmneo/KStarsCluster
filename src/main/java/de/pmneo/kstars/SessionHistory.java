package de.pmneo.kstars;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReference;

import de.pmneo.kstars.utils.EkosAnalyzeLog;

/**
 * Everything about what happened this session and where the resulting files ended up: captured
 * images, HFR/guide-error/timeline-event history for the web UI's charts, replaying that same
 * history from Ekos' own Analyze log on startup (our ring buffers are in-memory only, so a
 * restart would otherwise show a blank UI even though KStars/Ekos kept running throughout), and
 * resolving a recorded filename to wherever it actually lives on disk today (some external
 * process renames capture files after the fact — see resolveRenamedCapture). Deliberately
 * standalone (no dependency on KStarsCluster/KStarsState's live D-Bus device-status handling) —
 * callers just record events as they happen and read the history back for the status broadcast.
 */
public class SessionHistory {

    /** Recent HFR samples per train, newest last — feeds the web UI's HFR graph. */
    public static class HfrSample {
        public final long ts;
        public final double hfr;
        public final int position;

        public HfrSample( long ts, double hfr, int position ) {
            this.ts = ts;
            this.hfr = hfr;
            this.position = position;
        }
    }

    // Bumped from 300 — a full night's worth of autofocus runs (dozens of runs, ~10 points each)
    // easily exceeds that, truncating the session timeline's Focus lane to only the last few
    // hours. HfrSample is a handful of primitives (~24 bytes); even this cap is a trivial amount
    // of memory.
    private static final int HFR_HISTORY_CAP = 2000;
    public final ConcurrentHashMap<String, Deque<HfrSample>> hfrHistory = new ConcurrentHashMap<>();

    public void recordHfr( String train, double hfr, int position ) {
        recordHfr( train, System.currentTimeMillis(), hfr, position );
    }

    /** ts-taking overload — lets the startup analyze-log replay backfill history with the
     *  original recorded times instead of "now". */
    public void recordHfr( String train, long ts, double hfr, int position ) {
        Deque<HfrSample> history = hfrHistory.computeIfAbsent( train, t -> new ConcurrentLinkedDeque<>() );
        history.addLast( new HfrSample( ts, hfr, position ) );
        while( history.size() > HFR_HISTORY_CAP ) {
            history.pollFirst();
        }
    }

    /** One Guide.newAxisDelta signal per guide frame — RA/DEC guiding error, in arcsec. */
    public static class GuideDeltaSample {
        public final long ts;
        public final double ra;
        public final double de;

        public GuideDeltaSample( long ts, double ra, double de ) {
            this.ts = ts;
            this.ra = ra;
            this.de = de;
        }
    }

    // Bumped from 300 — guiding samples land every few seconds while active, so a single night
    // easily produces thousands (one real analyze log had 5544). 300 only ever showed the last
    // few minutes. GuideDeltaSample is ~24 bytes; even 20000 of them is under 500KB.
    private static final int GUIDE_HISTORY_CAP = 20_000;
    /** Guiding is one mount/one guide camera — unlike HFR/capture, never per-train. */
    public final Deque<GuideDeltaSample> guideDeltaHistory = new ConcurrentLinkedDeque<>();
    public final AtomicReference<double[]> guideSigma = new AtomicReference<>();

    public void recordGuideDelta( double ra, double de ) {
        recordGuideDelta( System.currentTimeMillis(), ra, de );
    }

    /** ts-taking overload — see recordHfr(train, ts, hfr, position). */
    public void recordGuideDelta( long ts, double ra, double de ) {
        guideDeltaHistory.addLast( new GuideDeltaSample( ts, ra, de ) );
        while( guideDeltaHistory.size() > GUIDE_HISTORY_CAP ) {
            guideDeltaHistory.pollFirst();
        }
    }

    public void recordGuideSigma( double ra, double de ) {
        guideSigma.set( new double[]{ ra, de } );
    }

    /** One entry per state change on a "lane" (guide/mount/align/scheduler) — the web UI's
     *  session timeline draws each lane as contiguous colored segments between consecutive
     *  same-lane events, so this only needs a row when something actually changed, not a sample
     *  every tick. */
    public static class TimelineEvent {
        public final long ts;
        public final String lane;
        public final String label;

        public TimelineEvent( long ts, String lane, String label ) {
            this.ts = ts;
            this.lane = lane;
            this.label = label;
        }
    }

    // Bumped from 1000 for the same reason as the other history caps below — one full night
    // across guide/mount/align/scheduler state changes can add up, and TimelineEvent is cheap.
    private static final int TIMELINE_CAP = 5000;
    public final Deque<TimelineEvent> timelineEvents = new ConcurrentLinkedDeque<>();
    /** Last recorded label per lane — recordTimelineEvent no-ops on a repeat of the same label
     *  (some Ekos status signals refire with an unchanged value) so the timeline doesn't fill up
     *  with zero-duration segments. */
    private final Map<String,String> lastTimelineLabel = new ConcurrentHashMap<>();

    public void recordTimelineEvent( String lane, String label ) {
        recordTimelineEvent( System.currentTimeMillis(), lane, label );
    }

    /** ts-taking overload — see recordHfr(train, ts, hfr, position). Used for live signals (with
     *  the current time) and for replaying events out of the Ekos analyze log at startup (with
     *  the event's own recorded time) alike. */
    public void recordTimelineEvent( long ts, String lane, String label ) {
        if( label == null || label.equals( lastTimelineLabel.put( lane, label ) ) ) {
            return;
        }

        timelineEvents.addLast( new TimelineEvent( ts, lane, label ) );
        while( timelineEvents.size() > TIMELINE_CAP ) {
            timelineEvents.pollFirst();
        }
    }

    /**
     * One entry per Capture.captureComplete signal — carries the exact saved path, so the web
     * UI's image preview never has to guess a directory or filename convention.
     */
    public static class CapturedImage {
        public final long ts;
        public final String filename;
        public final String target;
        public final String filter;
        public final double exposure;
        public final double hfr;
        public final double eccentricity;
        public final double median;
        public final double snr;
        public final int starCount;
        public final int width;
        public final int height;
        public final int type;

        public CapturedImage( long ts, Map<String,Object> m ) {
            this.ts = ts;
            this.filename = str( m, "filename" );
            this.target = inferTarget( this.filename );
            this.filter = str( m, "filter" );
            this.exposure = num( m, "exposure" );
            this.hfr = num( m, "hfr" );
            this.eccentricity = num( m, "eccentricity" );
            this.median = num( m, "median" );
            this.snr = num( m, "snr" );
            this.starCount = (int) num( m, "starCount" );
            this.width = (int) num( m, "width" );
            this.height = (int) num( m, "height" );
            this.type = (int) num( m, "type" );
        }

        /** Neither the live captureComplete signal nor the analyze log carry the target/object
         *  name directly — KStars' own default capture layout always puts it in the path
         *  (.../&lt;Target&gt;/&lt;Type&gt;/&lt;Filter&gt;/file), one level above the frame-type
         *  folder, so infer it the same way EkosAnalyzeLog.inferFrameType() infers the type. */
        private static String inferTarget( String filename ) {
            if( filename == null ) {
                return null;
            }

            String[] segments = filename.replace( '\\', '/' ).split( "/" );
            for( int i = segments.length - 1; i >= 1; i-- ) {
                String seg = segments[i].toLowerCase();
                if( seg.equals( "light" ) || seg.equals( "dark" ) || seg.equals( "bias" ) || seg.equals( "flat" ) ) {
                    return segments[i - 1];
                }
            }
            return null;
        }

        private static String str( Map<String,Object> m, String key ) {
            Object v = m.get( key );
            return v == null ? null : v.toString();
        }

        private static double num( Map<String,Object> m, String key ) {
            Object v = m.get( key );
            if( v instanceof Number ) {
                return ((Number) v).doubleValue();
            }
            try {
                return v == null ? 0 : Double.parseDouble( v.toString() );
            }
            catch( Throwable t ) {
                return 0;
            }
        }
    }

    // Bumped from 50 — a full night across several targets/filters (e.g. the 960-frame job seen
    // in one real session) blew straight through that, which is exactly why the image strip and
    // session timeline's Capture lane only ever showed frames from around midnight onward: the
    // ring buffer had already evicted everything captured earlier that evening. CapturedImage is
    // a handful of primitives plus a few short strings; even 2000 of them is a trivial amount of
    // memory.
    private static final int CAPTURED_IMAGES_CAP = 2000;
    public final ConcurrentHashMap<String, Deque<CapturedImage>> capturedImages = new ConcurrentHashMap<>();

    public void recordCapturedImage( String train, Map<String,Object> metadata ) {
        recordCapturedImage( train, System.currentTimeMillis(), metadata );
    }

    /** ts-taking overload — see recordHfr(train, ts, hfr, position). */
    public void recordCapturedImage( String train, long ts, Map<String,Object> metadata ) {
        Deque<CapturedImage> history = capturedImages.computeIfAbsent( train, t -> new ConcurrentLinkedDeque<>() );
        history.addLast( new CapturedImage( ts, metadata ) );
        while( history.size() > CAPTURED_IMAGES_CAP ) {
            history.pollFirst();
        }
    }

    /** Recent captures for one train, newest first — sourced from Capture.captureComplete, not
     *  filesystem scanning. Skips any entry whose file is gone for good (see
     *  {@link #resolveRenamedCapture}) — no point showing a preview that 404s. */
    public List<Map<String,Object>> listRecentImages( String train ) {
        Deque<CapturedImage> history = capturedImages.getOrDefault( train, new ConcurrentLinkedDeque<>() );

        List<Map<String,Object>> res = new ArrayList<>();
        for( CapturedImage img : history ) {
            if( resolveRenamedCapture( img.filename ) == null ) {
                continue;
            }

            Map<String,Object> entry = new LinkedHashMap<>();
            entry.put( "ts", img.ts );
            entry.put( "filename", img.filename );
            entry.put( "target", img.target );
            entry.put( "filter", img.filter );
            entry.put( "exposure", img.exposure );
            entry.put( "hfr", img.hfr );
            entry.put( "eccentricity", img.eccentricity );
            entry.put( "median", img.median );
            entry.put( "starCount", img.starCount );
            entry.put( "width", img.width );
            entry.put( "height", img.height );
            entry.put( "type", img.type );
            res.add( entry );
        }
        Collections.reverse( res );
        return res;
    }

    /**
     * Refuses to render anything that wasn't actually reported by a captureComplete signal —
     * the "file" query param on ImageServlet's thumb/autostretch actions is client-supplied, so
     * this is the only thing standing between it and an arbitrary local file read. The identity
     * check is always against the original recorded filename (img.filename), matching what
     * listRecentImages() hands the client; resolveRenamedCapture then finds wherever that file
     * actually lives now.
     */
    public File resolveKnownCapturedFile( String filename ) {
        for( Deque<CapturedImage> history : capturedImages.values() ) {
            for( CapturedImage img : history ) {
                if( filename.equals( img.filename ) ) {
                    return resolveRenamedCapture( img.filename );
                }
            }
        }
        return null;
    }

    /** Some external process renames capture files after the fact, tagging them with the imaging
     *  train and rotator angle they were captured at — e.g.
     *  ".../NGC_1333_Light_L_180_secs__005.fits" becomes "..._005_T-ED100B2_ROT_160.0.fits"
     *  (confirmed against real files on disk: renamed and still-plain files coexist in the same
     *  folder, so this evidently runs as a periodic batch job, not immediately after capture).
     *  Without this, every renamed file would silently 404 forever — captureComplete/the analyze
     *  log only ever recorded the original name.
     *
     *  Only the not-found fallback (a directory listing) is cached, keyed by the original
     *  filename: the exact-path case is a cheap stat, freshly re-checked every call, so a file
     *  that's still plain today keeps resolving correctly right up until it actually gets
     *  renamed. Once that happens the answer is stable forever (files don't get un-renamed),
     *  which is what makes caching *that* case safe — otherwise every second's status broadcast
     *  would re-list the directory for every renamed-away image, forever. */
    private final Map<String, Optional<File>> renamedCaptureCache = new ConcurrentHashMap<>();

    private File resolveRenamedCapture( String originalFilename ) {
        File original = new File( originalFilename );
        if( original.isFile() ) {
            return original;
        }

        return renamedCaptureCache.computeIfAbsent( originalFilename, f -> {
            File dir = original.getParentFile();
            if( dir == null ) {
                return Optional.empty();
            }

            String name = original.getName();
            int dot = name.lastIndexOf( '.' );
            String stem = dot >= 0 ? name.substring( 0, dot ) : name;
            String ext = dot >= 0 ? name.substring( dot ) : "";

            File[] renamed = dir.listFiles( ( d, n ) -> n.startsWith( stem + "_T-" ) && n.endsWith( ext ) );
            return renamed != null && renamed.length > 0 ? Optional.of( renamed[0] ) : Optional.empty();
        } ).orElse( null );
    }

    /** Summary of what got replayed, for the caller to log — kept separate from actually logging
     *  it so this class doesn't need to depend on WithLogging's conventions. */
    public record RestoreSummary( int images, int hfrSamples, int guideSamples, int timelineEvents ) {}

    /**
     * Our capture/HFR/guide ring buffers only ever get filled by live D-Bus signals, so every
     * restart of this server starts them empty even though KStars/Ekos itself kept running the
     * whole time. Ekos' own Analyze module already logs exactly this history to disk (session by
     * session) — replay the latest file once at startup so the web UI isn't blank right after a
     * restart.
     */
    public RestoreSummary restoreFromAnalyzeLog( File analyzeDir ) throws IOException {
        // Same caps as the ring buffers these feed (50 images, 300 HFR/guide samples) — the
        // most recent file alone is often a short guiding-only test with zero captures, so
        // this walks backward through up to 10 files merging history until met.
        EkosAnalyzeLog.ParsedHistory parsed = EkosAnalyzeLog.parseRecent( analyzeDir, 50, 300, 300, 10 );

        // Recent KStars versions tag CaptureComplete/AutofocusComplete rows with the train
        // they belong to; older rows (and thus older log files) come back keyed under
        // EkosAnalyzeLog's DEFAULT_TRAIN ("Primary") — same train every such row was
        // attributed to before this per-train restore existed.
        for( var entry : parsed.images.entrySet() ) {
            String train = entry.getKey();
            for( CapturedImage img : entry.getValue() ) {
                Map<String,Object> m = new LinkedHashMap<>();
                m.put( "filename", img.filename );
                m.put( "filter", img.filter );
                m.put( "exposure", img.exposure );
                m.put( "hfr", img.hfr );
                m.put( "type", img.type );
                recordCapturedImage( train, img.ts, m );
            }
        }
        for( var entry : parsed.hfrSamples.entrySet() ) {
            String train = entry.getKey();
            for( HfrSample s : entry.getValue() ) {
                recordHfr( train, s.ts, s.hfr, s.position );
            }
        }
        for( GuideDeltaSample s : parsed.guideSamples ) {
            recordGuideDelta( s.ts, s.ra, s.de );
        }
        for( TimelineEvent e : parsed.timelineEvents ) {
            recordTimelineEvent( e.ts, e.lane, e.label );
        }

        return new RestoreSummary( parsed.totalImages(), parsed.totalHfrSamples(), parsed.guideSamples.size(), parsed.timelineEvents.size() );
    }

    /** Folded into the status push instead of separate polling loops for the HFR chart and image
     *  strip — one WebSocket, not several independently-polled REST endpoints. */
    public Map<String, Object> fillStatus( Map<String, Object> res ) {
        res.put( "hfrHistory", hfrHistory );

        Map<String, List<Map<String,Object>>> images = new LinkedHashMap<>();
        for( String train : capturedImages.keySet() ) {
            images.put( train, listRecentImages( train ) );
        }
        res.put( "images", images );

        res.put( "guideDeltaHistory", guideDeltaHistory );
        res.put( "timelineEvents", timelineEvents );

        double[] sigma = guideSigma.get();
        if( sigma != null ) {
            Map<String,Object> guideSigmaMap = new LinkedHashMap<>();
            guideSigmaMap.put( "ra", sigma[0] );
            guideSigmaMap.put( "de", sigma[1] );
            res.put( "guideSigma", guideSigmaMap );
        }

        return res;
    }
}
