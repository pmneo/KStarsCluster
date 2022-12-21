package de.pmneo.kstars;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.kde.kstars.Ekos.CommunicationStatus;
import org.kde.kstars.ekos.Align.AlignState;
import org.kde.kstars.ekos.Capture.CaptureStatus;
import org.kde.kstars.ekos.Focus.FocusState;
import org.kde.kstars.ekos.Guide.GuideStatus;
import org.kde.kstars.ekos.Mount.MeridianFlipStatus;
import org.kde.kstars.ekos.Mount.MountStatus;
import org.kde.kstars.ekos.Mount.ParkStatus;
import org.kde.kstars.ekos.Scheduler.SchedulerState;
import org.kde.kstars.ekos.Weather.WeatherState;

public class KStarsState {
		
    public final AtomicBoolean captureRunning = new AtomicBoolean( false );
    public final AtomicBoolean capturePaused = new AtomicBoolean( false );
    public final AtomicBoolean focusRunning = new AtomicBoolean( false );
    public final AtomicBoolean autoFocusDone = new AtomicBoolean( false );
    public final AtomicBoolean schedulerRunning = new AtomicBoolean( false );
    public final AtomicBoolean gudingRunning = new AtomicBoolean( false );
    public final AtomicBoolean ditheringActive = new AtomicBoolean( false );

    
    private final String logPrefix;

    public KStarsState( String logPrefix ) {
        this.logPrefix = "[" + logPrefix + "] ";
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
        capturePaused.set( false );
        focusRunning.set( false );
        autoFocusDone.set( false );
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
        return mountStatus.get( );
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
        return alignStatus.get();
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
                capturePaused.set( false );
            break;
            
            case CAPTURE_PROGRESS:
                //no need to handle
            break;
            
            case CAPTURE_IMAGE_RECEIVED:
            break;
            
            case CAPTURE_ABORTED:
                capturePaused.set( false );
                captureRunning.set( false );
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

        return state;
    }


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
                schedulerRunning.getAndSet( true );
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


    public Map<String, Object> fillStatus(Map<String, Object> res) {
		
		res.put( "captureRunning", this.captureRunning.get() );
		res.put( "capturePaused", this.capturePaused.get() );
		res.put( "focusRunning", this.focusRunning.get() );
		res.put( "autoFocusDone", this.autoFocusDone.get() );
        res.put( "gudingRunning", this.gudingRunning.get() );

		res.put( "alignStatus", this.alignStatus.get() );
		res.put( "weatherState", this.weatherState.get() );
        res.put( "mountStatus", this.mountStatus.get() );
        res.put( "schedulerState", this.schedulerState.get() );
        res.put( "captureStatus", this.captureStatus.get() );
        res.put( "focusState", this.focusState.get() );
        res.put( "guideStatus", this.guideStatus.get() );
        
        return res;
	}
}

