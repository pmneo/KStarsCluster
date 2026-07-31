package de.pmneo.kstars;

import de.pmneo.kstars.utils.SunriseSunset;
import org.apache.commons.configuration2.INIConfiguration;
import java.io.FileReader;
import java.util.Calendar;

public class KStarsConfig extends WithLogging {
    private INIConfiguration config;

    public KStarsConfig() {
        super();
        try {
            config = new INIConfiguration();
            config.read(new FileReader(System.getProperty("user.home") + "/.config/kstarsrc"));
        }
        catch( Throwable t ) {
            logError( "Failed to load kstars config", t );
        }
    }

    public double getLatitude() {
        return config.getDouble( "Location.Latitude", -999 );
    }

    public double getLongitude() {
        return config.getDouble( "Location.Longitude", -999 );
    }

    /** Absolute path to the user's "Terrain" panorama image (View > Show Terrain in KStars) —
     *  null if the feature was never configured. See TerrainSourceCorrectAz/Alt for the two
     *  alignment offsets KStars applies when sampling it, needed to reproduce the same mapping. */
    public String getTerrainSource() {
        String value = config.getString( "Terrain.TerrainSource", null );
        return ( value == null || value.isBlank() ) ? null : value;
    }

    public double getTerrainCorrectAz() {
        return config.getDouble( "Terrain.TerrainSourceCorrectAz", 0 );
    }

    public double getTerrainCorrectAlt() {
        return config.getDouble( "Terrain.TerrainSourceCorrectAlt", 0 );
    }

    public Calendar[] getCivilTwilight() {
        try {
            double longitude = config.getDouble("Location.Longitude", -999 );
            double latitude = config.getDouble( "Location.Latitude", -999 );

            Calendar now = Calendar.getInstance();
            Calendar[] range = SunriseSunset.getCivilTwilight( now, latitude, longitude );
            if( range == null ) {
                return new Calendar[] { null, null, now };
            }
            else {
                return new Calendar[] { range[0], range[1], now };
            }
        }
        catch( Throwable t ) {
            logError( "Failed to calc twighlight", t);
            return null;
        }
    }

    public boolean isNight( ) {
        return isNight( getCivilTwilight() );
    }
    public boolean isNight( Calendar[] range ) {
        if( range[0] == null ) {
            return true;
        }
        Calendar now = range[2];
        if( now.getTimeInMillis() < range[0].getTimeInMillis() || range[1].getTimeInMillis() < now.getTimeInMillis() ) {
            //logMessage( "Twighlight: " + start.getTime() + " to " + end.getTime() + " at ("+latitude + "/" + longitude+")" );
            return true;
        }
        else {
            return false;
        }
    }

}
