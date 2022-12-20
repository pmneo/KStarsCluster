package de.pmneo.kstars;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;


public class SimpleLogger {
    private final SimpleDateFormat sdf = new SimpleDateFormat( "[HH:mm:ss.SSS] " );
	
    private static SimpleLogger instance = new SimpleLogger();

    public static SimpleLogger getLogger() {
        return instance;
    }

    public static interface LogListener {
        public void logMessage( String message );
    }

    public SimpleLogger() {
        new Thread( () -> {
            while( true ) {
                try {
                    synchronized( logsToSend ) {
                        if( logsToSend.size() == 0 ) {
                            logsToSend.wait();
                        }

                        synchronized( listeners ) {
                            for( LogListener l : listeners ) {
                                l.logMessage( logsToSend.removeFirst() );
                            }
                        }
                    }
                }
                catch( Throwable t ) {
                    //SILENT
                }
            }
        }, "logSender").start();
    }

    private LinkedList<String> logHistory = new LinkedList<>();
    private LinkedList<String> logsToSend = new LinkedList<>();

    protected String log( Object message, Throwable t ) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            final PrintStream s = new PrintStream( out, false, "UTF-8" );
            s.print( sdf.format( new Date() ) );
            s.print( message );
            
            if( t != null ) {
                s.println();
                t.printStackTrace( s );
            }

            s.flush();

            String log = out.toString( "UTF-8" );

            synchronized( logHistory ) {
                logHistory.addLast( log );
                while( logHistory.size() > 100 ) {
                    logHistory.removeFirst();
                }
            }

            synchronized( logsToSend ) {
                logsToSend.add( log );
                logsToSend.notify();
            }

            return log;
        }
        catch( Throwable tt ) {
            //SILENT_CATCH

            return "Somithing went terrible wrong";
        }
	}

	public void logMessage( Object message ) {
		System.out.println( log( message, null ) );
	}
	
	public void logError( Object message, Throwable t ) {
		System.err.println( log( message, t ) );
	}

    private LinkedList< LogListener > listeners = new LinkedList<>();

    public void addListener( LogListener l  ) {
        synchronized( listeners ) {
            if( listeners.contains( l ) ) {
                return;
            }

            listeners.add( l );
        }

        synchronized( logHistory ) { 
            for( String log : logHistory ) {
                l.logMessage( log );
            }
        }
    }
    public void removeListener( LogListener l ) {
        synchronized( listeners ) {
            listeners.remove( l );
        }
    } 
}
