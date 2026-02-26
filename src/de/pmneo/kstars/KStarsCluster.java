package de.pmneo.kstars;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.commons.configuration2.INIConfiguration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Request;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.Introspectable;
import org.freedesktop.dbus.messages.MethodCall;
import org.kde.kstars.Ekos;
import org.kde.kstars.INDI;
import org.kde.kstars.INDI.DriverInterface;
import org.kde.kstars.INDI.IpsState;
import org.kde.kstars.ekos.Align;
import org.kde.kstars.ekos.Align.AlignState;
import org.kde.kstars.ekos.Capture;
import org.kde.kstars.ekos.Dome;
import org.kde.kstars.ekos.Capture.CaptureStatus;
import org.kde.kstars.ekos.Focus;
import org.kde.kstars.ekos.Focus.FocusState;
import org.kde.kstars.ekos.Guide;
import org.kde.kstars.ekos.Mount;
import org.kde.kstars.ekos.Mount.MountStatus;
import org.kde.kstars.ekos.Scheduler;
import org.kde.kstars.ekos.Scheduler.SchedulerState;
import org.kde.kstars.ekos.Weather.WeatherState;
import org.kde.kstars.ekos.SchedulerJob;
import org.qtproject.Qt.QAction;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.google.gson.GsonBuilder;

import bsh.Interpreter;

import de.pmneo.kstars.utils.RaDecUtils;
import de.pmneo.kstars.utils.SunriseSunset;
import de.pmneo.kstars.web.CommandServlet.Action;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;


public abstract class KStarsCluster extends KStarsState {
	protected DBusConnection con;

	public Device<Ekos> ekos;
	public Device<Align> align;
	public Device<Focus> focus;
	public Device<Guide> guide;
	public Device<Capture> capture;
	public Device<Mount> mount;
	public Device<Scheduler> scheduler;
	public Device<INDI> indi;

	public Device<QAction> showEkos;
	public Device<QAction> quitKStars;
	
	private IndiRotator rotatorDevice = null;
	public IndiRotator getRotatorDevice() {
		return rotatorDevice;
	}
	
	private IndiFocuser focusDevice = null;
	public IndiFocuser getFocusDevice() {
		return focusDevice;
	}

	private IndiCamera cameraDevice = null;
	public IndiCamera getCameraDevice() {
		return cameraDevice;
	}

	private IndiFilterWheel filterDevice = null;
	public IndiFilterWheel getFilterDevice() {
		return filterDevice;
	}
	
	protected final List< Device<?> > mandatoryDevices = new ArrayList<Device<?>>();
	protected final List< Device<?> > devices = new ArrayList<Device<?>>();
	
    private double preCoolTemp = -15;
    public void setPreCoolTemp(double preCoolTemp) {
		IndiCamera camera = getCameraDevice();
		if( camera != null ) {
			camera.setPreCoolTemp(preCoolTemp);
		}
        this.preCoolTemp = preCoolTemp;
    }
    public double getPreCoolTemp() {
        return preCoolTemp;
    }

	private List<Runnable> subscriptions = new ArrayList<>();

	public KStarsCluster( String logPrefix ) throws DBusException {
		super( logPrefix );

		MethodCall.setDefaultTimeout( 20000 );

		/* Get a connection to the session bus so we can get data */
		con = DBusConnection.getConnection( DBusConnection.DBusBusType.SESSION );

	}

	protected synchronized void createEkosDevices() throws DBusException {
		this.unsubscribe();

		this.mandatoryDevices.clear();
		this.devices.clear();

		this.showEkos = new Device<>( con, "org.kde.kstars", "/kstars/MainWindow_1/actions/ekos", QAction.class );
		this.quitKStars = new Device<>( con, "org.kde.kstars", "/kstars/MainWindow_1/actions/quit", QAction.class );

		this.ekos = new Device<>( con, "org.kde.kstars", "/KStars/Ekos", Ekos.class );
		this.mandatoryDevices.add( this.ekos );
	}

	protected AtomicReference<String> opticalTrain = new AtomicReference<String>( "unkown" );

	protected synchronized void createDevices() throws DBusException {
		this.createEkosDevices();

		this.indi = new Device<>( con, "org.kde.kstars", "/KStars/INDI", INDI.class );
		this.devices.add( this.indi );

		this.guide = new Device<>( con, "org.kde.kstars", "/KStars/Ekos/Guide", Guide.class );
		this.devices.add( this.guide );
		this.mandatoryDevices.add( this.guide );

		this.capture = new Device<>( con, "org.kde.kstars", "/KStars/Ekos/Capture", Capture.class );
		this.devices.add( this.capture );
		this.mandatoryDevices.add( this.capture );

		this.mount = new Device<>( con, "org.kde.kstars", "/KStars/Ekos/Mount", Mount.class );
		this.devices.add( this.mount );
		this.mandatoryDevices.add( this.mount );

		this.align = new Device<>( con, "org.kde.kstars", "/KStars/Ekos/Align", Align.class, d -> {
			opticalTrain.set( (String) d.read( "opticalTrain" ) );
			return (Align.AlignState) d.read( "status" );
		});
		this.devices.add( this.align );
		this.mandatoryDevices.add( this.align );

		this.focus = new Device<>( con, "org.kde.kstars", "/KStars/Ekos/Focus", Focus.class, d -> {
			Object[] status = d.methods.status( opticalTrain.get() );
			return Focus.FocusState.values()[ (int) status[0] ];
		 } );
		this.devices.add( this.focus );
		this.mandatoryDevices.add( this.focus );
		
		this.scheduler = new Device<>( con, "org.kde.kstars", "/KStars/Ekos/Scheduler", Scheduler.class );
		this.devices.add( this.scheduler );
		this.mandatoryDevices.add( this.scheduler );
	}

	protected synchronized void unsubscribe() throws DBusException {
		resetValues();

		for( Runnable unsub : subscriptions ) {
			try {
				unsub.run();
			}
			catch( Throwable t ) {
				logError( "Failed to unsubscribe", t );
			}
		}
		subscriptions.clear();

		cameraDevice = null;
		focusDevice = null;
		filterDevice = null;
		rotatorDevice = null;
	}

