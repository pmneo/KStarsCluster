package de.pmneo.kstars.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

import de.pmneo.kstars.SimpleLogger;

/**
 * Resolves which physical device (camera/focuser) is currently assigned to which Ekos Optical
 * Train ("Primary"/"Secondary"/...) — straight from KStars' own on-disk config and SQLite
 * database, no D-Bus/Ekos required. This is what lets KStarsCluster correct device-keyed
 * Analyze-log history (see SessionHistory.remapDeviceKeysToTrains) even when it (re)starts
 * before KStars/Ekos is running at all — same "read straight off disk" fallback already relied
 * on for the scheduler .esl file and (see ArtificialHorizon, same userdb.sqlite) the artificial
 * horizon data.
 */
public final class OpticalTrainDb {

    private OpticalTrainDb() {}

    /** {@code <device name> -> <train name>} for every camera/focuser assigned to a train in
     *  KStars' currently ACTIVE equipment profile (resolved from ~/.config/kstarsrc's own
     *  [Ekos]/profile= setting — the same profile Ekos itself loads on startup, needed because
     *  userdb.sqlite's opticaltrains table accumulates rows from every profile ever configured,
     *  including old/stale ones that can reuse the same train names with different devices).
     *  Empty (never null) if kstarsrc, the userdb, or either lookup step fails — this is a
     *  best-effort refinement, not something callers should treat as fatal. */
    public static Map<String,String> readDeviceToTrain() {
        String profileName = readActiveProfileName();
        if( profileName == null ) {
            return Map.of();
        }

        File dbFile = new File( System.getProperty( "user.home" ) + "/.local/share/kstars/userdb.sqlite" );
        if( !dbFile.isFile() ) {
            return Map.of();
        }

        // Read-only + immutable: this file is KStars' own live database, still open by a running
        // KStars process most of the time — never write to it, and "immutable=1" lets SQLite skip
        // the write-ahead-log machinery entirely since we only ever take one read snapshot here.
        String url = "jdbc:sqlite:file:" + dbFile.getAbsolutePath() + "?immutable=1";
        Map<String,String> deviceToTrain = new HashMap<>();
        try( Connection conn = DriverManager.getConnection( url ) ) {
            Integer profileId = null;
            try( PreparedStatement st = conn.prepareStatement( "SELECT id FROM profile WHERE name = ?" ) ) {
                st.setString( 1, profileName );
                try( ResultSet rs = st.executeQuery() ) {
                    if( rs.next() ) {
                        profileId = rs.getInt( "id" );
                    }
                }
            }
            if( profileId == null ) {
                return Map.of();
            }

            try( PreparedStatement st = conn.prepareStatement(
                    "SELECT name, camera, focuser FROM opticaltrains WHERE profile = ?" ) ) {
                st.setInt( 1, profileId );
                try( ResultSet rs = st.executeQuery() ) {
                    while( rs.next() ) {
                        String train = rs.getString( "name" );
                        String camera = rs.getString( "camera" );
                        String focuser = rs.getString( "focuser" );
                        if( isConfigured( camera ) ) {
                            deviceToTrain.put( camera, train );
                        }
                        if( isConfigured( focuser ) ) {
                            deviceToTrain.put( focuser, train );
                        }
                    }
                }
            }
        }
        catch( Throwable t ) {
            SimpleLogger.getLogger().logError( "Failed to read optical train device assignments from " + dbFile, t );
            return Map.of();
        }

        return deviceToTrain;
    }

    /** KStars stores an unconfigured optical-train slot (e.g. the "Guider" train's own focuser
     *  column, confirmed against this observatory's real profile) as the literal placeholder
     *  string "--", not NULL/empty — a plain blank check alone doesn't catch it. */
    private static boolean isConfigured( String device ) {
        return device != null && !device.isBlank() && !device.equals( "--" );
    }

    /** Reads the [Ekos]/profile= key from ~/.config/kstarsrc — the active equipment profile's
     *  NAME (its numeric id only exists in userdb.sqlite's own profile table, resolved
     *  separately above), the same setting KStars itself reads to pick which profile to load on
     *  startup. Plain INI line scan rather than a real parser: this file has no library already
     *  pulled in for it, and the format here is simple enough (no continuation lines, no
     *  quoting) not to need one. */
    private static String readActiveProfileName() {
        File configFile = new File( System.getProperty( "user.home" ) + "/.config/kstarsrc" );
        if( !configFile.isFile() ) {
            return null;
        }

        try( BufferedReader reader = new BufferedReader( new FileReader( configFile ) ) ) {
            String line;
            String section = null;
            while( ( line = reader.readLine() ) != null ) {
                line = line.strip();
                if( line.startsWith( "[" ) && line.endsWith( "]" ) ) {
                    section = line;
                }
                else if( "[Ekos]".equals( section ) && line.startsWith( "profile=" ) ) {
                    return line.substring( "profile=".length() ).strip();
                }
            }
        }
        catch( IOException e ) {
            SimpleLogger.getLogger().logError( "Failed to read active Ekos profile from " + configFile, e );
        }

        return null;
    }
}
