package de.pmneo.kstars;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public class WithLogging {
    private final String logPrefix;

    public WithLogging() {
        this( null );
    }
    public WithLogging( String logPrefix ) {
        this.logPrefix = this.createLogPrefix(  logPrefix );
    }

    protected String createLogPrefix( String logPrefix ) {
        return "[" + Objects.requireNonNullElse( logPrefix, getClass().getSimpleName() ) + "] ";
    }

    private final AtomicReference<String> lastMessage = new AtomicReference<String>("");
    public void logMessageOnce( String message ) {
        if( message.equals( lastMessage.getAndSet( message ) ) ) {
            return;
        }
        SimpleLogger.getLogger().logMessage( logPrefix + message );
    }

    public void logMessage( Object message ) {
        lastMessage.set( "" );
        SimpleLogger.getLogger().logMessage( logPrefix + message );
    }

    public void logDebug( Object message ) {
        SimpleLogger.getLogger().logMessage( logPrefix + message );
    }

    public void logError( Object message, Throwable t ) {
        SimpleLogger.getLogger().logError( logPrefix + message, t );
    }

}
