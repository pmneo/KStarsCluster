package de.pmneo.kstars;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.configuration2.INIConfiguration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Request;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.errors.NoReply;
import org.freedesktop.dbus.errors.ServiceUnknown;
import org.freedesktop.dbus.errors.UnknownObject;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.messages.MethodCall;
import org.kde.kstars.Ekos;
import org.kde.kstars.INDI;
import org.kde.kstars.INDI.DriverInterface;
import org.kde.kstars.INDI.IpsState;
import org.kde.kstars.ekos.Align;
import org.kde.kstars.ekos.Align.AlignState;
import org.kde.kstars.ekos.Capture;
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

import com.google.gson.GsonBuilder;

import bsh.Interpreter;

import de.pmneo.kstars.utils.RaDecUtils;
import de.pmneo.kstars.utils.SunriseSunset;
import de.pmneo.kstars.web.CommandServlet.Action;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;


public abstract class KStarsCluster extends KStarsState {

	public static final String PRIMARY_TRAIN = "Primary";
	public static final String SECONDARY_TRAIN = "Secondary";


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


	protected Map<String, IndiCamera> cameraDevices = new HashMap<>();
	protected Map<String, IndiFilterWheel> filterDevices = new HashMap<>();
	protected Map<String, IndiRotator> rotatorDevices = new HashMap<>();
	protected Map<String, IndiCap> capDevices = new HashMap<>();
	protected Map<String, IndiLightBox> lightBoxDevices = new HashMap<>();

	protected final List< Device<?> > mandatoryDevices = new ArrayList<Device<?>>();
	protected final List< Device<?> > devices = new ArrayList<Device<?>>();
	
    private double preCoolTemp = -15;
    public void setPreCoolTemp(double preCoolTemp) {
		for( IndiCamera camera : cameraDevices.values() ) {
			camera.setPreCoolTemp(preCoolTemp);
		}
        this.preCoolTemp = preCoolTemp;
    }
    public double getPreCoolTemp() {
        return preCoolTemp;
    }

	private List<Runnable> subscriptions = new ArrayList<>();

	protected final HttpClient client;

	public KStarsCluster( String logPrefix ) throws DBusException {
		super( logPrefix );

		//make sure no error can escape the log file
		Thread.setDefaultUncaughtExceptionHandler( ( thread, error ) -> {
			logError( "Uncaught exception in thread " + thread.getName(), error );
		} );

		MethodCall.setDefaultTimeout( 5000 );

		/* Get a connection to the session bus so we can get data. */
		con = buildDBusConnection();

		//every synchronous D-Bus call reports here — first NoReply triggers the freeze watchdog
		Device.setHealthListener( new Device.DBusHealthListener() {
			@Override
			public void onSuccess() {
				//nothing to do — watchdog triggers on first NoReply
			}
			@Override
			public void onFailure( Throwable t ) {
				if( t instanceof NoReply == false ) {
					return;
				}
				if( siegeMode.get() || dbusRecoveryInProgress.get() ) {
					//recovery is already dealing with it — stay quiet
					return;
				}
				if( dbusProbeInProgress.compareAndSet( false, true ) == false ) {
					//another thread is probing right now
					return;
				}
				try {
					//verify over a FRESH throwaway connection: distinguishes a frozen KStars
					//from a single slow module call AND from a broken own connection
					if( probeKStarsAlive() ) {
						logMessage( "DBUS WATCHDOG: NoReply on a single call, but KStars answers the probe — no recovery (slow module call)" );
					}
					else {
						triggerDBusFreezeRecovery( t );
					}
				}
				finally {
					dbusProbeInProgress.set( false );
				}
			}
		} );

		client = new HttpClient() {
			@Override
			public Request newRequest(URI uri) {
				return super.newRequest(uri)
					.idleTimeout( 5, TimeUnit.SECONDS )
					.timeout( 10, TimeUnit.SECONDS )
				;
			}
		};
		try {
			client.setConnectTimeout( 2000 );
			client.setIdleTimeout( 5000 );
			client.setAddressResolutionTimeout( 5000L );
			client.setMaxConnectionsPerDestination( 50 );

			//client.setDestinationIdleTimeout( 5000L );
			client.start();
		}
		catch( Throwable t ) {
			logError( "Failed to start http client", t);
		}
	}

	protected void createEkosDevices() throws DBusException {
		this.unsubscribe();

		this.mandatoryDevices.clear();
		this.devices.clear();

		this.showEkos = new Device<>( con, "org.kde.kstars", "/kstars/MainWindow_1/actions/ekos", QAction.class );
		this.quitKStars = new Device<>( con, "org.kde.kstars", "/kstars/MainWindow_1/actions/quit", QAction.class );

		this.ekos = new Device<>( con, "org.kde.kstars", "/KStars/Ekos", Ekos.class );
		this.mandatoryDevices.add( this.ekos );
	}

