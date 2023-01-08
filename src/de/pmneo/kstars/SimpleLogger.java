package de.pmneo.kstars;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOError;
import java.io.IOException;
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

    private static class FileLogger implements LogListener {

        private final SimpleDateFormat fnf = new SimpleDateFormat( "YYYY-MM-dd" );
	
        private String fileDate = "init";
        private FileOutputStream logFile = null;

        public synchronized FileOutputStream getLogFile() throws IOException {
            String now = fnf.format( new Date() );

            if( now.equals( fileDate ) == false ) {
                if( logFile != null ) {
                    logFile.flush();
                    logFile.close();
                }

                fileDate = now;

                logFile = new FileOutputStream( "./KStarsLog_" + fileDate + ".log", true );
            }

            return logFile;
        }

        @Override
        public void logMessage(String message) {
            try {
                FileOutputStream out = getLogFile();
                out.write( message.getBytes( "UTF-8" ) );
                out.write( '\n' );
            }
            catch( Throwable t ) {
                //SILENT_CATCH
            }
        }

    }

    static {
        getLogger().addListener( new FileLogger() );
    }

    public static interface LogListener {
        public void logMessage( String message );
    }

    private LinkedList<String> logHistory = new LinkedList<>();

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

            synchronized( listeners ) {
                for( LogListener l : listeners ) {
                    l.logMessage( log );
                }
            }

            return log;
        }
        catch( Throwable tt ) {
            //SILENT_CATCH

            return "Something went terrible wrong";
        }
	}

	public void logMessage( Object message ) {
		System.out.println( log( message, null ) );
	}

    public void logDebug( Object message ) {
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
