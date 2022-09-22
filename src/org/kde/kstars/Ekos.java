package org.kde.kstars;

import java.util.List;

import org.freedesktop.dbus.TypeRef;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.annotations.DBusProperty;
import org.freedesktop.dbus.annotations.DBusProperty.Access;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.messages.DBusSignal;
import org.freedesktop.dbus.types.UInt32;
import org.kde.kstars.ekos.AbstractStateSignal;

/**
 * Auto-generated class.
 */
@DBusInterfaceName("org.kde.kstars.Ekos")
@DBusProperty(name = "indiStatus", type = Ekos.CommunicationStatus.class, access = Access.READ)
@DBusProperty(name = "ekosStatus", type = Ekos.CommunicationStatus.class, access = Access.READ)
@DBusProperty(name = "settleStatus", type = UInt32.class, access = Access.READ)
@DBusProperty(name = "ekosLiveStatus", type = Boolean.class, access = Access.READ)
@DBusProperty(name = "logText", type = Ekos.PropertylogTextType.class, access = Access.READ)
public interface Ekos extends DBusInterface {


    public void connectDevices();
    public void disconnectDevices();
    public void start();
    public void stop();
    public List<String> getProfiles();
    public boolean setProfile(String profileName);
    public void setEkosLiveConnected(boolean enabled);
    public void setEkosLiveConfig(boolean onlineService, boolean rememberCredentials, boolean autoConnect);
    public void setEkosLiveUser(String username, String password);
    public void setEkosLoggingEnabled(String name, boolean enabled);


    public static enum CommunicationStatus {
        Idle,
        Pending,
        Success,
        Error
    }

    public static interface PropertylogTextType extends TypeRef<List<String>> {

    }

    public static class indiStatusChanged extends AbstractStateSignal<CommunicationStatus> {
		public indiStatusChanged(String _path, Object[] _status) throws DBusException {
			super(_path, CommunicationStatus.class, _status );
		}
	}

    public static class ekosStatusChanged extends AbstractStateSignal<CommunicationStatus> {
		public ekosStatusChanged(String _path, Object[] _status) throws DBusException {
			super(_path, CommunicationStatus.class, _status );
		}
	}

    public static class settleStatusChanged extends AbstractStateSignal<CommunicationStatus> {
		public settleStatusChanged(String _path, Object[] _status) throws DBusException {
			super(_path, CommunicationStatus.class, _status );
		}
	}

    public static class ekosLiveStatusChanged extends DBusSignal {
        private final boolean status;
        public ekosLiveStatusChanged(String _path, String _interfaceName, boolean _status) throws DBusException {
            super(_path, _interfaceName);
            this.status = _status;
        }
        public boolean getStatus() {
            return status;
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

    public static class newModule extends DBusSignal {
        private final String name;
        public newModule(String _path, String _interfaceName, String _name) throws DBusException {
            super(_path, _interfaceName);
            this.name = _name;
        }
        public String getName() {
            return name;
        }
    }
}