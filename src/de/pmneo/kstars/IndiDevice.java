package de.pmneo.kstars;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

import org.freedesktop.dbus.errors.ServiceUnknown;
import org.kde.kstars.INDI;
import org.kde.kstars.INDI.IpsState;

public class IndiDevice {
    public final String deviceName;
    public final Device< INDI > indi;
    

	public static String findFirstDevice( Device< INDI > indi, int ofInterface) {
		for( String device : indi.methods.getDevices() ) {
			int driverInterface = Integer.parseInt( indi.methods.getText( device, "DRIVER_INFO", "DRIVER_INTERFACE" ) );

			if( ( driverInterface & ofInterface ) != 0 ) {
				return device;
			}
		}
		return null;
	}

    public IndiDevice( String deviceName, Device<INDI> indi ) {
        this.deviceName = deviceName;
        this.indi = indi;
    }

    private final SimpleDateFormat sdf = new SimpleDateFormat( "[HH:mm:ss.SSS] " );
	
	public void logMessage( Object message ) {
		System.out.println( sdf.format( new Date() ) + message );
	}
	
	public void logError( Object message, Throwable t ) {
		System.err.println( sdf.format( new Date() ) + message );
		
		if( t != null ) {
			t.printStackTrace();
		}
	}
	

    private AtomicBoolean running = new AtomicBoolean( false );
    private Thread workerThread = null;
    public synchronized void start() {
        if( this.workerThread != null && this.workerThread.isAlive() ) {
            return;
        }

        this.running.set( true );

        this.workerThread = new Thread( () -> {
            try {
                while( this.running.get() ) {
                    try {
                        workerLoop();
                        Thread.sleep( 1000L );
                    }
                    catch( ServiceUnknown su ) {
                        //will be thrown, if KStars is not available
                    } 
                    catch( Throwable t ) {
                        logError( "Error in processing device " + deviceName, t );
                    }
                }
            }
            finally {
                this.workerThread = null;
            }
        }, this.deviceName + " Worker" );

        this.workerThread.start();
    }

    protected void workerLoop() {
    }

    public double getNumber( String property, String numberName ) {
        return indi.methods.getNumber(deviceName, property, numberName );
    }
    public void setNumber( String property, String numberName, double value ) {
        this.indi.methods.setNumber( deviceName, property, numberName, value );
		this.indi.methods.sendProperty( deviceName, property );
    }
    public IpsState getPropertyState( String property ) {
		return IpsState.get( this.indi.methods.getPropertyState( deviceName, property ) );
	}
}
