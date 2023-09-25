package de.pmneo.kstars;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.kde.kstars.INDI;
import org.kde.kstars.INDI.DriverInterface;
import org.kde.kstars.INDI.IpsState;

public class IndiDevice {
    public final String deviceName;
    public final Device< INDI > indi;
    

	public static String findFirstDevice( Device< INDI > indi, DriverInterface ofInterface) {
		Map<DriverInterface, List<String> > devices = getDevices(indi);
		List<String> devList = devices.get( ofInterface );
		if( devList != null ) {
			return devList.get(0);
		}
		return null;
	}

	public static Map<DriverInterface, List<String> > getDevices( Device< INDI > indi ) {
		Map<DriverInterface, List<String> > devices = new HashMap<>();

		for( String device : indi.methods.getDevices() ) {
			int driverInterface = Integer.parseInt( indi.methods.getText( device, "DRIVER_INFO", "DRIVER_INTERFACE" ) );

			for( DriverInterface ofInterface : DriverInterface.values() ) {
				if( ( driverInterface & ofInterface.id ) == ofInterface.id ) {
					List<String> devList = devices.get( ofInterface );
					if( devList == null ) {
						devices.put( ofInterface, devList = new LinkedList<>() );
					}
					devList.add( device );
				}
			}
		}

		return devices;
	}

    public IndiDevice( String deviceName, Device<INDI> indi ) {
        this.deviceName = deviceName;
        this.indi = indi;
    }

	public void logMessage( Object message ) {
		SimpleLogger.getLogger().logMessage( message );
	}
	
	public void logError( Object message, Throwable t ) {
        SimpleLogger.getLogger().logError( message, t );
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

	public void setSwitch( String property, String switchName, String state ) {
        this.indi.methods.setSwitch( deviceName, property, switchName, state );
		this.indi.methods.sendProperty( deviceName, property );
    }
	public String getSwitch( String property, String switchName ) {
		return this.indi.methods.getSwitch( deviceName, property, switchName );
	}
}

