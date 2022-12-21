package de.pmneo.kstars;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
import org.kde.kstars.ekos.Mount;
import org.kde.kstars.ekos.Mount.MountStatus;
import org.kde.kstars.ekos.Scheduler;
import org.kde.kstars.ekos.Scheduler.SchedulerState;

import bsh.Interpreter;

import org.kde.kstars.ekos.Weather;

import de.pmneo.kstars.web.CommandServlet.Action;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;


public abstract class KStarsCluster extends KStarsState {
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
	
	protected final List< Device<?> > mandatoryDevices = new ArrayList<Device<?>>();
	protected final List< Device<?> > devices = new ArrayList<Device<?>>();
	
	protected final AtomicBoolean kStarsConnected = new AtomicBoolean(false);

	public final AtomicInteger activeCaptureJob = new AtomicInteger( 0 );
    
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

	public KStarsCluster( String logPrefix ) throws DBusException {
		super( logPrefix );

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

	private final AtomicInteger alignProgressCounter = new AtomicInteger(0);
	public AlignState handleAlignStatus( AlignState state ) {
		state = super.handleAlignStatus(state);

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

	public CaptureStatus handleCaptureStatus( CaptureStatus state ) {
		boolean captureWasRunning = captureRunning.get();

		state = super.handleCaptureStatus(state);

		if( CaptureStatus.CAPTURE_CAPTURING == state || lastFocusPos.get() == null ) {
			double focusPos = getFocusDevice().getFocusPosition();
			if( Double.valueOf( focusPos ).equals( lastFocusPos.getAndSet(focusPos) ) == false ) {
				logMessage( "Storing last focus pos ("+state+"): " + focusPos );
			}
		}

		if( captureWasRunning == false && captureRunning.get() ) {
			final int jobId = this.capture.methods.getActiveJobID();
			activeCaptureJob.set( jobId );
		}
		else if( captureWasRunning == true && captureRunning.get() == false ) {
			logMessage( "Capture " + activeCaptureJob.get() + " was aborted");
		}

		return state;
	}

	public FocusState handleFocusStatus( FocusState state ) {
		state = super.handleFocusStatus( state );
		
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

		return state;
	}
	
	private boolean disableCameraWarming = true;

	private final Object[] checkCameraCoolingStates = new Object[2];
    protected void checkCameraCooling( KStarsState state ) {

		if( disableCameraWarming ) {
			return;
		}

		SchedulerState schedulerStatus = state.schedulerState.get();
		MountStatus mountStatus = state.mountStatus.get();

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


	public int runAutoFocus( int filter ) {
		WaitUntil maxWait = new WaitUntil( 20, "changeFilter" );
		
		this.getFilterDevice().setFilterSlot( filter ); //switch to first filter as reference
		
		while( this.getFilterDevice().getFilterSlotStatus() != IpsState.IPS_OK && maxWait.check() ) {
			try { Thread.sleep( 10 ); } catch( Throwable t ) {};
		}

		return this.runAutoFocus();
	}

	public int runAutoFocus() {
		if( this.focusRunning.get() == false ) {
			this.focus.methods.start();
		}

		final WaitUntil maxWait = new WaitUntil( 5, "Focusing" );

		while( !this.focusRunning.get() && maxWait.check() ) {
			try { Thread.sleep( 10 ); } catch( Throwable t ) {};
		}

		logMessage( "Focus process has started" );

		maxWait.reset( 300 );

		while( this.focusRunning.get() && maxWait.check() ) {
			try { Thread.sleep( 10 ); } catch( Throwable t ) {};
		}

		double pos = this.getFocusDevice().getFocusPosition();

		logMessage( "Focus process has finished: " + pos );

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
		Map<String,Object> res = new HashMap<>();
        
		res.put( "cameraDevice", this.getCameraDevice().deviceName );

		List<String> filters = this.getFilterDevice().getFilters() ;
		
		res.put( "filters", filters );
		res.put( "currentFilter", filters.get( this.getFilterDevice().getFilterSlot() - 1 ) );
		res.put( "focusPosition", this.getFocusDevice().getFocusPosition() );
		res.put( "rotatorAngle", this.getRotatorDevice().getRotatorPosition() );

		res.put( "automationSuspended", this.automationSuspended.get() );
		
		fillStatus( res );

		res.put( "calibrateFilterInProgress", calibrateFilterInProgress.get() );

		if( this.captureRunning.get() ) {
			res.put( "capture", getCaptureDetails( activeCaptureJob.get() ) ); 
		}
		
		res.put( "alignment", fillAlignment(new HashMap<>(), this.align.methods.getSolutionResult() ) );
		
		return res;
	}


    public double normalizePa(double pa) {

		if( pa == -1000000 ) {
			return 0;
		}

        pa = Math.round( pa * 100.0 ) / 100.0;
        while( pa < 0.0 ) {
            pa += 180.0;
        }
        while( pa >= 180.0 ) {
            pa -= 180.0;
        }
        pa = Math.round( pa * 100.0 ) / 100.0;
        return pa;
    }


	public Map<String, Object> fillAlignment(Map<String, Object> res, List<Double> alignSolution) {
		res.put( "solutionResult", alignSolution );

        double pa = normalizePa( alignSolution.get( 0 ).doubleValue() );
                            
		res.put( "pa", pa );
		
		double ra = alignSolution.get(1) / 15.0;
		double dec = alignSolution.get(2);
        res.put( "ra", ra );
		res.put( "dec", dec );

		return res;
	}



	public static class CaptureDetails {
		public int jobId;
		public double exposure;
		public double duration;
		public double timeLeft;
		public int imageProgress;
		public int imageCount;

		public String toString() {
			return jobId + ": " + exposure + "/" + duration + "s, " + imageProgress + "/" + imageCount;
		}
	}

	public CaptureDetails getCaptureDetails(int jobId) {
		CaptureDetails c = new CaptureDetails();

		c.jobId = jobId;
		c.duration = this.capture.methods.getJobExposureDuration( jobId );
		c.timeLeft = this.capture.methods.getJobExposureProgress( jobId );
		
		if( c.timeLeft == 0 ) {
			c.timeLeft = c.duration;
		}
		
		c.exposure = c.duration - c.timeLeft;
		
		c.imageCount = this.capture.methods.getJobImageCount( jobId );
		c.imageProgress = this.capture.methods.getJobImageProgress( jobId );
		
		return c;
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

	protected void captureAndSolveAndWait( boolean autoSync ) {

        this.alignStatus.set( AlignState.ALIGN_IDLE );

        //rotation of 180deg takes 65 seconds, so 130 seconds should be fine 
        WaitUntil maxWait = new WaitUntil( 20, "Capture and Solve" );

        this.align.methods.captureAndSolve();

        IpsState rotatorState = IpsState.IPS_IDLE;

        boolean alignRunning = true;
        while( alignRunning && maxWait.check() ) {
            AlignState state = this.alignStatus.get();

            IpsState cRotatorState = getRotatorDevice().getRotatorPositionStatus();
            if( cRotatorState == IpsState.IPS_BUSY ) {
                maxWait.reset();
            }

            if( cRotatorState != rotatorState ) {
                rotatorState = cRotatorState;

                logMessage( "Rotator is " + cRotatorState );
            }
            
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
                    maxWait.reset();
                    this.alignStatus.set( AlignState.ALIGN_IDLE );
                    
                    if( autoSync ) {
                        List<Double> coords = this.align.methods.getSolutionResult();
                        this.align.methods.setTargetCoords( coords.get(1) / 15.0, coords.get(2) );
                        logMessage( "Sync done: " + coords );
                    }
                break;

                case ALIGN_SLEWING:
                    maxWait.reset();
                    break;

                case ALIGN_ROTATING:
                    maxWait.reset();
                break;

                
                case ALIGN_SYNCING:
                    maxWait.reset();

                    if( autoSync ) {
                        List<Double> coords = this.align.methods.getSolutionResult();
                        this.align.methods.setTargetCoords( coords.get(1) / 15.0, coords.get(2) );
                        logMessage( "Sync done: " + coords );
                    }
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
}
