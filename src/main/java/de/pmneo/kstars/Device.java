package de.pmneo.kstars;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.annotations.DBusProperty;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.interfaces.DBusSigHandler;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.messages.DBusSignal;
import org.freedesktop.dbus.types.Variant;
import org.kde.kstars.ekos.AbstractStateSignal;

public class Device<T extends DBusInterface> {

	/**
	 * Health hook for the D-Bus freeze watchdog: every synchronous remote call made
	 * through {@link #methods} or the properties proxy reports its outcome here.
	 */
	public static interface DBusHealthListener {
		public void onSuccess();
		public void onFailure( Throwable t );
	}

	private static volatile DBusHealthListener healthListener = null;
	public static void setHealthListener( DBusHealthListener l ) {
		healthListener = l;
	}

	/**
	 * In-flight call registry: which synchronous D-Bus call is running on which
	 * thread right now, plus the last completed call per interface. Dumped by the
	 * freeze watchdog to answer "which call was in flight when KStars froze".
	 */
	private static final class CallInfo {
		final String desc;
		final long at;
		CallInfo( String desc, long at ) {
			this.desc = desc;
			this.at = at;
		}
	}
	private static final Map<String, CallInfo> inFlightCalls = new ConcurrentHashMap<>();
	private static final Map<String, CallInfo> lastCompletedCalls = new ConcurrentHashMap<>();

	public static String dumpCallRegistry() {
		final long now = System.currentTimeMillis();
		final StringBuilder sb = new StringBuilder();

		sb.append( "in-flight:" );
		if( inFlightCalls.isEmpty() ) {
			sb.append( " none" );
		}
		for( Map.Entry<String, CallInfo> e : inFlightCalls.entrySet() ) {
			sb.append( "\n\t" ).append( e.getValue().desc )
			  .append( " on thread " ).append( e.getKey() )
			  .append( ", running since " ).append( now - e.getValue().at ).append( "ms" );
		}

		sb.append( "\n\tlast completed:" );
		for( Map.Entry<String, CallInfo> e : lastCompletedCalls.entrySet() ) {
			sb.append( "\n\t" ).append( e.getValue().desc )
			  .append( ", " ).append( now - e.getValue().at ).append( "ms ago" );
		}

		return sb.toString();
	}

	public final Class<T> impl;
	public final String interfaceName;
	public final String busName;
	public final String objectPath;
	
	private final DBusConnection con;
	
	private final Map<String, Class<?>> dbusProperties;
	private final Map<String, Object> parsedProperties;
	
	public T methods;
	/** Unwrapped dbus-java proxy — required for addSigHandler/removeSigHandler, which resolve the object path by proxy identity. */
	private T rawMethods;
	private Properties properties;
	
	public Device( DBusConnection con, String busName, String objectPath, Class<T> impl ) throws DBusException {
		this( con, busName, objectPath, impl, d -> {
			String property = d.dbusProperties.containsKey( "status" ) ? "status" : d.dbusProperties.keySet().stream().filter( p -> p.toLowerCase().endsWith( "status" ) ).findFirst().orElse( "status" );
			Object value =  d.read( property );
			return (Enum<?>) value;
		} );
	}

	private final Function< Device<T>, Enum<?>> readStatus;
	public Device( DBusConnection con, String busName, String objectPath, Class<T> impl, Function< Device<T>, Enum<?>> readStatus ) throws DBusException {
		this.con = con;

		this.impl = impl;
		this.busName = busName;
		this.objectPath = objectPath;

		this.readStatus = readStatus;
		
		this.interfaceName = impl.getAnnotation( DBusInterfaceName.class ).value();

		this.parsedProperties = new ConcurrentHashMap<String, Object>();
		this.parsedProperties.put( "interfaceName", interfaceName );
		this.dbusProperties = Arrays.stream( impl.getAnnotationsByType( DBusProperty.class ) ).collect( Collectors.toMap( p -> p.name(), p->p.type() ) );
	
		this.connect();
	}

	public void checkAlive() {
		readStatus.apply(this);
	}

	public Device<T> connect() throws DBusException {
		this.rawMethods = con.getRemoteObject( busName, objectPath, impl );
		this.methods = monitored( this.rawMethods, impl );
		this.properties = monitored( con.getRemoteObject( busName, objectPath, Properties.class ), Properties.class );

		return this;
	}

