package de.pmneo.kstars;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;

import com.sampullara.cli.Args;
import com.sampullara.cli.Argument;

import de.pmneo.kstars.web.CommandServlet;
import de.pmneo.kstars.web.LoggingSocket;


public class ServerRunner {
		
	@Argument(alias = "wp", required = false)
	public static int webPort = 8080;
	
	@Argument(alias = "p", required = false)
	public static int port = 8888;

	@Argument(alias = "pc", required = false)
	public static int preCoolTemp = -15;
	
	@Argument(alias = "ls", required = false )
	public static String loadSchedule = "~/current_schedule.esl";

	@Argument(alias = "lc", required = false )
	public static String loadSequence = "~/current_sequence.esq";

	public static class BooleanProvider implements Callable< List<String> > {
		@Override
		public List<String> call() throws Exception {
			return Arrays.asList( "true", "false" );
		}
	}
	
	@Argument(alias = "h", required = false)
	public static String host;
		
	@Argument(alias = "a", required = false, valuesProvider = BooleanProvider.class )
	public static String autoFocus = "true";
	
	public static void main(String[] args) throws Exception {
		Args.parseOrExit(ServerRunner.class, args);
		
		KStarsCluster cluster = null;

		if( host != null && host.isEmpty() == false ) {
			KStarsClusterClient client = new KStarsClusterClient( host, port );
			client.setAutoFocuseEnabled( Boolean.valueOf(autoFocus).booleanValue() );
			client.setCaptureSequence( loadSequence );
			cluster = client;
		}
		else {
			KStarsClusterServer server = new KStarsClusterServer(port);
			server.setLoadSchedule( loadSchedule );
			cluster = server;
		}

		cluster.setPreCoolTemp(preCoolTemp);
		cluster.connectToKStars();

		startServer( cluster );

		cluster.listen();
	}

	public static void startServer( KStarsCluster cluster ) throws Exception
    {
        Server server = new Server( webPort );

        URL webRootLocation = null;
		
		File devWeb = new File( "./src/web/index.html" );

		if( devWeb.exists() ) {
			webRootLocation = devWeb.getCanonicalFile().toURI().toURL();
		}
		
		if( webRootLocation == null ) {
			webRootLocation = ServerRunner.class.getResource("/web/index.html");
		}
        if (webRootLocation == null)
        {
            throw new IllegalStateException("Unable to determine webroot URL location");
        }

        URI webRootUri = URI.create( webRootLocation.toURI().toASCIIString().replaceFirst("/index.html$", "/") );
        System.err.printf("Web Root URI: %s%n", webRootUri);

        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        contextHandler.setBaseResource(Resource.newResource(webRootUri));
        contextHandler.setWelcomeFiles(new String[]{"index.html"});

		contextHandler.setAttribute( "cluster", cluster );

        contextHandler.getMimeTypes().addMimeMapping("txt", "text/plain;charset=utf-8");

        server.setHandler(contextHandler);

        // Add WebSocket endpoints
        JakartaWebSocketServletContainerInitializer.configure(contextHandler, (context, wsContainer) ->
            wsContainer.addEndpoint(LoggingSocket.class) );

        // Add Servlet endpoints

		contextHandler.addServlet(CommandServlet.class, "/cmd/*");
        contextHandler.addServlet(DefaultServlet.class, "/");

        // Start Server
        server.start();
    }
}