	protected synchronized void subscribe() throws DBusException {
		unsubscribe();

		logMessage( "Subscribing to KStars" );

		subscriptions.add( this.ekos.addSigHandler( Ekos.ekosStatusChanged.class, status -> {
			this.handleEkosStatus( status.getStatus() );
		} ) );
		subscriptions.add( this.guide.addNewStatusHandler( Guide.newStatus.class, status -> {
			this.handleGuideStatus( status.getStatus() );
		} ) );
		subscriptions.add( this.capture.addNewStatusHandler( Capture.newStatus.class, status -> {
			this.handleCaptureStatus( status.getStatus() );
		} ) );
		subscriptions.add( this.mount.addNewStatusHandler( Mount.newStatus.class, status -> {
			this.handleMountStatus( status.getStatus() );
		} ) );
		subscriptions.add( this.mount.addSigHandler( Mount.newParkStatus.class, status -> {
			this.handleMountParkStatus( status.getStatus() );
		} ) );
		subscriptions.add( this.mount.addSigHandler( Mount.newMeridianFlipStatus.class, status -> {
			this.handleMeridianFlipStatus( status.getStatus() );
		} ) );
		subscriptions.add( this.align.addNewStatusHandler( Align.newStatus.class, status -> {
			this.handleAlignStatus( status.getStatus() );
		} ) );
		subscriptions.add( this.align.addSigHandler( Align.newSolution.class, status -> {
			logMessage( "newSolution: " + status.getSolution() );
		} ) );
		subscriptions.add( this.focus.addNewStatusHandler( Focus.newStatus.class, status -> {
			this.handleFocusStatus( status.getStatus() );
		} ) );
		subscriptions.add( this.focus.addSigHandler( Focus.newHFR.class, hfr -> {
			System.out.println( "new hfr " + hfr.getHFR() );
		} ) );
		subscriptions.add( this.scheduler.addNewStatusHandler( Scheduler.newStatus.class, status -> {
			this.handleSchedulerStatus( status.getStatus() );
		} ) );

		String foundCamera = (String) this.capture.read( "camera" );
		cameraDevice = new IndiCamera(foundCamera, indi);
		cameraDevice.setPreCoolTemp( getPreCoolTemp() );

		String foundFocuser = (String) this.focus.methods.focuser( opticalTrain.get() );
		focusDevice = new IndiFocuser(foundFocuser, indi);

		String foundRotator = IndiRotator.findFirstDevice(indi, DriverInterface.ROTATOR_INTERFACE);
		rotatorDevice = new IndiRotator(foundRotator, indi);

		String foundFilterWheel = (String) this.capture.read( "filterWheel" );
		filterDevice = new IndiFilterWheel(foundFilterWheel, indi);
	}
	
	protected void ekosDisconnected() {
		try {
			unsubscribe();
		}
		catch( Throwable t ) {
			logError( "Failed to unsubscribe", t);
		}
	}

	private INIConfiguration config;
	
	public Calendar[] getCivilTwilight() {
		try {
			loadConfig();

			double longitude = config.getDouble("Location.Longitude", -999 );
			double latitude = config.getDouble( "Location.Latitude", -999 );

			Calendar now = Calendar.getInstance();
			Calendar[] range = SunriseSunset.getCivilTwilight( now, latitude, longitude );

			if( range == null ) {
				return new Calendar[] { null, null, now };
			}
			else {
				return new Calendar[] { range[0], range[1], now };
			}
			
		}
		catch( Throwable t ) {
			logError( "Failed to calc twighlight", t);
			return null;
		}
	}

	public boolean isNight( ) {
		return isNight( getCivilTwilight() );
	}
	public boolean isNight( Calendar[] range ) {
		if( range[0] == null ) {
			return true;
		}
		Calendar now = range[2];
		if( now.getTimeInMillis() < range[0].getTimeInMillis() || range[1].getTimeInMillis() < now.getTimeInMillis() ) {
			//logMessage( "Twighlight: " + start.getTime() + " to " + end.getTime() + " at ("+latitude + "/" + longitude+")" );
			return true;
		}
		else {
			return false;
		}
	}
	
	private synchronized void loadConfig() throws ConfigurationException, IOException, FileNotFoundException {
		if( config == null ) {
			config = new INIConfiguration();
			config.read( new FileReader( System.getProperty("user.home") + "/.config/kstarsrc" ) );
		}
	}

	protected HttpClient client = null;
	private boolean weatherSafty = false;
	private long lastCheck = -1;

	public synchronized boolean checkWeatherStatus() {
		ensureHttpClient();
		
		if( this.lastCheck + 10000 < System.currentTimeMillis() ) {

			final File isSafeCondition = new File( "./KStarsClusterScripts/isSafeCondition.bsh" );
			
			boolean weatherSafty = true;

			if( isSafeCondition.exists() ) {
				try {
					Interpreter i = new Interpreter();
					i.set( "cluster", this );
					i.set( "client", client );

					Object isSafe = i.eval( new FileReader( isSafeCondition, Charset.forName( "UTF-8" ) ) );

					if( isSafe instanceof Boolean ) {
						weatherSafty = ((Boolean)isSafe).booleanValue();
					}
				}
				catch( Throwable t ) {
					logError( "Failed to get weather status", t);
					weatherSafty = false;
				}
			}

			this.lastCheck = System.currentTimeMillis();

			if( this.weatherSafty != weatherSafty ) {
				logMessage( "Weather saftey changed from " + this.weatherSafty + " to " + weatherSafty);
				this.weatherSafty = weatherSafty;
			}
		}

		WeatherState newState = this.weatherSafty ? WeatherState.WEATHER_OK : WeatherState.WEATHER_ALERT;
		if( this.weatherState.getAndSet( newState ) != newState ) {
			handleSchedulerWeatherStatus( newState );
		}

		return this.weatherSafty;
	}

