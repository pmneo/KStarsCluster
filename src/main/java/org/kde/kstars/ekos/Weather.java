package org.kde.kstars.ekos;

import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.annotations.DBusProperty;
import org.freedesktop.dbus.annotations.DBusProperty.Access;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;

/**
 * Auto-generated class.
 */
@DBusInterfaceName("org.kde.kstars.INDI.Weather")
@DBusProperty(name = "connected", type = Boolean.class, access = Access.READ)
@DBusProperty(name = "refreshPeriod", type = Integer.class, access = Access.READ)
@DBusProperty(name = "status", type = Weather.WeatherState.class, access = Access.READ)
@DBusProperty(name = "data", type = String.class, access = Access.READ)
@DBusProperty(name = "name", type = String.class, access = Access.READ)
public interface Weather extends DBusInterface {
    public static enum WeatherState {
        WEATHER_IDLE, WEATHER_OK, WEATHER_WARNING, WEATHER_ALERT
    }
    public static class newStatus extends AbstractStateSignal<WeatherState> {
		public newStatus(String _path, Object[] _status) throws DBusException {
			super(_path, WeatherState.class, _status );
		}
	}
}