package de.pmneo.kstars.web;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;

import de.pmneo.kstars.KStarsCluster;
import de.pmneo.kstars.KStarsConfig;
import de.pmneo.kstars.utils.ArtificialHorizon;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Backs the Sky Map's "simulate the horizon at a given time" planning feature — everything here
 * is read-only and near-static (location, terrain panorama, artificial horizon regions all only
 * ever change if the user reconfigures KStars itself), unlike the per-second status broadcast.
 */
public class ObservatoryServlet extends HttpServlet {

    private KStarsConfig config;
    private final Gson gson = new Gson();

    @Override
    public void init( ServletConfig servletConfig ) throws ServletException {
        super.init( servletConfig );
        KStarsCluster cluster = (KStarsCluster) getServletContext().getAttribute( "cluster" );
        config = cluster.config;
    }

    @Override
    protected void doGet( HttpServletRequest req, HttpServletResponse resp ) throws IOException {
        String pathInfo = req.getPathInfo();
        if( pathInfo == null ) {
            resp.sendError( HttpServletResponse.SC_NOT_FOUND );
            return;
        }

        switch( pathInfo ) {
            case "/info":
                handleInfo( resp );
                return;

            case "/artificial-horizon":
                handleArtificialHorizon( resp );
                return;

            case "/terrain.png":
                handleTerrain( req, resp );
                return;

            default:
                resp.sendError( HttpServletResponse.SC_NOT_FOUND );
        }
    }

    private void handleInfo( HttpServletResponse resp ) throws IOException {
        Map<String,Object> res = new LinkedHashMap<>();
        res.put( "latitude", config.getLatitude() );
        res.put( "longitude", config.getLongitude() );
        res.put( "terrainCorrectAz", config.getTerrainCorrectAz() );
        res.put( "terrainCorrectAlt", config.getTerrainCorrectAlt() );
        res.put( "hasTerrain", config.getTerrainSource() != null && new File( config.getTerrainSource() ).isFile() );
        writeJson( resp, res );
    }

    private void handleArtificialHorizon( HttpServletResponse resp ) throws IOException {
        List<ArtificialHorizon.Region> regions = ArtificialHorizon.readEnabledRegions();
        writeJson( resp, regions );
    }

    /** Served straight off disk rather than embedded in /info — this is an 8+MB panorama image,
     *  fetched once by the browser and cached (it changes only if the user re-points KStars at a
     *  different terrain file), not something to inline as base64 JSON on every card mount. */
    private void handleTerrain( HttpServletRequest req, HttpServletResponse resp ) throws IOException {
        String source = config.getTerrainSource();
        File file = source == null ? null : new File( source );
        if( file == null || !file.isFile() ) {
            resp.sendError( HttpServletResponse.SC_NOT_FOUND );
            return;
        }

        long lastModified = file.lastModified();
        String etag = "\"" + file.length() + "-" + lastModified + "\"";
        resp.setHeader( "ETag", etag );
        resp.setHeader( "Cache-Control", "private, max-age=86400" );
        if( etag.equals( req.getHeader( "If-None-Match" ) ) ) {
            resp.setStatus( HttpServletResponse.SC_NOT_MODIFIED );
            return;
        }

        byte[] bytes = Files.readAllBytes( file.toPath() );
        resp.setContentType( "image/png" );
        resp.setContentLength( bytes.length );
        resp.getOutputStream().write( bytes );
        resp.getOutputStream().flush();
    }

    private void writeJson( HttpServletResponse resp, Object value ) throws IOException {
        resp.setContentType( "application/json;charset=utf-8" );
        gson.toJson( value, resp.getWriter() );
    }
}
