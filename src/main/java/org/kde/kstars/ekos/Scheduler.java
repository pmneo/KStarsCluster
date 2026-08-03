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

        //constructor MUST be (objectPath, signalArgs...) — the signal carries one string,
        //so exactly two parameters; a third one makes dbus-java drop the signal silently
        public newLog(String _path, String _text) throws DBusException {
            super(_path, _text);
            this.text = _text;
        }

        public String getText() {
            return text;
        }
    }

    public static class jobStarted extends DBusSignal {
        private final String jobName;
        public jobStarted(String _path, String _jobName) throws DBusException {
            super(_path, _jobName);
            this.jobName = _jobName;
        }
        public String getJobName() { return jobName; }
    }

    public static class jobEnded extends DBusSignal {
        private final String jobName;
        private final String endReason;
        public jobEnded(String _path, String _jobName, String _endReason) throws DBusException {
            super(_path, _jobName, _endReason);
            this.jobName = _jobName;
            this.endReason = _endReason;
        }
        public String getJobName() { return jobName; }
        public String getEndReason() { return endReason; }
    }

    public static class schedulerStopped extends DBusSignal {
        public schedulerStopped(String _path) throws DBusException { super(_path); }
    }

    public static class shutdownStarted extends DBusSignal {
        public shutdownStarted(String _path) throws DBusException { super(_path); }
    }

    public static class schedulerPaused extends DBusSignal {
        public schedulerPaused(String _path) throws DBusException { super(_path); }
    }

    public static class schedulerSleeping extends DBusSignal {
        private final boolean shutdown;
        private final boolean sleeping;
        public schedulerSleeping(String _path, boolean _shutdown, boolean _sleeping) throws DBusException {
            super(_path, _shutdown, _sleeping);
            this.shutdown = _shutdown;
            this.sleeping = _sleeping;
        }
        public boolean isShutdown() { return shutdown; }
        public boolean isSleeping() { return sleeping; }
    }

    public static class changeCurrentSequence extends DBusSignal {
        private final String sequenceFileURL;
        public changeCurrentSequence(String _path, String _sequenceFileURL) throws DBusException {
            super(_path, _sequenceFileURL);
            this.sequenceFileURL = _sequenceFileURL;
        }
        public String getSequenceFileURL() { return sequenceFileURL; }
    }

    public static class updateSchedulerURL extends DBusSignal {
        private final String fileURL;
        public updateSchedulerURL(String _path, String _fileURL) throws DBusException {
            super(_path, _fileURL);
            this.fileURL = _fileURL;
        }
        public String getFileURL() { return fileURL; }
    }

    public static class targetDistance extends DBusSignal {
        private final double distance;
        public targetDistance(String _path, double _distance) throws DBusException {
            super(_path, _distance);
            this.distance = _distance;
        }
        public double getDistance() { return distance; }
    }

    public static class changeSleepLabel extends DBusSignal {
        private final String text;
        private final boolean show;
        public changeSleepLabel(String _path, String _text, boolean _show) throws DBusException {
            super(_path, _text, _show);
            this.text = _text;
            this.show = _show;
        }
        public String getText() { return text; }
        public boolean isShow() { return show; }
    }
}