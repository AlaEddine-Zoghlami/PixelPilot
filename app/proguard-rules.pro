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
