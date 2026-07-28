package de.pmneo.kstars;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import org.kde.kstars.INDI;
import org.kde.kstars.INDI.DriverInterface;

public class IndiDevice extends WithLogging {
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
		super( deviceName );
        this.deviceName = deviceName;
        this.indi = indi;
    }

	@Override
	protected String createLogPrefix(String logPrefix) {
		return super.createLogPrefix( getClass().getSimpleName() + " / " + logPrefix );
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

	/** Properties older than this get re-watched by {@link #refreshWatches()} — a successful
	 *  re-watch always resets receivedAt, so a property reaching this age means it has neither
	 *  received a propertyValueChanged push nor been re-watched in that long. */
	private static final long REWATCH_AGE_MS = TimeUnit.MINUTES.toMillis( 5 );

	/**
	 * Re-issues watchProperty for every currently cached property older than {@link #REWATCH_AGE_MS}.
	 * KStars can silently drop a client's watch registration for a device/property (e.g. after an
	 * INDI driver restart) without any connection-level error — ekosStatus and the heartbeat stay
	 * fine, so nothing else notices. The cache then freezes forever since we otherwise only ever
	 * refresh it via the pushed propertyValueChanged signal. Call this frequently (e.g. every
	 * minute) as a cheap self-healing check; watchProperty itself only runs for properties that
	 * actually turn out to be stale.
	 */
	public void refreshWatches() {
		long now = System.currentTimeMillis();
		for( var entry : properties.entrySet() ) {
			long age = now - entry.getValue().receivedAt;
			if( age >= REWATCH_AGE_MS ) {
				logMessage( "Property " + entry.getKey() + " is " + TimeUnit.MILLISECONDS.toMinutes( age )
						+ " minutes old, re-watching" );
				watch( entry.getKey() );
			}
		}
	}

	/** Called by KStarsCluster's INDI.propertyValueChanged handler when a watched property of this device changes. */
	public void onPropertyChanged( String propertyName, String json ) {
		IndiProperty p = IndiProperty.parse( json );
		if( p != null ) {
			logMessage( "onPropertyChanged(" + json + ")");
			properties.put( propertyName, p );
		}
		else {
			logError( "Failed to parse property: " + json, null );
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
		try {
			return this.indi.methods.setPropertyJSON(this.deviceName, prop.name, prop.toElementsJson());
		}
		finally {
			var json = this.indi.methods.getPropertyJSON( this.deviceName, prop.name, true );
			if( json != null && !json.isBlank() ) {
				onPropertyChanged(prop.name, json);
			}
		}
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
