package de.pmneo.kstars.utils;

import java.text.NumberFormat;
import java.util.Locale;

public class RaDecUtils {

	public static void main(String[] args) {
		System.out.println( degreesToRA( 76.1528467995962 ));
		System.out.println( degreesToDEC(-7.371022992756764 ));
	}

	private static final double JD_UNIX_EPOCH = 2440587.5;
	private static final double JD_J2000 = 2451545.0;

	/**
	 * Precesses apparent-place (JNow) equatorial coordinates back to the J2000/ICRS mean equinox
	 * — IAU 1976 precession (Meeus, "Astronomical Algorithms" ch. 21), inverted. Needed because
	 * Mount.equatorialCoords reports JNow (what the mount actually points at right now), while
	 * catalog/scheduler targets and Aladin's own sky surveys are all J2000 — left unconverted,
	 * the two disagree by tens of arcminutes and grow further apart every year (~17' already for
	 * a dec=60 target in 2026), even though the mount is genuinely pointing at the right place.
	 *
	 * @param raHours  JNow right ascension, in decimal hours
	 * @param decDeg   JNow declination, in decimal degrees
	 * @param epochMillis  the moment the JNow reading was taken (System.currentTimeMillis())
	 * @return {raHours, decDeg} in J2000
	 */
	public static double[] jNowToJ2000( double raHours, double decDeg, long epochMillis ) {
		double jd = epochMillis / 86400000.0 + JD_UNIX_EPOCH;
		double t = (jd - JD_J2000) / 36525.0;

		double zeta0 = Math.toRadians( (2306.2181 * t + 0.30188 * t * t + 0.017998 * t * t * t) / 3600.0 );
		double z = Math.toRadians( (2306.2181 * t + 1.09468 * t * t + 0.018203 * t * t * t) / 3600.0 );
		double theta = Math.toRadians( (2004.3109 * t - 0.42665 * t * t - 0.041833 * t * t * t) / 3600.0 );

		double ra = Math.toRadians( raHours * 15.0 );
		double dec = Math.toRadians( decDeg );

		double cosDec = Math.cos( dec );
		double a = cosDec * Math.sin( ra - z );
		double b = Math.cos( theta ) * cosDec * Math.cos( ra - z ) + Math.sin( theta ) * Math.sin( dec );
		double c = -Math.sin( theta ) * cosDec * Math.cos( ra - z ) + Math.cos( theta ) * Math.sin( dec );

		double ra0 = Math.atan2( a, b ) - zeta0;
		double dec0 = Math.asin( c );

		double ra0Hours = Math.toDegrees( ra0 ) / 15.0;
		while( ra0Hours < 0 ) ra0Hours += 24.0;
		while( ra0Hours >= 24.0 ) ra0Hours -= 24.0;

		return new double[]{ ra0Hours, Math.toDegrees( dec0 ) };
	}

    private static NumberFormat raFormat = NumberFormat.getInstance( Locale.US );
    private static NumberFormat decFormat = NumberFormat.getInstance( Locale.US );

    public static String[] degreesToSexigessimal(double ra, double dec) {
        return new String[] { degreesToRA(ra), degreesToDEC(dec) };
    }//from  w ww .  j a va  2  s  .  com

    public static String degreesToRA(double val) {
        // raneg reduction to [0.0,360.0)
        while (val < 0.0) {
            val += 360.0;
        }
        while (val >= 360.0) {
            val -= 360.0;
        }

        // 24 hours/360 degrees = 15 deg/hour
        int h = (int) (val / 15.0);
        val -= h * 15.0;
        // 15 deg/hour == 0.25 deg/min == 4 min/deg
        int m = (int) (val * 4.0);
        val -= m / 4.0;
        // 4 min/deg == 240 sec/deg
        val *= 240.0;

        String hh = Integer.toString(h);
        String mm = Integer.toString(m);

        if (h < 10) {
            hh = "0" + h;
        }
        if (m < 10) {
            mm = "0" + m;
        }

        return (hh + ":" + mm + ":") + raFormat.format(val);
    }

    public static String degreesToDEC(double val) {
        if (val < -90.0 || val > 90.0) {
            throw new IllegalArgumentException("value " + val + " out of bounds: [-90.0, 90.0]");
        }
        String sign = "+";
        if (val < 0.0) {
            sign = "-";
            val *= -1.0;
        }
        int deg = (int) (val);
        val -= deg;
        // 60 min/deg
        int m = (int) (val * 60.0);
        val -= m / 60.0;
        // 60 sec/min == 3600 sec/deg
        val *= 3600.0;
        //String d = Double.toString(val);

        String degs = Integer.toString(deg);
        if (deg < 10) {
            degs = "0" + degs;
        }
        String min = Integer.toString(m);
        if (m < 10) {
            min = "0" + m;
        }

        String s = sign + degs + ":" + min + ":";

        return s + decFormat.format(val);
    }
}
