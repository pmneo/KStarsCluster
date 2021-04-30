package org.kde.kstars;

import java.util.List;
import org.freedesktop.dbus.interfaces.DBusInterface;

/**
 * Auto-generated class.
 */
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

}