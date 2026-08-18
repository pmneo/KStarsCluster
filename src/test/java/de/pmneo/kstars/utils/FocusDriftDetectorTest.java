package de.pmneo.kstars.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Exercises FocusDriftDetector against real recorded autofocus solutions, copied from
 * ~/.local/share/kstars/analyze/*.analyze into src/test/resources/focus-drift: one night where
 * the Primary train's focuser (DeepSkyDad AF3 A) held a stable position, and the very next night
 * where it drifted inward ~4000 ticks while HFR degraded — see the "Auto-detect and correct
 * focuser drift" plan for how the thresholds here were derived from this exact data.
 */
class FocusDriftDetectorTest {

    private static final int TICKS_THRESHOLD = 1000; // matches KStarsCluster's default focusDriftTicks

    @Test
    void stableNightNeverTriggers() throws IOException {
        Map<String, List<FocusDriftDetector.Sample>> byTrain = loadSolutions( "focus-drift/ekos-2026-08-13-good-night.analyze" );

        assertNotNull( byTrain.get( "Primary" ) );
        assertFalse( everTriggers( byTrain.get( "Primary" ) ),
                "a healthy night's Primary solutions must not be flagged as drift" );
        assertFalse( everTriggers( byTrain.get( "Secondary" ) ),
                "Secondary was stable too and must not be flagged" );
    }

    @Test
    void driftingNightIsDetectedAndPointsBackToPreDriftPosition() throws IOException {
        Map<String, List<FocusDriftDetector.Sample>> byTrain = loadSolutions( "focus-drift/ekos-2026-08-14-drift-night.analyze" );

        List<FocusDriftDetector.Sample> primary = byTrain.get( "Primary" );
        FocusDriftDetector.Result triggered = firstTrigger( primary );

        assertNotNull( triggered, "the recorded overnight inward drift on Primary must be detected" );
        assertTrue( triggered.netDrift < 0, "this recorded night drifted inward (position decreasing), was " + triggered.netDrift );
        assertTrue( Math.abs( triggered.netDrift ) >= TICKS_THRESHOLD );
        // referencePosition is the oldest position in the triggering window — i.e. a position
        // this focuser actually held before the drift, not an extrapolated/invented value.
        assertTrue( primary.stream().anyMatch( s -> s.position == triggered.referencePosition ) );

        // Secondary's focuser stayed rock-solid the same night — must not false-positive.
        assertFalse( everTriggers( byTrain.get( "Secondary" ) ),
                "Secondary was fine the same night and must not be flagged" );
    }

    /** Mirrors KStarsCluster.checkFocusDrift()'s rolling window exactly: cap at DEFAULT_WINDOW,
     *  evaluate after every new solution, stop at the first confirmed drift. */
    private static FocusDriftDetector.Result firstTrigger( List<FocusDriftDetector.Sample> solutions ) {
        if( solutions == null ) {
            return null;
        }
        Deque<FocusDriftDetector.Sample> window = new ArrayDeque<>();
        for( FocusDriftDetector.Sample s : solutions ) {
            window.addLast( s );
            while( window.size() > FocusDriftDetector.DEFAULT_WINDOW ) {
                window.pollFirst();
            }
            if( window.size() < FocusDriftDetector.DEFAULT_WINDOW ) {
                continue;
            }
            FocusDriftDetector.Result result = FocusDriftDetector.evaluate(
                    new ArrayList<>( window ), TICKS_THRESHOLD, FocusDriftDetector.DEFAULT_HFR_BAD );
            if( result.driftDetected ) {
                return result;
            }
        }
        return null;
    }

    private static boolean everTriggers( List<FocusDriftDetector.Sample> solutions ) {
        return firstTrigger( solutions ) != null;
    }

    /**
     * Extracts one solution sample per AutofocusComplete row — its final position|hfr|weight|flag
     * quadruple, confirmed to match the row's own trailing "Solution: N" text — keyed by train
     * (the row's last field). Deliberately separate from EkosAnalyzeLog.parseAutofocusComplete,
     * which instead flattens *every* V-curve point from *every* row into one per-train list
     * (right for backfilling the HFR chart, wrong for reconstructing "one result per completed
     * run" the way live checkFocusDrift() sees it).
     */
    private static Map<String, List<FocusDriftDetector.Sample>> loadSolutions( String resourcePath ) throws IOException {
        Map<String, List<FocusDriftDetector.Sample>> result = new LinkedHashMap<>();

        try( InputStream in = FocusDriftDetectorTest.class.getClassLoader().getResourceAsStream( resourcePath ) ) {
            assertNotNull( in, "missing test fixture: " + resourcePath );
            try( BufferedReader r = new BufferedReader( new InputStreamReader( in, StandardCharsets.UTF_8 ) ) ) {
                String line;
                long ts = 0;
                while( (line = r.readLine()) != null ) {
                    if( !line.startsWith( "AutofocusComplete" ) ) {
                        continue;
                    }
                    String[] parts = line.split( ",", -1 );
                    if( parts.length < 11 ) {
                        continue; // no train field — not expected in this fixture, skip defensively
                    }

                    String train = parts[parts.length - 1];
                    String[] points = parts[6].split( "\\|" );
                    int last = points.length - 4;
                    int position = (int) Double.parseDouble( points[last] );
                    double hfr = Double.parseDouble( points[last + 1] );

                    result.computeIfAbsent( train, t -> new ArrayList<>() ).add( new FocusDriftDetector.Sample( ts++, position, hfr ) );
                }
            }
        }

        return result;
    }
}
