package de.pmneo.kstars.utils;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.pmneo.kstars.SimpleLogger;

/**
 * Caches FitsThumbnail's own (fairly expensive — decoding a whole FITS file to sample
 * median/MAD, or to render a stretched JPEG) renders, keyed by the file's path+mtime plus
 * whatever rendering parameters were asked for. Entirely self-contained (no dependency on
 * KStarsCluster/SessionHistory) — it only ever renders whatever File it's given, the same way
 * ImageServlet's own "is this filename something we actually captured" security check
 * (SessionHistory.resolveKnownCapturedFile) is a completely separate concern from actually
 * rendering the file it resolves to.
 */
public class FitsThumbnailCache {

    /** The result is a handful of doubles, so a plain in-memory map is enough — no need for the
     *  disk cache renderThumbnail below uses. */
    private final Map<String,double[]> autoStretchCache = new ConcurrentHashMap<>();

    /** Every thumbnail in the image strip auto-fetches its own auto-stretch, so the same
     *  file+strong combo gets requested repeatedly (re-renders, multiple tabs, polling). */
    public double[] computeAutoStretch( File fitsFile, boolean strong ) throws Exception {
        String cacheKey = fitsFile.getAbsolutePath() + "_" + fitsFile.lastModified() + "_" + strong;

        double[] cached = autoStretchCache.get( cacheKey );
        if( cached != null ) {
            return cached;
        }

        double[] shmh = FitsThumbnail.computeAutoStretch( fitsFile, strong );
        autoStretchCache.put( cacheKey, shmh );
        return shmh;
    }

    private static final File THUMBNAIL_CACHE_DIR = new File( "./thumb-cache" );

    /** Serves a cached render if present, otherwise renders and caches one keyed by path+mtime+size+stretch. */
    public byte[] renderThumbnail( File fitsFile, int maxDim, double shadows, double midtones, double highlights ) throws Exception {
        THUMBNAIL_CACHE_DIR.mkdirs();

        String cacheKey = Integer.toHexString( fitsFile.getAbsolutePath().hashCode() ) + "_" + fitsFile.lastModified()
                + "_" + maxDim + "_" + shadows + "_" + midtones + "_" + highlights + ".jpg";
        File cacheFile = new File( THUMBNAIL_CACHE_DIR, cacheKey );

        if( cacheFile.isFile() ) {
            return Files.readAllBytes( cacheFile.toPath() );
        }

        byte[] jpeg = FitsThumbnail.render( fitsFile, maxDim, shadows, midtones, highlights );

        try {
            Files.write( cacheFile.toPath(), jpeg );
        }
        catch( Throwable t ) {
            // Not fatal — the caller still gets a correctly-rendered jpeg for this request, just
            // without it being cached to disk for next time.
            SimpleLogger.getLogger().logError( "Failed to cache thumbnail for " + fitsFile, t );
        }

        return jpeg;
    }
}
