package de.pmneo.kstars;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.freedesktop.dbus.exceptions.DBusException;
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

import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import de.pmneo.kstars.web.CommandServlet.Action;

public class KStarsClusterServer extends KStarsCluster {
    protected final ServerSocket serverSocket;
    
    public KStarsClusterServer( int listenPort ) throws IOException, DBusException {
        super( "Server" );
        serverSocket = new ServerSocket( listenPort );
    }
    
    protected String loadSchedule = "";

    public void setLoadSchedule(String loadSchedule) {
        if( loadSchedule != null ) {
            loadSchedule = loadSchedule.replaceFirst("^~", System.getProperty("user.home"));
        }
        this.loadSchedule = loadSchedule;
    }
    
    public void ekosReady() {
        super.ekosReady();

        loadSchedule();
    }

    @Override
    protected void ekosRunningLoop() {
        try {
            fetchRoofStatus();
            if( ekosReady.get() ) {
                checkServerState();
            }
        }
        catch( Throwable t ) {
            logError( "Error in check server state", t);
        }

        super.ekosRunningLoop();
    }

    protected void loadSchedule() {
        if( loadSchedule != null && loadSchedule.isEmpty() == false ) {
            File f = new File( loadSchedule );
            loadSchedule( f );                
        }
        else {
            logMessage( "No Scheduler File provided" );
        }
    }

    private int schedulerErrors = 0;
    private long schedulerStartetAt = 0;

    protected void checkServerState() {
        try {
            this.updateSchedulerState( );

            if( automationSuspended.get() ) {
                logMessage( "Weather is "+this.weatherState.get()+", but automation is suspended" );
            }
            else if( this.weatherState.get() != WeatherState.WEATHER_ALERT ) {
                switch ( this.schedulerState.get() ) {
                    case SCHEDULER_LOADING:
                    case SCHEDULER_STARTUP:
                    case SCHEDULER_SHUTDOWN:
                        //WAIT
                        logMessage( "Weather is "+this.weatherState.get()+", wait for scheduler get's started: " + this.schedulerState.get() );
                    break;

                    
                    case SCHEDULER_ABORTED:
                    case SCHEDULER_PAUSED:
                    case SCHEDULER_IDLE:
                        long startedDelta = System.currentTimeMillis() - schedulerStartetAt;

                        if( startedDelta < TimeUnit.SECONDS.toMillis( 30 ) ) {
                            schedulerErrors ++;
                            logMessage( "Scheduler start failed within: " + TimeUnit.MILLISECONDS.toSeconds( startedDelta ) + ", failed for " + schedulerErrors + " times" );
                        }
         
                        if( schedulerErrors > 5 ) {
                            logMessage( "Scheduler start failed for " + schedulerErrors + " times. Killing kstars and retry" );
                            schedulerErrors = 0;
                            stopKStars();
                            return;
                        }
                        else {
                            logMessage( "Weather is OK, Starting "+this.schedulerState.get()+" scheduler" );
                            scheduler.methods.start();
                            schedulerStartetAt = System.currentTimeMillis();
                        }
                    break;

                    case SCHEDULER_RUNNING:
                        if( schedulerStartetAt == 0 ) {
                            schedulerStartetAt = System.currentTimeMillis(); //manual start
                        }
                        startedDelta = System.currentTimeMillis() - schedulerStartetAt;

                        SchedulerJob job = schedulerActiveJob.get();
                        if( job != null ) {
                            this.mount.methods.unpark();
                            unparkRoof();    
                        }
                        else {
                            parkRoof();
                        }
                        
                        if( startedDelta > TimeUnit.SECONDS.toMillis( 30 ) ) {
                            //clear errors to 0
                            schedulerErrors = 0;
                        }


                        if( captureStatus.get() == CaptureStatus.CAPTURE_CHANGING_FILTER ) {
                            long delta = TimeUnit.MILLISECONDS.toSeconds( System.currentTimeMillis() - captureStateChangedAt.get() );

                            if( delta >= 15 ) {
                                logMessage( "Changing filter since " + delta + " seconds, abort capture");
                                this.capture.methods.abort(opticalTrain.get());
                            }
                        }
                    break;
                }
            }    
            else {
                parkRoof();

                switch ( this.schedulerState.get() ) {
                    case SCHEDULER_LOADING:
                    case SCHEDULER_SHUTDOWN:
                        //do nothing
                    break;

                    case SCHEDULER_ABORTED:
                    case SCHEDULER_IDLE:
                        ensureMountIsParked();
                    break;

                    case SCHEDULER_PAUSED:
                        logMessage( "Starting paused scheduler, because in this state in can not be stopped" );
                        scheduler.methods.start();
                    break;
                    
                    case SCHEDULER_RUNNING:
                        logMessage( "Stopping running scheduler" );
                        this.stopAll();
                    break;

                    case SCHEDULER_STARTUP:
                        logMessage( "Stopping starting scheduler" );
                        this.stopAll();
                    break;
                }
            }       
        }
        catch( Throwable t ) {
            logError( "Failed to check server state", t );
        }
    }

    


