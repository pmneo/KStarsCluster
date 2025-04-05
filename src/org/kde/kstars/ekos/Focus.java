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
@DBusInterfaceName("org.kde.kstars.Ekos.Focus")
/*
@DBusProperty(name = "camera", type = String.class, access = Access.READ_WRITE)
@DBusProperty(name = "filterWheel", type = String.class, access = Access.READ_WRITE)
@DBusProperty(name = "filter", type = String.class, access = Access.READ_WRITE)
@DBusProperty(name = "focuser", type = String.class, access = Access.READ_WRITE)
@DBusProperty(name = "HFR", type = Double.class, access = Access.READ)
@DBusProperty(name = "logText", type = String[].class, access = Access.READ)
@DBusProperty(name = "status", type = Focus.FocusState.class, access = Access.READ)
*/
public interface Focus extends DBusInterface {

    public void abort(String trainName);
    public String camera(String trainName);
    public boolean canAutoFocus(String trainName);
    public boolean capture(String trainName, double settleTime);
    public boolean checkFocus(double requiredHFR, String trainName);
    public String filter(String trainName);
    public String filterWheel(String trainName);
    public boolean focusIn(String trainName, int ms);
    public boolean focusOut(String trainName, int ms);
    public String focuser(String trainName);
    public void resetFrame(String trainName);
    public boolean setAutoFocusParameters(String trainName, int boxSize, int stepSize, int maxTravel, double tolerance);
    public boolean setAutoStarEnabled(boolean enable, String trainName);
    public boolean setAutoSubFrameEnabled(boolean enable, String trainName);
    public void setBinning(int binX, int binY, String trainName);

    public void start(String trainName);
    public Object[] status(String trainName);

    public boolean useFullField(String value);

    public static enum FocusState {
        FOCUS_IDLE,
        FOCUS_COMPLETE,
        FOCUS_FAILED,
        FOCUS_ABORTED,
        FOCUS_WAITING,
        FOCUS_PROGRESS,
        FOCUS_FRAMING,
        FOCUS_CHANGING_FILTER
    }
    public static class newStatus extends AbstractStateSignal<FocusState> {
        public newStatus(String _path, Object[] _status) throws DBusException {
            super(_path, FocusState.class, _status );
        }
        public newStatus(String _path, Object[] _status, String focuser) throws DBusException {
            super(_path, FocusState.class, _status );
        }
    }
    public static class newLog extends DBusSignal {
        private final String text;
        public newLog(String _path, String _text) throws DBusException {
            super(_path);
            this.text = _text;
        }
        public String getText() {
            return text;
        }
    }

    public static class newHFR extends DBusSignal {
        private final double HFR;
        private final int position;

        public newHFR(String _path, double _HFR, int _position, boolean b, String focuser) throws DBusException {
            super(_path);
            this.HFR = _HFR;
            this.position = _position;
        }

        public double getHFR() {
            return HFR;
        }
        public int getPosition() {
            return position;
        }
    }
}