	protected synchronized void ensureHttpClient() {
		if( client == null ) {
			client = new HttpClient() {
				@Override
				public Request newRequest(URI uri) {
					return super.newRequest(uri)
						.idleTimeout( 5, TimeUnit.SECONDS )
						.timeout( 10, TimeUnit.SECONDS );
				}
			};
			try {
				client.setConnectTimeout( 2000 );
				client.setIdleTimeout( 5000 );
				client.setAddressResolutionTimeout( 5000L );
				//client.setDestinationIdleTimeout( 5000L );
				client.start();
			}
			catch( Throwable t ) {
				logError( "Failed to start http client", t);
			}
		}
	}

	public void stopUsbDevices() {
		ensureHttpClient();

		final File stopScript = new File( "./KStarsClusterScripts/stopUsb.bsh" );
		if( stopScript.exists() ) {
			try {
				Interpreter i = new Interpreter();
				i.set( "cluster", this );
				i.set( "client", client );

				i.eval( new FileReader( stopScript, Charset.forName( "UTF-8" ) ) );
			}
			catch( Throwable t ) {
				logError( "Failed to stop usb devices", t);
			}
		}
	}

	private long checkShutdownUsb( long ekosStoppedAt ) {
		if( ekosStoppedAt == 0 ) {
			ekosStoppedAt = System.currentTimeMillis();
		}
		else if( ( System.currentTimeMillis() - ekosStoppedAt ) >= TimeUnit.MINUTES.toMillis( 5 ) ) {
			logMessageOnce( "Check if usb is off, because ekos has stopped" );
			stopUsbDevices();
			ekosStoppedAt = Long.MAX_VALUE;
		}

		return ekosStoppedAt;
	}

	private Thread kStarsMonitor = null;
	public synchronized void connectToKStars() {
		if( kStarsMonitor != null ) {
			if( kStarsMonitor.isAlive() ) {
				return;
			}
		}

		kStarsMonitor = new Thread( () -> {
			Thread.currentThread().setName( "KStars Monitor Thread" );

			long ekosStoppedAt = 0;

			while( true ) { try {
				if( tryStartKStars() == false ) {
					ekosStoppedAt = checkShutdownUsb( ekosStoppedAt );
					//retry in 5 seconds
					sleep( 5000L );
				}
				else {
					this.createDevices();

					if( checkEkosReady( false ) == false ) {
						ekosStoppedAt = checkShutdownUsb( ekosStoppedAt );

						Calendar[] range = getCivilTwilight();
						if( isNight(range) == false ) {
							Calendar now = range[2];
							if( getKStarsRuntime() > TimeUnit.HOURS.toSeconds( 5 ) && now.get( Calendar.HOUR_OF_DAY ) >= 15 ) {
								logMessage( "It's day and KStars is running more than 5h, stopping KStars" );
								stopKStars();
							}
						}

						if( checkWeatherStatus() == false ) {
							weatherState.set( WeatherState.WEATHER_ALERT );
							logMessageOnce( "Weather conditions are UNSAFE, skip start of ekos");
							sleep( 5000L );
						}
						else {
							
							logMessage( "Weather conditions are SAFE, starting ekos now" );

							try {
								showEkos.methods.trigger();
								sleep( 1000L );
								ekos.methods.start();
							}
							catch( Throwable t ) {
								logError( "Failed to start ekos, is kstars running?", t );
								continue; //repeat check
							}
							boolean ekosStarted = false;
							for( int i=0; i<60; i++ ) {
								if( checkEkosReady( true ) == false ) {
									sleep( 1000L );
								}
								else {
									ekosStarted = true;
									break;
								}
							}
							if( ekosStarted == false ) {
								logMessage( "Ekos failed to start, stopping ekos and retry later" );
								this.stopKStars();
							}
						}
					}
					else {
						ekosStoppedAt = 0;

						synchronized( this ) {
							subscribe();
							ekosReady();

							ekosReady.set( true );
						}

						waitUntilEkosHasStopped();

						ekosStoppedAt = checkShutdownUsb( ekosStoppedAt );
						
						logMessage( "Ekos has stopped, waiting to become ready again" );
						
						synchronized( this ) {
							ekosDisconnected();
						}
					}
				}
			}
			catch( Throwable t ) {
				logError( "Unhandled error in KStars Monitor loop", t );
			} 
			finally {
				kStarsMonitor = null;
			}
		} } );
		kStarsMonitor.setDaemon( true );
		kStarsMonitor.start();
	}

	private boolean tryStartKStars() throws DBusException {
		
		this.createEkosDevices();
		
		for( int i=0; i<10; i++ ) {
			//first check if ekos is started and available
			try {
				ekos.checkAlive();
				return true;
			}
			catch( Throwable t ) {
				//ekos is not responding ... kstars may be crashed or not running
				long runtime = getKStarsRuntime();
				if( runtime > TimeUnit.HOURS.toSeconds( 1 ) ) {
					stopKStars();
				}

				Calendar[] range = getCivilTwilight();
				if( isNight(range) ) {
					if( runtime == 0 ) {
						try {
							logMessage( "Starting kstars" );
							Process kstarsProcess = Runtime.getRuntime().exec( new String[]{ "setsid", "nohup", "kstars" } );
							logMessage( "Started kstars with pid " + kstarsProcess.pid() );
							sleep( 5000L );
						}
						catch( Throwable tt ) {
							logError( "Failed to start kstars", tt );
						}
					}
				}
				else {
					logMessageOnce( "It's daytime, wait to start until dusk: " + range[1].getTime() );
					return false;
				}
			}
		}

		return false;
	}


    protected void stopAll() {
        this.focus.methods.abort( opticalTrain.get() );
        this.align.methods.abort();
        this.scheduler.methods.stop();
    }

