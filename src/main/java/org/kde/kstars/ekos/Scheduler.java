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
@DBusInterfaceName("org.kde.kstars.Ekos.Scheduler")
@DBusProperty(name = "profile", type = String.class, access = Access.READ_WRITE)
@DBusProperty(name = "logText", type = String[].class, access = Access.READ)
@DBusProperty(name = "status", type = Scheduler.SchedulerState.class, access = Access.READ)
public interface Scheduler extends DBusInterface {

    public void start();
    public void stop();
    public void removeAllJobs();
    public boolean loadScheduler(String fileURL);
    public void setSequence(String sequenceFileURL);
    public void resetAllJobs();
    
    public static enum SchedulerState {
        SCHEDULER_IDLE,     /*< Scheduler is stopped. */
        SCHEDULER_STARTUP,  /*< Scheduler is starting the observatory up. */
        SCHEDULER_RUNNING,  /*< Scheduler is running. */
        SCHEDULER_PAUSED,   /*< Scheduler is paused by the end-user. */
        SCHEDULER_SHUTDOWN, /*< Scheduler is shutting the observatory down. */
        SCHEDULER_ABORTED,  /*< Scheduler is stopped in error. */
        SCHEDULER_LOADING   /*< Scheduler is loading a schedule. */
    }

    public static class newStatus extends AbstractStateSignal<SchedulerState> {
		public newStatus(String _path, Object[] _status) throws DBusException {
			super(_path, SchedulerState.class, _status );
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