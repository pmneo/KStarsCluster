package de.pmneo.kstars.web;

import java.io.IOException;

import de.pmneo.kstars.KStarsCluster;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class StatusServlet extends HttpServlet
{
    @Override
    public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
    {
        KStarsCluster cluster = (KStarsCluster) this.getServletContext().getAttribute( "cluster" );

        resp.getWriter().println( cluster.getClass().getName() );
    }
}