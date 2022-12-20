package de.pmneo.kstars.web;

import java.io.IOException;

import de.pmneo.kstars.SimpleLogger;
import de.pmneo.kstars.SimpleLogger.LogListener;
import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
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

    @Override
    public void logMessage(String message) {
        if (this.session != null)
        {
            try
            {
                this.session.getBasicRemote().sendText( message );
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }
    }
}
