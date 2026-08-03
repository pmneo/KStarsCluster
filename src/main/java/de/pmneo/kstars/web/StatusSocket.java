package de.pmneo.kstars.web;

import de.pmneo.kstars.StatusBroadcaster;
import de.pmneo.kstars.StatusBroadcaster.StatusListener;
import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;

@ServerEndpoint("/status/")
public class StatusSocket implements StatusListener {
    private Session session;

    @OnOpen
    public void onOpen(Session session)
    {
        this.session = session;
        StatusBroadcaster.getInstance().addListener( this );
    }

    @OnClose
    public void onClose(CloseReason close)
    {
        this.session = null;
        StatusBroadcaster.getInstance().removeListener( this );
    }

    @OnMessage
    public void onMessage( String message ) {
        //keep-alive pings from the client — nothing to do
    }

    @OnError
    public void onError( Throwable t ) {
    }

    @Override
    public void statusChanged(String json) {
        if (this.session != null) {
            this.session.getAsyncRemote().sendText(json, res -> {
                if( res.getException() != null ) {
                    res.getException().printStackTrace();
                }
            } );
        }
    }
}