    @Override
    public CaptureStatus handleCaptureStatus(CaptureStatus state) {
        state = super.handleCaptureStatus(state);
        
        final Map<String,Object> payload = new HashMap<>();
        payload.put( "action", "handleCaptureStatus" );
        payload.put( "status", state);
        payload.put( "targetName", capture.read( "targetName" ) );

        writeToAllClients( payload );

        return state;
    }
    
    @Override
    public AlignState handleAlignStatus(AlignState state) {
        state = super.handleAlignStatus(state);

        List<Double> solutionResult = getSolutionResult();
        List<Double> targetCoords = getTargetCoords();

        SchedulerJob job = schedulerActiveJob.get();

        if( job != null && targetCoords != null && targetCoords.size() >= 2 ) {
            double targetRA = targetCoords.get(0);
            double targetDEC = targetCoords.get(1);

            //ra is in ha, convert to deg by 15° per hour
            double raDelta = (job.targetRA * 15 - targetRA * 15) * 3600;
            double decDelta = (job.targetDEC - targetDEC) * 3600;

            double delta = Math.sqrt( Math.pow(raDelta,2) + Math.pow(decDelta,2) );

            logMessage( "Job coordinates = " + job.targetRA + "/" + job.targetDEC + ", alignTargetCoords = " + targetRA + "/" + targetDEC);
            logMessage( "Job target align delta = " + raDelta + "/" + decDelta + ", all = " + delta );

            if( delta > 5 ) { //the target delta is larger then 5 arc seconds
                logMessage( "Adjusting alignment target coordinates from the job: " + job.targetRA + "/" + job.targetDEC );
                this.align.methods.setTargetCoords( job.targetRA, job.targetDEC );
            }

            if( state == AlignState.ALIGN_COMPLETE ) {
                targetRA = solutionResult.get(1) / 15.0;
                targetDEC = solutionResult.get(2);

                //ra is in ha, convert to deg by 15° per hour
                raDelta = (job.targetRA * 15 - targetRA * 15) * 3600;
                decDelta = (job.targetDEC - targetDEC) * 3600;

                delta = Math.sqrt( Math.pow(raDelta,2) + Math.pow(decDelta,2) );

                logMessage( "Solution coordinates = " + targetRA + "/" + targetDEC + ", alignTargetCoords = " + job.targetRA + "/" + job.targetDEC);
                logMessage( "Solution align delta = " + raDelta + "/" + decDelta + ", all = " + delta );

                if( delta > 30 ) { //the target delta is larger then 30 arc seconds
                    logMessage( "Stopping Scheduler, because of to big alignment delta" );
                    this.stopAll();
                }
            }
        }

        

        final Map<String,Object> payload = new HashMap<>();
        payload.put( "action", "handleAlignStatus" );
        payload.put( "solutionResult", solutionResult );
        payload.put( "targetCoords", targetCoords );
        payload.put( "status", state);

        writeToAllClients( payload );

        return state;
    }

    protected List<Double> getSolutionResult() {
        try {
            List<Double> result = this.align.methods.getSolutionResult();
            logMessage( "Solution results: " + result );
            return result;
        }
        catch( Throwable t ) {
            logError( "Failed to get pa from align module", t );
            return null;
        }
    }

        protected List<Double> getTargetCoords() {
        try {
            List<Double> result = this.align.methods.getTargetCoords();
            logMessage( "Target coords: " + result );
            return result;
        }
        catch( Throwable t ) {
            logError( "Failed to get target corrds from align module", t );
            return null;
        }
    }
    
