package org.kde.kstars;

import java.util.List;

import org.freedesktop.dbus.Tuple;
import org.freedesktop.dbus.annotations.Position;

/**
 * Return tuple for {@link INDI#getBLOBData}: raw BLOB bytes plus its format and size.
 */
public class getBLOBDataTuple extends Tuple {
    @Position(0)
    public List<Byte> data;
    @Position(1)
    public String blobFormat;
    @Position(2)
    public int size;

    public getBLOBDataTuple(List<Byte> data, String blobFormat, int size) {
        this.data = data;
        this.blobFormat = blobFormat;
        this.size = size;
    }
}
