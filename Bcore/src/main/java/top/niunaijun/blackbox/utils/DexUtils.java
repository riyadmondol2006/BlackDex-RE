package top.niunaijun.blackbox.utils;

import android.util.AtomicFile;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.zip.Adler32;

/**
 * Created by Milk on 2021/6/6.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class DexUtils {
    private static final String TAG = "DexUtils";

    /** Size of the fixed dex header (header_item). */
    private static final int DEX_HEADER_SIZE = 0x70;
    /** Refuse to load absurdly large files into memory. */
    private static final long MAX_DEX_SIZE = Integer.MAX_VALUE - 8L;

    /** Serialises repairs so two callers never race on the same file / AtomicFile backup. */
    private static final Object sFixLock = new Object();

    /**
     * Repairs the header of a dumped dex in place: file_size, SHA-1 signature and adler32
     * checksum are recomputed from the actual bytes. The original file is never lost:
     * input that is not a dex (bad magic / shorter than the header) is left untouched, and the
     * repaired bytes are committed atomically via {@link AtomicFile}, which restores the
     * original if anything fails mid-write.
     */
    public static void fixDex(File dex) {
        if (dex == null)
            return;
        synchronized (sFixLock) {
            byte[] original;
            try {
                original = readFully(dex);
            } catch (Throwable e) {
                Log.w(TAG, "fixDex: cannot read " + dex, e);
                return;
            }
            if (original == null || !isDexHeader(original)) {
                return;
            }

            byte[] bytes = original.clone();
            try {
                fixFileSizeHeader(bytes);
                calcSignature(bytes);
                calcChecksum(bytes);
            } catch (Throwable e) {
                Log.w(TAG, "fixDex: cannot repair " + dex, e);
                return;
            }
            if (Arrays.equals(original, bytes)) {
                return; // already correct; nothing to write
            }
            commit(dex, original, bytes);
        }
    }

    private static void commit(File dex, byte[] original, byte[] bytes) {
        File backup = new File(dex.getPath() + ".bak");
        // A stale AtomicFile backup (left by a crash) would be treated as the authoritative copy
        // and restored over the dump we just read. We hold the validated bytes, so drop it.
        if (backup.exists() && !backup.delete()) {
            Log.w(TAG, "fixDex: cannot remove stale backup " + backup + ", leaving dex untouched");
            return;
        }
        AtomicFile atomicFile = new AtomicFile(dex);
        FileOutputStream out = null;
        try {
            out = atomicFile.startWrite();
            out.write(bytes);
            atomicFile.finishWrite(out);
            out = null;
        } catch (Throwable e) {
            Log.w(TAG, "fixDex: cannot write " + dex + ", keeping original", e);
            if (out != null) {
                atomicFile.failWrite(out);
            }
            restoreIfLost(dex, backup, original);
        }
    }

    /**
     * Last line of defence: if a failure (e.g. startWrite() throwing after it already moved the
     * base file aside on older platforms) left the dex missing or altered, put the original back.
     */
    private static void restoreIfLost(File dex, File backup, byte[] original) {
        try {
            if (!dex.exists() && backup.exists() && backup.renameTo(dex)) {
                return;
            }
            if (dex.exists() && dex.length() == original.length
                    && Arrays.equals(readFully(dex), original)) {
                return;
            }
            FileOutputStream fos = new FileOutputStream(dex);
            try {
                fos.write(original);
                fos.getFD().sync();
            } finally {
                CloseUtils.close(fos);
            }
        } catch (Throwable e) {
            Log.e(TAG, "fixDex: failed to restore original " + dex, e);
        }
    }

    /** Standard dex magic "dex\n0xx\0" (two-digit version) and a complete header. */
    private static boolean isDexHeader(byte[] b) {
        if (b.length < DEX_HEADER_SIZE)
            return false;
        return b[0] == 'd' && b[1] == 'e' && b[2] == 'x' && b[3] == '\n'
                && b[4] == '0' && isDigit(b[5]) && isDigit(b[6]) && b[7] == 0;
    }

    private static boolean isDigit(byte c) {
        return c >= '0' && c <= '9';
    }

    private static byte[] readFully(File file) throws IOException {
        if (!file.isFile())
            return null;
        long length = file.length();
        if (length > MAX_DEX_SIZE)
            return null;
        FileInputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.max(length, 32));
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
                if (bos.size() > MAX_DEX_SIZE)
                    return null;
            }
            return bos.toByteArray();
        } finally {
            CloseUtils.close(in);
        }
    }

    private static void calcChecksum(byte[] var0) {
        Adler32 var2 = new Adler32();
        var2.update(var0, 12, var0.length - 12);
        int var1 = (int) var2.getValue();
        var0[8] = (byte) var1;
        var0[9] = (byte) (var1 >> 8);
        var0[10] = (byte) (var1 >> 16);
        var0[11] = (byte) (var1 >> 24);
    }

    private static void calcSignature(byte[] bytes) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException ex) {
            throw new RuntimeException(ex);
        }

        md.update(bytes, 32, bytes.length - 32);

        try {
            int amt = md.digest(bytes, 12, 20);
            if (amt != 20) {
                throw new RuntimeException("unexpected digest write: " + amt +
                        " bytes");
            }
        } catch (DigestException ex) {
            throw new RuntimeException(ex);
        }
    }

    private static void fixFileSizeHeader(byte[] dexBytes) {
        byte[] newfs = intToByte(dexBytes.length);
        byte[] refs = new byte[4];
        for (int i = 0; i < 4; i++) {
            refs[i] = newfs[newfs.length - 1 - i];
        }
        System.arraycopy(refs, 0, dexBytes, 32, 4);
    }

    private static byte[] intToByte(int number) {
        byte[] b = new byte[4];
        for (int i = 3; i >= 0; i--) {
            b[i] = (byte) (number % 256);
            number >>= 8;
        }
        return b;
    }
}
