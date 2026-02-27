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
@DBusInterfaceName("org.kde.kstars.Ekos.Capture")
@DBusProperty(name = "targetName", type = String.class, access = Access.READ_WRITE)
@DBusProperty(name = "observerName", type = String.class, access = Access.READ_WRITE)
@DBusProperty(name = "camera", type = String.class, access = Access.READ_WRITE)
@DBusProperty(name = "filterWheel", type = String.class, access = Access.READ_WRITE)
@DBusProperty(name = "filter", type = String.class, access = Access.READ_WRITE)
@DBusProperty(name = "coolerControl", type = Boolean.class, access = Access.READ_WRITE)
@DBusProperty(name = "logText", type = String[].class, access = Access.READ)
@DBusProperty(name = "status", type = Capture.CaptureStatus.class, access = Access.READ)
public interface Capture extends DBusInterface {
    public void start(String train);
    public void abort(String train);
    public void suspend();
    public void stop();
    public void pause();
    public void toggleSequence();
    public void restartCamera(String name);
    public void toggleVideo(boolean enabled);
    public boolean loadSequenceQueue(String fileURL, String targetName);
    public boolean saveSequenceQueue(String path);
    public void clearSequenceQueue();
    public String getSequenceQueueStatus();
    public void setMaximumGuidingDeviation(boolean enable, double value);
    public void setInSequenceFocus(boolean enable, double HFR);
    public int getJobCount();
    public int getPendingJobCount();
    public String getJobState(int id);
    public int getJobImageProgress(int id);
    public int getJobImageCount(int id);
    public double getJobExposureProgress(int id);
    public double getJobExposureDuration(int id);
    public double getProgressPercentage();
    public int getActiveJobID();
    public int getActiveJobRemainingTime();
    public int getOverallRemainingTime();
    public void clearAutoFocusHFR();
    public void ignoreSequenceHistory();
    public void setCapturedFramesMap(String signature, int count);


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

    public static class newSequenceImage extends DBusSignal {

        private final String filename;

        public newSequenceImage(String _path, String _filename) throws DBusException {
            super(_path);
            this.filename = _filename;
        }


        public String getFilename() {
            return filename;
        }
    }

    public static enum CaptureStatus {
        CAPTURE_IDLE,                /*!< no capture job active */
        CAPTURE_PROGRESS,            /*!< capture job sequence in preparation (temperature, filter, rotator) */
        CAPTURE_CAPTURING,           /*!< CCD capture running */
        CAPTURE_PAUSE_PLANNED,       /*!< user has requested to pause the capture sequence */
        CAPTURE_PAUSED,              /*!< paused capture sequence due to a user request */
        CAPTURE_SUSPENDED,           /*!< capture stopped since some limits are not met, but may be continued if all limits are met again */
        CAPTURE_ABORTED,             /*!< capture stopped by the user or aborted due to guiding problems etc. */
        CAPTURE_WAITING,             /*!< waiting for settling of the mount before start of capturing */
        CAPTURE_IMAGE_RECEIVED,      /*!< image received from the CDD device */
        CAPTURE_DITHERING,           /*!< dithering before starting to capture */
        CAPTURE_FOCUSING,            /*!< focusing before starting to capture */
        CAPTURE_FILTER_FOCUS,        /*!< not used */
        CAPTURE_CHANGING_FILTER,     /*!< preparation event changing the filter */
        CAPTURE_GUIDER_DRIFT,        /*!< preparation event waiting for the guider to settle */
        CAPTURE_SETTING_TEMPERATURE, /*!< preparation event setting the camera temperature */
        CAPTURE_SETTING_ROTATOR,     /*!< preparation event setting the camera rotator */
        CAPTURE_ALIGNING,            /*!< aligning before starting to capture */
        CAPTURE_CALIBRATING,         /*!< startup of guiding running before starting to capture */
        CAPTURE_MERIDIAN_FLIP,       /*!< only used as signal that a meridian flip is ongoing */
        CAPTURE_COMPLETE             /*!< capture job sequence completed successfully */  
	}
    public static class newStatus extends AbstractStateSignal<CaptureStatus> {
        public newStatus(String _path, Object[] _status) throws DBusException {
            super(_path, CaptureStatus.class, _status);
        }
        public newStatus(String _path, Object[] _status, String train, int i) throws DBusException {
            super(_path, CaptureStatus.class, _status);
        }
    }
}