package de.pmneo.kstars.utils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.security.cert.X509Certificate;
import java.util.Map;

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
        HttpsURLConnection conn = (HttpsURLConnection) URI.create( url ).toURL().openConnection();
        conn.setSSLSocketFactory( sslContext.getSocketFactory() );
        conn.setHostnameVerifier( ( hostname, session ) -> true );
        conn.setConnectTimeout( 5000 );
        conn.setReadTimeout( 10000 );
        conn.setRequestMethod( "GET" );
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
}
