package de.pmneo.kstars.utils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
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
 * right now" widget, since star count/SQM tend to drop sharply under cloud, a useful independent
 * cross-check alongside the weather station. Self-signed cert on this local LAN device, same as
 * visiting it directly in a browser and clicking through the warning once.
 *
 * Uses the classic HttpsURLConnection instead of java.net.http.HttpClient: the newer HttpClient
 * keeps enforcing hostname/SAN verification even with a trust-all TrustManager and
 * setEndpointIdentificationAlgorithm(null) on its SSLParameters — confirmed empirically, this
 * camera's cert has no SAN for its IP at all and HttpClient rejected it regardless.
 * HttpsURLConnection's setHostnameVerifier() reliably does what it says.
 */
public class AllskyClient {

    private static final String BASE_URL = "https://192.168.0.109/indi-allsky/";

    private final SSLContext sslContext;
    private final Gson gson = new Gson();

    public AllskyClient() {
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

    /** Raw parsed response of GET js/loop?camera_id=1&limit_s={limitS} — image_list (newest
     *  first), plus stars_data/jsqm_data/camera_sqm_*_data summary stats over the window. */
    @SuppressWarnings("unchecked")
    public Map<String,Object> fetchLoop( int limitS ) throws Exception {
        String url = BASE_URL + "js/loop?camera_id=1&limit_s=" + limitS;
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
        HttpsURLConnection conn = open( BASE_URL + relativePath );
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
