package de.pmneo.kstars;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
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

	
	public final Class<T> impl;
	public final String interfaceName;
	public final String busName;
	public final String objectPath;
	
	private final DBusConnection con;
	
	private final Map<String, Class<?>> dbusProperties;
	private final Map<String, Object> parsedProperties;
	
	public T methods;
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

		this.parsedProperties = new HashMap<String, Object>();
		this.parsedProperties.put( "interfaceName", interfaceName );
		this.dbusProperties = Arrays.stream( impl.getAnnotationsByType( DBusProperty.class ) ).collect( Collectors.toMap( p -> p.name(), p->p.type() ) );
	
		this.connect();
	}

	public void checkAlive() {
		readStatus.apply(this);
	}

	public Device<T> connect() throws DBusException {
		this.methods = con.getRemoteObject( busName, objectPath, impl );
		this.properties = con.getRemoteObject( busName, objectPath, Properties.class );

		return this;
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
		final DBusSigHandler<S> handler = status -> {
			synchronized( this ) {
				_handler.handle( status );
			}
		};

		con.<S>addSigHandler( _type, this.methods, handler );

		return () -> {
			try {
				con.removeSigHandler( _type, this.methods, handler  );
			}
			catch( Throwable t ) {
				t.printStackTrace();
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
					
				if( c != null ) {
					AbstractStateSignal s = (AbstractStateSignal) c.newInstance( this.objectPath, new Object[] { Integer.valueOf( status.ordinal() ) } );
					this.newStateHandler.handle( s );
				}
			}
			catch( Throwable t ) {
				t.printStackTrace();
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
	
	public synchronized Map<String,Object> readAll() {
		final Map<String,Variant<?>> all = this.properties.GetAll( interfaceName );
		all.forEach( this::parseProperty );
		if( all.size() == 0 ) {
			throw new IllegalStateException( "No properties readed" );
		}
		return parsedProperties;
	}
	
	public synchronized Object read( String name ) {
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
	
	public synchronized Object write( String name, Object value ) {
		this.properties.Set( interfaceName, name, value );
		return this.read( name );
	}
	
	
	@Override
	public String toString() {
		return interfaceName;
	}
}
