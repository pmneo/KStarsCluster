package de.pmneo.kstars;

import org.kde.kstars.INDI;

public class IndiCap extends IndiDevice {

    public IndiCap(String deviceName, Device<INDI> indi) {
        super(deviceName, indi);
        //eager: needed by unparkCap()/parkCap(), called from checkServerState() every 5s
        watch( "CAP_PARK" );
    }

    public void unpark() {
        indi.methods.setSwitch( this.deviceName, "CAP_PARK", "PARK",   "Off" );
        indi.methods.setSwitch( this.deviceName, "CAP_PARK", "UNPARK", "On"  );
        this.indi.methods.sendProperty( deviceName, "CAP_PARK" );
    }

    public void park() {
        indi.methods.setSwitch( this.deviceName, "CAP_PARK", "PARK",   "On"  );
        indi.methods.setSwitch( this.deviceName, "CAP_PARK", "UNPARK", "Off" );
        this.indi.methods.sendProperty( deviceName, "CAP_PARK" );
    }

    public boolean isParked() {
        return getProperty( "CAP_PARK" ).isOn( "PARK" );
    }
}
