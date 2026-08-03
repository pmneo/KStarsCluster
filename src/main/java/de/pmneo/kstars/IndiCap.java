package de.pmneo.kstars;

import org.kde.kstars.INDI;

public class IndiCap extends IndiDevice {

    public IndiCap(String deviceName, Device<INDI> indi) {
        super(deviceName, indi);
    }

    @Override
    public void onPropertyChanged(String propertyName, String json) {
        super.onPropertyChanged(propertyName, json);

        /*
        var p = getProperty(  propertyName );
        logMessage( "onPropertyChanged on " + System.identityHashCode(this) + "/" + propertyName
                + "\n\tjson:   " + json
                + "\n\tparsed: " + p.toJson()
                + "\n\tisParked: " + isParked() + " / isBusy " + isBusy()
        );
         */
    }

    public void unpark() {
        var capPark = getProperty( "CAP_PARK" );
        if( isParked() && capPark.state != INDI.IpsState.IPS_BUSY ) {
            logMessage( "Request cap unpark for " + deviceName );
            capPark.setSwitch( "PARK", false );
            capPark.setSwitch( "UNPARK", true );
            this.setProperty( capPark );
        }
        else if( isParked() ) {
            logMessageOnce( "Cap unpark for " + deviceName + " requested, but CAP_PARK is still BUSY" );
        }
    }

    public void park() {
        var capPark = getProperty( "CAP_PARK" );
        if( !isParked() && !isBusy() ) {
            logMessage( "Request cap park for " + deviceName );
            capPark.state = INDI.IpsState.IPS_BUSY;
            capPark.setSwitch( "PARK", true );
            capPark.setSwitch( "UNPARK", false );
            this.setProperty( capPark );
        }
        else if( !isParked() ) {
            logMessageOnce( "Cap park for " + deviceName + " requested, but CAP_PARK is still BUSY" );
        }
    }

    public boolean isParked() {
        return getProperty( "CAP_PARK" ).getSwitch( "PARK" );
    }

    public boolean isBusy() {
        return getProperty( "CAP_PARK" ).state == INDI.IpsState.IPS_BUSY;
    }
}
