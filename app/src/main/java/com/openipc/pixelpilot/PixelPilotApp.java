package com.openipc.pixelpilot;

import android.app.Application;

import com.google.firebase.FirebaseApp;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

/**
 * Application entry point. Initializes Firebase Crashlytics so both Java/Kotlin
 * exceptions and native (NDK) crashes from the devourer/APFPV driver are
 * captured. Everything is wrapped so a missing/placeholder google-services.json
 * (no real Firebase project yet) degrades to a no-op instead of crashing the app.
 */
public class PixelPilotApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        try {
            FirebaseApp.initializeApp(this);
            FirebaseCrashlytics fc = FirebaseCrashlytics.getInstance();
            fc.setCrashlyticsCollectionEnabled(true);
            fc.setCustomKey("app", "PixelPilot-APFPV");
        } catch (Throwable ignored) {
            // No Firebase config (placeholder google-services.json) — run without reporting.
        }
    }
}