	protected void createDevices() throws DBusException {
		this.createEkosDevices();

		this.indi = new Device<>( con, "org.kde.kstars", "/KStars/INDI", INDI.class );
		this.devices.add( this.indi );

		this.guide = new Device<>( con, "org.kde.kstars", "/KStars/Ekos/Guide", Guide.class );
		this.devices.add( this.guide );

		this.capture = new Device<>( con, "org.kde.kstars", "/KStars/Ekos/Capture", Capture.class );
		this.devices.add( this.capture );

		this.mount = new Device<>( con, "org.kde.kstars", "/KStars/Ekos/Mount", Mount.class );
		this.devices.add( this.mount );
		this.mandatoryDevices.add( this.mount );

		this.align = new Device<>( con, "org.kde.kstars", "/KStars/Ekos/Align", Align.class, d -> {
			return (Align.AlignState) d.read( "status" );
		});
		this.devices.add( this.align );

		final AtomicReference<String> opticalTrain = new AtomicReference<>();
		this.focus = new Device<>( con, "org.kde.kstars", "/KStars/Ekos/Focus", Focus.class, d -> {
			if( opticalTrain.get() == null ) {
				return Focus.FocusState.FOCUS_IDLE;
			}
			Object[] status = d.methods.status( opticalTrain.get() );
			return Focus.FocusState.values()[ (int) status[0] ];
		 } );
		this.devices.add( this.focus );
		
		this.scheduler = new Device<>( con, "org.kde.kstars", "/KStars/Ekos/Scheduler", Scheduler.class );
		this.devices.add( this.scheduler );
	}

	protected void unsubscribe() throws DBusException {
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
	}

	protected void subscribe() throws DBusException {
		unsubscribe();

		logMessage( "Subscribing to KStars" );

		subscriptions.add( this.ekos.addSigHandler( Ekos.ekosStatusChanged.class, status -> {
			this.handleEkosStatus( status.getStatus() );
		} ) );
		subscriptions.add( this.guide.addNewStatusHandler( Guide.newStatus.class, status -> {
			this.handleGuideStatus( status.getStatus() );
		} ) );
		subscriptions.add( this.capture.addNewStatusHandler( Capture.newStatus.class, status -> {
			this.handleCaptureStatus( status.getStatus(), status.train );
		} ) );
		subscriptions.add( this.mount.addNewStatusHandler( Mount.newStatus.class, status -> {
			this.handleMountStatus( status.getStatus() );
		} ) );
		subscriptions.add( this.mount.addSigHandler( Mount.newParkStatus.class, status -> {
			this.handleMountParkStatus( status.getStatus() );
		} ) );
		subscriptions.add( this.align.addNewStatusHandler( Align.newStatus.class, status -> {
			this.handleAlignStatus( status.getStatus() );
		} ) );
		subscriptions.add( this.align.addSigHandler( Align.newSolution.class, status -> {
			logMessage( "newSolution: " + status.getSolution() );
		} ) );
		subscriptions.add( this.focus.addNewStatusHandler( Focus.newStatus.class, status -> {
			this.handleFocusStatus( status.getStatus(), status.train );
		} ) );
		subscriptions.add( this.focus.addSigHandler( Focus.newHFR.class, hfr -> {
			logDebug( "new hfr " + hfr.getHFR() );
		} ) );
		subscriptions.add( this.scheduler.addNewStatusHandler( Scheduler.newStatus.class, status -> {
			this.handleSchedulerStatus( status.getStatus() );
		} ) );
		subscriptions.add( this.scheduler.addSigHandler( Scheduler.newLog.class, log -> {
			logMessage( "Scheduler: " + log.getText() );

			//the scheduler emits a log line exactly when it ACTS (job started, sleeping,
			//paused, aborted, ...) — use that as an EVENT TRIGGER for the authoritative
			//job/status refresh instead of parsing the localized text. This also gives
			//sub-second latency for scheduler state changes, compensating the newStatus
			//D-Bus signal that KStars 3.8.2 never emits (proven via dbus-monitor).
			updateSchedulerStateDebounced();
		} ) );

		//INDI device enumeration is done SEPARATELY (refreshIndiDevices): INDI.getDevices
		//can block >5s inside KStars (observed 2026-07-20) and a NoReply here previously
		//aborted subscribe() mid-way, leaving us without stable signal subscriptions
		indiDevicesDirty.set( true );
		refreshIndiDevices();

		/*
		String foundCamera = (String) this.capture.read( "camera" );
		cameraDevice = new IndiCamera(foundCamera, indi);
		cameraDevice.setPreCoolTemp( getPreCoolTemp() );

		String foundFocuser = (String) this.focus.methods.focuser( opticalTrain.get() );
		focusDevice = new IndiFocuser(foundFocuser, indi);

		String foundRotator = IndiRotator.findFirstDevice(indi, DriverInterface.ROTATOR_INTERFACE);
		rotatorDevice = new IndiRotator(foundRotator, indi);

		String foundFilterWheel = (String) this.capture.read( "filterWheel" );
		filterDevice = new IndiFilterWheel(foundFilterWheel, indi);
		*/
	}

	/**
	 * INDI device enumeration, isolated from the signal subscriptions: INDI.getDevices
	 * can block >5s inside KStars while it talks to the INDI server. A failure here
	 * must never invalidate the D-Bus signal subscriptions — it just marks the device
	 * maps dirty and the monitor loop retries on its next pass.
	 */
	protected final AtomicBoolean indiDevicesDirty = new AtomicBoolean( true );

