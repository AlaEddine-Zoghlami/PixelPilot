-keep class com.openipc.mavlink.MavlinkData { *; }
-keep interface com.openipc.mavlink.MavlinkUpdate { *; }
-keep class * implements com.openipc.mavlink.MavlinkUpdate { *; }
-keep class com.openipc.mavlink.MavlinkNative { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Crashlytics: keep file/line info so release crash reports are symbolicated,
# and don't strip custom exception types.
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception

# APFPV/WFB station: onNativeState/onNativeRssi/onNativeScanResult are invoked ONLY from
# native (reverse-JNI, no Java caller), so R8 strips them in release -> native GetMethodID
# throws NoSuchMethodError and aborts at connect. The `native <methods>` rule above keeps
# the native entrypoints but NOT these callbacks. Keep the whole link classes.
-keep class com.openipc.wfbngrtl8812.ApfpvStaLink { *; }
-keep class com.openipc.wfbngrtl8812.WfbNgLink { *; }
