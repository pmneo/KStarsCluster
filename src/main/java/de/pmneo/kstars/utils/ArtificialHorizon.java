package de.pmneo.kstars.utils;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.pmneo.kstars.SimpleLogger;

/**
 * Reads the user's own KStars "artificial horizon" regions (Settings > Ekos > Scheduler >
 * Artificial Horizon — physical obstructions like piers/trees/buildings the user traced out by
 * hand) straight from KStars' own SQLite database, read-only. KStars doesn't expose this over
 * D-Bus, and there's no other file format for it (unlike Terrain, which is a plain image path in
 * kstarsrc) — the "horizons" table lists one row per user-defined region (its own data table name
 * plus a display label and "enabled" flag, mirroring the Scheduler's own per-region checkbox), and
 * each region's own table ("horizon_1", "horizon_2", ...) holds its (Az, Alt) polygon points,
 * already a closed loop back down to the horizon baseline on both ends (confirmed against a real
 * profile) — so each region can be drawn as-is, no extra closing needed.
 */
public final class ArtificialHorizon {

    private ArtificialHorizon() {}

    public record Point( double az, double alt ) {}

    public record Region( String label, List<Point> points ) {}

    /** Empty (never null) if the db/table is missing or unreadable — an artificial horizon is an
     *  optional refinement on top of the always-available flat geometric horizon, not something
     *  the caller needs to treat as an error. */
    public static List<Region> readEnabledRegions() {
        File dbFile = new File( System.getProperty( "user.home" ) + "/.local/share/kstars/userdb.sqlite" );
        if( !dbFile.isFile() ) {
            return List.of();
        }

        // Read-only + immutable: this file is KStars' own live database, still open by a running
        // KStars process most of the time — never write to it, and "immutable=1" lets SQLite skip
        // the write-ahead-log machinery entirely since we only ever take one read snapshot here.
        String url = "jdbc:sqlite:file:" + dbFile.getAbsolutePath() + "?immutable=1";

        List<Region> regions = new ArrayList<>();
        try( Connection conn = DriverManager.getConnection( url ) ) {
            Map<String,String> enabledTables = new LinkedHashMap<>();
            try( Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery( "SELECT name, label FROM horizons WHERE enabled != 0" ) ) {
                while( rs.next() ) {
                    enabledTables.put( rs.getString( "name" ), rs.getString( "label" ) );
                }
            }

            for( Map.Entry<String,String> entry : enabledTables.entrySet() ) {
                List<Point> points = new ArrayList<>();
                // The table name comes from KStars' own "horizons" row, never client input, but
                // it can't be a query parameter (table names aren't) — validate it looks like the
                // "horizon_<n>" convention KStars itself always uses before splicing it into SQL.
                String table = entry.getKey();
                if( !table.matches( "horizon_[0-9]+" ) ) {
                    continue;
                }
                try( Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery( "SELECT Az, Alt FROM " + table + " ORDER BY rowid" ) ) {
                    while( rs.next() ) {
                        points.add( new Point( rs.getDouble( "Az" ), rs.getDouble( "Alt" ) ) );
                    }
                }
                if( !points.isEmpty() ) {
                    regions.add( new Region( entry.getValue(), points ) );
                }
            }
        }
        catch( Throwable t ) {
            SimpleLogger.getLogger().logError( "Failed to read artificial horizon from " + dbFile, t );
            return List.of();
        }

        return regions;
    }
}
