package de.pmneo.kstars;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

import com.sampullara.cli.Args;
import com.sampullara.cli.Argument;

public class ServerRunner {
		
	
	@Argument(alias = "p", required = false)
	public static int port = 8888;
	
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
	
	@Argument(alias = "s", required = false , valuesProvider = BooleanProvider.class)
	public static String syncMount = "true";
	
	public static void main(String[] args) throws Exception {
		Args.parseOrExit(ServerRunner.class, args);
		
		if( host != null && host.isEmpty() == false ) {
			KStarsCluster.Client client = new KStarsCluster.Client( host, port );
			client.setSyncMount( Boolean.valueOf(syncMount).booleanValue() );
			client.setAutoFocuseEnabled( Boolean.valueOf(autoFocus).booleanValue() );
			client.setCaptureSequence( loadSequence );
			client.connectToKStars();
			client.listen();
		}
		else {
			KStarsCluster.Server cluster = new KStarsCluster.Server(port);
			cluster.setLoadSchedule( loadSchedule );
			cluster.connectToKStars();

			cluster.listen();
		}
	}
}
