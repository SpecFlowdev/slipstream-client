# Serialized shapes are looked up by name, so their names have to survive.
-keepclassmembers class dev.specflow.slipstream.core.** {
    *** Companion;
}
-keepclasseswithmembers class dev.specflow.slipstream.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.specflow.slipstream.core.**$$serializer { *; }
