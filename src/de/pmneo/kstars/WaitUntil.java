package de.pmneo.kstars;

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
}
