package de.pmneo.kstars;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

import com.sampullara.cli.Args;
import com.sampullara.cli.Argument;

public class ClientRunner {

	public static class BooleanProvider implements Callable< List<String> > {
		@Override
		public List<String> call() throws Exception {
			return Arrays.asList( "true", "false" );
		}
	}
	
	@Argument(alias = "h", required = true)
	private static String host;
	
	@Argument(alias = "p", required = false)
	private static int port = 8888;
	
	@Argument(alias = "a", required = false, valuesProvider = BooleanProvider.class )
	private static String autoFocus = "true";
	
	@Argument(alias = "s", required = false , valuesProvider = BooleanProvider.class)
	private static String syncMount = "true";
	
	public static void main(String[] args) throws Exception {
		
		Args.parseOrExit(ClientRunner.class, args);
		
		KStarsCluster.Client client = new KStarsCluster.Client( host, port );
		client.setSyncMount( Boolean.valueOf(syncMount).booleanValue() );
		client.setAutoFocuseEnabled( Boolean.valueOf(autoFocus).booleanValue() );
		client.connectToKStars();
		client.listen();
	}
}
