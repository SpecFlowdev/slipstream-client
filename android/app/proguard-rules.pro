# The packet bridge finds this class and these four methods by name, from C,
# through FindClass and RegisterNatives. Nothing in the Kotlin calls them by
# reflection, so R8 sees them as unused and renames or removes them — and the
# failure is silent: the library loads and binds to nothing. Only release
# builds are minified, so this cannot show up in a debug build at all.
-keep class dev.specflow.slipstream.net.Bridge {
    native <methods>;
    public <init>(...);
    public boolean TProxyStartService(java.lang.String, int);
    public boolean TProxyStopService();
    public boolean TProxyIsRunning();
    public long[] TProxyGetStats();
}

# Serialized shapes are looked up by name, so their names have to survive.
-keepclassmembers class dev.specflow.slipstream.core.** {
    *** Companion;
}
-keepclasseswithmembers class dev.specflow.slipstream.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.specflow.slipstream.core.**$$serializer { *; }

# The enum names are the stored values; renaming them makes an old store
# unreadable by a new build.
-keepclassmembers enum dev.specflow.slipstream.core.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Entry points Android instantiates from the manifest by name.
-keep class dev.specflow.slipstream.App
-keep class dev.specflow.slipstream.MainActivity
-keep class dev.specflow.slipstream.TunnelTile
-keep class dev.specflow.slipstream.BootReceiver
-keep class dev.specflow.slipstream.net.TunnelService