	private void waitUntilEkosHasStopped() {
		Long weatherBadSince = null;
		while( checkEkosReady( false ) ) {
			long start = System.currentTimeMillis();

			if( checkWeatherStatus() ) {
				if( weatherBadSince != null ) {
					logMessage( "Weather changed to SAFE" );
					weatherBadSince = null;
				}
			}
			else {
				if( weatherBadSince == null ) {
					weatherBadSince = System.currentTimeMillis();
					logMessage( "Weather changed to UNSAFE" );
				}
				else {
					long now = System.currentTimeMillis();
					long badWeatherDuration = (now - weatherBadSince);
					long badWeatherTimeout = TimeUnit.HOURS.toMillis(1);

					if( badWeatherDuration >= badWeatherTimeout ) {
						StringBuilder waitToStopReasons = new StringBuilder();
						waitToStopReasons.append( "Weather is UNSAFE since 1 hour, check if we can shutdown ekos" );

						boolean canStop = true;

						if( this.mountStatus.get() != MountStatus.MOUNT_PARKED ) {
							waitToStopReasons.append( "\n\tMount is not yet parked, wait for parking" );
							canStop = false;
						}
						if( this.captureRunning.get() )  {
							waitToStopReasons.append( "\n\tA capture is in progress" );
							canStop = false;
						}
						
						if( this.captureRunning.lastChangeLessThen( TimeUnit.MINUTES.toMillis( 15 ) ) ) {
							waitToStopReasons.append( "\n\tLast capture was less than 15 Minutes ago" );
							canStop = false;
						}
						else {
							this.capture.determineAndDispatchCurrentState( this.captureStatus.get() );
						}

						if( canStop == false ) {
							logMessageOnce( waitToStopReasons.toString() );
						}
						else {
							logMessage( "Shutting down Ekos / KStars after " + (badWeatherDuration / 1000 / 60 ) + " Minutes" );

							try {
								logMessage( "Setting Filter slot to L" );
								WaitUntil maxWait = new WaitUntil( 20, "changeFilter" );
								this.getFilterDevice().setFilterSlot( 1 ); //switch to first filter as reference
								while( this.getFilterDevice().getFilterSlotStatus() != IpsState.IPS_OK && maxWait.check() ) {
									try { Thread.sleep( 10 ); } catch( Throwable t ) {};
								}
								
								logMessage( "Caputure one focus image");
								this.focus.methods.capture( opticalTrain.get(), 0 );
								sleep( 1000L );
								maxWait.reset();

								while( this.focusState.get() != FocusState.FOCUS_IDLE && maxWait.check() ) {
									try { Thread.sleep( 10 ); } catch( Throwable t ) {};
								}

								logMessage( "Caputure one focus image done");


								sleep( 5000L );
							}
							catch( Throwable t ) {
								logError( "Failed to go back to L before shutdown", t );
							}

							ensureMountIsParked(); //ensure mount is parked

							if( stopEkos() == false ) {
								stopKStars();
							}
							stopUsbDevices();
							break;
						}
					}
					else if( badWeatherDuration >= TimeUnit.MINUTES.toMillis( 1 ) ) {
						ensureMountIsParked();
					}
				}
			}

			try {
				ekosRunningLoop();
			}
			catch( Throwable t ) {
				logError( "error in ekos running loop", t);
			}

			long checkTime = System.currentTimeMillis() - start;
			sleep( Math.max( 500, ekosLoopDelay - checkTime ) ); //min 500 ms delay
		}
	}

	protected long ekosLoopDelay = 5000;

	protected void ekosRunningLoop() {

	}

	protected boolean ensureMountIsParked() {
		switch( this.mountStatus.get() ) {
			case MOUNT_PARKING:
				return false;
			case MOUNT_PARKED:
				return true;

			
			case MOUNT_IDLE:
			case MOUNT_MOVING: 
			case MOUNT_SLEWING: 
			case MOUNT_TRACKING:
			case MOUNT_ERROR:
			default:
				
				
				Calendar[] range = getCivilTwilight();
				range[0].add( Calendar.HOUR, 1 );
				if( isNight(range) == false ) {
					//logMessage( "Do not park, it's day" );
					return false;
				}

				if( automationSuspended.get() ) {
					return false;
				}

				try {
					this.mount.methods.abort();
					this.mount.methods.park();
				}
				catch( Throwable t ) {
					logError( "Failed to park mount", t);
				}
				
				return false;
		}
	}

	public boolean isCameraBusy() {
		boolean isCooling = getCameraDevice().isCooling();
		IpsState ccdTempState = getCameraDevice().getCcdTemparaturState();

		if( isCooling || ccdTempState == IpsState.IPS_BUSY || ccdTempState == IpsState.IPS_ALERT ) {
			return true;
		}
		else {
			return false;
		}
	}

	protected void sleep(long time) {
		try {
			Thread.sleep( time );
		}
		catch( Throwable t ) {
			//ignore
		}
	}

	protected final AtomicBoolean ekosReady = new AtomicBoolean(false);

	protected boolean checkEkosReady( boolean autoConnect ) {
		
		for( Device<?> d : mandatoryDevices ) {
			try {
				d.checkAlive();
			}
			catch( Throwable t ) {
				ekosReady.set( false );
				return false;
			}
		}

		boolean allConnected = true;
		try {
			for( String device : this.indi.methods.getDevices() ) {
				String state = this.indi.methods.getPropertyState( device, "CONNECTION" );
				String connected = this.indi.methods.getSwitch( device, "CONNECTION", "CONNECT" );
				
				if( "Ok".equals( state ) && "On".equals( connected ) ) {
					continue;
				}
				else {
					allConnected = false;
					logMessage( "The device " + device + " is not connected: " + state );
					if( autoConnect ) {
						this.indi.methods.setSwitch( device, "CONNECTION", "CONNECT", "On" );
						this.indi.methods.sendProperty( device, "CONNECTION" );
					}
				}
			}
		}
		catch( Throwable t ) {
			logError( "Failed to query indi device status", t );
		}

		return allConnected;
	}

	protected void ekosReady() {
		for( Device<?> d : devices ) {
			try {
				d.determineAndDispatchCurrentState();
			}
			catch( Throwable t ) {
				logError( "Failed to read status from device " + d.interfaceName, t );
			}
		}

		try {
			logMessage( "Ekos started, checking focuser temp and move to estimated position" ) ;

			double temp = 0;
			for( int i=0; i<20; i++ ) {
				temp = this.focusDevice.getFocusTemperature();
				if( temp == 0 ) {
					// wait a second seconds
					logMessage( "Focus temp is zero, let's wait a second to init" );
					sleep( 1000 );
				}
				else {
					break;
				}
			}
		
			if( temp == 0 ) {
				temp = 25;
			}

			//FocusAnalyser a = new FocusAnalyser();

			//int pos = a.aproximatePos( "Ha", temp );

			//logMessage( "Estimated focuser position for " + temp + "°C is " + pos );

			//this.focusDevice.setFocusPosition( pos );
		}
		catch( Throwable t ) {
			logError( "Failed to set estimated focus pos", t );
		}
			
	}

