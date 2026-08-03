package de.pmneo.kstars;

import org.kde.kstars.INDI;
import org.kde.kstars.INDI.IpsState;

public class IndiRotator extends IndiDevice {

    public IndiRotator(String deviceName, Device<INDI> indi) {
        super(deviceName, indi);
    }

    public double getRotatorPosition() {
        return getProperty( "ABS_ROTATOR_ANGLE" ).getNumber( "ANGLE" );
    }
    public IpsState getRotatorPositionStatus() {
        return getProperty( "ABS_ROTATOR_ANGLE" ).state;
    }
    protected void setRotatorPosition( double pos ) {
        setNumber( "ABS_ROTATOR_ANGLE", "ANGLE", pos );
    }
}
