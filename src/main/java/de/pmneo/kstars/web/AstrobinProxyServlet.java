package de.pmneo.kstars.web;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.google.gson.Gson;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Draws the user's own AstroBin gallery as footprint rectangles on the Sky Map — "what have I
 * already imaged, and where". AstroBin's own site has no documented public endpoint for this, but
 * the two REST calls its own Angular frontend makes to render https://app.astrobin.com/u/&lt;user&gt;
 * turn out to be unauthenticated and CORS-free (found by inspecting its network traffic and
 * bundled JS): a lightweight paginated image listing, and a separate plate-solving "solutions"
 * endpoint keyed by Django content-type/object-id. Neither sends an Access-Control-Allow-Origin
 * header, so the browser can't call them directly from our own frontend's origin — this proxies
 * and joins both server-side, where CORS doesn't apply, and caches the (fairly expensive: N/50
 * round trips for a gallery of N images) result for an hour.
 */
public class AstrobinProxyServlet extends HttpServlet {

    // pmneo's AstroBin numeric user id and the Django ContentType id for AstroBin's own Image
    // model — both found via the network requests app.astrobin.com/u/pmneo itself makes, and both
    // effectively permanent for a given account/deployment. Not worth making configurable for what
    // is fundamentally "my own dashboard showing my own gallery".
    private static final long ASTROBIN_USER_ID = 56163;
    private static final int IMAGE_CONTENT_TYPE_ID = 19;
    private static final int IMAGE_REVISION_CONTENT_TYPE_ID = 20;
    private static final String API_BASE = "https://app.astrobin.com/api/v2";

    // The bulk solutions endpoint accepts a comma-separated id list in one request — batched
    // rather than sent in one shot purely to keep individual request URLs a sane length.
    private static final int SOLUTION_BATCH_SIZE = 50;

    // This is a published astrophotography gallery, not something that changes minute to minute —
    // re-fetching a few hundred images' worth of metadata plus solutions on every dashboard load
    // would be both slow and needlessly heavy on AstroBin's (undocumented, unauthenticated) API.
    private static final long CACHE_TTL_MS = 60 * 60 * 1000;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout( Duration.ofSeconds( 10 ) )
            .build();
    private final Gson gson = new Gson();

    private volatile List<Map<String, Object>> cachedFootprints = null;
    private volatile long cachedAt = 0;

    @Override
    protected void doGet( HttpServletRequest req, HttpServletResponse resp ) throws IOException {
        String pathInfo = req.getPathInfo();
        if( "/footprints".equals( pathInfo ) ) {
            List<Map<String, Object>> footprints;
            try {
                footprints = getFootprints();
            }
            catch( Exception e ) {
                resp.sendError( HttpServletResponse.SC_BAD_GATEWAY );
                return;
            }
            resp.setContentType( "application/json;charset=utf-8" );
            gson.toJson( footprints, resp.getWriter() );
            return;
        }

        if( "/image-detail".equals( pathInfo ) ) {
            String hash = req.getParameter( "hash" );
            if( hash == null || hash.isEmpty() ) {
                resp.sendError( HttpServletResponse.SC_BAD_REQUEST );
                return;
            }
            Map<String, Object> detail;
            try {
                detail = getImageDetail( hash );
            }
            catch( Exception e ) {
                resp.sendError( HttpServletResponse.SC_BAD_GATEWAY );
                return;
            }
            if( detail == null ) {
                resp.sendError( HttpServletResponse.SC_NOT_FOUND );
                return;
            }
            resp.setContentType( "application/json;charset=utf-8" );
            gson.toJson( detail, resp.getWriter() );
            return;
        }

        resp.sendError( HttpServletResponse.SC_NOT_FOUND );
    }

