package de.pmneo.kstars;

import org.kde.kstars.INDI;
import org.kde.kstars.INDI.IpsState;

public class IndiFocuser extends IndiDevice {

    public IndiFocuser(String deviceName, Device<INDI> indi) {
        super(deviceName, indi);
        //neither is read from a signal handler or the 5s loop — both watched lazily on first use
    }

    public double getFocusPosition() {
        return getProperty( "ABS_FOCUS_POSITION" ).getNumber( "FOCUS_ABSOLUTE_POSITION" );
    }
    public IpsState getFocusPositionStatus() {
        return getProperty( "ABS_FOCUS_POSITION" ).state;
    }
    public void setFocusPosition( double pos ) {
        setNumber( "ABS_FOCUS_POSITION", "FOCUS_ABSOLUTE_POSITION", pos );
    }

    public double getFocusTemperature() {
        return getProperty( "FOCUS_TEMPERATURE" ).getNumber( "TEMPERATURE" );
    }
    public IpsState getFocusTemperatureStatus() {
        return getProperty( "FOCUS_TEMPERATURE" ).state;
    }
}
