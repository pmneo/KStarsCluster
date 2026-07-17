package org.kde.kstars.ekos;

import java.util.List;

import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.annotations.DBusProperty;
import org.freedesktop.dbus.annotations.DBusProperty.Access;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.messages.DBusSignal;

/**
 * Auto-generated class.
 */
@DBusInterfaceName("org.kde.kstars.Ekos.Guide")
@DBusProperty(name = "camera", type = String.class, access = Access.READ_WRITE)
@DBusProperty(name = "st4", type = String.class, access = Access.READ_WRITE)
@DBusProperty(name = "logText", type = String[].class, access = Access.READ)
@DBusProperty(name = "exposure", type = Double.class, access = Access.READ_WRITE)
@DBusProperty(name = "status", type = Guide.GuideStatus.class, access = Access.READ)
@DBusProperty(name = "axisDelta", type = Double[].class, access = Access.READ)
@DBusProperty(name = "axisSigma", type = Double[].class, access = Access.READ)
public interface Guide extends DBusInterface {
    public boolean connectGuider();
    public boolean disconnectGuider();
    public boolean guide();
    public boolean abort();
    public boolean calibrate();
    public boolean capture();
    public void loop();
    public boolean dither();
    public boolean suspend();
    public boolean resume();
    public void clearCalibration();
    public List<String> getST4Devices();
    public void setImageFilter(String value);
    public void setCalibrationTwoAxis(boolean enable);
    public void setCalibrationAutoStar(boolean enable);
    public void setCalibrationAutoSquareSize(boolean enable);
    public void setDarkFrameEnabled(boolean enable);
    public void setCalibrationPulseDuration(int pulseDuration);
    public void setGuideBoxSizeIndex(int boxSizeIndex);
    public void setGuideAlgorithmIndex(int index);
    public void setSubFrameEnabled(boolean enable);
    public void setDitherSettings(boolean enable, double value);
    public boolean setGuiderType(int guideType);


    public static enum GuideStatus {
		GUIDE_IDLE, 	
		GUIDE_ABORTED, 	
		GUIDE_CONNECTED, 	
		GUIDE_DISCONNECTED, 	
		GUIDE_CAPTURE, 	
		GUIDE_LOOPING,	
		GUIDE_DARK,
		GUIDE_SUBFRAME, 	
		GUIDE_STAR_SELECT, 	
		GUIDE_CALIBRATING, 	
		GUIDE_CALIBRATION_ERROR, 	
		GUIDE_CALIBRATION_SUCESS, 	
		GUIDE_GUIDING, 	
		GUIDE_SUSPENDED, 	
		GUIDE_REACQUIRE, 	
		GUIDE_DITHERING, 	
		GUIDE_MANUAL_DITHERING, 	
		GUIDE_DITHERING_ERROR, 	
		GUIDE_DITHERING_SUCCESS, 	
		GUIDE_DITHERING_SETTLE 	
	}
    public static class newStatus extends AbstractStateSignal<GuideStatus> {
        public newStatus(String _path, Object[] _status) throws DBusException {
            super(_path, GuideStatus.class, _status );
        }
    }

    public static class newAxisDelta extends DBusSignal {
        private final double ra;
        private final double de;

        public newAxisDelta(String _path, double _ra, double _de) throws DBusException {
            super(_path);
            this.ra = _ra;
            this.de = _de;
        }

        public double getRa() {
            return ra;
        }

        public double getDe() {
            return de;
        }
    }

    public static class newAxisSigma extends DBusSignal {

        private final double ra;
        private final double de;

        public newAxisSigma(String _path, double _ra, double _de) throws DBusException {
            super(_path);
            this.ra = _ra;
            this.de = _de;
        }
        public double getRa() {
            return ra;
        }
        public double getDe() {
            return de;
        }
    }

    public static class newLog extends DBusSignal {
        private String text;
        public newLog(String _path, String _text) throws DBusException {
            super(_path);
            this.text = _text;
        }
        public String getText() {
            return text;
        }
    }
}