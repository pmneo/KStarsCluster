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
import java.util.concurrent.atomic.AtomicReference;

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

public class KStarsClusterServer extends KStarsCluster {
    protected final ServerSocket serverSocket;
    
    public KStarsClusterServer( int listenPort ) throws IOException, DBusException {
        super( );
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
                            if( isKStarsReady() ) {
                                checkServerState();
                            }
                            Thread.sleep( 100 );
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

                if( scheduler.getParsedProperties().get( "status" ) == SchedulerState.SCHEDULER_IDLE ) {
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
                    logMessage( "starting schedule " + f.getPath() );
                    try {
                        scheduler.methods.start();
                    }
                    catch( Throwable t ) {
                        logError( "Failed to start schedule", t );
                    }
                    
                }
                else {
                    logMessage( "Scheduler is not idle: " + scheduler.getParsedProperties().get( "status" ) );
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

    private final WaitUntil manualSchedulerAbort = new WaitUntil( 5, null ); 
    private AtomicBoolean autoStartScheduler = new AtomicBoolean( true );
    protected synchronized void checkServerState() {
        try {
            if( autoStartScheduler.get() ) {
                if( this.weatherState.get() == WeatherState.WEATHER_OK && this.schedulerState.get() != SchedulerState.SCHEDULER_RUNNING ) {
                    logMessage( "Weather is OK, Starting idle scheduler" );
                    scheduler.methods.start();
                }
            }
        }
        catch( Throwable t ) {
            logError( "Failed to check server state", t );
        }
    }

    protected final AtomicReference< Map<String,Object> > lastCaptureStatus = new AtomicReference< Map<String,Object> >();
    @Override
    protected void handleCaptureStatus(CaptureStatus state) {
        super.handleCaptureStatus(state);
        
        final Map<String,Object> payload = capture.getParsedProperties();
        payload.put( "action", "handleCaptureStatus" );
        lastCaptureStatus.set( payload );
        writeToAllClients( payload );
    }
    
    protected final AtomicReference< Map<String,Object> > lastAlignStatus = new AtomicReference< Map<String,Object> >();
    @Override
    protected void handleAlignStatus(AlignState state) {
        super.handleAlignStatus(state);
        
        List<Double> solutionResult = getSolutionResult();

        final Map<String,Object> payload = align.getParsedProperties();
        payload.put( "action", "handleAlignStatus" );
        payload.put( "solutionResult", solutionResult );
        lastAlignStatus.set( payload );

        writeToAllClients( payload );
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
    
    protected final AtomicReference< Map<String,Object> > lastFocusStatus = new AtomicReference< Map<String,Object> >();
    @Override
    protected void handleFocusStatus(FocusState state) {
        super.handleFocusStatus(state);
        
        final Map<String,Object> payload = focus.getParsedProperties();
        payload.put( "action", "handleFocusStatus" );
        lastFocusStatus.set( payload );
        writeToAllClients( payload );
    }
    
    protected final AtomicReference< SchedulerState > schedulerState = new AtomicReference< SchedulerState >( SchedulerState.SCHEDULER_IDLE );
    protected final AtomicReference< WeatherState > weatherState = new AtomicReference< WeatherState >( WeatherState.WEATHER_IDLE );
    protected final AtomicReference< Map<String,Object> > lastScheduleStatus = new AtomicReference< Map<String,Object> >();
    @Override
    protected void handleSchedulerStatus(SchedulerState state) {
        super.handleSchedulerStatus(state);

        this.schedulerState.set( state );

        switch( state ) {
            case SCHEDULER_IDLE:
                //if scheduler paused and aborted within 5 seconds, disable auto scheduler start
                if( manualSchedulerAbort.check() ) {
                    autoStartScheduler.set( false );
                }
                
                break;

            case SCHEDULER_PAUSED:
                manualSchedulerAbort.reset();
            break;
            
            case SCHEDULER_RUNNING:
                autoStartScheduler.set( true );
            break;

            case SCHEDULER_STARTUP:
            case SCHEDULER_LOADING:
            case SCHEDULER_SHUTDOWN:
            case SCHEDULER_ABORTED:
            default:
                break;
        }

        
        final Map<String,Object> payload = scheduler.getParsedProperties();
        payload.put( "action", "handleSchedulerStatus" );
        lastScheduleStatus.set( payload );
        writeToAllClients( payload );
    }

    protected final AtomicReference< Map<String,Object> > lastScheduleWeatherStatus = new AtomicReference< Map<String,Object> >();
    @Override
    protected void handleSchedulerWeatherStatus(WeatherState state) {
        super.handleSchedulerWeatherStatus(state);
        
        this.weatherState.set( state );

        final Map<String,Object> payload = new HashMap<>();
        payload.put( "action", "handleSchedulerWeatherStatus" );
        payload.put( "status", state );
        lastScheduleWeatherStatus.set( payload );
        writeToAllClients( payload );
    }
    
    protected final AtomicReference< Map<String,Object> > lastGuideStatus = new AtomicReference< Map<String,Object> >();
    protected final AtomicBoolean guideCalibrating = new AtomicBoolean( false );
    @Override
    protected void handleGuideStatus(GuideStatus state) {
        super.handleGuideStatus(state);
        
        final Map<String,Object> payload = guide.getParsedProperties();
        payload.put( "action", "handleGuideStatus" );
        lastGuideStatus.set( payload );
        writeToAllClients( payload );
    }
    
    protected final AtomicReference< Map<String,Object> > lastMountStatus = new AtomicReference< Map<String,Object> >();
    @Override
    protected void handleMountStatus(MountStatus state) {
        super.handleMountStatus(state);
        
        final Map<String, Object> payload = updateLastMountStatus();
        writeToAllClients( payload );
    }

    protected Map<String, Object> updateLastMountStatus() {
        final Map<String,Object> payload = mount.getParsedProperties();
        payload.put( "action", "handleMountStatus" );
        lastMountStatus.set( payload );
        return payload;
    }
    
    @Override
    protected void handleMeridianFlipStatus(MeridianFlipStatus state) {
        super.handleMeridianFlipStatus(state);
        
        updateLastMountStatus();
        
        final Map<String,Object> payload = mount.getParsedProperties();
        payload.put( "action", "handleMeridianFlipStatus" );
        writeToAllClients( payload );
    }
    @Override
    protected void handleMountParkStatus(ParkStatus state) {
        super.handleMountParkStatus(state);
        
        updateLastMountStatus();
        
        final Map<String,Object> payload = mount.getParsedProperties();
        payload.put( "action", "handleMountParkStatus" );
        writeToAllClients( payload );
    }
    
    protected void writeToAllClients( Map<String, Object> payload ) {
        
        payload.remove( "logText" ); 
        
        logMessage( "Sending " + payload.get( "action" ) + ": " + payload.get( "status" ) + " to " + clients.size() + " clients" );
        
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
                        receive( clientHandler, this::clientFrameReceived, (c,t) -> {
                            clients.remove( c );
                        } );
                    } );
                    clients.put( clientHandler, rThread );
                            
                    rThread.start();
                    
                    synchronized ( clientHandler._output ) {
                        clientHandler.writeObject( "Hello Client" );
                        
                        //inform new client about the current state
                        clientHandler.writeNotNullObject( lastScheduleStatus.get() );
                        clientHandler.writeNotNullObject( lastMountStatus.get() );
                        clientHandler.writeNotNullObject( lastAlignStatus.get() );
                        clientHandler.writeNotNullObject( lastFocusStatus.get() );
                        clientHandler.writeNotNullObject( lastGuideStatus.get() );
                        clientHandler.writeNotNullObject( lastCaptureStatus.get() );
                    }
                }
            }
            catch( Throwable t ) {
                logError( "Failed to accept", t );
            }
        }
    }
}
