package de.pmneo.kstars.utils;

import java.util.Calendar;

/**
 * Conversion between horizontal (Alt/Az) and equatorial (RA/Dec) coordinates.
 */
public final class Coordinates {

    private Coordinates() {
        // Prevent instantiation of this utility class
    }

    /**
     * @param time         the moment for which to compute the sidereal time
     * @param longitudeDeg observer longitude in degrees, East positive
     * @return the local sidereal time in hours [0, 24)
     */
    public static double getLocalSiderealTime(Calendar time, double longitudeDeg) {
        double julianDate = SunriseSunset.getJulianDate(time);
        double daysSinceEpoch = julianDate - 2451545.0;
        double t = daysSinceEpoch / 36525.0;

        double gmstDeg = 280.46061837 + 360.98564736629 * daysSinceEpoch
                + 0.000387933 * t * t - (t * t * t) / 38710000.0;

        double lstDeg = ((gmstDeg + longitudeDeg) % 360.0 + 360.0) % 360.0;

        return lstDeg / 15.0;
    }

    /**
     * Converts horizontal coordinates (Alt/Az, azimuth measured from North going through East)
     * to equatorial coordinates for the given observer and time.
     *
     * @param altitudeDeg  altitude above the horizon in degrees
     * @param azimuthDeg   azimuth in degrees, measured from North (0) through East (90)
     * @param latitudeDeg  observer latitude in degrees
     * @param longitudeDeg observer longitude in degrees, East positive
     * @param time         the moment for which to compute the equatorial coordinates
     * @return a two element array: { RA in hours, Dec in degrees }
     */
    public static double[] altAzToRaDec(double altitudeDeg, double azimuthDeg, double latitudeDeg, double longitudeDeg, Calendar time) {
        double alt = Math.toRadians(altitudeDeg);
        double az = Math.toRadians(azimuthDeg);
        double lat = Math.toRadians(latitudeDeg);

        double sinDec = Math.sin(alt) * Math.sin(lat) + Math.cos(alt) * Math.cos(lat) * Math.cos(az);
        double dec = Math.asin(sinDec);

        double cosHourAngle = (Math.sin(alt) - Math.sin(lat) * sinDec) / (Math.cos(lat) * Math.cos(dec));
        cosHourAngle = Math.max(-1.0, Math.min(1.0, cosHourAngle));
        double hourAngleDeg = Math.toDegrees(Math.acos(cosHourAngle));
        if (Math.sin(az) > 0) {
            hourAngleDeg = 360.0 - hourAngleDeg;
        }

        double lst = getLocalSiderealTime(time, longitudeDeg);
        double raHours = ((lst - hourAngleDeg / 15.0) % 24.0 + 24.0) % 24.0;

        return new double[] { raHours, Math.toDegrees(dec) };
    }
}
