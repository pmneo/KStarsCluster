package de.pmneo.kstars;

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.freedesktop.dbus.exceptions.DBusException;
import org.kde.kstars.ParkStatus;
import org.kde.kstars.INDI.IpsState;
import org.kde.kstars.ekos.Align.AlignState;
import org.kde.kstars.ekos.Capture.CaptureStatus;
import org.kde.kstars.ekos.Focus.FocusState;
import org.kde.kstars.ekos.Guide.GuideStatus;
import org.kde.kstars.ekos.Mount.MountStatus;
import org.kde.kstars.ekos.Scheduler.SchedulerState;
import org.kde.kstars.ekos.Weather.WeatherState;

public class KStarsClusterClient extends KStarsCluster {
    
    protected final String host;
    protected final int listenPort; 
    
    protected boolean autoFocusEnabled = true;
    
    protected String targetPostFix = "client";
    
    public KStarsClusterClient( String host, int listenPort ) throws DBusException {
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
    
    private Thread clientWorker = null;

    public void ekosReady() {
        super.ekosReady();

        synchronized( this ) {
            if( clientWorker == null || clientWorker.isAlive() == false ) {
                clientWorker = new Thread( () -> {
                    while( true ) {
                        try {
                            if( isKStarsReady() ) {
                                checkClientState();
                            }
                            Thread.sleep( 100 );
                        }
                        catch( Throwable t ) {
                            logError( "Error in check client state", t) ;
                        }
                    }
                }, "clientWorker" );
                clientWorker.start();
            }
        }

        //loadSequence();
    }
    
    public void loadSequence() {
        if( captureSequence != null && captureSequence.isEmpty() == false ) {
            File f = new File( captureSequence );

            if( serverCaptureTarget.get() != null ) {
                String targetName = ( serverCaptureTarget.get() + ".esq" ).toLowerCase();
                targetName = targetName.replaceAll( "[ -]", "_" );

                System.out.println( "Try to resolve sequence by target: " + targetName );

                for( File ff : f.getParentFile().listFiles() ) {
                    String fName = ff.getName().toLowerCase().replaceAll( "[ -]", "_" );
                    if( fName.equalsIgnoreCase( targetName ) ) {
                        f = ff;
                        System.out.println( "Using by target sequence: " + ff.getPath() );
                        break;
                    }
                }
            }

            if( f.exists() ) {
                Object status = capture.getParsedProperties().get( "status" );
                if( status != CaptureStatus.CAPTURE_CAPTURING ) {
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
    protected AtomicReference<CaptureStatus> serverCaptureStatus = new AtomicReference<>( null );
    protected AtomicReference<String> serverCaptureTarget = new AtomicReference<>( null );
    protected AtomicBoolean serverGudingRunning = new AtomicBoolean( false );
    protected AtomicBoolean serverDitheringActive = new AtomicBoolean( false );

    protected AtomicBoolean shouldAlign = new AtomicBoolean( false );
    protected AtomicReference< List<Double> > serverSolutionResult = new AtomicReference<>();
    
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

    protected AtomicReference< List<Double> > serverCoords = new AtomicReference<>( Arrays.asList( Double.valueOf(0), Double.valueOf(0) ) ); 
    
    protected final AtomicReference<MountStatus> serverMountStatus = new AtomicReference<>( MountStatus.MOUNT_PARKED );

    protected void handleServerMountStatus( final MountStatus status, final Map<String, Object> payload ) {
        logMessage( "Server mount status " + status );
    
        serverMountStatus.set( status );
        
        @SuppressWarnings("unchecked") 
        List<Double> pos = (List<Double>) payload.get( "equatorialCoords" );
        if( pos != null ) {
            serverCoords.set( pos );
        }

        switch( status ) {
            case MOUNT_ERROR:
                serverMountTracking.set( false );
                break;
            case MOUNT_IDLE:
                serverMountTracking.set( false );
                break;
            case MOUNT_MOVING:
                serverMountTracking.set( false );
                break;
            case MOUNT_PARKED:
                serverMountTracking.set( false );
                break;
            case MOUNT_PARKING:
                serverMountTracking.set( false );
                break;
            case MOUNT_SLEWING:
                serverMountTracking.set( false );
                break;
            case MOUNT_TRACKING:
                serverMountTracking.set( true );
                break;
            default:
                break;
        }

        checkCameraCooling( serverSchedulerState.get(), serverMountStatus.get() );
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
            break;
                
            case GUIDE_GUIDING:
                serverGudingRunning.set( true );
                serverDitheringActive.set( false );
            break;
            
            case GUIDE_LOOPING:
            case GUIDE_DISCONNECTED:
                serverGudingRunning.set( false );
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

    private final AtomicReference< SchedulerState > serverSchedulerState = new AtomicReference<>( SchedulerState.SCHEDULER_IDLE );
    protected void handleServerSchedulerStatus(SchedulerState status, final Map<String, Object> payload) {
        logMessage( "Server scheduler status " + status );
        
        serverSchedulerState.set( status );

        switch( status ) {
            case SCHEDULER_ABORTED:
            case SCHEDULER_IDLE:
            case SCHEDULER_SHUTDOWN:
                
            case SCHEDULER_LOADING:
            case SCHEDULER_PAUSED:
            
            case SCHEDULER_STARTUP:
                serverSchedulerRunning.set( false );
            break;
                
            case SCHEDULER_RUNNING:
                serverSchedulerRunning.set( true );
            break;
        }

        this.checkCameraCooling( serverSchedulerState.get(), serverMountStatus.get() );
    }

    protected void handleServerSchedulerWeatherStatus(WeatherState status, final Map<String, Object> payload) {
        logMessage( "Server scheduler weather status " + status );
    }
    
    protected void handleServerAlignStatus(AlignState status, Map<String, Object> payload) {
        logMessage( "Server scheduler align status " + status );

        serverSolutionResult.set( (List<Double>) payload.get( "solutionResult" ) );
        logMessage( "Server Solution Result: " + serverSolutionResult.get() );
        if( status == AlignState.ALIGN_COMPLETE ) {
            shouldAlign.set( true );
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
            break;
                
            case FOCUS_PROGRESS:
                serverFocusRunning.set( true );
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
    }
    
    protected void handleServerCaptureStatus(CaptureStatus status, final Map<String, Object> payload) {
        logMessage( "Server capture status " + status );

        serverCaptureStatus.set( status );
        
        String serverTarget = (String) payload.get( "targetName" );
        
        if( serverTarget == null ) {
            serverTarget = "";
        }

        logMessage( "Server target name: " + serverTarget );
        
        serverCaptureTarget.set( serverTarget );

        switch( status ) {
            case CAPTURE_CAPTURING:
            case CAPTURE_PROGRESS:
                serverCaptureRunning.set( true );
            break;
            case CAPTURE_IMAGE_RECEIVED:
                
            break;
            case CAPTURE_COMPLETE:
            case CAPTURE_ABORTED:
            case CAPTURE_SUSPENDED:
                serverCaptureRunning.set( false );
            break;
            case CAPTURE_IDLE:
                //no need to handle
                break;
            
            case CAPTURE_PAUSED:
            case CAPTURE_PAUSE_PLANNED:
                break;
            
            case CAPTURE_DITHERING:
                serverDitheringActive.set( true );
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

    protected synchronized void checkClientState() {
        if( focusRunning.get() ) {
            //can't do anything at this moment.
            return;
        }

        if( canCapture() ) {

            String currentTargetName = (String) this.capture.read( "targetName" );
            String targetName = serverCaptureTarget.get() + "_" + targetPostFix;
            
            if( targetName.equals( currentTargetName ) == false ) {
                logMessage( "Target has changed: " + targetName );
                this.stopCapture();

                this.capture.write( "targetName", targetName );
                this.capture.methods.clearSequenceQueue();

                this.loadSequence();
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
                            if( focusRunning.get() == false ) {
                                this.focus.methods.start();
                            }

                            final WaitUntil maxWait = new WaitUntil( 5, "Focusing" );
                            while( !focusRunning.get() && maxWait.check() ) {
                                Thread.sleep( 10 );
                            }
                            logMessage( "Focus process has started" );

                            maxWait.reset( 300 );
                            while( focusRunning.get() && maxWait.check() ) {
                                Thread.sleep( 10 );
                            }

                            logMessage( "Focus process has finished" );
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
                else if( shouldAlign.get() ) {
                    try {

                        Integer currenParkStatusOrdinal = (Integer) this.mount.read( "parkStatus" );

                        WaitUntil maxWait = new WaitUntil( 60, "Unparking Mount" );
                        while( currenParkStatusOrdinal.intValue() != ParkStatus.PARK_UNPARKED.ordinal() && maxWait.check() ) {
                            if( currenParkStatusOrdinal.intValue() != ParkStatus.PARK_UNPARKING.ordinal() ) {
                                this.mount.methods.unpark();
                            }
                            currenParkStatusOrdinal = (Integer) this.mount.read( "parkStatus" );
                        }
                        List<Double> serverSolution = serverSolutionResult.get();
        
                        if( serverSolution != null && serverSolution.size() == 3 ) {
        
                            //List<Double> pos = serverCoords.get();
                            logMessage( "Slewing to " + (serverSolution.get(1) / 15.0 ) + " / " + serverSolution.get(2) );
                            this.mount.methods.slew( serverSolution.get(1) / 15.0, serverSolution.get(2) );
                            waitForMountTracking( 60 );
                        
                            double pa = serverSolution.get( 0 ).doubleValue();
                            
                            pa = Math.round( pa * 100.0 ) / 100.0;
                            while( pa < 0.0 ) {
                                pa += 180.0;
                            }
                            while( pa >= 180.0 ) {
                                pa -= 180.0;
                            }
                            pa = Math.round( pa * 100.0 ) / 100.0;

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
                            captureAndSolveAndWait( true );
                            
                            coords = this.align.methods.getSolutionResult();
                            logMessage( "PA align done: " + coords );
                        }
                    }
                    catch( Throwable t ) {
                        //autofocus failed, this
                        logError( "Align failed", t );
                    }
                    finally {
                        shouldAlign.set( false );
                    }
                }
                else {
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
                        this.capture.methods.abort();
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
            }
        }
    }

    protected void waitForMountTracking( long timeout ) {
        WaitUntil maxWait = new WaitUntil( timeout, "Mount Tracking" );
        logMessage( "Wait for mount tracking: " + this.currentMountStatus.get() );
        boolean mountTracking = false;
        while( !mountTracking && maxWait.check() ) {
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
    protected void captureAndSolveAndWait( boolean autoSync ) {

        this.currentAlignStatus.set( AlignState.ALIGN_IDLE );

        //rotation of 180deg takes 65 seconds, so 130 seconds should be fine 
        WaitUntil maxWait = new WaitUntil( 20, "Capture and Solve" );

        this.align.methods.captureAndSolve();

        IpsState rotatorState = IpsState.IPS_IDLE;

        boolean alignRunning = true;
        while( alignRunning && maxWait.check() ) {
            AlignState state = this.currentAlignStatus.get();

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
                    currentAlignStatus.set( AlignState.ALIGN_IDLE );
                    
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
                if( clientHandler != null ) {
                    clientHandler.socket.close();
                }
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