package de.pmneo.kstars.utils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import com.google.gson.Gson;

/**
 * indi-allsky (https://github.com/aaronwmorris/indi-allsky) exposes a small JSON API for its
 * latest capture and a rolling window of recent ones — queried for a quick "how clear is the sky
 * right now" widget, since star count tends to drop sharply under cloud, a useful independent
 * cross-check alongside the weather station. One instance per physical camera/host — there can
 * be more than one indi-allsky install on the LAN (e.g. one per site). Self-signed cert on these
 * local LAN devices, same as visiting one directly in a browser and clicking through the warning
 * once.
 *
 * Uses the classic HttpsURLConnection instead of java.net.http.HttpClient: the newer HttpClient
 * keeps enforcing hostname/SAN verification even with a trust-all TrustManager and
 * setEndpointIdentificationAlgorithm(null) on its SSLParameters — confirmed empirically, this
 * camera's cert has no SAN for its IP at all and HttpClient rejected it regardless.
 * HttpsURLConnection's setHostnameVerifier() reliably does what it says.
 */
public class AllskyClient {

    private final String baseUrl;
    private final int cameraId;
    private final SSLContext sslContext;
    private final Gson gson = new Gson();

    public AllskyClient( String host, int cameraId ) {
        this.baseUrl = "https://" + host + "/indi-allsky/";
        this.cameraId = cameraId;

        try {
            sslContext = SSLContext.getInstance( "TLS" );
            sslContext.init( null, new TrustManager[]{ new X509TrustManager() {
                public void checkClientTrusted( X509Certificate[] chain, String authType ) {}
                public void checkServerTrusted( X509Certificate[] chain, String authType ) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            } }, null );
        }
        catch( Exception e ) {
            throw new IllegalStateException( "Failed to set up allsky HTTP client", e );
        }
    }

    private HttpsURLConnection open( String url ) throws Exception {
        return open( url, "GET" );
    }

    private HttpsURLConnection open( String url, String method ) throws Exception {
        HttpsURLConnection conn = (HttpsURLConnection) URI.create( url ).toURL().openConnection();
        conn.setSSLSocketFactory( sslContext.getSocketFactory() );
        conn.setHostnameVerifier( ( hostname, session ) -> true );
        conn.setConnectTimeout( 5000 );
        conn.setReadTimeout( 10000 );
        conn.setRequestMethod( method );
        return conn;
    }

