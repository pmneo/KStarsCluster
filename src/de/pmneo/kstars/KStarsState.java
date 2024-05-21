package de.pmneo.kstars;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.kde.kstars.Ekos.CommunicationStatus;
import org.kde.kstars.ekos.Dome;
import org.kde.kstars.ekos.SchedulerJob;
import org.kde.kstars.ekos.Align.AlignState;
import org.kde.kstars.ekos.Capture.CaptureStatus;
import org.kde.kstars.ekos.Focus.FocusState;
import org.kde.kstars.ekos.Guide.GuideStatus;
import org.kde.kstars.ekos.Mount.MeridianFlipStatus;
import org.kde.kstars.ekos.Mount.MountStatus;
import org.kde.kstars.ekos.Mount.ParkStatus;
import org.kde.kstars.ekos.Scheduler.SchedulerState;
import org.kde.kstars.ekos.Weather.WeatherState;

import de.pmneo.kstars.utils.DirtyBoolean;

public class KStarsState {
		
    public final DirtyBoolean captureRunning = new DirtyBoolean( false );
    public final DirtyBoolean focusRunning = new DirtyBoolean( false );
    public final DirtyBoolean schedulerRunning = new DirtyBoolean( false );
    public final DirtyBoolean gudingRunning = new DirtyBoolean( false );
    public final DirtyBoolean mountIsTracking = new DirtyBoolean( false );
    public final DirtyBoolean ditheringActive = new DirtyBoolean( false );
    public final AtomicReference<String> captureTarget = new AtomicReference<>( "" );
    

    private final String logPrefix;

    public KStarsState( String logPrefix ) {
        this.logPrefix = "[" + logPrefix + "] ";
    }

    
    private AtomicReference<String> lastMessage = new AtomicReference<String>("");
    public void logMessageOnce( String message ) {
        if( message.equals( lastMessage.getAndSet( message ) ) ) {
            return;
        }
		SimpleLogger.getLogger().logMessage( logPrefix + message );
	}

	public void logMessage( Object message ) {
		SimpleLogger.getLogger().logMessage( logPrefix + message );
	}

    public void logDebug( Object message ) {
		SimpleLogger.getLogger().logMessage( logPrefix + message );
	}
	
	public void logError( Object message, Throwable t ) {
        SimpleLogger.getLogger().logError( logPrefix + message, t );
	}	


    public void resetValues() {
        captureRunning.set( false );
        focusRunning.set( false );
        schedulerRunning.set(false);
        gudingRunning.set(false);
        ditheringActive.set(false);
    } 

    public final AtomicReference<CommunicationStatus> ekosStatus = new AtomicReference<CommunicationStatus>( CommunicationStatus.Idle );
    public CommunicationStatus handleEkosStatus( CommunicationStatus state ) {
        if( state != null ) {
            ekosStatus.set( state );
        }
        logMessage( "handleEkosStatus(" + state + ")" );
        return ekosStatus.get( );
    }

    public final AtomicReference<GuideStatus> guideStatus = new AtomicReference<GuideStatus>( GuideStatus.GUIDE_IDLE );
    public GuideStatus handleGuideStatus( GuideStatus state ) {
        if( state != null ) {
            guideStatus.set( state );
        }

        logMessage( "handleGuideStatus(" + state + ")" );
        state = guideStatus.get( );

        switch( state ) {
            case GUIDE_ABORTED:
                gudingRunning.set( false );
            break;
            
            case GUIDE_DITHERING:
                gudingRunning.set( true );
                ditheringActive.set( true );
            break;

            case GUIDE_MANUAL_DITHERING:
                ditheringActive.set( true );
            break;
                
            case GUIDE_GUIDING:
                gudingRunning.set( true );
                ditheringActive.set( false );
            break;
            
            case GUIDE_LOOPING:
            case GUIDE_DISCONNECTED:
                gudingRunning.set( false );
            break;
            
            default:
                //no need to handle
                break;
        }

        return state;
    }
    
    
    public final AtomicReference<MountStatus> mountStatus = new AtomicReference<MountStatus>( MountStatus.MOUNT_IDLE );
    public MountStatus handleMountStatus( MountStatus state ) {
        if( state != null ) {
            mountStatus.set( state );
        }

        logMessage( "handleMountStatus(" + state + ")" );
        state = mountStatus.get( );

        switch( state ) {
            case MOUNT_ERROR:
            case MOUNT_IDLE:
            case MOUNT_MOVING:
            case MOUNT_PARKED:
            case MOUNT_PARKING:
            case MOUNT_SLEWING:
                mountIsTracking.set( false );
                break;
            case MOUNT_TRACKING:
                mountIsTracking.set( true );
                break;
            default:
                break;
        }

        return state;
    }

    public final AtomicReference<ParkStatus> mountParkStatus = new AtomicReference<ParkStatus>( ParkStatus.PARK_UNKNOWN );
    public ParkStatus handleMountParkStatus( ParkStatus state ) {
        if( state != null ) {
            mountParkStatus.set( state );
        }

        logMessage( "handleMountParkStatus(" + state + ")" );
        return mountParkStatus.get();
    }

