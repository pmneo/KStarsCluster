package de.pmneo.kstars.web;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.GsonBuilder;

import de.pmneo.kstars.KStarsCluster;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class CommandServlet extends HttpServlet
{
    public interface Action {
        public Object doAction( String[] pathParts, HttpServletRequest req, HttpServletResponse resp ) throws Exception;
    }

    private Map<String,Action> actions = new HashMap<>();

    public CommandServlet() {
        super();


    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);

        KStarsCluster cluster = (KStarsCluster) this.getServletContext().getAttribute( "cluster" );
        cluster.addActions(actions);
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
       
        String pathInfo = req.getPathInfo();

        String action = null;
        String[] parts = null;

        if( pathInfo != null ) {
            parts = (pathInfo.startsWith( "/" ) ? pathInfo.substring(1) : pathInfo ).split( "/" );
        }
        else {
            parts = new String[0];
        }

        if( parts.length > 0 ) {
            action = parts[0];
        }
        
        Action a = actions.get( action );

        if( a != null ) {
            try {
                Object res = a.doAction( parts, req, resp);

                if( res != null ) {
                    new GsonBuilder().setPrettyPrinting().create().toJson( res, resp.getWriter() );
                }
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException( e );
            }
        }
        else {
            Map<String,Object> res = new HashMap<>();
       
            res.put( "actions", actions.keySet() );
            res.put( "error", "Invalid action" );

            new GsonBuilder().setPrettyPrinting().create().toJson( res, resp.getWriter() );
        }

    }
}