	private final AtomicInteger alignProgressCounter = new AtomicInteger(0);
	public AlignState handleAlignStatus( AlignState state ) {
		state = super.handleAlignStatus(state);

		switch( state ) {
			case ALIGN_SYNCING:
			break;
			case ALIGN_PROGRESS:
			break;
			
			case ALIGN_SLEWING:
				if( alignProgressCounter.incrementAndGet() > 15 ) {
					logMessage( "Resetting mount model" );
					this.mount.methods.resetModel();
					alignProgressCounter.set(0);
				}
			break;

			case ALIGN_ABORTED:
			case ALIGN_COMPLETE:
			case ALIGN_FAILED:
				alignProgressCounter.set( 0 );
			break;

			case ALIGN_IDLE:
			case ALIGN_ROTATING:
			case ALIGN_SUSPENDED:
				break;
			default:
				break;
		}

		return state;
	}


	protected final AtomicReference<Double> lastFocusPos = new AtomicReference<>(null);
	protected final AtomicInteger activeCaptureJob = new AtomicInteger( -1 );
	protected final AtomicLong activeCaptureJobStarted = new AtomicLong( -1 );

	public CaptureStatus handleCaptureStatus( CaptureStatus state ) {
		boolean captureWasRunning = captureRunning.get();

		state = super.handleCaptureStatus(state);

		String targetName = (String) this.capture.read( "targetName" );
                    
		if( targetName != null && targetName.isEmpty() == false ) {
			String oldTarget = this.captureTarget.getAndSet( targetName );
			if( targetName.equals( oldTarget ) == false ) {
				logMessage( "Capture target has changed from " + oldTarget + " to " + targetName );
			}
		}

		if( CaptureStatus.CAPTURE_CAPTURING == state || lastFocusPos.get() == null ) {
			double focusPos = getFocusDevice().getFocusPosition();
			if( Double.valueOf( focusPos ).equals( lastFocusPos.getAndSet(focusPos) ) == false ) {
				logMessage( "Storing last focus pos ("+state+"): " + focusPos );
			}
		}

		if( ( captureWasRunning == false || state == CaptureStatus.CAPTURE_PROGRESS ) && captureRunning.get() ) {
			final int jobId = this.capture.methods.getActiveJobID();

			activeCaptureJob.set( jobId );
			activeCaptureJobStarted.set( System.currentTimeMillis() );

			final CaptureDetails details = getCaptureDetails( jobId );

			checkBin1();

			logMessage( "Capture started " + details );
		}
		else if( captureWasRunning == true && captureRunning.get() == false ) {
			logMessage( "Capture " + activeCaptureJob.get() + " stopped");

			this.activeCaptureJob.set( -1 );
			this.activeCaptureJobStarted.set( -1 );
		}

		return state;
	}
	private boolean checkBin1() {
		final int binning = this.cameraDevice.getBinning();
		if( binning != 1 ) {
			logMessage( "WARNING: Camera binning was not set to bin1: " + " bin" + binning);
			//this.cameraDevice.resetFrameSettings();
			//this.cameraDevice.setGain( 100 );
			return false;
		}
		else {
			return true;
		}
	}

	public FocusState handleFocusStatus( FocusState state ) {
		state = super.handleFocusStatus( state );
		
		try {
			switch( state ) {
				case FOCUS_ABORTED:
				/*
					Double lastPos = lastFocusPos.get();
					if( lastPos != null ) {
						logMessage( "Restoring focuser position to " + lastPos );
						getFocusDevice().setFocusPosition( lastPos.doubleValue() );
					}
				*/
					break;
					
				case FOCUS_CHANGING_FILTER:
					break;
				case FOCUS_FAILED:
					break;
				case FOCUS_FRAMING:
					break;
				
				case FOCUS_COMPLETE:
					//checkBin1();
				case FOCUS_IDLE:
					double focusPos = getFocusDevice().getFocusPosition();
					logMessage( "Storing last focus pos ("+state+"): " + focusPos );
					lastFocusPos.set(focusPos);
				break;
				case FOCUS_PROGRESS:
					break;
				case FOCUS_WAITING:
					break;
				default:
					break;

			}
		}
		catch( Throwable t ) {
			logError( "Failed to handle focus state", t );
		}

		return state;
	}
	
    protected void checkCameraCooling( KStarsState state ) {
		if( this.getCameraDevice() == null ) {
			return;
		}

		SchedulerState schedulerStatus = state.schedulerState.get();
		MountStatus mountStatus = state.mountStatus.get();

		switch( mountStatus ) {
			case MOUNT_PARKED:
			case MOUNT_PARKING:
				this.getCameraDevice().warm();
			break;
			default:
				break;
		}

        switch( schedulerStatus ) {
            case SCHEDULER_ABORTED:
            case SCHEDULER_IDLE:
            case SCHEDULER_SHUTDOWN:
				this.getCameraDevice().warm();
			break;
                
            case SCHEDULER_LOADING:
            case SCHEDULER_PAUSED:
            case SCHEDULER_STARTUP:
            break;
                
            case SCHEDULER_RUNNING:
				switch( mountStatus ) {
					case MOUNT_SLEWING:
					case MOUNT_TRACKING:
						this.getCameraDevice().preCool();
					break;

					default:
						break;
				}
            break;
        }
    }


	public int runAutoFocus( int filter ) {
		WaitUntil maxWait = new WaitUntil( 20, "changeFilter" );
		
		this.getFilterDevice().setFilterSlot( filter ); //switch to first filter as reference
		
		while( this.getFilterDevice().getFilterSlotStatus() != IpsState.IPS_OK && maxWait.check() ) {
			try { Thread.sleep( 10 ); } catch( Throwable t ) {};
		}

		return this.runAutoFocus();
	}

