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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *
 * The single-channel surveys (halpha8/oiii8/sii8) are starless; simg.de's own ohs8 combo is not.
 * That makes two genuinely different products worth keeping, not one obsoleting the other:
 * "-sl" (starless) palettes recombine the single-channel surveys directly (PALETTES below), while
 * plain "sho"/"hso" instead re-permute ohs8's own R/G/B (OHS_REMAP) to get the same palettes with
 * stars intact.
 */
public class HipsProxyServlet extends HttpServlet {

    /** palette id -> {R survey, G survey, B survey} — all three are the DR0.2 8-bit single-channel
     *  (starless) HiPS, so every palette built this way is starless too. */
    private static final Map<String, String[]> PALETTES = Map.of(
        "sho-sl", new String[]{ "sii8", "halpha8", "oiii8" },  // classic Hubble/SHO palette: R=SII, G=Halpha, B=OIII
        "hso-sl", new String[]{ "halpha8", "sii8", "oiii8" },  // R=Halpha, G=SII, B=OIII
        "ohs-sl", new String[]{ "oiii8", "halpha8", "sii8" }   // R=OIII, G=Halpha, B=SII — starless counterpart of ohs8
    );

    /** palette id -> permutation of ohs8's own {R=OIII, G=Halpha, B=SII} channels, e.g. sho's
     *  {2,1,0} reads as "new R = old channel 2 (SII/B), new G = old channel 1 (Halpha/G),
     *  new B = old channel 0 (OIII/R)". Reusing ohs8's pixels instead of the single-channel
     *  surveys is what keeps the stars: ohs8 is simg.de's own real (starfull) combo. */
    private static final Map<String, int[]> OHS_REMAP = Map.of(
        "sho", new int[]{ 2, 1, 0 },
        "hso", new int[]{ 1, 2, 0 }
    );

    /** simg.de survey folders that we proxy+cache as a straight passthrough (no channel
     *  recombination) — same benefit as the combined palettes above: every tile is fetched from
     *  simg.de at most once, ever, instead of hammering their server on every browser pan/zoom. */
    private static final Set<String> RAW_SURVEYS = Set.of( "halpha8", "oiii8", "sii8", "ohs8", "hbr8", "rgb8" );

    private static final String BASE_URL = "https://www.simg.de/nebulae3/dr0_2/";
    private static final Pattern TILE_PATH = Pattern.compile( "Norder\\d+/(Dir\\d+/Npix\\d+|Allsky)\\.png" );

    /** Aladin Lite's own default "no HiPS data here" background (its init option defaults to
     *  exactly this, see aladin.js's own `backgroundColor:"rgb(60, 60, 60)"`) — but that default
     *  only paints the letterboxed area *outside* the projected sky circle. *Within* a tile,
     *  Aladin's WebGL renderer doesn't alpha-blend the base image layer at all: confirmed by
     *  reading back an actual rendered canvas pixel at a known no-coverage point, alpha=0 rendered
     *  as fully opaque black, identical to how alpha=255 black would render. The single-channel
     *  surveys' and ohs8's own no-coverage pixels are alpha=0 with RGB baked in as (0,0,0) — real
     *  black, not a neutral placeholder — so every recombination below has to check alpha itself
     *  and substitute this gray, then output fully opaque RGB (no alpha channel at all), rather
     *  than trying to preserve transparency that nothing downstream actually honors. */
    private static final int NODATA_RGB = ( 60 << 16 ) | ( 60 << 8 ) | 60;

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
        int[] ohsRemap = OHS_REMAP.get( palette );
        boolean raw = channels == null && ohsRemap == null && RAW_SURVEYS.contains( palette );
        if( channels == null && ohsRemap == null && !raw ) {
            resp.sendError( HttpServletResponse.SC_NOT_FOUND );
            return;
        }

        if( "properties".equals( tilePath ) ) {
            // Aladin fetches this even when frame/order are already passed explicitly to
            // createImageSurvey() — without it, it silently refuses to actually switch survey
            // (logs "Survey not found" and stays on whatever was showing before).
            if( raw ) {
                serveRawProperties( req, resp, palette );
            }
            else if( channels != null ) {
                servePropertiesFile( req, resp, palette, channels, false );
            }
            else {
                servePropertiesFile( req, resp, palette, remappedChannelOrder( ohsRemap ), true );
            }
            return;
        }

