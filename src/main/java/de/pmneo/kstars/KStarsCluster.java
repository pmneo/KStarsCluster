package de.pmneo.kstars;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Request;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.Properties;
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
import org.kde.kstars.ekos.SchedulerJob;
import org.qtproject.Qt.QAction;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import bsh.Interpreter;

import de.pmneo.kstars.utils.Coordinates;
import de.pmneo.kstars.utils.FitsThumbnail;
import de.pmneo.kstars.utils.RaDecUtils;
import de.pmneo.kstars.web.CommandServlet.Action;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;


public abstract class KStarsCluster extends KStarsState {

	public static final String PRIMARY_TRAIN = "Primary";
	public static final String SECONDARY_TRAIN = "Secondary";

	protected DBusConnection con;

	public final KStarsConfig config = new KStarsConfig();
	public final Weather weather = new Weather();

	protected final ScheduledExecutorService schedulerService = Executors.newScheduledThreadPool(1);

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


	protected Map<String, List<IndiDevice>> indiDevices = new HashMap<>();
	protected Map<String, IndiCamera> cameraDevices = new HashMap<>();
	protected Map<String, IndiFilterWheel> filterDevices = new HashMap<>();
	protected Map<String, IndiRotator> rotatorDevices = new HashMap<>();
	protected Map<String, IndiCap> capDevices = new HashMap<>();
	protected Map<String, IndiLightBox> lightBoxDevices = new HashMap<>();

	protected final List< Device<?> > devices = new ArrayList<Device<?>>();

	// Minimum number of each INDI device kind expected to be present. Used by subscribe()
	// to tell "not enumerated yet" (driver reports asynchronously, give it a moment) apart
	// from "genuinely missing" (broken/disconnected hardware) instead of trusting whatever
	// happens to be enumerated in the first pass.
	private int requiredCameras = 2;
	private int requiredFilterWheels = 1;
	private int requiredRotators = 2;
	private int requiredCaps = 2;
	private int requiredLightBoxes = 2;

	public void setRequiredCameras( int requiredCameras ) {
		this.requiredCameras = requiredCameras;
	}
	public void setRequiredFilterWheels( int requiredFilterWheels ) {
		this.requiredFilterWheels = requiredFilterWheels;
	}
	public void setRequiredRotators( int requiredRotators ) {
		this.requiredRotators = requiredRotators;
	}
	public void setRequiredCaps( int requiredCaps ) {
		this.requiredCaps = requiredCaps;
	}
	public void setRequiredLightBoxes( int requiredLightBoxes ) {
		this.requiredLightBoxes = requiredLightBoxes;
	}

	// Set to false whenever subscribe() gives up waiting for the expected device counts
	// before they were all met — the shutdown gate must not trust an incomplete device
	// enumeration (e.g. a cap that's still missing from capDevices doesn't get checked at
	// all, which would otherwise look like "nothing left to park").
	protected final AtomicBoolean devicesComplete = new AtomicBoolean( true );

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

	private final List<Runnable> subscriptions = new ArrayList<>();

	protected final HttpClient client;

