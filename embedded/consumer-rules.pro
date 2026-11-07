# Modified for host embedding: preserve shell entry points without applying the
# standalone manager's global repackaging/side-effect rules to the host.
# These APIs are supplied by Android at runtime, outside the public SDK jar.
-dontwarn com.android.internal.app.IAppOpsService
-dontwarn com.android.org.conscrypt.Conscrypt

# Preserve callbacks invoked through Android's hidden observer interfaces.
-keepclassmembers class rikka.hidden.compat.adapter.ProcessObserverAdapter {
    <methods>;
}
-keepclassmembers class rikka.hidden.compat.adapter.UidObserverAdapter {
    <methods>;
}

-keep class rikka.shizuku.server.ShizukuService {
    public static void main(java.lang.String[]);
}
-keep class moe.shizuku.starter.ServiceStarter {
    public static void main(java.lang.String[]);
}
-keep class moe.shizuku.manager.shell.Shell {
    public static void main(java.lang.String[], java.lang.String, android.os.IBinder, android.os.Handler);
}
-keepnames class moe.shizuku.api.BinderContainer
# Match the standalone release's removal of debug logs, including process args.
-assumenosideeffects class rikka.shizuku.server.util.Logger {
    public *** d(...);
}
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