    private synchronized List<Map<String, Object>> getFootprints() throws Exception {
        long now = System.currentTimeMillis();
        if( cachedFootprints != null && now - cachedAt < CACHE_TTL_MS ) {
            return cachedFootprints;
        }

        List<Map<String, Object>> images = fetchAllImages();
        Map<String, Map<String, Object>> solutionsByKey = fetchSolutions( images );

        List<Map<String, Object>> footprints = new ArrayList<>();
        for( Map<String, Object> image : images ) {
            Map<String, Object> solution = solutionsByKey.get( solutionKey( image ) );
            if( solution == null ) continue; // not (yet) plate-solved

            Map<String, Object> footprint = new HashMap<>();
            footprint.put( "title", image.get( "title" ) );
            footprint.put( "hash", image.get( "hash" ) );
            footprint.put( "url", "https://app.astrobin.com/i/" + image.get( "hash" ) );
            footprint.put( "thumbnailUrl", extractThumbnailUrl( image ) );

            double[][] corners = extractAdvancedCorners( solution );
            if( corners != null ) {
                footprint.put( "corners", corners );
            }
            else {
                // No advanced solve for this one — fall back to reconstructing a rectangle from
                // center + size + orientation, which carries the same "which rotation direction"
                // risk described on preferAdvanced below (unavoidable without real corner data).
                // w/h must come from whichever revision the solution itself belongs to — the base
                // Image's own w/h can be a completely different aspect ratio once edits exist (the
                // same revision mismatch solutionKey() exists to avoid, just for pixel dimensions
                // instead of the plate-solve).
                Map<String, Object> finalRevision = finalRevision( image );
                Map<String, Object> dimensionsSource = finalRevision != null ? finalRevision : image;
                double pixscale = preferAdvanced( solution, "advanced_pixscale", "pixscale" );
                double w = asDouble( dimensionsSource.get( "w" ) );
                double h = asDouble( dimensionsSource.get( "h" ) );
                if( pixscale <= 0 || w <= 0 || h <= 0 ) continue;

                footprint.put( "ra", preferAdvanced( solution, "advanced_ra", "ra" ) );
                footprint.put( "dec", preferAdvanced( solution, "advanced_dec", "dec" ) );
                // pixscale is arcsec/pixel, so sensor-dimension-in-pixels × pixscale / 3600 is the
                // angular size — the same relationship the Planning FOV calculator uses inverted.
                footprint.put( "widthDeg", (w * pixscale) / 3600.0 );
                footprint.put( "heightDeg", (h * pixscale) / 3600.0 );
                footprint.put( "orientationDeg", preferAdvanced( solution, "advanced_orientation", "orientation" ) );
            }
            footprints.add( footprint );
        }

        cachedFootprints = footprints;
        cachedAt = now;
        return footprints;
    }

    /** On-demand (not part of the bulk/cached footprint listing — capture date is only needed for
     *  whichever single image the user actually clicks on the sky map, not eagerly for all few
     *  hundred). The lightweight gallery listing has no acquisition-date field at all; only the
     *  full per-image detail endpoint carries "deepSkyAcquisitions" (one entry per imaging
     *  session, e.g. spread across several nights for the same target). Reports the [min, max]
     *  session date range as a single "date" string — a plain date if it's all one night. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getImageDetail( String hash ) throws Exception {
        // Like fetchAllImages, this is a filtered list endpoint, not a dedicated detail route — it
        // still returns a paginated {"results": [...]} envelope even though the hash filter can only
        // ever match zero or one image.
        Map<String, Object> page = fetchJsonObject( API_BASE + "/images/image/?hash=" + hash );
        if( page == null ) return null;
        Object results = page.get( "results" );
        if( !(results instanceof List) || ((List<Object>) results).isEmpty() ) return null;
        Map<String, Object> image = (Map<String, Object>) ((List<Object>) results).get( 0 );

        Map<String, Object> detail = new HashMap<>();
        detail.put( "title", image.get( "title" ) );
        detail.put( "url", "https://app.astrobin.com/i/" + hash );
        detail.put( "date", captureDateRange( image.get( "deepSkyAcquisitions" ) ) );
        return detail;
    }

    @SuppressWarnings("unchecked")
    private static String captureDateRange( Object deepSkyAcquisitions ) {
        if( !(deepSkyAcquisitions instanceof List) ) return null;
        String min = null, max = null;
        for( Object entry : (List<Object>) deepSkyAcquisitions ) {
            if( !(entry instanceof Map) ) continue;
            Object date = ((Map<String, Object>) entry).get( "date" );
            if( date == null ) continue;
            String dateStr = String.valueOf( date );
            if( min == null || dateStr.compareTo( min ) < 0 ) min = dateStr;
            if( max == null || dateStr.compareTo( max ) > 0 ) max = dateStr;
        }
        if( min == null ) return null;
        return min.equals( max ) ? min : min + " – " + max;
    }

    /** The listing's own "finalGalleryThumbnail" is a 130x130 *square-cropped* thumbnail — fine for
     *  a gallery grid, but stretched over a footprint rectangle with the image's real (non-square)
     *  aspect ratio it reads as badly zoomed/cropped. Its "thumbnails" array also carries a
     *  "regular" alias (scaled to a fixed width, aspect ratio preserved), which is what a footprint
     *  actually needs — same aspect ratio as widthDeg:heightDeg below, since both derive from the
     *  same original pixel width/height. Falls back to the square one only if "regular" is somehow
     *  missing, so a footprint still shows *something* rather than nothing. */
    @SuppressWarnings("unchecked")
    private static String extractThumbnailUrl( Map<String, Object> image ) {
        Object thumbnails = image.get( "thumbnails" );
        if( thumbnails instanceof List ) {
            for( Object entry : (List<Object>) thumbnails ) {
                if( entry instanceof Map && "regular".equals( ((Map<String, Object>) entry).get( "alias" ) ) ) {
                    Object url = ((Map<String, Object>) entry).get( "url" );
                    if( url != null ) return String.valueOf( url );
                }
            }
        }
        Object fallback = image.get( "finalGalleryThumbnail" );
        return fallback == null ? null : String.valueOf( fallback );
    }