    @Override
    public FocusState handleFocusStatus(FocusState state) {
        state = super.handleFocusStatus(state);
        
        final Map<String,Object> payload = new HashMap<>();
        payload.put( "action", "handleFocusStatus" );
        payload.put( "status", state );

        writeToAllClients( payload );

        return state;
    }
    
    @Override
    public SchedulerState handleSchedulerStatus(SchedulerState state) {
        state = super.handleSchedulerStatus(state);

        if( ekosReady.get() ) {
            checkCameraCooling( this );
        }

        
        final Map<String,Object> payload = new HashMap<>();
        payload.put( "action", "handleSchedulerStatus" );
        payload.put( "status", state );
        payload.put( "job", schedulerActiveJob.get() );

        writeToAllClients( payload );

        return state;
    }

    
    @Override
    public WeatherState handleSchedulerWeatherStatus(WeatherState state) {
        state = super.handleSchedulerWeatherStatus(state);
        
        this.weatherState.set( state );

        final Map<String,Object> payload = new HashMap<>();
        payload.put( "action", "handleSchedulerWeatherStatus" );
        payload.put( "status", state );

        writeToAllClients( payload );

        return state;
    }
    
    protected final AtomicBoolean guideCalibrating = new AtomicBoolean( false );
    
    @Override
    public GuideStatus handleGuideStatus(GuideStatus state) {
        state = super.handleGuideStatus(state);
        
        final Map<String,Object> payload = new HashMap<>();
        payload.put( "action", "handleGuideStatus" );
        payload.put( "status", state);

        writeToAllClients( payload );

        return state;
    }
    
    @Override
    public MountStatus handleMountStatus(MountStatus state) {
        state = super.handleMountStatus(state);
        
        if( ekosReady.get() ) {
            checkCameraCooling( this );
        }

        final Map<String,Object> payload = new HashMap<>();
        payload.put( "action", "handleMountStatus" );
        payload.put( "status", state);

        writeToAllClients( payload );

        return state;
    }

    
    @Override
    public MeridianFlipStatus handleMeridianFlipStatus(MeridianFlipStatus state) {
        state = super.handleMeridianFlipStatus(state);
        
        final Map<String,Object> payload = new HashMap<>();
        payload.put( "action", "handleMeridianFlipStatus" );
        payload.put( "status", state);

        writeToAllClients( payload );

        return state;
    }
    @Override
    public ParkStatus handleMountParkStatus(ParkStatus state) {
        state = super.handleMountParkStatus(state);
        
        final Map<String,Object> payload = new HashMap<>();
        payload.put( "action", "handleMountParkStatus" );
        payload.put( "status", state);

        writeToAllClients( payload );

        return state;
    }
    

    protected final Map<String, Map<String, Object> > actionCache = new ConcurrentHashMap<>();
    protected void writeToAllClients( Map<String, Object> payload ) {
        String action = (String) payload.get( "action" );
        actionCache.put( action, payload );

        payload.remove( "logText" ); 
        logMessage( "Sending " + action + ": " + payload.get( "status" ) + " to " + clients.size() + " clients" );
        for( SocketHandler handler : clients.keySet() ) {
            try {
                handler.writeObject( payload );
            } catch (IOException e) {
                logError( "Failed to inform client " + handler, e );
            }
        }        
    }
    
    protected ConcurrentHashMap< SocketHandler, Thread > clients = new ConcurrentHashMap<SocketHandler, Thread>();
    
    protected void clientFrameReceived( SocketHandler client, Object frame ) {
        //this should currently not happen?
        logMessage( "Received Client Frame: " + frame );
    }

    public void listen() {
        while( true ) {
            try {
                logMessage( "Listen" );
                
                final Socket client = this.serverSocket.accept();
                
                if( client != null ) {
                    logMessage( "Client from " + client.getRemoteSocketAddress() + " connected" );
                    
                    final SocketHandler clientHandler = new SocketHandler( client );
                    final Thread rThread = new Thread( () -> {
                        clientHandler.receive( this::clientFrameReceived, (c,t) -> {
                            logMessage( "Client " + client.getRemoteSocketAddress() + " disconnected" );
                            clients.remove( c );
                        } );
                    } );
                    clients.put( clientHandler, rThread );
                            
                    rThread.start();
                    
                    synchronized ( clientHandler._output ) {
                        clientHandler.writeObject( "Begin init" );

                        for( Map<String,Object> payload : actionCache.values() ) {
                            clientHandler.writeNotNullObject( payload );
                        }

                        clientHandler.writeObject( "Init done" );
                    }
                }
            }
            catch( Throwable t ) {
                logError( "Failed to accept", t );
            }
        }
    }

