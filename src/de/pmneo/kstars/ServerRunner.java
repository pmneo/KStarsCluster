package de.pmneo.kstars;

public class ServerRunner {

	public static void main(String[] args) throws Exception {
		
		int port = 8888;
		
		if( args.length > 0 ) {
			port = Integer.parseInt( args[0] );
		}
		
		KStarsCluster.Server cluster = new KStarsCluster.Server(port);
		cluster.connectToKStars();
		cluster.listen();
	}
}
