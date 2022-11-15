package org.kde.kstars.ekos;

import java.util.List;

import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.annotations.DBusProperty;
import org.freedesktop.dbus.annotations.DBusProperty.Access;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.messages.DBusSignal;
import org.freedesktop.dbus.types.UInt32;

/**
 * Auto-generated class.
 */
@DBusInterfaceName("org.kde.kstars.Ekos.Align")
@DBusProperty(name = "camera", type = String.class, access = Access.READ_WRITE)
@DBusProperty(name = "filterWheel", type = String.class, access = Access.READ_WRITE)
@DBusProperty(name = "filter", type = String.class, access = Access.READ_WRITE)
@DBusProperty(name = "logText", type = String[].class, access = Access.READ)
@DBusProperty(name = "status", type = Align.AlignState.class, access = Access.READ)
@DBusProperty(name = "fov", type = Double[].class, access = Access.READ)
@DBusProperty(name = "solverArguments", type = String.class, access = Access.READ_WRITE)
public interface Align extends DBusInterface {
    public void abort();
    public boolean captureAndSolve();
    public boolean loadAndSlew(String fileURL);
    public void setSolverMode(UInt32 mode);
    
    
    /** DBUS interface function.
     * Select Solver Action after successfully solving an image.
     * @param mode 0 for Sync, 1 for Slew To Target, 2 for Nothing (just display solution results)
     */
    public void setSolverAction(int mode);
    public List<Double> cameraInfo();
    public List<Double> telescopeInfo();
    public List<Double> getSolutionResult();
    public int getLoadAndSlewStatus();
    public void setBinningIndex(int binningIndex);
    public void setFOVTelescopeType(int index);
    public void setTargetCoords(double ra, double de);
    public void setTargetRotation(double rotation);
    public void setTargetPositionAngle (double value);

    public static enum AlignState {
    	ALIGN_IDLE, 	
    	ALIGN_COMPLETE, 	
    	ALIGN_FAILED, 	
    	ALIGN_ABORTED,
    	ALIGN_PROGRESS, 	
    	ALIGN_SYNCING, 	
    	ALIGN_SLEWING,
        ALIGN_ROTATING,
        ALIGN_SUSPENDED
    }
    public static class newStatus extends AbstractStateSignal<AlignState> {
        public newStatus(String _path, Object[] _status) throws DBusException {
            super(_path, AlignState.class, _status );
        }
    }

    public static class newSolution extends DBusSignal {
        private final Object[] solution;
        public newSolution(String _path, Object[] _solution) throws DBusException {
            super(_path);
            this.solution = _solution;
        }
        public Object[] getSolution() {
            return solution;
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
}