        if( "Moc.fits".equals( tilePath ) ) {
            // The MOC tells Aladin which parts of the sky a survey actually has data for — without
            // it, Aladin has no way to know that short of probing tiles, which reads as random
            // "half the sky is black" until it's given up guessing. NSNS only covers part of the
            // northern sky, so this matters here especially.
            serveMoc( resp, raw ? palette : "ohs8" );
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

        byte[] tile;
        try {
            if( raw ) {
                tile = fetchBytes( BASE_URL + palette + "/" + tilePath ).join();
            }
            else if( channels != null ) {
                tile = fetchAndCombine( channels, tilePath );
            }
            else {
                tile = fetchAndRemap( ohsRemap, tilePath );
            }
        }
        catch( Exception e ) {
            resp.sendError( HttpServletResponse.SC_BAD_GATEWAY );
            return;
        }

        if( tile == null ) {
            // Sparse HiPS tree — this pix legitimately doesn't exist at this order, same as a
            // real HiPS service would 404 it. Not cached: a 404 today doesn't mean one tomorrow.
            resp.sendError( HttpServletResponse.SC_NOT_FOUND );
            return;
        }

        cacheFile.getParentFile().mkdirs();
        Files.write( cacheFile.toPath(), tile );
        serveBytes( resp, tile );
    }

    private static String channelLabel( String survey ) {
        if( survey.startsWith( "halpha" ) ) return "Halpha";
        if( survey.startsWith( "oiii" ) ) return "OIII";
        if( survey.startsWith( "sii" ) ) return "SII";
        return survey;
    }

    /** ohs8's own fixed R/G/B order — OHS_REMAP's indices are into this. */
    private static final String[] OHS8_CHANNEL_SURVEYS = { "oiii8", "halpha8", "sii8" };

    private static String[] remappedChannelOrder( int[] remap ) {
        return new String[]{ OHS8_CHANNEL_SURVEYS[remap[0]], OHS8_CHANNEL_SURVEYS[remap[1]], OHS8_CHANNEL_SURVEYS[remap[2]] };
    }

