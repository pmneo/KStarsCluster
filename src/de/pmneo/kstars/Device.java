package de.pmneo.kstars;

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

public class Device<T extends DBusInterface> {

	public final T methods;
	public final String interfaceName;
	
	private final DBusConnection con;
	
	private final Properties properties;
	private final Map<String, Class<?>> dbusProperties;
	private final Map<String, Object> parsedProperties;
	
	public Device( DBusConnection con, String busName, String objectPath, Class<T> impl ) throws DBusException {
		
		this.con = con;
		
		this.methods = con.getRemoteObject( busName, objectPath, impl );
		this.properties = con.getRemoteObject( busName, objectPath, Properties.class );
		this.interfaceName = impl.getAnnotation( DBusInterfaceName.class ).value();

		this.parsedProperties = new HashMap<String, Object>();
		this.parsedProperties.put( "interfaceName", interfaceName );
		dbusProperties = Arrays.stream( impl.getAnnotationsByType( DBusProperty.class ) ).collect( Collectors.toMap( p -> p.name(), p->p.type() ) );
	}
	
	public <S extends DBusSignal> void addSigHandler(Class<S> _type, DBusSigHandler<S> _handler) throws DBusException {
		con.<S>addSigHandler( _type, this.methods,status -> {
			synchronized( this ) {
				this.readAll();
				_handler.handle( status );
			}
		} );
	}
	
	private void parseProperty( String key, Variant<?> value ) {
		Class<?> p = dbusProperties.get( key );
		
		Object v = value.getValue();
		
		if( v != null && v.getClass().isArray() ) {
			v = ArrayUtils.arrayToList( v );
		}
		
		if( p != null && p.isEnum() && v instanceof List ) {
			@SuppressWarnings("unchecked")
			final int s = ( ( List<Number> ) v ).get(0).intValue();
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
		return parsedProperties;
	}
	
	public synchronized Object read( String name ) {
		final Map<String,Variant<?>> all = this.properties.Get( interfaceName, name );
		all.forEach( this::parseProperty );
		return this.parsedProperties.get( name );
	}
	
	public synchronized Map<String, Object> getParsedProperties() {
		return new HashMap<String, Object>( parsedProperties );
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
