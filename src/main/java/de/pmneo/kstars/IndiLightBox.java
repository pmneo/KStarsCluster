package de.pmneo.kstars;

import org.kde.kstars.INDI;

import java.util.concurrent.TimeUnit;

public class IndiLightBox extends IndiDevice {
    public IndiLightBox(String deviceName, Device<INDI> indi) {
        super(deviceName, indi);
    }

	public void lightOff() {
		indi.methods.setSwitch( this.deviceName, "FLAT_LIGHT_CONTROL", "FLAT_LIGHT_ON", "Off" );   
		indi.methods.setSwitch( this.deviceName, "FLAT_LIGHT_CONTROL", "FLAT_LIGHT_OFF", "On" );   
		this.indi.methods.sendProperty( deviceName, "FLAT_LIGHT_CONTROL" );

		lastUpdate = 0;
    }

	public void lightOn() {
		indi.methods.setSwitch( this.deviceName, "FLAT_LIGHT_CONTROL", "FLAT_LIGHT_ON", "On" );   
		indi.methods.setSwitch( this.deviceName, "FLAT_LIGHT_CONTROL", "FLAT_LIGHT_OFF", "Off" );   
		this.indi.methods.sendProperty( deviceName, "FLAT_LIGHT_CONTROL" );

		lastUpdate = 0;
    }

	private long lastUpdate = 0;
	private boolean isLightOn = false;
	public boolean isLightOn() {
		if( lastUpdate + TimeUnit.MINUTES.toMillis( 5 ) < System.currentTimeMillis() ) {
			lastUpdate = System.currentTimeMillis();
			isLightOn = "on".equalsIgnoreCase( indi.methods.getSwitch( this.deviceName, "FLAT_LIGHT_CONTROL", "FLAT_LIGHT_ON" ) );
		}

		return isLightOn;
	}
}
