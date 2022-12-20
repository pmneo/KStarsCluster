package de.pmneo.kstars;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.exceptions.DBusException;
import org.kde.kstars.Ekos;
import org.kde.kstars.INDI;
import org.kde.kstars.Ekos.CommunicationStatus;
import org.kde.kstars.INDI.DriverInterface;
import org.kde.kstars.INDI.IpsState;
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
import org.kde.kstars.ekos.Weather;
import org.kde.kstars.ekos.Weather.WeatherState;

import de.pmneo.kstars.web.CommandServlet.Action;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;


public abstract class KStarsCluster {
	protected DBusConnection con;

	public final Device<Ekos> ekos;
	public final Device<Align> align;
	public final Device<Focus> focus;
	public final Device<Guide> guide;
	public final Device<Capture> capture;
	public final Device<Mount> mount;
	public final Device<Scheduler> scheduler;
	public final Device<Weather> weather;
	public final Device<INDI> indi;
	
	protected final List< Device<?> > mandatoryDevices = new ArrayList<Device<?>>();
	protected final List< Device<?> > devices = new ArrayList<Device<?>>();
	
	
	protected final AtomicBoolean kStarsConnected = new AtomicBoolean(false);

	public final AtomicBoolean captureRunning = new AtomicBoolean( false );
    public final AtomicBoolean capturePaused = new AtomicBoolean( false );
    public final AtomicBoolean focusRunning = new AtomicBoolean( false );
	public final AtomicBoolean autoFocusDone = new AtomicBoolean( false );
	public final AtomicReference<AlignState> currentAlignStatus = new AtomicReference<AlignState>( AlignState.ALIGN_IDLE );
    public final AtomicReference<MountStatus> currentMountStatus = new AtomicReference<MountStatus>( MountStatus.MOUNT_IDLE );
	public final AtomicInteger activeCaptureJob = new AtomicInteger( 0 );
    
	protected void resetValues() {
        
		autoFocusDone.set( false );
		captureRunning.set( false );
		capturePaused.set( false );
		focusRunning.set( false );
	}
		

    private double preCoolTemp = -15;
    public void setPreCoolTemp(double preCoolTemp) {
		if( this.cameraDevice.get() != null ) {
			this.cameraDevice.get().setPreCoolTemp(preCoolTemp);
		}
        this.preCoolTemp = preCoolTemp;
    }
    public double getPreCoolTemp() {
        return preCoolTemp;
    }

	public KStarsCluster( ) throws DBusException {
		/* Get a connection to the session bus so we can get data */
		con = DBusConnection.getConnection( DBusConnection.DBusBusType.SESSION );
		
		this.ekos = new Device<>( con, "org.kde.kstars", "/KStars/Ekos", Ekos.class );
		this.mandatoryDevices.add( this.ekos );
		this.ekos.addSigHandler( Ekos.ekosStatusChanged.class, status -> {
			this.handleEkosStatus( status.getStatus() );
		} );

		this.indi = new Device<>( con, "org.kde.kstars", "/KStars/INDI", INDI.class );
		this.devices.add( this.indi );

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

		System.out.println( "Detecting INDI Weather Device" );
		Device<Weather> weather = null;
		for( int i=0; i<10; i++ ) {
			weather = new Device<>( con, "org.kde.kstars", "/KStars/INDI/Weather/" + i, Weather.class );
			try {
				String name = (String) weather.read( "name" );
				System.out.println( "Detected Weather device: " + name + " at " + "/KStars/INDI/Weather/" + i );
				break;
			}
			catch( Throwable t ) {
				//IGNORE
				weather = null;
			}
		}
		this.weather = weather;
		if( this.weather != null ) {
			this.devices.add( this.weather );
			this.weather.addNewStatusHandler( Weather.newStatus.class, status -> {
				this.handleSchedulerWeatherStatus( status.getStatus() );
			} );
		}
	}

