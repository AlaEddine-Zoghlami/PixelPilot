package com.openipc.pixelpilot.apfpv;

/**
 * RfLimits — EIRP-compliant TX-power math for the RTL8812AU ground-station dongle.
 *
 * The legal limiter is EIRP (radiated), not conducted power:
 *     EIRP(dBm) = conducted_power(dBm) + antenna_gain(dBi)
 * so to stay at a legal EIRP cap with a higher-gain antenna we must LOWER the
 * conducted power by the gain. The configured gain is that of the antenna the GS
 * DONGLE TRANSMITS on (worst case). The EMAX Wyvern Link VRX kit ships an LHCP
 * omni (~2 dBi) + a patch (~6-8 dBi); use the patch -> default 6 dB. We derive the
 * conducted target per mode:
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

    public static final double DEFAULT_ANTENNA_GAIN_DB = 6.0;

    // RTL8812AU 0..63 index linear estimate (card-dependent — see class doc).
    static final double IDX_PER_DB = 2.0;   // ~0.5 dB per index step
    static final double IDX0_DBM   = 0.0;   // index 0 ~= 0 dBm (anchor)

    /** Target conducted power (dBm) to hold the EIRP cap with the given antenna. */
    public static double conductedDbm(double eirpCapDbm, double antennaGainDb) {
        return eirpCapDbm - antennaGainDb;
    }

    /** Map a conducted power (dBm) to the RTL8812AU 0..63 power index (clamped). */
    public static int conductedToIndex(double conductedDbm) {
        int idx = (int) Math.round((conductedDbm - IDX0_DBM) * IDX_PER_DB);
        return Math.max(0, Math.min(63, idx));
    }

    /** Legal TX power index for an EIRP cap + antenna gain (clamped 0..63). */
    public static int legalIndex(double eirpCapDbm, double antennaGainDb) {
        return conductedToIndex(conductedDbm(eirpCapDbm, antennaGainDb));
    }

    /** mW for a dBm value (for display). */
    public static int mw(double dbm) {
        return (int) Math.round(Math.pow(10.0, dbm / 10.0));
    }
}
