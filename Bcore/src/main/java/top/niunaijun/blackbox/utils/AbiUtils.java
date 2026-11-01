package top.niunaijun.blackbox.utils;

import java.io.File;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import top.niunaijun.blackbox.BlackBoxCore;

/**
 * Created by Milk on 3/2/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class AbiUtils {
    private final Set<String> mLibs = new HashSet<>();
    private static final Map<File, AbiUtils> sAbiUtilsMap = new HashMap<>();

    private static synchronized AbiUtils of(File apkFile) {
        AbiUtils abiUtils = sAbiUtilsMap.get(apkFile);
        if (abiUtils == null) {
            abiUtils = new AbiUtils(apkFile);
            sAbiUtilsMap.put(apkFile, abiUtils);
        }
        return abiUtils;
    }

    /**
     * True if this install can dump the APK: it has no native code, its native code matches the
     * host runtime, or (64-bit host) it is 32-bit-only and the device can still run 32-bit code —
     * those go through the app_process32 helper (see {@code abi32.Abi32Dumper}).
     */
    public static boolean isSupport(File apkFile) {
        AbiUtils abiUtils = of(apkFile);
        if (abiUtils.isEmptyAib()) {
            return true;
        }

        if (BlackBoxCore.is64Bit()) {
            return abiUtils.is64Bit() || (abiUtils.is32Bit() && isDevice32BitCapable());
        } else {
            return abiUtils.is32Bit();
        }
    }

    /**
     * True if the guest has native code only for 32-bit ARM while we run 64-bit, i.e. it cannot
     * load its libraries in our process and must be dumped in a 32-bit runtime.
     */
    public static boolean requires32BitRuntime(File apkFile) {
        if (!BlackBoxCore.is64Bit()) return false;
        AbiUtils abiUtils = of(apkFile);
        return !abiUtils.isEmptyAib() && !abiUtils.is64Bit() && abiUtils.is32Bit();
    }

    /** Device has a 32-bit ARM userspace (Build.SUPPORTED_32_BIT_ABIS non-empty + app_process32). */
    public static boolean isDevice32BitCapable() {
        String[] abis = android.os.Build.SUPPORTED_32_BIT_ABIS;
        return abis != null && abis.length > 0 && new File("/system/bin/app_process32").exists();
    }

    /**
     * ABI directories to try, in order, when copying a guest's native libs for the runtime the
     * guest will actually run in.
     */
    public static String[] nativeLibAbiCandidates(File apkFile) {
        if (requires32BitRuntime(apkFile)) {
            return new String[]{"armeabi-v7a", "armeabi"};
        }
        if (BlackBoxCore.is64Bit()) {
            return new String[]{"arm64-v8a"};
        }
        return new String[]{"armeabi-v7a", "armeabi"};
    }

    public AbiUtils(File apkFile) {
        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(apkFile);
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry zipEntry = entries.nextElement();
                String name = zipEntry.getName();
                if (name.startsWith("lib/arm64-v8a")) {
                    mLibs.add("arm64-v8a");
                } else if (name.startsWith("lib/armeabi-v7a/")) {
                    mLibs.add("armeabi-v7a");
                } else if (name.startsWith("lib/armeabi/")) {
                    mLibs.add("armeabi");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            CloseUtils.close(zipFile);
        }
    }

    public boolean is64Bit() {
        return mLibs.contains("arm64-v8a");
    }

    public boolean is32Bit() {
        return mLibs.contains("armeabi") || mLibs.contains("armeabi-v7a");
    }

    public boolean isEmptyAib() {
        return mLibs.isEmpty();
    }
}
