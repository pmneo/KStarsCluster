package de.pmneo.kstars;

import org.kde.kstars.INDI;
import org.kde.kstars.INDI.IpsState;

public class IndiCamera extends IndiDevice {

    public IndiCamera(String deviceName, Device<INDI> indi) {
        super(deviceName, indi);
        //eager: needed by checkCameraCooling(), called from the Mount/Scheduler status
        //signal handlers. Everything else (CCD_TEMPERATURE, CCD_CONTROLS, ...) is only
        //read by the HTTP status endpoint and is watched lazily on first use.
        watch( "CCD_COOLER" );
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
        }
    }

    public double getCcdTemparatur() {
        return getProperty( "CCD_TEMPERATURE" ).getNumber( "CCD_TEMPERATURE_VALUE" );
    }
    public IpsState getCcdTemparaturState() {
        return getProperty( "CCD_TEMPERATURE" ).state;
    }

    public void setCcdTemparatur( double value ) {
        this.setNumber( "CCD_TEMPERATURE", "CCD_TEMPERATURE_VALUE", value );
    }

    public boolean isCooling() {
        return getProperty( "CCD_COOLER" ).isOn( "COOLER_ON" );
    }
    public void setCooling( boolean value ) {
        try {
            if( value ) {
                indi.methods.setSwitch( this.deviceName, "CCD_COOLER", "COOLER_ON", "On" );
                indi.methods.setSwitch( this.deviceName, "CCD_COOLER", "COOLER_OFF", "Off" );
            }
            else {
                indi.methods.setSwitch( this.deviceName, "CCD_COOLER", "COOLER_ON", "Off" );
                indi.methods.setSwitch( this.deviceName, "CCD_COOLER", "COOLER_OFF", "On" );
            }
            this.indi.methods.sendProperty( deviceName, "CCD_COOLER" );
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
        setNumber( "CCD_BINNING", "HOR_BIN", value );
        setNumber( "CCD_BINNING", "VER_BIN", value );
    }

    public void resetFrameSettings() {
        setSwitch( "CCD_FRAME_RESET", "RESET", "On" );
    }

    public void setGain( int gain ) {
        setNumber( "CCD_CONTROLS", "Gain", gain );
    }
    public int getGain() {
        return (int) getProperty( "CCD_CONTROLS" ).getNumber( "Gain" );
    }
}