	public KStarsCluster( String logPrefix ) throws DBusException {
		super( logPrefix );

		//make sure no error can escape the log file
		Thread.setDefaultUncaughtExceptionHandler( ( thread, error ) -> {
			logError( "Uncaught exception in thread " + thread.getName(), error );
		} );

		MethodCall.setDefaultTimeout( 5000 );

		//every synchronous D-Bus call reports here — first NoReply triggers the freeze watchdog
		Device.setHealthListener( new Device.DBusHealthListener() {
			@Override
			public void onSuccess() {
				//nothing to do — watchdog triggers on first NoReply
			}
			@Override
			public void onFailure( Throwable t ) {
				//nothing to do
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
			client.start();
		}
		catch( Throwable t ) {
			logError( "Failed to start http client", t);
		}

		schedulerService.scheduleWithFixedDelay( this::broadcastStatusIfChanged, 1, 1, TimeUnit.SECONDS );
	}

	private String lastBroadcastStatus = null;
	private void broadcastStatusIfChanged() {
		try {
			refreshSequenceQueueStatus();
			refreshMountCoords();
			refreshFov();

			String json = new GsonBuilder().create().toJson( buildStatusSnapshot() );
			if( !json.equals( lastBroadcastStatus ) ) {
				lastBroadcastStatus = json;
				StatusBroadcaster.getInstance().broadcast( json );
			}
		}
		catch( Throwable t ) {
			logError( "Failed to broadcast status", t );
		}
	}

	/**
	 * Capture.getSequenceQueueStatusJSON(train) is a synchronous D-Bus call, so — same rule as
	 * everywhere else — it only ever runs from this periodic broadcaster thread, never from a
	 * signal handler. Runs once per second, same cadence as the broadcast itself, so the web
	 * UI's remaining-time countdown tracks Ekos's own live view.
	 */
	private void refreshSequenceQueueStatus() {
		if( !ekosReady.get() || this.capture == null ) {
			return;
		}

		Set<String> trains = new LinkedHashSet<>( captureStatus.keySet() );
		trains.add( PRIMARY_TRAIN );

		for( String train : trains ) {
			try {
				String json = this.capture.methods.getSequenceQueueStatusJSON( train );
				List<?> parsed = new Gson().fromJson( json, List.class );
				if( parsed != null && !parsed.isEmpty() ) {
					sequenceQueueStatus.put( train, parsed.get( 0 ) );
				}
			}
			catch( Throwable t ) {
				logDebug( "Failed to refresh sequence queue status for " + train + ": " + t );
			}
		}
	}

	/** Mount.equatorialCoords is a plain D-Bus property read (RA hours, DEC degrees) — same
	 *  broadcaster-thread-only rule as the sequence queue refresh above. Feeds the sky map's
	 *  "where is the telescope pointing right now" marker. */
	@SuppressWarnings("unchecked")
	private void refreshMountCoords() {
		if( !ekosReady.get() || this.mount == null ) {
			return;
		}

		try {
			List<Double> coords = (List<Double>) this.mount.read( "equatorialCoords" );
			if( coords != null && coords.size() >= 2 ) {
				mountCoords.set( new double[]{ coords.get( 0 ), coords.get( 1 ) } );
			}
		}
		catch( Throwable t ) {
			logDebug( "Failed to refresh mount coordinates: " + t );
		}
	}

	/** Align.fov is [widthArcmin, heightArcmin, arcsecPerPixel] — same read-only-property,
	 *  broadcaster-thread-only rule. Feeds the sky map's FOV rectangle. */
	@SuppressWarnings("unchecked")
	private void refreshFov() {
		if( !ekosReady.get() || this.align == null ) {
			return;
		}

		try {
			List<Double> fovValues = (List<Double>) this.align.read( "fov" );
			if( fovValues != null && fovValues.size() >= 2 && fovValues.get( 0 ) > 0 && fovValues.get( 1 ) > 0 ) {
				fov.set( new double[]{ fovValues.get( 0 ), fovValues.get( 1 ) } );
			}
		}
		catch( Throwable t ) {
			logDebug( "Failed to refresh FOV: " + t );
		}
	}

	protected void createDevices() throws DBusException {
		if( this.con != null ) {
			logMessage( "Creating devices, while a con is active" );
			return;
		}

		logMessage( "Subscribing to KStars" );

		this.con = buildDBusConnection();
		this.devices.clear();
		this.indiDevices.clear();

		this.showEkos = new Device<>( con, "org.kde.kstars", "/kstars/MainWindow_1/actions/ekos", QAction.class );
		this.quitKStars = new Device<>( con, "org.kde.kstars", "/kstars/MainWindow_1/actions/quit", QAction.class );

		this.ekos = new Device<>( con, "org.kde.kstars", "/KStars/Ekos", Ekos.class );
		this.devices.add( this.ekos );

		this.indi = new Device<>( con, "org.kde.kstars", "/KStars/INDI", INDI.class );
		this.devices.add( this.indi );

		this.guide = new Device<>( con, "org.kde.kstars", "/KStars/Ekos/Guide", Guide.class );
		this.devices.add( this.guide );

		this.capture = new Device<>( con, "org.kde.kstars", "/KStars/Ekos/Capture", Capture.class );
		this.devices.add( this.capture );

		this.mount = new Device<>( con, "org.kde.kstars", "/KStars/Ekos/Mount", Mount.class );
		this.devices.add( this.mount );

		this.align = new Device<>( con, "org.kde.kstars", "/KStars/Ekos/Align", Align.class );
		this.devices.add( this.align );

		this.focus = new Device<>( con, "org.kde.kstars", "/KStars/Ekos/Focus", Focus.class, d -> Focus.FocusState.FOCUS_IDLE );
		this.devices.add( this.focus );
		
		this.scheduler = new Device<>( con, "org.kde.kstars", "/KStars/Ekos/Scheduler", Scheduler.class );
		this.devices.add( this.scheduler );


		if( !subscriptions.isEmpty() ) {
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

		subscriptions.add( this.ekos.addSigHandler( Ekos.ekosStatusChanged.class, status -> {
			this.handleEkosStatus( status.getStatus() );
		} ) );
		subscriptions.add( this.ekos.addSigHandler( Ekos.indiStatusChanged.class, status -> {
			this.handleEkosIndiStatus( status.getStatus() );
		} ) );
		subscriptions.add( this.guide.addNewStatusHandler( Guide.newStatus.class, status -> {
			this.handleGuideStatus( status.getStatus() );
		} ) );
		subscriptions.add( this.capture.addNewStatusHandler( Capture.newStatus.class, status -> {
			this.handleCaptureStatus( status.getStatus(), status.train );
		} ) );
		subscriptions.add( this.capture.addSigHandler( Capture.captureComplete.class, sig -> {
			logMessage( "Captured " + sig.getMetadata().get( "filename" ) + " (" + sig.getTrain() + ")" );
			recordCapturedImage( sig.getTrain(), sig.getMetadata() );
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
			logDebug( hfr.getName() + ": new hfr " + hfr.getHFR() + " @ " + hfr.getPosition() );
			recordHfr( hfr.getTrain(), hfr.getHFR(), hfr.getPosition() );
		} ) );
		subscriptions.add( this.scheduler.addNewStatusHandler( Scheduler.newStatus.class, status -> {
			this.handleSchedulerStatus( status.getStatus() );
		} ) );
		subscriptions.add( this.scheduler.addSigHandler( Scheduler.newLog.class, log -> {
			logMessage( "Scheduler: " + log.getText() );
		} ) );
		subscriptions.add( this.scheduler.addSigHandler( Scheduler.jobStarted.class, sig -> {
			logMessage( "Scheduler: job started: " + sig.getJobName() );
			updateSchedulerActiveJob( sig.getJobName() );
		} ) );
		subscriptions.add( this.scheduler.addSigHandler( Scheduler.jobEnded.class, sig -> {
			logMessage( "Scheduler: job ended: " + sig.getJobName() + " (" + sig.getEndReason() + ")" );
			updateSchedulerActiveJob( null );
		} ) );
		subscriptions.add( this.ekos.addSigHandler( Ekos.heartbeat.class, sig -> {
			//logDebug( "Heartbeat received" );
			lastHeartbeat.set( System.currentTimeMillis() );
		} ) );
		subscriptions.add( this.indi.addSigHandler( INDI.propertyValueChanged.class, sig -> {
			var devices = indiDevices.get( sig.getDevice() );
			if( devices != null ) {
				for( var d : devices ) {
					d.onPropertyChanged( sig.getProperty(), sig.getJson() );
				}
			}
		} ) );

		this.handleEkosStatus( (Ekos.CommunicationStatus) this.ekos.read( "ekosStatus" ) );
		this.handleEkosIndiStatus( (Ekos.CommunicationStatus) this.ekos.read( "indiStatus" ) );
	}

	protected void subscribe() throws DBusException {
		// Drivers report themselves to KStars/INDI asynchronously, so right after a (re)connect
		// the device enumeration can be short for a moment even though every driver is actually
		// there. Retry for a few seconds instead of trusting the very first enumeration —
		// otherwise a transient short read (e.g. "0 caps") gets treated as final truth.
		devicesComplete.set( WaitUntil.waitUntil( "Waiting for expected INDI device count", 10, () -> {
			cameraDevices = IndiDevice.createDevices( indi, DriverInterface.CCD_INTERFACE, IndiCamera::new );
			filterDevices = IndiDevice.createDevices( indi, DriverInterface.FILTER_INTERFACE, IndiFilterWheel::new );
			rotatorDevices = IndiDevice.createDevices( indi, DriverInterface.ROTATOR_INTERFACE, IndiRotator::new );
			capDevices = IndiDevice.createDevices( indi, DriverInterface.DUSTCAP_INTERFACE, IndiCap::new );
			lightBoxDevices = IndiDevice.createDevices( indi, DriverInterface.LIGHTBOX_INTERFACE, IndiLightBox::new );

			return cameraDevices.size() >= requiredCameras
					&& filterDevices.size() >= requiredFilterWheels
					&& rotatorDevices.size() >= requiredRotators
					&& capDevices.size() >= requiredCaps
					&& lightBoxDevices.size() >= requiredLightBoxes;
		} ) );

		indiDevices.clear();
		BiConsumer<String,IndiDevice> putAll = (k,v) -> {
			indiDevices.compute( k, (n,l) -> {
				if( l == null ) {
					l = new ArrayList<>();
				}
				l.add( v );
				return l;
			});
		};
		cameraDevices.forEach(putAll);
		filterDevices.forEach(putAll);
		rotatorDevices.forEach(putAll);
		capDevices.forEach(putAll);
		lightBoxDevices.forEach(putAll);


		logMessage( "INDI devices refreshed: " + cameraDevices.size() + " cameras, " + filterDevices.size() + " filter wheels, "
				+ rotatorDevices.size() + " rotators, " + capDevices.size() + " caps, " + lightBoxDevices.size() + " light boxes" );

		if( !devicesComplete.get() ) {
			logMessage( "INDI devices incomplete: expected at least " + requiredCameras + " cameras, " + requiredFilterWheels
					+ " filter wheels, " + requiredRotators + " rotators, " + requiredCaps + " caps, " + requiredLightBoxes
					+ " light boxes — check hardware/drivers" );
		}
	}

	protected void disconnect() {
		if( this.con != null ) {
			this.unsubscribe();
			try {
				this.con.disconnect();
			} catch (Throwable t) {
				//ignore
			}
			this.con = null;
		}
	}

	protected void unsubscribe() {
		for( Runnable unsub : subscriptions ) {
			try {
				unsub.run();
			}
			catch( Throwable t ) {
				logError( "Failed to unsubscribe", t );
			}
		}
		subscriptions.clear();
		resetValues();

		cameraDevices.clear();
		filterDevices.clear();
		rotatorDevices.clear();
		capDevices.clear();
		lightBoxDevices.clear();
		indiDevices.clear();
	}

	protected void ekosDisconnected() {
		// ekosReady never got reset back to false here before — harmless while the periodic
		// broadcaster only read cached values, but the sequence-queue/mount-coords/FOV refreshes
		// (all guarded by ekosReady) make real D-Bus calls, and those started throwing (and
		// logging) once per second against now-stale device proxies after every Ekos stop.
		ekosReady.set( false );

		// Full disconnect (not just unsubscribe): createDevices() only re-registers the
		// Ekos/INDI signal handlers (ekosStatusChanged, indiStatusChanged, heartbeat,
		// propertyValueChanged) while con == null. A bare unsubscribe() here would leave
		// con set, so those handlers would never come back and ekosStatus would freeze
		// forever on its last value.
		this.disconnect();
	}

	public void stopUsbDevices() {
		final File stopScript = new File( "./KStarsClusterScripts/stopUsb.bsh" );
		if( stopScript.exists() ) {
			try {
				Interpreter i = new Interpreter();
				i.set( "cluster", this );
				i.set( "client", client );

				i.eval( new FileReader( stopScript, StandardCharsets.UTF_8 ) );
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
		return DBusConnectionBuilder
				.forSessionBus()
				.withShared( false )
				.receivingThreadConfig()
					.withSignalThreadCount( 5 )
					.withErrorHandlerThreadCount( 1 )
					.withMethodCallThreadCount( 5 )
					.withMethodReturnThreadCount( 5 )
				.connectionConfig()
				.build();
	}

	/**
	 * Liveness probe over a FRESH, private, throwaway connection. This answers "is
	 * KStars alive" INDEPENDENTLY of the health of our main connection — the night of
	 * 2026-07-21 proved that probing through our own connection cannot distinguish a
	 * frozen KStars from a broken own connection and misdiagnosed for hours.
	 */
	protected boolean probeKStarsAlive() {
		try( var probeCon = DBusConnectionBuilder.forSessionBus().withShared( false ).build() ) {
			final Properties props = probeCon.getRemoteObject( "org.kde.kstars", "/KStars/Ekos", Properties.class );
			props.Get( "org.kde.kstars.Ekos", "ekosStatus" );
			return true;
		}
		catch( Throwable t ) {
			return false;
		}
	}

	/**
	 * Timestamp of the last {@link Ekos.heartbeat} signal received from KStars.
	 * Zero means no heartbeat seen yet (e.g. right after a reconnect). The KStars
	 * Qt event loop fires this every 5 s — absence for {@link #HEARTBEAT_TIMEOUT_MS}
	 * means the loop is frozen even if no synchronous D-Bus call produced a NoReply.
	 */
	private final AtomicLong lastHeartbeat = new AtomicLong(0);
	private static final long HEARTBEAT_TIMEOUT_MS = 15_000;

	private AtomicBoolean opticalTrain;
	public void start() {
		if( kStarsMonitor != null ) {
			if( kStarsMonitor.isAlive() ) {
				return;
			}
		}

		kStarsMonitor = new Thread( () -> {
			long ekosStoppedAt = 0;

			while( true ) { try {
				if( !tryStartKStars() ) {
					disconnect();
					ekosStoppedAt = checkShutdownUsb( ekosStoppedAt );
					//retry in 5 seconds
					sleep( 5000L );
				}
				else {
					if( !checkEkosReady( false ) ) {
						ekosStoppedAt = checkShutdownUsb( ekosStoppedAt );

						Calendar[] range = config.getCivilTwilight();
						if( !config.isNight(range) ) {
							Calendar now = range[2];
							if( getKStarsRuntime() > TimeUnit.HOURS.toSeconds( 5 ) && now.get( Calendar.HOUR_OF_DAY ) >= 15 ) {
								safeStopEkos( "It's day and KStars is running more than 5h" );
							}
						}

						if( !isWeatherSafty() && !manualStartRequested.get() ) {
							logMessageOnce( "Weather conditions are UNSAFE, skip start of ekos");
							sleep( 5000L );
						}
						else {
							logMessage( manualStartRequested.get()
									? "Manual start requested, starting ekos now"
									: "Weather conditions are SAFE, starting ekos now" );
							manualStartRequested.set( false );

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
								if( !checkEkosReady( true ) ) {
									sleep( 2000L );
								}
								else {
									ekosStarted = true;
									break;
								}
							}

							if( !ekosStarted ) {
								logMessage( "Ekos failed to start, stopping ekos and retry later" );
								this.stopKStars();
								sleep( 5000L );
							}
						}
					}
					else {
						ekosStoppedAt = 0;
						lastHeartbeat.set( 0 );

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
		} }, "KStars Monitor Thread" );
		kStarsMonitor.setDaemon( true );
		kStarsMonitor.start();
	}

	private boolean isWeatherSafty() {
		var weatherSafty = weather.checkWeatherStatus( client );
		var newState = weatherSafty ? org.kde.kstars.ekos.Weather.WeatherState.WEATHER_OK : org.kde.kstars.ekos.Weather.WeatherState.WEATHER_ALERT;
		if( this.weatherState.getAndSet( newState ) != newState ) {
			handleSchedulerWeatherStatus( newState );
		}
		return weatherSafty;
	}

	private boolean tryStartKStars() {
		long runtime = getKStarsRuntime();
		if( runtime < 0 ) {
			Calendar[] range = config.getCivilTwilight();
			if( config.isNight(range) || manualStartRequested.get() ) {
				logMessage( "Starting kstars" );
				try {
					// -f forks setsid instead of exec'ing in place, so it can exit right away and
					// let kstars be reparented to init. Without -f, kstars keeps running as a direct
					// child of this JVM (only its session/pgid changes), so anything that kills the
					// JVM's whole process tree (e.g. IntelliJ's "terminate process tree" on Stop)
					// takes kstars down with it.
					var p = Runtime.getRuntime().exec( new String[]{ "setsid", "-f", "nohup", "kstars" } );

					new Thread( () -> {
						try {
							p.getErrorStream().readAllBytes();
						}
						catch( Throwable t ) {
							logError( "Failed to start kstars", t );
						}
					}).start();

					logMessage( "Started kstars" );
					if( !WaitUntil.waitUntil("Starting kstars", 10, () -> getKStarsRuntime() > 0 ) ) {
						logMessage( "Failed to start kstars" );
						return false;
					}
				}
				catch( Throwable tt ) {
					logError("Failed to start kstars", tt);
					return false;
				}
			}
			else {
				logMessageOnce( "It's daytime, wait to start until dusk: " + range[1].getTime() );
				return false;
			}
		}

		for( int i=0; i<10; i++ ) {
			if( probeKStarsAlive() ) {
				if( con == null ) {
					try {
						this.createDevices();
					} catch (Throwable t) {
						logError("Failed to create devices", t);
						return false;
					}
				}
				this.lastHeartbeat.set( 0 );
				return true;
			}
			else {
				if( con != null ) {
					disconnect();
				}
				sleep( 1000 );
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
		Long weatherBadSince = null;

		var validEkosStatus = Set.of(
				Ekos.CommunicationStatus.Pending,
				Ekos.CommunicationStatus.Success
		);
		while( validEkosStatus.contains( ekosStatus.get() ) ) {
			long start = System.currentTimeMillis();

			// Heartbeat watchdog: KStars fires Ekos.heartbeat every 5 s from its Qt main
			// event loop. If the loop is frozen (even without producing a NoReply) we will
			// not see a heartbeat for >15 s. Probe first — a delayed signal delivery is
			// possible under load, so we only escalate when KStars actually fails the probe.
			final long hb = lastHeartbeat.get();
			if( hb > 0 ) {
				final long hbAge = System.currentTimeMillis() - hb;
				if( hbAge > HEARTBEAT_TIMEOUT_MS ) {
					logMessage( "HEARTBEAT WATCHDOG: last heartbeat was " + hbAge + "ms ago — reconnecting" );
					return;
				}
			}

			if( isWeatherSafty() ) {
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
						if( safeStopEkos( "Weather is UNSAFE since " + (badWeatherDuration / 1000 / 60 ) + " Minutes" ) ) {
							return;
						}
					}
					else if( badWeatherDuration >= TimeUnit.MINUTES.toMillis( 1 ) ) {
						if( !automationSuspended.get() ) {
							ensureMountIsParked();
						}
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
			sleep( remaining );
		}
	}

	private boolean canStopEkos(StringBuilder waitToStopReasons, String reason) {
		waitToStopReasons.append( reason ).append( ", check if we can shutdown ekos" );

		boolean canStop = true;

		if( !devicesComplete.get() ) {
			waitToStopReasons.append( "\n\tINDI device enumeration is incomplete, can not verify cap/camera state" );
			canStop = false;
		}
		if( this.automationSuspended.get() ) {
			waitToStopReasons.append( "\n\tAutomation suspended" );
			canStop = false;
		}
		if( this.mountStatus.get() != MountStatus.MOUNT_PARKED ) {
			waitToStopReasons.append( "\n\tMount is not yet parked, wait for parking" );
			canStop = false;
		}
		if( this.captureRunning.containsValue( Boolean.TRUE ) )  {
			waitToStopReasons.append( "\n\tA capture is in progress" );
			canStop = false;
		}

		/*
		var lastCapture = System.currentTimeMillis() - lastCapturedImage.get();
		if( lastCapture < TimeUnit.MINUTES.toMillis( 10 ) ) {
			waitToStopReasons.append( "\n\tLast capture was less than 10 Minutes ago" );
			canStop = false;
		}
		*/

		for( IndiCap cap : capDevices.values() ) {
			if( !cap.isParked() || cap.isBusy() ) {
				if( !cap.isBusy() ) {
					cap.park();
				}
				waitToStopReasons.append( "\n\tCap " + cap.deviceName + " is not yet parked" );
				canStop = false;
			}
		}

		// Only request the camera warm-up once every other criterion is already
		// met — never eagerly on every mount-park/scheduler-abort — so the cooler
		// isn't cycled on/off during a short bad-weather spell. Once we get here we
		// are already committed to stopping, so it's fine to wait past the usual
		// 60 Minutes for the cooler to actually finish warming up.
		if( canStop ) {
			for( IndiCamera camera : cameraDevices.values() ) {
				camera.warm();
				if( camera.isCooling() || camera.isCoolerBusy() ) {
					waitToStopReasons.append( "\n\tCamera " + camera.deviceName + " is still cooling/warming up" );
					canStop = false;
				}
			}
		}
		return canStop;
	}

	/**
	 * Safe shutdown: refuses to do anything if {@link #canStopEkos} says it's not yet safe
	 * (returns false — caller decides whether/when to retry, e.g. by re-checking on its own
	 * next loop tick). Once safe, sets filters to L and takes one reference focus frame per
	 * train, then waits up to 120s for the checklist to hold (parking the mount along the
	 * way) before stopping Ekos/KStars regardless of whether it ever fully settled — this
	 * bounded-then-stop-anyway behavior matches the original bad-weather shutdown exactly,
	 * just shared across every caller instead of duplicated.
	 */
	protected boolean safeStopEkos( String reason ) {
		StringBuilder waitToStopReasons = new StringBuilder();

		if( !canStopEkos( waitToStopReasons, reason ) ) {
			logMessageOnce( waitToStopReasons.toString() );
			return false;
		}

		logMessage( "Shutting down Ekos / KStars (" + reason + ")" );

		try {
			for( IndiFilterWheel filterWheel : filterDevices.values() ) {
				logMessage( "Setting Filter slot to L of " + filterWheel.deviceName );
				filterWheel.setFilterSlot( 1 );
			}

			WaitUntil.waitUntil( "changeFilter", 20,
					() -> filterDevices.values().stream()
							.noneMatch(fw -> fw.getFilterSlotStatus() != IpsState.IPS_OK ) );

			for( String train : this.focusState.keySet() ) {
				logMessage( "Caputure one focus image on train " + train);
				this.focus.methods.capture( train, 0 );
			}

			sleep( 1000L );

			WaitUntil.waitUntil( "captureFinished", 20,
					() -> this.focusState.values().stream().noneMatch( s -> s != FocusState.FOCUS_IDLE ) );

			logMessage( "Caputure one focus image done" );
		}
		catch( Throwable t ) {
			logError( "Failed to go back to L before shutdown", t );
		}

		WaitUntil.waitUntil( "canStop", 120,
				() -> {
					ensureMountIsParked();
					var sb = new StringBuilder();
					try {
						return canStopEkos( sb, reason );
					}
					finally {
						logMessageOnce( sb.toString() );
					}
				} );

		if( !stopEkos() ) {
			stopKStars();
		}
		stopUsbDevices();

		return true;
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
				Calendar[] range = config.getCivilTwilight();
				range[0].add( Calendar.HOUR, 1 );
				if( !config.isNight(range) ) {
					//logMessage( "Do not park, it's day" );
					return false;
				}

				if( automationSuspended.get() ) {
					return false;
				}

				try {
					logMessage( "Parking mount" );
					this.mount.methods.abort();
					this.mount.methods.park();
					this.mountStatus.set( MountStatus.MOUNT_PARKING );
				}
				catch( Throwable t ) {
					logError( "Failed to park mount", t);
				}
				
				return false;
		}
	}

	/**
	 * Slews the mount to a fixed horizontal position by converting it to equatorial
	 * coordinates for the observer's location (from kstarsrc) at the current time,
	 * since Ekos' Mount.slew() only accepts RA/Dec.
	 */
	protected boolean slewAltAz( double altitude, double azimuth ) {
		double[] raDec = Coordinates.altAzToRaDec( altitude, azimuth, config.getLatitude(), config.getLongitude(), Calendar.getInstance() );
		return this.mount.methods.slew( raDec[0], raDec[1] );
	}

	protected final AtomicBoolean ekosReady = new AtomicBoolean(false);

	/**
	 * Set by the "start Ekos/KStars" web action. Bypasses both the twilight gate (in
	 * {@link #tryStartKStars()}) and the weather-safety gate (in {@link #start()}'s loop)
	 * for exactly one start attempt — starting the Ekos *software* doesn't move any
	 * hardware by itself, so overriding those gates on manual request is safe. Cleared
	 * once that one attempt has actually been triggered.
	 */
	protected final AtomicBoolean manualStartRequested = new AtomicBoolean( false );

	/**
	 * Cached instead of calling align.methods.getSolutionResult() / scheduler.read("jsonJobs")
	 * from buildStatusSnapshot() — that method is invoked from the periodic status broadcaster
	 * (its own scheduled thread) AND from Jetty request threads, neither of which is the single
	 * signal-handling thread the rest of this codebase relies on to keep at most one synchronous
	 * D-Bus call in flight at a time. Refreshed only from signal handlers / connect-time bootstrap
	 * (see the Align.newSolution subscription, ekosReady(), and updateSchedulerActiveJob()).
	 */
	protected final AtomicReference<List<Double>> lastAlignSolution = new AtomicReference<>( List.of() );
	protected final AtomicReference<List<SchedulerJob>> allSchedulerJobs = new AtomicReference<>( List.of() );

	protected boolean checkEkosReady( boolean autoConnect ) {
		try {
			ekos.checkAlive();
		} catch (Throwable t) {
			return false;
		}

		switch (ekosStatus.get()) {
			case Error:
			case Idle:
				return false;

			case Success:
				return true;

			case Pending:
			default:
			{
				if (autoConnect) {
					try {
						for (String device : this.indi.methods.getDevices()) {
							String state = this.indi.methods.getPropertyState(device, "CONNECTION");
							String connected = this.indi.methods.getSwitch(device, "CONNECTION", "CONNECT");

							if (!("Ok".equals(state) && "On".equals(connected))) {
								logMessage("The device " + device + " is not connected: " + state + "/" + connected);

								logMessage("Connecting device " + device + " now");
								this.indi.methods.setSwitch(device, "CONNECTION", "CONNECT", "On");
								this.indi.methods.sendProperty(device, "CONNECTION");
								sleep(2000L);
							}
						}
					} catch (Throwable t) {
						logError("Failed to query indi device status", t);
					}
				}

				return false;
			}
		}
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

		//one-off fetch on connect, same as the device loop above — afterwards this is only
		//ever refreshed from the Align.newSolution signal, never polled (see lastAlignSolution)
		try {
			lastAlignSolution.set( this.align.methods.getSolutionResult() );
		}
		catch( Throwable t ) {
			logError( "Failed to read initial align solution", t );
		}

		//initial job determination: when we connect while a job is ALREADY executing,
		//no scheduler newLog will fire until the next scheduler action — fetch it once
		updateSchedulerActiveJob( null );

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

				if( state == AlignState.ALIGN_COMPLETE ) {
					// refresh the cache buildStatusSnapshot() reads — this runs on the signal
					// thread, same safe context as everything else in this handler
					try {
						lastAlignSolution.set( this.align.methods.getSolutionResult() );
					}
					catch( Throwable t ) {
						logError( "Failed to read align solution", t );
					}
				}
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
				cap.unpark();
            }
            catch( Throwable t ) {
                logError( "Failed to request unpark cap " + cap.deviceName, t);
            }
        }
    }

    protected void parkCap() {
        for( IndiCap cap : capDevices.values() ) {
            try {
				cap.park();
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
				return safeStopEkos( "Manual stop requested via web UI" ) ? "OK" : "Not safe to stop yet, see log";
		} );

		actions.put( "startEkos", (parts, req, resp ) -> {
				manualStartRequested.set( true );
				return "OK";
		} );

		actions.put( "preCool", (parts, req, resp ) -> {
			preCool();
			return "OK";
		} );


		actions.put( "warmCameras", (parts, req, resp ) -> {
			warmCameras();
			return "OK";
		} );


		actions.put( "scheduler", ( parts, req, resp ) -> {
			if( parts.length < 2 ) {
				return "usage: scheduler/<start|stop>";
			}
			switch( parts[1] ) {
				case "start":
					scheduler.methods.start();
					return "OK";
				case "stop":
					scheduler.methods.stop();
					return "OK";
				default:
					return "unknown scheduler action " + parts[1];
			}
		} );

		actions.put( "focus", ( parts, req, resp ) -> {
			if( parts.length < 3 ) {
				return "usage: focus/<run|abort>/<train>";
			}
			String train = parts[2];
			switch( parts[1] ) {
				case "run":
					focus.methods.abort( train );
					focus.methods.start( train );
					return "OK";
				case "abort":
					focus.methods.abort( train );
					return "OK";
				default:
					return "unknown focus action " + parts[1];
			}
		} );

		actions.put( "capture", ( parts, req, resp ) -> {
			if( parts.length < 3 ) {
				return "usage: capture/abort/<train>";
			}
			String train = parts[2];
			switch( parts[1] ) {
				case "abort":
					capture.methods.abort( train );
					return "OK";
				default:
					return "unknown capture action " + parts[1];
			}
		} );

		actions.put( "cap", ( parts, req, resp ) -> {
			if( parts.length < 2 ) {
				return "usage: cap/<open|close>";
			}
			switch( parts[1] ) {
				case "open":
					unparkCap();
					return "OK";
				case "close":
					parkCap();
					return "OK";
				default:
					return "unknown cap action " + parts[1];
			}
		} );

		actions.put( "light", ( parts, req, resp ) -> {
			if( parts.length < 2 ) {
				return "usage: light/<on|off>";
			}
			switch( parts[1] ) {
				case "on":
					for( IndiLightBox light : lightBoxDevices.values() ) {
						light.lightOn();
					}
					return "OK";
				case "off":
					for( IndiLightBox light : lightBoxDevices.values() ) {
						light.lightOff();
					}
					return "OK";
				default:
					return "unknown light action " + parts[1];
			}
		} );

		actions.put( "images", ( parts, req, resp ) -> {
			if( parts.length < 2 ) {
				return "usage: images/<thumb|autostretch>";
			}
			switch( parts[1] ) {
				case "thumb": {
					File fitsFile = resolveFileParam( req, resp );
					if( fitsFile == null ) {
						return null;
					}

					int maxDim = clamp( parseIntParam( req, "maxDim", 320 ), 32, 8000 );
					double shadows = clamp( parseDoubleParam( req, "shadows", 0.0 ), 0, 1 );
					double midtones = clamp( parseDoubleParam( req, "midtones", 0.5 ), 0, 1 );
					double highlights = clamp( parseDoubleParam( req, "highlights", 1.0 ), 0, 1 );

					byte[] jpeg = renderThumbnailCached( fitsFile, maxDim, shadows, midtones, highlights );
					resp.setContentType( "image/jpeg" );
					resp.setContentLength( jpeg.length );
					resp.getOutputStream().write( jpeg );
					resp.getOutputStream().flush();
					return null;
				}

				case "autostretch": {
					File fitsFile = resolveFileParam( req, resp );
					if( fitsFile == null ) {
						return null;
					}

					boolean strong = "true".equals( req.getParameter( "strong" ) );
					double[] shmh = FitsThumbnail.computeAutoStretch( fitsFile, strong );

					Map<String,Object> res = new LinkedHashMap<>();
					res.put( "shadows", shmh[0] );
					res.put( "midtones", shmh[1] );
					res.put( "highlights", shmh[2] );
					return res;
				}

				default:
					return "unknown images action " + parts[1];
			}
		} );

		actions.put( "flats", ( parts, req, resp ) -> {
			if( mountStatus.get() != MountStatus.MOUNT_PARKED ) {
				return "mount is not parked";
			}
			else if( parts.length < 2 ) {
				return "no rotations given";
			}
			if( !automationSuspended.compareAndSet( false, true ) ) {
				return "suspended";
			}
			try {
				mount.methods.unpark();

				slewAltAz( 90, 90 );

				WaitUntil.waitUntil( "mount tracking", TimeUnit.MINUTES.toSeconds( 2 ),
						() -> mountStatus.get() == MountStatus.MOUNT_TRACKING );

				mount.methods.abort();

				WaitUntil.waitUntil( "mount standing still", TimeUnit.MINUTES.toSeconds( 2 ),
						() -> mountStatus.get() == MountStatus.MOUNT_IDLE );

				var angles = Arrays.stream(parts[1].split(",")).map(p -> Double.valueOf(p.trim())).toArray(Double[]::new);

				Map<String,String> trains = Map.of(
						PRIMARY_TRAIN, "/home/philip/ASI2600/15_lrgb_HaOiiiSii_flat_G100_O50_B_nocal.esq"
						//SECONDARY_TRAIN, "/home/philip/ASI2600/15_lrgb_HaOiiiSii_flat_G100_O50_A_nocal.esq"
				);

				List<Integer> trainIds = trains.keySet().stream().map( train -> capture.methods.findCameraPosition( train, true ) ).toList();

				for( var train : trains.keySet() ) {
					capture.methods.abort(train);
				}

				var finished = new HashMap<String, Boolean>();

				var unsub = this.capture.addNewStatusHandler(Capture.newStatus.class, status -> {
					//System.out.println(status.train + ": " + status.getStatus());
					if (status.getStatus() == CaptureStatus.CAPTURE_COMPLETE) {
						finished.put(status.train, true);
					} else {
						finished.put(status.train, false);
					}
				});

				for( var light : this.lightBoxDevices.values() ) {
					light.lightOn();
				}

				try {
					for (var pos : angles) {
						logMessage("Moving rotator to postion " + pos);
						WaitUntil.waitUntil(
								"Rotators Idle",
								120,
								() -> rotatorDevices.values().stream().allMatch(r -> List.of(IpsState.IPS_IDLE, IpsState.IPS_OK).contains(r.getRotatorPositionStatus()) )
						);

						for (var r : rotatorDevices.values()) {
							r.setRotatorPosition(pos);
						}

						WaitUntil.waitUntil(
								"Rotators Idle",
								120,
								() -> rotatorDevices.values().stream().allMatch(r -> r.getRotatorPositionStatus() == IpsState.IPS_OK)
						);

						logMessage("Moved rotator to postion " + pos);

						for( var train : trains.entrySet() ) {
							capture.methods.loadSequenceQueue(train.getValue(), train.getKey(), true, "");
						}

						finished.clear();

						for( var train : trains.keySet() ) {
							capture.methods.start(train);
						}

						WaitUntil.waitUntil(
								"Capture Finished",
								TimeUnit.MINUTES.toSeconds(30),
								() -> (finished.size() == trains.size() && finished.values().stream().allMatch(b -> b.booleanValue()))
						);

						logMessage("all captures finished");
					}

					for( var light : this.lightBoxDevices.values() ) {
						light.lightOff();
					}

					mount.methods.park();
					WaitUntil.waitUntil( "mount tracking", TimeUnit.MINUTES.toSeconds( 2 ),
							() -> mountStatus.get() == MountStatus.MOUNT_PARKED );
				} finally {
					unsub.run();
				}

				return trainIds.toString();
			}
			finally {
				automationSuspended.set( false );
			}
		} );
	}

	public Map<String,Object> statusAction( String[] parts, HttpServletRequest req, HttpServletResponse resp) throws IOException {
		if( ekosReady.get() ) {
			String capPark = req.getParameter( "capPark" );
			if( "park".equals( capPark ) ) {
				for( IndiCap capDevice : capDevices.values() ) {
					capDevice.park();
				}
			}
			else if( "unpark".equals( capPark ) ) {
				for( IndiCap capDevice : capDevices.values() ) {
					capDevice.unpark();
				}
			}
		}

		return buildStatusSnapshot();
	}

	/** Same payload as {@link #statusAction}, without the req-bound capPark side effect — reused by the periodic status WebSocket broadcast. */
	public Map<String,Object> buildStatusSnapshot() {
		Map<String,Object> res = new LinkedHashMap<>();

		// Always available, regardless of connection state — lets the UI tell "KStars
		// process isn't running" apart from "KStars is running but Ekos isn't ready yet".
		res.put( "kstarsRunning", getKStarsRuntime() >= 0 );
		res.put( "ekosReady", ekosReady.get() );
		res.put( "ekosStatus", ekosStatus.get() );
		res.put( "manualStartRequested", manualStartRequested.get() );
		res.put( "automationSuspended", this.automationSuspended.get() );

		if( !ekosReady.get() ) {
			return res;
		}

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

			res.put( capDevice.deviceName, cap );
		}

		// A physical flat panel (e.g. Gemini Flat-Wizard) is often ONE INDI device exposing
		// both the dust cap and the light — merge into the existing entry instead of
		// overwriting it when the device name collides with one from the cap loop above.
		for( IndiLightBox lightBox : lightBoxDevices.values() ) {
			@SuppressWarnings("unchecked")
			Map<String,Object> device = (Map<String,Object>) res.computeIfAbsent( lightBox.deviceName, n -> new LinkedHashMap<>() );
			device.put( "name", lightBox.deviceName );
			device.put( "lightOn", lightBox.isLightOn() );
		}

		fillStatus( res );

		res.put( "alignment", fillAlignment( new HashMap<>(), lastAlignSolution.get() ) );
		res.put( "jobs", allSchedulerJobs.get() );

		// Folded into the status push instead of separate polling loops for the HFR chart
		// and image strip — one WebSocket, not three independently-polled REST endpoints.
		res.put( "hfrHistory", hfrHistory );

		Map<String, List<Map<String,Object>>> images = new LinkedHashMap<>();
		for( String train : capturedImages.keySet() ) {
			images.put( train, listRecentImages( train ) );
		}
		res.put( "images", images );

		res.put( "sequenceQueue", sequenceQueueStatus );

		double[] coords = mountCoords.get();
		if( coords != null ) {
			Map<String,Object> mountCoordsMap = new LinkedHashMap<>();
			mountCoordsMap.put( "ra", coords[0] );
			mountCoordsMap.put( "dec", coords[1] );
			res.put( "mountCoords", mountCoordsMap );
		}

		double[] fovValues = fov.get();
		if( fovValues != null ) {
			Map<String,Object> fovMap = new LinkedHashMap<>();
			fovMap.put( "widthArcmin", fovValues[0] );
			fovMap.put( "heightArcmin", fovValues[1] );
			res.put( "fov", fovMap );
		}

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
			double pa = normalizePa( alignSolution.get( 0 ) );
								
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


	/**
	 * Raw D-Bus fetch of every job in the loaded schedule (not just the currently executing
	 * one) — only ever called from {@link #updateSchedulerActiveJob}, i.e. from the same
	 * signal-handler-safe contexts that already refresh {@link #schedulerActiveJob}. Cached
	 * into {@link #allSchedulerJobs}; buildStatusSnapshot() reads the cache, never this.
	 */
	private List<SchedulerJob> fetchAllSchedulerJobs() {
		try {
			final String jsonJobs = (String) this.scheduler.read( "jsonJobs" );
			if( jsonJobs == null || jsonJobs.isBlank() ) {
				return List.of();
			}
			SchedulerJob[] jobs = new GsonBuilder().create().fromJson( jsonJobs, SchedulerJob[].class );
			return jobs == null ? List.of() : Arrays.asList( jobs );
		}
		catch( Throwable t ) {
			logError( "Failed to read scheduler jobs", t );
			return List.of();
		}
	}

	/**
	 * Tracks the scheduler's active job via the currentJobJson property (one read per
	 * poll — it carries name, state, stage and target RA/DEC in one go). Unlike the
	 * old name-based tracking this also sees state changes WITHIN the same job, i.e.
	 * JOB_SCHEDULED (waiting for startup time) -> JOB_BUSY (actually executing).
	 */
	protected void updateSchedulerActiveJob( String jobName ) {
		try {
			logDebug( "Updating scheduler active job" );
			allSchedulerJobs.set( fetchAllSchedulerJobs() );

			final String currentJobJson = (String) this.scheduler.read( "currentJobJson" );

			SchedulerJob job = null;
			if( currentJobJson != null && !currentJobJson.isBlank() ) {
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
			else if( prev == null || !prev.name.equals( job.name ) ) {
				logMessage( "Scheduler job has changed from " + ( prev == null ? "null" : prev.name ) + " to " + job.name + " (" + job.getState() + ")" );

				preCool();

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
				preCool();

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

	private void preCool() {
		for( IndiCamera camera : cameraDevices.values() ) {
			camera.preCool();
		}
	}

	private void warmCameras() {
		for( IndiCamera camera : cameraDevices.values() ) {
			camera.warm();
		}
	}

	public double calculateFRatio() {
		try {
			List<Double> info = this.align.methods.telescopeInfo();
			return ( info.get(0) / info.get( 1 ) ) * info.get( 2 );
		}
		catch( Throwable t ) {
			logError( "Failed to get telescope info", t );
			return 0;
		}
	}

	protected void loadSchedule( File f ) {

		if( f.exists() ) {
			SchedulerState status = schedulerState.get();
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
        double clientPa = normalizePa( coords.get( 0 ) );

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

			var seconds = out.toString().trim().split("\n")[0].trim();
			if( seconds.isBlank() ) {
				return -1;
			}
			else {
				return Integer.parseInt(seconds);
			}
		}
		catch( Throwable t ) {
			logError( "Failed to get KStars pid", t );
			return -1;
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

	protected void sleep(long time) {
		try {
			Thread.sleep( time );
		}
		catch( Throwable t ) {
			//ignore
		}
	}

	/** Recent captures for one train, newest first — sourced from Capture.captureComplete, not filesystem scanning. */
	public List<Map<String,Object>> listRecentImages( String train ) {
		Deque<CapturedImage> history = capturedImages.getOrDefault( train, new ConcurrentLinkedDeque<>() );

		List<Map<String,Object>> res = new ArrayList<>();
		for( CapturedImage img : history ) {
			Map<String,Object> entry = new LinkedHashMap<>();
			entry.put( "ts", img.ts );
			entry.put( "filename", img.filename );
			entry.put( "filter", img.filter );
			entry.put( "exposure", img.exposure );
			entry.put( "hfr", img.hfr );
			entry.put( "eccentricity", img.eccentricity );
			entry.put( "median", img.median );
			entry.put( "starCount", img.starCount );
			entry.put( "width", img.width );
			entry.put( "height", img.height );
			entry.put( "type", img.type );
			res.add( entry );
		}
		Collections.reverse( res );
		return res;
	}

	private static int parseIntParam( HttpServletRequest req, String name, int fallback ) {
		try {
			String v = req.getParameter( name );
			return v == null ? fallback : Integer.parseInt( v );
		}
		catch( Throwable t ) {
			return fallback;
		}
	}

	private static double parseDoubleParam( HttpServletRequest req, String name, double fallback ) {
		try {
			String v = req.getParameter( name );
			return v == null ? fallback : Double.parseDouble( v );
		}
		catch( Throwable t ) {
			return fallback;
		}
	}

	private static int clamp( int v, int lo, int hi ) {
		return Math.max( lo, Math.min( hi, v ) );
	}

	private static double clamp( double v, double lo, double hi ) {
		return Math.max( lo, Math.min( hi, v ) );
	}

	/** Shared by the thumb/autostretch sub-actions: validates the "file" param, 404s on the response if it's unusable. */
	private File resolveFileParam( HttpServletRequest req, HttpServletResponse resp ) {
		String file = req.getParameter( "file" );
		if( file == null || file.isBlank() ) {
			resp.setStatus( HttpServletResponse.SC_BAD_REQUEST );
			return null;
		}
		File fitsFile = resolveKnownCapturedFile( file );
		if( fitsFile == null ) {
			resp.setStatus( HttpServletResponse.SC_NOT_FOUND );
			return null;
		}
		return fitsFile;
	}

	/**
	 * Refuses to render anything that wasn't actually reported by a captureComplete signal —
	 * the "file" query param on the thumb action is client-supplied, so this is the only thing
	 * standing between it and an arbitrary local file read.
	 */
	public File resolveKnownCapturedFile( String filename ) {
		for( Deque<CapturedImage> history : capturedImages.values() ) {
			for( CapturedImage img : history ) {
				if( filename.equals( img.filename ) ) {
					File f = new File( filename );
					return f.isFile() ? f : null;
				}
			}
		}
		return null;
	}

	private static final File THUMBNAIL_CACHE_DIR = new File( "./thumb-cache" );

	/** Serves a cached render if present, otherwise renders and caches one keyed by path+mtime+size+stretch. */
	public byte[] renderThumbnailCached( File fitsFile, int maxDim, double shadows, double midtones, double highlights ) throws Exception {
		THUMBNAIL_CACHE_DIR.mkdirs();

		String cacheKey = Integer.toHexString( fitsFile.getAbsolutePath().hashCode() ) + "_" + fitsFile.lastModified()
				+ "_" + maxDim + "_" + shadows + "_" + midtones + "_" + highlights + ".jpg";
		File cacheFile = new File( THUMBNAIL_CACHE_DIR, cacheKey );

		if( cacheFile.isFile() ) {
			return Files.readAllBytes( cacheFile.toPath() );
		}

		byte[] jpeg = FitsThumbnail.render( fitsFile, maxDim, shadows, midtones, highlights );

		try {
			Files.write( cacheFile.toPath(), jpeg );
		}
		catch( Throwable t ) {
			logError( "Failed to cache thumbnail for " + fitsFile, t );
		}

		return jpeg;
	}
}
