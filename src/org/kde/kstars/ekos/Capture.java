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
    public void start();
    public void abort();
    public void suspend();
    public void stop();
    public void pause();
    public void toggleSequence();
    public void restartCamera(String name);
    public void toggleVideo(boolean enabled);
    public boolean loadSequenceQueue(String fileURL);
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
	    CAPTURE_IDLE,                
	    CAPTURE_PROGRESS,            
	    CAPTURE_CAPTURING,           
	    CAPTURE_PAUSE_PLANNED,       
	    CAPTURE_PAUSED,              
	    CAPTURE_SUSPENDED,           
	    CAPTURE_ABORTED,             
	    CAPTURE_WAITING,             
	    CAPTURE_IMAGE_RECEIVED,      
	    CAPTURE_DITHERING,           
	    CAPTURE_FOCUSING,            
	    CAPTURE_FILTER_FOCUS,        
	    CAPTURE_CHANGING_FILTER,     
	    CAPTURE_GUIDER_DRIFT,        
	    CAPTURE_SETTING_TEMPERATURE, 
	    CAPTURE_SETTING_ROTATOR,     
	    CAPTURE_ALIGNING,            
	    CAPTURE_CALIBRATING,         
	    CAPTURE_MERIDIAN_FLIP,       
	    CAPTURE_COMPLETE             
	}
    public static class newStatus extends AbstractStateSignal<CaptureStatus> {
        public newStatus(String _path, Object[] _status) throws DBusException {
            super(_path, CaptureStatus.class, _status);
        }
    }
}