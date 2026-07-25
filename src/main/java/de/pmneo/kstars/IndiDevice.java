package de.pmneo.kstars;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import org.kde.kstars.INDI;
import org.kde.kstars.INDI.DriverInterface;

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

	public static <D extends IndiDevice> Map<String,D> createDevices( Device< INDI > indi, DriverInterface ofInterface, BiFunction<String,Device<INDI>,D> factory ) {
		Map<DriverInterface, List<String> > devices = getDevices(indi);
		List<String> devList = devices.get( ofInterface );
		if( devList != null ) {
			return devList.stream().collect( Collectors.toMap( k -> k, k -> factory.apply( k, indi ) ) );
		}
		return new HashMap<>();
	}

    public IndiDevice( String deviceName, Device<INDI> indi ) {
        this.deviceName = deviceName;
        this.indi = indi;
    }

	public void logMessage( Object message ) {
		SimpleLogger.getLogger().logMessage( message );
	}

	private final java.util.concurrent.atomic.AtomicReference<String> lastMessage = new java.util.concurrent.atomic.AtomicReference<>("");
	public void logMessageOnce( String message ) {
		if( message.equals( lastMessage.getAndSet( message ) ) ) {
			return;
		}
		SimpleLogger.getLogger().logMessage( message );
	}

	public void logError( Object message, Throwable t ) {
        SimpleLogger.getLogger().logError( message, t );
	}

	// -------------------------------------------------------------------------
	// Property watch cache
	// -------------------------------------------------------------------------

	private final ConcurrentHashMap<String, IndiProperty> properties = new ConcurrentHashMap<>();

	/**
	 * Subscribes to a property via INDI.watchProperty and caches its current value —
	 * call this directly from the subclass constructor for every property it reads.
	 * watchProperty returns the value synchronously as compact JSON, so the cache is
	 * primed before the constructor returns; no separate registration pass needed.
	 */
	protected void watch( String property ) {
		try {
			IndiProperty p = IndiProperty.parse( indi.methods.watchProperty( deviceName, property ) );
			if( p != null ) {
				properties.put( property, p );
			}
			else {
				logError( "watchProperty returned no data for " + deviceName + "/" + property + " — property may not exist on this device", null );
				properties.put( property, new IndiProperty( this.deviceName, property ) );
			}
		}
		catch( Throwable t ) {
			logError( "Failed to watch property " + deviceName + "/" + property, t );
		}
	}

	/** Called by KStarsCluster's INDI.propertyValueChanged handler when a watched property of this device changes. */
	public void onPropertyChanged( String propertyName, String json ) {
		IndiProperty p = IndiProperty.parse( json );
		if( p != null ) {
			properties.put( propertyName, p );
		}
	}

	/**
	 * Returns the cached value of a property, watching it on first use if it isn't
	 * cached yet. Constructors only eager-watch properties needed by signal handlers
	 * or the 5s monitor loop; everything else (HTTP status endpoint, scripts, ...) is
	 * watched lazily here on its first actual call.
	 */
	public IndiProperty getProperty( String name ) {
		IndiProperty p = properties.get( name );
		if( p == null ) {
			watch( name );
			p = properties.get( name );
		}
//		if( p == null ) {
//			throw new IllegalStateException( "Property " + name + " of " + deviceName + " is not available (watchProperty returned no data — wrong name?)" );
//		}
		return p;
	}

	public boolean setProperty(IndiProperty prop) {
		return this.indi.methods.setPropertyJSON( this.deviceName, prop.name, prop.toElementsJson() );
	}


	// -------------------------------------------------------------------------
	// Direct D-Bus accessors (unchanged)
	// -------------------------------------------------------------------------

    public void setNumber( String property, String numberName, double value ) {
		setProperty( getProperty( property ).setNumber( numberName, value ) );
    }

	public void setSwitch( String property, String switchName, boolean state ) {
		setProperty( getProperty( property ).setSwitch( switchName, state ) );
	}
}
