package de.pmneo.kstars;

public class ClientRunner {

	public static void main(String[] args) throws Exception {
		
		int argNum = 0;
		
		String host = "192.168.0.145";
		if( args.length > argNum ) {
			host = args[ argNum ];
		}
		argNum++;
		
		int port = 8888;
		if( args.length > argNum ) {
			port = Integer.parseInt( args[argNum] );
		}
		argNum++;
		
		boolean syncMount = true;
		if( args.length > argNum ) {
			syncMount = Boolean.parseBoolean( args[argNum] );
		}
		argNum++;
		
		KStarsCluster.Client client = new KStarsCluster.Client(  host, port );
		client.setSyncMount( syncMount );
		client.connectToKStars();
		client.listen();
	}
}