    private static byte[] readAll( InputStream in ) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        in.transferTo( out );
        return out.toByteArray();
    }

    /** Raw parsed response of GET js/loop?camera_id={cameraId}&limit_s={limitS} — image_list
     *  (newest first), plus stars_data/jsqm_data/camera_sqm_*_data summary stats over the window.
     *  Some indi-allsky installs return an always-empty image_list here (timelapse indexing
     *  disabled server-side) even while capturing fine — don't rely on this alone for "is there a
     *  latest image", see {@link #fetchLatest(int)}. */
    public Map<String,Object> fetchLoop( int limitS ) throws Exception {
        return fetchLoop( limitS, null );
    }

    /** Same as {@link #fetchLoop(int)}, but anchored at a specific point in time (epoch seconds)
     *  instead of implicitly "now" — confirmed empirically against a real instance: with
     *  timestamp=T, image_list's newest entry IS timestamp T (not just "on or before"), and every
     *  entry after it goes backwards from there, i.e. the window is [T-limitS, T]. Used to look up
     *  what an allsky camera saw around a specific capture's own timestamp, which can be well
     *  outside whatever "last limitS seconds from now" already covers — pass a timestamp pushed
     *  forward by half the window to get a window that's centered on, rather than ending at, the
     *  moment you actually care about. */
    @SuppressWarnings("unchecked")
    public Map<String,Object> fetchLoop( int limitS, Long timestamp ) throws Exception {
        String url = baseUrl + "js/loop?camera_id=" + cameraId + "&limit_s=" + limitS;
        if( timestamp != null ) {
            url += "&timestamp=" + timestamp;
        }
        HttpsURLConnection conn = open( url );
        try {
            if( conn.getResponseCode() != 200 ) {
                throw new IllegalStateException( "indi-allsky returned HTTP " + conn.getResponseCode() + " for " + url );
            }
            String body = new String( readAll( conn.getInputStream() ) );
            return gson.fromJson( body, Map.class );
        }
        finally {
            conn.disconnect();
        }
    }

    /** Raw parsed response of GET js/latest?camera_id={cameraId}&limit_s={limitS} — the single
     *  most recent capture (latest_image.url/width/height/moonmode), regardless of whether this
     *  install's timelapse indexing (which {@link #fetchLoop} depends on) is enabled. limitS here
     *  is a staleness cutoff — the endpoint returns latest_image.url == null if the most recent
     *  capture is older than that, so pass a generous window (e.g. a full day). */
    @SuppressWarnings("unchecked")
    public Map<String,Object> fetchLatest( int limitS ) throws Exception {
        String url = baseUrl + "js/latest?camera_id=" + cameraId + "&limit_s=" + limitS;
        HttpsURLConnection conn = open( url );
        try {
            if( conn.getResponseCode() != 200 ) {
                throw new IllegalStateException( "indi-allsky returned HTTP " + conn.getResponseCode() + " for " + url );
            }
            String body = new String( readAll( conn.getInputStream() ) );
            return gson.fromJson( body, Map.class );
        }
        finally {
            conn.disconnect();
        }
    }

    /** Fetches the JPEG at a path exactly as returned in a loop/latest response's "url" field
     *  (relative to the indi-allsky web root) — null on any non-200 response. */
    public byte[] fetchImage( String relativePath ) throws Exception {
        HttpsURLConnection conn = open( baseUrl + relativePath );
        try {
            if( conn.getResponseCode() != 200 ) {
                return null;
            }
            return readAll( conn.getInputStream() );
        }
        finally {
            conn.disconnect();
        }
    }

    /** Scrapes the CSRF token indi-allsky's own pages inline into a `<script>` (Flask-WTF style,
     *  e.g. `xhr.setRequestHeader("X-CSRFToken", "...")`) — there's no dedicated API for it, the
     *  token only ever shows up baked into an HTML page's JS. */
    private static final Pattern CSRF_TOKEN_PATTERN = Pattern.compile( "X-CSRFToken\",\\s*\"([^\"]+)\"" );

    /** dayDate/dayDateLong/path/maxStars/avgStars for one COMPLETED night (i.e. indi-allsky has
     *  already finished generating its keogram for it) — see {@link #fetchNightKeograms()}. */
    public record NightKeogram( String dayDate, String dayDateLong, String path, Integer maxStars, Integer avgStars ) {}

    /** Only changes once a day, and finding it costs two round-trips per month queried (a
     *  CSRF/cookie scrape plus the actual POST) — not worth repeating on every dashboard poll. */
    private static final long NIGHT_KEOGRAM_CACHE_MS = 15 * 60 * 1000;
    /** How many months back to look — the Session Timeline can retain (and let the user scroll
     *  back through) more than one night's worth of history, so a single "latest night" isn't
     *  enough; 2 months is a generous, simple upper bound rather than trying to coordinate the
     *  exact lookback with however far back the frontend's own retained session data happens to
     *  reach. */
    private static final int NIGHT_KEOGRAM_MONTHS_BACK = 2;
    private volatile List<NightKeogram> cachedNightKeograms;
    private volatile long cachedNightKeogramsFetchedAtMs;

    /**
     * Every night indi-allsky has fully processed (keogram already generated) in the last
     * {@link #NIGHT_KEOGRAM_MONTHS_BACK} months, newest first — empty if none found. Unlike
     * {@link #fetchLoop}/{@link #fetchLatest}, this isn't exposed anywhere in indi-allsky's simple
     * unauthenticated JSON API — the only source is its CSRF-protected `ajax/videoviewer` endpoint,
     * which backs its own "Timelapses" page.
     */
    public List<NightKeogram> fetchNightKeograms() throws Exception {
        List<NightKeogram> cached = cachedNightKeograms;
        if( cached != null && System.currentTimeMillis() - cachedNightKeogramsFetchedAtMs < NIGHT_KEOGRAM_CACHE_MS ) {
            return cached;
        }

        Calendar month = Calendar.getInstance();
        List<NightKeogram> result = new ArrayList<>();
        for( int i = 0; i < NIGHT_KEOGRAM_MONTHS_BACK; i++ ) {
            result.addAll( fetchNightKeograms( month.get( Calendar.YEAR ), month.get( Calendar.MONTH ) + 1 ) );
            month.add( Calendar.MONTH, -1 );
        }

        cachedNightKeograms = result;
        cachedNightKeogramsFetchedAtMs = System.currentTimeMillis();
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<NightKeogram> fetchNightKeograms( int year, int month ) throws Exception {
        String viewerUrl = baseUrl + "videoviewer";

        String csrfToken;
        String cookie;
        HttpsURLConnection getConn = open( viewerUrl );
        try {
            if( getConn.getResponseCode() != 200 ) {
                throw new IllegalStateException( "indi-allsky returned HTTP " + getConn.getResponseCode() + " for " + viewerUrl );
            }
            String html = new String( readAll( getConn.getInputStream() ), StandardCharsets.UTF_8 );
            Matcher m = CSRF_TOKEN_PATTERN.matcher( html );
            if( !m.find() ) {
                throw new IllegalStateException( "Could not find CSRF token on " + viewerUrl );
            }
            csrfToken = m.group( 1 );
            cookie = extractCookie( getConn );
        }
        finally {
            getConn.disconnect();
        }

        String json = gson.toJson( Map.of(
                "CAMERA_ID", String.valueOf( cameraId ),
                "YEAR_SELECT", String.valueOf( year ),
                "MONTH_SELECT", String.valueOf( month ),
                "TIMEOFDAY_SELECT", "night"
        ) );

        HttpsURLConnection postConn = open( baseUrl + "ajax/videoviewer", "POST" );
        postConn.setDoOutput( true );
        postConn.setRequestProperty( "Content-Type", "application/json" );
        postConn.setRequestProperty( "X-CSRFToken", csrfToken );
        // Required — confirmed empirically that a bare POST without this 400s ("The referrer
        // header is missing.") even with a valid CSRF token and cookie.
        postConn.setRequestProperty( "Referer", viewerUrl );
        if( cookie != null ) {
            postConn.setRequestProperty( "Cookie", cookie );
        }
        try {
            try( OutputStream out = postConn.getOutputStream() ) {
                out.write( json.getBytes( StandardCharsets.UTF_8 ) );
            }

            if( postConn.getResponseCode() != 200 ) {
                throw new IllegalStateException( "indi-allsky returned HTTP " + postConn.getResponseCode() + " for ajax/videoviewer" );
            }

            String responseBody = new String( readAll( postConn.getInputStream() ), StandardCharsets.UTF_8 );
            Map<String,Object> response = gson.fromJson( responseBody, Map.class );
            List<Map<String,Object>> videoList = (List<Map<String,Object>>) response.get( "video_list" );
            if( videoList == null ) {
                return List.of();
            }

            // A still-in-progress night either isn't in this list yet or has keogram_success ==
            // false — everything else here is genuinely done.
            List<NightKeogram> result = new ArrayList<>();
            for( Map<String,Object> entry : videoList ) {
                if( !Boolean.TRUE.equals( entry.get( "night" ) ) ) continue;
                if( !Boolean.TRUE.equals( entry.get( "keogram_success" ) ) ) continue;
                Object path = entry.get( "keogram" );
                if( !(path instanceof String) || "None".equals( path ) ) continue;

                result.add( new NightKeogram(
                        (String) entry.get( "dayDate" ),
                        (String) entry.get( "dayDate_long" ),
                        (String) path,
                        toInteger( entry.get( "max_stars" ) ),
                        toInteger( entry.get( "avg_stars" ) )
                ) );
            }
            return result;
        }
        finally {
            postConn.disconnect();
        }
    }

    /** Flask's session cookie, captured from Set-Cookie and replayed on the follow-up POST —
     *  attribute-stripped (path/expires/etc, keeping only name=value) same as a browser would send
     *  it back. This class otherwise never handles cookies (see class javadoc) since js/loop and
     *  js/latest are plain unauthenticated GETs — only the CSRF-protected ajax/videoviewer needs
     *  this, so it's kept local to that call rather than a shared CookieManager on the client. */
    private static String extractCookie( HttpsURLConnection conn ) {
        List<String> cookies = new ArrayList<>();
        for( Map.Entry<String,List<String>> header : conn.getHeaderFields().entrySet() ) {
            if( header.getKey() == null || !header.getKey().equalsIgnoreCase( "Set-Cookie" ) ) continue;
            for( String value : header.getValue() ) {
                int semi = value.indexOf( ';' );
                cookies.add( semi >= 0 ? value.substring( 0, semi ) : value );
            }
        }
        return cookies.isEmpty() ? null : String.join( "; ", cookies );
    }

    private static Integer toInteger( Object value ) {
        return value instanceof Number ? ((Number) value).intValue() : null;
    }
}