    public final AtomicReference<MeridianFlipStatus> meridianFlipStatus = new AtomicReference<MeridianFlipStatus>( MeridianFlipStatus.FLIP_NONE );
    public MeridianFlipStatus handleMeridianFlipStatus( MeridianFlipStatus state ) {
        if( state != null ) {
            meridianFlipStatus.set( state );
        }

        logMessage( "handleMeridianFlipStatus(" + state + ")" );
        return meridianFlipStatus.get();
    }
    
    public final AtomicReference<AlignState> alignStatus = new AtomicReference<AlignState>( AlignState.ALIGN_IDLE );
    public AlignState handleAlignStatus( AlignState state ) {
        if( state != null ) {
            alignStatus.set( state );
        }

        logMessage( "handleAlignStatus(" + state + ")" );
        state = alignStatus.get();
        return state;
    }

    
    public final AtomicReference<FocusState> focusState = new AtomicReference<FocusState>( FocusState.FOCUS_IDLE );
    protected FocusState handleFocusStatus( FocusState state ) {
        if( state != null ) {
            focusState.set( state );
        }

        logMessage( "handleFocusStatus(" + state + ")" );
        state = focusState.get();

        switch( state ) {
            case FOCUS_COMPLETE:
                focusRunning.set( false );
            break;
            
            case FOCUS_ABORTED:
            case FOCUS_FAILED:
                focusRunning.set( false );
            break;
            
            case FOCUS_IDLE:
                focusRunning.set( false );
            break;
            
            case FOCUS_PROGRESS:
                focusRunning.set( true );
            break;

            case FOCUS_FRAMING:
            case FOCUS_WAITING:
            case FOCUS_CHANGING_FILTER:

            break;
        }
        
        return state;
    }

    public final AtomicReference<CaptureStatus> captureStatus = new AtomicReference<CaptureStatus>( CaptureStatus.CAPTURE_IDLE );
    public CaptureStatus handleCaptureStatus( CaptureStatus state ) {
        if( state != null ) {
            captureStatus.set( state );
        }

        logMessage( "handleCaptureStatus(" + state + ")" );
        state = captureStatus.get();

        switch (state) {
            case CAPTURE_CAPTURING:
                captureRunning.set( true );
            break;
            
            case CAPTURE_PROGRESS:
                captureRunning.set( true );
            break;
            case CAPTURE_SETTING_TEMPERATURE:
                captureRunning.set( true );
            break;
            
            case CAPTURE_IMAGE_RECEIVED:
            break;
            
            case CAPTURE_ABORTED:
                captureRunning.set( false );
            break;
            
            case CAPTURE_COMPLETE:
            case CAPTURE_SUSPENDED:
                captureRunning.set( false );
            break;
            
            case CAPTURE_PAUSED:
            break;
            
            case CAPTURE_IDLE:
                //captureRunning.set( false );
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

        return state;
    }


    public final AtomicReference<SchedulerJob> schedulerActiveJob = new AtomicReference<SchedulerJob>( null );
    
    public final AtomicReference<SchedulerState> schedulerState = new AtomicReference<SchedulerState>( SchedulerState.SCHEDULER_IDLE );
    public SchedulerState handleSchedulerStatus( SchedulerState state ) {
        if( state != null ) {
            schedulerState.set( state );
        }

        logMessage( "handleSchedulerStatus(" + state + ")" );
        state = schedulerState.get();

        switch( state ) {
            case SCHEDULER_ABORTED:
            case SCHEDULER_IDLE:
            case SCHEDULER_SHUTDOWN:
                
            case SCHEDULER_LOADING:
            case SCHEDULER_PAUSED:
            
            case SCHEDULER_STARTUP:
                schedulerRunning.set( false );
            break;
                
            case SCHEDULER_RUNNING:
                schedulerRunning.set( true );
            break;
        }

        return state;
    }
    
    
    public final AtomicReference<WeatherState> weatherState = new AtomicReference<WeatherState>( WeatherState.WEATHER_IDLE );
    public WeatherState handleSchedulerWeatherStatus( WeatherState state ) {
        if( state != null ) {
            weatherState.set( state );
        }

        logMessage( "handleSchedulerWeatherStatus(" + state + ")" );
        return weatherState.get();
    }

    public final AtomicReference<Dome.DomeState> domeStatus = new AtomicReference<Dome.DomeState>( Dome.DomeState.DOME_IDLE );
    public Dome.DomeState handleDomeStatus( Dome.DomeState state ) {
        if( state != null ) {
            domeStatus.set( state );
        }

        logMessage( "handleDomeStatus(" + state + ")" );
        return domeStatus.get();
    }
    

    public Map<String, Object> fillStatus(Map<String, Object> res) {
		
		res.put( "captureRunning", this.captureRunning.get() );
		res.put( "focusRunning", this.focusRunning.get() );
        res.put( "gudingRunning", this.gudingRunning.get() );
        res.put( "ditheringActive", this.ditheringActive.get() );
        

		res.put( "alignStatus", this.alignStatus.get() );
		res.put( "weatherState", this.weatherState.get() );
        res.put( "mountStatus", this.mountStatus.get() );
        res.put( "schedulerState", this.schedulerState.get() );
        res.put( "captureStatus", this.captureStatus.get() );
        res.put( "focusState", this.focusState.get() );
        res.put( "guideStatus", this.guideStatus.get() );
        res.put( "captureTarget", this.captureTarget.get() );

        res.put( "activeJob", this.schedulerActiveJob.get() );
        
        //new GsonBuilder().create().

        return res;
	}
}

