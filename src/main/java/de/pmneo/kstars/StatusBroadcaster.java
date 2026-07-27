package de.pmneo.kstars;

import java.util.LinkedList;

/** Pub/sub for the web UI's live status WebSocket — mirrors {@link SimpleLogger}'s listener pattern. */
public class StatusBroadcaster {

    private static final StatusBroadcaster instance = new StatusBroadcaster();

    public static StatusBroadcaster getInstance() {
        return instance;
    }

    public static interface StatusListener {
        public void statusChanged( String json );
    }

    private final LinkedList<StatusListener> listeners = new LinkedList<>();
    private String lastPayload = null;

    public void addListener( StatusListener l ) {
        synchronized( listeners ) {
            if( listeners.contains( l ) ) {
                return;
            }
            listeners.add( l );
        }

        if( lastPayload != null ) {
            l.statusChanged( lastPayload );
        }
    }

    public void removeListener( StatusListener l ) {
        synchronized( listeners ) {
            listeners.remove( l );
        }
    }

    public void broadcast( String json ) {
        lastPayload = json;

        synchronized( listeners ) {
            for( StatusListener l : listeners ) {
                l.statusChanged( json );
            }
        }
    }
}
