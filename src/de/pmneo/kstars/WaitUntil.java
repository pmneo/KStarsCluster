package de.pmneo.kstars;

import java.util.concurrent.locks.Condition;
import java.util.function.Predicate;

public class WaitUntil {
    private long endTime = -1;
    private long maxWaitInSeconds = -1;
    private final String timeoutMessage;
    public WaitUntil( long maxWaitInSeconds, String timeoutMessage ) {
        this.timeoutMessage = timeoutMessage;
        this.reset( maxWaitInSeconds );
    }

    public void reset( long maxWaitInSeconds ) {
        this.maxWaitInSeconds = maxWaitInSeconds;
        reset();
    }

    public void reset() {
        endTime = System.currentTimeMillis() + ( maxWaitInSeconds * 1000 );
    }

    public boolean elapsed() {
        return !check();
    }

    public boolean check() {
        if( System.currentTimeMillis() < endTime ) {
            return true;
        }
        else {	
            if( timeoutMessage != null ) {
                System.out.println( "Wait timed out: " + timeoutMessage );
            }
            return false;
        }
    }


    public static interface WaitCondition {
        public boolean isInvalid();
    }
    public static boolean waitUntil( String timeoutMessage, int maxWait, WaitCondition condition ) {
        final WaitUntil waitUntil = new WaitUntil( 5, timeoutMessage );
        while( true ) {
            if( waitUntil.check() == false ) {
                return false;
            } 
            if( condition.isInvalid() == false ) {
                return true;
            }

            try {
                Thread.sleep( 100 );
            }
            catch( Throwable t ) {
                //SILENT_CATCH
            }
        }
    }
}
