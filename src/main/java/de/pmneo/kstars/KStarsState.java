package de.pmneo.kstars;

import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReference;

import org.kde.kstars.Ekos.CommunicationStatus;
import org.kde.kstars.ekos.Dome;
import org.kde.kstars.ekos.SchedulerJob;
import org.kde.kstars.ekos.Align.AlignState;
import org.kde.kstars.ekos.Capture.CaptureStatus;
import org.kde.kstars.ekos.Focus.FocusState;
import org.kde.kstars.ekos.Guide.GuideStatus;
import org.kde.kstars.ekos.Mount.MountStatus;
import org.kde.kstars.ekos.Mount.ParkStatus;
import org.kde.kstars.ekos.Scheduler.SchedulerState;
import org.kde.kstars.ekos.Weather.WeatherState;

import de.pmneo.kstars.utils.DirtyBoolean;

public class KStarsState extends WithLogging {
		
    public final ConcurrentHashMap<String,Boolean> captureRunning = new ConcurrentHashMap<String,Boolean>();
    public final ConcurrentHashMap<String,Boolean> focusRunning = new ConcurrentHashMap<String,Boolean> ();

    /** Recent HFR samples per train, newest last — feeds the web UI's HFR graph. */
    public static class HfrSample {
        public final long ts;
        public final double hfr;
        public final int position;

        public HfrSample( long ts, double hfr, int position ) {
            this.ts = ts;
            this.hfr = hfr;
            this.position = position;
        }
    }

    private static final int HFR_HISTORY_CAP = 300;
    public final ConcurrentHashMap<String, Deque<HfrSample>> hfrHistory = new ConcurrentHashMap<>();

    public void recordHfr( String train, double hfr, int position ) {
        Deque<HfrSample> history = hfrHistory.computeIfAbsent( train, t -> new ConcurrentLinkedDeque<>() );
        history.addLast( new HfrSample( System.currentTimeMillis(), hfr, position ) );
        while( history.size() > HFR_HISTORY_CAP ) {
            history.pollFirst();
        }
    }

    /**
     * One entry per Capture.captureComplete signal — carries the exact saved path, so the web
     * UI's image preview never has to guess a directory or filename convention.
     */
    public static class CapturedImage {
        public final long ts;
        public final String filename;
        public final String filter;
        public final double exposure;
        public final double hfr;
        public final double eccentricity;
        public final double median;
        public final double snr;
        public final int starCount;
        public final int width;
        public final int height;
        public final int type;

        public CapturedImage( long ts, Map<String,Object> m ) {
            this.ts = ts;
            this.filename = str( m, "filename" );
            this.filter = str( m, "filter" );
            this.exposure = num( m, "exposure" );
            this.hfr = num( m, "hfr" );
            this.eccentricity = num( m, "eccentricity" );
            this.median = num( m, "median" );
            this.snr = num( m, "snr" );
            this.starCount = (int) num( m, "starCount" );
            this.width = (int) num( m, "width" );
            this.height = (int) num( m, "height" );
            this.type = (int) num( m, "type" );
        }

        private static String str( Map<String,Object> m, String key ) {
            Object v = m.get( key );
            return v == null ? null : v.toString();
        }

        private static double num( Map<String,Object> m, String key ) {
            Object v = m.get( key );
            if( v instanceof Number ) {
                return ((Number) v).doubleValue();
            }
            try {
                return v == null ? 0 : Double.parseDouble( v.toString() );
            }
            catch( Throwable t ) {
                return 0;
            }
        }
    }

    private static final int CAPTURED_IMAGES_CAP = 50;
    public final ConcurrentHashMap<String, Deque<CapturedImage>> capturedImages = new ConcurrentHashMap<>();

    public void recordCapturedImage( String train, Map<String,Object> metadata ) {
        Deque<CapturedImage> history = capturedImages.computeIfAbsent( train, t -> new ConcurrentLinkedDeque<>() );
        history.addLast( new CapturedImage( System.currentTimeMillis(), metadata ) );
        while( history.size() > CAPTURED_IMAGES_CAP ) {
            history.pollFirst();
        }
    }

    public final DirtyBoolean schedulerRunning = new DirtyBoolean( false );
    public final DirtyBoolean gudingRunning = new DirtyBoolean( false );
    public final DirtyBoolean mountIsTracking = new DirtyBoolean( false );
    public final DirtyBoolean ditheringActive = new DirtyBoolean( false );

    public KStarsState( String logPrefix ) {
        super( logPrefix );
    }

    public void resetValues() {
        captureRunning.clear();
        focusRunning.clear();
        schedulerRunning.set(false);
        gudingRunning.set(false);
        ditheringActive.set(false);
    } 

    public final AtomicReference<CommunicationStatus> ekosStatus = new AtomicReference<>( CommunicationStatus.Idle );
    public CommunicationStatus handleEkosStatus( CommunicationStatus state ) {
        if( state != null ) {
            ekosStatus.set( state );
        }
        logMessage( "handleEkosStatus(" + state + ")" );
        return ekosStatus.get( );
    }

