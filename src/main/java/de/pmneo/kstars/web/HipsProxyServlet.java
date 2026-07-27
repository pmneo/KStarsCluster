package de.pmneo.kstars.web;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * simg.de's Northern Sky Narrowband Survey doesn't publish an SHO (Hubble palette) composite —
 * only single-channel HiPS surveys (halpha8, oiii8, sii8) plus its own fixed-mapping combos
 * (ohs8, hbr8, rgb8). Aladin Lite fetches HiPS tiles directly from the browser, so a client-side
 * remap isn't possible without CORS games — this proxies each requested tile path against all
 * three single-channel surveys server-side (where CORS doesn't apply at all) and recombines them
 * into one RGB PNG. Same URL/tile addressing convention as the source HiPS trees, so Aladin
 * doesn't need to know it's not talking to a real HiPS service.
 */
public class HipsProxyServlet extends HttpServlet {

    /** palette id -> {R survey, G survey, B survey} — all three are the DR0.2 8-bit single-channel HiPS. */
    private static final Map<String, String[]> PALETTES = Map.of(
        "sho", new String[]{ "sii8", "halpha8", "oiii8" }  // classic Hubble/SHO palette: R=SII, G=Halpha, B=OIII
    );

    private static final String BASE_URL = "https://www.simg.de/nebulae3/dr0_2/";
    private static final Pattern TILE_PATH = Pattern.compile( "Norder\\d+/(Dir\\d+/Npix\\d+|Allsky)\\.png" );

    private static final File CACHE_DIR = new File( "./hips-cache" );

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout( Duration.ofSeconds( 5 ) )
            .build();

    @Override
    protected void doGet( HttpServletRequest req, HttpServletResponse resp ) throws IOException {
        String pathInfo = req.getPathInfo();
        if( pathInfo == null || !pathInfo.startsWith( "/" ) ) {
            resp.sendError( HttpServletResponse.SC_NOT_FOUND );
            return;
        }

        String[] parts = pathInfo.substring( 1 ).split( "/", 2 );
        if( parts.length < 2 ) {
            resp.sendError( HttpServletResponse.SC_NOT_FOUND );
            return;
        }

        String palette = parts[0];
        String tilePath = parts[1];

        String[] channels = PALETTES.get( palette );
        if( channels == null ) {
            resp.sendError( HttpServletResponse.SC_NOT_FOUND );
            return;
        }

        if( "properties".equals( tilePath ) ) {
            // Aladin fetches this even when frame/order are already passed explicitly to
            // createImageSurvey() — without it, it silently refuses to actually switch survey
            // (logs "Survey not found" and stays on whatever was showing before).
            servePropertiesFile( req, resp, palette );
            return;
        }

        if( !TILE_PATH.matcher( tilePath ).matches() ) {
            resp.sendError( HttpServletResponse.SC_NOT_FOUND );
            return;
        }

        File cacheFile = new File( CACHE_DIR, palette + "/" + tilePath );
        if( cacheFile.isFile() ) {
            serveBytes( resp, Files.readAllBytes( cacheFile.toPath() ) );
            return;
        }

        byte[] combined;
        try {
            combined = fetchAndCombine( channels, tilePath );
        }
        catch( Exception e ) {
            resp.sendError( HttpServletResponse.SC_BAD_GATEWAY );
            return;
        }

        if( combined == null ) {
            // Sparse HiPS tree — this pix legitimately doesn't exist at this order, same as a
            // real HiPS service would 404 it. Not cached: a 404 today doesn't mean one tomorrow.
            resp.sendError( HttpServletResponse.SC_NOT_FOUND );
            return;
        }

        cacheFile.getParentFile().mkdirs();
        Files.write( cacheFile.toPath(), combined );
        serveBytes( resp, combined );
    }

    /** Minimal HiPS properties file — same key fields real HiPS trees publish (frame, order,
     *  tile format/width), just enough for Aladin to accept the survey and start requesting tiles. */
    private void servePropertiesFile( HttpServletRequest req, HttpServletResponse resp, String palette ) throws IOException {
        String baseUrl = req.getScheme() + "://" + req.getServerName() + ":" + req.getServerPort()
                + req.getContextPath() + req.getServletPath() + "/" + palette;

        String properties = "obs_collection       = Northern Sky Narrowband Survey (SHO composite)\n"
                + "obs_title            = NSNS DR0.2: SHO (Hubble palette, proxied)\n"
                + "obs_description      = Server-side R=SII/G=Halpha/B=OIII composite of simg.de's single-channel HiPS surveys, combined on the fly by KStarsCluster since simg.de doesn't publish one directly.\n"
                + "hips_frame           = equatorial\n"
                + "hips_order           = 6\n"
                + "hips_order_min       = 0\n"
                + "hips_tile_width      = 512\n"
                + "hips_tile_format     = png\n"
                + "hips_status          = public master clonable\n"
                + "hips_version         = 1.4\n"
                + "dataproduct_type     = image\n"
                + "client_application   = AladinLite\n"
                + "hips_service_url     = " + baseUrl + "\n"
                + "creator_did          = ivo://kstarscluster/hips/" + palette + "\n";

        resp.setContentType( "text/plain;charset=utf-8" );
        resp.getWriter().write( properties );
    }

    private void serveBytes( HttpServletResponse resp, byte[] bytes ) throws IOException {
        resp.setContentType( "image/png" );
        // Tiles are immutable once generated — cache hard on the client too.
        resp.setHeader( "Cache-Control", "public, max-age=31536000, immutable" );
        resp.setContentLength( bytes.length );
        resp.getOutputStream().write( bytes );
        resp.getOutputStream().flush();
    }

    /** Fetches the same tile path from all three source surveys concurrently and recombines
     *  them into one RGB PNG — or returns null if any of the three doesn't have this tile. */
    private byte[] fetchAndCombine( String[] channels, String tilePath ) throws Exception {
        List<CompletableFuture<byte[]>> futures = List.of(
                fetchBytes( BASE_URL + channels[0] + "/" + tilePath ),
                fetchBytes( BASE_URL + channels[1] + "/" + tilePath ),
                fetchBytes( BASE_URL + channels[2] + "/" + tilePath )
        );
        CompletableFuture.allOf( futures.toArray( new CompletableFuture[0] ) ).join();

        byte[] rBytes = futures.get( 0 ).join();
        byte[] gBytes = futures.get( 1 ).join();
        byte[] bBytes = futures.get( 2 ).join();
        if( rBytes == null || gBytes == null || bBytes == null ) {
            return null;
        }

        BufferedImage r = ImageIO.read( new ByteArrayInputStream( rBytes ) );
        BufferedImage g = ImageIO.read( new ByteArrayInputStream( gBytes ) );
        BufferedImage b = ImageIO.read( new ByteArrayInputStream( bBytes ) );

        int w = r.getWidth();
        int h = r.getHeight();
        BufferedImage out = new BufferedImage( w, h, BufferedImage.TYPE_INT_RGB );
        for( int y = 0; y < h; y++ ) {
            for( int x = 0; x < w; x++ ) {
                int rv = r.getRaster().getSample( x, y, 0 );
                int gv = g.getRaster().getSample( x, y, 0 );
                int bv = b.getRaster().getSample( x, y, 0 );
                out.setRGB( x, y, (rv << 16) | (gv << 8) | bv );
            }
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write( out, "png", bytes );
        return bytes.toByteArray();
    }

    private CompletableFuture<byte[]> fetchBytes( String url ) {
        HttpRequest request = HttpRequest.newBuilder( URI.create( url ) ).GET().build();
        return httpClient.sendAsync( request, HttpResponse.BodyHandlers.ofByteArray() )
                .thenApply( r -> r.statusCode() == 200 ? r.body() : null )
                .exceptionally( e -> null );
    }
}
