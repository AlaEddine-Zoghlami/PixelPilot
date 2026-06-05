package com.openipc.pixelpilot;

import com.google.firebase.crashlytics.FirebaseCrashlytics;

/**
 * Thin, null-safe facade over Crashlytics. Every call is guarded so it is a
 * no-op when Firebase isn't initialized (placeholder google-services.json),
 * letting feature code drop breadcrumbs/keys without try/catch noise.
 */
public final class Crash {
    private Crash() {}

    /** Breadcrumb line attached to the next crash report. */
    public static void log(String msg) {
        try { FirebaseCrashlytics.getInstance().log(msg); } catch (Throwable ignored) {}
    }

    /** Report a non-fatal/caught exception (e.g. a failed link bring-up). */
    public static void record(Throwable t) {
        try { FirebaseCrashlytics.getInstance().recordException(t); } catch (Throwable ignored) {}
    }

    /** Attach a key/value to subsequent reports (e.g. link mode, channel). */
    public static void key(String k, String v) {
        try { FirebaseCrashlytics.getInstance().setCustomKey(k, v); } catch (Throwable ignored) {}
    }
}
