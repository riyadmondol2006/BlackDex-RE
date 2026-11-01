package top.niunaijun.blackbox.abi32;

import android.content.pm.PackageInfo;
import android.os.Parcel;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * What the host hands to the 32-bit helper: the sandbox PackageInfo of the guest (with its
 * components and sandbox data/lib dirs) plus dump options. Serialized with {@link Parcel}; both
 * sides run the same framework on the same device, so the format always matches.
 */
final class Abi32Request {
    private static final int VERSION = 1;

    String hostPackage;
    String abi;
    String outDir;
    boolean fixCodeItem;
    boolean hookDump;
    PackageInfo packageInfo;

    void write(File file) throws IOException {
        Parcel p = Parcel.obtain();
        try {
            p.writeInt(VERSION);
            p.writeString(hostPackage);
            p.writeString(abi);
            p.writeString(outDir);
            p.writeInt(fixCodeItem ? 1 : 0);
            p.writeInt(hookDump ? 1 : 0);
            packageInfo.writeToParcel(p, 0);
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(p.marshall());
            }
        } finally {
            p.recycle();
        }
    }

    static Abi32Request read(File file) throws IOException {
        byte[] bytes;
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            bytes = buf.toByteArray();
        }
        Parcel p = Parcel.obtain();
        try {
            p.unmarshall(bytes, 0, bytes.length);
            p.setDataPosition(0);
            int version = p.readInt();
            if (version != VERSION) throw new IOException("request version " + version);
            Abi32Request r = new Abi32Request();
            r.hostPackage = p.readString();
            r.abi = p.readString();
            r.outDir = p.readString();
            r.fixCodeItem = p.readInt() != 0;
            r.hookDump = p.readInt() != 0;
            r.packageInfo = PackageInfo.CREATOR.createFromParcel(p);
            return r;
        } finally {
            p.recycle();
        }
    }
}