	public int runAutoFocus() {
		this.focus.methods.abort( opticalTrain.get() );
		sleep( 1000 );
		this.focus.methods.start( opticalTrain.get() );

		final WaitUntil maxWait = new WaitUntil( 5, "Focusing" );

		while( !this.focusRunning.get() && maxWait.check() ) {
			sleep( 10 );
		}

		logMessage( "Focus process has started" );

		maxWait.reset( 300 );

		while( this.focusRunning.get() && maxWait.check() ) {
			sleep(10);
		}

		double pos = this.getFocusDevice() == null ? -1 : this.getFocusDevice().getFocusPosition();

		logMessage( "Focus process has finished: " + pos );

		this.focusRunning.hasChangedAndReset();

		return (int) pos;
		
	}

	public abstract void listen();


	public AtomicBoolean automationSuspended = new AtomicBoolean( false );

	public void addActions( Map<String, Action> actions ) {
		actions.put( "calibrateFilters", this::calibrateFiltersAction );
        actions.put( "status", this::statusAction );

		actions.put( "suspend", ( parts, req, resp ) -> {
			automationSuspended.set( true );
			return this.statusAction(parts, req, resp);
		} );
		actions.put( "resume", ( parts, req, resp ) -> {
			automationSuspended.set( false );
			return this.statusAction(parts, req, resp);
		} );

		actions.put( "camera", ( parts, req, resp ) -> {
			if( ekosReady.get() == false ) {
				return NOT_CONNECTED;
			}

			if( parts.length > 1 ) {
				if( parts[1].equals( "preCool" ) ) {
					this.getCameraDevice().preCool();
				}
				else if( parts[1].equals( "warm" ) ) {
					this.getCameraDevice().warm();
				}
			}
			
			return true;
		} );

		actions.put( "stopKStars", (parts, req, resp ) -> {
				stopKStars();
				return "OK";
		} );

		actions.put( "exec", ( parts, req, resp ) -> {
			
			int len = req.getContentLength();

			if( len > 0 ) {
				byte[] buffer = new byte[len];
				int pos = 0;
				InputStream in = req.getInputStream();
				while( ( len = in.read(buffer, pos, buffer.length - pos ) ) >= 0 ) {
					pos += len;
				}

				String content = new String( buffer, "UTF-8" );

				Interpreter i = new Interpreter();
				i.set( "cluster", this );
				return i.eval( content );
			}

			return "Not yet implemented";
		} );
	}

	public Map<String,Object> statusAction( String[] parts, HttpServletRequest req, HttpServletResponse resp) throws IOException {
		if( ekosReady.get() == false ) {
			return NOT_CONNECTED;
		}
		
		Map<String,Object> res = new HashMap<>();
        
		List<String> filters = this.getFilterDevice().getFilters() ;
		
		res.put( "filters", filters );
		res.put( "currentFilter", filters.get( this.getFilterDevice().getFilterSlot() - 1 ) );
		res.put( "focusPosition", this.getFocusDevice().getFocusPosition() );
		res.put( "rotatorAngle", this.getRotatorDevice().getRotatorPosition() );

		Map<String,Object> camera = new HashMap<>();

		camera.put( "name", this.getCameraDevice().deviceName );
		camera.put( "temperature", this.getCameraDevice().getCcdTemparatur() );
		camera.put( "antiDewHeaterOn", this.getCameraDevice().isAntiDewHeaterOn() );
		camera.put( "isCooling", this.getCameraDevice().isCooling() );

		res.put( "camera", camera );

		res.put( "automationSuspended", this.automationSuspended.get() );
		
		fillStatus( res );

		res.put( "calibrateFilterInProgress", calibrateFilterInProgress.get() );

		if( this.captureRunning.get() ) {
			res.put( "capture", getCaptureDetails( activeCaptureJob.get() ) ); 
		}
		
		res.put( "alignment", fillAlignment(new HashMap<>(), this.align.methods.getSolutionResult() ) );
		
		return res;
	}


    public double normalizePa(double value) {
		if( value == -1000000 ) {
			return 0;
		}

		double pa = value + 180;
		while (pa > 180)
			pa -= 360;
		while (pa < -180)
			pa += 360;
		return pa;
    }


	public Map<String, Object> fillAlignment(Map<String, Object> res, List<Double> alignSolution) {
		res.put( "solutionResult", alignSolution );

		if( alignSolution != null && alignSolution.size() == 3 ) {
			double pa = normalizePa( alignSolution.get( 0 ).doubleValue() );
								
			res.put( "pa", pa );
			
			try {
				res.put( "ra", RaDecUtils.degreesToRA( alignSolution.get(1) ) );
				res.put( "dec", RaDecUtils.degreesToDEC( alignSolution.get(2) )  );
			}
			catch( Throwable t ) {

				//SILENT CATCH
			}
		}

		return res;
	}



	public static class CaptureDetails {
		public int jobId;
		public double exposure;
		public double duration;
		public double timeLeft;
		public int imageProgress = -1;
		public int imageCount = -1;

		public String toString() {
			return jobId + ": " + exposure + "/" + timeLeft + "/" + duration + "s" + ( imageProgress >= 0 ? ( ", " + imageProgress + "/" + imageCount ) : "" );
		}
	}

	public CaptureDetails getCaptureDetails(int jobId ) {
		return getCaptureDetails(jobId, true );
	}
	public CaptureDetails getCaptureDetails(int jobId, boolean withCnt ) {
		CaptureDetails c = new CaptureDetails();

		c.jobId = jobId;
		if( jobId >= 0 ) {
			c.duration = this.capture.methods.getJobExposureDuration( jobId );
			c.timeLeft = this.capture.methods.getJobExposureProgress( jobId );
			/*
			if( c.timeLeft == 0 ) {
				c.timeLeft = c.duration;
			}
			*/
			
			c.exposure = c.duration - c.timeLeft;
			
			if( withCnt ) {
				c.imageCount = this.capture.methods.getJobImageCount( jobId );
				c.imageProgress = this.capture.methods.getJobImageProgress( jobId );
			}
		}

		return c;
	}
    

