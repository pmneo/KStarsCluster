package de.pmneo.kstars.web;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import com.google.gson.Gson;

import de.pmneo.kstars.KStarsCluster;
import de.pmneo.kstars.utils.FitsThumbnailCache;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Serves the observatory's own captured FITS frames as browser-viewable JPEG thumbnails (with an
 * adjustable linear stretch), and reports an auto-computed stretch for a given frame. The actual
 * FITS decoding/rendering/caching (FitsThumbnailCache) is entirely self-contained; only the "is
 * this filename something we actually captured" security check (never render an arbitrary
 * client-supplied path) needs KStarsCluster's own live captured-image history, fetched via the
 * shared "cluster" servlet context attribute, same as CommandServlet.
 */
public class ImageServlet extends HttpServlet {

    private KStarsCluster cluster;
    private final FitsThumbnailCache thumbnails = new FitsThumbnailCache();
    private final Gson gson = new Gson();

    @Override
    public void init( ServletConfig config ) throws ServletException {
        super.init( config );
        cluster = (KStarsCluster) getServletContext().getAttribute( "cluster" );
    }

    @Override
    protected void doGet( HttpServletRequest req, HttpServletResponse resp ) throws IOException {
        String pathInfo = req.getPathInfo();
        if( pathInfo == null ) {
            resp.sendError( HttpServletResponse.SC_NOT_FOUND );
            return;
        }

        try {
            switch( pathInfo ) {
                case "/thumb":
                    handleThumb( req, resp );
                    return;

                case "/autostretch":
                    handleAutostretch( req, resp );
                    return;

                default:
                    resp.sendError( HttpServletResponse.SC_NOT_FOUND );
            }
        }
        catch( IOException e ) {
            throw e;
        }
        catch( Exception e ) {
            throw new IOException( e );
        }
    }

    private void handleThumb( HttpServletRequest req, HttpServletResponse resp ) throws Exception {
        File fitsFile = resolveFileParam( req, resp );
        if( fitsFile == null ) {
            // A missing/blank "file" param (400) is a caller bug — leave that as a plain error
            // response. A file that's unrecognized/gone (404) is the normal case for e.g.
            // analyze-log-restored images whose files have since been moved or deleted — serve a
            // placeholder instead of leaving the <img> tag broken.
            if( resp.getStatus() == HttpServletResponse.SC_NOT_FOUND ) {
                resp.setStatus( HttpServletResponse.SC_OK );
                writeJpeg( resp, getNotFoundImageBytes() );
            }
            return;
        }

        int maxDim = clamp( parseIntParam( req, "maxDim", 320 ), 32, 8000 );
        double shadows = clamp( parseDoubleParam( req, "shadows", 0.0 ), 0, 1 );
        double midtones = clamp( parseDoubleParam( req, "midtones", 0.5 ), 0, 1 );
        double highlights = clamp( parseDoubleParam( req, "highlights", 1.0 ), 0, 1 );

        byte[] jpeg = thumbnails.renderThumbnail( fitsFile, maxDim, shadows, midtones, highlights );
        writeJpeg( resp, jpeg );
    }

    private void handleAutostretch( HttpServletRequest req, HttpServletResponse resp ) throws Exception {
        File fitsFile = resolveFileParam( req, resp );
        if( fitsFile == null ) {
            return;
        }

        boolean strong = "true".equals( req.getParameter( "strong" ) );
        double[] shmh = thumbnails.computeAutoStretch( fitsFile, strong );

        Map<String,Object> res = new LinkedHashMap<>();
        res.put( "shadows", shmh[0] );
        res.put( "midtones", shmh[1] );
        res.put( "highlights", shmh[2] );
        resp.setContentType( "application/json;charset=utf-8" );
        gson.toJson( res, resp.getWriter() );
    }

    /** Shared by the thumb/autostretch handlers: validates the "file" param, 404s on the response if it's unusable. */
    private File resolveFileParam( HttpServletRequest req, HttpServletResponse resp ) {
        String file = req.getParameter( "file" );
        if( file == null || file.isBlank() ) {
            resp.setStatus( HttpServletResponse.SC_BAD_REQUEST );
            return null;
        }
        File fitsFile = cluster.history.resolveKnownCapturedFile( file );
        if( fitsFile == null ) {
            resp.setStatus( HttpServletResponse.SC_NOT_FOUND );
            return null;
        }
        return fitsFile;
    }

    private static void writeJpeg( HttpServletResponse resp, byte[] jpeg ) throws IOException {
        resp.setContentType( "image/jpeg" );
        resp.setContentLength( jpeg.length );
        resp.getOutputStream().write( jpeg );
        resp.getOutputStream().flush();
    }

    private static byte[] notFoundImageBytes;

    /**
     * Images restored from the Ekos analyze log on startup can point at files that have since
     * been moved (e.g. by an external reorganizing tool) or deleted — rather than a broken-image
     * icon in the browser, the thumb action serves this placeholder instead.
     */
    private static synchronized byte[] getNotFoundImageBytes() throws IOException {
        if( notFoundImageBytes != null ) {
            return notFoundImageBytes;
        }

        BufferedImage img = new BufferedImage( 320, 213, BufferedImage.TYPE_INT_RGB );
        Graphics2D g = img.createGraphics();
        g.setRenderingHint( RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON );
        g.setColor( new Color( 0x20, 0x20, 0x20 ) );
        g.fillRect( 0, 0, img.getWidth(), img.getHeight() );
        g.setColor( new Color( 0x80, 0x80, 0x80 ) );
        g.setFont( g.getFont().deriveFont( 18f ) );
        FontMetrics fm = g.getFontMetrics();
        String text = "Image not found";
        g.drawString( text, (img.getWidth() - fm.stringWidth( text )) / 2, img.getHeight() / 2 );
        g.dispose();

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write( img, "jpg", bytes );
        notFoundImageBytes = bytes.toByteArray();
        return notFoundImageBytes;
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

    private static double parseDoubleParam( HttpServletRequest req, String name, double fallback ) {
        try {
            String v = req.getParameter( name );
            return v == null ? fallback : Double.parseDouble( v );
        }
        catch( Throwable t ) {
            return fallback;
        }
    }

    private static int clamp( int v, int lo, int hi ) {
        return Math.max( lo, Math.min( hi, v ) );
    }

    private static double clamp( double v, double lo, double hi ) {
        return Math.max( lo, Math.min( hi, v ) );
    }
}
