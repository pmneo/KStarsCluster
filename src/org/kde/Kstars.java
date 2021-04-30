package org.kde;

import org.freedesktop.dbus.annotations.DBusProperty;
import org.freedesktop.dbus.annotations.DBusProperty.Access;
import org.freedesktop.dbus.interfaces.DBusInterface;

/**
 * Auto-generated class.
 */
@DBusProperty(name = "colorScheme", type = String.class, access = Access.READ_WRITE)
public interface Kstars extends DBusInterface {


    public void setRaDec(double ra, double dec);
    public void setRaDecJ2000(double ra0, double dec0);
    public void setAltAz(double alt, double az);
    public void setAltAz(double alt, double az, boolean altIsRefracted);
    public void lookTowards(String direction);
    public void addLabel(String name);
    public void removeLabel(String name);
    public void addTrail(String name);
    public void removeTrail(String name);
    public void zoomIn();
    public void zoomOut();
    public void defaultZoom();
    public void zoom(double z);
    public void setLocalTime(int yr, int mth, int day, int hr, int min, int sec);
    public void setTimeToNow();
    public void waitFor(double t);
    public void waitForKey(String k);
    public void setTracking(boolean track);
    public void changeViewOption(String option, String value);
    public void readConfig();
    public void writeConfig();
    public void popupMessage(int x, int y, String message);
    public void drawLine(int x1, int y1, int x2, int y2, int speed);
    public boolean setGeoLocation(String city, String province, String country);
    public String location();
    public boolean setGPSLocation(double longitude, double latitude, double elevation, double tz0);
    public void setColor(String colorName, String value);
    public void exportImage(String filename, int width, int height, boolean includeLegend);
    public void exportImage(String filename, int width, int height);
    public void exportImage(String filename, int width);
    public void exportImage(String filename);
    public String getDSSURL(String objectName);
    public String getDSSURL(double RAJ2000, double DecJ2000);
    public String getObjectDataXML(String objectName);
    public String getObjectPositionInfo(String objectName);
    public void renderEyepieceView(String objectName, String destPathChart, double fovWidth, double fovHeight, double rotation, double scale, boolean flip, boolean invert, String imagePath, String destPathImage, boolean overlay, boolean invertColors);
    public void setApproxFOV(double FOVDegrees);
    public String getSkyMapDimensions();
    public String getObservingWishListObjectNames();
    public String getObservingSessionPlanObjectNames();
    public void printImage(boolean usePrintDialog, boolean useChartColors);
    public void openFITS(String imageURL);
}