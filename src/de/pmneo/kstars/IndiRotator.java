package de.pmneo.kstars;

import org.kde.kstars.INDI;
import org.kde.kstars.INDI.IpsState;

public class IndiRotator extends IndiDevice {

    public IndiRotator(String deviceName, Device<INDI> indi) {
        super(deviceName, indi);
    }


    /*
	'ABS_ROTATOR_ANGLE.ANGLE',
	'SYNC_ROTATOR_ANGLE.ANGLE',
 	*/

	public double getRotatorPosition() {
		return getNumber( "ABS_ROTATOR_ANGLE", "ANGLE" );
	}
	public IpsState getRotatorPositionStatus() {
		return getPropertyState("ABS_ROTATOR_ANGLE" );
	}
	protected void setRotatorPosition( double pos ) {
        setNumber( "ABS_ROTATOR_ANGLE", "ANGLE", pos );
	}
    
}
