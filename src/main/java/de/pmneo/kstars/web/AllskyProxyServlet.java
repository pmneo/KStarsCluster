package de.pmneo.kstars.web;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.google.gson.Gson;

import de.pmneo.kstars.KStarsCluster;
import de.pmneo.kstars.KStarsConfig;
import de.pmneo.kstars.utils.AllskyClient;
import de.pmneo.kstars.utils.SunriseSunset;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Proxies indi-allsky's (https://github.com/aaronwmorris/indi-allsky) own small JSON API for a
 * quick "how clear is the sky right now" widget, and for the Session Timeline's click/hover-to-
 * compare feature (nearest allsky shot to a given capture's own timestamp) — star count tends to
 * drop sharply under cloud, an independent cross-check next to the weather station. One
 * AllskyClient per physical camera/host — there can be more than one indi-allsky install on the
 * LAN (e.g. one per site); the "cam" query param on every action but /cameras selects which.
 *
 * Mostly self-contained — no dependency on live cluster state (unlike ImageServlet, which needs
 * KStarsCluster's own captured-image history), so most of this could be lifted wholesale into the
 * standalone Sky Map/AstroBin widget this app is being prepped to spin out into its own repo. The
 * one exception is /keogram, which needs the observatory's lat/long (static config, not live
 * status) to place a night's keogram on the Session Timeline's time axis — see init() below.
 */
public class AllskyProxyServlet extends HttpServlet {

    private KStarsConfig config;

    @Override
    public void init( ServletConfig servletConfig ) throws ServletException {
        super.init( servletConfig );
        KStarsCluster cluster = (KStarsCluster) getServletContext().getAttribute( "cluster" );
        config = cluster.config;
    }

    /** showDetails: whether star count/history are meaningful for this camera — false for one
     *  pointed at the dome interior rather than the sky (no point charting "stars" there). */
    private record AllskyCamera( String label, boolean showDetails, AllskyClient client ) {}

    /** One indi-allsky install per site — "cam" query param selects which. */
    private final Map<String, AllskyCamera> cameras = Map.of(
            "default", new AllskyCamera( "Allsky", true, new AllskyClient( "192.168.0.109", 1 ) ),
            "obsy", new AllskyCamera( "Allsky (Obsy)", false, new AllskyClient( "192.168.0.145", 2 ) )
    );

    private final Gson gson = new Gson();

    @Override
    protected void doGet( HttpServletRequest req, HttpServletResponse resp ) throws IOException {
        String pathInfo = req.getPathInfo();
        if( pathInfo == null ) {
            resp.sendError( HttpServletResponse.SC_NOT_FOUND );
            return;
        }

        if( "/cameras".equals( pathInfo ) ) {
            Map<String,Object> res = new LinkedHashMap<>();
            cameras.forEach( ( id, cam ) -> {
                Map<String,Object> info = new LinkedHashMap<>();
                info.put( "label", cam.label() );
                info.put( "showDetails", cam.showDetails() );
                res.put( id, info );
            } );
            writeJson( resp, res );
            return;
        }

        String camId = req.getParameter( "cam" );
        AllskyCamera cam = cameras.get( camId != null ? camId : "default" );
        if( cam == null ) {
            resp.sendError( HttpServletResponse.SC_NOT_FOUND );
            return;
        }

        try {
            switch( pathInfo ) {
                case "/latest":
                    writeJson( resp, fetchLatest( cam.client() ) );
                    return;

                case "/chart": {
                    int limitS = clamp( parseIntParam( req, "limitS", 15000 ), 60, 86400 );
                    // Epoch seconds, matching indi-allsky's own convention (and the "ts" field this
                    // same endpoint returns is only ever converted TO milliseconds, never from) —
                    // omitted entirely (rather than defaulting to "now") when absent, so existing
                    // callers asking for "the last limitS seconds" keep working unchanged.
                    Long timestamp = parseLongParam( req, "timestamp" );
                    writeJson( resp, fetchChart( cam.client(), limitS, timestamp ) );
                    return;
                }

                case "/keogram": {
                    List<Map<String,Object>> keograms = new ArrayList<>();
                    for( AllskyClient.NightKeogram keogram : cam.client().fetchNightKeograms() ) {
                        keograms.add( nightKeogramJson( keogram ) );
                    }
                    writeJson( resp, keograms );
                    return;
                }

                case "/image": {
                    String path = req.getParameter( "path" );
                    if( path == null || !IMAGE_PATH.matcher( path ).matches() ) {
                        resp.sendError( HttpServletResponse.SC_BAD_REQUEST );
                        return;
                    }

                    byte[] jpeg = cam.client().fetchImage( path );
                    if( jpeg == null ) {
                        resp.sendError( HttpServletResponse.SC_NOT_FOUND );
                        return;
                    }

                    resp.setContentType( "image/jpeg" );
                    resp.setContentLength( jpeg.length );
                    resp.getOutputStream().write( jpeg );
                    resp.getOutputStream().flush();
                    return;
                }

                default:
                    resp.sendError( HttpServletResponse.SC_NOT_FOUND );
            }
        }
        catch( Exception e ) {
            resp.sendError( HttpServletResponse.SC_BAD_GATEWAY );
        }
    }

    private void writeJson( HttpServletResponse resp, Object value ) throws IOException {
        resp.setContentType( "application/json;charset=utf-8" );
        gson.toJson( value, resp.getWriter() );
    }

    private static final Pattern IMAGE_PATH = Pattern.compile( "images/[A-Za-z0-9_.\\-/]+\\.jpe?g" );

    /** Generous staleness cutoff for js/latest — during the day capture can pause for hours, and
     *  the endpoint returns latest_image.url == null once the most recent capture is older than
     *  this, so this needs to comfortably span a capture gap, not just a single poll interval. */
    private static final int LATEST_MAX_AGE_S = 86400;

    /** Window used to enrich the image with a star count — this is a nice-to-have overlay, not
     *  the source of the image itself (see below), so unlike the old escalating search this is
     *  just one request; if it comes up empty (e.g. capture gap, or an install with timelapse
     *  indexing disabled — see fetchLoop's javadoc) the image is still shown, just without it. */
    private static final int STARS_WINDOW_S = 3600;

    /**
     * The image itself always comes from js/latest — indi-allsky's own "Latest" page uses the same
     * endpoint, and unlike js/loop (used only below to enrich with star count) it reliably returns
     * the most recent capture even on installs where js/loop's image_list is permanently empty
     * (confirmed against a real camera: js/loop returned "No Timelapse Data" for every camera_id
     * and window up to 24h, while js/latest returned the actual latest frame). SQM turned out not
     * to be a useful signal here, so it's dropped rather than passed through.
     */
    @SuppressWarnings("unchecked")
    private Map<String,Object> fetchLatest( AllskyClient client ) throws Exception {
        Map<String,Object> res = new LinkedHashMap<>();

        Map<String,Object> latest = client.fetchLatest( LATEST_MAX_AGE_S );
        Object url = mapGet( latest, "latest_image", "url" );
        if( url != null ) {
            res.put( "url", url );
            res.put( "moonmode", mapGet( latest, "latest_image", "moonmode" ) );
        }

        Map<String,Object> loop = client.fetchLoop( STARS_WINDOW_S );
        List<Map<String,Object>> images = (List<Map<String,Object>>) loop.get( "image_list" );
        if( images != null && !images.isEmpty() ) {
            Map<String,Object> loopLatest = images.get( 0 );
            res.putIfAbsent( "url", loopLatest.get( "url" ) );
            res.putIfAbsent( "moonmode", loopLatest.get( "moonmode" ) );
            res.put( "stars", loopLatest.get( "stars" ) );
            res.put( "ts", secondsToMillis( loopLatest.get( "timestamp" ) ) );
        }

        Object starsAvg = mapGet( loop, "stars_data", "avg" );
        if( starsAvg != null ) {
            res.put( "starsAvg", starsAvg );
        }

        return res;
    }

    /** Star-count history for the chart — oldest first, matching every other chart in this app
     *  (indi-allsky's own loop response is newest first). `timestamp` (epoch seconds), when given,
     *  anchors the window there instead of at "now" — see AllskyClient.fetchLoop's javadoc. */
    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> fetchChart( AllskyClient client, int limitS, Long timestamp ) throws Exception {
        Map<String,Object> loop = client.fetchLoop( limitS, timestamp );
        List<Map<String,Object>> images = (List<Map<String,Object>>) loop.get( "image_list" );

        List<Map<String,Object>> points = new ArrayList<>();
        if( images != null ) {
            for( int i = images.size() - 1; i >= 0; i-- ) {
                Map<String,Object> img = images.get( i );
                Map<String,Object> point = new LinkedHashMap<>();
                point.put( "ts", secondsToMillis( img.get( "timestamp" ) ) );
                point.put( "stars", img.get( "stars" ) );
                point.put( "url", img.get( "url" ) );
                points.add( point );
            }
        }
        return points;
    }

    private static final Pattern DAY_DATE = Pattern.compile( "(\\d{4})(\\d{2})(\\d{2})" );

    /** indi-allsky's own response has no start/end time for a night's keogram — only its
     *  `dayDate` (the calendar day the night started). Rather than trying to derive an exact
     *  boundary from indi-allsky itself (tried live against js/loop — unreliable, since that
     *  endpoint caps how many entries it returns regardless of the requested window, so a wide
     *  query never actually reaches back to dusk at night-time capture cadence), reuse this app's
     *  own twilight math for the same observatory location: night start = that day's civil dusk,
     *  night end = the following day's civil dawn. Approximate (a keogram's columns are one per
     *  captured frame, not strictly one per second) but good enough to place/stretch the image on
     *  the Session Timeline's axis. */
    private long[] nightBounds( String dayDate ) {
        var m = DAY_DATE.matcher( dayDate );
        if( !m.matches() ) {
            return null;
        }

        Calendar day = Calendar.getInstance();
        day.set( Integer.parseInt( m.group( 1 ) ), Integer.parseInt( m.group( 2 ) ) - 1, Integer.parseInt( m.group( 3 ) ), 12, 0, 0 );

        Calendar nextDay = (Calendar) day.clone();
        nextDay.add( Calendar.DAY_OF_MONTH, 1 );

        Calendar[] duskRange = SunriseSunset.getCivilTwilight( day, config.getLatitude(), config.getLongitude() );
        Calendar[] dawnRange = SunriseSunset.getCivilTwilight( nextDay, config.getLatitude(), config.getLongitude() );
        if( duskRange == null || dawnRange == null ) {
            return null;
        }

        return new long[]{ duskRange[1].getTimeInMillis(), dawnRange[0].getTimeInMillis() };
    }

    private Map<String,Object> nightKeogramJson( AllskyClient.NightKeogram keogram ) {
        Map<String,Object> res = new LinkedHashMap<>();
        res.put( "dayDate", keogram.dayDate() );
        res.put( "dayDateLong", keogram.dayDateLong() );
        res.put( "path", keogram.path() );
        res.put( "maxStars", keogram.maxStars() );
        res.put( "avgStars", keogram.avgStars() );

        long[] bounds = nightBounds( keogram.dayDate() );
        if( bounds != null ) {
            res.put( "startMs", bounds[0] );
            res.put( "endMs", bounds[1] );
        }
        return res;
    }

    @SuppressWarnings("unchecked")
    private static Object mapGet( Map<String,Object> m, String key, String subKey ) {
        Object sub = m.get( key );
        return sub instanceof Map ? ((Map<String,Object>) sub).get( subKey ) : null;
    }

    private static long secondsToMillis( Object seconds ) {
        return seconds instanceof Number ? ((Number) seconds).longValue() * 1000L : 0L;
    }

    private static int parseIntParam( HttpServletRequest req, String name, int fallback ) {
        try {
            String v = req.getParameter( name );
            return v == null ? fallback : Integer.parseInt( v );
        }
        catch( Throwable t ) {
            return fallback;
        }
    }

    /** Null (not a numeric fallback) when absent/unparseable — unlike limitS, an allsky chart
     *  timestamp has no sensible default to fall back to, callers just treat null as "omit it". */
    private static Long parseLongParam( HttpServletRequest req, String name ) {
        try {
            String v = req.getParameter( name );
            return v == null ? null : Long.parseLong( v );
        }
        catch( Throwable t ) {
            return null;
        }
    }

    private static int clamp( int v, int lo, int hi ) {
        return Math.max( lo, Math.min( hi, v ) );
    }
}