    /** Minimal HiPS properties file — same key fields real HiPS trees publish (frame, order,
     *  tile format/width), just enough for Aladin to accept the survey and start requesting tiles.
     *  starfull distinguishes the two ways a palette here gets built (see class javadoc): straight
     *  recombination of the starless single-channel surveys, or a channel re-permutation of
     *  simg.de's own starfull ohs8 combo — only the description differs, everything else is the
     *  same synthetic-HiPS boilerplate either way. */
    private void servePropertiesFile( HttpServletRequest req, HttpServletResponse resp, String palette, String[] channels, boolean starfull ) throws IOException {
        String baseUrl = req.getScheme() + "://" + req.getServerName() + ":" + req.getServerPort()
                + req.getContextPath() + req.getServletPath() + "/" + palette;

        String paletteUpper = palette.toUpperCase();
        String channelDesc = "R=" + channelLabel( channels[0] ) + "/G=" + channelLabel( channels[1] ) + "/B=" + channelLabel( channels[2] );
        String source = starfull
                ? "re-permuting simg.de's own starfull OIII/Halpha/SII combo (ohs8)"
                : "combining simg.de's starless single-channel HiPS surveys";

        String properties = "obs_collection       = Northern Sky Narrowband Survey (" + paletteUpper + " composite)\n"
                + "obs_title            = NSNS DR0.2: " + paletteUpper + " composite (proxied, " + (starfull ? "starfull" : "starless") + ")\n"
                + "obs_description      = Server-side " + channelDesc + " composite, built by " + source + " on the fly, since simg.de doesn't publish this combination directly.\n"
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

    /** For raw (non-recombined) surveys, proxy simg.de's own real properties file instead of
     *  synthesizing one — it already has the correct description/copyright/order/etc. The only
     *  thing that has to change is hips_service_url, which otherwise points straight at simg.de
     *  and would make Aladin fetch every subsequent tile directly from them, bypassing our cache
     *  entirely. Cached to disk like any other tile — simg.de's properties files don't change. */
    private void serveRawProperties( HttpServletRequest req, HttpServletResponse resp, String survey ) throws IOException {
        File cacheFile = new File( CACHE_DIR, survey + "/properties" );
        byte[] bytes;
        if( cacheFile.isFile() ) {
            bytes = Files.readAllBytes( cacheFile.toPath() );
        }
        else {
            byte[] fetched = fetchBytes( BASE_URL + survey + "/properties" ).join();
            if( fetched == null ) {
                resp.sendError( HttpServletResponse.SC_NOT_FOUND );
                return;
            }
            String ourUrl = req.getScheme() + "://" + req.getServerName() + ":" + req.getServerPort()
                    + req.getContextPath() + req.getServletPath() + "/" + survey;
            String rewritten = new String( fetched, StandardCharsets.UTF_8 )
                    .replaceAll( "(?m)^hips_service_url\\s*=.*$", "hips_service_url     = " + ourUrl );
            bytes = rewritten.getBytes( StandardCharsets.UTF_8 );

            cacheFile.getParentFile().mkdirs();
            Files.write( cacheFile.toPath(), bytes );
        }

        resp.setContentType( "text/plain;charset=utf-8" );
        resp.setContentLength( bytes.length );
        resp.getOutputStream().write( bytes );
        resp.getOutputStream().flush();
    }

    /** Straight passthrough+cache of one survey's real Moc.fits from simg.de — for the combined
     *  (PALETTES) and remapped (OHS_REMAP) palettes this is always ohs8's own Moc, a reasonable
     *  stand-in since all of these are built from the same NSNS DR0.2 processing run and so share
     *  essentially the same real sky footprint (computing the true intersection of three separate
     *  single-channel Mocs for the "-sl" palettes wasn't worth it for that approximation). */
    private void serveMoc( HttpServletResponse resp, String survey ) throws IOException {
        File cacheFile = new File( CACHE_DIR, survey + "/Moc.fits" );
        byte[] bytes;
        if( cacheFile.isFile() ) {
            bytes = Files.readAllBytes( cacheFile.toPath() );
        }
        else {
            bytes = fetchBytes( BASE_URL + survey + "/Moc.fits" ).join();
            if( bytes == null ) {
                resp.sendError( HttpServletResponse.SC_NOT_FOUND );
                return;
            }
            cacheFile.getParentFile().mkdirs();
            Files.write( cacheFile.toPath(), bytes );
        }

        resp.setContentType( "image/fits" );
        resp.setHeader( "Cache-Control", "public, max-age=31536000, immutable" );
        resp.setContentLength( bytes.length );
        resp.getOutputStream().write( bytes );
        resp.getOutputStream().flush();
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
                // getRGB() normalizes any source color model (these are single-channel grayscale
                // PNGs) to ARGB — the gray sample ends up replicated into R/G/B alike, and alpha
                // comes along for free instead of having to inspect each raster's own band layout.
                int rArgb = r.getRGB( x, y );
                int gArgb = g.getRGB( x, y );
                int bArgb = b.getRGB( x, y );
                // Any one of the three single-channel surveys marking "no data" here (alpha=0,
                // same NSNS DR0.2 convention as ohs8 below) means the combined pixel has no real
                // data either — see NODATA_RGB above for why this substitutes gray, not black.
                boolean noData = (rArgb >>> 24) == 0 || (gArgb >>> 24) == 0 || (bArgb >>> 24) == 0;
                if( noData ) {
                    out.setRGB( x, y, NODATA_RGB );
                }
                else {
                    out.setRGB( x, y, ((rArgb & 0xFF) << 16) | ((gArgb & 0xFF) << 8) | (bArgb & 0xFF) );
                }
            }
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write( out, "png", bytes );
        return bytes.toByteArray();
    }

    /** Fetches one ohs8 tile (already a color PNG, unlike the single-channel surveys above) and
     *  re-permutes its own R/G/B into a different order — see OHS_REMAP. Keeps ohs8's stars,
     *  unlike fetchAndCombine's from-scratch recombination of the starless single-channel surveys. */
    private byte[] fetchAndRemap( int[] remap, String tilePath ) throws Exception {
        byte[] sourceBytes = fetchBytes( BASE_URL + "ohs8/" + tilePath ).join();
        if( sourceBytes == null ) {
            return null;
        }

        BufferedImage src = ImageIO.read( new ByteArrayInputStream( sourceBytes ) );
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage( w, h, BufferedImage.TYPE_INT_RGB );
        for( int y = 0; y < h; y++ ) {
            for( int x = 0; x < w; x++ ) {
                int argb = src.getRGB( x, y );
                // alpha=0 marks a pixel NSNS has no coverage for at all — see NODATA_RGB above for
                // why this substitutes gray instead of passing the real (baked-in black) RGB through.
                if( (argb >>> 24) == 0 ) {
                    out.setRGB( x, y, NODATA_RGB );
                    continue;
                }
                int[] channelValues = { (argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF };
                out.setRGB( x, y, (channelValues[remap[0]] << 16) | (channelValues[remap[1]] << 8) | channelValues[remap[2]] );
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
