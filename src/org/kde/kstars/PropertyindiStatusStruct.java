package org.kde.kstars;

import org.freedesktop.dbus.Struct;
import org.freedesktop.dbus.annotations.Position;

/**
 * Auto-generated class.
 */
public class PropertyindiStatusStruct extends Struct {
    @Position(0)
    private final int member0;

    public PropertyindiStatusStruct(int member0) {
        this.member0 = member0;
    }


    public int getMember0() {
        return member0;
    }


}