package reflection.android.app.servertransaction;

import java.util.List;

import reflection.MirrorReflection;

public class ClientTransaction {
    public static final MirrorReflection REF = MirrorReflection.on("android.app.servertransaction.ClientTransaction");

    // API <= 33: separate callbacks list.
    public static MirrorReflection.FieldWrapper<List<Object>> mActivityCallbacks = REF.field("mActivityCallbacks");
    // API 34+ (Android 14): callbacks + lifecycle items were merged into one list.
    public static MirrorReflection.FieldWrapper<List<Object>> mTransactionItems = REF.field("mTransactionItems");
}
