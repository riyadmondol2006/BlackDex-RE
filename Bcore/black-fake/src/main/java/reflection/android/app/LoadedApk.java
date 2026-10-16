package reflection.android.app;

import android.app.Application;
import android.app.Instrumentation;
import android.content.pm.ApplicationInfo;
import reflection.MirrorReflection;

public class LoadedApk {
    public static final MirrorReflection REF = MirrorReflection.on("android.app.LoadedApk");
    public static MirrorReflection.FieldWrapper<ApplicationInfo> mApplicationInfo = REF.field("mApplicationInfo");
    public static MirrorReflection.MethodWrapper<Application> makeApplication = REF.method("makeApplication", boolean.class, Instrumentation.class);
    // Android 14+ (API 34) renamed the real factory to makeApplicationInner; the 2-arg
    // makeApplication may be absent, so resolve both and pick whichever exists at runtime.
    public static MirrorReflection.MethodWrapper<Application> makeApplicationInner = REF.method("makeApplicationInner", boolean.class, Instrumentation.class);
    public static MirrorReflection.MethodWrapper<ClassLoader> getClassloader = REF.method("getClassloader");

    public static MirrorReflection.FieldWrapper<Boolean> mSecurityViolation = REF.field("mSecurityViolation");
}