	private static Map<String,Object> NOT_CONNECTED = new HashMap<>(); 
	static {
		NOT_CONNECTED.put( "result", "KStars not connected" );
	}

	private AtomicBoolean calibrateFilterInProgress = new AtomicBoolean( false );
    public Object calibrateFiltersAction( String[] parts, HttpServletRequest req, HttpServletResponse resp) throws IOException {
		if( ekosReady.get() == false ) {
			return NOT_CONNECTED;
		}

		Map<String,Object> res = new HashMap<>();

        if( calibrateFilterInProgress.getAndSet( true ) ) {
            res.put( "result", "Filter calibration is already in progress" );            
        }
        else {
            res.put( "result", "Filter calibration started" );

            new Thread( () -> {
                try {
                    SimpleLogger logger = SimpleLogger.getLogger();
                    logger.logMessage( "starting filter calibration" );

                    List<String> filters = this.getFilterDevice().getFilters();
                    int n = 1;

					final String refFilterName = filters.get(n-1);

                    StringBuilder result = new StringBuilder( );

					logger.logMessage( "Starting focus procedure of filter " + refFilterName + " as reference" );
					int refPos = runAutoFocus( n );
					
                    //skip first filter
                    for( int i=1; i<=filters.size(); i++ ) {
                        if( i == n  ) {
                            continue;
                        }

						final String filterName = filters.get(i-1);

						logger.logMessage( "Pre reference position: " + refPos );
						logger.logMessage( "Starting focus procedure of filter " + filterName + " for calibration" );

		                final int calPos = this.runAutoFocus( i );

						logger.logMessage( "Starting focus procedure of filter " + refFilterName + " as reference" );

						final int postRefPos = runAutoFocus( n );

						logger.logMessage( "Post reference position: " + postRefPos );

						final int avgRefPos = ( refPos + postRefPos ) / 2;

						logger.logMessage( "Avg reference position: " + avgRefPos );
                        final int offset = calPos - avgRefPos;

                        result.append( filterName ).append( ": " ).append( offset ).append( "\n" );

                        logger.logMessage( "Found solution for " + filterName + ": " + refPos + " > " + calPos + " = " + offset );
                        logger.logMessage( "Current soltions: \n" + result.toString() );

						refPos = postRefPos;
                    }

                    logger.logMessage( "Calibration finished" );

                    calibrateFilterInProgress.set( false );
                }
                catch( Throwable t ) {
                    t.printStackTrace();
                }
            }, "filterCalibration").start();
        }

        return res;
    }

	public boolean captureAndSolveAndWait( boolean autoSync ) {


        final AtomicBoolean alignRunning = new AtomicBoolean( true );

		final AtomicBoolean alignFailed = new AtomicBoolean( false );

		final List<Runnable> unsub = new ArrayList<>();
		
		try {

			//max wait 20 seconds
			final WaitUntil maxWait = new WaitUntil( 20, "Capture and Solve" );

			IpsState rotatorState = getRotatorDevice().getRotatorPositionStatus();

			unsub.add( this.align.addNewStatusHandler( Align.newStatus.class, ( status ) -> {
				logDebug( "captureAndSolveAndWait(" + status.getStatus() + ")");
				switch( status.getStatus() ) {
					case ALIGN_ABORTED:
						alignRunning.set( false );
						break;

					case ALIGN_COMPLETE:
						alignRunning.set( false );

						//this.checkBin1();

						break;
				
					case ALIGN_FAILED:
						alignFailed.set( true );	
						alignRunning.set( false );
						
						break;
					
					case ALIGN_PROGRESS:
						alignFailed.set( false );
						maxWait.reset();
					break;

					case ALIGN_SLEWING:
						maxWait.reset();
						break;

					case ALIGN_ROTATING:
						maxWait.reset();
					break;
					
					case ALIGN_SYNCING:
						maxWait.reset();
						break;

					case ALIGN_SUCCESSFUL:
						alignFailed.set( false );
						maxWait.reset();
					break;

					case ALIGN_SUSPENDED:
						maxWait.reset();
						break;

					default:
						break;
				}
			} ) );

			if( autoSync ) {
				unsub.add( this.align.addSigHandler( Align.newSolution.class, status -> {
					List<Double> coords = this.align.methods.getSolutionResult();
					this.align.methods.setTargetCoords( coords.get(1) / 15.0, coords.get(2) );
					logMessage( "Sync done: " + coords );
				} ) );
			}

			this.align.methods.captureAndSolve();

			while( alignRunning.get() && maxWait.check() ) {
				IpsState cRotatorState = getRotatorDevice().getRotatorPositionStatus();

				if( cRotatorState == IpsState.IPS_BUSY ) {
					maxWait.reset();
				}

				if( cRotatorState != rotatorState ) {
					rotatorState = cRotatorState;

					logMessage( "Rotator is " + cRotatorState );
				}

				sleep( 500 );
			}

			return alignFailed.get();
		}
		catch( Throwable t ) {
			logError( "error in capture an solve", t);
			return false;
		}
		finally {
			for( Runnable u : unsub ) {
				u.run();
			}
		}
    }
	
	protected void updateSchedulerState() {
        String currentJobName = (String) this.scheduler.read( "currentJobName" );
		if( currentJobName == null ) {
			currentJobName = "";
		}

		SchedulerJob job = this.schedulerActiveJob.get();

		boolean jobChanged = false;

		if( currentJobName.isEmpty() ) {
			if( job != null ) {
				logMessage( "Scheduler job has changed from " + job.name + " to null" );

				this.schedulerActiveJob.set( job = null );
				jobChanged = true;
			}
		}
		else if( job == null || job.name.equals( currentJobName ) == false ) {
			logMessage( "Scheduler job has changed from " + (job == null ? "null" : job.name ) + " to " + currentJobName );

			String currentJobJson = (String) this.scheduler.read( "currentJobJson" );

			job = new GsonBuilder().create().fromJson( currentJobJson, SchedulerJob.class );
			if( job != null ) {
				job.fRatio = calculateFRatio();

				try {
					job.loadSequenceContent();
				}
				catch( IOException e ) {
					logError( "Failed to read sequence content", e );
				}
			}
			this.schedulerActiveJob.set( job );
			jobChanged = true;
		}            
		
		//force update
		this.scheduler.determineAndDispatchCurrentState( jobChanged ? null : this.schedulerState.get() );
    }
	public double calculateFRatio() {
		try {
			List<Double> info = this.align.methods.telescopeInfo();
			return ( info.get(0).doubleValue() / info.get( 1 ).doubleValue() ) * info.get( 2 ).doubleValue();
		}
		catch( Throwable t ) {
			logError( "Failed to get telescope info", t );
			return 0;
		}
	}

