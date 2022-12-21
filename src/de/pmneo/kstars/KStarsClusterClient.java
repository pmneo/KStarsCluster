package de.pmneo.kstars;

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicMarkableReference;
import java.util.concurrent.atomic.AtomicReference;

import org.freedesktop.dbus.exceptions.DBusException;
import org.kde.kstars.ParkStatus;
import org.kde.kstars.ekos.Align.AlignState;
import org.kde.kstars.ekos.Capture.CaptureStatus;
import org.kde.kstars.ekos.Focus.FocusState;
import org.kde.kstars.ekos.Guide.GuideStatus;
import org.kde.kstars.ekos.Mount.MountStatus;
import org.kde.kstars.ekos.Scheduler.SchedulerState;
import org.kde.kstars.ekos.Weather.WeatherState;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class KStarsClusterClient extends KStarsCluster {
    
    protected final String host;
    protected final int listenPort; 
    
    public final KStarsState server = new KStarsState( "Server" );

    protected boolean autoFocusEnabled = true;
    
    protected String targetPostFix = "client";
    
    public KStarsClusterClient( String host, int listenPort ) throws DBusException {
        super( "Client" );
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

    public final AtomicBoolean syncEnabled = new AtomicBoolean( true );

    public void ekosReady() {
        super.ekosReady();

        synchronized( this ) {
            if( clientWorker == null || clientWorker.isAlive() == false ) {
                clientWorker = new Thread( () -> {
                    while( true ) {
                        try {
                            if( isKStarsReady() && serverInitDone.get() && this.automationSuspended.get() == false ) {
                                checkClientState();
                            }
                            sleep( 100 );
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
    
    protected final AtomicBoolean serverInitDone = new AtomicBoolean( false );

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
                   
                    server.handleMountStatus(status);

                    if( serverInitDone.get() ) {
                        this.checkCameraCooling( server );
                    }
                }
                else if( "handleGuideStatus".equals( action ) ) {
                    final GuideStatus status = (GuideStatus) payload.get( "status" );

                    server.handleGuideStatus( status );
                }
                else if( "handleCaptureStatus".equals( action ) ) {
                    final CaptureStatus status = (CaptureStatus) payload.get( "status" );
                    server.handleCaptureStatus(status);

                    String serverTarget = (String) payload.get( "targetName" );
                    
                    if( serverTarget != null ) {
                        serverCaptureTarget.set( serverTarget );
                    }
                }
                else if( "handleFocusStatus".equals( action ) ) {
                    final FocusState status = (FocusState) payload.get( "status" );
                    
                    server.handleFocusStatus(status);
                }
                else if( "handleSchedulerStatus".equals( action ) ) {
                    final SchedulerState status = (SchedulerState) payload.get( "status" );
                    
                    server.handleSchedulerStatus( status );

                    if( serverInitDone.get() ) {
                        this.checkCameraCooling( server );
                    }
                }
                else if( "handleSchedulerWeatherStatus".equals( action ) ) {
                    final WeatherState status = (WeatherState) payload.get( "status" );
                    
                    server.handleSchedulerWeatherStatus(status);
                }
                else if( "handleAlignStatus".equals( action ) ) {
                    final AlignState status = (AlignState) payload.get( "status" );
                    
                    server.handleAlignStatus( status );

                    @SuppressWarnings( "unchecked" )
                    final List<Double> res = (List<Double>) payload.get( "solutionResult" );
                    serverSolutionResult.set( res );
                    logMessage( "Server Solution Result: " + serverSolutionResult.get() );

                    if( status == AlignState.ALIGN_COMPLETE ) {
                        shouldAlign.set( true );
                    }
                }
            }
            else if( "Hello Client".equals( frame ) ) {
                serverInitDone.set( true ); //legacy server
            }
            else if( "Begin init".equals( frame ) ) {
                serverInitDone.set( false );
                logMessage( "Begin init by server");
            }
            else if( "Init done".equals( frame ) ) {
                serverInitDone.set( true );
                logMessage( "Server init done");
            }
        }
        catch( Throwable t ) {
            logError( "Error due handle server frame", t );
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
                    clientHandler.receive( 
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


    protected AtomicReference<String> serverCaptureTarget = new AtomicReference<>( null );
    protected AtomicReference< List<Double> > serverSolutionResult = new AtomicReference<>();

    protected AtomicBoolean shouldAlign = new AtomicBoolean( false );
    protected AtomicBoolean autoCapture = new AtomicBoolean( true );
    
    public void resetValues() {
        super.resetValues();

        autoCapture.set( true );
        shouldAlign.set( true );
    }
    
    
    
    @Override
    public CaptureStatus handleCaptureStatus(CaptureStatus state) {
        state = super.handleCaptureStatus(state);
        
        switch (state) {
        
            case CAPTURE_CAPTURING:
                autoCapture.set( true );
                
                int jobId = activeCaptureJob.get();

                if( !canCapture() ) {
                    checkStopCapture();
                }
                else {
                    final CaptureDetails details = getCaptureDetails( jobId );
                    logMessage( "Capture started " + details );
                }
            break;

            default: 
            break;
        }

        return state;
    }
    
    private void startCapture() {
        if( this.captureRunning.getAndSet( true ) == false ) {
            this.capture.methods.start();
        }
    }
    private void stopCapture() {
        this.capturePaused.set( false );
        if( this.captureRunning.getAndSet( false ) == true ) {
            this.capture.methods.stop();
        }
    }
    
    public boolean canCapture() {
        //capture is allowed, when mount is tracking and NO dithering is active
        if( server.gudingRunning.get() && server.ditheringActive.get() == false ) {
            return true;
        }
        else {
            return false;
        }
    }

    protected synchronized void checkClientState() {
        if( this.focusRunning.get() ) {
            //can't do anything at this moment.
            return;
        }

        if( canCapture() ) {

            String currentTargetName = (String) this.capture.read( "targetName" );
            String targetName = serverCaptureTarget.get() + "_" + targetPostFix;
            
            if( targetName.equals( currentTargetName ) == false ) {
                logMessage( "Target has changed: " + targetName );

                if( captureRunning.get() ) {
                    logMessage( "Stopping capture, to load new sequence" );
                    this.stopCapture();

                    final WaitUntil waitUntil = new WaitUntil( 5, "Wait for capture has stopped" );
                    while( waitUntil.check() && captureRunning.get() ) sleep( 100 );
                }

                this.capture.write( "targetName", targetName );
                this.capture.methods.clearSequenceQueue();


                final WaitUntil waitUntil = new WaitUntil( 5, "Wait for capture has cleared" );
                while( waitUntil.check() && this.capture.methods.getJobCount() > 0 ) sleep( 100 );
                
                if( this.autoFocusDone.getAndSet( false ) ) {
                    logMessage( "Resetting autofocus done to enforce refocus" );
                }

                this.loadSequence();
            }

            if( this.captureRunning.get() ) { 
                //check if we should resume
                if( this.capturePaused.get() ) {
                    final int jobId = activeCaptureJob.get();
                    logMessage( "Warning: paused job " + jobId );
                }
            }
            else if( server.gudingRunning.get() && autoCapture.get() ) {
                //if server is tracking AND guiding, we can start capture
                logMessage( "Server is Guding, but no capture or focus is in progress" );
                
                if( this.autoFocusDone.get() == false ) {
                    try {
                        if( this.isAutoFocusEnabled() && this.focus.methods.canAutoFocus() ) {
                            logMessage( "Starting Autofocus process" );
                            runAutoFocus();
                        }
                        else {
                            logMessage( "Autofocus is disabled" );
                        }
                    }
                    catch( Throwable t ) {
                        //autofocus failed, this
                        logMessage( "Focus module not present" );
                    }
                    finally {
                        this.autoFocusDone.set( true );
                    }
                }
                
                if( shouldAlign.get() ) {
                    try {
                        executeAlignment();
                    }
                    catch( Throwable t ) {
                        //autofocus failed, this
                        logError( "Align failed", t );
                    }
                    finally {
                        shouldAlign.set( false );
                    }
                }

                
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
        else {
            checkStopCapture();
        }
    }

    public void checkStopCapture() {
        //no capture possible, check if we have to abort
        if( this.captureRunning.get() && this.automationSuspended.get() == false ) {
            final int jobId = activeCaptureJob.get();
            
            final double timeLeft = this.capture.methods.getJobExposureProgress( jobId );
            
            //if more than 2 seconds left in exposure, abort
            if( timeLeft >= 2.0 ) {
                logMessage( "Aborting job " + jobId + " with " + timeLeft + "s left" );
                stopCapture();
            }
        }
    }

    public void executeAlignment() {
        Integer currenParkStatusOrdinal = (Integer) this.mount.read( "parkStatus" );

        WaitUntil maxWait = new WaitUntil( 60, "Unparking Mount" );
        while( currenParkStatusOrdinal.intValue() != ParkStatus.PARK_UNPARKED.ordinal() && maxWait.check() ) {
            if( currenParkStatusOrdinal.intValue() != ParkStatus.PARK_UNPARKING.ordinal() ) {
                this.mount.methods.unpark();
            }
            currenParkStatusOrdinal = (Integer) this.mount.read( "parkStatus" );
        }
        List<Double> serverSolution = serverSolutionResult.get();
      
        logMessage( "Slewing to " + (serverSolution.get(1) / 15.0 ) + " / " + serverSolution.get(2) );
        this.mount.methods.slew( serverSolution.get(1) / 15.0, serverSolution.get(2) );
        waitForMountTracking( 60 );
               
        double pa = normalizePa( serverSolution.get( 0 ).doubleValue() );

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

    protected void waitForMountTracking( long timeout ) {
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
    
    @Override
    public Map<String,Object> statusAction(String[] parts, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String,Object> res = super.statusAction(parts, req, resp);

        Map<String,Object> serverInfo = server.fillStatus( new HashMap<>() );
        serverInfo.put( "alignment", fillAlignment(new HashMap<>(), serverSolutionResult.get() ) );

        res.put( "serverInfo", serverInfo );

        return res;
    }

    
}