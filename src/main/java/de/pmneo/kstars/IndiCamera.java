package de.pmneo.kstars;

import org.kde.kstars.INDI;
import org.kde.kstars.INDI.IpsState;

public class IndiCamera extends IndiDevice {

    public IndiCamera(String deviceName, Device<INDI> indi) {
        super(deviceName, indi);
    }

    private double preCoolTemp = -15;

    public void setPreCoolTemp(double preCoolTemp) {
        this.preCoolTemp = preCoolTemp;
    }
    public double getPreCoolTemp() {
        return preCoolTemp;
    }

    public void warm() {
        if( isCooling() ) {
            logMessage( "Warming Camera" );
            this.setCooling( false );
        }
    }

    public void preCool() {
        if( !isCooling() ) {
            logMessage( "Precooling Camera to " + preCoolTemp );
            this.setCcdTemparatur( preCoolTemp );
            this.setCooling( true );
        }
    }

    public double getCcdTemparatur() {
        return getProperty( "CCD_TEMPERATURE" ).getNumber( "CCD_TEMPERATURE_VALUE" );
    }
    public IpsState getCcdTemparaturState() {
        return getProperty( "CCD_TEMPERATURE" ).state;
    }

    public void setCcdTemparatur( double value ) {
        this.setProperty( getProperty( "CCD_TEMPERATURE" )
                .setNumber( "CCD_TEMPERATURE_VALUE", value ) );
    }

    public boolean isCooling() {
        return getProperty( "CCD_COOLER" ).isOn( "COOLER_ON" );
    }
    public boolean isCoolerBusy() {
        return getProperty( "CCD_COOLER" ).state == IpsState.IPS_BUSY;
    }
    public void setCooling( boolean value ) {
        try {
            var prop = getProperty( "CCD_COOLER" );
            if( value ) {
                prop.setSwitch( "COOLER_ON", true );
                prop.setSwitch( "COOLER_OFF", false );
            }
            else {
                prop.setSwitch( "COOLER_ON", false );
                prop.setSwitch( "COOLER_OFF", true );
            }
            this.setProperty( prop );
        }
        catch( Throwable t ) {
            logMessage( "The camera " + deviceName + " does not support cooling" );
        }
    }

    public boolean isAntiDewHeaterOn() {
        return getProperty( "CCD_CONTROLS" ).getNumber( "AntiDewHeater" ) > 0;
    }
    public void setAntiDewHeaterOn( boolean value ) {
        setNumber( "CCD_CONTROLS", "AntiDewHeater", value ? 1 : 0 );
    }

    public double getRampingSlope() {
        return getProperty( "CCD_TEMP_RAMP" ).getNumber( "RAMP_SLOPE" );
    }
    public void setRampingSlope( double value ) {
        setNumber( "CCD_TEMP_RAMP", "RAMP_SLOPE", value );
    }

    public int getBinning() {
        return (int) getProperty( "CCD_BINNING" ).getNumber( "HOR_BIN" );
    }
    public void setBinning( int value ) {
        setProperty(
                getProperty( "CCD_BINNING" )
                        .setNumber("HOR_BIN", value )
                        .setNumber("VER_BIN", value )
        );
    }

    public void resetFrameSettings() {
        setSwitch( "CCD_FRAME_RESET", "RESET", true );
    }

    public void setGain( int gain ) {
        setNumber( "CCD_CONTROLS", "Gain", gain );
    }
    public int getGain() {
        return (int) getProperty( "CCD_CONTROLS" ).getNumber( "Gain" );
    }
}