    /** Pages through the same lightweight "gallery" listing app.astrobin.com/u/&lt;username&gt;
     *  itself renders from — id/title/hash/pixel-size, but no astrometry (see fetchSolutions). */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchAllImages() throws Exception {
        List<Map<String, Object>> images = new ArrayList<>();
        int page = 1;
        while( true ) {
            String url = API_BASE + "/images/image/?user=" + ASTROBIN_USER_ID
                    + "&page=" + page + "&gallery-serializer=1&subsection=uploaded";
            Map<String, Object> pageResult = fetchJsonObject( url );
            if( pageResult == null ) break;

            Object results = pageResult.get( "results" );
            if( results instanceof List ) {
                images.addAll( (List<Map<String, Object>>) results );
            }

            if( pageResult.get( "next" ) == null ) break;
            page++;
        }
        return images;
    }

    /** Images with edit history carry their *own* plate-solve per revision, tied to the specific
     *  revision's ImageRevision id (Django content-type 20) rather than the base Image id (content
     *  type 19) — confirmed on a real image whose base-Image solution was ~90° off from what its
     *  current (edited) revision actually shows, because that base solution belonged to an earlier,
     *  differently-oriented upload the user has since replaced. The listing's own "revisions" array
     *  already tells us which revision (if any) is the current "final" one, with no extra request
     *  needed to find it. Images with no edit history (empty "revisions") have only the base
     *  Image-level solution, which is then also the current one. */
    private static String solutionKey( Map<String, Object> image ) {
        Map<String, Object> finalRevision = finalRevision( image );
        return finalRevision != null
                ? IMAGE_REVISION_CONTENT_TYPE_ID + ":" + asLong( finalRevision.get( "pk" ) )
                : IMAGE_CONTENT_TYPE_ID + ":" + asLong( image.get( "pk" ) );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> finalRevision( Map<String, Object> image ) {
        Object revisions = image.get( "revisions" );
        if( !(revisions instanceof List) ) return null;
        for( Object entry : (List<Object>) revisions ) {
            if( entry instanceof Map && Boolean.TRUE.equals( ((Map<String, Object>) entry).get( "isFinal" ) ) ) {
                return (Map<String, Object>) entry;
            }
        }
        return null;
    }

    /** AstroBin's plate-solving results live behind a separate endpoint, keyed by Django's generic
     *  content-type/object-id pair rather than being inlined into the image listing — fetched in
     *  batches (per content type, see solutionKey) since it accepts a comma-separated id list per
     *  request, instead of one round trip per image (a few hundred images would otherwise mean a
     *  few hundred requests). */
    private Map<String, Map<String, Object>> fetchSolutions( List<Map<String, Object>> images ) throws Exception {
        Map<Integer, List<Long>> idsByContentType = new HashMap<>();
        for( Map<String, Object> image : images ) {
            Map<String, Object> finalRevision = finalRevision( image );
            int contentType = finalRevision != null ? IMAGE_REVISION_CONTENT_TYPE_ID : IMAGE_CONTENT_TYPE_ID;
            long objectId = asLong( finalRevision != null ? finalRevision.get( "pk" ) : image.get( "pk" ) );
            idsByContentType.computeIfAbsent( contentType, k -> new ArrayList<>() ).add( objectId );
        }

        Map<String, Map<String, Object>> byKey = new HashMap<>();
        for( Map.Entry<Integer, List<Long>> entry : idsByContentType.entrySet() ) {
            int contentType = entry.getKey();
            List<Long> ids = entry.getValue();
            for( int start = 0; start < ids.size(); start += SOLUTION_BATCH_SIZE ) {
                List<Long> batch = ids.subList( start, Math.min( start + SOLUTION_BATCH_SIZE, ids.size() ) );
                String idsParam = batch.stream().map( String::valueOf ).collect( Collectors.joining( "," ) );
                String url = API_BASE + "/platesolving/solutions/?content_type=" + contentType + "&object_ids=" + idsParam;

                List<Map<String, Object>> solutions = fetchJsonArray( url );
                if( solutions == null ) continue;
                for( Map<String, Object> solution : solutions ) {
                    Object objectId = solution.get( "object_id" );
                    if( objectId != null ) byKey.put( contentType + ":" + objectId, solution );
                }
            }
        }
        return byKey;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchJsonObject( String url ) throws Exception {
        String body = fetchBody( url );
        return body == null ? null : gson.fromJson( body, Map.class );
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchJsonArray( String url ) throws Exception {
        String body = fetchBody( url );
        return body == null ? null : gson.fromJson( body, List.class );
    }

    private String fetchBody( String url ) throws Exception {
        HttpRequest request = HttpRequest.newBuilder( URI.create( url ) ).GET().build();
        HttpResponse<String> response = httpClient.send( request, HttpResponse.BodyHandlers.ofString() );
        return response.statusCode() == 200 ? response.body() : null;
    }

    /** AstroBin's "advanced" (distortion-corrected) plate-solve reports the real RA/Dec of all
     *  four image corners directly — using these outright sidesteps rotation-angle sign/handedness
     *  conventions entirely, which turned out to be genuinely ambiguous: both the "basic"
     *  (astrometry.net) orientation field *and* a fixed "prefer advanced" heuristic (see
     *  preferAdvanced) were each confirmed wrong on different real images from this gallery — one
     *  where basic and advanced orientation summed to ~360° (mirror images of each other, advanced
     *  being correct), another where even the advanced orientation alone rendered mirrored. There's
     *  no single reliable sign rule; the corners AstroBin already solved for are ground truth and
     *  need no rule at all. Returned in [top-left, top-right, bottom-right, bottom-left] winding
     *  order to match fovCorners' own corner convention on the frontend (corners 0 and 2 diagonal,
     *  corner 1 adjacent to both). Null if this image was never advanced-solved. */
    private static double[][] extractAdvancedCorners( Map<String, Object> solution ) {
        String[] keys = {
            "advanced_ra_top_left", "advanced_dec_top_left",
            "advanced_ra_top_right", "advanced_dec_top_right",
            "advanced_ra_bottom_right", "advanced_dec_bottom_right",
            "advanced_ra_bottom_left", "advanced_dec_bottom_left",
        };
        double[] values = new double[keys.length];
        for( int i = 0; i < keys.length; i++ ) {
            Object v = solution.get( keys[i] );
            if( v == null || "".equals( v ) ) return null;
            values[i] = asDouble( v );
        }
        return new double[][]{
            { values[0], values[1] }, // top-left
            { values[2], values[3] }, // top-right
            { values[4], values[5] }, // bottom-right
            { values[6], values[7] }, // bottom-left
        };
    }

    /** Fallback-only now (see extractAdvancedCorners) — still used for pixscale/ra/dec/orientation
     *  when an image has no advanced solve at all, on the theory that "advanced" is usually at
     *  least as good as "basic" even though it isn't a reliable fix for the mirroring problem. */
    private static double preferAdvanced( Map<String, Object> solution, String advancedKey, String basicKey ) {
        Object advanced = solution.get( advancedKey );
        if( advanced != null && !"".equals( advanced ) ) return asDouble( advanced );
        return asDouble( solution.get( basicKey ) );
    }

    private static double asDouble( Object value ) {
        if( value instanceof Number ) return ((Number) value).doubleValue();
        if( value instanceof String ) {
            try { return Double.parseDouble( (String) value ); } catch( NumberFormatException e ) { return 0; }
        }
        return 0;
    }

    private static long asLong( Object value ) {
        return (long) asDouble( value );
    }
}
