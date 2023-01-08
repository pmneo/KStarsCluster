package de.pmneo.kstars;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

	public final T methods;
	public final String interfaceName;
	public final String objectPath;
	
	private final DBusConnection con;
	
	private final Properties properties;
	private final Map<String, Class<?>> dbusProperties;
	private final Map<String, Object> parsedProperties;
	
	public Device( DBusConnection con, String busName, String objectPath, Class<T> impl ) throws DBusException {
		
		this.con = con;
		
		this.objectPath = objectPath;
		
		this.methods = con.getRemoteObject( busName, objectPath, impl );
		this.properties = con.getRemoteObject( busName, objectPath, Properties.class );
		this.interfaceName = impl.getAnnotation( DBusInterfaceName.class ).value();

		this.parsedProperties = new HashMap<String, Object>();
		this.parsedProperties.put( "interfaceName", interfaceName );
		dbusProperties = Arrays.stream( impl.getAnnotationsByType( DBusProperty.class ) ).collect( Collectors.toMap( p -> p.name(), p->p.type() ) );
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
				try {
					this.readAll();
				}
				catch( Throwable t ) {
					t.printStackTrace();
				}
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
	
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public void determineAndDispatchCurrentState() {
		Map<String,Object> payload = readAll();
		
		if( this.newStateSignal != null ) {
			try {
				Enum status = (Enum) payload.get( "status" );
				
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
	private void parseProperty( String key, Variant<?> value ) {
		Class<?> p = dbusProperties.get( key );
		
		Object v = value.getValue();
		
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
			this.parsedProperties.put( name, value );
		}
		return this.parsedProperties.get( name );
	}
	
	public synchronized Map<String, Object> getParsedProperties() {
		return new HashMap<String, Object>( parsedProperties );
	}
	
	public synchronized Object write( String name, Object value ) {
		this.properties.Set( interfaceName, name, value );
		return this.read( name );
	}
	
	
	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder();
		
		sb.append( interfaceName ).append( ": " );
		getParsedProperties().forEach( (k,v) -> {
			sb.append( "\n\t" ).append( k ).append( ": " ).append( v );
		});
		
		return sb.toString();
	}
}