    public final AtomicReference<CommunicationStatus> ekosIndiStatus = new AtomicReference<>( CommunicationStatus.Idle );
    public CommunicationStatus handleEkosIndiStatus( CommunicationStatus state ) {
        if( state != null ) {
            ekosIndiStatus.set( state );
        }
        logMessage( "handleEkosIndiStatus(" + state + ")" );
        return ekosIndiStatus.get( );
    }

    public final AtomicReference<GuideStatus> guideStatus = new AtomicReference<>( GuideStatus.GUIDE_IDLE );
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
    
    public final AtomicReference<MountStatus> mountStatus = new AtomicReference<>( MountStatus.MOUNT_IDLE );
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

    public final AtomicReference<ParkStatus> mountParkStatus = new AtomicReference<>( ParkStatus.PARK_UNKNOWN );
    public ParkStatus handleMountParkStatus( ParkStatus state ) {
        if( state != null ) {
            mountParkStatus.set( state );
        }

        logMessage( "handleMountParkStatus(" + state + ")" );
        return mountParkStatus.get();
    }

    public final AtomicReference<AlignState> alignStatus = new AtomicReference<>( AlignState.ALIGN_IDLE );
    public AlignState handleAlignStatus( AlignState state ) {
        if( state != null ) {
            alignStatus.set( state );
        }

        logMessage( "handleAlignStatus(" + state + ")" );
        state = alignStatus.get();
        return state;
    }

    
    public final ConcurrentHashMap<String,FocusState> focusState = new ConcurrentHashMap<>();
    protected FocusState handleFocusStatus( FocusState state, String train ) {
        if( state != null ) {
            focusState.put( train, state );
        }

        logMessage( "handleFocusStatus(" + train + ", " + state + ")" );
        state = focusState.computeIfAbsent( train, t -> FocusState.FOCUS_IDLE );

        switch( state ) {
            case FOCUS_COMPLETE:
                focusRunning.put( train, false );
            break;
            
            case FOCUS_ABORTED:
            case FOCUS_FAILED:
                focusRunning.put( train, false );
            break;
            
            case FOCUS_IDLE:
                focusRunning.put( train, false );
            break;
            
            case FOCUS_PROGRESS:
                focusRunning.put( train, true );
            break;

            case FOCUS_FRAMING:
            case FOCUS_WAITING:
            case FOCUS_CHANGING_FILTER:

            break;
        }
        
        return state;
    }

    public final ConcurrentHashMap<String,CaptureStatus> captureStatus = new ConcurrentHashMap<>();

    /**
     * Parsed Capture.getSequenceQueueStatusJSON(train) result, refreshed once per second from
     * the periodic status broadcaster (never from a signal handler — it's a synchronous D-Bus
     * call). One entry per train, holding the full sequence queue detail Ekos itself shows:
     * active job progress/remaining time and every step's filter/exposure/count/status.
     */
    public final ConcurrentHashMap<String, Object> sequenceQueueStatus = new ConcurrentHashMap<>();

    public CaptureStatus handleCaptureStatus( CaptureStatus state, String train ) {

        if( state != null ) {
            captureStatus.put( train, state );
        }

        logMessage( "handleCaptureStatus(" + train + ", " + state + ")" );
        state = captureStatus.computeIfAbsent( train, t -> CaptureStatus.CAPTURE_IDLE );

        switch (state) {
            case CAPTURE_CAPTURING:
                captureRunning.put( train, true );
            break;
            
            case CAPTURE_PROGRESS:
                captureRunning.put( train, true );
            break;
            case CAPTURE_SETTING_TEMPERATURE:
                captureRunning.put( train, true );
            break;
            
            case CAPTURE_IMAGE_RECEIVED:
            break;
            
            case CAPTURE_ABORTED:
                captureRunning.put( train, false );
            break;
            
            case CAPTURE_COMPLETE:
            case CAPTURE_SUSPENDED:
                captureRunning.put( train, false );
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

    /** True only while the scheduler actually EXECUTES a job — false while it merely waits for a job's startup time. */
    public boolean isSchedulerJobExecuting() {
        final SchedulerJob job = schedulerActiveJob.get();
        return job != null && job.isExecuting();
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
		res.put( "captureRunning", this.captureRunning );
		res.put( "focusRunning", this.focusRunning );
        res.put( "gudingRunning", this.gudingRunning.get() );
        res.put( "ditheringActive", this.ditheringActive.get() );

		res.put( "alignStatus", this.alignStatus.get() );
		res.put( "weatherState", this.weatherState.get() );
        res.put( "mountStatus", this.mountStatus.get() );
        res.put( "schedulerState", this.schedulerState.get() );
        res.put( "captureStatus", this.captureStatus );
        res.put( "focusState", this.focusState );
        res.put( "guideStatus", this.guideStatus.get() );

        res.put( "activeJob", this.schedulerActiveJob.get() );

        return res;
	}
}

