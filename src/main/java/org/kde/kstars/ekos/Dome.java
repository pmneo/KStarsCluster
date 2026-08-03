package org.kde.kstars.ekos;

import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.annotations.DBusProperty;
import org.freedesktop.dbus.annotations.DBusProperty.Access;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;

/**
 * Auto-generated class.
 */
@DBusInterfaceName("org.kde.kstars.INDI.Dome")
@DBusProperty(name = "canPark", type = Boolean.class, access = Access.READ)
@DBusProperty(name = "canAbsoluteMove", type = Boolean.class, access = Access.READ)
@DBusProperty(name = "canRelativeMove", type = Boolean.class, access = Access.READ)
@DBusProperty(name = "canAbort", type = Boolean.class, access = Access.READ)
@DBusProperty(name = "isMoving", type = Boolean.class, access = Access.READ)
@DBusProperty(name = "connected", type = Boolean.class, access = Access.READ)

@DBusProperty(name = "shutterStatus", type = Dome.ShutterStatus.class, access = Access.READ)
@DBusProperty(name = "status", type = Dome.DomeState.class, access = Access.READ)
@DBusProperty(name = "parkStatus", type = Dome.ParkStatus.class, access = Access.READ)
@DBusProperty(name = "position", type = Double.class, access = Access.READ_WRITE)
@DBusProperty(name = "name", type = String.class, access = Access.READ)
public interface Dome extends DBusInterface {

    public static enum DomeState
    {
        DOME_IDLE,
        DOME_MOVING_CW,
        DOME_MOVING_CCW,
        DOME_TRACKING,
        DOME_PARKING,
        DOME_UNPARKING,
        DOME_PARKED,
        DOME_ERROR
    }

    public static enum ShutterStatus
    {
        SHUTTER_UNKNOWN,
        SHUTTER_OPEN,
        SHUTTER_CLOSED,
        SHUTTER_OPENING,
        SHUTTER_CLOSING,
        SHUTTER_ERROR
    } 

    public static enum DomeDirection
    {
        DOME_CW,
        DOME_CCW
    }

    public static enum DomeMotionCommand
    {
        MOTION_START,
        MOTION_STOP
    }

    public static enum ParkStatus {
		PARK_UNKNOWN,
		PARK_PARKED,
		PARK_PARKING,
		PARK_UNPARKING,
		PARK_UNPARKED,
		PARK_ERROR      
	}

    public boolean abort();
    public boolean park();
    public boolean unpark();
    public boolean isParked();


    public static class newStatus extends AbstractStateSignal<DomeState> {
		public newStatus(String _path, Object[] _status) throws DBusException {
			super(_path, DomeState.class, _status );
		}
	}
    public static class newParkStatus extends AbstractStateSignal<ParkStatus> {
		public newParkStatus(String _path, Object[] _status) throws DBusException {
			super(_path, ParkStatus.class, _status );
		}
    }
}