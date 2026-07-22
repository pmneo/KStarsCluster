package org.kde.kstars;

import java.util.List;

import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.messages.DBusSignal;

/**
 * Per-device INDI proxy exposed at /KStars/INDI/&lt;DeviceName&gt;.
 * Complements the global INDIDBus singleton — same operations but without
 * the redundant device-name argument since the path already identifies the device.
 */
@DBusInterfaceName("org.kde.kstars.INDI.GenericDevice")
public interface GenericDevice extends DBusInterface {

    /** Subscribe to value-change events for a property; returns the current value as compact JSON. */
    String watchProperty(String property);
    /** Unsubscribe from value-change events previously registered with watchProperty. */
    void unwatchProperty(String property);

    /** Returns the full property state as a JSON string; compact=false for pretty-print. */
    String getPropertyJSON(String propName);
    /** Sets property elements from a JSON string; returns true on success. */
    boolean setPropertyJSON(String propName, String elementsJson);
    /** Returns all property names exposed by this device. */
    List<String> getPropertiesList();
    /** Returns the INDI property state ("Idle", "Ok", "Busy", "Alert"). */
    String getPropertyState(String property);
    /** Sends the current property values to the INDI server. */
    boolean sendProperty(String property);

    boolean setSwitch(String property, String switchName, String status);
    String  getSwitch(String property, String switchName);
    boolean setText(String property, String textName, String text);
    String  getText(String property, String textName);
    boolean setNumber(String property, String numberName, double value);
    double  getNumber(String property, String numberName);
    String  getLight(String property, String lightName);

    getBLOBDataTuple getBLOBData(String property, String blobName);
    getBLOBFileTuple getBLOBFile(String property, String blobName);

    /** Fired when a watched property changes; json contains the full property state. */
    class propertyValueChanged extends DBusSignal {
        private final String device;
        private final String property;
        private final String json;
        public propertyValueChanged(String _path, String _device, String _property, String _json)
                throws DBusException {
            super(_path, _device, _property, _json);
            this.device = _device;
            this.property = _property;
            this.json = _json;
        }
        public String getDevice()   { return device; }
        public String getProperty() { return property; }
        public String getJson()     { return json; }
    }
}
