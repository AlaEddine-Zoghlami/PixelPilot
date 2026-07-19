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

# JSch: many crypto provider classes (jce.Random, jce.DH, jce.SHA256, jce.HMAC, jce.AES*,
# jce.Signature*, jce.KeyPairGen*, ...) are loaded reflectively via getJceName() ->
# Class.forName("com.jcraft.jsch.jce." + name), so R8 can't see the references and strips them,
# burning ClassNotFoundException at runtime (e.g. changing SSH/settings). Keep the whole
# com.jcraft.jsch package. Several of its classes reference OPTIONAL external deps that are NOT
# present on Android and are only reached via reflection/optional code paths (com.sun.jna for the
# Windows PageantConnector, org.bouncycastle for the BC crypto provider, org.apache.logging.log4j
# / org.slf4j for optional logging, org.newsclub for optional JZlib). Dontwarn those so R8 does
# not abort on "missing classes" — the classes are never actually used on Android.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.PageantConnector
-dontwarn com.jcraft.jsch.bc.**
-dontwarn com.jcraft.jsch.Log4j2Logger
-dontwarn com.jcraft.jsch.Slf4jLogger
-dontwarn org.bouncycastle.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**
-dontwarn org.newsclub.**
-dontwarn org.ietf.jgss.**
-dontwarn com.sun.jna.**
