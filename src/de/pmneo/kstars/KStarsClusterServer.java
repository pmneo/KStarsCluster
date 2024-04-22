package de.pmneo.kstars;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.freedesktop.dbus.exceptions.DBusException;
import org.kde.kstars.ekos.Align.AlignState;
import org.kde.kstars.ekos.Capture.CaptureStatus;
import org.kde.kstars.ekos.Focus.FocusState;
import org.kde.kstars.ekos.Guide.GuideStatus;
import org.kde.kstars.ekos.Mount.MeridianFlipStatus;
import org.kde.kstars.ekos.Mount.MountStatus;
import org.kde.kstars.ekos.Mount.ParkStatus;
import org.kde.kstars.ekos.Scheduler.SchedulerState;
import org.kde.kstars.ekos.Weather.WeatherState;

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
    
    private Thread serverWorker = null;

    public void ekosReady() {
        super.ekosReady();

        synchronized( this ) {
            if( serverWorker == null || serverWorker.isAlive() == false ) {
                serverWorker = new Thread( () -> {
                    while( true ) {
                        try {
                            if( ekosReady.get() ) {
                                checkServerState();
                            }
                            Thread.sleep( 5000 );
                        }
                        catch( Throwable t ) {
                            logError( "Error in check server state", t);
                        }
                    }
                }, "serverWorker" );
                serverWorker.start();
            }
        }

        loadSchedule();
    }

    protected void loadSchedule() {

        if( loadSchedule != null && loadSchedule.isEmpty() == false ) {
            File f = new File( loadSchedule );
            if( f.exists() ) {

                SchedulerState status = (SchedulerState) scheduler.read( "status" );
                if( status == SchedulerState.SCHEDULER_IDLE ) {
                    try {
                        f = f.getCanonicalFile();
                    }
                    catch( IOException e ) {
                        //ignore
                    }
                    logMessage( "loading schedule " + f.getPath() );
                    try {
                        scheduler.methods.loadScheduler( f.getPath() );
                    }
                    catch( Throwable t ) {
                        logError( "Failed to load schedule", t );
                    }
                    sleep(1000L);                   
                }
                else {
                    logMessage( "Scheduler is not idle: " + status );
                }
            }
            else {
                logMessage( "Scheduler File does not exists: " + f.getPath() );
            }
        }
        else {
            logMessage( "No Scheduler File provided" );
        }
    }

    protected synchronized void checkServerState() {
        try {
            
            if( this.weatherState.get() != WeatherState.WEATHER_ALERT && this.schedulerState.get() != SchedulerState.SCHEDULER_RUNNING ) {
                if( automationSuspended.get() == false ) {
                    logMessage( "Weather is OK, Starting idle scheduler" );
                    if( ensureMountIsParked() ) {
                        scheduler.methods.start();
                    }
                    else {
                        logMessage( "Weather is OK, but Mount is not yet parked" );
                    }
                }
                else {
                    logMessage( "Weather is OK, but automatio is suspended" );
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
        
        final Map<String,Object> payload = new HashMap<>();
        payload.put( "action", "handleAlignStatus" );
        payload.put( "solutionResult", getSolutionResult() );
        payload.put( "status", state);

        writeToAllClients( payload );

        return state;
    }

    protected List<Double> getSolutionResult() {
        try {
            List<Double> result = this.align.methods.getSolutionResult();
            System.out.println( "Solution results: " + result );
            return result;
        }
        catch( Throwable t ) {
            logError( "Failed to get pa from align module", t );
            return null;
        }
    }
    
    @Override
    public FocusState handleFocusStatus(FocusState state) {
        state = super.handleFocusStatus(state);
        
        final Map<String,Object> payload = new HashMap<>();
        payload.put( "action", "handleFocusStatus" );
        payload.put( "status", state);

        writeToAllClients( payload );

        return state;
    }
    
    @Override
    public SchedulerState handleSchedulerStatus(SchedulerState state) {
        state = super.handleSchedulerStatus(state);

        checkCameraCooling( this );

        
        final Map<String,Object> payload = new HashMap<>();
        payload.put( "action", "handleSchedulerStatus" );
        payload.put( "status", state);

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
        
        checkCameraCooling( this );

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
    

    protected final Map<String, Map<String, Object> > actionCache = new HashMap<>();
    protected void writeToAllClients( Map<String, Object> payload ) {

        synchronized( actionCache ) {
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

                        synchronized( actionCache ) {
                            for( Map<String,Object> payload : actionCache.values() ) {
                                clientHandler.writeNotNullObject( payload );
                            }
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

    public void addActions( Map<String, Action> actions ) {
        super.addActions(actions);

        final int PARKED = 1;
        final int UNPARKED = 0;
        
        final AtomicBoolean parked = new AtomicBoolean( true );
        actions.put( "roof", ( parts, req, resp ) -> {

            if( parts.length > 1 ) {
                if( parts[1].equals( "park" ) ) {
                    logMessage( "Request dome park" );
                    parked.set( true );
                    ensureHttpClient();
                    try {
                        client.GET( "http://192.168.0.106:8082/simple-api.0/set/0_userdata.0.Roof.CLOSE?value=true" );
                    }
                    catch( Throwable t ) {
                        logError( "Failed to request close roof", t);
                    }
                }
                else if( parts[1].equals( "unpark" ) ) {
                    logMessage( "Request dome unpark" );

                    parked.set( false );

                    ensureHttpClient();
                    try {
                        client.GET( "http://192.168.0.106:8082/simple-api.0/set/0_userdata.0.Roof.OPEN?value=true" );
                    }
                    catch( Throwable t ) {
                        logError( "Failed to request open roof", t);
                    }
                }
            }

            return parked.get() ? PARKED : UNPARKED;

		} );
    }
}
