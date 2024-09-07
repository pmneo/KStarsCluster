package de.pmneo.kstars;

import org.kde.kstars.INDI;
import org.kde.kstars.INDI.IpsState;

public class IndiFocuser extends IndiDevice {

    public IndiFocuser(String deviceName, Device<INDI> indi) {
        super(deviceName, indi);
    }
    
    /*
 'FOCUS_MOTION.FOCUS_INWARD',
 'FOCUS_MOTION.FOCUS_OUTWARD',
 'REL_FOCUS_POSITION.FOCUS_RELATIVE_POSITION',
 'ABS_FOCUS_POSITION.FOCUS_ABSOLUTE_POSITION',
 'FOCUS_MAX.FOCUS_MAX_VALUE',
 'FOCUS_ABORT_MOTION.ABORT',
 'FOCUS_SYNC.FOCUS_SYNC_VALUE',
 'FOCUS_REVERSE_MOTION.INDI_ENABLED',
 'FOCUS_REVERSE_MOTION.INDI_DISABLED',
 'FOCUS_BACKLASH_TOGGLE.INDI_ENABLED',
 'FOCUS_BACKLASH_TOGGLE.INDI_DISABLED',
 'FOCUS_BACKLASH_STEPS.FOCUS_BACKLASH_VALUE',
 'FOCUS_SETTLE_BUFFER.SETTLE_BUFFER',
 'FOCUS_TEMPERATURE.TEMPERATURE'
*/

    public double getFocusPosition() {
		return getNumber( "ABS_FOCUS_POSITION", "FOCUS_ABSOLUTE_POSITION" );
	}
	public IpsState getFocusPositionStatus() {
		return getPropertyState( "ABS_FOCUS_POSITION" );
	}
	public void setFocusPosition( double pos ) {
		setNumber( "ABS_FOCUS_POSITION", "FOCUS_ABSOLUTE_POSITION", pos );
	}

	public double getFocusTemperature() {
		return getNumber( "FOCUS_TEMPERATURE", "TEMPERATURE" );
	}
	public IpsState getFocusTemperatureStatus() {
		return getPropertyState( "FOCUS_TEMPERATURE" );
	}
}
