package de.pmneo.kstars;

import org.kde.kstars.INDI;

public class IndiLightBox extends IndiDevice {

    public IndiLightBox(String deviceName, Device<INDI> indi) {
        super(deviceName, indi);
    }

    public void lightOff() {
        logMessage( "Request light off for " + deviceName );
        indi.methods.setSwitch( this.deviceName, "FLAT_LIGHT_CONTROL", "FLAT_LIGHT_ON",  "Off" );
        indi.methods.setSwitch( this.deviceName, "FLAT_LIGHT_CONTROL", "FLAT_LIGHT_OFF", "On"  );
        this.indi.methods.sendProperty( deviceName, "FLAT_LIGHT_CONTROL" );
    }

    public void lightOn() {
        logMessage( "Request light on for " + deviceName );
        indi.methods.setSwitch( this.deviceName, "FLAT_LIGHT_CONTROL", "FLAT_LIGHT_ON",  "On"  );
        indi.methods.setSwitch( this.deviceName, "FLAT_LIGHT_CONTROL", "FLAT_LIGHT_OFF", "Off" );
        this.indi.methods.sendProperty( deviceName, "FLAT_LIGHT_CONTROL" );
    }

    public boolean isLightOn() {
        return getProperty( "FLAT_LIGHT_CONTROL" ).getSwitch( "FLAT_LIGHT_ON" );
    }
}
