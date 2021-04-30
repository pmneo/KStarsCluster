package de.pmneo.kstars;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.exceptions.DBusException;
import org.kde.kstars.ekos.Align;
import org.kde.kstars.ekos.Align.AlignState;
import org.kde.kstars.ekos.Capture;
import org.kde.kstars.ekos.Capture.CaptureStatus;
import org.kde.kstars.ekos.Focus;
import org.kde.kstars.ekos.Focus.FocusState;
import org.kde.kstars.ekos.Guide;
import org.kde.kstars.ekos.Guide.GuideStatus;
import org.kde.kstars.ekos.Mount;
import org.kde.kstars.ekos.Mount.MeridianFlipStatus;
import org.kde.kstars.ekos.Mount.MountStatus;
import org.kde.kstars.ekos.Mount.ParkStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class KStarsCluster {
	protected final Logger logger = LoggerFactory.getLogger(getClass());
	protected DBusConnection con;
	
	protected final Device<Align> align;
	protected final Device<Focus> focus;
	protected final Device<Guide> guide;
	protected final Device<Capture> capture;
	protected final Device<Mount> mount;
	
	protected final List< Device<?> > mandatoryDevices = new ArrayList<Device<?>>();
	protected final List< Device<?> > devices = new ArrayList<Device<?>>();
	
	public KStarsCluster( ) throws DBusException {
		/* Get a connection to the session bus so we can get data */
		con = DBusConnection.getConnection( DBusConnection.DBusBusType.SESSION );
		
		this.guide = new Device<>( con, "org.kde.kstars", "/KStars/Ekos/Guide", Guide.class );
		this.devices.add( this.guide );
		this.mandatoryDevices.add( this.guide );
		this.guide.addSigHandler( Guide.newStatus.class, status -> {
			this.handleGuideStatus( status.getStatus() );
		} );


		this.capture = new Device<>( con, "org.kde.kstars", "/KStars/Ekos/Capture", Capture.class );
		this.devices.add( this.capture );
		this.mandatoryDevices.add( this.capture );
		this.capture.addSigHandler( Capture.newStatus.class, status -> {
			this.handleCaptureStatus( status.getStatus() );
		} );
		
		
		this.mount = new Device<>( con, "org.kde.kstars", "/KStars/Ekos/Mount", Mount.class );
		this.devices.add( this.mount );
		this.mandatoryDevices.add( this.mount );
		this.mount.addSigHandler( Mount.newStatus.class, status -> {
			this.handleMountStatus( status.getStatus() );
		} );
		this.mount.addSigHandler( Mount.newParkStatus.class, status -> {
			this.handleMountParkStatus( status.getStatus() );
		} );
		this.mount.addSigHandler( Mount.newMeridianFlipStatus.class, status -> {
			this.handleMeridianFlipStatus( status.getStatus() );
		} );
		
		
		this.align = new Device<>( con, "org.kde.kstars", "/KStars/Ekos/Align", Align.class );
		this.devices.add( this.align );
		this.align.addSigHandler( Align.newStatus.class, status -> {
			this.handleAlignStatus( status.getStatus() );
		} );
		this.align.addSigHandler( Align.newSolution.class, status -> {
			System.out.println( "newSolution: " + Arrays.toString( status.getSolution() ) );
		} );

		
		this.focus = new Device<>( con, "org.kde.kstars", "/KStars/Ekos/Focus", Focus.class );
		this.devices.add( this.focus );
		this.focus.addSigHandler( Focus.newStatus.class, status -> {
			this.handleFocusStatus( status.getStatus() );
		} );
	}
	
	public void connectToKStars() {
		
		boolean failed = false;
		
		do {
			failed = false;
			for( Device<?> d : mandatoryDevices ) {
				try {
					d.readAll();
				}
				catch( Throwable t ) {
					failed = true;
					logger.error( "Failed to read from device " + d.interfaceName, t );
				}
			}
		} while( failed );
		
		
		System.out.println( "Kstars is ready" );
	}
	
	
	protected void handleGuideStatus( GuideStatus state ) {
		//System.out.println( "handleGuideStatus " + state );
	}
	
	protected void handleCaptureStatus( CaptureStatus state ) {
		//System.out.println( "handleCaptureStatus " + state );
	}
	
	protected void handleMountStatus( MountStatus state ) {
		//System.out.println( "handleMountStatus " + state );
	}
	protected void handleMountParkStatus( ParkStatus state ) {
		//System.out.println( "handleMountParkStatus " + state );
	}
	protected void handleMeridianFlipStatus( MeridianFlipStatus state ) {
		//System.out.println( "handleMeridianFlipStatus " + state );
	}
	
	
	protected void handleAlignStatus( AlignState state ) {
		//System.out.println( "handleAlignStatus " + state );
	}
	protected void handleFocusStatus( FocusState state ) {
		//System.out.println( "handleFocusStatus " + state );
	}
	
	public static class SocketHandler {
		public final Socket socket;
		
		public final InputStream _input;
		public final OutputStream _output;
		
		public ObjectInputStream oInput;
		public ObjectOutputStream oOutput;
		
		public SocketHandler( final Socket socket ) throws IOException {
			this.socket = socket;
			this._input = ( socket.getInputStream() );
			this._output = ( socket.getOutputStream() );
			
			oInput = null;
			oOutput = null;
		}

		public void writeObject( Object frame ) throws IOException {
			synchronized ( _output ) {
				this.getOutput().writeObject( frame );
				this.getOutput().flush();
			}
		}
		
		public ObjectOutputStream getOutput() throws IOException {
			synchronized ( _output ) {
				if( oOutput == null ) {
					oOutput = new ObjectOutputStream( _output );
				}
			}
			return oOutput;
		}
		public ObjectInputStream getInput() throws IOException {
			synchronized ( _input ) {
				if( oInput == null ) {
					oInput = new ObjectInputStream( _input );
				}
			}
			return oInput;
		}
	}
	
	public static void receive( final SocketHandler socket, final BiConsumer<SocketHandler, Object> frameReceived, final BiConsumer<SocketHandler, Throwable > disconnected ) {
		try {
			while( socket.socket.isConnected() ) {
				final Object frame = socket.getInput().readObject();
				if( frame != null ) {
					frameReceived.accept( socket, frame );
				}
			}
		}
		catch( Throwable t ) {
			disconnected.accept( socket, t );
			return;
		}
		
		disconnected.accept( socket, null );
	}

	public static class Server extends KStarsCluster {
		protected final ServerSocket serverSocket;
		
		public Server( int listenPort ) throws IOException, DBusException {
			super( );
			serverSocket = new ServerSocket( listenPort );
		}
		
		@Override
		protected void handleCaptureStatus(CaptureStatus state) {
			super.handleCaptureStatus(state);
			
			final Map<String,Object> payload = capture.getParsedProperties();
			payload.put( "action", "handleCaptureStatus" );
			writeToAllClients( payload );
		}
		@Override
		protected void handleAlignStatus(AlignState state) {
			super.handleAlignStatus(state);
			
			final Map<String,Object> payload = align.getParsedProperties();
			payload.put( "action", "handleAlignStatus" );
			writeToAllClients( payload );
		}
		@Override
		protected void handleFocusStatus(FocusState state) {
			super.handleFocusStatus(state);
			
			final Map<String,Object> payload = focus.getParsedProperties();
			payload.put( "action", "handleFocusStatus" );
			writeToAllClients( payload );
		}
		@Override
		protected void handleMeridianFlipStatus(MeridianFlipStatus state) {
			super.handleMeridianFlipStatus(state);
			
			final Map<String,Object> payload = mount.getParsedProperties();
			payload.put( "action", "handleMeridianFlipStatus" );
			writeToAllClients( payload );
		}
		@Override
		protected void handleMountParkStatus(ParkStatus state) {
			super.handleMountParkStatus(state);
			
			final Map<String,Object> payload = mount.getParsedProperties();
			payload.put( "action", "handleMountParkStatus" );
			writeToAllClients( payload );
		}
		
		@Override
		protected void handleGuideStatus(GuideStatus state) {
			super.handleGuideStatus(state);
			
			final Map<String,Object> payload = guide.getParsedProperties();
			payload.put( "action", "handleGuideStatus" );
			writeToAllClients( payload );
		}
		
		@Override
		protected void handleMountStatus(MountStatus state) {
			super.handleMountStatus(state);
			
			final Map<String,Object> payload = mount.getParsedProperties();
			payload.put( "action", "handleMountStatus" );
			writeToAllClients( payload );
		}

		private void writeToAllClients( Map<String, Object> payload ) {
			
			payload.remove( "logText" ); 
			
			for( SocketHandler handler : clients.keySet() ) {
				try {
					handler.writeObject( payload );
				} catch (IOException e) {
					logger.error( "Failed to inform client {0}", e, handler );
				}
			}
		}
		
		private ConcurrentHashMap< SocketHandler, Thread > clients = new ConcurrentHashMap<SocketHandler, Thread>();
		
		private void clientFrameReceived( SocketHandler client, Object frame ) {
			//this should currently not happen?
			System.out.println( "Received Client Frame: " + frame );
		}

		public void listen() {
			while( true ) {
				try {
					System.out.println( "Listen" );
					
					final Socket client = this.serverSocket.accept();
					
					if( client != null ) {
						System.out.println( "Connected" );
						
						logger.info( "Client {0} connected", client );
						
						final SocketHandler clientHandler = new SocketHandler( client );
						final Thread rThread = new Thread( () -> {
							receive( clientHandler, this::clientFrameReceived, (c,t) -> {
								clients.remove( c );
							} );
						} );
						clients.put( clientHandler, rThread );
								
						rThread.start();
						
						clientHandler.writeObject( "Hello Client" );
					}
				}
				catch( Throwable t ) {
					logger.error( "Failed to accept", t );
				}
			}
		}
	}
	
	public static class Client extends KStarsCluster {
		
		protected final String host;
		protected final int listenPort; 
		
		private boolean syncMount = false;
		
		public Client( String host, int listenPort ) throws DBusException {
			super( );
			this.host = host;
			this.listenPort = listenPort;
		}
		
		public void setSyncMount(boolean syncMount) {
			this.syncMount = syncMount;
		}
		public boolean isSyncMount() {
			return syncMount;
		}
		
		private void serverFrameReceived( SocketHandler client, Object frame ) {
			try {
				//this should currently not happen?
				
				if( frame instanceof Map ) {
					@SuppressWarnings("unchecked")
					Map<String, Object> payload = (Map<String, Object>) frame;
					
					String action = (String) payload.get( "action" );
					
					if( "handleMountStatus".equals( action ) ) {
						MountStatus status = (MountStatus) payload.get( "status" );
						
						System.out.println( status );
						
						switch( status ) {
							case MOUNT_ERROR:
								break;
							case MOUNT_IDLE:
								this.suspendCapture( "Mount" );
								
								if( isSyncMount() ) {
									this.mount.methods.unpark();
								}
								break;
							case MOUNT_MOVING:
								this.suspendCapture( "Mount" );
								
								break;
							case MOUNT_PARKED:
								this.suspendCapture( "Mount" );
								
								if( isSyncMount() ) {
									this.mount.methods.park();
								}
								break;
							case MOUNT_PARKING:
								this.suspendCapture( "Mount" );
								
								break;
							case MOUNT_SLEWING:
								this.suspendCapture( "Mount" );
								
								if( isSyncMount() ) {
									this.mount.methods.unpark();
								}
								break;
							case MOUNT_TRACKING:
								this.resumeCapture( "Mount" );
								
								if( isSyncMount() ) {
									@SuppressWarnings("unchecked") 
									final List<Double> pos = (List<Double>) payload.get( "equatorialCoords" );
									this.mount.methods.slew( pos.get(0), pos.get(1) );
								}
								break;
							default:
								break;
						}
					}
					else if( "handleGuideStatus".equals( action ) ) {
						GuideStatus status = (GuideStatus) payload.get( "status" );
						
						switch( status ) {
							case GUIDE_ABORTED:
							case GUIDE_MANUAL_DITHERING:
							case GUIDE_DITHERING:
							case GUIDE_CALIBRATING:
								suspendCapture( "Guding" );
							break;
								
							case GUIDE_GUIDING:
								resumeCapture( "Guding" );
							break;
							
							default:
								break;
						}
					}
				}
			}
			catch( Throwable t ) {
				logger.error( "Error due handle server frame", t );
			}
		}

		private void resumeCapture( String source ) {
			if( restartCapture.getAndSet( false ) ) {
				this.capture.methods.start();
			}
		}

		private void suspendCapture( String source ) {
			if( captureActive.get() ) {
				
				int jobId = this.capture.methods.getActiveJobID();
				double timeLeft = this.capture.methods.getJobExposureProgress( jobId ) ;
				while( timeLeft > 0 && timeLeft < 2 ) {
					timeLeft = this.capture.methods.getJobExposureProgress( jobId ) ;
				}
				
				restartCapture.set( true );
				this.capture.methods.stop();
			}
		}

		private AtomicBoolean restartCapture = new AtomicBoolean( false );
		
		private AtomicBoolean captureActive = new AtomicBoolean( false );
		
		@Override
		protected void handleCaptureStatus(CaptureStatus state) {
			super.handleCaptureStatus(state);
			
			switch (state) {
			
				case CAPTURE_CAPTURING:
					if( !captureActive.getAndSet( true ) ) {
						System.out.println( "Capture is Active");
					}
				break;
				
				case CAPTURE_IMAGE_RECEIVED:
				case CAPTURE_PAUSED:
				case CAPTURE_COMPLETE:
				case CAPTURE_ABORTED:
				case CAPTURE_SUSPENDED:
				case CAPTURE_IDLE:
					if( captureActive.getAndSet( false ) ) {
						System.out.println( "Capture is Inactive");
					}
				break;
				
				default:
					System.out.println( "Unhandled " + state);
					break;
			
			}
		}
		
		public void listen()  {
			
			while( true ) {
				try {
					SocketHandler clientHandler = new SocketHandler( new Socket( host, listenPort ) );
					clientHandler.writeObject( "HelloServer" );
					System.out.println( "Connected ...");
					receive( 
						clientHandler, 
						this::serverFrameReceived, 
						(c,t) -> {
						
						} 
					);
				}
				catch( Throwable t ) {
					//logger.error( "Failed to handle ", t );
					try {
						Thread.sleep( 1000 );
					} catch (InterruptedException e) {
					}
				}
			}
		}
	}
	
	
	public static void main(String[] args) throws DBusException, InterruptedException, IOException {
		
		KStarsCluster.Server cluster = new KStarsCluster.Server(8888);
		
		cluster.connectToKStars();
		
		for( Device<?> d : cluster.devices ) {
			d.readAll();
			System.out.println( d );
		}
		
		cluster.listen();

		//DBusViewer.main( args );
	}
}
