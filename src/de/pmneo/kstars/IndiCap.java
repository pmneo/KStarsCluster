package de.pmneo.kstars;

import java.util.concurrent.TimeUnit;

import org.kde.kstars.INDI;
import org.kde.kstars.INDI.IpsState;

public class IndiCap extends IndiDevice {

    public IndiCap(String deviceName, Device<INDI> indi) {
        super(deviceName, indi);
    }


	public void unpark() {
		indi.methods.setSwitch( this.deviceName, "CAP_PARK", "PARK", "Off" );   
		indi.methods.setSwitch( this.deviceName, "CAP_PARK", "UNPARK", "On" );   
		this.indi.methods.sendProperty( deviceName, "CAP_PARK" );

		lastUpdate = 0;
    }

	public void park() {
		indi.methods.setSwitch( this.deviceName, "CAP_PARK", "PARK", "On" );   
		indi.methods.setSwitch( this.deviceName, "CAP_PARK", "UNPARK", "Off" );   
		this.indi.methods.sendProperty( deviceName, "CAP_PARK" );

		lastUpdate = 0;
    }


	private long lastUpdate = 0;
	private boolean isParked = false;
	public boolean isParked() {
		if( lastUpdate + TimeUnit.MINUTES.toMillis( 5 ) < System.currentTimeMillis() ) {
			lastUpdate = System.currentTimeMillis();
			isParked = "on".equalsIgnoreCase( indi.methods.getSwitch( this.deviceName, "CAP_PARK", "PARK" ) );
		}

		return isParked;
	}
}
