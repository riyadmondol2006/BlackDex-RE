package top.niunaijun.blackbox.core;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.text.TextUtils;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.utils.FileUtils;

/**
 * Created by Milk on 4/9/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
@SuppressLint("SdCardPath")
public class IOCore {
    private static final IOCore sIOCore = new IOCore();
    private final Map<String, String> mRedirectMap = new LinkedHashMap<>();

    private static final Map<String, Map<String, String>> sCachePackageRedirect = new HashMap<>();

    public static IOCore get() {
        return sIOCore;
    }

    // /data/data/com.google/  ----->  /data/data/com.virtual/data/com.google/
    public void addRedirect(String origPath, String redirectPath) {
        if (TextUtils.isEmpty(origPath) || TextUtils.isEmpty(redirectPath) || mRedirectMap.get(origPath) != null)
            return;
        mRedirectMap.put(origPath, redirectPath);
        File redirectFile = new File(redirectPath);
        if (!redirectFile.exists()) {
            FileUtils.mkdirs(redirectPath);
        }
        VMCore.addIORule(origPath, redirectPath);
    }

    public String redirectPath(String path) {
        if (TextUtils.isEmpty(path))
            return path;
        for (String orig : mRedirectMap.keySet()) {
            if (path.startsWith(orig)) {
                path = path.replace(orig, Objects.requireNonNull(mRedirectMap.get(orig)));
                break;
            }
        }
        return path;
    }

    public File redirectPath(File path) {
        if (path == null)
            return null;
        String pathStr = path.getAbsolutePath();
        return new File(redirectPath(pathStr));
    }

    public String redirectPath(String path, Map<String, String> rule) {
        if (TextUtils.isEmpty(path))
            return path;
        for (String orig : rule.keySet()) {
            if (path.startsWith(orig)) {
                path = path.replace(orig, Objects.requireNonNull(rule.get(orig)));
                break;
            }
        }
        return path;
    }

    public File redirectPath(File path, Map<String, String> rule) {
        if (path == null)
            return null;
        String pathStr = path.getAbsolutePath();
        return new File(redirectPath(pathStr, rule));
    }

    // 由于正常情况Application已完成重定向，以下重定向是怕代码写死。
    public void enableRedirect(Context context) {
        try {
            ApplicationInfo packageInfo = BlackBoxCore.getBPackageManager().getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA, BActivityThread.getUserId());
            enableRedirect(context, packageInfo, BlackBoxCore.getContext().getExternalCacheDir() != null);
        } catch (Exception e) {
            e.printStackTrace();
            VMCore.enableIO();
        }
    }

    /**
     * Redirect the guest's hard-coded data/lib/external paths into its sandbox dirs, then turn on
     * the native IO hooks. {@code info} is the sandbox ApplicationInfo (dataDir/nativeLibraryDir
     * already point inside our own storage).
     */
    public void enableRedirect(Context context, ApplicationInfo info, boolean hostHasExternal) {
        Map<String, String> rule = new LinkedHashMap<>();
        String packageName = info.packageName;
        try {
            rule.put("/data/data/" + packageName + "/lib", info.nativeLibraryDir);
            rule.put("/data/user/0/" + packageName + "/lib", info.nativeLibraryDir);

            rule.put("/data/data/" + packageName, info.dataDir);
            rule.put("/data/user/0/" + packageName, info.dataDir);

            if (hostHasExternal && context.getExternalCacheDir() != null) {
                File external = context.getExternalCacheDir().getParentFile();

                // sdcard
                rule.put("/sdcard/Android/data/" + packageName,
                        external.getAbsolutePath());
                rule.put("/sdcard/android/data/" + packageName, external.getAbsolutePath());

                rule.put("/storage/emulated/0/android/data/" + packageName,
                        external.getAbsolutePath());
                rule.put("/storage/emulated/0/Android/data/" + packageName,
                        external.getAbsolutePath());

                rule.put("/storage/emulated/0/Android/data/" + packageName + "/files",
                        new File(external.getAbsolutePath(), "files").getAbsolutePath());
                rule.put("/storage/emulated/0/Android/data/" + packageName + "/cache",
                        new File(external.getAbsolutePath(), "cache").getAbsolutePath());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        for (String key : rule.keySet()) {
            get().addRedirect(key, rule.get(key));
        }
        VMCore.enableIO();
    }
}
