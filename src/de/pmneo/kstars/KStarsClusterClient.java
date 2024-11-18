package de.pmneo.kstars;

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.freedesktop.dbus.exceptions.DBusException;
import org.kde.kstars.ekos.SchedulerJob;
import org.kde.kstars.ekos.Align.AlignState;
import org.kde.kstars.ekos.Capture.CaptureStatus;
import org.kde.kstars.ekos.Focus.FocusState;
import org.kde.kstars.ekos.Guide.GuideStatus;
import org.kde.kstars.ekos.Mount.MountStatus;
import org.kde.kstars.ekos.Scheduler.SchedulerState;
import org.kde.kstars.ekos.Weather.WeatherState;

import com.google.gson.GsonBuilder;

import de.pmneo.kstars.utils.IOUtils;
import de.pmneo.kstars.web.CommandServlet.Action;
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
    }

    public String getClientJobName( String name ) {
        String baseName = name.replaceAll( "[ -/\\\\]", "_" );
        String jobName = baseName + "_" + targetPostFix;
        return jobName;
    }
    
    public File ensureClientSchedule( SchedulerJob job ) {

        String jobName = getClientJobName( job.name );

        File seqFile = new File( System.getProperty("user.home") + "/" + jobName.toLowerCase() + ".esq" );
        File scheduleFile = new File( System.getProperty("user.home") + "/" + jobName.toLowerCase() + ".esl" );

        String scheduleSample = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"+
        "<SchedulerList version='1.6'>\n"+
        "<Profile>Standard</Profile>\n"+
        "<Job>\n"+
        "<Name>{name}</Name>\n"+
        "<Group></Group>\n"+
        "<Coordinates>\n"+
        "<J2000RA>{ra}</J2000RA>\n"+
        "<J2000DE>{dec}</J2000DE>\n"+
        "</Coordinates>\n"+
        "<PositionAngle>{pa}</PositionAngle>\n"+
        "<Sequence>{seq}</Sequence>\n"+
        "<StartupCondition>\n"+
        "<Condition>ASAP</Condition>\n"+
        "</StartupCondition>\n"+
        "<Constraints>\n"+
        "<Constraint>EnforceWeather</Constraint>\n"+
        "</Constraints>\n"+
        "<CompletionCondition>\n"+
        "<Condition>Loop</Condition>\n"+
        "</CompletionCondition>\n"+
        "<Steps>\n"+
        "</Steps>\n"+
        "</Job>\n"+
        "<SchedulerAlgorithm value='1'/>\n"+
        "<ErrorHandlingStrategy value='1'>\n"+
        " <delay>5</delay>\n"+
        "</ErrorHandlingStrategy>\n"+
        "<StartupProcedure>\n"+
        "</StartupProcedure>\n"+
        "<ShutdownProcedure>\n"+
        "</ShutdownProcedure>\n"+
        "</SchedulerList>";

        String scheduleContent = scheduleSample
            .replace( "{name}", jobName )
            .replace( "{ra}", Double.toString( job.targetRA ) )
            .replace( "{dec}", Double.toString( job.targetDEC ) )
            .replace( "{pa}", Double.toString( job.pa ) )
            .replace( "{seq}", seqFile.getAbsolutePath() )
        ; 
        try {
           IOUtils.writeTextContent( scheduleFile, scheduleContent, "UTF-8" );
        }
        catch( Throwable t ) {
            logError( "Failed to write " + scheduleFile.getAbsolutePath(), t );
        }

        try {
            IOUtils.writeTextContent( seqFile, adjustCaptureLength( job.sequenceContent, job.fRatio, calculateFRatio() ), "UTF-8" );
        }
        catch( Throwable t ) {
            logError( "Failed to write " + seqFile.getAbsolutePath(), t );
        }  
        
        return scheduleFile;
    }

    public static String adjustCaptureLength( String sequenceContent, double sourceFRatio, double targetFRatio ) {
        if( sourceFRatio > 0 && targetFRatio > 0 ) {
            final double factor = Math.pow( targetFRatio / sourceFRatio, 2 );

            return Pattern.compile( "<Exposure>([0-9]+)</Exposure>" ).matcher( sequenceContent ).replaceAll( r -> {
            	int e = Integer.parseInt( r.group( 1 ) );
            	int adjusted = (int) Math.round( factor * e );
            	
            	//System.out.println( "Adjusting " + e + " to " + adjusted );
            	
            	return "<Exposure>"+adjusted+"</Exposure>";
            } );
        }

        return sequenceContent;
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
                    
                    SchedulerJob job = (SchedulerJob) payload.get( "job" );

                    server.schedulerActiveJob.set( job );

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
        
    public static enum Stage {
        INIT,
        FOCUS,
        ALIGN,
        CAPTURE
    }

    private Stage stage = Stage.INIT;

    protected void stopAll() {
        this.focus.methods.abort();
        this.align.methods.abort();
        this.scheduler.methods.stop();
    }

    protected synchronized void checkClientState() {
        
        this.updateSchedulerState();

        if( server.mountIsTracking.get() ) {

            SchedulerJob serverJob = server.schedulerActiveJob.get();
            SchedulerJob clientJob = this.schedulerActiveJob.get();

            if( serverJob != null ) {
                String targetJobName = getClientJobName( serverJob.name );
                if( clientJob == null ) {
                    String jsonJobs = (String) this.scheduler.read( "jsonJobs" );

			        SchedulerJob[] loadedJobs = new GsonBuilder().create().fromJson( jsonJobs, SchedulerJob[].class );

                    if( loadedJobs.length == 1 ) {
                        clientJob = loadedJobs[0];

                        if( clientJob != null ) {
                            clientJob.fRatio = calculateFRatio();
                        }
                    }                   
                }

                if( clientJob == null || clientJob.name.equals( targetJobName ) == false ) {
                    logMessage( "Target Job has changed to " + targetJobName + ", stopping scheduler and load scheduler" );

                    clientJob = null;

                    File clientSchedule = ensureClientSchedule( serverJob );

                    this.stopAll();

                    loadSchedule( clientSchedule );

                    serverSolutionChanged.set( true );

                    this.updateSchedulerState();
                }
            }


            if( clientJob == null ) {
                logMessage( "No active client job, waiting ..." );
                return;
            }

            if( server.mountIsTracking.hasChangedAndReset() ) {
                logMessage( "Server mount started tracking" );
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
                    this.stopAll();

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
                        stage = Stage.ALIGN;

                        logMessage( "changing next stage to " + stage );
                    }
                }
                else if( stage == Stage.ALIGN ) {
                    this.stopAll();

                    try {
                        List<Double> serverSolution = serverSolutionResult.get();
                        double targetPa = serverSolution.get(0);

                        executePaAlignment( targetPa, serverSolution.get(1), serverSolution.get(2) );

                        logMessage( "checking pa" );

                        if( checkIfPaInRange( targetPa, 2 ) ) {
                            stage = Stage.CAPTURE;
                            logMessage( "changing next stage to " + stage );
                        }

                    }
                    catch( Throwable t ) {
                        //autofocus failed, this
                        logError( "Align failed", t );
                    }
                }
                else if( stage == Stage.CAPTURE ) {
                    boolean ditheringActive = server.ditheringActive.get();
                    if( server.ditheringActive.hasChangedAndReset() ) {
                        logMessage( "Server dithering has changed: " + ditheringActive );

                        ditheringActive = true; //force abort
                    }

                    if( ditheringActive ) {
                        //checkAbortCapture();
                        switch( this.schedulerState.get() ) {
                            case SCHEDULER_ABORTED: 
                            case SCHEDULER_IDLE:
                            case SCHEDULER_LOADING:
                            case SCHEDULER_PAUSED:
                            case SCHEDULER_SHUTDOWN:
                            case SCHEDULER_STARTUP:
                                //ignore
                                logMessage( "No dithering action: " + this.schedulerState.get() );
                            break;
                            case SCHEDULER_RUNNING:
                                //todo: check capture
                                //no capture possible, check if we have to abort
                                if( this.captureRunning.get() ) {
                                    final int jobId = activeCaptureJob.get();

                                    CaptureDetails job = getCaptureDetails(jobId, false);

                                    //if more than 2 seconds left, or more than 2 seconds exposed
                                    if( job.timeLeft >= 2.0 && job.exposure >= 2.0 ) {
                                        logMessage( "Aborting job " + job );
                                        this.capture.methods.abort();
                                    }
                                    else {
                                        logMessage( "Do not abort job " + job );
                                    }
                                }
                            break;
                        }
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

                        switch( this.schedulerState.get() ) {
                            case SCHEDULER_ABORTED: 
                            case SCHEDULER_IDLE:
                                //starting scheduler
                                logMessage( "Starting scheduler with state " + this.schedulerState.get() );
                                this.scheduler.methods.start();
                            break;
                            
                            case SCHEDULER_LOADING:
                            case SCHEDULER_PAUSED:
                            case SCHEDULER_SHUTDOWN:
                            case SCHEDULER_STARTUP:
                                //ignore
                                logMessage( "Waiting, scheduler state is " + this.schedulerState.get() );
                            break;
                            case SCHEDULER_RUNNING:
                                //OK
                                break;
                        }
                    }
                }
            }
            else if( server.gudingRunning.hasChangedAndReset() ) {
                logMessage( "Server mount stopped guiding > stopping scheduler" );

                this.scheduler.methods.stop();
            }
        }
        else if( server.mountIsTracking.hasChangedAndReset() ) {
            logMessage( "Server mount is not longer in tracking" );
            
            this.stopAll();
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


    public void addActions( Map<String, Action> actions ) {
        super.addActions(actions);
    }
    
}