package de.pmneo.kstars;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.freedesktop.dbus.exceptions.DBusException;
import org.kde.kstars.ekos.Align.AlignState;
import org.kde.kstars.ekos.Capture.CaptureStatus;
import org.kde.kstars.ekos.Focus.FocusState;
import org.kde.kstars.ekos.Guide.GuideStatus;
import org.kde.kstars.ekos.Mount.MountStatus;
import org.kde.kstars.ekos.Scheduler.SchedulerState;
import org.kde.kstars.ekos.Weather.WeatherState;

import de.pmneo.kstars.web.CommandServlet.Action;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class KStarsClusterClientLegacy extends KStarsCluster {
    
    protected final String host;
    protected final int listenPort; 
    
    public final KStarsState server = new KStarsState( "Server" );

    protected boolean autoFocusEnabled = true;
    
    protected String targetPostFix = "client";
    
    public KStarsClusterClientLegacy( String host, int listenPort ) throws DBusException {
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
                            if( ekosReady.get() && serverInitDone.get() && this.automationSuspended.get() == false ) {
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

    public String resolveCaptureSequence() {
        File f;

        if( captureSequence != null && captureSequence.isEmpty() == false ) {
            f = new File( captureSequence );
        }
        else {
            f = new File( System.getProperty("user.home") + "/current_sequence.esq" );
        }

        if( server.captureTarget.get() != null ) {
            String targetName = ( server.captureTarget.get() + ".esq" ).toLowerCase();
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
            try {
                f = f.getCanonicalFile();
            }
            catch( IOException e ) {
                //ignore
            }

            return f.getPath();
        }
        else {
            logMessage( "capture File does not exists: " + f.getPath() );

            return null;
        }
        
    }

    public void loadSequence() {

        String currentTargetName = (String) this.captureTarget.get();

        String sequencePath = resolveCaptureSequence();

        if( sequencePath == null ) {
            return;
        }

        if( captureRunning.get() ) {
            logMessage( "Stopping capture, to load new sequence" );
            this.stopCapture();
        }

        this.capture.methods.clearSequenceQueue();

        WaitUntil.waitUntil( "Capture sequence is empty", 5, () -> this.capture.methods.getJobCount() > 0 );

        logMessage( "loading sequence " + sequencePath + " for target " + currentTargetName );
        try {
            capture.methods.loadSequenceQueue( sequencePath, currentTargetName );//server.captureTarget.get() );
        }
        catch( Throwable t ) {
            logError( "Failed to load sequence", t );
        }

        WaitUntil.waitUntil( "Capture sequence is loaded", 5, () -> this.capture.methods.getJobCount() == 0 );

        try {
            logMessage( "sequence loaded with " + this.capture.methods.getPendingJobCount() + " pending jobs" );

            this.capture.write( "targetName", currentTargetName );
        }
        catch( Throwable t ) {
            logError( "Failed to determine pending jobs", t );
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
    protected final AtomicBoolean serverSolutionChanged = new AtomicBoolean( false );

    protected void serverFrameReceived( SocketHandler client, Object frame ) {
        try {
            //this should currently not happen?

            if( frame instanceof Map ) {
                @SuppressWarnings("unchecked")
                final Map<String, Object> payload = (Map<String, Object>) frame;
                final String action = (String) payload.get( "action" );
                
                if( "handleMountStatus".equals( action ) ) {
                    final MountStatus status = (MountStatus) payload.get( "status" );
                   
                    server.handleMountStatus(status);

                    if( serverInitDone.get() && ekosReady.get() ) {
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
                        server.captureTarget.set( serverTarget );
                    }
                }
                else if( "handleFocusStatus".equals( action ) ) {
                    final FocusState status = (FocusState) payload.get( "status" );
                    
                    server.handleFocusStatus(status);
                }
                else if( "handleSchedulerStatus".equals( action ) ) {
                    final SchedulerState status = (SchedulerState) payload.get( "status" );
                    
                    server.handleSchedulerStatus( status );                   

                    if( serverInitDone.get() && ekosReady.get()  ) {
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
                    final List<Double> prev = serverSolutionResult.getAndSet( res );

                    if( prev == null || res.equals( prev ) == false ) {
                        logMessage( "Server Solution Result changed: " + res );

                        serverSolutionChanged.set( true );
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
    protected void ekosDisconnected() {
        super.ekosDisconnected();

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
            while( ekosReady.get() ) {
                try {
                    synchronized ( this ) {
                        clientHandler = new SocketHandler( new Socket( host, listenPort ) );
                    }
                    clientHandler.writeObject( "Hello Server" );
                    logMessage( "Connected to " + host + " ...");
                    clientHandler.receive( 
                        this::serverFrameReceived, 
                        (c,t) -> {
                            logMessage( "Disconnected from " + host + " ");
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


    protected AtomicReference< List<Double> > serverSolutionResult = new AtomicReference<>();

    public void resetValues() {
        super.resetValues();
    }
    
    
    @Override
    public CaptureStatus handleCaptureStatus(CaptureStatus state) {
        state = super.handleCaptureStatus(state);
        return state;
    }
    
    private boolean startCapture() {
        if( this.captureRunning.get() == false ) {
            this.capture.methods.start();
            return WaitUntil.waitUntil( "capture has started", 5, () -> captureRunning.get() == false );
        }
        else {
            return true;
        }
    }

    private void stopCapture() {
        if( this.captureRunning.get() ) {

            this.capture.methods.abort();
            WaitUntil.waitUntil( "capture has stopped", 5, () -> captureRunning.get() );

            if( this.captureRunning.get() ) {
                this.capture.determineAndDispatchCurrentState();
            }            
        }
    }
    
    public static enum Stage {
        INIT,
        FOCUS,
        ALIGN,
        CAPTURE
    }

    private Stage stage = Stage.INIT;

    protected synchronized void checkClientState() {
        
        if( server.mountIsTracking.get() ) {
            if( server.mountIsTracking.hasChangedAndReset() ) {
                logMessage( "Server mount started tracking" );
            }

            String currentTargetName = (String) this.capture.read( "targetName" );
            String sTarget = server.captureTarget.get();
            if( sTarget == null || sTarget.isEmpty() ) {
                sTarget = "Unkown";
            }
            String targetName = sTarget + "_" + targetPostFix;

            
            if( currentTargetName == null || currentTargetName.isEmpty() ) {
                currentTargetName = this.captureTarget.get();
                //logMessage( "Target is empty, restoring to " + currentTargetName );
                //this.capture.write( "targetName", currentTargetName );
            }
            
            if( targetName.equals( currentTargetName ) == false ) {
                logMessage( "Target has changed from " + currentTargetName + " to " + targetName );

                this.captureTarget.set( targetName );
                this.capture.write( "targetName", targetName );

                this.loadSequence();

                this.capture.write( "targetName", targetName );
                serverSolutionChanged.set( true );
            }

            if( serverSolutionChanged.getAndSet( false ) ) {
                Stage prevStage = this.stage;
                switch( this.stage ) {
                    case FOCUS:
                    case ALIGN:
                        //nothing todo
                        break;
                    case CAPTURE:
                        //we have to refocus and realign
                        this.stage = Stage.FOCUS;
                        break;
                    
                    case INIT:
                        if( captureRunning.get() ) {
                            this.stage = Stage.CAPTURE;
                            logMessage( "Resume to capture after restart" );
                        }
                        else {
                            this.stage = Stage.FOCUS;
                        }
                    break;
                }

                if( prevStage != this.stage ) {
                    logMessage( "changing next stage from " + prevStage + " to " + this.stage );
                }
            }

            if( server.gudingRunning.get() ) {

                if( server.gudingRunning.hasChangedAndReset() ) {
                    logMessage( "Server mount started guiding" );
                }

                if( stage == Stage.FOCUS ) {
                    this.focus.methods.abort( opticalTrain.get() );
                    this.align.methods.abort();
                    this.stopCapture();

                    try {
                        if( this.isAutoFocusEnabled() && this.focus.methods.canAutoFocus( opticalTrain.get() ) ) {
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
                        stage = Stage.ALIGN;

                        logMessage( "changing next stage to " + stage );
                    }
                }
                else if( stage == Stage.ALIGN ) {
                    this.focus.methods.abort( opticalTrain.get() );
                    this.align.methods.abort();
                    this.stopCapture();

                    try {
                        executeAlignment();

                        logMessage( "checking pa" );

                        if( checkIfPaInRange( getTargetPa(), 2 ) ) {
                            stage = Stage.CAPTURE;
                            logMessage( "changing next stage to " + stage );
                        }

                    }
                    catch( Throwable t ) {
                        //autofocus failed, this
                        logError( "Align failed", t );
                    }
                    finally {
                        
                    }
                }
                else if( stage == Stage.CAPTURE ) {
                    if( server.ditheringActive.hasChangedAndReset() ) {
                        logMessage( "Server dithering has changed: " + server.ditheringActive.get() );
                        checkStopCapture(); //check stop in any case, also if dithering is not longer active because the next job might have started
                    }

                    if( server.ditheringActive.get() ) {
                        checkStopCapture();
                    }
                    else {
                        if( this.focusRunning.get() ) {
                            if( this.focusRunning.hasChangedAndReset() ) {
                                logMessage( "Focus process is running" );
                            }

                            return;
                        }
                        else if( this.focusRunning.hasChangedAndReset() ) {
                            logMessage( "Focus process has finished" );
                            return;
                        }

                        this.checkStartCapture();
                    }
                }
            }
            else if( server.gudingRunning.hasChangedAndReset() ) {
                logMessage( "Server mount stopped guiding" );
            }
        }
        else if( server.mountIsTracking.hasChangedAndReset() ) {
            logMessage( "Server mount is not longer in tracking" );
            this.stopCapture();
        }
    }

    private double getTargetPa() {
        List<Double> serverSolution = serverSolutionResult.get();
        double serverPa = normalizePa( serverSolution.get( 0 ).doubleValue() );

        String sequencePath = resolveCaptureSequence();

        if( sequencePath != null ) {
            File rot = new File( sequencePath + ".rot" );
            if( rot.exists() ) {
                try {
                    FileInputStream in = new FileInputStream( rot );
                    try {
                        byte[] buffer = new byte[ 4096 ];
                        int len = in.read(buffer);
                        int customPa = Integer.parseInt( new String( buffer, 0, len ).trim() );

                        logMessage( "Using custom pa " + customPa + " instead of " + serverPa );
                        return customPa;
                    }
                    finally {
                        in.close();
                    }
                }
                catch( Throwable t ) {
                    logError( "Failed to read rotation file", t);
                }
            }
        }

        return serverPa;
    }

    public boolean checkStartCapture() {
        //check if we have to start the capture
        if( this.captureRunning.get() == false ) {
            logMessage( "Server is guiding, but no capture is running" );

            int pendingJobCount = this.capture.methods.getPendingJobCount();
    
            if( pendingJobCount == 0 ) {
                loadSequence();
                pendingJobCount = this.capture.methods.getPendingJobCount();
            }

            if( pendingJobCount > 0 ) {
                logMessage( "Starting aborted capture, pending jobs " + pendingJobCount );
                if( this.startCapture() == false ) {
                    logMessage( "Start of capture was not possible, try loading new sequence and start again later" );
                    loadSequence();
                    return false;
                }
                else {
                    return true;
                }
            }
            else {
                logMessage( "No Jobs to capture in sequence, aborting capture in any way and retry" );
                try {
                    this.capture.methods.abort();
                }
                catch( Throwable t ) {
                    logError( "Aborting capture failed", t);
                }

                return false;
            }            
        }
        else {
            int jobId = this.activeCaptureJob.get();
            long jobStarted = this.activeCaptureJobStarted.get();
            long timeSinceStart = ( System.currentTimeMillis() - jobStarted ) / 1000;

            if( jobId >= 0 ) {
                CaptureDetails job = this.getCaptureDetails(jobId, false);

                if( job.exposure < 5 && timeSinceStart > (job.duration + 300) ) {
                    logMessage( "Job has started " + ( timeSinceStart / 60.0 ) + " minutes ago, but still no progress, aborting and restarting" );
                    stopCapture();
                    return false;
                }

                return true;
            }
            else {
                logMessage( "Capture is running, but got no jobId" );

                if( timeSinceStart > 10 ) {
                    stopCapture();
                    return false;
                }
                else {
                    return true;
                }
            }
        }
    }

    public boolean checkStopCapture() {
        if( this.automationSuspended.get() ) {
            return false;
        }

        //no capture possible, check if we have to abort
        if( this.captureRunning.get() ) {
            final int jobId = activeCaptureJob.get();

            CaptureDetails job = getCaptureDetails(jobId, false);

            //if more than 2 seconds left, or more than 2 seconds exposed
            if( job.timeLeft >= 2.0 && job.exposure >= 2.0 ) {
                logMessage( "Aborting job " + job );
                stopCapture();
                return true;
            }
            else {
                logMessage( "Do not abort job " + job );
                return false;
            }
        }

        return false;
    }

    public boolean executeAlignment() {
        List<Double> serverSolution = serverSolutionResult.get();
        return executePaAlignment( getTargetPa(), serverSolution.get(1), serverSolution.get(2) );
    }
    
    @Override
    public Map<String,Object> statusAction(String[] parts, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String,Object> res = super.statusAction(parts, req, resp);

        Map<String,Object> serverInfo = server.fillStatus( new HashMap<>() );
        serverInfo.put( "alignment", fillAlignment(new HashMap<>(), serverSolutionResult.get() ) );

        res.put( "serverInfo", serverInfo );

        return res;
    }


    public void addActions( Map<String, Action> actions ) {
        super.addActions(actions);

        actions.put( "checkStartCapture", ( parts, req, resp ) -> {
            return this.checkStartCapture();
		} );
        actions.put( "checkStopCapture", ( parts, req, resp ) -> {
			return this.checkStopCapture();
		} );
    }
    
}