	/**
	 * Wraps a remote proxy so every synchronous D-Bus call reports success or
	 * failure to the {@link DBusHealthListener} (freeze watchdog in KStarsCluster).
	 */
	@SuppressWarnings("unchecked")
	private <I> I monitored( final I target, final Class<I> iface ) {
		return (I) Proxy.newProxyInstance( iface.getClassLoader(), new Class<?>[] { iface }, ( proxy, method, args ) -> {
			if( method.getDeclaringClass() == Object.class ) {
				return method.invoke( target, args );
			}

			final String desc = interfaceName + "." + method.getName();
			final String threadName = Thread.currentThread().getName();
			inFlightCalls.put( threadName, new CallInfo( desc, System.currentTimeMillis() ) );

			try {
				Object result = method.invoke( target, args );

				lastCompletedCalls.put( interfaceName, new CallInfo( desc, System.currentTimeMillis() ) );

				DBusHealthListener l = healthListener;
				if( l != null ) {
					l.onSuccess();
				}

				return result;
			}
			catch( InvocationTargetException e ) {
				Throwable cause = e.getCause() != null ? e.getCause() : e;

				DBusHealthListener l = healthListener;
				if( l != null ) {
					l.onFailure( cause );
				}

				throw cause;
			}
			finally {
				inFlightCalls.remove( threadName );
			}
		} );
	}
	
	private Class<? extends AbstractStateSignal<?> > newStateSignal;
	@SuppressWarnings("rawtypes")
	private DBusSigHandler newStateHandler;
	
	public <S extends AbstractStateSignal<?>> Runnable addNewStatusHandler(Class<S> _type, DBusSigHandler<S> _handler) throws DBusException {
		this.newStateSignal = _type;
		this.newStateHandler = _handler;
		
		return this.addSigHandler( _type, _handler );
	}
	
	public <S extends DBusSignal> Runnable addSigHandler(Class<S> _type, DBusSigHandler<S> _handler) throws DBusException {
		// Handlers run directly on dbus-java's signal receiver thread (configured to a
		// SINGLE thread in KStarsCluster). dbus-java 5.x processes method returns on a
		// separate pool, so synchronous KStars calls from a handler are deadlock-free —
		// and one signal thread serializes handlers, capping in-flight synchronous
		// calls from signals at one. An unbounded pool here previously let a signal
		// storm (capture status during autofocus) fire dozens of blocking calls at a
		// busy KStars in parallel and freeze its GUI.
		final DBusSigHandler<S> handler = status -> {
			try {
				_handler.handle( status );
			}
			catch( Throwable t ) {
				SimpleLogger.getLogger().logError( "Unhandled error in signal handler for " + _type.getSimpleName() + " of " + interfaceName, t );
			}
		};

		con.<S>addSigHandler( _type, this.rawMethods, handler );

		return () -> {
			try {
				con.removeSigHandler( _type, this.rawMethods, handler  );
			}
			catch( Throwable t ) {
				SimpleLogger.getLogger().logError( "Failed to remove signal handler for " + _type.getSimpleName() + " of " + interfaceName, t );
			}
		};
	}

	public void determineAndDispatchCurrentState() {
		determineAndDispatchCurrentState( null );
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	public void determineAndDispatchCurrentState( Enum prevState ) {
		if( this.newStateSignal != null ) {
			try {
				Enum status = readStatus.apply(this);
				if( prevState == status ) {
					return;
				}

				Constructor c = this.newStateSignal.getConstructor( String.class, Object[].class );
				AbstractStateSignal s = (AbstractStateSignal) c.newInstance( this.objectPath, new Object[] { Integer.valueOf( status.ordinal() ) } );
				this.newStateHandler.handle( s );
			}
			catch( Throwable t ) {
				SimpleLogger.getLogger().logError( "Failed to determine and dispatch current state of " + interfaceName, t );
			}
		}
	}
	
	@SuppressWarnings("unchecked")
	private void parseProperty( String key, Object v ) {
		Class<?> p = dbusProperties.get( key );
		
		if( v instanceof Variant ) {
			v = ((Variant<?>) v).getValue();
		}

		if( v != null && v.getClass().isArray() ) {
			v = ArrayUtils.arrayToList( v );
		}
		
		if( p != null && p.isEnum() ) {
			int s;
			
			if( v instanceof List ) {
				s = ( ( List<Number> ) v ).get(0).intValue();
			}
			else if( v instanceof Number ) {
				s = ((Number) v).intValue();
			}
			else {
				s = -1;
			}

			final Object[] values = p.getEnumConstants();
	    	if( s < 0 || s >= values.length ) {
	    		v = null;
	    	}
	    	else {
	    		v = values[ s ];
	    	}
		}
		
		parsedProperties.put( key, v );
	}
	
	public Map<String,Object> readAll() {
		final Map<String,Variant<?>> all = this.properties.GetAll( interfaceName );
		all.forEach( this::parseProperty );
		if( all.size() == 0 ) {
			throw new IllegalStateException( "No properties readed" );
		}
		return parsedProperties;
	}
	
	public Object read( String name ) {
		Object value = this.properties.Get( interfaceName, name );
		
		if( value instanceof Map ) {
			@SuppressWarnings("unchecked")
			final Map<String,Variant<?>> all = (Map<String,Variant<?>>) value;
			all.forEach( this::parseProperty );
		}
		else {
			this.parseProperty( name, value );
		}

		return this.parsedProperties.get( name );
	}
	
	public Object write( String name, Object value ) {
		this.properties.Set( interfaceName, name, value );
		return this.read( name );
	}

	@Override
	public String toString() {
		return interfaceName;
	}
}
