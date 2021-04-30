package org.kde.kstars.ekos;

import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.messages.DBusSignal;

public abstract class AbstractStateSignal<E extends Enum<E>> extends DBusSignal {

    private final E status;

    public AbstractStateSignal(String _path, Class<E> enumClass, Object[] status) throws DBusException {
        super(_path);
        
        if( status == null || status.length != 1 || status[0] instanceof Number == false ) {
        	this.status = null;
    	}
        else {
	    	final int s = ((Number)status[0] ).intValue();
	    	final E[] values = enumClass.getEnumConstants();
	    	if( s < 0 || s >= values.length ) {
	    		this.status = null;
	    	}
	    	else {
	    		this.status = values[ s ];
	    	}
	    }
    }


    public E getStatus() {
        return status;
    }


}