	protected void loadSchedule( File f ) {

		if( f.exists() ) {
			SchedulerState status = (SchedulerState) scheduler.read( "status" );
			if( status == SchedulerState.SCHEDULER_IDLE ) {
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
			}
			else {
				logMessage( "Scheduler is not idle: " + status );
			}
		}
		else {
			logMessage( "Scheduler File does not exists: " + f.getPath() );
		}
    }
	

	protected void waitForMountTracking( long timeout ) {
        sleep( 1000 );
        WaitUntil maxWait = new WaitUntil( timeout, "Mount Tracking" );
        logMessage( "Wait for mount tracking: " + this.mountStatus.get() );
        boolean mountTracking = false;
        while( !mountTracking && maxWait.check() ) {
            MountStatus state = this.mountStatus.get();
            
            switch( state ) {
                case MOUNT_TRACKING:
                    logMessage( "Mount is tracking now: " + state );
                    mountTracking = true;
                    break;
                default:
                    break;
            }

            sleep( 500 );
        }
    }

	public boolean checkIfPaInRange( double targetPa, double range ) {
        double serverPa = normalizePa( targetPa );

        List<Double> coords = this.align.methods.getSolutionResult();
        double clientPa = normalizePa( coords.get( 0 ).doubleValue() );

        double delta = Math.abs(serverPa - clientPa);
        delta = Math.min( delta, Math.abs( delta - 180 ) );

        if( delta <= range ) {
            logMessage( "The delta between target " + serverPa + " and current " + clientPa + " is less than "+range+" deg: " + delta );
            return true;
        }
        else {
            logMessage( "The delta between target " + serverPa + " and current " + clientPa + " is more than "+range+" deg: " + delta );
            return false;
        }
    }

	public boolean executePaAlignment( double targetPa, double targetRA, double targetDEC ) {
		Mount.ParkStatus currenParkStatus = (Mount.ParkStatus) this.mount.read( "parkStatus" );

        WaitUntil maxWait = new WaitUntil( 60, "Unparking Mount" );
        while( currenParkStatus != Mount.ParkStatus.PARK_UNPARKED && maxWait.check() ) {
            if( currenParkStatus != Mount.ParkStatus.PARK_UNPARKING ) {
                this.mount.methods.unpark();
            }
            currenParkStatus = (Mount.ParkStatus) this.mount.read( "parkStatus" );
        }
      
        logMessage( "Slewing to " + (targetRA / 15.0 ) + " / " + targetDEC );
        this.mount.methods.slew( targetRA / 15.0, targetDEC );
        waitForMountTracking( 60 );

        double pa = normalizePa( targetPa );

        logMessage( "Starting Align process to " + pa );
        this.align.methods.setTargetPositionAngle( pa );
        this.align.methods.setSolverAction( 2 ); //NOTHING
        
        captureAndSolveAndWait( false );

        List<Double> coords = this.align.methods.getSolutionResult();
        logMessage( "Resolved coordinates: " + coords );

        this.align.methods.setTargetPositionAngle( pa );
        this.mount.methods.slew( coords.get(1) / 15.0, coords.get(2) );
        waitForMountTracking( 60 );
        logMessage( "Mount slewed to new coordinates: " + coords );

        this.align.methods.setSolverAction( 1 ); //SYNC
        if( captureAndSolveAndWait( true ) == false ) {
            logMessage( "Alignment failed, retry later" );
            return false;
        }
        else {
            coords = this.align.methods.getSolutionResult();
            logMessage( "PA align done: " + coords );
        }

        return true;
    }

	public int getKStarsRuntime() {
		try {
			Process runtime = Runtime.getRuntime().exec( new String[]{ "ps", "-C", "kstars", "-o", "etimes="} );
				
			InputStream in = runtime.getInputStream();

			runtime.waitFor();
			ByteArrayOutputStream out = new ByteArrayOutputStream();

			byte[] buf = new byte[4096];
			int len = 0;
			while( (len = in.read(buf)) > 0 ) {
				out.write(buf, 0, len);
			}
			
			in.close();
			out.close();

			int rts = Integer.parseInt( out.toString().trim() );

			return rts;
		}
		catch( Throwable t ) {
			return 0;
		}
	}

	public boolean stopEkos() {
		try {
			this.unsubscribe();
		}
		catch( Throwable t ) {
			//SILENT_CATCH
		}

		if( getKStarsRuntime() > 0 ) {
			logMessage( "Stopping Ekos" );
			try {
				this.ekos.methods.stop();
				sleep( 5000 );
				return true;
			}
			catch( Throwable t ) {
				logError( "Failed to stop ekos", t);
				return false;
			}
		}
		else {
			return true;
		}
		
	}
	public void stopKStars() {
		stopEkos();

		if( getKStarsRuntime() > 0 ) {
			logMessage( "Quting KStars" );
			for( int i=0; i<20; i++ ) {
				try {
					this.quitKStars.methods.trigger();
					sleep( 1000 );
				}
				catch( Throwable t ) {
					break;
				}
			}

			try {
				logMessage( "Killing hanging kstars processes" );
				Process kill = Runtime.getRuntime().exec( new String[]{ "killall", "kstars" } );
				
				kill.waitFor();
				logMessage( "Killed previous kstars processes" );

				logMessage( "Killing hanging indi processes" );
				kill = Runtime.getRuntime().exec( new String[]{ "killall", "-9", "-r", "indi.*"} );
				
				kill.waitFor();
				logMessage( "Killed previous indi processes" );
			}
			catch( Throwable tt ) {
				logError( "Failed to kill kstars", tt );
			}
		}
	}
}
