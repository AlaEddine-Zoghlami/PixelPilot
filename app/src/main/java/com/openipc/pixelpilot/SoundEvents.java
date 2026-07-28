package com.openipc.pixelpilot;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenTX-style voice-alert condition table — the Android port of SoundEvents.h.
 *
 * <p>A table of rules, each pairing a CONDITION with a concatenated announcement. Fires on
 * STATE CHANGE (false-&gt;true), matching the arm-driven recording, not on "condition is true".
 * repeat=0 announces once per activation; repeat=N re-announces every N seconds while true.
 *
 * <p>Values come from the OSD, because msposd sends only MSP_DISPLAYPORT + MSP_STATUS: battery
 * / RSSI never arrive as numbers, only as glyphs. So numeric variables are read from the
 * DisplayPort WRITE_STRING runs (feedOsdRun), keyed on the UNIT font symbols (0x06=volts,
 * 0x07=mAh, 0x01=rssi) — an ASCII view of the canvas would discard exactly the byte that says
 * which quantity a number is. An absent variable never fires, and an unresolved value slot is
 * dropped rather than spoken as zero.
 */
public final class SoundEvents {
    private static final String TAG = "SoundEvents";

    // Betaflight OSD unit symbols (src/main/drivers/osd_symbols.h).
    private static final int SYM_RSSI = 0x01, SYM_VOLT = 0x06, SYM_MAH = 0x07, SYM_METRE = 0x0C;

    /** Variables scraped from the OSD, plus the structured arm bit. */
    public static final class Vars {
        final Map<String, Double> num = new HashMap<>();
        boolean armed = false, haveArmed = false;
        Double get(String k) { return num.get(k); }
    }

    private interface Player { void play(List<String> clips); }

    private static final class Rule {
        String name, var;
        int op;                       // 0 isTrue,1 isFalse,2 lt,3 gt
        double threshold, hyst;
        final List<String> seq = new ArrayList<>();
        int repeatSec;
        boolean active;
        long lastFiredMs;
    }

    private final List<Rule> rules = new ArrayList<>();
    private Player player;

    public void setPlayer(SoundPlayer sp) { this.player = sp::play; }

    // ---- Stateful battery announcer ---------------------------------------------------
    // The old timer-repeat battery rules looped ("battery low" every N seconds) and spoke the
    // raw voltage (0 when no battery / a bad OSD read). This is a proper state machine instead:
    // it detects the cell count, tracks a per-cell alert LEVEL, announces each lower level exactly
    // ONCE, never repeats or goes back up within a flight, and resets on a fresh arm. It ignores an
    // absent/implausible voltage entirely (no bench spam, no "zero"), and only runs while armed.
    private int battLevel = 0;              // 0 ok, 1 low, 2 critical, 3 land-now
    private int battCells = 0;              // detected on the first valid armed reading
    private boolean battWasArmed = false;
    private int battPendLevel = 0, battPendCount = 0;   // 2-reading confirm vs load sag

    private void evaluateBattery(Vars v) {
        boolean armed = v.haveArmed && v.armed;
        if (armed && !battWasArmed) { battLevel = 0; battCells = 0; battPendLevel = 0; battPendCount = 0; }
        battWasArmed = armed;
        Double vb = v.get("vbat");
        if (!armed || vb == null || vb < 5.0) { battPendCount = 0; return; }   // not flying / no real pack
        if (battCells <= 0) battCells = Math.max(1, (int) Math.ceil(vb / 4.25));
        double perCell = vb / battCells;
        int level = perCell < 3.0 ? 3 : perCell < 3.3 ? 2 : perCell < 3.5 ? 1 : 0;
        if (level <= battLevel) { battPendCount = 0; return; }   // same / recovered -> no re-announce
        // Confirm the worse level across two readings so a momentary sag under load doesn't false-fire.
        if (level == battPendLevel) battPendCount++; else { battPendLevel = level; battPendCount = 1; }
        if (battPendCount < 2) return;
        battLevel = level;
        List<String> clips = new ArrayList<>();
        switch (level) {
            case 1:  clips.add("lowbat.wav"); break;                              // "low battery"
            case 2:  clips.add("batalert.wav"); break;                            // "battery alert"
            default: clips.add("batalert.wav"); clips.add("warnng.wav"); break;   // "battery alert, warning"
        }
        Log.i(TAG, "battery level " + level + " (" + String.format(java.util.Locale.US, "%.2fV/cell, %dS", perCell, battCells) + ") -> " + clips);
        if (player != null) player.play(clips);
    }