    public static enum RoofStatus {
        PARKED(1),
        UNPARKING(1),
        UNPARKED(0), //0
        PARKING(0),
        IDLE(0)
        ;//1

        private final int indiStatus;
        private RoofStatus( int indiStatus ) {
            this.indiStatus = indiStatus;
        }
    }

    protected final AtomicReference<RoofStatus> roofStatus = new AtomicReference<>( RoofStatus.IDLE );

    protected static class MapList extends TypeToken< List<Map<String,Object>> > {}

    protected RoofStatus fetchRoofStatus() {
        RoofStatus roofStatus = this.roofStatus.get();
        
        String statusJson = null;
        Throwable lastError = null;

        for( int i=0;i<10;i++) {
            try {
                //status = Integer.valueOf( client.GET( "http://192.168.0.106:8087/getPlainValue/0_userdata.0.Roof.indiStatus" ).getContentAsString() );
                statusJson = client.GET( "http://192.168.0.106:8087/getBulk/0_userdata.0.Roof.isFullyOpen,0_userdata.0.Roof.isFullyClosed,0_userdata.0.Roof.status" ).getContentAsString();
                lastError = null;
                break;
            }
            catch( Throwable t ) {
                lastError = t;
            }
        }

        try {
            if( lastError != null ) {
                throw lastError;
            }
        
            List<Map<String,Object>> parsedData = new GsonBuilder().create().fromJson( statusJson, new MapList().getType() );
            Map<String,Object> data = parsedData.stream().collect( Collectors.toMap( e -> (String)e.get( "id"), e -> e.get( "val" ) ) );

            String status = (String)data.get( "0_userdata.0.Roof.status" );
            Boolean isFullyOpen = (Boolean)data.get( "0_userdata.0.Roof.isFullyOpen" );
            Boolean isFullyClosed = (Boolean)data.get( "0_userdata.0.Roof.isFullyClosed" );

            if( isFullyClosed ) {
                roofStatus = RoofStatus.PARKED;
            }
            else if( isFullyOpen ) {
                roofStatus = RoofStatus.UNPARKED;
            }
            else if( "opening".equals( status ) ) {
                roofStatus = RoofStatus.UNPARKING;
            }
            else if( "closing".equals( status ) ) {
                roofStatus = RoofStatus.PARKING;
            }
            else {
                logError( "Unexpected roof status " + status, null );
                roofStatus = RoofStatus.IDLE;
            }
        }
        catch( Throwable t ) {
            logError( "Failed to get roof status", t);
        }

        RoofStatus oldState = this.roofStatus.getAndSet( roofStatus );

        if( oldState != roofStatus ) {
            logMessage( "Roof status changed from " + oldState + " to " + roofStatus );
        }

        return RoofStatus.PARKED;
    }

    protected void unparkRoof() {
        switch( roofStatus.get() ) {
            case IDLE:
            case PARKED:
            case PARKING:
                //UNPARK
                logMessage( "Request dome unpark, weather status = " + weatherState.get() );
                try {
                    client.GET( "http://192.168.0.106:8087/set/0_userdata.0.Roof.OPEN?value=true" );
                }
                catch( Throwable t ) {
                    logError( "Failed to request open roof", t);
                }
            break;
            default:
                break;
        }
    }

    protected void parkRoof() {
        switch( roofStatus.get() ) {
            case IDLE:
            case UNPARKED:
            case UNPARKING:
                //PARK
                logMessage( "Request dome park, weather status = " + weatherState.get() );
                try {
                    client.GET( "http://192.168.0.106:8087/set/0_userdata.0.Roof.CLOSE?value=true" );
                }
                catch( Throwable t ) {
                    logError( "Failed to request close roof", t);
                }
            break;
            default:
                break;
        }
    }


    public void addActions( Map<String, Action> actions ) {
        super.addActions(actions);

        actions.put( "roof", ( parts, req, resp ) -> {
            if( parts.length > 1 ) {
                if( parts[1].equals( "park" ) ) {
                    parkRoof();
                }
                else if( parts[1].equals( "unpark" ) ) {
                    this.mount.methods.unpark();
                    unparkRoof();
                }
            }
            return roofStatus.get().indiStatus;
		} );
    }
}
