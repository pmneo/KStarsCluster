package de.pmneo.kstars.utils;

import java.util.List;

/**
 * Pure decision logic for detecting a focuser whose autofocus solution keeps moving the same
 * direction across consecutive runs instead of settling (bad temp-comp coefficient, backlash
 * runaway, ...). Kept free of D-Bus/SessionHistory/KStarsCluster dependencies so it can be unit
 * tested directly against real recorded autofocus data — see KStarsCluster.checkFocusDrift() for
 * the live caller that feeds it from Focus.newHFR/SessionHistory.hfrHistory.
 *
 * Thresholds were derived from one real bad night (a DeepSkyDad AF3 drifting inward ~4000 ticks
 * over a session while HFR degraded from ~1.0 to ~6.0) and a preceding good night (stable within
 * ~700 ticks, HFR ~1.0-1.3) — see FocusDriftDetectorTest for the recorded fixtures.
 */
public class FocusDriftDetector {

    public static final int DEFAULT_WINDOW = 6;
    public static final double DEFAULT_HFR_BAD = 1.5; // same "bad focus" cutoff FocusAnalyser already uses
    public static final double MIN_CONSISTENT_FRACTION = 0.8;

    public static class Sample {
        public final long ts;
        public final int position;
        public final double hfr;

        public Sample( long ts, int position, double hfr ) {
            this.ts = ts;
            this.position = position;
            this.hfr = hfr;
        }
    }

    public static class Result {
        public final boolean driftDetected;
        public final int netDrift;
        public final double consistentFraction;
        /** The oldest position in the evaluated window — the "last known good" position from
         *  before the drift run began, used as the corrective target. */
        public final int referencePosition;

        Result( boolean driftDetected, int netDrift, double consistentFraction, int referencePosition ) {
            this.driftDetected = driftDetected;
            this.netDrift = netDrift;
            this.consistentFraction = consistentFraction;
            this.referencePosition = referencePosition;
        }
    }

    /**
     * @param window ordered oldest-first, newest-last; should contain exactly the trailing
     *               window of completed-run solutions to consider (e.g. DEFAULT_WINDOW entries).
     * @param ticksThreshold minimum |net drift| across the window before it's considered real
     * @param hfrBadThreshold the latest run's HFR must exceed this for drift to be confirmed —
     *                        without it, a focuser that moved a lot but is still sharp (e.g. a
     *                        legitimate large per-filter offset) would false-positive
     */
    public static Result evaluate( List<Sample> window, int ticksThreshold, double hfrBadThreshold ) {
        if( window.size() < 2 ) {
            return new Result( false, 0, 0, window.isEmpty() ? 0 : window.get( 0 ).position );
        }

        Sample first = window.get( 0 );
        Sample latest = window.get( window.size() - 1 );
        int netDrift = latest.position - first.position;

        if( netDrift == 0 ) {
            return new Result( false, 0, 0, first.position );
        }

        int consistentSteps = 0;
        for( int i = 1; i < window.size(); i++ ) {
            int delta = window.get( i ).position - window.get( i - 1 ).position;
            if( Integer.signum( delta ) == Integer.signum( netDrift ) ) {
                consistentSteps++;
            }
        }
        double consistentFraction = (double) consistentSteps / ( window.size() - 1 );

        boolean driftDetected = Math.abs( netDrift ) >= ticksThreshold
                && consistentFraction >= MIN_CONSISTENT_FRACTION
                && latest.hfr > hfrBadThreshold;

        return new Result( driftDetected, netDrift, consistentFraction, first.position );
    }
}