    // ---- OSD run parser: one WRITE_STRING run -> at most one variable ------------------
    /** Feed the glyph bytes of one DisplayPort WRITE_STRING run (after row/col/attr). */
    public static void feedOsdRun(Vars out, byte[] g, int off, int n) {
        if (g == null || n <= 0) return;
        int s0 = -1, s1 = -1; boolean dot = false;
        for (int i = 0; i < n; i++) {
            int c = g[off + i] & 0xFF;
            boolean isNum = (c >= '0' && c <= '9') || (c == '.' && !dot && s0 >= 0);
            if (isNum) { if (c == '.') dot = true; if (s0 < 0) s0 = i; s1 = i; }
            else if (s0 >= 0) break;
        }
        if (s0 < 0) return;
        StringBuilder sb = new StringBuilder();
        for (int i = s0; i <= s1; i++) sb.append((char) (g[off + i] & 0xFF));
        double val;
        try { val = Double.parseDouble(sb.toString()); } catch (NumberFormatException e) { return; }
        int after = (s1 + 1 < n) ? (g[off + s1 + 1] & 0xFF) : 0;
        int before = (s0 - 1 >= 0) ? (g[off + s0 - 1] & 0xFF) : 0;
        if (after == SYM_VOLT || before == SYM_VOLT) {
            if (val >= 2.0 && val <= 60.0) out.num.put("vbat", val);
        } else if (after == SYM_MAH || before == SYM_MAH) {
            out.num.put("mah", val);
        } else if (after == SYM_RSSI || before == SYM_RSSI || after == '%' || before == '%') {
            if (val >= 0 && val <= 100) out.num.put("lq", val);
        } else if (after == SYM_METRE || before == SYM_METRE) {
            out.num.put("alt", val);
        }
    }

    // ---- config -----------------------------------------------------------------------
    public boolean load(Context ctx, String assetName) {
        rules.clear();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(ctx.getAssets().open(assetName)))) {
            String line; int no = 0;
            while ((line = r.readLine()) != null) {
                no++;
                // '#' starts a comment only at word start (value slots are "#var").
                for (int h = 0; h < line.length(); h++) {
                    if (line.charAt(h) != '#') continue;
                    if (h == 0 || Character.isWhitespace(line.charAt(h - 1))) { line = line.substring(0, h); break; }
                }
                line = line.trim();
                if (line.isEmpty() || !line.startsWith("event ")) continue;
                String[] tok = line.substring(6).trim().split("\\s+");
                if (tok.length == 0) continue;
                Rule ru = new Rule();
                ru.name = tok[0];
                for (int i = 1; i < tok.length; i++) {
                    int eq = tok[i].indexOf('=');
                    if (eq <= 0) continue;
                    String k = tok[i].substring(0, eq), v = tok[i].substring(eq + 1);
                    switch (k) {
                        case "sound": case "say":
                            for (String t : v.split(",")) if (!t.isEmpty()) ru.seq.add(t);
                            break;
                        case "repeat": ru.repeatSec = safeInt(v); break;
                        case "hyst":   ru.hyst = safeDouble(v); break;
                        case "when":   parseWhen(v, ru); break;
                    }
                }
                if (ru.var != null && !ru.seq.isEmpty()) rules.add(ru);
            }
        } catch (Exception e) {
            Log.w(TAG, "load failed: " + e.getMessage());
            return false;
        }
        Log.i(TAG, rules.size() + " rule(s) from " + assetName);
        return !rules.isEmpty();
    }

    private static void parseWhen(String w, Rule r) {
        if (w.equals("armed"))  { r.var = "armed"; r.op = 0; return; }
        if (w.equals("!armed")) { r.var = "armed"; r.op = 1; return; }
        int p = -1; for (int i = 0; i < w.length(); i++) if (w.charAt(i) == '<' || w.charAt(i) == '>') { p = i; break; }
        if (p <= 0 || p + 1 >= w.length()) return;
        r.var = w.substring(0, p);
        r.op = (w.charAt(p) == '<') ? 2 : 3;
        r.threshold = safeDouble(w.substring(p + 1));
    }

    // ---- evaluation (edge-triggered) --------------------------------------------------
    public void evaluate(Vars v, long nowMs) {
        evaluateBattery(v);   // stateful battery handled here, NOT via timer-repeat rules
        for (Rule r : rules) {
            Boolean truth = truth(r, v);
            if (truth == null) continue;              // variable absent -> never fires
            if (truth && !r.active) { r.active = true; fire(r, v, nowMs); }
            else if (truth && r.active) {
                if (r.repeatSec > 0 && nowMs - r.lastFiredMs >= r.repeatSec * 1000L) fire(r, v, nowMs);
            } else if (!truth) r.active = false;
        }
    }

    private Boolean truth(Rule r, Vars v) {
        if (r.var.equals("armed")) {
            if (!v.haveArmed) return null;
            return (r.op == 0) == v.armed;
        }
        Double val = v.get(r.var);
        if (val == null) return null;
        if (r.op == 2) return r.active ? (val < r.threshold + r.hyst) : (val < r.threshold);
        return r.active ? (val > r.threshold - r.hyst) : (val > r.threshold);
    }

    private void fire(Rule r, Vars v, long nowMs) {
        r.lastFiredMs = nowMs;
        List<String> clips = new ArrayList<>();
        for (String t : r.seq) {
            if (t.length() > 1 && t.charAt(0) == '#') {
                Double val = v.get(t.substring(1));
                if (val == null) continue;             // unknown -> drop the slot
                long iv = Math.round(val);
                if (iv < 0 || iv > 100) continue;
                clips.add(String.format(java.util.Locale.US, "num/%04d.wav", iv));
            } else {
                clips.add(t.contains(".") ? t : t + ".wav");
            }
        }
        if (clips.isEmpty()) return;
        Log.i(TAG, r.name + " -> " + clips);
        if (player != null) player.play(clips);
    }

    private static int safeInt(String s) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; } }
    private static double safeDouble(String s) { try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0; } }
}
