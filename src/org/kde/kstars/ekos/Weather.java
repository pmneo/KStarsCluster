package org.kde.kstars.ekos;

import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.annotations.DBusProperty;
import org.freedesktop.dbus.annotations.DBusProperty.Access;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.messages.DBusSignal;

/**
 * Auto-generated class.
 */
@DBusInterfaceName("org.kde.kstars.Ekos.Weather")
@DBusProperty(name = "logText", type = String[].class, access = Access.READ)
@DBusProperty(name = "status", type = Weather.WeatherState.class, access = Access.READ)
public interface Weather extends DBusInterface {
    public static enum WeatherState {
        WEATHER_IDLE, WEATHER_OK, WEATHER_WARNING, WEATHER_ALERT
    }
    public static class newStatus extends AbstractStateSignal<WeatherState> {
		public newStatus(String _path, Object[] _status) throws DBusException {
			super(_path, WeatherState.class, _status );
		}
	}
   
    public static class newLog extends DBusSignal {

        private final String text;

        public newLog(String _path, String _interfaceName, String _text) throws DBusException {
            super(_path, _interfaceName);
            this.text = _text;
        }

        public String getText() {
            return text;
        }
    }
}