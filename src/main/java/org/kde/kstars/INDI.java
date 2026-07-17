package org.kde.kstars;

import java.util.List;

import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.interfaces.DBusInterface;

/**
 * Auto-generated class.
 */
@DBusInterfaceName("org.kde.kstars.INDI")
public interface INDI extends DBusInterface {

    public boolean start(String port, List<String> drivers);
    public boolean stop(String port);
    public boolean connect(String host, String port);
    public boolean disconnect(String host, String port);
    public List<String> getDevices();
    public List<String> getProperties(String device);
    public String getPropertyState(String device, String property);
    public boolean sendProperty(String device, String property);
    public String getLight(String device, String property, String lightName);
    public boolean setSwitch(String device, String property, String switchName, String status);
    public String getSwitch(String device, String property, String switchName);
    public boolean setText(String device, String property, String textName, String text);
    public String getText(String device, String property, String textName);
    public boolean setNumber(String device, String property, String numberName, double value);
    public double getNumber(String device, String property, String numberName);
    public List<Byte> getBLOBData(String device, String property, String blobName);
    public String getBLOBFile(String device, String property, String blobName);

    public static enum IpsState {
        IPS_IDLE, /*!< State is idle */
        IPS_OK,       /*!< State is ok */
        IPS_BUSY,     /*!< State is busy */
        IPS_ALERT;     /*!< State is alert */
        
        public static IpsState get( String byName ) {
            switch( byName ) {
                case "Idle": return IPS_IDLE;
                case "Ok": return IPS_OK;
                case "Busy": return IPS_BUSY;
                case "Alrt": return IPS_ALERT;
                default: return IPS_IDLE;
            }
        }
    }

    public static enum DriverInterface {       
        GENERAL_INTERFACE       (0),         /**< Default interface for all INDI devices */
        TELESCOPE_INTERFACE     (1 << 0),  /**< Telescope interface, must subclass INDI::Telescope */
        CCD_INTERFACE           (1 << 1),  /**< CCD interface, must subclass INDI::CCD */
        GUIDER_INTERFACE        (1 << 2),  /**< Guider interface, must subclass INDI::GuiderInterface */
        FOCUSER_INTERFACE       (1 << 3),  /**< Focuser interface, must subclass INDI::FocuserInterface */
        FILTER_INTERFACE        (1 << 4),  /**< Filter interface, must subclass INDI::FilterInterface */
        DOME_INTERFACE          (1 << 5),  /**< Dome interface, must subclass INDI::Dome */
        GPS_INTERFACE           (1 << 6),  /**< GPS interface, must subclass INDI::GPS */
        WEATHER_INTERFACE       (1 << 7),  /**< Weather interface, must subclass INDI::Weather */
        AO_INTERFACE            (1 << 8),  /**< Adaptive Optics Interface */
        DUSTCAP_INTERFACE       (1 << 9),  /**< Dust Cap Interface */
        LIGHTBOX_INTERFACE      (1 << 10), /**< Light Box Interface */
        DETECTOR_INTERFACE      (1 << 11), /**< Detector interface, must subclass INDI::Detector */
        ROTATOR_INTERFACE       (1 << 12), /**< Rotator interface, must subclass INDI::RotatorInterface */
        SPECTROGRAPH_INTERFACE  (1 << 13), /**< Spectrograph interface */
        CORRELATOR_INTERFACE    (1 << 14), /**< Correlators (interferometers) interface */
        AUX_INTERFACE           (1 << 15) /**< Auxiliary interface */
        //SENSOR_INTERFACE        (SPECTROGRAPH_INTERFACE | DETECTOR_INTERFACE | CORRELATOR_INTERFACE;
        ;
        public final int id;
        DriverInterface( int id ) {
            this.id = id;
        }
    }
}