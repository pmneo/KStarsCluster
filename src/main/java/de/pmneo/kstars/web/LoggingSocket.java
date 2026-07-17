package de.pmneo.kstars.web;

import de.pmneo.kstars.SimpleLogger;
import de.pmneo.kstars.SimpleLogger.LogListener;
import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;

@ServerEndpoint("/logging/")
public class LoggingSocket implements LogListener {
    private Session session;

    @OnOpen
    public void onOpen(Session session)
    {
        this.session = session;
        SimpleLogger.getLogger().addListener( this );
    }

    @OnClose
    public void onClose(CloseReason close)
    {
        this.session = null;
        SimpleLogger.getLogger().removeListener( this );
    }

    @OnMessage
    public void onMessage( String message ) {
        //System.out.println( "onMessage: " + message );
    }

    @OnError
    public void onError( Throwable t ) {
        //SimpleLogger.getLogger().logError( "onError", t);
    }

    @Override
    public void logMessage(String message) {
        if (this.session != null) {
            this.session.getAsyncRemote().sendText(message, res -> {
                if( res.getException() != null ) {
                    res.getException().printStackTrace();
                }
            } );
        }
    }
}