	protected boolean refreshIndiDevices() {
		try {
			cameraDevices = IndiDevice.createDevices( indi, DriverInterface.CCD_INTERFACE, IndiCamera::new );
			filterDevices = IndiDevice.createDevices( indi, DriverInterface.FILTER_INTERFACE, IndiFilterWheel::new );
			rotatorDevices = IndiDevice.createDevices( indi, DriverInterface.ROTATOR_INTERFACE, IndiRotator::new );
			capDevices = IndiDevice.createDevices( indi, DriverInterface.DUSTCAP_INTERFACE, IndiCap::new );
			lightBoxDevices = IndiDevice.createDevices( indi, DriverInterface.LIGHTBOX_INTERFACE, IndiLightBox::new );

			indiDevicesDirty.set( false );
			logMessage( "INDI devices refreshed: " + cameraDevices.size() + " cameras, " + filterDevices.size() + " filter wheels, "
				+ rotatorDevices.size() + " rotators, " + capDevices.size() + " caps, " + lightBoxDevices.size() + " light boxes" );
			return true;
		}
		catch( Throwable t ) {
			indiDevicesDirty.set( true );
			logError( "Failed to refresh INDI devices — will retry on next loop pass", t );
			return false;
		}
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
	
	private void loadConfig() throws ConfigurationException, IOException, FileNotFoundException {
		if( config == null ) {
			config = new INIConfiguration();
			config.read( new FileReader( System.getProperty("user.home") + "/.config/kstarsrc" ) );
		}
	}

	private boolean weatherSafty = false;
	private long lastWeatherCheck = -1;

	public boolean checkWeatherStatus() {
		long delta = TimeUnit.MILLISECONDS.toSeconds( System.currentTimeMillis() - this.lastWeatherCheck );

		long updateDelta = 15;

		if( delta >= updateDelta ) {
			boolean weatherSafty = false;
			try {
				//logMessage( "Check weather status, last check was " + delta + " seconds ago");
				var res = client.newRequest( "http://192.168.0.106:8087/getPlainValue/0_userdata.0.Roof.isSafeCondition" ).send();
				weatherSafty = Boolean.parseBoolean( res.getContentAsString() );

				if( delta >= ( updateDelta + 5 ) ) {
					logMessage( "Resumed weather status after "+ delta +" seconds");
				}
				this.lastWeatherCheck = System.currentTimeMillis();
			}
			catch( ExecutionException e ) {
				if( delta < 90 ) {
					logMessage( "Failed to get weather status since "+ delta +" seconds");

					weatherSafty = this.weatherSafty;
				}
				else {
					logError( "Failed to get weather status since more than 90 seconds: " + delta, e);
					weatherSafty = false;
				}
			}
			catch( Throwable t ) {
				logError( "Failed to get weather status", t);
				weatherSafty = false;
			}

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

	public void stopUsbDevices() {
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

	/**
	 * Signal handlers run directly on this single signal thread (see Device
	 * .addSigHandler): serialized on purpose, so at most ONE synchronous call
	 * from signal handlers is in flight against KStars at any time.
	 */
	private DBusConnection buildDBusConnection() throws DBusException {
		return DBusConnectionBuilder.forSessionBus()
			.receivingThreadConfig()
				.withSignalThreadCount( 1 )
			.connectionConfig()
			.build();
	}

	/*
	 * D-Bus connection recycling.
	 *
	 * KStars freezes have proven hard to DETECT from our side: the 2026-07-19
	 * freeze produced not a single NoReply, so the reactive watchdog below never
	 * fired. What reliably CURES a frozen KStars is dropping our bus connection.
	 * Therefore every (re)connect to KStars/Ekos happens on a FRESH connection:
	 * whenever the monitor loop is about to subscribe after Ekos became ready,
	 * the old connection is closed and replaced — a stale or wedged connection
	 * never survives a reconnect cycle. The watchdog additionally recycles
	 * immediately on the first NoReply.
	 */
	protected synchronized void recycleDBusConnection( String reason ) {
		try {
			logMessage( "DBUS RECYCLE (" + reason + "): closing and reopening the D-Bus connection" );

			try {
				unsubscribe();
			}
			catch( Throwable t ) {
				logError( "DBUS RECYCLE: unsubscribe failed", t );
			}

			try {
				con.disconnect();
			}
			catch( Throwable t ) {
				logError( "DBUS RECYCLE: disconnect failed", t );
			}

			con = buildDBusConnection();
			logMessage( "DBUS RECYCLE: reconnected to session bus as " + con.getUniqueName() );

			this.createDevices();

			if( ekosReady.get() ) {
				subscribe();

				//re-dispatch current states so nothing missed during the reconnect gap is lost
				for( Device<?> d : devices ) {
					try {
						d.determineAndDispatchCurrentState();
					}
					catch( Throwable t ) {
						logError( "DBUS RECYCLE: failed to refresh state of " + d.interfaceName, t );
					}
				}

				//job may have changed while the connection was down — fetch it once
				updateSchedulerActiveJob();
			}

			logMessage( "DBUS RECYCLE: done" );
		}
		catch( Throwable t ) {
			logError( "DBUS RECYCLE failed — will retry after next interval", t );
		}
	}

	// Completed by ekosStatusChanged signal (Idle/Error) or crash fallback — replaces D-Bus polling
	private volatile CompletableFuture<Void> ekosStopFuture;

	/*
	 * D-Bus freeze watchdog.
	 *
	 * KStars (observed with 3.8.2, repeatedly during autofocus) can wedge its GUI
	 * while a synchronous D-Bus call from us is pending. On the FIRST NoReply the
	 * watchdog saves a full thread dump as evidence and recycles the connection
	 * immediately — complementing the periodic recycle above, which covers the
	 * freezes that produce no NoReply at all.
	 */
	private final AtomicBoolean dbusRecoveryInProgress = new AtomicBoolean( false );
	private volatile long lastDbusRecoveryAt = 0;

	/**
	 * Liveness probe over a FRESH, private, throwaway connection. This answers "is
	 * KStars alive" INDEPENDENTLY of the health of our main connection — the night of
	 * 2026-07-21 proved that probing through our own connection cannot distinguish a
	 * frozen KStars from a broken own connection and misdiagnosed for hours.
	 */
	protected boolean probeKStarsAlive() {
		DBusConnection probeCon = null;
		try {
			probeCon = DBusConnectionBuilder.forSessionBus().withShared( false ).build();

			final org.freedesktop.dbus.interfaces.Properties props =
				probeCon.getRemoteObject( "org.kde.kstars", "/KStars/Ekos", org.freedesktop.dbus.interfaces.Properties.class );
			props.Get( "org.kde.kstars.Ekos", "ekosStatus" );

			return true;
		}
		catch( Throwable t ) {
			logMessage( "DBUS WATCHDOG: KStars probe failed: " + t.getClass().getSimpleName() + ": " + t.getMessage() );
			return false;
		}
		finally {
			if( probeCon != null ) {
				try {
					probeCon.disconnect();
				}
				catch( Throwable t ) {
					//ignore
				}
			}
		}
	}

	/** Guards against parallel probes when several calls fail at once. */
	private final AtomicBoolean dbusProbeInProgress = new AtomicBoolean( false );

	protected void triggerDBusFreezeRecovery( final Throwable cause ) {
		final long now = System.currentTimeMillis();
		if( now - lastDbusRecoveryAt < TimeUnit.MINUTES.toMillis( 1 ) ) {
			//rate limit: at most one recovery per minute
			return;
		}
		if( dbusRecoveryInProgress.compareAndSet( false, true ) == false ) {
			//recovery already running
			return;
		}
		lastDbusRecoveryAt = now;

		final Thread recovery = new Thread( () -> {
			try {
				runFreezeRecovery( cause );
			}
			catch( Throwable t ) {
				logError( "DBUS WATCHDOG: recovery failed", t );
			}
			finally {
				dbusRecoveryInProgress.set( false );
			}
		}, "DBus-Watchdog-Recovery" );
		recovery.setDaemon( true );
		recovery.start();
	}

	/**
	 * SIEGE mode: while true, the monitor loop stays completely idle and no D-Bus
	 * traffic is generated at all — the programmatic equivalent of killing the java
	 * process, which is the one cure that has reliably unfrozen KStars (2026-07-17).
	 * The night of 2026-07-21 showed the opposite failure mode: recovering every
	 * minute besieged the frozen KStars with thousands of queued calls all night.
	 */
	protected final AtomicBoolean siegeMode = new AtomicBoolean( false );

	private void runFreezeRecovery( final Throwable cause ) {
		logError( "DBUS WATCHDOG: NoReply and fresh-connection probe failed — KStars is unresponsive, starting recovery", cause );
		logMessage( "DBUS WATCHDOG: calls at time of failure — " + Device.dumpCallRegistry() );
		logMessage( "DBUS WATCHDOG: thread dump saved to " + saveThreadDump() );
		logMessage( "DBUS WATCHDOG: open file descriptors: " + countOpenSockets() );

		//attempt 1: one full connection recycle — cures the case where OUR connection was the problem
		recycleDBusConnection( "watchdog: first recovery attempt" );
		sleep( 5000L );

		if( probeKStarsAlive() ) {
			logMessage( "DBUS WATCHDOG: KStars responds after recycle — recovery complete" );
			return;
		}

		//SIEGE: mimic killing the java process — close our connection, do NOT
		//reconnect, stay completely silent and give KStars room to breathe
		final long siegeStart = System.currentTimeMillis();
		siegeMode.set( true );
		try {
			logMessage( "DBUS SIEGE: KStars still unresponsive — closing connection and going completely silent" );
			try {
				unsubscribe();
			}
			catch( Throwable t ) {
				//already logged inside
			}
			try {
				con.disconnect();
			}
			catch( Throwable t ) {
				logError( "DBUS SIEGE: disconnect failed", t );
			}

			//2 minutes of silence, probing once per minute — if that doesn't cure it, kill & restart
			boolean alive = false;
			for( long backoffMinutes : new long[] { 1, 1 } ) {
				logMessage( "DBUS SIEGE: silent for the next " + backoffMinutes + " min, then probing again" );
				sleep( TimeUnit.MINUTES.toMillis( backoffMinutes ) );

				if( probeKStarsAlive() ) {
					alive = true;
					break;
				}
			}

			final long outageMinutes = TimeUnit.MILLISECONDS.toMinutes( System.currentTimeMillis() - siegeStart );
			if( alive ) {
				logMessage( "DBUS SIEGE: KStars responds again after ~" + outageMinutes + " min of silence — rebuilding" );
			}
			else {
				//escalation: silence did not cure it — do what the operator does manually
				logMessage( "DBUS SIEGE: KStars still unresponsive after ~" + outageMinutes + " min of silence — killing and restarting KStars" );
				try {
					stopKStars();
				}
				catch( Throwable t ) {
					logError( "DBUS SIEGE: stopKStars failed", t );
				}
				sleep( 5000L );
			}
		}
		finally {
			siegeMode.set( false );
		}

		recycleDBusConnection( "siege ended — rebuilding" );

		//wake the monitor loop so it re-evaluates (and restarts KStars/Ekos if we killed it)
		final CompletableFuture<Void> f = ekosStopFuture;
		if( f != null ) {
			f.complete( null );
		}
	}

	/** Diagnostic for connection leaks: counts open sockets among our file descriptors. */
	protected String countOpenSockets() {
		try {
			final File fdDir = new File( "/proc/self/fd" );
			final File[] fds = fdDir.listFiles();
			if( fds == null ) {
				return "(unavailable)";
			}

			int sockets = 0;
			for( File fd : fds ) {
				try {
					if( java.nio.file.Files.readSymbolicLink( fd.toPath() ).toString().startsWith( "socket:" ) ) {
						sockets++;
					}
				}
				catch( Throwable t ) {
					//ignore raced fds
				}
			}
			return sockets + " sockets of " + fds.length + " fds";
		}
		catch( Throwable t ) {
			return "(unavailable: " + t + ")";
		}
	}

	protected String saveThreadDump() {
		try {
			final ThreadMXBean mx = ManagementFactory.getThreadMXBean();
			final ThreadInfo[] infos = mx.dumpAllThreads( mx.isObjectMonitorUsageSupported(), mx.isSynchronizerUsageSupported() );

			final StringBuilder dump = new StringBuilder();
			dump.append( "Full thread dump taken " ).append( new Date() ).append( " by DBUS WATCHDOG\n\n" );

			try {
				final long[] deadlocked = mx.findDeadlockedThreads();
				if( deadlocked != null && deadlocked.length > 0 ) {
					dump.append( "!!! DEADLOCK detected, involved thread ids: " ).append( Arrays.toString( deadlocked ) ).append( "\n\n" );
				}
			}
			catch( Throwable t ) {
				dump.append( "(deadlock detection failed: " ).append( t ).append( ")\n\n" );
			}

			for( ThreadInfo info : infos ) {
				if( info == null ) {
					continue;
				}

				dump.append( '"' ).append( info.getThreadName() ).append( "\" #" ).append( info.getThreadId() )
					.append( info.isDaemon() ? " daemon" : "" )
					.append( " prio=" ).append( info.getPriority() )
					.append( " state=" ).append( info.getThreadState() );

				if( info.getLockName() != null ) {
					dump.append( " on " ).append( info.getLockName() );
					if( info.getLockOwnerName() != null ) {
						dump.append( " owned by \"" ).append( info.getLockOwnerName() ).append( "\" #" ).append( info.getLockOwnerId() );
					}
				}
				dump.append( '\n' );

				final StackTraceElement[] stack = info.getStackTrace();
				final MonitorInfo[] monitors = info.getLockedMonitors();

				for( int depth = 0; depth < stack.length; depth++ ) {
					dump.append( "\tat " ).append( stack[ depth ] ).append( '\n' );

					if( depth == 0 && info.getLockInfo() != null ) {
						switch( info.getThreadState() ) {
							case BLOCKED:
								dump.append( "\t-  blocked on " ).append( info.getLockInfo() ).append( '\n' );
								break;
							case WAITING:
							case TIMED_WAITING:
								dump.append( "\t-  waiting on " ).append( info.getLockInfo() ).append( '\n' );
								break;
							default:
								break;
						}
					}

					for( MonitorInfo mi : monitors ) {
						if( mi.getLockedStackDepth() == depth ) {
							dump.append( "\t-  locked " ).append( mi ).append( '\n' );
						}
					}
				}

				final LockInfo[] synchronizers = info.getLockedSynchronizers();
				if( synchronizers.length > 0 ) {
					dump.append( "\n\tLocked ownable synchronizers:\n" );
					for( LockInfo li : synchronizers ) {
						dump.append( "\t-  " ).append( li ).append( '\n' );
					}
				}

				dump.append( '\n' );
			}

			final String fileName = "./KStarsThreadDump_" + new SimpleDateFormat( "yyyy-MM-dd_HHmmss" ).format( new Date() ) + ".txt";
			try( FileOutputStream out = new FileOutputStream( fileName ) ) {
				out.write( dump.toString().getBytes( Charset.forName( "UTF-8" ) ) );
			}
			return fileName;
		}
		catch( Throwable t ) {
			logError( "Failed to save thread dump", t );
			return "(failed to save)";
		}
	}

	public Ekos.CommunicationStatus handleEkosStatus( Ekos.CommunicationStatus state ) {
		state = super.handleEkosStatus( state );

		if( state == Ekos.CommunicationStatus.Idle || state == Ekos.CommunicationStatus.Error ) {
			CompletableFuture<Void> f = ekosStopFuture;
			if( f != null ) {
				logMessage( "Ekos stopped (status=" + state + "), signaling monitor loop" );
				f.complete( null );
			}
		}

		return state;
	}

	private AtomicBoolean opticalTrain;
	public void connectToKStars() {
		if( kStarsMonitor != null ) {
			if( kStarsMonitor.isAlive() ) {
				return;
			}
		}

		kStarsMonitor = new Thread( () -> {
			Thread.currentThread().setName( "KStars Monitor Thread" );

			long ekosStoppedAt = 0;

			while( true ) { try {
				if( siegeMode.get() ) {
					logMessageOnce( "DBUS SIEGE active — monitor loop idle" );
					sleep( 5000L );
					continue;
				}

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

						//every (re)connect to Ekos happens on a fresh D-Bus connection —
						//a wedged connection never survives a reconnect cycle
						recycleDBusConnection( "reconnecting to Ekos" );

						subscribe();
						ekosReady();

						ekosReady.set( true );

						waitUntilEkosHasStopped();

						ekosStoppedAt = checkShutdownUsb( ekosStoppedAt );
						
						logMessage( "Ekos has stopped, waiting to become ready again" );
						
						ekosDisconnected();
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
					if( runtime < 0 ) {
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
		for( String opticalTrain : this.focusState.keySet() ) {
        	this.focus.methods.abort( opticalTrain );
		}
        this.align.methods.abort();
        this.scheduler.methods.stop();
    }

	private void waitUntilEkosHasStopped() {
		ekosStopFuture = new CompletableFuture<>();
		Long weatherBadSince = null;

		try {
			while( !ekosStopFuture.isDone() ) {
				long start = System.currentTimeMillis();

				if( siegeMode.get() ) {
					logMessageOnce( "DBUS SIEGE active — monitor loop idle" );
					sleep( 5000L );
					continue;
				}

				// Crash fallback: ps process check — no D-Bus call needed
				if( getKStarsRuntime() < 0 ) {
					logMessage( "KStars process gone, exiting monitor loop" );
					break;
				}

				if( indiDevicesDirty.get() ) {
					refreshIndiDevices();
				}

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
							if( this.captureRunning.values().contains( Boolean.TRUE ) )  {
								waitToStopReasons.append( "\n\tA capture is in progress" );
								canStop = false;
							}

							var lastCapture = System.currentTimeMillis() - lastCapturedImage.get();

							if( lastCapture < TimeUnit.MINUTES.toMillis( 10 ) ) {
								waitToStopReasons.append( "\n\tLast capture was less than 10 Minutes ago" );
								canStop = false;
							}
							else if( lastCapture < TimeUnit.MINUTES.toMillis( 30 ) ) {
								for( IndiCamera camera : cameraDevices.values() ) {
									camera.warm();
								}

								waitToStopReasons.append( "\n\tLast capture was less than 30 Minutes ago" );
								canStop = false;
							}

							if( canStop == false ) {
								logMessageOnce( waitToStopReasons.toString() );
							}
							else {
								logMessage( "Shutting down Ekos / KStars after " + (badWeatherDuration / 1000 / 60 ) + " Minutes" );

								try {
									WaitUntil maxWait = new WaitUntil( 20, "changeFilter" );

									for( IndiFilterWheel filterWheel : filterDevices.values() ) {
										logMessage( "Setting Filter slot to L of " + filterWheel.deviceName );
										filterWheel.setFilterSlot( 1 );
									}

									while( filterDevices.values().stream().anyMatch( fw -> fw.getFilterSlotStatus() != IpsState.IPS_OK ) && maxWait.check() ) {
										try { Thread.sleep( 10 ); } catch( Throwable t ) {};
									}

									for( String train : this.focusState.keySet() ) {
										logMessage( "Caputure one focus image on train " + train);
										this.focus.methods.capture( train, 0 );
									}

									sleep( 1000L );

									maxWait.reset();

									while( this.focusState.values().stream().anyMatch( s -> s != FocusState.FOCUS_IDLE ) && maxWait.check() ) {
										try { Thread.sleep( 10 ); } catch( Throwable t ) {};
									}

									logMessage( "Caputure one focus image done");

									sleep( 5000L );
								}
								catch( Throwable t ) {
									logError( "Failed to go back to L before shutdown", t );
								}

								ensureMountIsParked();

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
				long remaining = Math.max( 500, ekosLoopDelay - checkTime );
				try {
					ekosStopFuture.get( remaining, TimeUnit.MILLISECONDS );
				}
				catch( TimeoutException e ) {
					// Normal loop timeout, continue
				}
				catch( Exception e ) {
					break;
				}
			}
		}
		finally {
			ekosStopFuture = null;
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
			catch( UnknownObject | ServiceUnknown uo ) {
				ekosReady.set( false );
				return false;
			}
			catch( Throwable t ) {
				logError( "Failed to check device " + d.interfaceName, t );
			}
		}

		boolean allConnected = true;
		if( autoConnect ) {
			try {
				for( String device : this.indi.methods.getDevices() ) {
					String state = this.indi.methods.getPropertyState( device, "CONNECTION" );
					String connected = this.indi.methods.getSwitch( device, "CONNECTION", "CONNECT" );
					
					if( "Ok".equals( state ) && "On".equals( connected ) ) {
						continue;
					}
					else {
						allConnected = false;
						logMessage( "The device " + device + " is not connected: " + state + "/" + connected );
						/*
						if( autoConnect ) {
							this.indi.methods.setSwitch( device, "CONNECTION", "CONNECT", "On" );
							this.indi.methods.sendProperty( device, "CONNECTION" );
						}
						*/
					}
				}
			}
			catch( Throwable t ) {
				logError( "Failed to query indi device status", t );
			}
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

		//initial job determination: when we connect while a job is ALREADY executing,
		//no scheduler newLog will fire until the next scheduler action — fetch it once
		updateSchedulerActiveJob();

		/*
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
		*/
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

	protected final ConcurrentHashMap<String, Long> activeCaptureJobStarted = new ConcurrentHashMap<>();
	protected final ConcurrentHashMap<String, Long> captureStateChangedAt = new ConcurrentHashMap<>();

	protected final AtomicLong lastCapturedImage = new  AtomicLong(System.currentTimeMillis());

	public CaptureStatus handleCaptureStatus( CaptureStatus state, String train ) {
		boolean captureWasRunning = captureRunning.computeIfAbsent( train, t -> false );

		state = super.handleCaptureStatus(state, train);

		captureStateChangedAt.put( train, System.currentTimeMillis() );

		if( state == CaptureStatus.CAPTURE_CAPTURING ) {
			lastCapturedImage.set( System.currentTimeMillis() );
		}

		if( ( captureWasRunning == false || state == CaptureStatus.CAPTURE_PROGRESS ) && captureRunning.computeIfAbsent( train, t -> false ) ) {
			activeCaptureJobStarted.put( train, System.currentTimeMillis() );
		}
		else if( captureWasRunning == true && captureRunning.computeIfAbsent( train, t -> false ) == false ) {
			this.activeCaptureJobStarted.put( train, -1L );
		}

		return state;
	}


	public FocusState handleFocusStatus( FocusState state, String train ) {
		state = super.handleFocusStatus( state, train );
		return state;
	}
	
    protected void checkCameraCooling( KStarsState state ) {
		SchedulerState schedulerStatus = state.schedulerState.get();
		MountStatus mountStatus = state.mountStatus.get();

		switch( mountStatus ) {
			case MOUNT_PARKED:
			case MOUNT_PARKING:
				for( IndiCamera camera : cameraDevices.values() ) {
					camera.warm();
				}
			break;
			default:
				break;
		}

        switch( schedulerStatus ) {
            case SCHEDULER_ABORTED:
            case SCHEDULER_IDLE:
            case SCHEDULER_SHUTDOWN:
				for( IndiCamera camera : cameraDevices.values() ) {
					camera.warm();
				}
			break;
                
            case SCHEDULER_LOADING:
            case SCHEDULER_PAUSED:
            case SCHEDULER_STARTUP:
            break;
                
            case SCHEDULER_RUNNING:
				switch( mountStatus ) {
					case MOUNT_SLEWING:
					case MOUNT_TRACKING:
						for( IndiCamera camera : cameraDevices.values() ) {
							camera.preCool();
						}
					break;

					default:
						break;
				}
            break;
        }
    }


	public void runAutoFocus() {
		
		String train = PRIMARY_TRAIN;

		this.focus.methods.abort( train );
		sleep( 1000 );
		this.focus.methods.start( train );

		final WaitUntil maxWait = new WaitUntil( 5, "Focusing" );

		while( !this.focusRunning.get( train ) && maxWait.check() ) {
			sleep( 10 );
		}

		logMessage( "Focus process has started" );

		maxWait.reset( 300 );

		while( this.focusRunning.get( train ) && maxWait.check() ) {
			sleep(10);
		}
	}

	public abstract void listen();


	protected void unparkCap() {
        for( IndiCap cap : capDevices.values() ) {
            try {
                if( cap.isParked() ) {
                    logMessage( "Request cap unpark for " + cap.deviceName );
                    cap.unpark();
                }
            }
            catch( Throwable t ) {
                logError( "Failed to request unpark cap " + cap.deviceName, t);
            }
        }
    }

    protected void parkCap() {
        for( IndiCap cap : capDevices.values() ) {
            try {
                if( !cap.isParked() ) {
                    logMessage( "Request cap park for " + cap.deviceName );
                    cap.park();
                }
            }
            catch( Throwable t ) {
                logError( "Failed to request unpark cap " + cap.deviceName, t);
            }
        }
    }

	public AtomicBoolean automationSuspended = new AtomicBoolean( false );

	public void addActions( Map<String, Action> actions ) {
        actions.put( "status", this::statusAction );

		actions.put( "suspend", ( parts, req, resp ) -> {
			automationSuspended.set( true );
			return this.statusAction(parts, req, resp);
		} );
		actions.put( "resume", ( parts, req, resp ) -> {
			automationSuspended.set( false );
			return this.statusAction(parts, req, resp);
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
		
		
		Map<String,Object> res = new LinkedHashMap<>();
		

		for( IndiFilterWheel filterDevice : filterDevices.values() ) {
			Map<String,Object> device = new LinkedHashMap<>();
			List<String> filters = filterDevice.getFilters() ;
			device.put( "filters", filters );
			device.put( "currentFilter", filters.get( filterDevice.getFilterSlot() - 1 ) );
			
			res.put( filterDevice.deviceName, device );
		}


		for( IndiCamera cameraDevice : cameraDevices.values() ) {
			Map<String,Object> camera = new LinkedHashMap<>();

			camera.put( "name", cameraDevice.deviceName );
			camera.put( "temperature", cameraDevice.getCcdTemparatur() );
			camera.put( "antiDewHeaterOn", cameraDevice.isAntiDewHeaterOn() );
			camera.put( "isCooling", cameraDevice.isCooling() );

			res.put( cameraDevice.deviceName, camera );
		}

		for( IndiCap capDevice : capDevices.values() ) {
			Map<String,Object> cap = new LinkedHashMap<>();

			cap.put( "name", capDevice.deviceName );
			cap.put( "parked", capDevice.isParked() );

			if( "park".equals( req.getParameter( "capPark" ) ) ) {
				capDevice.park();
			}
			else if( "unpark".equals( req.getParameter( "capPark" ) ) ) {
				capDevice.unpark();
			}
		

			res.put( capDevice.deviceName, cap );
		}

		res.put( "automationSuspended", this.automationSuspended.get() );
			
		fillStatus( res );
		
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

	public boolean captureAndSolveAndWait( boolean autoSync ) {


        final AtomicBoolean alignRunning = new AtomicBoolean( true );

		final AtomicBoolean alignFailed = new AtomicBoolean( false );

		final List<Runnable> unsub = new ArrayList<>();
		
		try {
			//max wait 20 seconds
			final WaitUntil maxWait = new WaitUntil( 20, "Capture and Solve" );

			IndiRotator rotator = rotatorDevices.values().iterator().next();

			IpsState rotatorState = rotator.getRotatorPositionStatus();

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
				IpsState cRotatorState = rotator.getRotatorPositionStatus();

				if( cRotatorState == IpsState.IPS_BUSY ) {
					maxWait.reset();
				}

				if( cRotatorState != rotatorState ) {
					rotatorState = cRotatorState;

					logMessage( "Rotator is " + cRotatorState );
				}

				sleep( 500 );
			}

			return !alignFailed.get();
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
		updateSchedulerActiveJob();

		this.scheduler.determineAndDispatchCurrentState( this.schedulerState.get() );
    }

	/**
	 * Signal-triggered variant of {@link #updateSchedulerState()}: rate-limited so a
	 * newLog burst (scheduler startup logs several lines within milliseconds) causes
	 * only one refresh — the regular 5s poll covers the tail of a burst anyway.
	 */
	private volatile long lastSignalTriggeredRefreshAt = 0;

	protected void updateSchedulerStateDebounced() {
		final long now = System.currentTimeMillis();
		if( now - lastSignalTriggeredRefreshAt < 1000L ) {
			return;
		}
		lastSignalTriggeredRefreshAt = now;

		try {
			updateSchedulerState();
		}
		catch( Throwable t ) {
			logError( "Failed to refresh scheduler state from newLog signal", t );
		}
	}

	/**
	 * Tracks the scheduler's active job via the currentJobJson property (one read per
	 * poll — it carries name, state, stage and target RA/DEC in one go). Unlike the
	 * old name-based tracking this also sees state changes WITHIN the same job, i.e.
	 * JOB_SCHEDULED (waiting for startup time) -> JOB_BUSY (actually executing).
	 */
	protected void updateSchedulerActiveJob() {
		try {
			logDebug( "Updating scheduler active job" );
			final String currentJobJson = (String) this.scheduler.read( "currentJobJson" );

			SchedulerJob job = null;
			if( currentJobJson != null && currentJobJson.isBlank() == false ) {
				job = new GsonBuilder().create().fromJson( currentJobJson, SchedulerJob.class );
			}
			if( job != null && ( job.name == null || job.name.isEmpty() ) ) {
				job = null;
			}

			final SchedulerJob prev = this.schedulerActiveJob.get();

			if( job == null ) {
				if( prev != null ) {
					logMessage( "Scheduler job has changed from " + prev.name + " to null" );
					this.schedulerActiveJob.set( null );
				}
			}
			else if( prev == null || prev.name.equals( job.name ) == false ) {
				logMessage( "Scheduler job has changed from " + ( prev == null ? "null" : prev.name ) + " to " + job.name + " (" + job.getState() + ")" );

				job.fRatio = calculateFRatio();
				try {
					job.loadSequenceContent();
				}
				catch( IOException e ) {
					logError( "Failed to read sequence content", e );
				}

				this.schedulerActiveJob.set( job );
			}
			else {
				//same job: carry over the expensive parts, but keep state/stage/progress fresh
				job.fRatio = prev.fRatio;
				job.sequenceContent = prev.sequenceContent;

				if( prev.state != job.state ) {
					logMessage( "Scheduler job '" + job.name + "' state changed from " + prev.getState() + " to " + job.getState() );
				}

				this.schedulerActiveJob.set( job );
			}
		}
		catch( Throwable t ) {
			logError( "Failed to update scheduler active job", t );
		}
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

			var sout = out.toString().trim().split("\n")[0].trim();
			if( sout.isBlank() ) {
				return -1;
			}
			else {
				return Integer.parseInt(sout);
			}
		}
		catch( Throwable t ) {
			logError( "Failed to get KStars pid", t );
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
