package org.kde.kstars;

import org.freedesktop.dbus.Tuple;
import org.freedesktop.dbus.annotations.Position;

/**
 * Return tuple for {@link INDI#getBLOBFile}: BLOB file name plus its format and size.
 */
public class getBLOBFileTuple extends Tuple {
    @Position(0)
    public String fileName;
    @Position(1)
    public String blobFormat;
    @Position(2)
    public int size;

    public getBLOBFileTuple(String fileName, String blobFormat, int size) {
        this.fileName = fileName;
        this.blobFormat = blobFormat;
        this.size = size;
    }
}
