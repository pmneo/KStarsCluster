package de.pmneo.kstars;

import java.util.concurrent.TimeUnit;

import de.pmneo.kstars.utils.CachedValue;
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

		isParked.forceUpdate();
    }

	public void park() {
		indi.methods.setSwitch( this.deviceName, "CAP_PARK", "PARK", "On" );   
		indi.methods.setSwitch( this.deviceName, "CAP_PARK", "UNPARK", "Off" );   
		this.indi.methods.sendProperty( deviceName, "CAP_PARK" );

		isParked.forceUpdate();
    }


	private final CachedValue<Boolean> isParked = new CachedValue<Boolean>(
			() -> "on".equalsIgnoreCase( indi.methods.getSwitch( this.deviceName, "CAP_PARK", "PARK" ) ),
			TimeUnit.MINUTES.toMillis( 5 ) );
	public boolean isParked() {
		return isParked.get();
	}
}
