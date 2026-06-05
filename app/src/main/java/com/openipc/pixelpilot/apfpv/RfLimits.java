package com.openipc.pixelpilot.apfpv;

/**
 * RfLimits — EIRP-compliant TX-power math for the RTL8812AU ground-station dongle.
 *
 * The legal limiter is EIRP (radiated), not conducted power:
 *     EIRP(dBm) = conducted_power(dBm) + antenna_gain(dBi)
 * so to stay at a legal EIRP cap with a higher-gain antenna we must LOWER the
 * conducted power by the gain. The configured gain is that of the antenna the GS
 * DONGLE TRANSMITS on (worst case). The EMAX Wyvern Link VRX kit ships an LHCP
 * omni (~2 dBi, circular) + a flat hexagon patch (~6-8 dBi); use the patch and
 * round up -> default 8 dB. We derive the conducted target per mode:
 *
 *   APFPV  5.2 GHz UNII-1 : 200 mW EIRP = 23.0 dBm   -> conducted = 23.0 - gain
 *   WFB    5.8 GHz        :  25 mW EIRP = 14.0 dBm   -> conducted = 14.0 - gain
 *
 * The dBm math above is EXACT. The conducted-dBm -> RTL8812AU power INDEX (0..63)
 * step is NOT: that index is unitless and card-dependent (only the RTL8812EU takes
 * real mBm, where mBm/100 = dBm). We map with a documented linear estimate
 * (~0.5 dB per index step, anchored at index 0 = 0 dBm) and CLAMP to [0,63]. Treat
 * the resulting index as a calibrated starting point — verify true EIRP with a
 * meter for your specific dongle + antenna. Tune IDX_PER_DB / IDX0_DBM if measured.
 */
public final class RfLimits {
    private RfLimits() {}

    /** Legal EIRP caps (dBm) in Germany for each mode's band. */
    public static final double EIRP_APFPV_DBM = 23.0;   // 200 mW, 5.2 GHz UNII-1
    public static final double EIRP_WFB_DBM   = 14.0;   //  25 mW, 5.8 GHz

    public static final double DEFAULT_ANTENNA_GAIN_DB = 8.0;   // EMAX VRX hexagon patch (conservative)

    // Measured RTL8812AU index<->conducted-power, from OpenHD's HackRF table for
    // the ASUS AC56-USB (a bare 8812au, used as a proxy for the EMAX VRX — STILL
    // card-dependent, so verify if you can). Endpoints 0 and 63 are extrapolated.
    //   idx 19=>10-12mW 25=>25-30 30=>45-50 35=>70-80 37=>100-110 40=>120-140
    //       45=>200-230 50=>280-320 55=>380-400 58=>420-450 (mW)
    private static final int[]    IDX = {   0,   19,   25,   30,   35,   37,   40,   45,   50,   55,   58,   63 };
    private static final double[] DBM = { 2.6, 10.4, 14.4, 16.8, 18.8, 20.2, 21.1, 23.3, 24.8, 25.9, 26.4, 27.5 };

    /** Target conducted power (dBm) to hold the EIRP cap with the given antenna. */
    public static double conductedDbm(double eirpCapDbm, double antennaGainDb) {
        return eirpCapDbm - antennaGainDb;
    }

    /** Map a conducted power (dBm) to the RTL8812AU 0..63 index via the measured
     *  table (piecewise-linear, clamped). */
    public static int conductedToIndex(double conductedDbm) {
        if (conductedDbm <= DBM[0]) return IDX[0];
        for (int i = 1; i < DBM.length; i++) {
            if (conductedDbm <= DBM[i]) {
                double f = (conductedDbm - DBM[i - 1]) / (DBM[i] - DBM[i - 1]);
                int idx = (int) Math.round(IDX[i - 1] + f * (IDX[i] - IDX[i - 1]));
                return Math.max(0, Math.min(63, idx));
            }
        }
        return 63;
    }

    /** Inverse: approximate conducted dBm for a given index (for display). */
    public static double indexToDbm(int idx) {
        if (idx <= IDX[0]) return DBM[0];
        for (int i = 1; i < IDX.length; i++) {
            if (idx <= IDX[i]) {
                double f = (double) (idx - IDX[i - 1]) / (IDX[i] - IDX[i - 1]);
                return DBM[i - 1] + f * (DBM[i] - DBM[i - 1]);
            }
        }
        return DBM[DBM.length - 1];
    }

    /** Legal TX power index for an EIRP cap + antenna gain (clamped 0..63). */
    public static int legalIndex(double eirpCapDbm, double antennaGainDb) {
        return conductedToIndex(conductedDbm(eirpCapDbm, antennaGainDb));
    }

    /** mW for a dBm value (for display). */
    public static int mw(double dbm) {
        return (int) Math.round(Math.pow(10.0, dbm / 10.0));
    }

    // ---- EIRP estimation from a received RSSI (no meter needed) --------------

    /** Free-space path loss (dB) at distance d (m), frequency f (MHz). */
    public static double fsplDb(double dMeters, double fMHz) {
        if (dMeters < 0.1) dMeters = 0.1;
        return 20.0 * Math.log10(dMeters) + 20.0 * Math.log10(fMHz) - 27.55;
    }

    /** Estimate a TRANSMITTER's EIRP (dBm) from a received RSSI at distance d.
     *  rxGainDbi = the MEASURING receiver's antenna gain (phone internal ~0;
     *  the VRX dongle omni ~2 / patch ~8 if the dongle is the measurer). */
    public static double estimateEirpDbm(double rssiDbm, double dMeters, double fMHz, double rxGainDbi) {
        return rssiDbm + fsplDb(dMeters, fMHz) - rxGainDbi;
    }

    /** 5/2.4 GHz channel -> centre frequency (MHz). */
    public static int channelToFreqMHz(int ch) {
        if (ch >= 14 && ch <= 196) return 5000 + 5 * ch;     // 5 GHz
        if (ch == 14) return 2484;
        if (ch >= 1 && ch <= 13) return 2407 + 5 * ch;       // 2.4 GHz
        return 5200;                                         // fallback UNII-1
    }

    /** Legal EIRP cap (dBm) for a frequency, or NaN if outside the regulated bands. */
    public static double eirpCapForFreq(double fMHz) {
        if (fMHz >= 5170 && fMHz <= 5250) return EIRP_APFPV_DBM;   // UNII-1 200 mW
        if (fMHz >= 5725 && fMHz <= 5875) return EIRP_WFB_DBM;     // 5.8 GHz 25 mW
        return Double.NaN;
    }
}
