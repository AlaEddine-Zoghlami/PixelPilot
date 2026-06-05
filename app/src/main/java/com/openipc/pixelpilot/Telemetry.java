package com.openipc.pixelpilot;

import android.content.Context;
import android.os.Bundle;

import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

/**
 * Telemetry facade for the link/connection flow. Every step in every mode
 * (WFB / APFPV dongle / APFPV Wi-Fi) calls {@link #event} so we get BOTH:
 *   - a Crashlytics breadcrumb (so the trail leading to a crash is visible), and
 *   - a Firebase Analytics event (so the full flow is queryable per mode).
 *
 * All calls are null-safe: with no Firebase config (placeholder
 * google-services.json) or before {@link #init}, they degrade to no-ops.
 */
public final class Telemetry {
    private Telemetry() {}

    private static volatile FirebaseAnalytics analytics;

    /** Call once from Application.onCreate(). */
    public static void init(Context ctx) {
        try { analytics = FirebaseAnalytics.getInstance(ctx.getApplicationContext()); }
        catch (Throwable ignored) {}
    }

    /** A flow step with no params. */
    public static void event(String name) { event(name, null); }

    /** A flow step with one param. */
    public static void event(String name, String key, String value) {
        Bundle b = new Bundle();
        if (key != null) b.putString(key, value != null ? value : "");
        event(name, b);
    }

    /** A flow step with two params. */
    public static void event(String name, String k1, String v1, String k2, String v2) {
        Bundle b = new Bundle();
        if (k1 != null) b.putString(k1, v1 != null ? v1 : "");
        if (k2 != null) b.putString(k2, v2 != null ? v2 : "");
        event(name, b);
    }

    /** A flow step with an explicit param bundle. */
    public static void event(String name, Bundle params) {
        if (name == null) return;
        // Crashlytics breadcrumb (human-readable trail).
        try {
            StringBuilder sb = new StringBuilder(name);
            if (params != null) {
                for (String k : params.keySet()) sb.append(' ').append(k).append('=').append(params.get(k));
            }
            FirebaseCrashlytics.getInstance().log(sb.toString());
        } catch (Throwable ignored) {}
        // Firebase Analytics event (queryable funnel per mode).
        try {
            FirebaseAnalytics a = analytics;
            if (a != null) a.logEvent(name, params);
        } catch (Throwable ignored) {}
    }

    /** Pin a property to all subsequent crash reports and analytics (e.g. link mode). */
    public static void setMode(String mode) {
        try { FirebaseCrashlytics.getInstance().setCustomKey("link_mode", mode); } catch (Throwable ignored) {}
        try { FirebaseAnalytics a = analytics; if (a != null) a.setUserProperty("link_mode", mode); } catch (Throwable ignored) {}
    }
}