	protected void kStarsConnected() {
		this.getCameraDevice();
		this.getFocusDevice();
		this.getRotatorDevice();

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
	
	public void logMessage( Object message ) {
		SimpleLogger.getLogger().logMessage( message );
	}
	
	public void logError( Object message, Throwable t ) {
        SimpleLogger.getLogger().logError( message, t );
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

			while( true ) { try {
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

				while( checkKStarsReady() == false ) {
					sleep( 1000L );
				}
			
				logMessage( "Ekos is ready" );
				
				ekosReady();

				kStarsConnected.set(true);
				kStarsConnected();
				
				while( checkKStarsReady() ) {
					sleep( 5000L );
				}
				
				logMessage( "Kstars is has stopped, waiting to become ready again" );
				
				kStarsConnected.set(false);
				kStarsDisconnected();
			}
			catch( Throwable t ) {
				logError( "Unhandled error in KStars Monitor loop", t );
			} }
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

	protected final AtomicBoolean kStarsReady = new AtomicBoolean(false);

	protected boolean checkKStarsReady() {
		
		for( Device<?> d : mandatoryDevices ) {
			try {
				d.readAll();
			}
			catch( Throwable t ) {
				kStarsReady.set( false );
				return false;
			}
		}
		kStarsReady.set( true );
		return true;
	}

	protected boolean isKStarsReady() {
		return kStarsReady.get();
	}

	protected void ekosReady() {

	}
	
	protected void handleEkosStatus( CommunicationStatus state ) {
		//logMessage( "handleGuideStatus " + state );
	}

	protected void handleGuideStatus( GuideStatus state ) {
		//logMessage( "handleGuideStatus " + state );
	}
	
	protected void handleMountStatus( MountStatus state ) {
        currentMountStatus.set( state );
	}
	protected void handleMountParkStatus( ParkStatus state ) {
		//logMessage( "handleMountParkStatus " + state );
	}
	protected void handleMeridianFlipStatus( MeridianFlipStatus state ) {
		//logMessage( "handleMeridianFlipStatus " + state );
	}
	
	private final AtomicInteger alignProgressCounter = new AtomicInteger(0);
	protected void handleAlignStatus( AlignState state ) {
		//logMessage( "handleAlignStatus " + state );
		if( state == null ) {
			state = AlignState.ALIGN_IDLE;
		}

		switch( state ) {
			case ALIGN_SYNCING:
			break;
			case ALIGN_PROGRESS:
			break;
			
			case ALIGN_SLEWING:
				if( alignProgressCounter.incrementAndGet() > 5 ) {
					logMessage( "Resetting mount model" );
					this.mount.methods.resetModel();
					alignProgressCounter.set(0);
				}
			break;

			case ALIGN_ABORTED:
			case ALIGN_COMPLETE:
			case ALIGN_FAILED:
			alignProgressCounter.set( 0 );
			case ALIGN_IDLE:
			case ALIGN_ROTATING:
			case ALIGN_SUSPENDED:
				break;
			default:
				break;
			
		}
	}

	private final AtomicReference< IndiRotator > rotatorDevice = new AtomicReference<>();
	public IndiRotator getRotatorDevice() {
		if( rotatorDevice.get() == null ) {
			String foundRotator = IndiRotator.findFirstDevice(indi, DriverInterface.ROTATOR_INTERFACE);
			rotatorDevice.compareAndSet(null, new IndiRotator(foundRotator, indi) );
			rotatorDevice.get().start();
		}
		return rotatorDevice.get();
	}
	
	private final AtomicReference<IndiFocuser> focusDevice = new AtomicReference<>();
	public IndiFocuser getFocusDevice() {
		if( focusDevice.get() == null ) {
			String foundFocuser = (String) this.focus.read( "focuser" );
			focusDevice.compareAndSet(null, new IndiFocuser(foundFocuser, indi) );
			focusDevice.get().start();
		}
		return focusDevice.get();
	}

	private final AtomicReference<IndiCamera> cameraDevice = new AtomicReference<>();
	public IndiCamera getCameraDevice() {
		if( cameraDevice.get() == null ) {
			String foundCamera = (String) this.capture.read( "camera" );
			cameraDevice.compareAndSet(null, new IndiCamera(foundCamera, indi) );
			cameraDevice.get().setPreCoolTemp( getPreCoolTemp() );
			cameraDevice.get().start();
		}
		return cameraDevice.get();
	}

	private final AtomicReference<IndiFilterWheel> filterDevice = new AtomicReference<>();
	public IndiFilterWheel getFilterDevice() {
		if( filterDevice.get() == null ) {
			String foundFilterWheel = (String) this.capture.read( "filterWheel" );
			filterDevice.compareAndSet(null, new IndiFilterWheel(foundFilterWheel, indi) );
			filterDevice.get().start();
		}
		return filterDevice.get();
	}

	protected final AtomicReference<Double> lastFocusPos = new AtomicReference<>(null);

	protected void handleCaptureStatus( CaptureStatus state ) {
		//logMessage( "handleCaptureStatus " + state );

		if( CaptureStatus.CAPTURE_CAPTURING == state || lastFocusPos.get() == null ) {
			double focusPos = getFocusDevice().getFocusPosition();
			logMessage( "Storing last focus pos ("+state+"): " + focusPos );
			lastFocusPos.set(focusPos);
		}

		switch (state) {
        
            case CAPTURE_CAPTURING:
                captureRunning.set( true );
                capturePaused.set( false );

				final int jobId = this.capture.methods.getActiveJobID();
                activeCaptureJob.set( jobId );
            
			break;
            case CAPTURE_PROGRESS:
                //no need to handle
            break;
            
            case CAPTURE_IMAGE_RECEIVED:
            break;
            
            case CAPTURE_ABORTED:
                capturePaused.set( false );
                if( captureRunning.getAndSet( false ) ) {
                    logMessage( "Capture " + activeCaptureJob.get() + " was aborted");
                }
            break;
            
            case CAPTURE_COMPLETE:
            case CAPTURE_SUSPENDED:
                captureRunning.set( false );
                capturePaused.set( false );
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
	protected void handleFocusStatus( FocusState state ) {
		switch( state ) {
            case FOCUS_COMPLETE:
                autoFocusDone.set( true );
                focusRunning.set( false );
            break;
            
            case FOCUS_ABORTED:
            case FOCUS_FAILED:
                autoFocusDone.set( false );
                focusRunning.set( false );
            break;
            
            case FOCUS_IDLE:
                focusRunning.set( false );
            break;
            
            case FOCUS_FRAMING:
            case FOCUS_WAITING:
            case FOCUS_CHANGING_FILTER:
            case FOCUS_PROGRESS:
                focusRunning.set( true );
            break;
        }

		try {
			switch( state ) {
				case FOCUS_ABORTED:
					Double lastPos = lastFocusPos.get();
					if( lastPos != null ) {
						logMessage( "Restoring focuser position to " + lastPos );
						getFocusDevice().setFocusPosition( lastPos.doubleValue() );
					}
					break;
				case FOCUS_CHANGING_FILTER:
					break;
				case FOCUS_FAILED:
					break;
				case FOCUS_FRAMING:
					break;
				case FOCUS_IDLE:
				case FOCUS_COMPLETE:
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

	private boolean disableCameraWarming = true;

	private final Object[] checkCameraCoolingStates = new Object[2];
    protected void checkCameraCooling( SchedulerState schedulerStatus, MountStatus mountStatus ) {

		if( disableCameraWarming ) {
			return;
		}

		synchronized( checkCameraCoolingStates ) {
			if( checkCameraCoolingStates[0] == schedulerStatus && checkCameraCoolingStates[1] == mountStatus ) {
				return;
			}

			checkCameraCoolingStates[0] = schedulerStatus;
			checkCameraCoolingStates[1] = mountStatus;
		}

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


	public int runAutoFocus() {
		if( focusRunning.get() == false ) {
			this.focus.methods.start();
		}

		final WaitUntil maxWait = new WaitUntil( 5, "Focusing" );

		while( !focusRunning.get() && maxWait.check() ) {
			try { Thread.sleep( 10 ); } catch( Throwable t ) {};
		}

		logMessage( "Focus process has started" );

		maxWait.reset( 300 );

		while( focusRunning.get() && maxWait.check() ) {
			try { Thread.sleep( 10 ); } catch( Throwable t ) {};
		}

		logMessage( "Focus process has finished" );

		double pos = this.getFocusDevice().getFocusPosition();
		return (int) pos;
		
	}

	public abstract void listen();


	public void addActions( Map<String, Action> actions ) {
		actions.put( "calibrateFilters", this::calibrateFiltersAction );
        actions.put( "status", this::statusAction );
	}

	public Object statusAction( String[] parts, HttpServletRequest req, HttpServletResponse resp) throws IOException {
		Map<String,Object> res = new HashMap<>();
        
		res.put( "cameraDevice", this.getCameraDevice().deviceName );

		List<String> filters = this.getFilterDevice().getFilters() ;
		
		res.put( "filters", filters );
		res.put( "currentFilter", filters.get( this.getFilterDevice().getFilterSlot() - 1 ) );
		res.put( "focusPosition", this.getFocusDevice().getFocusPosition() );
		res.put( "rotatorAngle", this.getRotatorDevice().getRotatorPosition() );


		res.put( "captureRunning", captureRunning.get() );
		res.put( "capturePaused", capturePaused.get() );
		res.put( "focusRunning", focusRunning.get() );
		res.put( "autoFocusDone", autoFocusDone.get() );

		res.put( "currentAlignStatus", currentAlignStatus.get() );
		res.put( "currentMountStatus", currentMountStatus.get() );

		res.put( "calibrateFilterInProgress", calibrateFilterInProgress.get() );

		if( captureRunning.get() ) {
			int jobId = activeCaptureJob.get();

			double duration = this.capture.methods.getJobExposureDuration( jobId );
			double timeLeft = this.capture.methods.getJobExposureProgress( jobId );
			
			if( timeLeft == 0 ) {
				timeLeft = duration;
			}
			
			double exposure = duration - timeLeft;
			
			int imageCount = this.capture.methods.getJobImageCount( jobId );
			int imageProgress = this.capture.methods.getJobImageProgress( jobId );
			
			Map<String,Object> capture = new HashMap<>();
			capture.put( "jobId", jobId );
			capture.put( "exposure", exposure );
			capture.put( "duration", duration );
			capture.put( "imageProgress", imageProgress );
			capture.put( "imageCount", imageCount );

			res.put( "capture", capture ); 
		}

		List<Double> serverSolution = this.align.methods.getSolutionResult();
		
		res.put( "serverSolution", serverSolution );

        double pa = serverSolution.get( 0 ).doubleValue();
                            
		pa = Math.round( pa * 100.0 ) / 100.0;
		while( pa < 0.0 ) {
			pa += 180.0;
		}
		while( pa >= 180.0 ) {
			pa -= 180.0;
		}
		pa = Math.round( pa * 100.0 ) / 100.0;

		res.put( "pa", pa );
		
		double ra = serverSolution.get(1) / 15.0;
		double dec = serverSolution.get(2);
        res.put( "ra", ra );
		res.put( "dec", dec );
		
		return res;
	}
    

	private AtomicBoolean calibrateFilterInProgress = new AtomicBoolean( false );
    public Object calibrateFiltersAction( String[] parts, HttpServletRequest req, HttpServletResponse resp) throws IOException {
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

                    StringBuilder result = new StringBuilder( );


                    WaitUntil maxWait = new WaitUntil( 20, "changeFilter" );

                    //skip first filter
                    for( int i=1; i<=filters.size(); i++ ) {
                        if( i == n  ) {
                            continue;
                        }

                        logger.logMessage( "Starting focus procedure of filter " + filters.get(n-1) + " as reference" );
                        
                        maxWait.reset();
                        this.getFilterDevice().setFilterSlot( n ); //switch to first filter as reference
                        while( this.getFilterDevice().getFilterSlotStatus() != IpsState.IPS_OK && maxWait.check() );

                        int refPos = this.runAutoFocus();
                        logger.logMessage( "Reference Position: " + refPos );

                        logger.logMessage( "Starting focus procedure of filter " + filters.get(i-1) + " for calibration" );

                        maxWait.reset();
                        this.getFilterDevice().setFilterSlot( i ); //switch to filter for calibration
                        while( this.getFilterDevice().getFilterSlotStatus() != IpsState.IPS_OK && maxWait.check() );

                        int calPos = this.runAutoFocus();

                        int offset = calPos - refPos;

                        result.append( filters.get(i-1) ).append( ": " ).append( offset ).append( "\n" );

                        logger.logMessage( "Found solution for " + filters.get(i-1) + ": " + refPos + " > " + calPos + " = " + offset );

                        logger.logMessage( "Current soltions: " + result.toString() );
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
}
