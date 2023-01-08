package de.pmneo.kstars.utils;

import java.text.NumberFormat;
import java.util.Locale;

public class RaDecUtils {
	
	public static void main(String[] args) {
		System.out.println( degreesToRA( 76.1528467995962 ));
		System.out.println( degreesToDEC(-7.371022992756764 ));
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
        String d = Double.toString(val);

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
