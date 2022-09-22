package de.pmneo.kstars;

import java.io.File;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.exceptions.DBusException;
import org.kde.kstars.Ekos;
import org.kde.kstars.Ekos.CommunicationStatus;
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
import org.kde.kstars.ekos.Weather;
import org.kde.kstars.ekos.Scheduler.SchedulerState;
import org.kde.kstars.ekos.Weather.WeatherState;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;


public abstract class KStarsCluster {
	protected DBusConnection con;

	protected final Device<Ekos> ekos;
	protected final Device<Align> align;
	protected final Device<Focus> focus;
	protected final Device<Guide> guide;
	protected final Device<Capture> capture;
	protected final Device<Mount> mount;
	protected final Device<Scheduler> scheduler;
	protected final Device<Weather> weather;
	
	protected final List< Device<?> > mandatoryDevices = new ArrayList<Device<?>>();
	protected final List< Device<?> > devices = new ArrayList<Device<?>>();
	
	
	protected final AtomicBoolean kStarsConnected = new AtomicBoolean(false);
	
	public KStarsCluster( ) throws DBusException {
		/* Get a connection to the session bus so we can get data */
		con = DBusConnection.getConnection( DBusConnection.DBusBusType.SESSION );
		
		this.ekos = new Device<>( con, "org.kde.kstars", "/KStars/Ekos", Ekos.class );
		this.mandatoryDevices.add( this.ekos );
		this.ekos.addSigHandler( Ekos.ekosStatusChanged.class, status -> {
			this.handleEkosStatus( status.getStatus() );
		} );


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

		this.weather = new Device<>( con, "org.kde.kstars", "/KStars/Ekos/Weather", Weather.class );
		this.devices.add( this.weather );
		this.weather.addNewStatusHandler( Weather.newStatus.class, status -> {
			this.handleSchedulerWeatherStatus( status.getStatus() );
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
		
		if( t != null ) {
			t.printStackTrace();
		}
	}
	
	

	private Thread kStarsMonitor = null;
	public synchronized void connectToKStars() {
		if( kStarsMonitor != null ) {
			if( kStarsMonitor.isAlive() ) {
				return;
			}
		}
		kStarsMonitor = new Thread( () -> {
			boolean ekosAvailable = false;
			boolean startKStars = false;
			Process kstarsProcess = null;

			Runtime.getRuntime().addShutdownHook(new Thread()
			{
				@Override
				public void run()
				{
					System.out.println("Shutdown hook ran!");
				}
			});

			Thread.currentThread().setName( "KStars Monitor Thread" );

			while( true ) {
				
				ekosAvailable = false;

				while( !ekosAvailable ) {
					//first check if ekos is started and available
					try {
						ekos.readAll();
						ekosAvailable = true;
						startKStars = false;
					}
					catch( Throwable t ) {
						//ekos is not responding ... kstars may be crashed or not running

						if( !startKStars ) {
							startKStars = true;
							ekosAvailable = false;

							logMessage( "KStars/Ekos seems not to run, let's wait up to 15 Seconds it may be start soon" );
							for( int wi = 0; wi < 15; wi++ ) {
								try {
									ekos.readAll();
									ekosAvailable = true;
									startKStars = false;
									break;
								}
								catch( Throwable t2 ) {
									sleep( 1000L );
								}
							}
						}
						else {

							try {
								logMessage( "Killing previous kstars processes" );
								Process kill = Runtime.getRuntime().exec( new String[]{ "killall", "kstars" } );
								
								kill.waitFor();

								logMessage( "Killed previous kstars processes" );
								
							}
							catch( Throwable tt ) {
								logError( "Failed to kill kstars", tt );
							}
							
							try {
								logMessage( "Starting kstars" );
								kstarsProcess = Runtime.getRuntime().exec( new String[]{ "setsid", "nohup", "kstars" } );
								
								logMessage( "Started kstars with pid " + kstarsProcess.pid() );
								startKStars = false;
							}
							catch( Throwable tt ) {
								logError( "Failed to start kstars", tt );
							}
							
						}
					}
				}

				if( isKStarsReady() == false ) {
					CommunicationStatus indiStatus = (CommunicationStatus)ekos.getParsedProperties().get( "indiStatus" );

					logMessage( "Current indi status: " + indiStatus );
					logMessage( "Ekos not started yet, starting now" );
					try {
						ekos.methods.start();
					}
					catch( Throwable t ) {
						logError( "Faield to start ekos, is kstars running?", t );
						continue; //repeat check
					}
				}

				while( isKStarsReady() == false ) {
					sleep( 1000L );
				}
			
				logMessage( "Ekos is ready" );
				
				ekosReady();

				kStarsConnected.set(true);
				kStarsConnected();
				
				while( isKStarsReady() ) {
					sleep( 5000L );
				}
				
				logMessage( "Kstars is has stopped, waiting to become ready again" );
				
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

	protected void ekosReady() {

	}
	
	protected void handleEkosStatus( CommunicationStatus state ) {
		//logMessage( "handleGuideStatus " + state );
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

	protected void handleSchedulerWeatherStatus( WeatherState state ) {
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
		
		protected String loadSchedule = "";

		public void setLoadSchedule(String loadSchedule) {
			if( loadSchedule != null ) {
				loadSchedule = loadSchedule.replaceFirst("^~", System.getProperty("user.home"));
			}
			this.loadSchedule = loadSchedule;
		}
		
		public void ekosReady() {
			super.ekosReady();

			if( loadSchedule != null && loadSchedule.isEmpty() == false ) {
				File f = new File( loadSchedule );
				if( f.exists() ) {

					if( scheduler.getParsedProperties().get( "status" ) == SchedulerState.SCHEDULER_IDLE ) {
						try {
							f = f.getCanonicalFile();
						}
						catch( IOException e ) {
							//ignore
						}
						logMessage( "loading schedule " + f.getPath() );
						try {
							scheduler.methods.loadScheduler( f.getPath() );
						}
						catch( Throwable t ) {
							logError( "Failed to load schedule", t );
						}
						sleep(1000L);
						logMessage( "starting schedule " + f.getPath() );
						try {
							scheduler.methods.start();
						}
						catch( Throwable t ) {
							logError( "Failed to start schedule", t );
						}
						
					}
					else {
						logMessage( "Scheduler is not idle: " + scheduler.getParsedProperties().get( "status" ) );
					}
				}
				else {
					logMessage( "Scheduler File does not exists: " + f.getPath() );
				}
			}
			else {
				logMessage( "No Scheduler File provided" );
			}
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
			
			double pa = getPositionAngle();

			final Map<String,Object> payload = align.getParsedProperties();
			payload.put( "action", "handleAlignStatus" );
			payload.put( "positionAngle", pa );
			lastAlignStatus.set( payload );
			writeToAllClients( payload );
		}

		protected double getPositionAngle() {
			
			Double pa = null;

			try {
				List<Double> result = this.align.methods.getSolutionResult();
				pa = result.get( 0 ).doubleValue() + 180;

				while( pa < 0.0 ) {
					pa += 360.0;
				}
				while( pa >= 360.0 ) {
					pa -= 360.0;
				}
			}
			catch( Throwable t ) {
				logError( "Failed to get pa from align module", t );
			}
			
			if( pa == null ) {
				File f = new File( loadSchedule );

				if( f.exists() ) {
					try {
						logMessage( "Try resolve by scheduler" );
						DocumentBuilder doc = DocumentBuilderFactory.newInstance().newDocumentBuilder();
						Document d = doc.parse( f );
						NodeList nl = d.getDocumentElement().getElementsByTagName( "PositionAngle" );
						
						for( int i=0; i<nl.getLength(); i++ ) {
							String tmp = nl.item( i ).getTextContent();
							if( tmp != null ) {
								double tpa = Double.parseDouble( tmp.trim() );

								if( pa == null ) {
									pa =  tpa;
								}
								else if( pa.doubleValue() != pa ) {
									logError( "Ombigous pa found: " + pa + " vs. " + tpa, null );
								}
							}
						}
					}
					catch( Throwable t ) {
						logError( "Failed to determine PositionAngle", t );
					}
				}
			}

			if( pa == null ) {
				logMessage( "No PA found, falling back to 0.0" );
				return 0.0;
			}
			else {
				logMessage( "Found PA: " + pa );
				return pa.doubleValue();
			}
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
		
		protected final AtomicReference< SchedulerState > schedulerState = new AtomicReference< SchedulerState >( SchedulerState.SCHEDULER_IDLE );
		protected final AtomicReference< Map<String,Object> > lastScheduleStatus = new AtomicReference< Map<String,Object> >();
		@Override
		protected void handleSchedulerStatus(SchedulerState state) {
			super.handleSchedulerStatus(state);

			this.schedulerState.set( state );
			
			final Map<String,Object> payload = scheduler.getParsedProperties();
			payload.put( "action", "handleSchedulerStatus" );
			lastScheduleStatus.set( payload );
			writeToAllClients( payload );


			
		}

		protected final AtomicReference< Map<String,Object> > lastScheduleWeatherStatus = new AtomicReference< Map<String,Object> >();
		@Override
		protected void handleSchedulerWeatherStatus(WeatherState state) {
			super.handleSchedulerWeatherStatus(state);
			
			final Map<String,Object> payload = new HashMap<>();
			payload.put( "action", "handleSchedulerWeatherStatus" );
			payload.put( "status", state );
			lastScheduleWeatherStatus.set( payload );
			writeToAllClients( payload );
			
			if( state == WeatherState.WEATHER_OK && this.schedulerState.get() == SchedulerState.SCHEDULER_IDLE ) {
				logMessage( "Weather is OK, Starting idle scheduler" );
				scheduler.methods.start();
			}
		}
		
		protected final AtomicReference< Map<String,Object> > lastGuideStatus = new AtomicReference< Map<String,Object> >();
		protected final AtomicBoolean guideCalibrating = new AtomicBoolean( false );
		@Override
		protected void handleGuideStatus(GuideStatus state) {
			super.handleGuideStatus(state);
			
			final Map<String,Object> payload = guide.getParsedProperties();
			payload.put( "action", "handleGuideStatus" );
			lastGuideStatus.set( payload );
			writeToAllClients( payload );

			/*
			switch( state ) {
				case GUIDE_ABORTED:
					break;
				case GUIDE_CALIBRATING:
					guideCalibrating.set( true );
					break;
				case GUIDE_CALIBRATION_ERROR:
					break;
				case GUIDE_CALIBRATION_SUCESS:
					guideCalibrating.set( false );
					break;

				case GUIDE_SUSPENDED:
					break;

				case GUIDE_GUIDING:
					if( guideCalibrating.get() ) {
						logMessage( "Calibration was not finished, but guiding was resumed ... clearing calibration and abort guiding" );
						try {
							guide.methods.clearCalibration();
						}
						catch( Throwable t ) {
							logError( "Failed to clear calibration", t );
						}

						try {
							guide.methods.abort();
						}
						catch( Throwable t ) {
							logError( "Failed to abort guiding", t );
						}
					}
				break;

				case GUIDE_CAPTURE:
				case GUIDE_CONNECTED:
				case GUIDE_DARK:
				case GUIDE_DISCONNECTED:
				case GUIDE_DITHERING:
					break;
				case GUIDE_DITHERING_ERROR:
				case GUIDE_DITHERING_SETTLE:
				case GUIDE_DITHERING_SUCCESS:
					break;
				
				case GUIDE_IDLE:
				case GUIDE_LOOPING:
				case GUIDE_MANUAL_DITHERING:
				case GUIDE_REACQUIRE:
				case GUIDE_STAR_SELECT:
				case GUIDE_SUBFRAME:
					break;
				
				
				default:
					break;

			}

			 */
			
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
		
		protected boolean syncMount = true;
		protected boolean autoFocusEnabled = true;
		
		protected String targetPostFix = "client";
		
		public Client( String host, int listenPort ) throws DBusException {
			super( );
			this.host = host;
			this.listenPort = listenPort;
		}

		protected String captureSequence = "";
		
		public void setCaptureSequence(String captureSequence) {
			if( captureSequence != null ) {
				captureSequence = captureSequence.replaceFirst("^~", System.getProperty("user.home"));
			}
			this.captureSequence = captureSequence;
		}
		
		public void ekosReady() {
			super.ekosReady();

			loadSequence();
		}
		
		public void loadSequence() {
			if( captureSequence != null && captureSequence.isEmpty() == false ) {
				File f = new File( captureSequence );
				if( f.exists() ) {
					Object status = capture.getParsedProperties().get( "status" );
					if( status == CaptureStatus.CAPTURE_ABORTED || status == CaptureStatus.CAPTURE_COMPLETE || status == CaptureStatus.CAPTURE_IDLE ) {
						try {
							f = f.getCanonicalFile();
						}
						catch( IOException e ) {
							//ignore
						}
						logMessage( "loading sequence " + f.getPath() );
						try {
							capture.methods.loadSequenceQueue( f.getPath() );
						}
						catch( Throwable t ) {
							logError( "Failed to load sequence", t );
						}
						sleep(1000L);
					}
					else {
						logMessage( "capture is not idle: " + status );
					}
				}
				else {
					logMessage( "capture File does not exists: " + f.getPath() );
				}
			}
			else {
				logMessage( "No sequence file provided" );
			}
		}

		public void setSyncMount(boolean syncMount) {
			this.syncMount = syncMount;
		}
		public boolean isSyncMount() {
			return syncMount;
		}
		
		public void setAutoFocuseEnabled(boolean autoFocus) {
			this.autoFocusEnabled = autoFocus;
		}
		public boolean isAutoFocusEnabled() {
			return autoFocusEnabled;
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
					else if( "handleSchedulerWeatherStatus".equals( action ) ) {
						final WeatherState status = (WeatherState) payload.get( "status" );
						handleServerSchedulerWeatherStatus(status, payload);
					}
					else if( "handleAlignStatus".equals( action ) ) {
						final AlignState status = (AlignState) payload.get( "status" );
						handleServerAlignStatus(status, payload);
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

		protected AtomicBoolean shouldAlign = new AtomicBoolean( false );
		protected AtomicReference<Double> targetPositionAngle = new AtomicReference<Double>( 0.0 );
		protected AtomicReference<AlignState> currentAlignStatus = new AtomicReference<AlignState>( AlignState.ALIGN_IDLE );
		protected AtomicReference<MountStatus> currentMountStatus = new AtomicReference<MountStatus>( MountStatus.MOUNT_IDLE );
		
		protected AtomicBoolean serverMountTracking = new AtomicBoolean( false );
		
		protected AtomicInteger activeCaptureJob = new AtomicInteger( 0 );
		protected AtomicBoolean captureRunning = new AtomicBoolean( false );
		protected AtomicBoolean capturePaused = new AtomicBoolean( false );
		protected AtomicBoolean autoFocusDone = new AtomicBoolean( false );
		protected AtomicBoolean focusRunning = new AtomicBoolean( false );
		protected AtomicBoolean imageReceived = new AtomicBoolean( false );
		
		protected AtomicBoolean autoCapture = new AtomicBoolean( true );
		
		protected void resetValues() {
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
			currentMountStatus.set( state );
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
				
				
				case GUIDE_CALIBRATION_ERROR:
					//should be handled
					break;
				case GUIDE_CALIBRATING:
				case GUIDE_CALIBRATION_SUCESS:
					//no need to handle
					break;
					
				case GUIDE_CAPTURE:
					//no need to handle
					break;
					
				case GUIDE_DITHERING_ERROR:
					//should be handled
					break;
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
				case SCHEDULER_SHUTDOWN:
					this.capture.write( "coolerControl", Boolean.FALSE );
					
				case SCHEDULER_LOADING:
				case SCHEDULER_PAUSED:
				
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

		protected void handleServerSchedulerWeatherStatus(WeatherState status, final Map<String, Object> payload) {
			logMessage( "Server scheduler weather status " + status );
		}
		
		protected void handleServerAlignStatus(AlignState status, Map<String, Object> payload) {
			logMessage( "Server scheduler align status " + status );

			targetPositionAngle.set( (Double) payload.get( "positionAngle" ) );

			logMessage( "Server PA: " + targetPositionAngle.get() );

			if( status == AlignState.ALIGN_COMPLETE ) {
				shouldAlign.set( true );
				checkState();
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
					focusRunning.set( false );
					checkState();
				break;
				
				case FOCUS_ABORTED:
				case FOCUS_FAILED:
					autoFocusDone.set( false );
					focusRunning.set( false );
					checkState();
				break;
				
				case FOCUS_IDLE:
					focusRunning.set( false );
					checkState();
				break;
				
				case FOCUS_FRAMING:
				case FOCUS_WAITING:
				case FOCUS_CHANGING_FILTER:
				case FOCUS_PROGRESS:
					focusRunning.set( true );
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

		protected void handleAlignStatus( AlignState state ) {
			if( state == null ) {
				state = AlignState.ALIGN_IDLE;
			}

			super.handleAlignStatus(state);
			logMessage( "handleAlignStatus " + state );
			currentAlignStatus.set( state );
		}

		
		private void startCapture() {
			if( captureRunning.getAndSet( true ) == false ) {
				this.capture.methods.start();
			}
		}
		private void stopCapture() {
			capturePaused.set( false );
			if( captureRunning.getAndSet( false ) == true ) {
				//this.capture.methods.abort();
				this.capture.methods.stop();
			}
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
						logMessage( "Warning: paused job " + jobId );
					}
				}
				else if( serverGudingRunning.get() && autoCapture.get() ) {
					//if server is tracking AND guiding, we can start capture
					logMessage( "Server is Guding, but no capture or focus is in progress" );
					
					if( autoFocusDone.get() == false ) {
						try {
							logMessage( "Starting Autofocus process" );
							
							if( this.isAutoFocusEnabled() && this.focus.methods.canAutoFocus() ) {
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

					if( shouldAlign.get() && this.targetPositionAngle.get() != null ) {
						try {

							waitForMountTracking();
							logMessage( "Starting Align process to " + this.targetPositionAngle.get() );
							this.align.methods.setSolverAction( 0 ); //SYNC
							captureAndSolveAndWait();
							List<Double> coords = this.align.methods.getSolutionResult();
							logMessage( "Sync done: " + coords );
							this.align.methods.setTargetPositionAngle( this.targetPositionAngle.get() );
							//logMessage( "Set target corrds" );
							this.align.methods.setTargetCoords( coords.get(1) / 15.0, coords.get(2) );
							this.align.methods.setSolverAction( 1 ); //SYNC
							captureAndSolveAndWait();
							logMessage( "PA align done" );
						}
						catch( Throwable t ) {
							//autofocus failed, this
							logMessage( "Align module not present" );
						}
						finally {
							shouldAlign.set( false );
						}
					}
					
					if( autoFocusDone.get() ) {
						int pendingJobCount = this.capture.methods.getPendingJobCount();
						
						if( pendingJobCount == 0 ) {
							loadSequence();
							pendingJobCount = this.capture.methods.getPendingJobCount();
						}

						if( pendingJobCount > 0 ) {
							logMessage( "Starting aborted capture, pending jobs " + pendingJobCount );
							this.startCapture();
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
						//logMessage( "Pausing job " + jobId );
						//this.capture.methods.pause();
					}
				}
			}
		}

		protected void waitForMountTracking() {
			logMessage( "Wait for mount tracking: " + this.currentMountStatus.get() );
			boolean mountTracking = false;
			while( !mountTracking ) {
				MountStatus state = this.currentMountStatus.get();
				
				switch( state ) {
					case MOUNT_ERROR:
						break;
					case MOUNT_IDLE:
						break;
					case MOUNT_MOVING:
						break;
					case MOUNT_PARKED:
						break;
					case MOUNT_PARKING:
						break;
					case MOUNT_SLEWING:
						break;
					case MOUNT_TRACKING:
						logMessage( "Mount is tracking now: " + state );
						mountTracking = true;
						break;
					default:
						break;
				}

				try {
					Thread.sleep( 500 );
				}
				catch( Throwable t ) {
					t.printStackTrace();
				}
			}
		}
		protected void captureAndSolveAndWait() {

			this.currentAlignStatus.set( AlignState.ALIGN_IDLE );
			this.align.methods.captureAndSolve();

			boolean alignRunning = true;
			while( alignRunning ) {
				AlignState state = this.currentAlignStatus.get();
				
				switch( state ) {
					case ALIGN_ABORTED:
						alignRunning = false;
						break;
					case ALIGN_COMPLETE:
						alignRunning = false;
						break;
					case ALIGN_FAILED:
						alignRunning = false;
						break;
					
					case ALIGN_PROGRESS:
						break;
					case ALIGN_SLEWING:
						break;
					case ALIGN_SYNCING:
						break;
					default:
						break;
				}

				try {
					Thread.sleep( 500 );
				}
				catch( Throwable t ) {
					t.printStackTrace();
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
}
