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

    private boolean isWarming = false;
    private boolean warmingHasFinished = false;
    private double warmingSettleTemp = 99;

    private WaitUntil settleTimeout = new WaitUntil( 600, null );

    protected void workerLoop() {
        super.workerLoop();

        synchronized( this ) {
            double ccdTemp = getCcdTemparatur();
            IpsState ccdState = getCcdTemparaturState();

            if( isCooling() ) {
                if( !isAntiDewHeaterOn() && isWarming == false ) {
                    logMessage( "Camera is cooling, enable anti dew heater" );
                    setAntiDewHeaterOn( true );
                }
                
                if( isWarming ) {
                    if( ccdState == IpsState.IPS_OK ) {
                        double tempDelta = Math.abs( warmingSettleTemp - ccdTemp );
                        double maxDelta = 0.6;
                        if( tempDelta <= maxDelta ) { //procceed only if range is within 1.2deg
                            this.warmingSettleTemp = (ccdTemp + 2);
                            logMessage( "Warming temperature settled, next target temp = " + this.warmingSettleTemp );
                            settleTimeout.reset();
                            this.setCcdTemparatur( this.warmingSettleTemp );
                        }
                        else {
                            this.isWarming = false;
                            this.warmingHasFinished = false;
                            logMessage( "Warming aborted, delta is more than "+maxDelta+": " + tempDelta );
                        }
                    }
                    else if( settleTimeout.elapsed() ) {
                        logMessage( "Warming finished" );
                        this.isWarming = false;
                        this.warmingHasFinished = true;
                        this.setCooling( false );
                    }
                }
                else if( this.warmingHasFinished ) {
                    logMessage( "Resetting warming has finished" );
                    this.warmingHasFinished = false;
                }
            }
            else {
                if( isAntiDewHeaterOn() ) {
                    logMessage( "Camera is warming, disable anti dew heater" );
                    setAntiDewHeaterOn( false );
                }

                checkWarming( ccdTemp );

                if( this.isWarming ) {
                    logMessage( "Camera is warming, but was set off - restarting warming procedure" );
                    this.setCcdTemparatur( this.warmingSettleTemp );
                }
            }
        }
    }

    public synchronized void checkWarming( double ccdTemp ) {
        if( this.warmingHasFinished == false && this.isWarming == false ) {
            this.isWarming = true;
            this.warmingSettleTemp = (ccdTemp + 2);

            logMessage( "Warming detected, begin warm up procedure with next target temp = " + this.warmingSettleTemp );
            settleTimeout.reset();
            this.setCcdTemparatur( this.warmingSettleTemp );
        }
    }

    public void warm() {
        if( isCooling() ) {
            logMessage( "Warming Camera" );
            checkWarming( getCcdTemparatur() );
        }
    }

    public synchronized void preCool() {
        if( !isCooling() || isWarming ) {
            logMessage( "Precooling Camera to " + preCoolTemp );
            this.isWarming = false;
            this.warmingHasFinished = false;
            this.setCcdTemparatur( preCoolTemp );
        }
    }

    public double getCcdTemparatur() {
        return getNumber( "CCD_TEMPERATURE", "CCD_TEMPERATURE_VALUE" );
    }
    public IpsState getCcdTemparaturState() {
        return getPropertyState( "CCD_TEMPERATURE" );
    }

    public void setCcdTemparatur( double value ) {
        this.setNumber( "CCD_TEMPERATURE", "CCD_TEMPERATURE_VALUE", value );
    }

    public boolean isCooling() {
        String coolerOn = indi.methods.getSwitch( deviceName, "CCD_COOLER", "COOLER_ON" );
        if( "On".equals( coolerOn ) ) {
            return true;
        }
        else {
            return false;
        }
    }
    public void setCooling( boolean value ) {
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

    public boolean isAntiDewHeaterOn() {
        return getNumber( "CCD_CONTROLS","AntiDewHeater" ) > 0;
    }
    public void setAntiDewHeaterOn( boolean value ) {
        setNumber( "CCD_CONTROLS", "AntiDewHeater", value ? 1 : 0 );
    }

    public double getRampingSlope() {
        return getNumber( "CCD_TEMP_RAMP","RAMP_SLOPE" );
    }
    public void setRampingSlope( double value ) {
        setNumber( "CCD_TEMP_RAMP", "RAMP_SLOPE", value );
    }




    /*
"ZWO CCD ASI2600MM Pro", "CONNECTION", "CONNECT",
 "ZWO CCD ASI2600MM Pro", "CONNECTION", "DISCONNECT",
 "ZWO CCD ASI2600MM Pro", "DRIVER_INFO", "DRIVER_NAME",
 "ZWO CCD ASI2600MM Pro", "DRIVER_INFO", "DRIVER_EXEC",
 "ZWO CCD ASI2600MM Pro", "DRIVER_INFO", "DRIVER_VERSION",
 "ZWO CCD ASI2600MM Pro", "DRIVER_INFO", "DRIVER_INTERFACE",
 "ZWO CCD ASI2600MM Pro", "POLLING_PERIOD", "PERIOD_MS",
 "ZWO CCD ASI2600MM Pro", "DEBUG", "ENABLE",
 "ZWO CCD ASI2600MM Pro", "DEBUG", "DISABLE",
 "ZWO CCD ASI2600MM Pro", "SIMULATION", "ENABLE",
 "ZWO CCD ASI2600MM Pro", "SIMULATION", "DISABLE",
 "ZWO CCD ASI2600MM Pro", "CONFIG_PROCESS", "CONFIG_LOAD",
 "ZWO CCD ASI2600MM Pro", "CONFIG_PROCESS", "CONFIG_SAVE",
 "ZWO CCD ASI2600MM Pro", "CONFIG_PROCESS", "CONFIG_DEFAULT",
 "ZWO CCD ASI2600MM Pro", "CONFIG_PROCESS", "CONFIG_PURGE",
 "ZWO CCD ASI2600MM Pro", "ACTIVE_DEVICES", "ACTIVE_TELESCOPE",
 "ZWO CCD ASI2600MM Pro", "ACTIVE_DEVICES", "ACTIVE_ROTATOR",
 "ZWO CCD ASI2600MM Pro", "ACTIVE_DEVICES", "ACTIVE_FOCUSER",
 "ZWO CCD ASI2600MM Pro", "ACTIVE_DEVICES", "ACTIVE_FILTER",
 "ZWO CCD ASI2600MM Pro", "ACTIVE_DEVICES", "ACTIVE_SKYQUALITY",
 "ZWO CCD ASI2600MM Pro", "CCD_EXPOSURE", "CCD_EXPOSURE_VALUE",
 "ZWO CCD ASI2600MM Pro", "CCD_ABORT_EXPOSURE", "ABORT",
 "ZWO CCD ASI2600MM Pro", "CCD_FRAME", "X",
 "ZWO CCD ASI2600MM Pro", "CCD_FRAME", "Y",
 "ZWO CCD ASI2600MM Pro", "CCD_FRAME", "WIDTH",
 "ZWO CCD ASI2600MM Pro", "CCD_FRAME", "HEIGHT",
 "ZWO CCD ASI2600MM Pro", "CCD_FRAME_RESET", "RESET",
 "ZWO CCD ASI2600MM Pro", "CCD_BINNING", "HOR_BIN",
 "ZWO CCD ASI2600MM Pro", "CCD_BINNING", "VER_BIN",
 "ZWO CCD ASI2600MM Pro", "FITS_HEADER", "FITS_OBSERVER",
 "ZWO CCD ASI2600MM Pro", "FITS_HEADER", "FITS_OBJECT",
 "ZWO CCD ASI2600MM Pro", "CCD_TEMPERATURE", "CCD_TEMPERATURE_VALUE",
 "ZWO CCD ASI2600MM Pro", "CCD_TEMP_RAMP", "RAMP_SLOPE",
 "ZWO CCD ASI2600MM Pro", "CCD_TEMP_RAMP", "RAMP_THRESHOLD",
 "ZWO CCD ASI2600MM Pro", "CCD_TRANSFER_FORMAT", "FORMAT_FITS",
 "ZWO CCD ASI2600MM Pro", "CCD_TRANSFER_FORMAT", "FORMAT_NATIVE",
 "ZWO CCD ASI2600MM Pro", "CCD_INFO", "CCD_MAX_X",
 "ZWO CCD ASI2600MM Pro", "CCD_INFO", "CCD_MAX_Y",
 "ZWO CCD ASI2600MM Pro", "CCD_INFO", "CCD_PIXEL_SIZE",
 "ZWO CCD ASI2600MM Pro", "CCD_INFO", "CCD_PIXEL_SIZE_X",
 "ZWO CCD ASI2600MM Pro", "CCD_INFO", "CCD_PIXEL_SIZE_Y",
 "ZWO CCD ASI2600MM Pro", "CCD_INFO", "CCD_BITSPERPIXEL",
 "ZWO CCD ASI2600MM Pro", "CCD_COMPRESSION", "INDI_ENABLED",
 "ZWO CCD ASI2600MM Pro", "CCD_COMPRESSION", "INDI_DISABLED",
 "ZWO CCD ASI2600MM Pro", "CCD1", "CCD1",
 "ZWO CCD ASI2600MM Pro", "CCD_FRAME_TYPE", "FRAME_LIGHT",
 "ZWO CCD ASI2600MM Pro", "CCD_FRAME_TYPE", "FRAME_BIAS",
 "ZWO CCD ASI2600MM Pro", "CCD_FRAME_TYPE", "FRAME_DARK",
 "ZWO CCD ASI2600MM Pro", "CCD_FRAME_TYPE", "FRAME_FLAT",
 "ZWO CCD ASI2600MM Pro", "SCOPE_INFO", "FOCAL_LENGTH",
 "ZWO CCD ASI2600MM Pro", "SCOPE_INFO", "APERTURE",
 "ZWO CCD ASI2600MM Pro", "WCS_CONTROL", "WCS_ENABLE",
 "ZWO CCD ASI2600MM Pro", "WCS_CONTROL", "WCS_DISABLE",
 "ZWO CCD ASI2600MM Pro", "UPLOAD_MODE", "UPLOAD_CLIENT",
 "ZWO CCD ASI2600MM Pro", "UPLOAD_MODE", "UPLOAD_LOCAL",
 "ZWO CCD ASI2600MM Pro", "UPLOAD_MODE", "UPLOAD_BOTH",
 "ZWO CCD ASI2600MM Pro", "UPLOAD_SETTINGS", "UPLOAD_DIR",
 "ZWO CCD ASI2600MM Pro", "UPLOAD_SETTINGS", "UPLOAD_PREFIX",
 "ZWO CCD ASI2600MM Pro", "CCD_FAST_TOGGLE", "INDI_ENABLED",
 "ZWO CCD ASI2600MM Pro", "CCD_FAST_TOGGLE", "INDI_DISABLED",
 "ZWO CCD ASI2600MM Pro", "CCD_FAST_COUNT", "FRAMES",
 "ZWO CCD ASI2600MM Pro", "CCD_VIDEO_STREAM", "STREAM_ON",
 "ZWO CCD ASI2600MM Pro", "CCD_VIDEO_STREAM", "STREAM_OFF",
 "ZWO CCD ASI2600MM Pro", "STREAM_DELAY", "STREAM_DELAY_TIME",
 "ZWO CCD ASI2600MM Pro", "STREAMING_EXPOSURE", "STREAMING_EXPOSURE_VALUE",
 "ZWO CCD ASI2600MM Pro", "STREAMING_EXPOSURE", "STREAMING_DIVISOR_VALUE",
 "ZWO CCD ASI2600MM Pro", "FPS", "EST_FPS",
 "ZWO CCD ASI2600MM Pro", "FPS", "AVG_FPS",
 "ZWO CCD ASI2600MM Pro", "RECORD_STREAM", "RECORD_ON",
 "ZWO CCD ASI2600MM Pro", "RECORD_STREAM", "RECORD_DURATION_ON",
 "ZWO CCD ASI2600MM Pro", "RECORD_STREAM", "RECORD_FRAME_ON",
 "ZWO CCD ASI2600MM Pro", "RECORD_STREAM", "RECORD_OFF",
 "ZWO CCD ASI2600MM Pro", "RECORD_FILE", "RECORD_FILE_DIR",
 "ZWO CCD ASI2600MM Pro", "RECORD_FILE", "RECORD_FILE_NAME",
 "ZWO CCD ASI2600MM Pro", "RECORD_OPTIONS", "RECORD_DURATION",
 "ZWO CCD ASI2600MM Pro", "RECORD_OPTIONS", "RECORD_FRAME_TOTAL",
 "ZWO CCD ASI2600MM Pro", "CCD_STREAM_FRAME", "X",
 "ZWO CCD ASI2600MM Pro", "CCD_STREAM_FRAME", "Y",
 "ZWO CCD ASI2600MM Pro", "CCD_STREAM_FRAME", "WIDTH",
 "ZWO CCD ASI2600MM Pro", "CCD_STREAM_FRAME", "HEIGHT",
 "ZWO CCD ASI2600MM Pro", "CCD_STREAM_ENCODER", "RAW",
 "ZWO CCD ASI2600MM Pro", "CCD_STREAM_ENCODER", "MJPEG",
 "ZWO CCD ASI2600MM Pro", "CCD_STREAM_RECORDER", "SER",
 "ZWO CCD ASI2600MM Pro", "CCD_STREAM_RECORDER", "OGV",
 "ZWO CCD ASI2600MM Pro", "LIMITS", "LIMITS_BUFFER_MAX",
 "ZWO CCD ASI2600MM Pro", "LIMITS", "LIMITS_PREVIEW_FPS",
 "ZWO CCD ASI2600MM Pro", "CCD_COOLER_POWER", "CCD_COOLER_VALUE",
 "ZWO CCD ASI2600MM Pro", "CCD_COOLER", "COOLER_ON",
 "ZWO CCD ASI2600MM Pro", "CCD_COOLER", "COOLER_OFF",
 "ZWO CCD ASI2600MM Pro", "CCD_CONTROLS", "Gain",
 "ZWO CCD ASI2600MM Pro", "CCD_CONTROLS", "Offset",
 "ZWO CCD ASI2600MM Pro", "CCD_CONTROLS", "BandWidth",
 "ZWO CCD ASI2600MM Pro", "CCD_CONTROLS", "AutoExpMaxGain",
 "ZWO CCD ASI2600MM Pro", "CCD_CONTROLS", "AutoExpMaxExpMS",
 "ZWO CCD ASI2600MM Pro", "CCD_CONTROLS", "AutoExpTargetBrightness",
 "ZWO CCD ASI2600MM Pro", "CCD_CONTROLS", "HardwareBin",
 "ZWO CCD ASI2600MM Pro", "CCD_CONTROLS", "HighSpeedMode",
 "ZWO CCD ASI2600MM Pro", "CCD_CONTROLS", "AntiDewHeater",
 "ZWO CCD ASI2600MM Pro", "CCD_CONTROLS_MODE", "AUTO_Gain",
 "ZWO CCD ASI2600MM Pro", "CCD_CONTROLS_MODE", "AUTO_BandWidth",
 "ZWO CCD ASI2600MM Pro", "FLIP", "FLIP_HORIZONTAL",
 "ZWO CCD ASI2600MM Pro", "FLIP", "FLIP_VERTICAL",
 "ZWO CCD ASI2600MM Pro", "CCD_VIDEO_FORMAT", "ASI_IMG_RAW8",
 "ZWO CCD ASI2600MM Pro", "CCD_VIDEO_FORMAT", "ASI_IMG_RAW16",
 "ZWO CCD ASI2600MM Pro", "BLINK", "BLINK_COUNT",
 "ZWO CCD ASI2600MM Pro", "BLINK", "BLINK_DURATION",
 "ZWO CCD ASI2600MM Pro", "ADC_DEPTH", "BITS",
 "ZWO CCD ASI2600MM Pro", "SDK", "VERSION",
 "ZWO CCD ASI2600MM Pro", "Serial Number", "SN#",
 "ZWO CCD ASI2600MM Pro", "NICKNAME", "nickname",
 "ZWO CCD ASI2600MM Pro", "CCD_CAPTURE_FORMAT", "ASI_IMG_RAW8",
 "ZWO CCD ASI2600MM Pro", "CCD_CAPTURE_FORMAT", "ASI_IMG_RAW16",
 "ZWO CCD ASI2600MM Pro", "CCD_ROTATION", "CCD_ROTATION_VALUE"
     */
}