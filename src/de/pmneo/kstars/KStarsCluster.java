package de.pmneo.kstars;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
import org.kde.kstars.ekos.Scheduler;
import org.kde.kstars.ekos.Scheduler.SchedulerState;

public abstract class KStarsCluster {
	protected DBusConnection con;
	
	protected final Device<Align> align;
	protected final Device<Focus> focus;
	protected final Device<Guide> guide;
	protected final Device<Capture> capture;
	protected final Device<Mount> mount;
	protected final Device<Scheduler> scheduler;
	
	protected final List< Device<?> > mandatoryDevices = new ArrayList<Device<?>>();
	protected final List< Device<?> > devices = new ArrayList<Device<?>>();
	
	
	protected final AtomicBoolean kStarsConnected = new AtomicBoolean(false);
	
	public KStarsCluster( ) throws DBusException {
		/* Get a connection to the session bus so we can get data */
		con = DBusConnection.getConnection( DBusConnection.DBusBusType.SESSION );
		
		this.guide = new Device<>( con, "org.kde.kstars", "/KStars/Ekos/Guide", Guide.class );
		this.devices.add( this.guide );
		this.mandatoryDevices.add( this.guide );
		this.guide.addNewStatusHandler( Guide.newStatus.class, status -> {
			this.handleGuideStatus( status.getStatus() );
		} );


		this.capture = new Device<>( con, "org.kde.kstars", "/KStars/Ekos/Capture", Capture.class );
		this.devices.add( this.capture );
		this.mandatoryDevices.add( this.capture );
		this.capture.addNewStatusHandler( Capture.newStatus.class, status -> {
			this.handleCaptureStatus( status.getStatus() );
		} );
		
		
		this.mount = new Device<>( con, "org.kde.kstars", "/KStars/Ekos/Mount", Mount.class );
		this.devices.add( this.mount );
		this.mandatoryDevices.add( this.mount );
		this.mount.addNewStatusHandler( Mount.newStatus.class, status -> {
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
		this.align.addNewStatusHandler( Align.newStatus.class, status -> {
			this.handleAlignStatus( status.getStatus() );
		} );
		this.align.addSigHandler( Align.newSolution.class, status -> {
			logMessage( "newSolution: " + Arrays.toString( status.getSolution() ) );
		} );

		
		this.focus = new Device<>( con, "org.kde.kstars", "/KStars/Ekos/Focus", Focus.class );
		this.devices.add( this.focus );
		this.focus.addNewStatusHandler( Focus.newStatus.class, status -> {
			this.handleFocusStatus( status.getStatus() );
		} );
		
		this.scheduler = new Device<>( con, "org.kde.kstars", "/KStars/Ekos/Scheduler", Scheduler.class );
		this.devices.add( this.scheduler );
		this.mandatoryDevices.add( this.scheduler );
		this.scheduler.addNewStatusHandler( Scheduler.newStatus.class, status -> {
			this.handleSchedulerStatus( status.getStatus() );
		} );
	}
	
	protected void kStarsConnected() {
		for( Device<?> d : devices ) {
			try {
				d.determineAndDispatchCurrentState();
			}
			catch( Throwable t ) {
				//logger.error( "Failed to read from device " + d.interfaceName, t );
			}
		}
	}
	
	protected void kStarsDisconnected() {
		
	}
	
	private final SimpleDateFormat sdf = new SimpleDateFormat( "[HH:mm:ss.SSS] " );
	
	public void logMessage( Object message ) {
		System.out.println( sdf.format( new Date() ) + message );
	}
	
	public void logError( Object message, Throwable t ) {
		System.err.println( sdf.format( new Date() ) + message );
		t.printStackTrace();
	}
	
	
	private Thread kStarsMonitor = null;
	public synchronized void connectToKStars() {
		if( kStarsMonitor != null ) {
			if( kStarsMonitor.isAlive() ) {
				return;
			}
		}
		kStarsMonitor = new Thread( () -> {
			boolean ready = true;
			
			while( true ) {
				
				while( isKStarsReady() == false ) {
					if( ready ) {
						ready = false;
						logMessage( "Kstars is not ready yet" );
					}
					sleep( 1000L );
				}
				
				ready = true;
			
				logMessage( "Kstars is ready" );
				
				kStarsConnected.set(true);
				kStarsConnected();
				
				while( isKStarsReady() ) {
					sleep( 5000L );
				}
				
				logMessage( "Kstars is has stopped, waiting to become ready again" );
				ready = false;
				
				kStarsConnected.set(false);
				kStarsDisconnected();
			}
		} );
		kStarsMonitor.setDaemon( true );
		kStarsMonitor.start();
	}

	protected void sleep(long time) {
		try {
			Thread.sleep( time );
		}
		catch( Throwable t ) {
			//ignore
		}
	}

	protected boolean isKStarsReady() {
		
		for( Device<?> d : mandatoryDevices ) {
			try {
				d.readAll();
			}
			catch( Throwable t ) {
				return false;
			}
		}
		
		return true;
	}
	
	
	protected void handleGuideStatus( GuideStatus state ) {
		//logMessage( "handleGuideStatus " + state );
	}
	
	protected void handleCaptureStatus( CaptureStatus state ) {
		//logMessage( "handleCaptureStatus " + state );
	}
	
	protected void handleMountStatus( MountStatus state ) {
		//logMessage( "handleMountStatus " + state );
	}
	protected void handleMountParkStatus( ParkStatus state ) {
		//logMessage( "handleMountParkStatus " + state );
	}
	protected void handleMeridianFlipStatus( MeridianFlipStatus state ) {
		//logMessage( "handleMeridianFlipStatus " + state );
	}
	
	
	protected void handleAlignStatus( AlignState state ) {
		//logMessage( "handleAlignStatus " + state );
	}
	protected void handleFocusStatus( FocusState state ) {
		//logMessage( "handleFocusStatus " + state );
	}
	
	protected void handleSchedulerStatus( SchedulerState state ) {
		//logMessage( "handleSchedulerStatus " + state );
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

		public void writeNotNullObject( Object frame ) throws IOException {
			if( frame == null ) {
				return;
			}
			
			writeObject( frame );
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
		
		@Override
		public String toString() {
			return String.valueOf( socket.getRemoteSocketAddress() );
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
		
		protected final AtomicReference< Map<String,Object> > lastCaptureStatus = new AtomicReference< Map<String,Object> >();
		@Override
		protected void handleCaptureStatus(CaptureStatus state) {
			super.handleCaptureStatus(state);
			
			final Map<String,Object> payload = capture.getParsedProperties();
			payload.put( "action", "handleCaptureStatus" );
			lastCaptureStatus.set( payload );
			writeToAllClients( payload );
		}
		
		protected final AtomicReference< Map<String,Object> > lastAlignStatus = new AtomicReference< Map<String,Object> >();
		@Override
		protected void handleAlignStatus(AlignState state) {
			super.handleAlignStatus(state);
			
			final Map<String,Object> payload = align.getParsedProperties();
			payload.put( "action", "handleAlignStatus" );
			lastAlignStatus.set( payload );
			writeToAllClients( payload );
		}
		
		protected final AtomicReference< Map<String,Object> > lastFocusStatus = new AtomicReference< Map<String,Object> >();
		@Override
		protected void handleFocusStatus(FocusState state) {
			super.handleFocusStatus(state);
			
			final Map<String,Object> payload = focus.getParsedProperties();
			payload.put( "action", "handleFocusStatus" );
			lastFocusStatus.set( payload );
			writeToAllClients( payload );
		}
		
		protected final AtomicReference< Map<String,Object> > lastScheduleStatus = new AtomicReference< Map<String,Object> >();
		@Override
		protected void handleSchedulerStatus(SchedulerState state) {
			super.handleSchedulerStatus(state);
			
			final Map<String,Object> payload = scheduler.getParsedProperties();
			payload.put( "action", "handleSchedulerStatus" );
			lastScheduleStatus.set( payload );
			writeToAllClients( payload );
		}
		
		protected final AtomicReference< Map<String,Object> > lastGuideStatus = new AtomicReference< Map<String,Object> >();
		@Override
		protected void handleGuideStatus(GuideStatus state) {
			super.handleGuideStatus(state);
			
			final Map<String,Object> payload = guide.getParsedProperties();
			payload.put( "action", "handleGuideStatus" );
			lastGuideStatus.set( payload );
			writeToAllClients( payload );
		}
		
		protected final AtomicReference< Map<String,Object> > lastMountStatus = new AtomicReference< Map<String,Object> >();
		@Override
		protected void handleMountStatus(MountStatus state) {
			super.handleMountStatus(state);
			
			final Map<String, Object> payload = updateLastMountStatus();
			writeToAllClients( payload );
		}

		protected Map<String, Object> updateLastMountStatus() {
			final Map<String,Object> payload = mount.getParsedProperties();
			payload.put( "action", "handleMountStatus" );
			lastMountStatus.set( payload );
			return payload;
		}
		
		@Override
		protected void handleMeridianFlipStatus(MeridianFlipStatus state) {
			super.handleMeridianFlipStatus(state);
			
			updateLastMountStatus();
			
			final Map<String,Object> payload = mount.getParsedProperties();
			payload.put( "action", "handleMeridianFlipStatus" );
			writeToAllClients( payload );
		}
		@Override
		protected void handleMountParkStatus(ParkStatus state) {
			super.handleMountParkStatus(state);
			
			updateLastMountStatus();
			
			final Map<String,Object> payload = mount.getParsedProperties();
			payload.put( "action", "handleMountParkStatus" );
			writeToAllClients( payload );
		}
		
		protected void writeToAllClients( Map<String, Object> payload ) {
			
			payload.remove( "logText" ); 
			
			logMessage( "Sending " + payload.get( "action" ) + ": " + payload.get( "status" ) + " to " + clients.size() + " clients" );
			
			for( SocketHandler handler : clients.keySet() ) {
				try {
					handler.writeObject( payload );
				} catch (IOException e) {
					logError( "Failed to inform client " + handler, e );
				}
			}
		}
		
		protected ConcurrentHashMap< SocketHandler, Thread > clients = new ConcurrentHashMap<SocketHandler, Thread>();
		
		protected void clientFrameReceived( SocketHandler client, Object frame ) {
			//this should currently not happen?
			logMessage( "Received Client Frame: " + frame );
		}

		public void listen() {
			while( true ) {
				try {
					logMessage( "Listen" );
					
					final Socket client = this.serverSocket.accept();
					
					if( client != null ) {
						logMessage( "Client from " + client.getRemoteSocketAddress() + " connected" );
						
						final SocketHandler clientHandler = new SocketHandler( client );
						final Thread rThread = new Thread( () -> {
							receive( clientHandler, this::clientFrameReceived, (c,t) -> {
								clients.remove( c );
							} );
						} );
						clients.put( clientHandler, rThread );
								
						rThread.start();
						
						synchronized ( clientHandler._output ) {
							clientHandler.writeObject( "Hello Client" );
							
							//inform new client about the current state
							clientHandler.writeNotNullObject( lastScheduleStatus.get() );
							clientHandler.writeNotNullObject( lastMountStatus.get() );
							clientHandler.writeNotNullObject( lastAlignStatus.get() );
							clientHandler.writeNotNullObject( lastFocusStatus.get() );
							clientHandler.writeNotNullObject( lastGuideStatus.get() );
							clientHandler.writeNotNullObject( lastCaptureStatus.get() );
						}
					}
				}
				catch( Throwable t ) {
					logError( "Failed to accept", t );
				}
			}
		}
	}
	
	public static class Client extends KStarsCluster {
		
		protected final String host;
		protected final int listenPort; 
		
		protected boolean syncMount = false;
		
		protected String targetPostFix = "client";
		
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
		
		public void setTargetPostFix(String targetPostFix) {
			this.targetPostFix = targetPostFix;
		}
		public String getTargetPostFix() {
			return targetPostFix;
		}
		
		protected void serverFrameReceived( SocketHandler client, Object frame ) {
			try {
				//this should currently not happen?
				
				if( kStarsConnected.get() == false ) {
					logMessage( "Received a Server Frame, but KStars is not ready yet. Skipping.");
					return;
				}
				
				if( frame instanceof Map ) {
					@SuppressWarnings("unchecked")
					final Map<String, Object> payload = (Map<String, Object>) frame;
					final String action = (String) payload.get( "action" );
					
					if( "handleMountStatus".equals( action ) ) {
						final MountStatus status = (MountStatus) payload.get( "status" );
						handleServerMountStatus(status, payload);
					}
					else if( "handleGuideStatus".equals( action ) ) {
						final GuideStatus status = (GuideStatus) payload.get( "status" );
						handleServerGuideStatus(status, payload);
					}
					else if( "handleCaptureStatus".equals( action ) ) {
						final CaptureStatus status = (CaptureStatus) payload.get( "status" );
						handleServerCaptureStatus(status, payload);
					}
					else if( "handleFocusStatus".equals( action ) ) {
						final FocusState status = (FocusState) payload.get( "status" );
						handleServerFocusStatus(status, payload);
					}
					else if( "handleSchedulerStatus".equals( action ) ) {
						final SchedulerState status = (SchedulerState) payload.get( "status" );
						handleServerSchedulerStatus(status, payload);
					}
				}
			}
			catch( Throwable t ) {
				logError( "Error due handle server frame", t );
			}
		}

		
		protected AtomicBoolean serverSchedulerRunning = new AtomicBoolean( false );
		protected AtomicBoolean serverFocusRunning = new AtomicBoolean( false );
		protected AtomicBoolean serverCaptureRunning = new AtomicBoolean( false );
		protected AtomicReference<String> serverCaptureTarget = new AtomicReference<>( null );
		protected AtomicBoolean serverGudingRunning = new AtomicBoolean( false );
		protected AtomicBoolean serverDitheringActive = new AtomicBoolean( false );
		
		protected AtomicBoolean serverMountTracking = new AtomicBoolean( false );
		
		protected AtomicInteger activeCaptureJob = new AtomicInteger( 0 );
		protected AtomicBoolean captureRunning = new AtomicBoolean( false );
		protected AtomicBoolean capturePaused = new AtomicBoolean( false );
		protected AtomicBoolean autoFocusDone = new AtomicBoolean( false );
		protected AtomicBoolean focusRunning = new AtomicBoolean( false );
		protected AtomicBoolean imageReceived = new AtomicBoolean( false );
		
		protected AtomicBoolean autoCapture = new AtomicBoolean( true );
		
		protected void resetValues() {
			autoFocusDone.set( false );
			captureRunning.set( false );
			capturePaused.set( false );
			autoFocusDone.set( false );
			focusRunning.set( false );
			imageReceived.set( false );
			autoCapture.set( true );
		}
		
		protected void handleServerMountStatus( final MountStatus status, final Map<String, Object> payload ) {
			logMessage( "Server mount status " + status );
			
			switch( status ) {
				case MOUNT_ERROR:
					serverMountTracking.set( false );
					checkState();
					break;
				case MOUNT_IDLE:
					serverMountTracking.set( false );
					
					if( isSyncMount() ) {
						this.mount.methods.unpark();
					}
					
					checkState();
					break;
				case MOUNT_MOVING:
					serverMountTracking.set( false );
					checkState();
					break;
				case MOUNT_PARKED:
					serverMountTracking.set( false );
					
					if( isSyncMount() ) {
						this.mount.methods.park();
					}
					
					checkState();
					break;
				case MOUNT_PARKING:
					serverMountTracking.set( false );
					checkState();
					break;
				case MOUNT_SLEWING:
					serverMountTracking.set( false );
					
					if( isSyncMount() ) {
						this.mount.methods.unpark();
					}
					
					checkState();
					break;
				case MOUNT_TRACKING:
					serverMountTracking.set( true );
					
					if( isSyncMount() ) {
						@SuppressWarnings("unchecked") 
						final List<Double> pos = (List<Double>) payload.get( "equatorialCoords" );
						this.mount.methods.slew( pos.get(0), pos.get(1) );
					}
					
					checkState();
					break;
				default:
					break;
			}
		}
		@Override
		protected void handleMountStatus( MountStatus state ) {
			super.handleMountStatus(state);
			
			logMessage( "Client mount status " + state );
		}

		
		protected void handleServerGuideStatus(GuideStatus status, final Map<String, Object> payload) {
			logMessage( "Server guide status " + status );
			
			switch( status ) {
				case GUIDE_ABORTED:
					serverGudingRunning.set( false );
					
				case GUIDE_MANUAL_DITHERING:
				case GUIDE_DITHERING:
					serverDitheringActive.set( true );
					checkState();
				break;
					
				case GUIDE_GUIDING:
					serverGudingRunning.set( true );
					serverDitheringActive.set( false );
					
					checkState();
				break;
				
				case GUIDE_LOOPING:
				case GUIDE_DISCONNECTED:
					serverGudingRunning.set( false );
					checkState();
				break;
				
				case GUIDE_IDLE:
					//Todo: should we handle this
					break;
				case GUIDE_REACQUIRE:
					//Todo: should we handle this
					break;
				case GUIDE_SUSPENDED:
					//Todo: should we handle this
					break;
				
				
				case GUIDE_CALIBRATING:
				case GUIDE_CALIBRATION_ERROR:
				case GUIDE_CALIBRATION_SUCESS:
					//no need to handle
					break;
					
				case GUIDE_CAPTURE:
					//no need to handle
					break;
					
				case GUIDE_DITHERING_ERROR:
				case GUIDE_DITHERING_SETTLE:
				case GUIDE_DITHERING_SUCCESS:
					//no need to handle
					break;
					
				case GUIDE_CONNECTED:
				case GUIDE_DARK:
					//no need to handle
					break;
					
				case GUIDE_STAR_SELECT:
				case GUIDE_SUBFRAME:
					//no need to handle
					break;
				
			}
		}
		@Override
		protected void handleGuideStatus(GuideStatus state) {
			super.handleGuideStatus(state);
			
			logMessage( "Client guide status " + state );
		}

		protected void handleServerSchedulerStatus(SchedulerState status, final Map<String, Object> payload) {
			logMessage( "Server scheduler status " + status );
			
			switch( status ) {
				case SCHEDULER_ABORTED:
				case SCHEDULER_IDLE:
				case SCHEDULER_LOADING:
				case SCHEDULER_PAUSED:
				case SCHEDULER_SHUTDOWN:
				case SCHEDULER_STARTUP:
					serverSchedulerRunning.set( false );
					checkState();
				break;
					
				case SCHEDULER_RUNNING:
					serverSchedulerRunning.set( true );
					checkState();
				break;
			}
		}
		
		@Override
		protected void handleSchedulerStatus(SchedulerState state) {
			super.handleSchedulerStatus(state);
			
			logMessage( "Client scheduler status " + state );
		}

		protected void handleServerFocusStatus(FocusState status, final Map<String, Object> payload) {
			logMessage( "Server focus status " + status );
			
			switch( status ) {
				case FOCUS_ABORTED:
				case FOCUS_FAILED:
				case FOCUS_IDLE:
				case FOCUS_COMPLETE:
					serverFocusRunning.set( false );
					checkState();
				break;
					
				case FOCUS_PROGRESS:
					serverFocusRunning.set( true );
					checkState();
				break;
					
				case FOCUS_FRAMING:
				case FOCUS_WAITING:
					break;
				case FOCUS_CHANGING_FILTER:
					break;
			}
		}
		@Override
		protected void handleFocusStatus(FocusState state) {
			super.handleFocusStatus(state);
			
			logMessage( "Client focus status " + state );
			
			switch( state ) {
				case FOCUS_COMPLETE:
					autoFocusDone.set( true );
				case FOCUS_ABORTED:
				case FOCUS_FAILED:
				case FOCUS_IDLE:
					focusRunning.set( false );
					checkState();
				break;
				
				case FOCUS_PROGRESS:
					focusRunning.set( true );
				break;

				case FOCUS_FRAMING:
				case FOCUS_WAITING:
				case FOCUS_CHANGING_FILTER:
					break;
			}
		}
		
		protected void handleServerCaptureStatus(CaptureStatus status, final Map<String, Object> payload) {
			logMessage( "Server capture status " + status );
			
			String serverTarget = (String) payload.get( "targetName" );
			
			if( serverTarget == null ) {
				serverTarget = "";
			}
			
			if( serverTarget.equals( serverCaptureTarget.getAndSet( serverTarget ) ) == false ) {
				logMessage( "Server target has changed: " + serverTarget );
				this.capture.write( "targetName", serverTarget + "_" + targetPostFix );
			}
			
			switch( status ) {
				case CAPTURE_CAPTURING:
				case CAPTURE_PROGRESS:
					
					serverCaptureRunning.set( true );
					checkState();
				break;
				case CAPTURE_IMAGE_RECEIVED:
					
				break;
				case CAPTURE_COMPLETE:
				case CAPTURE_ABORTED:
				case CAPTURE_SUSPENDED:
					serverCaptureRunning.set( false );
					checkState();
				break;
				case CAPTURE_IDLE:
					//no need to handle
					break;
				
				case CAPTURE_PAUSED:
				case CAPTURE_PAUSE_PLANNED:
					break;
				
				case CAPTURE_DITHERING:
					serverDitheringActive.set( true );
					checkState();
					break;
				case CAPTURE_GUIDER_DRIFT:
					//Todo: should we handle this
					break;
				
				case CAPTURE_SETTING_ROTATOR:
				case CAPTURE_SETTING_TEMPERATURE:
				case CAPTURE_WAITING:
					//no need to handle
					break;
					
				case CAPTURE_ALIGNING:
				case CAPTURE_CALIBRATING:
				case CAPTURE_CHANGING_FILTER:
				case CAPTURE_MERIDIAN_FLIP:
					//no need to handle
					break;
				
				case CAPTURE_FILTER_FOCUS:
				case CAPTURE_FOCUSING:
					//no need to handle
					break;
				
			}
		}
		@Override
		protected void handleCaptureStatus(CaptureStatus state) {
			super.handleCaptureStatus(state);
			
			logMessage( "Client capture status "+ state );
			
			String clientTarget = (String) capture.getParsedProperties().get( "targetName" );
			
			if( clientTarget == null ) {
				clientTarget = "";
			}
			
			String serverTarget = serverCaptureTarget.get( );
			
			if( serverTarget == null ) {
				serverTarget = "";
			}
			
			serverTarget = serverTarget + "_" + targetPostFix;
			
			if( serverTarget.equals( clientTarget ) == false ) {
				logMessage( "Client target has changed: " + serverTarget );
				this.capture.write( "targetName", serverTarget );
			}
			
			switch (state) {
			
				case CAPTURE_CAPTURING:
					imageReceived.set( false );
					captureRunning.set( true );
					capturePaused.set( false );
					autoCapture.set( true );
					
					final int jobId = this.capture.methods.getActiveJobID();
					activeCaptureJob.set( jobId );
					
					if( !canCapture() ) {
						logMessage( "Capture not allowed yet, but was started ... aborting" );
						stopCapture();
					}
					else {
						double duration = this.capture.methods.getJobExposureDuration( jobId );
						double timeLeft = this.capture.methods.getJobExposureProgress( jobId );
						
						if( timeLeft == 0 ) {
							timeLeft = duration;
						}
						
						double exposure = duration - timeLeft;
						
						int imageCount = this.capture.methods.getJobImageCount( jobId );
						int imageProgress = this.capture.methods.getJobImageProgress( jobId );
						
						logMessage( "Capture started " + jobId + ": " + exposure + "/" + duration + "s, " + imageProgress + "/" + imageCount );
					}
					break;
				case CAPTURE_PROGRESS:
					//no need to handle
				break;
				
				case CAPTURE_IMAGE_RECEIVED:
					logMessage( "Capture finished: " + activeCaptureJob.get() );
					imageReceived.set( true );
				break;
				
				case CAPTURE_ABORTED:
					capturePaused.set( false );
					if( captureRunning.getAndSet( false ) ) {
						logMessage( "Capture " + activeCaptureJob.get() + " was aborted");
						logMessage( "Capture was aborted by user" );
						autoCapture.set( false );
					}
					
					checkState();
				break;
				
				case CAPTURE_COMPLETE:
				case CAPTURE_SUSPENDED:
					captureRunning.set( false );
					capturePaused.set( false );
					checkState();
				break;
				
				case CAPTURE_PAUSED:
					//running, but paused
					capturePaused.set( true );
				break;
				
				case CAPTURE_IDLE:
					//no need to handle
					break;
				
				case CAPTURE_PAUSE_PLANNED:
					break;
				
				case CAPTURE_DITHERING:
					//no need to handle
					break;
				case CAPTURE_GUIDER_DRIFT:
					//no need to handle
					break;
				
				case CAPTURE_SETTING_ROTATOR:
				case CAPTURE_SETTING_TEMPERATURE:
				case CAPTURE_WAITING:
					//no need to handle
					break;
					
				case CAPTURE_ALIGNING:
				case CAPTURE_CALIBRATING:
				case CAPTURE_CHANGING_FILTER:
				case CAPTURE_MERIDIAN_FLIP:
					//no need to handle
					break;
				
				case CAPTURE_FILTER_FOCUS:
				case CAPTURE_FOCUSING:
					//no need to handle
					break;				
			}
		}

		private void stopCapture() {
			capturePaused.set( false );
			captureRunning.set( false );
			this.capture.methods.abort();
		}
		
		public boolean canCapture() {
			//capture is allowed, when mount is tracking and NO dithering is active
			if( serverMountTracking.get() && serverDitheringActive.get() == false ) {
				return true;
			}
			else {
				return false;
			}
		}

		protected synchronized void checkState() {
			if( canCapture() ) {
				if( focusRunning.get() ) {
					//can't start capture, when focus process is active
					return;
				}
				
				if( captureRunning.get() ) { 
					//check if we should resume
					if( capturePaused.get() ) {
						final int jobId = activeCaptureJob.get();
						logMessage( "Resuming paused job " + jobId );
						this.capture.methods.start();
					}
				}
				else if( serverGudingRunning.get() && autoCapture.get() ) {
					//if server is tracking AND guiding, we can start capture
					logMessage( "Server is Guding, but no capture or focus is in progress" );
					
					if( autoFocusDone.get() == false ) {
						try {
							logMessage( "Starting Autofocus process" );
							
							if( this.focus.methods.canAutoFocus() ) {
								this.focus.methods.start();
								logMessage( "Done" );
							}
							else {
								logMessage( "Autofocus is disabled" );
								autoFocusDone.set( true );
							}
						}
						catch( Throwable t ) {
							//autofocus failed, this
							logMessage( "Focus module not present" );
							autoFocusDone.set( true );
						}
					}
					
					if( autoFocusDone.get() ) {
						int pendingJobCount = this.capture.methods.getPendingJobCount();
						
						if( pendingJobCount > 0 ) {
							logMessage( "Starting aborted capture, pending jobs " + pendingJobCount );
							this.capture.methods.start();
						}
						else {
							logMessage( "No Jobs to capture" );
						}
					}
				}
			}
			else {
				//no capture possible, check if we have to abort
				
				if( captureRunning.get() ) {
					final int jobId = activeCaptureJob.get();
					
					final double timeLeft = this.capture.methods.getJobExposureProgress( jobId );
					
					//if more than 2 seconds left in exposure, abort
					if( timeLeft >= 2.0 ) {
						logMessage( "Aborting job " + jobId + " with " + timeLeft + "s left" );
						stopCapture();
					}
					else {
						//always pause capture
						logMessage( "Pausing job " + jobId );
						this.capture.methods.pause();
					}
				}
			}
		}


		@Override
		protected void kStarsConnected() {
			resetValues();
			
			super.kStarsConnected();
		}
		
		@Override
		protected void kStarsDisconnected() {
			super.kStarsDisconnected();
			
			synchronized ( this ) {
				try {
					clientHandler.socket.close();
				} catch (IOException e) {
					//ignore
				}
				clientHandler = null;
			}
		}
		
		private SocketHandler clientHandler;
		
		public void listen()  {
			
			while( true ) {
				while( kStarsConnected.get() ) {
					try {
						synchronized ( this ) {
							clientHandler = new SocketHandler( new Socket( host, listenPort ) );
						}
						clientHandler.writeObject( "Hello Server" );
						logMessage( "Connected to " + host + " ...");
						receive( 
							clientHandler, 
							this::serverFrameReceived, 
							(c,t) -> {
							
							} 
						);
					}
					catch( Throwable t ) {
						sleep( 1000 );
					}
				}
				
				synchronized ( this ) {
					if( clientHandler != null ) {
						try {
							clientHandler.socket.close();
						} catch (IOException e) {
							//ignore
						}
						clientHandler = null;
					}
				}
				sleep( 1000 );
			}
		}
	}
	
	
	public static void main(String[] args) throws Exception {
		
		ClientRunner.main(args);
	}
}
