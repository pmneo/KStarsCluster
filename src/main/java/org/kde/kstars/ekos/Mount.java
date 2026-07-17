package org.kde.kstars.ekos;

import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.annotations.DBusProperty;
import org.freedesktop.dbus.annotations.DBusProperty.Access;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;

/**
 * Auto-generated class.
 */
@DBusInterfaceName("org.kde.kstars.Ekos.Mount")
@DBusProperty(name = "logText", type = String[].class, access = Access.READ)
@DBusProperty(name = "canPark", type = Boolean.class, access = Access.READ)
@DBusProperty(name = "slewStatus", type = Integer.class, access = Access.READ)
@DBusProperty(name = "altitudeLimits", type = Double[].class, access = Access.READ_WRITE)
@DBusProperty(name = "altitudeLimitsEnabled", type = Boolean.class, access = Access.READ_WRITE)
@DBusProperty(name = "hourAngleLimit", type = Double.class, access = Access.READ_WRITE)
@DBusProperty(name = "hourAngleLimitEnabled", type = Boolean.class, access = Access.READ_WRITE)
@DBusProperty(name = "equatorialCoords", type = Double[].class, access = Access.READ)
@DBusProperty(name = "horizontalCoords", type = Double[].class, access = Access.READ)
@DBusProperty(name = "slewRate", type = Integer.class, access = Access.READ_WRITE)
@DBusProperty(name = "telescopeInfo", type = Double[].class, access = Access.READ_WRITE)
@DBusProperty(name = "hourAngle", type = Double.class, access = Access.READ)
@DBusProperty(name = "status", type = Mount.MountStatus.class, access = Access.READ)
@DBusProperty(name = "parkStatus", type = Mount.ParkStatus.class, access = Access.READ)
@DBusProperty(name = "pierSide", type = Mount.PierSide.class, access = Access.READ)
public interface Mount extends DBusInterface {
    public boolean slew(double RA, double DEC);
    public boolean gotoTarget(String target);
    public boolean syncTarget(String target);
    public boolean abort();
    public boolean park();
    public boolean unpark();
    public boolean resetModel();
    public boolean executeMeridianFlip();
    public void setMeridianFlipValues(boolean activate, double hours);

	public static enum MountStatus {
		MOUNT_IDLE, 
		MOUNT_MOVING, 
		MOUNT_SLEWING, 
		MOUNT_TRACKING, 
		MOUNT_PARKING, 
		MOUNT_PARKED, 
		MOUNT_ERROR
	}
	public static class newStatus extends AbstractStateSignal<MountStatus> {
		public newStatus(String _path, Object[] _status) throws DBusException {
			super(_path, MountStatus.class, _status );
		}
	}
	
	public static enum ParkStatus {
		PARK_UNKNOWN,
		PARK_PARKED,
		PARK_PARKING,
		PARK_UNPARKING,
		PARK_UNPARKED,
		PARK_ERROR      
	}
    public static class newParkStatus extends AbstractStateSignal<ParkStatus> {
        public newParkStatus(String _path, Object[] _status) throws DBusException {
            super(_path, ParkStatus.class, _status );
        }
    }

    public static enum PierSide {
    	PIER_UNKNOWN, 	
    	PIER_WEST, 	
    	PIER_EAST 
    }
    public static class pierSideChanged extends AbstractStateSignal<PierSide> {
        public pierSideChanged(String _path, Object[] _status) throws DBusException {
            super(_path, PierSide.class, _status );
        }
    }
    
    public static enum MeridianFlipStatus {
	  FLIP_NONE, 
	  FLIP_PLANNED, 
	  FLIP_WAITING, 
	  FLIP_ACCEPTED,
	  FLIP_RUNNING, 
	  FLIP_COMPLETED, 
	  FLIP_ERROR
	}
    public static class newMeridianFlipStatus extends AbstractStateSignal<MeridianFlipStatus> {
        public newMeridianFlipStatus(String _path, Object[] _status) throws DBusException {
            super(_path, MeridianFlipStatus.class, _status );
        }
    }
}