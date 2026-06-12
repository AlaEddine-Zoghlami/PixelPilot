package com.openipc.pixelpilot;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.net.VpnService;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.format.Formatter;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SubMenu;
import android.view.View;
import android.view.WindowManager;
import android.webkit.HttpAuthHandler;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;

import com.github.mikephil.charting.charts.PieChart;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;
import com.github.mikephil.charting.formatter.PercentFormatter;
import com.openipc.mavlink.MavlinkData;
import com.openipc.mavlink.MavlinkNative;
import com.openipc.mavlink.MavlinkUpdate;
import com.openipc.pixelpilot.databinding.ActivityVideoBinding;
import com.openipc.pixelpilot.osd.OSDElement;
import com.openipc.pixelpilot.osd.OSDManager;
import com.openipc.videonative.DecodingInfo;
import com.openipc.videonative.IVideoParamsChanged;
import com.openipc.videonative.VideoPlayer;
import com.openipc.wfbngrtl8812.WfbNGStats;
import com.openipc.wfbngrtl8812.WfbNGStatsChanged;
import com.openipc.wfbngrtl8812.WfbNgLink;
import com.openipc.wfbngrtl8812.ApfpvStaLink;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

// Most basic implementation of an activity that uses VideoNative to stream a video
// Into an Android Surface View
public class VideoActivity extends AppCompatActivity implements IVideoParamsChanged,
        WfbNGStatsChanged, MavlinkUpdate, SettingsChanged {
    private static final String TAG = "pixelpilot";
    private static final int PICK_KEY_REQUEST_CODE = 1;
    private static final int PICK_DVR_REQUEST_CODE = 2;
    private static WifiManager wifiManager;
    final Handler handler = new Handler(Looper.getMainLooper());
    final Runnable runnable = new Runnable() {
        public void run() {
            MavlinkNative.nativeCallBack(VideoActivity.this);
            handler.postDelayed(this, 100);
        }
    };
    protected DecodingInfo mDecodingInfo;
    int lastVideoW = 0, lastVideoH = 0, lastCodec = 1;
    WfbLinkManager wfbLinkManager;
    BroadcastReceiver batteryReceiver;
    VideoPlayer videoPlayer;
    private ActivityVideoBinding binding;
    private OSDManager osdManager;
    private ParcelFileDescriptor dvrFd = null;
    private Timer dvrIconTimer = null;
    private Timer recordTimer = null;
    private int seconds = 0;
    private boolean isVRMode = false;
    private ConstraintLayout constraintLayout;
    private ConstraintSet constraintSet;
    private WfbNgLink wfbLink;
    // --- APFPV station mode (dongle associates to greg's AP via forked devourer) ---
    private ApfpvStaLink apfpvLink;
    private ApfpvLinkManager apfpvLinkManager;
    private com.openipc.pixelpilot.apfpv.ApfpvWifiManager apfpvWifiManager;
    private LinkModeCoordinator.Mode linkMode = LinkModeCoordinator.Mode.WFB;
    private boolean apfpvMode = false;   // true for EITHER apfpv mode (dongle or phone-wifi); see linkMode for which
    private volatile String lastWifiStatus = "idle";   // last APFPV phone-Wi-Fi status line
    private boolean vrxBeaconOn = false;                // VRX EIRP-cal beacon (master phone)
    static final String VRX_CAL_SSID = "APFPV-VRX-CAL"; // hardcoded SSID for the 2-phone EIRP rig
    private LinkModeCoordinator linkModeCoordinator;

    private static final String PREF_DRONE_USERNAME = "drone_username";
    private static final String PREF_DRONE_PASSWORD = "drone_password";

    public boolean getVRSetting() {
        return getSharedPreferences("general", Context.MODE_PRIVATE).getBoolean("vr-mode", false);
    }

    public void setVRSetting(boolean v) {
        SharedPreferences prefs = getSharedPreferences("general", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean("vr-mode", v);
        editor.commit();
    }

    public static int getChannel(Context context) {
        return context.getSharedPreferences("general",
                Context.MODE_PRIVATE).getInt("wifi-channel", 161);
    }

    public static int getBandwidth(Context context) {
        return context.getSharedPreferences("general",
                Context.MODE_PRIVATE).getInt("bandwidth", 20);
    }

    public static String wirelessInfo() {
        int address = wifiManager.getConnectionInfo().getIpAddress();
        return (address == 0) ? null : Formatter.formatIpAddress(address);
    }

    static String paddedDigits(int val, int len) {
        StringBuilder sb = new StringBuilder(String.format("%d", val));
        while (sb.length() < len) {
            sb.append('\t');
        }
        return sb.toString();
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xFF & b);
            if (hex.length() == 1) {
                // Append a leading zero for single digit hex values
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    private void resetApp() {
        // Restart the app
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
            System.exit(0); // Ensure the app is fully restarted
        }
    }

    private boolean hasUriPermission(Uri uri) {
        for (UriPermission perm : getContentResolver().getPersistedUriPermissions()) {
            if (perm.getUri().equals(uri) && perm.isWritePermission()) {
                return true;
            }
        }
        return false;
    }

    private void resetFolderPermissions() {
        // Retrieve the stored DVR folder URI
        SharedPreferences prefs = getSharedPreferences("general", Context.MODE_PRIVATE);
        String dvrFolderUriString = prefs.getString("dvr_folder_", null);
        if (dvrFolderUriString == null) {
            Toast.makeText(this, "No folder permissions to reset.", Toast.LENGTH_SHORT).show();
            return;
        }

        Uri dvrUri = Uri.parse(dvrFolderUriString);

        // Revoke persisted URI permissions
        for (UriPermission perm : getContentResolver().getPersistedUriPermissions()) {
            if (perm.getUri().equals(dvrUri)) {
                getContentResolver().releasePersistableUriPermission(perm.getUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION |
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                Log.d(TAG, "Released URI permission for: " + perm.getUri());
            }
        }

        // Clear the stored URI from SharedPreferences
        SharedPreferences.Editor editor = prefs.edit();
        editor.remove("dvr_folder_");
        editor.apply();

        // Stop any ongoing DVR recording
        if (dvrFd != null) {
            stopDvr();
        }

        // Update the record button icon to default
        binding.imgBtnRecord.setImageResource(R.drawable.record);

        // Reset any related UI elements
        binding.txtRecordLabel.setVisibility(View.GONE);
        binding.imgRecIndicator.setVisibility(View.INVISIBLE);

        // Inform the user
        Toast.makeText(this, "Folder permissions have been reset.", Toast.LENGTH_LONG).show();

        // Optionally, prompt the user to select a new folder immediately
        // Uncomment the following lines if you want to prompt immediately
        /*
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_DVR_REQUEST_CODE);
        */
    }

    // Lifecycle - onCreate

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "lifecycle onCreate");
        super.onCreate(savedInstanceState);

        // UI Setup
        initializeUI();

        // WFB-NG Setup
        initializeWfbNg();

        // Options like tx power must be initialized explicitly
        initDefaultOptions();

        // Video Player(s) Setup
        initializeVideoPlayers();

        // VR-specific SeekBars (only if VR mode)
        setupVRSeekBarsIfNeeded();

        // OSD Manager Setup
        setupOSDManager();

        // PieChart Setup
        setupPieChart();

        // Button Handlers
        setupButtonHandlers();

        // Mavlink Setup
        setupMavlink();

        // Battery Receiver
        setupBatteryReceiver();

        // wfbNg VPN Service
        startVpnService();
    }

    // ----------------------------------------------------------------------------
    // UI SETUP
    // ----------------------------------------------------------------------------

    /**
     * Initializes basic UI components, including window flags and layout binding.
     */
    private void initializeUI() {
        binding = ActivityVideoBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

        // FPV wants the highest refresh the panel supports (e.g. 120 Hz). Otherwise Android may keep
        // it at 60 Hz (power-save, no app request, or while screen-casting), which caps VISIBLE fps at
        // 60 and — with a 120 fps source — backs the GL render queue up into stale/old frames. Request
        // the max-refresh display mode at the current resolution.
        try {
            android.view.Display disp = getWindowManager().getDefaultDisplay();
            android.view.Display.Mode cur = disp.getMode();
            android.view.Display.Mode best = cur;
            for (android.view.Display.Mode m : disp.getSupportedModes()) {
                if (m.getPhysicalWidth() == cur.getPhysicalWidth()
                    && m.getPhysicalHeight() == cur.getPhysicalHeight()
                    && m.getRefreshRate() > best.getRefreshRate()) best = m;
            }
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.preferredDisplayModeId = best.getModeId();
            getWindow().setAttributes(lp);
            Log.i(TAG, "Requested display mode " + best.getPhysicalWidth() + "x"
                + best.getPhysicalHeight() + "@" + best.getRefreshRate() + "Hz");
        } catch (Throwable t) { Log.w(TAG, "refresh-rate request failed: " + t); }

        // On-device log capture: this phone has ONE USB-C port, so the dongle and USB-adb can't be
        // plugged at once — we can't watch logcat live during a dongle test. So the app writes its
        // OWN logcat to a file to pull AFTER the test (reconnect adb with the dongle out). The key
        // diagnostics (rxd-health RX loss/dup/decrypt-fail, "VideoDecoder FPS", apfpv state, devourer)
        // are this process's own logs, captured even without READ_LOGS. Child logcat dies with the app.
        try {
            java.io.File lf = new java.io.File(getExternalFilesDir(null), "pixelpilot_dbg.log");
            Runtime.getRuntime().exec(new String[]{"logcat", "-f", lf.getAbsolutePath(),
                "-r", "16384", "-n", "4", "-v", "time"});
            Log.i(TAG, "logcat -> " + lf.getAbsolutePath());
        } catch (Throwable t) { Log.w(TAG, "log-to-file failed: " + t); }

        wifiManager = (WifiManager) getSystemService(WIFI_SERVICE);
    }

    // ----------------------------------------------------------------------------
    // WFB-NG SETUP
    // ----------------------------------------------------------------------------

    /**
     * Initializes WFB-NG related logic such as setting default gs.key and linking
     * to WFB-NG stats changes.
     */
    private void initializeWfbNg() {
        setDefaultGsKey();
        copyGSKey();
        wfbLink = new WfbNgLink(this);
        wfbLink.SetWfbNGStatsChanged(this);
        wfbLinkManager = new WfbLinkManager(this, binding, wfbLink);

        // APFPV modes (parallel to WFB-ng). Restored from prefs. Two APFPV
        // variants: dongle station (ApfpvLinkManager) and phone Wi-Fi
        // (ApfpvWifiManager, no dongle). Video path (port 5600) is shared.
        apfpvLink = new ApfpvStaLink(this);
        apfpvLinkManager = new ApfpvLinkManager(this, binding, apfpvLink);
        apfpvWifiManager = new com.openipc.pixelpilot.apfpv.ApfpvWifiManager(this,
            new com.openipc.pixelpilot.apfpv.ApfpvWifiManager.Listener() {
                @Override public void onState(String s) { lastWifiStatus = s; runOnUiThread(() ->
                    Toast.makeText(VideoActivity.this, "APFPV Wi-Fi: " + s, Toast.LENGTH_SHORT).show()); }
                @Override public void onRssi(int dbm)   { /* OSD hook: WifiManager RSSI */ }
                @Override public void onError(String d) { lastWifiStatus = d; runOnUiThread(() ->
                    Toast.makeText(VideoActivity.this, "APFPV Wi-Fi: " + d, Toast.LENGTH_LONG).show()); }
            });
        String savedMode = getSharedPreferences("pixelpilot", MODE_PRIVATE)
                        .getString("link_mode", "wfb");
        linkMode = "apfpv".equals(savedMode) ? LinkModeCoordinator.Mode.APFPV
                 : "apfpv_wifi".equals(savedMode) ? LinkModeCoordinator.Mode.APFPV_WIFI
                 : LinkModeCoordinator.Mode.WFB;
        apfpvMode = (linkMode != LinkModeCoordinator.Mode.WFB);
        // Seamless switch coordinator (mirrors VRX rx_mode logic), now 3-way.
        linkModeCoordinator = new LinkModeCoordinator(this, binding,
                wfbLinkManager, apfpvLinkManager, apfpvWifiManager, linkMode);
        // Mode-agnostic USB hotplug: a physical dongle remove/insert reconnects the CURRENT
        // dongle mode (APFPV/WFB). Fired by WfbLinkManager.onReceive on any USB attach/detach/perm.
        wfbLinkManager.onUsbHotplug = () ->
            linkModeCoordinator.onUsbHotplug((m, ok, detail) ->
                runOnUiThread(() -> {
                    this.linkMode = m;
                    Toast.makeText(this, detail, Toast.LENGTH_SHORT).show();
                }));
    }

    /**
     * Seamless transport switch between the three modes (WFB / APFPV dongle /
     * APFPV phone Wi-Fi). Explicit — chosen from the menu, never auto-detected.
     * Credentials pulled from prefs.
     */
    public void setLinkMode(LinkModeCoordinator.Mode target) {
        String ssid = getSharedPreferences("pixelpilot", MODE_PRIVATE).getString("apfpv_ssid", "Taiga");
        String pass = getSharedPreferences("pixelpilot", MODE_PRIVATE).getString("apfpv_pass", "@Vdslvdsl2023");
        linkModeCoordinator.switchTo(target, ssid, pass,
            (m, ok, detail) -> runOnUiThread(() -> {
                this.linkMode = m;
                this.apfpvMode = (m != LinkModeCoordinator.Mode.WFB);
                // WFB owns the dongle only in WFB mode; gate its auto-start so a
                // later onResume / USB event doesn't fight APFPV for the dongle.
                wfbLinkManager.setAutoStart(m == LinkModeCoordinator.Mode.WFB);
                Toast.makeText(this, detail, Toast.LENGTH_SHORT).show();
            }));
    }

    public boolean isApfpvMode() { return apfpvMode; }
    public LinkModeCoordinator.Mode linkMode() { return linkMode; }

    // ----------------------------------------------------------------------------
    // VIDEO PLAYER SETUP
    // ----------------------------------------------------------------------------

    /**
     * Initializes VideoPlayer and configures surfaces for VR or standard mode.
     */
    private void initializeVideoPlayers() {
        videoPlayer = new VideoPlayer(this);
        videoPlayer.setIVideoParamsChanged(this);

        isVRMode = getVRSetting();

        if (isVRMode) {
            setupVRVideoPlayers();
        } else {
            setupStandardVideoPlayer();
        }
    }

    /**
     * Configures the UI for VR mode by attaching callbacks to the left and right SurfaceViews.
     */
    private void setupVRVideoPlayers() {
        binding.mainVideo.setVisibility(View.GONE);
        binding.surfaceViewLeft.getHolder().addCallback(videoPlayer.configure1(0));
        binding.surfaceViewRight.getHolder().addCallback(videoPlayer.configure1(1));
    }

    /**
     * Configures the UI for standard, single-surface video playback.
     */
    private void setupStandardVideoPlayer() {
        binding.surfaceViewRight.setVisibility(View.GONE);
        binding.surfaceViewLeft.setVisibility(View.GONE);
        binding.mainVideo.getHolder().addCallback(videoPlayer.configure1(0));
    }

    // ----------------------------------------------------------------------------
    // VR SEEK BARS (only in VR mode)
    // ----------------------------------------------------------------------------

    /**
     * Initializes and configures SeekBars for VR mode to adjust the margin and size of surfaces.
     * If not in VR mode, this method does nothing.
     */
    private void setupVRSeekBarsIfNeeded() {
        if (!isVRMode) return;

        constraintLayout = binding.frameLayout;
        constraintSet = new ConstraintSet();
        constraintSet.clone(constraintLayout);

        configureVRSeekBars();
        configureVRSeekBarVisibility();
        configureVRSeekBarListeners();
    }

    /**
     * Configures both the margin (binding.seekBar) and distance (binding.distanceSeekBar) SeekBars.
     */
    private void configureVRSeekBars() {
        // Rotate the first seekBar 180 degrees
        binding.seekBar.setRotation(180);

        // Retrieve saved progress for both seekBars
        SharedPreferences sharedPreferences = getSharedPreferences("SeekBarPrefs", MODE_PRIVATE);
        SharedPreferences sharedPreferencesd = getSharedPreferences("SeekBarPrefsD", MODE_PRIVATE);

        int savedProgress = sharedPreferences.getInt("seekBarProgress", 1);
        int savedDistanceProgress = sharedPreferencesd.getInt("distanceSeekBarProgress", 1);

        // Apply saved progress values
        binding.seekBar.setProgress(savedProgress);
        binding.distanceSeekBar.setProgress(savedDistanceProgress);

        // Make them visible initially
        binding.seekBar.setVisibility(View.VISIBLE);
        binding.distanceSeekBar.setVisibility(View.VISIBLE);

        // Apply initial constraints
        applyVRMargins(savedProgress);
        applyVRDistance(savedDistanceProgress);
    }

    /**
     * Manages hiding and showing the SeekBars after some delay or upon user touch.
     */
    private void configureVRSeekBarVisibility() {
        // Hide SeekBars after 3 seconds
        handler.postDelayed(() -> {
            binding.seekBar.setVisibility(View.GONE);
            binding.distanceSeekBar.setVisibility(View.GONE);
            updateViewRatio(R.id.surfaceViewLeft, lastVideoW, lastVideoH);
            updateViewRatio(R.id.surfaceViewRight, lastVideoW, lastVideoH);
        }, 3000);

        // Show SeekBars when the layout is touched
        binding.frameLayout.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                binding.seekBar.setVisibility(View.VISIBLE);
                binding.distanceSeekBar.setVisibility(View.VISIBLE);
                handler.postDelayed(() -> {
                    binding.seekBar.setVisibility(View.GONE);
                    binding.distanceSeekBar.setVisibility(View.GONE);
                    updateViewRatio(R.id.surfaceViewLeft, lastVideoW, lastVideoH);
                    updateViewRatio(R.id.surfaceViewRight, lastVideoW, lastVideoH);
                }, 3000);
            }
            return false;
        });
    }

    /**
     * Sets listeners on the SeekBars to adjust margins and distances in real time.
     */
    private void configureVRSeekBarListeners() {
        binding.seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                applyVRMargins(progress);
                saveSeekBarValue("SeekBarPrefs", "seekBarProgress", progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        binding.distanceSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar distanceSeekBar, int progress, boolean fromUser) {
                applyVRDistance(progress);
                saveSeekBarValue("SeekBarPrefsD", "distanceSeekBarProgress", progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }

    /**
     * Adjusts margins for left/right SurfaceViews based on progress.
     */
    private void applyVRMargins(int progress) {
        int margin = progress * 20; // Adjust multiplier as needed
        constraintSet.setMargin(R.id.surfaceViewLeft, ConstraintSet.START, margin);
        constraintSet.setMargin(R.id.surfaceViewRight, ConstraintSet.END, margin);
        constraintSet.applyTo(constraintLayout);
    }

    /**
     * Adjusts size for left/right SurfaceViews based on progress.
     */
    private void applyVRDistance(int progress) {
        int size = progress * 20; // Adjust multiplier as needed
        constraintSet.setMargin(R.id.surfaceViewLeft, ConstraintSet.END, size);
        constraintSet.setMargin(R.id.surfaceViewRight, ConstraintSet.START, size);
        constraintSet.applyTo(constraintLayout);
    }

    /**
     * Saves the SeekBar progress value to SharedPreferences.
     */
    private void saveSeekBarValue(String prefsName, String key, int progress) {
        SharedPreferences sp = getSharedPreferences(prefsName, MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        editor.putInt(key, progress);
        editor.apply();
    }

    // ----------------------------------------------------------------------------
    // OSD MANAGER
    // ----------------------------------------------------------------------------

    /**
     * Sets up the On-Screen Display (OSD) manager for telemetry or other overlays.
     */
    private void setupOSDManager() {
        osdManager = new OSDManager(this, binding);
        osdManager.setUp();
    }

    // ----------------------------------------------------------------------------
    // PIECHART SETUP
    // ----------------------------------------------------------------------------

    /**
     * Initializes and configures the PieChart to show link statistics (initially empty).
     */
    private void setupPieChart() {
        PieChart chart = binding.pcLinkStat;
        chart.getLegend().setEnabled(false);
        chart.getDescription().setEnabled(false);
        chart.setDrawHoleEnabled(true);
        chart.setHoleColor(Color.WHITE);
        chart.setHoleRadius(75f);
        chart.setCenterTextSize(12);
        chart.setCenterText("RSSI");
        chart.setHighlightPerTapEnabled(false);
        chart.setRotationEnabled(false);
        chart.setClickable(false);
        chart.setTouchEnabled(false);

        PieData emptyData = new PieData(new PieDataSet(new ArrayList<>(), ""));
        chart.setData(emptyData);
    }

    // ----------------------------------------------------------------------------
    // BUTTON HANDLERS
    // ----------------------------------------------------------------------------

    /**
     * Sets up the main button click listeners: Record and Settings.
     */
    private void setupButtonHandlers() {
        binding.imgBtnRecord.setOnClickListener(item -> startStopDvr());
        binding.btnSettings.setOnClickListener(this::showSettingsMenu);
    }

    /**
     * Shows the main settings popup menu and configures its items.
     */
    private void showSettingsMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);

        // Link mode FIRST — it decides the transport (WFB / APFPV dongle /
        // APFPV phone Wi-Fi) and therefore which of the items below apply.
        setupLinkModeSubMenu(popup);   // WFB-ng <-> APFPV transport toggle
        setupApfpvSubMenu(popup);      // APFPV ssid/pw + tools — placed right under Link Mode (APFPV modes only)

        // VR submenu
        setupVRSubMenu(popup);

        // Channel submenu
        setupChannelSubMenu(popup);

        // Bandwidth submenu
        setupBandwidthSubMenu(popup);

        // OSD submenu
        setupOSDSubMenu(popup);

        // Video flush (ms) — MediaTek only (the stale-frame flush is on the MTK decode path).
        if (isMtkDevice()) {
            setupVideoFlushSubMenu(popup);
        }

        // WFB-NG gs.key — only meaningful in WFB mode; APFPV uses WPA2 creds.
        if (!apfpvMode) {
            setupWFBSubMenu(popup);
        }

        // Adaptive link submenu
        setupAdaptiveLinkSubMenu(popup);

        // --- APFPV integration: the GS-menu knobs (air wfbng knobs for wfb;
        //     full apfpv set for apfpv modes) ---
        setupAirWfbngSubMenu(popup);   // air mcs/fec_k/fec_n/air_channel/width/mlink

        // Recording submenu
        setupRecordingSubMenu(popup);

        // Drone submenu
        setupDroneSubMenu(popup);

        // Help submenu
        setupHelpSubMenu(popup);

        popup.show();
    }

    /**
     * Submenu that toggles VR mode.
     */
    private void setupVRSubMenu(PopupMenu popup) {
        SubMenu vrMenu = popup.getMenu().addSubMenu("VR mode");
        MenuItem vrItem = vrMenu.add(getVRSetting() ? "On" : "Off");
        vrItem.setOnMenuItemClickListener(item -> {
            isVRMode = !getVRSetting();
            setVRSetting(isVRMode);
            vrItem.setTitle(isVRMode ? "Off" : "On");
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
            item.setActionView(new View(this));
            resetApp();
            return false;
        });
    }

    /**
     * Submenu that lists available channels and allows the user to select one.
     */
    private void setupChannelSubMenu(PopupMenu popup) {
        SubMenu chnMenu = popup.getMenu().addSubMenu("Channel");
        int channelPref = getChannel(this);

        // Create a disabled item to act as the header
        MenuItem headerItem = chnMenu.add("Current: " + channelPref);
        headerItem.setEnabled(false); // Makes it unclickable and grayed out like a label

        String[] channels = getResources().getStringArray(R.array.channels);
        for (String chnStr : channels) {
            chnMenu.add(chnStr).setOnMenuItemClickListener(item -> {
                onChannelSettingChanged(Integer.parseInt(chnStr));
                return true;
            });
        }
    }

    /**
     * Submenu that allows the user to select 20 or 40 MHz bandwidth.
     */
    private void setupBandwidthSubMenu(PopupMenu popup) {
        SubMenu bwMenu = popup.getMenu().addSubMenu("Bandwidth");
        int bandwidthPref = getBandwidth(this);

        // Add a disabled item to act as the header
        MenuItem headerItem = bwMenu.add("Current: " + bandwidthPref);
        headerItem.setEnabled(false); // Visually looks like a header, but unclickable

        String[] bws = getResources().getStringArray(R.array.bandwidths);
        for (String bwStr : bws) {
            bwMenu.add(bwStr).setOnMenuItemClickListener(item -> {
                onBandwidthSettingChanged(Integer.parseInt(bwStr));
                return true;
            });
        }
    }

    /** True on MediaTek SoCs — the stale-frame flush (and its menu item) is MTK-only. */
    static boolean isMtkDevice() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31
                && "mediatek".equalsIgnoreCase(android.os.Build.SOC_MANUFACTURER)) return true;
        } catch (Throwable ignored) {}
        String hw = android.os.Build.HARDWARE == null ? "" : android.os.Build.HARDWARE.toLowerCase(Locale.US);
        String bd = android.os.Build.BOARD == null ? "" : android.os.Build.BOARD.toLowerCase(Locale.US);
        return hw.startsWith("mt") || bd.startsWith("mt");
    }

    /** Menu-configured MTK stale-frame flush threshold in ms (0 = off). Default off — the baseline
     *  never drops frames; enable from the menu only if you want latency-capping. */
    static int getVideoFlushMs(Context ctx) {
        return ctx.getSharedPreferences("general", Context.MODE_PRIVATE).getInt("video_flush_ms", 60);
    }

    /**
     * Submenu to set the MTK stale-frame flush threshold (ms). 0 disables. Shown on MTK only.
     * Drops a stale decoded-frame backlog to the next keyframe instead of rendering late (FPV latency).
     */
    private void setupVideoFlushSubMenu(PopupMenu popup) {
        SubMenu s = popup.getMenu().addSubMenu("Video flush (ms)");
        int cur = getVideoFlushMs(this);
        s.add("Current: " + cur + " ms").setEnabled(false);
        for (int v = 0; v <= 200; v += 20) {
            final int val = v;
            s.add(v == 0 ? "0 (off)" : String.valueOf(v)).setOnMenuItemClickListener(i -> {
                getSharedPreferences("general", MODE_PRIVATE).edit().putInt("video_flush_ms", val).apply();
                if (videoPlayer != null) videoPlayer.setVideoFlushMs(val);
                Toast.makeText(this, "Video flush -> " + (val == 0 ? "off" : val + " ms"), Toast.LENGTH_SHORT).show();
                return true;
            });
        }
    }

    /**
     * Submenu handling OSD toggles and locks.
     */
    private void setupOSDSubMenu(PopupMenu popup) {
        SubMenu osd = popup.getMenu().addSubMenu("OSD");
        MenuItem lock = osd.add(osdManager.getTitle());
        lock.setOnMenuItemClickListener(item -> {
            osdManager.lockOSD(!osdManager.isOSDLocked());
            lock.setTitle(osdManager.getTitle());
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
            item.setActionView(new View(this));
            return false;
        });

        MenuItem boxes = osd.add("Element boxes");
        boxes.setCheckable(true);
        boxes.setChecked(osdManager.isBoxesEnabled());
        boxes.setOnMenuItemClickListener(item -> {
            boolean en = !osdManager.isBoxesEnabled();
            item.setChecked(en);
            osdManager.setBoxes(en);
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
            item.setActionView(new View(this));
            return false;
        });

        for (OSDElement element : osdManager.listOSDItems) {
            MenuItem itm = osd.add(element.name);
            itm.setCheckable(true);
            itm.setChecked(osdManager.isElementEnabled(element));
            itm.setOnMenuItemClickListener(menuItem -> {
                menuItem.setChecked(!menuItem.isChecked());
                osdManager.onOSDItemCheckChanged(element, menuItem.isChecked());
                menuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
                menuItem.setActionView(new View(this));
                return false;
            });
        }
    }

    /**
     * Submenu handling WFB-NG logic (e.g. selecting gs.key from storage).
     */
    private void setupWFBSubMenu(PopupMenu popup) {
        SubMenu wfb = popup.getMenu().addSubMenu("WFB-NG key");
        MenuItem keyBtn = wfb.add("gs.key");
        keyBtn.setOnMenuItemClickListener(item -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, PICK_KEY_REQUEST_CODE);
            return true;
        });
    }

    /**
     * Submenu for Adaptive link functionality.
     * It creates two options:
     * - "Enable": toggles the adaptive link quality thread
     * - "Power": a submenu that lets the user choose the TX power (1, 10, 20, 30, 40)
     */
    private void setupAdaptiveLinkSubMenu(PopupMenu popup) {
        SubMenu adaptiveMenu = popup.getMenu().addSubMenu("Adaptive link");

        SharedPreferences prefs = getSharedPreferences("general", MODE_PRIVATE);
        boolean adaptiveEnabled = prefs.getBoolean("adaptive_link_enabled", true);
        // Default shown in the menu: prefer the dongle's REAL reference index read
        // back from the driver (EFUSE-calibrated value for the connected unit). Only
        // the APFPV-dongle station link exposes a readback; if it's unavailable
        // (not connected yet, or WFB/phone-Wi-Fi mode) fall back to the stored pref.
        int adaptiveTxPower = prefs.getInt("adaptive_tx_power", 20);
        if (linkMode == LinkModeCoordinator.Mode.APFPV && apfpvLink != null) {
            int devIdx = apfpvLink.getTxPower();   // -1 if no device yet
            if (devIdx >= 0) adaptiveTxPower = devIdx;
        }

        // Adaptive link Enable option
        MenuItem adaptiveEnable = adaptiveMenu.add("Enable");
        adaptiveEnable.setCheckable(true);
        adaptiveEnable.setChecked(adaptiveEnabled);
        adaptiveEnable.setOnMenuItemClickListener(item -> {
            boolean newState = !item.isChecked();
            item.setChecked(newState);
            SharedPreferences.Editor editor = getSharedPreferences("general", MODE_PRIVATE).edit();
            editor.putBoolean("adaptive_link_enabled", newState);
            editor.apply();
            // Call instance method on the WfbNgLink instance via the wfbLinkManager.
            wfbLink.nativeSetAdaptiveLinkEnabled(newState);
            return true;
        });

        // Adaptive link Power submenu
        SubMenu powerSubMenu = adaptiveMenu.addSubMenu("Power");
        int[] txOptions = {1, 10, 20, 30, 40};
        for (int power : txOptions) {
            MenuItem powerItem = powerSubMenu.add(String.valueOf(power));
            powerItem.setCheckable(true);
            if (power == adaptiveTxPower) {
                powerItem.setChecked(true);
            }
            powerItem.setOnMenuItemClickListener(item -> {
                // Uncheck all items in the submenu
                for (int i = 0; i < powerSubMenu.size(); i++) {
                    powerSubMenu.getItem(i).setChecked(false);
                }
                item.setChecked(true);
                SharedPreferences.Editor editor = getSharedPreferences("general", MODE_PRIVATE).edit();
                editor.putInt("adaptive_tx_power", power);
                editor.apply();
                // Route the GS dongle TX power to whichever link drives the dongle
                // in the current mode. Same 0-63 index scale on the RTL8812AU
                // either way; only the owning native link object differs.
                switch (linkMode) {
                    case WFB:
                        wfbLink.nativeSetTxPower(power);
                        break;
                    case APFPV:
                        // dongle in station mode -> the APFPV station link owns it
                        if (apfpvLink != null) apfpvLink.setTxPower(power);
                        break;
                    case APFPV_WIFI:
                        // no dongle: the phone's own Wi-Fi radio TX power is not
                        // app-settable (controlled by Android/the modem), so this
                        // control has no effect here.
                        Toast.makeText(this,
                            "TX power is fixed in phone-Wi-Fi mode (no dongle)",
                            Toast.LENGTH_SHORT).show();
                        break;
                }
                return true;
            });
        }
        // Mode-aware "DE legal" TX power: derive the index from the band's EIRP cap
        // (APFPV 200 mW / WFB 25 mW) MINUS the configured antenna gain. Covers both
        // modes from one place; phone-Wi-Fi has no app-settable dongle power.
        MenuItem legalPwr = powerSubMenu.add("DE legal (EIRP − antenna)");
        legalPwr.setOnMenuItemClickListener(item -> {
            float gain = getSharedPreferences("pixelpilot", MODE_PRIVATE)
                    .getFloat("antenna_gain_db", (float) com.openipc.pixelpilot.apfpv.RfLimits.DEFAULT_ANTENNA_GAIN_DB);
            boolean apfpv = (linkMode == LinkModeCoordinator.Mode.APFPV);
            boolean wifi  = (linkMode == LinkModeCoordinator.Mode.APFPV_WIFI);
            double cap = apfpv ? com.openipc.pixelpilot.apfpv.RfLimits.EIRP_APFPV_DBM
                               : com.openipc.pixelpilot.apfpv.RfLimits.EIRP_WFB_DBM;
            int idx = com.openipc.pixelpilot.apfpv.RfLimits.legalIndex(cap, gain);
            double cond = com.openipc.pixelpilot.apfpv.RfLimits.conductedDbm(cap, gain);
            getSharedPreferences("general", MODE_PRIVATE).edit().putInt("adaptive_tx_power", idx).apply();
            if (wifi) {
                Toast.makeText(this, "Phone-Wi-Fi: TX power is OS-controlled (no dongle)", Toast.LENGTH_SHORT).show();
            } else {
                if (linkMode == LinkModeCoordinator.Mode.WFB) wfbLink.nativeSetTxPower(idx);
                else if (apfpvLink != null) apfpvLink.setTxPower(idx);
                Toast.makeText(this, String.format(java.util.Locale.US,
                    "%s legal: %d mW EIRP − %.0f dB ant = %.0f dBm (~%d mW), TX idx %d. Verify w/ a meter.",
                    apfpv ? "APFPV" : "WFB", com.openipc.pixelpilot.apfpv.RfLimits.mw(cap),
                    gain, cond, com.openipc.pixelpilot.apfpv.RfLimits.mw(cond), idx),
                    Toast.LENGTH_LONG).show();
            }
            return true;
        });

        // Adaptive use FEC submenu
        boolean fecEnabled = prefs.getBoolean("custom_fec_enabled", true);

        MenuItem fecEnable = adaptiveMenu.add("FEC");
        fecEnable.setCheckable(true);
        fecEnable.setChecked(fecEnabled);
        fecEnable.setOnMenuItemClickListener(item -> {
            boolean newState = !item.isChecked();
            item.setChecked(newState);
            SharedPreferences.Editor editor = getSharedPreferences("general", MODE_PRIVATE).edit();
            editor.putBoolean("custom_fec_enabled", newState);
            editor.apply();
            // Call instance method on the WfbNgLink instance via the wfbLinkManager.
            wfbLink.nativeSetUseFec(newState ? 1 : 0);
            return true;
        });

        // LDPC option
        boolean ldpcEnabled = prefs.getBoolean("custom_ldpc_enabled", true);
        MenuItem ldpcEnable = adaptiveMenu.add("LDPC");
        ldpcEnable.setCheckable(true);
        ldpcEnable.setChecked(ldpcEnabled);
        ldpcEnable.setOnMenuItemClickListener(item -> {
            boolean newState = !item.isChecked();
            item.setChecked(newState);
            SharedPreferences.Editor editor = getSharedPreferences("general", MODE_PRIVATE).edit();
            editor.putBoolean("custom_ldpc_enabled", newState);
            editor.apply();
            wfbLink.nativeSetUseLdpc(newState ? 1 : 0);
            return true;
        });

        // STBC option
        boolean stbcEnabled = prefs.getBoolean("custom_stbc_enabled", true);
        MenuItem stbcEnable = adaptiveMenu.add("STBC");
        stbcEnable.setCheckable(true);
        stbcEnable.setChecked(stbcEnabled);
        stbcEnable.setOnMenuItemClickListener(item -> {
            boolean newState = !item.isChecked();
            item.setChecked(newState);
            SharedPreferences.Editor editor = getSharedPreferences("general", MODE_PRIVATE).edit();
            editor.putBoolean("custom_stbc_enabled", newState);
            editor.apply();
            wfbLink.nativeSetUseStbc(newState ? 1 : 0);
            return true;
        });

        // --- FEC Thresholds menu (single dialog for all 5 settings) ---
        adaptiveMenu.add("FEC thresholds...").setOnMenuItemClickListener(item -> {
            showFecThresholdsDialog();
            return true;
        });
    }

    // Show dialog to configure FEC thresholds for all levels
    private void showFecThresholdsDialog() {
        SharedPreferences prefs = getSharedPreferences("general", MODE_PRIVATE);
        int lostTo5 = prefs.getInt("fec_lost_to_5", 2);
        int recTo4 = prefs.getInt("fec_recovered_to_4", 30);
        int recTo3 = prefs.getInt("fec_recovered_to_3", 24);
        int recTo2 = prefs.getInt("fec_recovered_to_2", 14);
        int recTo1 = prefs.getInt("fec_recovered_to_1", 8);

        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        final android.widget.EditText[] edits = new android.widget.EditText[5];
        String[] labels = {
                "Lost packets/sec for FEC 5", "Recovered/sec for FEC 4", "Recovered/sec for FEC 3",
                "Recovered/sec for FEC 2", "Recovered/sec for FEC 1"
        };
        int[] values = {lostTo5, recTo4, recTo3, recTo2, recTo1};
        for (int i = 0; i < 5; i++) {
            android.widget.TextView tv = new android.widget.TextView(this);
            tv.setText(labels[i]);
            layout.addView(tv);
            edits[i] = new android.widget.EditText(this);
            edits[i].setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            edits[i].setText(String.valueOf(values[i]));
            layout.addView(edits[i]);
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("FEC thresholds")
                .setView(layout)
                .setPositiveButton("OK", (dialog, which) -> {
                    SharedPreferences.Editor editor = prefs.edit();
                    try {
                        editor.putInt("fec_lost_to_5", Integer.parseInt(edits[0].getText().toString()));
                    } catch (Exception ignored) {
                    }
                    try {
                        editor.putInt("fec_recovered_to_4", Integer.parseInt(edits[1].getText().toString()));
                    } catch (Exception ignored) {
                    }
                    try {
                        editor.putInt("fec_recovered_to_3", Integer.parseInt(edits[2].getText().toString()));
                    } catch (Exception ignored) {
                    }
                    try {
                        editor.putInt("fec_recovered_to_2", Integer.parseInt(edits[3].getText().toString()));
                    } catch (Exception ignored) {
                    }
                    try {
                        editor.putInt("fec_recovered_to_1", Integer.parseInt(edits[4].getText().toString()));
                    } catch (Exception ignored) {
                    }
                    editor.apply();
                    setFecThresholdsFromPrefs();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    void initDefaultOptions() {
        SharedPreferences prefs = getSharedPreferences("general", MODE_PRIVATE);
        boolean adaptiveEnabled = prefs.getBoolean("adaptive_link_enabled", true);
        int adaptiveTxPower = prefs.getInt("adaptive_tx_power", 20);
        wfbLink.nativeSetAdaptiveLinkEnabled(adaptiveEnabled);
        // Apply saved GS dongle TX power to whichever link owns the dongle in the
        // current mode (phone-Wi-Fi has no dongle, so nothing to apply there).
        switch (linkMode) {
            case WFB:        wfbLink.nativeSetTxPower(adaptiveTxPower); break;
            case APFPV:      if (apfpvLink != null) apfpvLink.setTxPower(adaptiveTxPower); break;
            case APFPV_WIFI: /* no dongle: phone Wi-Fi TX power not app-settable */ break;
        }
        boolean fecEnabled = prefs.getBoolean("custom_fec_enabled", true);
        wfbLink.nativeSetUseFec(fecEnabled ? 1 : 0);

        // LDPC and STBC default options
        boolean ldpcEnabled = prefs.getBoolean("custom_ldpc_enabled", true);
        wfbLink.nativeSetUseLdpc(ldpcEnabled ? 1 : 0);

        boolean stbcEnabled = prefs.getBoolean("custom_stbc_enabled", true);
        wfbLink.nativeSetUseStbc(stbcEnabled ? 1 : 0);

        setFecThresholdsFromPrefs();
    }

    // Read FEC thresholds from prefs and call native method to apply
    private void setFecThresholdsFromPrefs() {
        SharedPreferences prefs = getSharedPreferences("general", MODE_PRIVATE);
        int lostTo5 = prefs.getInt("fec_lost_to_5", 2);
        int recTo4 = prefs.getInt("fec_recovered_to_4", 30);
        int recTo3 = prefs.getInt("fec_recovered_to_3", 24);
        int recTo2 = prefs.getInt("fec_recovered_to_2", 14);
        int recTo1 = prefs.getInt("fec_recovered_to_1", 8);
        if (wfbLink != null) {
            wfbLink.setFecThresholds(lostTo5, recTo4, recTo3, recTo2, recTo1);
        }
    }

    // ========================================================================
    //  APFPV integration submenus — complete the GS-menu feature set for BOTH
    //  modes. Channel/Bandwidth/Adaptive-link/OSD/Recording already exist above;
    //  these add what was missing: the transport toggle, the AIR-side WFB-ng
    //  knobs, and the full APFPV/aalink/camera controls.
    // ========================================================================

    /** Explicit link-mode picker: WFB-ng / APFPV dongle / APFPV phone Wi-Fi. */
    private void setupLinkModeSubMenu(PopupMenu popup) {
        SubMenu m = popup.getMenu().addSubMenu("Link mode");
        String cur = linkMode == LinkModeCoordinator.Mode.APFPV ? "APFPV (dongle)"
                   : linkMode == LinkModeCoordinator.Mode.APFPV_WIFI ? "APFPV (phone Wi-Fi)"
                   : "WFB-ng";
        m.add("Current: " + cur).setEnabled(false);
        m.add("WFB-ng (dongle monitor)").setOnMenuItemClickListener(i -> {
            setLinkMode(LinkModeCoordinator.Mode.WFB); return true; });
        m.add("APFPV (dongle station)").setOnMenuItemClickListener(i -> {
            setLinkMode(LinkModeCoordinator.Mode.APFPV); return true; });
        m.add("APFPV (phone Wi-Fi, no dongle)").setOnMenuItemClickListener(i -> {
            setLinkMode(LinkModeCoordinator.Mode.APFPV_WIFI); return true; });
    }

    /**
     * AIR-side WFB-ng knobs (mcs_index/fec_k/fec_n/air_channel/width/mlink) that
     * gsmenu sets via SSH `wifibroadcast cli -s`. Needs AirSshClient wired with
     * an SSH lib (see its class doc); until then taps surface the queued command.
     */
    private void setupAirWfbngSubMenu(PopupMenu popup) {
        // Air WFB-ng radio knobs (cli -s .wireless/.broadcast + wifibroadcast
        // restart), exactly like the OpenIPC GS gsmenu. These configure the air
        // unit's WFB-ng radio, which only runs in WFB-ng mode. In APFPV (dongle
        // OR phone Wi-Fi) the air runs the APFPV stack (aalink + AP) instead, so
        // these knobs would target inactive services — hide them. APFPV radio
        // settings live in the APFPV submenu (aalink channel/scale/etc).
        if (apfpvMode) return;   // WFB-ng mode only
        final com.openipc.pixelpilot.apfpv.AirSshClient ssh = new com.openipc.pixelpilot.apfpv.AirSshClient();
        com.openipc.pixelpilot.apfpv.GsMenuWfbng w = new com.openipc.pixelpilot.apfpv.GsMenuWfbng();
        pickInt(popup, "Air MCS index", w.mcsIndex, 0, 7, 1, v -> ssh.setMcsIndex(v,(o,d)->{}));
        pickInt(popup, "Air Width", w.width, 20, 40, 20, v -> ssh.setWidth(v,(o,d)->{}));
        pickInt(popup, "Air FEC k", w.fecK, 1, 12, 1, v -> ssh.setFecK(v,(o,d)->{}));
        pickInt(popup, "Air FEC n", w.fecN, 1, 16, 1, v -> ssh.setFecN(v,(o,d)->{}));
        pickInt(popup, "Air channel", w.airChannel, 36, 165, 1, v -> ssh.setAirChannel(v,(o,d)->{}));
        pickInt(popup, "Air TX power", w.airTxPower, 1, 30, 1, v -> ssh.setTxPower(v,(o,d)->{}));
    }

    /** Full APFPV controls: connection (ssid/pw) + aalink + camera, when in APFPV mode. */
    private void setupApfpvSubMenu(PopupMenu popup) {
        if (!apfpvMode) return;   // only relevant in APFPV mode
        com.openipc.pixelpilot.apfpv.ApfpvSettings cfg = loadApfpvSettings();
        // Air config goes over SSH (192.168.0.1), exactly like the OpenIPC GS
        // gsmenu (wifibroadcast cli / cli + killall majestic / sed aalink.conf).
        final com.openipc.pixelpilot.apfpv.AirSshClient ssh = new com.openipc.pixelpilot.apfpv.AirSshClient();
        ssh.useApfpvHost();

        // APFPV connection actions (simple items on the main menu, gs-style)
        SubMenu m = popup.getMenu().addSubMenu("APFPV");
        m.add("SSID / Password...").setOnMenuItemClickListener(i -> { showApfpvCredsDialog(); return true; });
        // Antenna gain entry (dBi, default 6) — feeds the EIRP-compliant TX power
        // below. EIRP = conducted + gain, so a higher-gain antenna LOWERS the
        // commanded conducted power to hold the legal cap.
        float gainNow = getSharedPreferences("pixelpilot", MODE_PRIVATE)
                .getFloat("antenna_gain_db", (float) com.openipc.pixelpilot.apfpv.RfLimits.DEFAULT_ANTENNA_GAIN_DB);
        m.add(String.format(java.util.Locale.US, "Antenna gain: %.0f dBi…", gainNow))
            .setOnMenuItemClickListener(i -> { showAntennaGainDialog(); return true; });
        // One-tap DE legal APFPV RF (dongle only — phone-Wi-Fi mode is regulated by
        // the OS). 5.2 GHz UNII-1 ch40 (centered, in 5170-5250), 20 MHz (200 mW
        // EIRP under the PSD cap). TX index derived from 200 mW EIRP MINUS the
        // configured antenna gain. EIRP still depends on the real antenna — verify.
        m.add("Apply DE legal limits").setOnMenuItemClickListener(i -> {
            float gain = getSharedPreferences("pixelpilot", MODE_PRIVATE)
                    .getFloat("antenna_gain_db", (float) com.openipc.pixelpilot.apfpv.RfLimits.DEFAULT_ANTENNA_GAIN_DB);
            int idx = com.openipc.pixelpilot.apfpv.RfLimits.legalIndex(
                    com.openipc.pixelpilot.apfpv.RfLimits.EIRP_APFPV_DBM, gain);
            double cond = com.openipc.pixelpilot.apfpv.RfLimits.conductedDbm(
                    com.openipc.pixelpilot.apfpv.RfLimits.EIRP_APFPV_DBM, gain);
            getSharedPreferences("pixelpilot", MODE_PRIVATE).edit()
                .putInt("apfpv_channel", 40).putInt("apfpv_bandwidth", 20).apply();
            getSharedPreferences("general", MODE_PRIVATE).edit()
                .putInt("adaptive_tx_power", idx).apply();
            if (apfpvLinkManager != null) {
                apfpvLinkManager.setChannel(40);
                apfpvLinkManager.setBandwidth(20);
            }
            if (apfpvLink != null) apfpvLink.setTxPower(idx);
            android.widget.Toast.makeText(this, String.format(java.util.Locale.US,
                "APFPV → DE legal: 5.2 GHz ch40, 20 MHz. 200 mW EIRP − %.0f dB ant = %.0f dBm conducted (TX idx %d, ~%d mW). Verify w/ a meter. Reconnect to apply.",
                gain, cond, idx, com.openipc.pixelpilot.apfpv.RfLimits.mw(cond)),
                android.widget.Toast.LENGTH_LONG).show();
            return true;
        });
        m.add("EIRP estimate…").setOnMenuItemClickListener(i -> { showEirpEstimatorDialog(); return true; });
        // Master phone (with dongle): broadcast a hardcoded SSID so a SECOND phone
        // (no dongle, ~2 m away) can EIRP-estimate this dongle's output.
        m.add(vrxBeaconOn ? "VRX beacon (cal): ON — tap to stop" : "VRX beacon (cal): start")
            .setOnMenuItemClickListener(i -> {
                if (apfpvLinkManager == null) return true;
                if (vrxBeaconOn) {
                    apfpvLinkManager.stopBeaconCal(); vrxBeaconOn = false;
                    Toast.makeText(this, "VRX beacon stopped", Toast.LENGTH_SHORT).show();
                } else {
                    int ch  = getSharedPreferences("pixelpilot", MODE_PRIVATE).getInt("apfpv_channel", 40);
                    int idx = getSharedPreferences("general", MODE_PRIVATE).getInt("adaptive_tx_power", 20);
                    vrxBeaconOn = apfpvLinkManager.startBeaconCal(VRX_CAL_SSID, ch, idx);
                    if (vrxBeaconOn) Toast.makeText(this, String.format(java.util.Locale.US,
                        "Beaconing \"%s\" ch%d, TX idx %d. On the 2nd phone (no dongle): EIRP estimate → pick \"%s\" → enter ~2 m.",
                        VRX_CAL_SSID, ch, idx, VRX_CAL_SSID), Toast.LENGTH_LONG).show();
                }
                return true;
            });
        m.add("Reconnect").setOnMenuItemClickListener(i -> {
            if (apfpvLinkManager != null) apfpvLinkManager.refreshAdapters(); return true; });
        // Mode-aware status: dongle has the full funnel (adapter -> connecting ->
        // connected -> APFPV found/not-found); phone-Wi-Fi only the last stages.
        String apfpvStatus = (linkMode == LinkModeCoordinator.Mode.APFPV_WIFI)
                ? "Wi-Fi: " + lastWifiStatus
                : (apfpvLinkManager != null ? apfpvLinkManager.statusLine() : "—");
        m.add("Status: " + apfpvStatus).setEnabled(false);
        m.add("RSSI: " + (apfpvLink != null ? apfpvLink.getRssi() : -99) + " dBm").setEnabled(false);
        if (apfpvLinkManager != null) {
            com.openipc.pixelpilot.apfpv.WlxAdapters wlx = apfpvLinkManager.adapters();
            if (wlx.count() > 1)
                for (com.openipc.pixelpilot.apfpv.WlxAdapters.Adapter a : wlx.all())
                    m.add((a.active ? "● " : "○ ") + a.name).setOnMenuItemClickListener(it -> {
                        apfpvLinkManager.selectAdapter(a.name); return true; });
        }

        // aalink keys — each its OWN top-level submenu, exactly like Channel/Bandwidth
        pickString(popup, "aalink MCS source", cfg.mcsSource,
            new String[]{"lowest","highest","both","uplink","downlink"},
            v -> { cfg.mcsSource = v; saveApfpv(cfg); ssh.setAalink("MCS_SOURCE", v, (o,d)->{}); });
        pickInt(popup, "aalink Throughput %", cfg.throughputPct, 10, 100, 5,
            v -> { cfg.throughputPct = v; saveApfpv(cfg); ssh.setAalink("THROUGHPUT_PCT", String.valueOf(v), (o,d)->{}); });
        // SCALE_TX_POWER: verified from greg10.2 aalink binary — the per-MCS PWR
        // table is multiplied by this value ONLY when it is < 1.0. Any value >= 1.0
        // is ignored (the code skips the multiply loop; no scale-up is possible).
        // Free numeric entry, clamped to [0, 1]: 0 disables, values < 1.0 reduce the
        // commanded TX power index proportionally (peak PWR_EU 2800 x 0.74 = ~2072).
        pickDoubleEntry(popup, "aalink TX power scale (0-1; <1.0 reduces power)",
            cfg.scaleTxPower, 0.0, 1.0,
            v -> { cfg.scaleTxPower = v; saveApfpv(cfg); ssh.setAalink("SCALE_TX_POWER", String.valueOf(v), (o,d)->{}); });
        pickInt(popup, "aalink Threshold shift", cfg.threshShift, -10, 10, 1,
            v -> { cfg.threshShift = v; saveApfpv(cfg); ssh.setAalink("THRESH_SHIFT", String.valueOf(v), (o,d)->{}); });
        pickInt(popup, "aalink High temp", cfg.highTemp, 70, 110, 5,
            v -> { cfg.highTemp = v; saveApfpv(cfg); ssh.setAalink("HIGH_TEMP", String.valueOf(v), (o,d)->{}); });
        pickDouble(popup, "aalink OSD scale", cfg.osdScale, new double[]{0.5,0.8,1.0,1.2,1.5},
            v -> { cfg.osdScale = v; saveApfpv(cfg); ssh.setAalink("OSD_SCALE", String.valueOf(v), (o,d)->{}); });
        pickInt(popup, "aalink channel", cfg.aalinkChannel, 36, 48, 4,
            v -> { cfg.aalinkChannel = v; saveApfpv(cfg); ssh.setAalink("channel", String.valueOf(v), (o,d)->{}); });

        // air camera — each its own top-level submenu
        com.openipc.pixelpilot.apfpv.AirCameraSettings c = new com.openipc.pixelpilot.apfpv.AirCameraSettings();
        pickFromList(popup, "Cam Resolution", c.size, com.openipc.pixelpilot.apfpv.AirCameraSettings.SIZES,
            v -> ssh.setSize(v,(o,d)->{}));
        pickFromList(popup, "Cam FPS", String.valueOf(c.fps), com.openipc.pixelpilot.apfpv.AirCameraSettings.FPS,
            v -> ssh.setFps(Integer.parseInt(v),(o,d)->{}));
        pickFromList(popup, "Cam Codec", c.codec, com.openipc.pixelpilot.apfpv.AirCameraSettings.CODECS,
            v -> ssh.setCodec(v,(o,d)->{}));
        pickInt(popup, "Cam Bitrate kbps", c.bitrateKbps, 1024, 30720, 1024, v -> ssh.setBitrate(v,(o,d)->{}));
        pickInt(popup, "Cam GOP", c.gopSize, 0, 10, 1, v -> ssh.setGop(v,(o,d)->{}));
        pickInt(popup, "Cam Contrast", c.contrast, 0, 100, 10, v -> ssh.setContrast(v,(o,d)->{}));
        pickInt(popup, "Cam Saturation", c.saturation, 0, 100, 10, v -> ssh.setSaturation(v,(o,d)->{}));
        pickInt(popup, "Cam Luminance", c.luminance, 0, 100, 10, v -> ssh.setLuminance(v,(o,d)->{}));

        // presets — top-level submenu
        SubMenu pr = popup.getMenu().addSubMenu("APFPV presets");
        com.openipc.pixelpilot.apfpv.AirPresets presets = new com.openipc.pixelpilot.apfpv.AirPresets();
        if (presets.list().isEmpty()) pr.add("(sync from OpenIPC/fpv-presets)").setEnabled(false);
        else for (com.openipc.pixelpilot.apfpv.AirPresets.Preset p : presets.list())
            pr.add(p.name).setOnMenuItemClickListener(i -> {
                presets.apply(p, true, ssh, (o,d)->{}); return true; });
    }

    // ---- gs-style value pickers: ONE submenu per parameter, exactly like the
    //      existing Channel/Bandwidth menus (header "Current: X" + value items).
    //      Each is a TOP-LEVEL submenu of the popup (the only legal nesting).
    private interface IntSet { void set(int v); }
    private interface StrSet { void set(String v); }
    private interface DblSet { void set(double v); }
    private void pickInt(PopupMenu popup, String name, int cur, int lo, int hi, int step, IntSet cb) {
        SubMenu s = popup.getMenu().addSubMenu(name);
        s.add("Current: " + cur).setEnabled(false);
        for (int v = lo; v <= hi; v += step) { final int val = v;
            s.add(String.valueOf(v)).setOnMenuItemClickListener(i -> { cb.set(val); return true; }); }
    }
    private void pickDouble(PopupMenu popup, String name, double cur, double[] opts, DblSet cb) {
        SubMenu s = popup.getMenu().addSubMenu(name);
        s.add("Current: " + cur).setEnabled(false);
        for (double v : opts) { final double val = v;
            s.add(String.valueOf(v)).setOnMenuItemClickListener(i -> { cb.set(val); return true; }); }
    }
    /** Free numeric entry for a double, clamped to [lo, hi]. Opens a dialog with a decimal field. */
    private void pickDoubleEntry(PopupMenu popup, String name, double cur, double lo, double hi, DblSet cb) {
        SubMenu s = popup.getMenu().addSubMenu(name);
        s.add("Current: " + cur).setEnabled(false);
        s.add("Enter value\u2026").setOnMenuItemClickListener(i -> {
            final android.widget.EditText in = new android.widget.EditText(this);
            in.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                          | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            in.setText(String.valueOf(cur));
            in.setHint(lo + " - " + hi);
            new android.app.AlertDialog.Builder(this)
                .setTitle(name)
                .setMessage("Allowed range: " + lo + " to " + hi)
                .setView(in)
                .setPositiveButton("OK", (d, w) -> {
                    double val;
                    try { val = Double.parseDouble(in.getText().toString().trim()); }
                    catch (Exception e) {
                        Toast.makeText(this, "Invalid number", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (val < lo) val = lo;
                    if (val > hi) val = hi;
                    cb.set(val);
                })
                .setNegativeButton("Cancel", null)
                .show();
            return true;
        });
    }
    private void pickString(PopupMenu popup, String name, String cur, String[] opts, StrSet cb) {
        SubMenu s = popup.getMenu().addSubMenu(name);
        s.add("Current: " + cur).setEnabled(false);
        for (String v : opts) { final String val = v;
            s.add(v).setOnMenuItemClickListener(i -> { cb.set(val); return true; }); }
    }
    private void pickFromList(PopupMenu popup, String name, String cur, java.util.List<String> opts, StrSet cb) {
        SubMenu s = popup.getMenu().addSubMenu(name);
        s.add("Current: " + cur).setEnabled(false);
        for (String v : opts) { final String val = v;
            s.add(v).setOnMenuItemClickListener(i -> { cb.set(val); return true; }); }
    }

    private com.openipc.pixelpilot.apfpv.ApfpvSettings loadApfpvSettings() {
        com.openipc.pixelpilot.apfpv.ApfpvSettings c = new com.openipc.pixelpilot.apfpv.ApfpvSettings();
        android.content.SharedPreferences p = getSharedPreferences("pixelpilot", MODE_PRIVATE);
        c.ssid = p.getString("apfpv_ssid", c.ssid);
        c.password = p.getString("apfpv_pass", c.password);
        c.mcsSource = p.getString("aalink_mcs", c.mcsSource);
        c.throughputPct = p.getInt("aalink_tput", c.throughputPct);
        return c;
    }
    private void saveApfpv(com.openipc.pixelpilot.apfpv.ApfpvSettings c) {
        getSharedPreferences("pixelpilot", MODE_PRIVATE).edit()
            .putString("aalink_mcs", c.mcsSource).putInt("aalink_tput", c.throughputPct).apply();
    }

    /** Live SSID picker: repeatedly scans the dongle (~every 3s while open),
     *  keeps the strongest signal per SSID, and shows them sorted by dBm
     *  (strongest first). Tapping one stores it as the APFPV SSID. */
    private void showApfpvScanDialog() {
        if (apfpvLinkManager == null) return;
        final java.util.Map<String, int[]> aps = new java.util.concurrent.ConcurrentHashMap<>(); // ssid -> [channel, dbm]
        final java.util.List<String> order  = new java.util.ArrayList<>();   // ssids in display order
        final java.util.List<String> labels = new java.util.ArrayList<>();
        final android.widget.ArrayAdapter<String> adapter =
            new android.widget.ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        final boolean[] open = { true };
        final boolean dfs = getSharedPreferences("pixelpilot", MODE_PRIVATE).getBoolean("apfpv_scan_dfs", true);
        final android.os.Handler h = new android.os.Handler(getMainLooper());

        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
            .setTitle("Scanning… (strongest first)")
            .setAdapter(adapter, (d, which) -> {
                if (which < 0 || which >= order.size()) return;
                String chosen = order.get(which);
                int[] v = aps.get(chosen);
                int chosenCh = (v != null) ? v[0] : 0;
                // Persist SSID + its channel so connect tunes straight to it
                // (and the discovery scan uses it as the hint, not a blind sweep).
                getSharedPreferences("pixelpilot", MODE_PRIVATE).edit()
                    .putString("apfpv_ssid", chosen)
                    .putInt("apfpv_channel", chosenCh > 0 ? chosenCh : 40).apply();
                android.widget.Toast.makeText(this, "APFPV SSID: " + chosen + " (ch " + chosenCh + ")",
                        android.widget.Toast.LENGTH_SHORT).show();
                if (chosenCh > 0) apfpvLinkManager.setChannel(chosenCh);
                apfpvLinkManager.setCredentials(chosen,
                    getSharedPreferences("pixelpilot", MODE_PRIVATE).getString("apfpv_pass", "@Vdslvdsl2023"));
            })
            .setNegativeButton("Close", null)
            .create();
        dlg.setOnDismissListener(d -> open[0] = false);   // stop the 3s re-poll
        dlg.show();

        final Runnable rebuild = () -> {
            java.util.List<String> sorted = new java.util.ArrayList<>(aps.keySet());
            sorted.sort((a, b) -> Integer.compare(aps.get(b)[1], aps.get(a)[1])); // dBm desc
            order.clear(); labels.clear();
            for (String s : sorted) { int[] v = aps.get(s);
                order.add(s); labels.add(s + "   (" + v[1] + " dBm, ch " + v[0] + ")"); }
            adapter.notifyDataSetChanged();
        };

        final ApfpvStaLink.ScanListener[] L = new ApfpvStaLink.ScanListener[1];
        L[0] = new ApfpvStaLink.ScanListener() {
            @Override public void onSsid(String ssid, int channel, int dbm) {
                int[] prev = aps.get(ssid);
                if (prev == null || dbm > prev[1]) aps.put(ssid, new int[]{channel, dbm}); // keep strongest
                runOnUiThread(rebuild);
            }
            @Override public void onScanDone() {
                runOnUiThread(() -> dlg.setTitle(aps.isEmpty()
                        ? "No SSIDs yet — scanning…" : "Select SSID (strongest first)"));
                if (open[0]) h.postDelayed(() -> {   // re-poll while the dialog is open
                    if (open[0]) apfpvLinkManager.scanSsids(200, dfs, L[0]);
                }, 3000);
            }
        };
        if (!apfpvLinkManager.scanSsids(200, dfs, L[0]))
            dlg.setTitle("Scan unavailable (no dongle/permission)");
    }

    /** Minimal SSID/password entry for APFPV association. */
    private void showApfpvCredsDialog() {
        android.content.SharedPreferences p = getSharedPreferences("pixelpilot", MODE_PRIVATE);
        final android.widget.EditText ssid = new android.widget.EditText(this);
        ssid.setHint("SSID (default OpenIPC)");
        ssid.setText(p.getString("apfpv_ssid", "Taiga"));
        final android.widget.EditText pass = new android.widget.EditText(this);
        pass.setHint("Password (default 12345678)");
        pass.setText(p.getString("apfpv_pass", "@Vdslvdsl2023"));
        android.widget.LinearLayout ll = new android.widget.LinearLayout(this);
        ll.setOrientation(android.widget.LinearLayout.VERTICAL);
        ll.addView(ssid); ll.addView(pass);
        new android.app.AlertDialog.Builder(this)
            .setTitle("APFPV credentials")
            .setView(ll)
            .setPositiveButton("Save", (d, w2) -> {
                // No channel field: APFPV follows the AP/VTX channel via discovery.
                getSharedPreferences("pixelpilot", MODE_PRIVATE).edit()
                    .putString("apfpv_ssid", ssid.getText().toString().isEmpty() ? "Taiga" : ssid.getText().toString())
                    .putString("apfpv_pass", pass.getText().toString().isEmpty() ? "@Vdslvdsl2023" : pass.getText().toString())
                    .apply();
                if (apfpvLinkManager != null) apfpvLinkManager.refreshAdapters();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    /** Configure the (VTX) antenna gain in dBi — feeds the EIRP-compliant TX power.
     *  EIRP = conducted + gain, so a higher-gain antenna lowers the commanded
     *  conducted power needed to hold the legal cap (200 mW APFPV / 25 mW WFB). */
    private void showAntennaGainDialog() {
        final android.widget.EditText in = new android.widget.EditText(this);
        in.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        float cur = getSharedPreferences("pixelpilot", MODE_PRIVATE)
                .getFloat("antenna_gain_db", (float) com.openipc.pixelpilot.apfpv.RfLimits.DEFAULT_ANTENNA_GAIN_DB);
        in.setText(String.format(java.util.Locale.US, "%.0f", cur));
        in.setHint("Antenna gain (dBi)");
        new android.app.AlertDialog.Builder(this)
            .setTitle("Antenna gain (dBi)")
            .setMessage("Gain of the antenna the GS dongle TRANSMITS on (worst case for EIRP). "
                + "EMAX Wyvern Link VRX kit: LHCP omni ≈2 dBi or patch ≈6–8 dBi — use the patch. "
                + "EIRP = conducted + gain; 'DE legal' lowers dongle TX power by this to hold the cap.")
            .setView(in)
            .setPositiveButton("Save", (d, w2) -> {
                float g;
                try { g = Float.parseFloat(in.getText().toString().trim()); }
                catch (Exception e) { g = (float) com.openipc.pixelpilot.apfpv.RfLimits.DEFAULT_ANTENNA_GAIN_DB; }
                g = Math.max(0f, Math.min(20f, g));   // sane dBi range
                getSharedPreferences("pixelpilot", MODE_PRIVATE).edit().putFloat("antenna_gain_db", g).apply();
                android.widget.Toast.makeText(this, String.format(java.util.Locale.US,
                    "Antenna gain %.0f dBi saved. Tap 'Apply DE legal limits' to recompute TX power.", g),
                    android.widget.Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    /** EIRP estimator (no meter): pick a transmitter SSID from the phone's Wi-Fi
     *  scan, enter the distance, and estimate its EIRP via Friis from the phone
     *  RSSI. The VTX (an AP) is measurable; the VRX (a station, no SSID) is shown
     *  COMPUTED from its commanded TX index + antenna gain. */
    private void showEirpEstimatorDialog() {
        android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager)
                getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) { Toast.makeText(this, "No Wi-Fi service", Toast.LENGTH_SHORT).show(); return; }
        // Scan results (SSID + RSSI + freq) require ACCESS_FINE_LOCATION + location on.
        if (androidx.core.content.ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this,
                    new String[]{ android.Manifest.permission.ACCESS_FINE_LOCATION }, 0xE1);
            Toast.makeText(this, "Grant Location for Wi-Fi scan, then tap EIRP estimate again", Toast.LENGTH_LONG).show();
            return;
        }
        try { wifi.startScan(); } catch (Exception ignored) {}
        java.util.List<android.net.wifi.ScanResult> results;
        try { results = wifi.getScanResults(); } catch (Exception e) { results = null; }
        if (results == null || results.isEmpty()) {
            Toast.makeText(this, "No Wi-Fi scan results — turn on Wi-Fi + Location and retry", Toast.LENGTH_LONG).show();
            return;
        }
        java.util.Collections.sort(results, (a, b) -> Integer.compare(b.level, a.level));   // strongest first
        final java.util.List<android.net.wifi.ScanResult> aps = new java.util.ArrayList<>(results);
        final String[] labels = new String[aps.size()];
        for (int i = 0; i < aps.size(); i++) {
            android.net.wifi.ScanResult r = aps.get(i);
            String ss = (r.SSID == null || r.SSID.isEmpty()) ? "(hidden)" : r.SSID;
            labels[i] = ss + "   " + r.level + " dBm, " + r.frequency + " MHz";
        }
        new android.app.AlertDialog.Builder(this)
            .setTitle("EIRP estimate — pick the transmitter (VTX)")
            .setItems(labels, (d, which) -> {
                android.net.wifi.ScanResult ap = aps.get(which);
                promptDistanceAndEstimate(ap.SSID == null ? "" : ap.SSID, ap.level, ap.frequency);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void promptDistanceAndEstimate(String ssid, int rssiDbm, int freqMHz) {
        final android.widget.EditText dist = new android.widget.EditText(this);
        dist.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        dist.setHint("Distance 0.1–5 m");
        float lastDist = getSharedPreferences("pixelpilot", MODE_PRIVATE).getFloat("eirp_distance_m", 2.0f);
        dist.setText(String.format(java.util.Locale.US, "%.1f", lastDist));   // default 2 m
        dist.setSelectAllOnFocus(true);
        new android.app.AlertDialog.Builder(this)
            .setTitle("Distance to \"" + ssid + "\"")
            .setMessage("RSSI " + rssiDbm + " dBm @ " + freqMHz + " MHz.\nLine-of-sight distance (0.1–5 m, default 2 m). ~1–2 m reads best — too close can saturate the RSSI.")
            .setView(dist)
            .setPositiveButton("Estimate", (d, w2) -> {
                double dm;
                try { dm = Double.parseDouble(dist.getText().toString().trim()); } catch (Exception e) { dm = 0; }
                if (dm <= 0) { Toast.makeText(this, "Enter a distance > 0 m", Toast.LENGTH_SHORT).show(); return; }
                getSharedPreferences("pixelpilot", MODE_PRIVATE).edit().putFloat("eirp_distance_m", (float) dm).apply();
                showEirpResult(ssid, rssiDbm, freqMHz, dm);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showEirpResult(String ssid, int rssiDbm, int freqMHz, double dMeters) {
        final double phoneGain = 0.0;   // phone internal Wi-Fi antenna ~0 dBi
        double eirp = com.openipc.pixelpilot.apfpv.RfLimits.estimateEirpDbm(rssiDbm, dMeters, freqMHz, phoneGain);
        double cap = com.openipc.pixelpilot.apfpv.RfLimits.eirpCapForFreq(freqMHz);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(java.util.Locale.US,
            "VTX \"%s\" — MEASURED\n  %d dBm @ %d MHz, %.0f m, phone ant ~0 dBi\n  → EIRP ≈ %.0f dBm (~%d mW)\n",
            ssid, rssiDbm, freqMHz, dMeters, eirp, com.openipc.pixelpilot.apfpv.RfLimits.mw(eirp)));
        if (!Double.isNaN(cap)) {
            sb.append(String.format(java.util.Locale.US, "  cap %d mW (%.0f dBm) → %s by %.0f dB\n",
                com.openipc.pixelpilot.apfpv.RfLimits.mw(cap), cap,
                eirp <= cap ? "WITHIN" : "OVER", Math.abs(eirp - cap)));
        } else {
            sb.append("  (freq outside DE APFPV/WFB bands — no cap)\n");
        }
        // VRX has no SSID to measure -> compute from the commanded TX index + gain.
        int idx = getSharedPreferences("general", MODE_PRIVATE).getInt("adaptive_tx_power", 20);
        float gain = getSharedPreferences("pixelpilot", MODE_PRIVATE)
                .getFloat("antenna_gain_db", (float) com.openipc.pixelpilot.apfpv.RfLimits.DEFAULT_ANTENNA_GAIN_DB);
        double cond = com.openipc.pixelpilot.apfpv.RfLimits.indexToDbm(idx);
        double eirpVrx = cond + gain;
        boolean apfpv = (linkMode == LinkModeCoordinator.Mode.APFPV);
        double vrxCap = apfpv ? com.openipc.pixelpilot.apfpv.RfLimits.EIRP_APFPV_DBM
                              : com.openipc.pixelpilot.apfpv.RfLimits.EIRP_WFB_DBM;
        sb.append(String.format(java.util.Locale.US,
            "\nVRX dongle — COMPUTED (no SSID to measure)\n  TX idx %d → %.0f dBm + %.0f dBi ant\n  → EIRP ≈ %.0f dBm (~%d mW), cap %d mW → %s\n",
            idx, cond, gain, eirpVrx, com.openipc.pixelpilot.apfpv.RfLimits.mw(eirpVrx),
            com.openipc.pixelpilot.apfpv.RfLimits.mw(vrxCap), eirpVrx <= vrxCap ? "WITHIN" : "OVER"));
        sb.append("\n±3–6 dB. Do it outdoors, line-of-sight; average a few distances.");
        new android.app.AlertDialog.Builder(this)
            .setTitle("EIRP estimate")
            .setMessage(sb.toString())
            .setPositiveButton("OK", null)
            .show();
    }

    /**
     * Submenu for recording options, including start/stop DVR and toggling fMP4.
     */
    private void setupRecordingSubMenu(PopupMenu popup) {
        SubMenu recording = popup.getMenu().addSubMenu("Recording");

        MenuItem dvrBtn = recording.add(dvrFd == null ? "Start" : "Stop");
        dvrBtn.setOnMenuItemClickListener(item -> {
            startStopDvr();
            return true;
        });

        MenuItem fmp4 = recording.add("fMP4");
        fmp4.setCheckable(true);
        fmp4.setChecked(getDvrMP4());
        fmp4.setOnMenuItemClickListener(item -> {
            boolean enabled = getDvrMP4();
            item.setChecked(!enabled);
            setDvrMP4(!enabled);
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
            item.setActionView(new View(this));
            return false;
        });

        // Record mode: 0=Raw (clean), 1=OSD (overlay composited), 2=Raw+OSD (both -> two files).
        SubMenu modeMenu = recording.addSubMenu("Record mode");
        final String[] modeNames = {"Raw", "OSD", "Raw + OSD"};
        for (int mi = 0; mi < modeNames.length; mi++) {
            final int mode = mi;
            MenuItem it = modeMenu.add(modeNames[mi]);
            it.setCheckable(true);
            it.setChecked(getDvrRecordMode() == mode);
            it.setOnMenuItemClickListener(item -> { setDvrRecordMode(mode); item.setChecked(true); return true; });
        }

        MenuItem autoDvr = recording.add("Auto DVR");
        autoDvr.setCheckable(true);
        autoDvr.setChecked(getDvrAuto());
        autoDvr.setOnMenuItemClickListener(item -> {
            boolean en = !getDvrAuto();
            item.setChecked(en); setDvrAuto(en);
            if (en) startAutoDvrWatcher(); else stopAutoDvrWatcher();
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
            item.setActionView(new View(this));
            return false;
        });

        MenuItem resetPermissions = recording.add("Reset DVR folder");
        resetPermissions.setOnMenuItemClickListener(item -> {
            resetFolderPermissions();
            return true;
        });
    }

    /**
     * Submenu for drone settings.
     */
    private void setupDroneSubMenu(PopupMenu popup) {
        SubMenu drone = popup.getMenu().addSubMenu("Drone");
        MenuItem settings = drone.add("Settings");
        settings.setOnMenuItemClickListener(item -> {
            startBrowser();
            return true;
        });

        // Add a new option to manage login credentials
        MenuItem loginCredentials = drone.add("Login Credentials");
        loginCredentials.setOnMenuItemClickListener(item -> {
            showLoginCredentialsDialog();
            return true;
        });
    }

    /**
     * Submenu for help items, such as sending logs.
     */
    private void setupHelpSubMenu(PopupMenu popup) {
        SubMenu help = popup.getMenu().addSubMenu("Help");
        MenuItem logs = help.add("Send Logs");

        // Increase logcat buffer to 10MB if possible
        try {
            Runtime.getRuntime().exec("logcat -G 10M");
        } catch (IOException e) {
            Log.e(TAG, "ShareLog: ", e);
        }

        logs.setOnMenuItemClickListener(item -> {
            shareLogs();
            return true;
        });
    }

    // ----------------------------------------------------------------------------
    // MAVLINK SETUP
    // ----------------------------------------------------------------------------

    /**
     * Starts the native Mavlink service and posts an initial Runnable to the Handler.
     */
    private void setupMavlink() {
        MavlinkNative.nativeStart(this);
        handler.post(runnable);
    }

    // ----------------------------------------------------------------------------
    // BATTERY RECEIVER
    // ----------------------------------------------------------------------------

    /**
     * Registers a receiver that listens for battery status changes and updates the UI accordingly.
     */
    private void setupBatteryReceiver() {
        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent batteryStatus) {
                updateBatteryStatus(batteryStatus);
            }
        };
    }

    /**
     * Updates the battery icon and percentage based on the current battery state.
     */
    private void updateBatteryStatus(Intent batteryStatus) {
        int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL;

        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        float batteryPct = level * 100 / (float) scale;
        binding.tvGSBattery.setText((int) batteryPct + "%");

        int icon;
        if (isCharging) {
            icon = R.drawable.baseline_battery_charging_full_24;
        } else {
            // Adjust these thresholds as needed
            if (batteryPct <= 0) {
                icon = R.drawable.baseline_battery_0_bar_24;
            } else if (batteryPct <= (1f / 7f) * 100) {
                icon = R.drawable.baseline_battery_1_bar_24;
            } else if (batteryPct <= (2f / 7f) * 100) {
                icon = R.drawable.baseline_battery_2_bar_24;
            } else if (batteryPct <= (3f / 7f) * 100) {
                icon = R.drawable.baseline_battery_3_bar_24;
            } else if (batteryPct <= (4f / 7f) * 100) {
                icon = R.drawable.baseline_battery_4_bar_24;
            } else if (batteryPct <= (5f / 7f) * 100) {
                icon = R.drawable.baseline_battery_5_bar_24;
            } else if (batteryPct <= (6f / 7f) * 100) {
                icon = R.drawable.baseline_battery_6_bar_24;
            } else {
                icon = R.drawable.baseline_battery_full_24;
            }
        }
        binding.imgGSBattery.setImageResource(icon);
    }

    // ----------------------------------------------------------------------------
    // LOG SHARING
    // ----------------------------------------------------------------------------

    /**
     * Shares the device logs by writing them to a file and prompting the user to choose a share target.
     */
    private void shareLogs() {
        try {
            Process process = Runtime.getRuntime().exec("logcat -d");
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            File logFile = new File(getExternalFilesDir(null), "pixelpilot_log_" + timeStamp + ".txt");
            FileWriter fileWriter = new FileWriter(logFile);

            // Fetch app version info
            String versionName = "";
            long versionCode = 0;
            try {
                PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
                versionName = packageInfo.versionName;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    versionCode = packageInfo.getLongVersionCode();
                } else {
                    versionCode = packageInfo.versionCode;
                }
            } catch (PackageManager.NameNotFoundException ignored) {
            }

            // Write device/app info
            fileWriter.append("Device Model: ").append(Build.MODEL).append("\n")
                    .append("Manufacturer: ").append(Build.MANUFACTURER).append("\n")
                    .append("OS Version: ").append(Build.VERSION.RELEASE).append("\n")
                    .append("SDK Version: ").append(String.valueOf(Build.VERSION.SDK_INT)).append("\n")
                    .append("App Version Name: ").append(versionName).append("\n")
                    .append("App Version Code: ").append(String.valueOf(versionCode)).append("\n");

            // Write actual logs
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                fileWriter.append(line).append("\n");
            }
            fileWriter.flush();
            fileWriter.close();

            // Share the log file
            Intent sendIntent = new Intent();
            sendIntent.setAction(Intent.ACTION_SEND);
            Uri fileUri = FileProvider.getUriForFile(this, getPackageName() + ".provider", logFile);
            sendIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
            sendIntent.setType("text/plain");
            sendIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent shareIntent = Intent.createChooser(sendIntent, null);
            startActivity(shareIntent);

        } catch (IOException e) {
            Log.e(TAG, "ShareLog: ", e);
        }
    }

    // ----------------------------------------------------------------------------
    // VPN SERVICE
    // ----------------------------------------------------------------------------
    private void startVpnService() {
        // VPN is only needed for WFB mode (raw WiFi frames). In APFPV dongle mode,
        // the dongle handles the link — the VPN just wastes a service start and
        // crashes on Android 14+ (BackgroundServiceStartNotAllowedException) if
        // the app is launched while the device is asleep/locked.
        String mode = getSharedPreferences("pixelpilot", MODE_PRIVATE)
                .getString("link_mode", "apfpv");
        if ("apfpv".equals(mode) || "apfpv_wifi".equals(mode)) return;

        int VPN_REQUEST_CODE = 100;
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE);
        } else {
            Intent serviceIntent = new Intent(this, WfbNgVpnService.class);
            try {
                startService(serviceIntent);
            } catch (IllegalStateException e) {
                android.util.Log.w("VideoActivity", "Cannot start VPN (background): " + e);
            }
        }
    }

    private Uri openDvrFile() { return openDvrFile(""); }

    private Uri openDvrFile(String suffix) {
        String dvrFolder = getSharedPreferences("general",
                Context.MODE_PRIVATE).getString("dvr_folder_", "");
        if (dvrFolder.isEmpty()) {
            Log.e(TAG, "dvrFolder is empty");
            return null;
        }
        Uri uri = Uri.parse(dvrFolder);
        DocumentFile pickedDir = DocumentFile.fromTreeUri(this, uri);
        if (pickedDir != null && pickedDir.canWrite()) {
            LocalDateTime now = LocalDateTime.now();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
            // Format the current date and time
            String formattedNow = now.format(formatter);
            String filename = "pixelpilot_" + formattedNow + suffix + ".mp4";
            DocumentFile newFile = pickedDir.createFile("video/mp4", filename);
            Toast.makeText(this, "Recording to " + filename, Toast.LENGTH_SHORT).show();
            if (newFile == null)
                Log.e(TAG, "dvr newFile null");
            return newFile != null ? newFile.getUri() : null;
        }
        return null;
    }

    private void startStopDvr() {
        if (dvrFd == null) {
            Uri dvrUri = openDvrFile();
            if (dvrUri != null) {
                userStoppedDvr = false;
                startDvr(dvrUri);
            } else {
                wfbLinkManager.stopAdapters();
                videoPlayer.stop();
                videoPlayer.stopAudio();

                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addCategory(Intent.CATEGORY_DEFAULT);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(intent, PICK_DVR_REQUEST_CODE);
            }
        } else {
            userStoppedDvr = true;  // manual stop: Auto-DVR won't re-arm until the stream drops + returns
            stopDvr();
        }
    }

    private void startDvr(Uri dvrUri) {
        if (dvrFd != null) {
            stopDvr();
        }
        try {
            dvrFd = getContentResolver().openFileDescriptor(dvrUri, "rw");
            com.openipc.videonative.GLFanoutManager fo = videoPlayer.getGlFanout();
            int vw = videoPlayer.getVideoWidth(), vh = videoPlayer.getVideoHeight();
            dvrViaFanout = (fo != null && vw > 0 && vh > 0);
            android.util.Log.d("GLFanoutDbg", "DVRpath fanout=" + dvrViaFanout + " w=" + vw + " h=" + vh + " fps=" + videoPlayer.getVideoFps() + " h265=" + videoPlayer.getVideoIsH265());
            if (dvrViaFanout) {
                // GL fan-out DVR: shares the live decode; composites the OSD overlay when enabled.
                int fps = videoPlayer.getVideoFps() > 0 ? videoPlayer.getVideoFps() : 30;
                boolean recordOsd = (getDvrRecordMode() != 0);  // Raw=0 clean; OSD/Raw+OSD composite (Raw+OSD 2nd raw file = dual-encoder follow-up)
                android.graphics.Bitmap osd = recordOsd ? captureOsdBitmap() : null;
                fo.startDvr(dvrFd.getFd(), vw, vh, fps, 8000000, getDvrMP4(), false, recordOsd, osd);  // DVR re-encodes to H.264
                if (recordOsd) startOsdUpdateTimer();  // keep the recorded OSD live (telemetry/GPS change)
                if (getDvrRecordMode() == 2) {  // Raw+OSD: also record a parallel CLEAN file (_raw)
                    try {
                        Uri rawUri = openDvrFile("_raw");
                        if (rawUri != null) {
                            rawDvrFd = getContentResolver().openFileDescriptor(rawUri, "rw");
                            fo.startDvrRaw(rawDvrFd.getFd(), vw, vh, fps, 8000000, getDvrMP4());
                        }
                    } catch (IOException re) { Log.e(TAG, "raw dvr open failed", re); rawDvrFd = null; }
                }
            } else {
                videoPlayer.startDvr(dvrFd.getFd(), getDvrMP4());
            }
            binding.imgBtnRecord.setImageResource(R.drawable.recording);
        } catch (IOException e) {
            Log.e(TAG, "Failed to open dvr file ", e);
            dvrFd = null;
        }

        binding.txtRecordLabel.setVisibility(View.VISIBLE);
        recordTimer = new Timer();
        recordTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                int minutes = seconds / 60;
                int secs = seconds % 60;

                String timeFormatted = String.format("%02d:%02d", minutes, secs);
                runOnUiThread(() -> binding.txtRecordLabel.setText(timeFormatted));
                seconds++;
            }
        }, 0, 1000);

        dvrIconTimer = new Timer();
        dvrIconTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                runOnUiThread(() -> binding.imgRecIndicator.setVisibility(binding.imgRecIndicator
                        .getVisibility() == View.VISIBLE ? View.INVISIBLE : View.VISIBLE));
            }
        }, 0, 1000);
    }

    private void stopDvr() {
        if (dvrFd == null) {
            return;
        }
        binding.imgRecIndicator.setVisibility(View.INVISIBLE);
        binding.imgBtnRecord.setImageResource(R.drawable.record);
        if (osdUpdateTimer != null) { osdUpdateTimer.cancel(); osdUpdateTimer = null; }
        if (dvrViaFanout) {
            com.openipc.videonative.GLFanoutManager fo = videoPlayer.getGlFanout();
            if (fo != null) { fo.stopDvr(); fo.stopDvrRaw(); }
            dvrViaFanout = false;
        } else {
            videoPlayer.stopDvr();
        }
        if (rawDvrFd != null) { try { rawDvrFd.close(); } catch (IOException e) { } rawDvrFd = null; }
        if (recordTimer != null) {
            recordTimer.cancel();
            recordTimer.purge();
            recordTimer = null;
            seconds = 0;
            binding.txtRecordLabel.setVisibility(View.GONE);
        }
        if (dvrIconTimer != null) {
            dvrIconTimer.cancel();
            dvrIconTimer.purge();
            dvrIconTimer = null;
        }
        try {
            dvrFd.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        dvrFd = null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_KEY_REQUEST_CODE && resultCode == RESULT_OK) {
            if (data != null && data.getData() != null) {
                Uri uri = data.getData();
                Log.d(TAG, "Selected file " + uri);
                try {
                    InputStream inputStream = getContentResolver().openInputStream(uri);
                    setGsKey(inputStream);
                    copyGSKey();
                    wfbLinkManager.refreshKey();
                    inputStream.close();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to import gs.key from " + uri);
                }
            }
        } else if (requestCode == PICK_DVR_REQUEST_CODE && resultCode == RESULT_OK) {
            // The result data contains a URI for the document or directory that
            // the user selected.
            Uri uri;
            if (data != null && data.getData() != null) {
                uri = data.getData();
                final int takeFlags = data.getFlags() &
                        (Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(uri, takeFlags);

                // Perform operations on the document using its URI.
                SharedPreferences prefs = getSharedPreferences("general", Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString("dvr_folder_", uri.toString());
                editor.apply();
                Uri dvrUri = openDvrFile();
                if (dvrUri != null) {
                    startDvr(dvrUri);
                }
            }
        } else if (requestCode == 100) {  // VPN_REQUEST_CODE is 100
            if (resultCode == RESULT_OK) {
                // VPN permission granted, start the VPN service
                Intent serviceIntent = new Intent(this, WfbNgVpnService.class);
                startService(serviceIntent);
            } else {
                // VPN permission not granted
                Log.e(TAG, "VPN permission was not granted by the user.");
            }
        } else {
            Log.w(TAG, "onActivityResult: unknown request code " + requestCode);
        }
    }

    public void setDefaultGsKey() {
        if (getGsKey().length > 0) {
            Log.d(TAG, "gs.key already saved in preferences.");
            return;
        }
        try {
            Log.d(TAG, "Importing default gs.key...");
            InputStream inputStream = getAssets().open("gs.key");
            setGsKey(inputStream);
            inputStream.close();
        } catch (IOException e) {
            Log.e(TAG, "Failed to import default gs.key");
        }
    }

    public byte[] getGsKey() {
        String pref = getSharedPreferences("general", Context.MODE_PRIVATE).getString("gs.key", "");
        return Base64.decode(pref, Base64.DEFAULT);
    }

    public void setGsKey(InputStream inputStream) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = inputStream.read(buffer)) != -1) {
            result.write(buffer, 0, length);
        }
        SharedPreferences prefs = getSharedPreferences("general", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("gs.key", Base64.encodeToString(result.toByteArray(), Base64.DEFAULT));
        editor.apply();
    }

    public boolean getDvrMP4() {
        return getSharedPreferences("general", Context.MODE_PRIVATE).getBoolean("dvr_fmp4", true);
    }

    // GL fan-out DVR: tracks whether the active recording is routed through the fan-out (vs legacy).
    private boolean dvrViaFanout = false;
    private ParcelFileDescriptor rawDvrFd;  // Raw+OSD: the parallel clean (_raw) file's fd
    // Last-valid video params for the DVR — mDecodingInfo/lastVideoW reset (null/0) when the menu
    // opens or the stream gaps, so cache the good values to choose + configure the fan-out DVR.
    private int dvrCapW, dvrCapH, dvrCapFps;
    private boolean dvrCapH265;

    public boolean getDvrRecordOsd() {
        return getSharedPreferences("general", Context.MODE_PRIVATE).getBoolean("dvr_record_osd", false);
    }

    public void setDvrRecordOsd(boolean enabled) {
        getSharedPreferences("general", Context.MODE_PRIVATE).edit().putBoolean("dvr_record_osd", enabled).apply();
    }

    // Record mode: 0=Raw, 1=OSD, 2=Raw+OSD. (Raw+OSD records two files; raw stream is the dual-
    // encoder follow-up — see apfpv-dvr-recordmode-autodvr memory.)
    public int getDvrRecordMode() { return getSharedPreferences("general", Context.MODE_PRIVATE).getInt("dvr_record_mode", 1); }
    public void setDvrRecordMode(int m) { getSharedPreferences("general", Context.MODE_PRIVATE).edit().putInt("dvr_record_mode", m).apply(); }
    public boolean getDvrAuto() { return getSharedPreferences("general", Context.MODE_PRIVATE).getBoolean("dvr_auto", false); }
    public void setDvrAuto(boolean e) { getSharedPreferences("general", Context.MODE_PRIVATE).edit().putBoolean("dvr_auto", e).apply(); }

    // Auto-DVR: ~1 Hz watcher of the live stream (decoder W/H + fps). Starts recording when a
    // stream appears, auto-stops ~3s after it drops (device/remote off). A manual Stop sets
    // userStoppedDvr so it won't immediately re-arm; that clears when the stream drops + returns.
    private Timer autoDvrTimer;
    private int autoNoStream;
    private boolean userStoppedDvr;
    private void startAutoDvrWatcher() {
        if (autoDvrTimer != null) autoDvrTimer.cancel();
        autoDvrTimer = new Timer(); autoNoStream = 0;
        autoDvrTimer.schedule(new TimerTask() {
            @Override public void run() {
                runOnUiThread(() -> {
                    if (!getDvrAuto()) return;
                    boolean live = videoPlayer != null && videoPlayer.getVideoWidth() > 0 && videoPlayer.getVideoFps() > 0;
                    if (live) {
                        autoNoStream = 0;
                        if (dvrFd == null && !userStoppedDvr) { Uri u = openDvrFile(); if (u != null) startDvr(u); }
                    } else if (++autoNoStream >= 3) {
                        if (dvrFd != null) stopDvr();   // stream gone -> auto-stop
                        userStoppedDvr = false;          // a returning stream may auto-start again
                    }
                });
            }
        }, 1000, 1000);
    }
    private void stopAutoDvrWatcher() { if (autoDvrTimer != null) { autoDvrTimer.cancel(); autoDvrTimer = null; } }

    /** Snapshot the on-screen OSD overlay as an RGBA_8888 Bitmap. The video is a SurfaceView and is
     *  NOT drawn by View#draw, so this captures ONLY the overlay (telemetry/indicators) — exactly the
     *  layer to composite into the DVR. Must run on the UI thread. */
    private android.graphics.Bitmap captureOsdBitmap() {
        android.view.View root = binding.getRoot();
        int sw = root.getWidth(), sh = root.getHeight();
        if (sw <= 0 || sh <= 0) return null;
        int vw = videoPlayer.getVideoWidth(), vh = videoPlayer.getVideoHeight();
        if (vw <= 0 || vh <= 0) { vw = sw; vh = sh; }
        // Render the OSD at the VIDEO's aspect (not the 20:9 phone screen) so the recorded overlay
        // matches what's on screen: each element keeps its % position from the edges (scale the
        // CENTER per-axis) but its true, undistorted size (one UNIFORM size scale). A flat
        // full-screen capture stretched 20:9 -> 16:9 turned circles into ovals. Drawing only the
        // OSD items (not the root) also drops app chrome (the settings gear) for free.
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(vw, vh, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);
        float sx = (float) vw / sw, sy = (float) vh / sh;   // position scale: keeps % from each edge
        float ss = Math.min(sx, sy);                        // uniform size scale: no stretch
        int[] rootLoc = new int[2]; root.getLocationInWindow(rootLoc);
        if (osdManager != null && osdManager.listOSDItems != null) {
            for (com.openipc.pixelpilot.osd.OSDElement el : osdManager.listOSDItems) {
                android.view.View v = el.layout;
                if (v == null || v.getVisibility() != View.VISIBLE || v.getWidth() <= 0 || v.getHeight() <= 0) continue;
                int[] loc = new int[2]; v.getLocationInWindow(loc);
                float cx = (loc[0] - rootLoc[0]) + v.getWidth() / 2f;
                float cy = (loc[1] - rootLoc[1]) + v.getHeight() / 2f;
                float ncx = cx * sx, ncy = cy * sy;         // element center, % preserved
                int save = c.save();
                c.translate(ncx - v.getWidth() * ss / 2f, ncy - v.getHeight() * ss / 2f);
                c.scale(ss, ss);
                v.draw(c);
                c.restoreToCount(save);
            }
        }
        return bmp;
    }

    private Timer osdUpdateTimer;
    /** Re-capture the OSD overlay ~2x/sec while recording so the recorded layer stays live (the
     *  telemetry/GPS values change). Runs on the UI thread (View#draw); updateOsd hops to the GL thread. */
    private void startOsdUpdateTimer() {
        if (osdUpdateTimer != null) osdUpdateTimer.cancel();
        osdUpdateTimer = new Timer();
        osdUpdateTimer.schedule(new TimerTask() {
            @Override public void run() {
                runOnUiThread(() -> {
                    com.openipc.videonative.GLFanoutManager fo = videoPlayer.getGlFanout();
                    if (dvrViaFanout && fo != null) { android.graphics.Bitmap b = captureOsdBitmap(); if (b != null) fo.updateOsd(b); }
                });
            }
        }, 500, 500);
    }

    public void setDvrMP4(boolean enabled) {
        SharedPreferences prefs = getSharedPreferences("general", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean("dvr_fmp4", enabled);
        editor.apply();
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    public void registerReceivers() {
        IntentFilter usbFilter = new IntentFilter();
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        usbFilter.addAction(WfbLinkManager.ACTION_USB_PERMISSION);
        IntentFilter batFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(wfbLinkManager, usbFilter, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(batteryReceiver, batFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(wfbLinkManager, usbFilter);
            registerReceiver(batteryReceiver, batFilter);
        }
    }

    public void unregisterReceivers() {
        try {
            unregisterReceiver(wfbLinkManager);
        } catch (IllegalArgumentException ignored) {
        }
        try {
            unregisterReceiver(batteryReceiver);
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        unregisterReceivers();

        videoPlayer.stop();
        videoPlayer.stopAudio();
        wfbLinkManager.stopAdapters();
        apfpvLinkManager.stopAdapters();   // APFPV dongle disconnect (was missing!)

        // Stop VPN service
        Log.w(TAG, "onPause: stopping service");
        Intent intent = new Intent(this, WfbNgVpnService.class);
        intent.setAction("STOP_SERVICE");
        startService(intent);
    }

    @Override
    protected void onStop() {
        MavlinkNative.nativeStop(this);
        handler.removeCallbacks(runnable);
        unregisterReceivers();
        // All transports + video already stopped in onPause; don't double-disconnect
        super.onStop();
    }

    @Override
    protected void onResume() {
        registerReceivers();

        wfbLinkManager.setChannel(getChannel(this));
        wfbLinkManager.setBandwidth(getBandwidth(this));

        // Bring up the transport for the ACTIVE mode only — WFB must not grab the
        // dongle (or print "Starting wfb-ng") when we're in an APFPV mode.
        wfbLinkManager.setAutoStart(linkMode == LinkModeCoordinator.Mode.WFB);
        switch (linkMode) {
            case APFPV: {
                // APFPV station on the dongle: enumerate Realtek adapters and bring
                // the active one up with the saved credentials/channel.
                String ssid = getSharedPreferences("pixelpilot", MODE_PRIVATE).getString("apfpv_ssid", "Taiga");
                String pass = getSharedPreferences("pixelpilot", MODE_PRIVATE).getString("apfpv_pass", "@Vdslvdsl2023");
                // APFPV (dongle) RF is independent of WFB: use the APFPV channel +
                // bandwidth prefs (default legal DE 5.2 GHz UNII-1 ch40, 20 MHz).
                int apfpvCh = getSharedPreferences("pixelpilot", MODE_PRIVATE).getInt("apfpv_channel", 40);
                int apfpvBw = getSharedPreferences("pixelpilot", MODE_PRIVATE).getInt("apfpv_bandwidth", 20);
                apfpvLinkManager.setChannel(apfpvCh);
                apfpvLinkManager.setBandwidth(apfpvBw);
                apfpvLinkManager.setCredentials(ssid, pass);
                apfpvLinkManager.refreshAdapters();
                break;
            }
            case APFPV_WIFI: {
                // No dongle — associate the phone's Wi-Fi to the air unit.
                String ssid = getSharedPreferences("pixelpilot", MODE_PRIVATE).getString("apfpv_ssid", "Taiga");
                String pass = getSharedPreferences("pixelpilot", MODE_PRIVATE).getString("apfpv_pass", "@Vdslvdsl2023");
                if (apfpvWifiManager != null) {
                    apfpvWifiManager.setCredentials(ssid, pass);
                    apfpvWifiManager.start();
                }
                break;
            }
            case WFB:
            default:
                // On resume a device might have been plugged since last start.
                wfbLinkManager.refreshAdapters();
                wfbLinkManager.startAdapters();
                break;
        }
        videoPlayer.start();
        videoPlayer.startAudio();
        // Flush stale frames at the menu-configured threshold (default 60ms).
        videoPlayer.setVideoFlushMs(getVideoFlushMs(this));
        if (getDvrAuto()) startAutoDvrWatcher();

        osdManager.restoreOSDConfig();

        startVpnService();

        super.onResume();
    }

    @Override
    public void onChannelSettingChanged(int channel) {
        int currentChannel = getChannel(this);
        if (currentChannel == channel) {
            return;
        }
        SharedPreferences prefs = getSharedPreferences("general", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt("wifi-channel", channel);
        editor.apply();
        wfbLinkManager.stopAdapters();
        wfbLinkManager.setChannel(channel);
        wfbLinkManager.startAdapters();
    }

    @Override
    public void onBandwidthSettingChanged(int bandwidth) {
        int currentBandwidth = getBandwidth(this);
        SharedPreferences prefs = getSharedPreferences("general", Context.MODE_PRIVATE);
        prefs.edit().putInt("bandwidth", bandwidth).apply();
        // GS (VRX) RX side: WFB-ng mode tunes the dongle here (CHANNEL_WIDTH_20/40 via the link).
        // APFPV station mode is pinned to 20 MHz for legal-DE compliance, so it's left alone.
        if (!apfpvMode && currentBandwidth != bandwidth) {
            wfbLinkManager.stopAdapters();
            wfbLinkManager.setBandwidth(bandwidth);
            wfbLinkManager.startAdapters();
        }
        // APFPV station (VRX RX): persist the width for the connect Params (apfpv_bandwidth ->
        // ApfpvStation::Params.bandwidth -> StationMode connect width) and re-connect to apply it.
        // Change-check MUST use apfpv_bandwidth (the station's pref), NOT general/bandwidth above —
        // they desync, so the old `currentBandwidth != bandwidth` test silently skipped this branch.
        if (apfpvMode) {
            SharedPreferences pp = getSharedPreferences("pixelpilot", MODE_PRIVATE);
            if (pp.getInt("apfpv_bandwidth", 20) != bandwidth) {
                pp.edit().putInt("apfpv_bandwidth", bandwidth).apply();
                if (bandwidth > 20)
                    Toast.makeText(this, "40 MHz exceeds the DE 200 mW @ 20 MHz cap", Toast.LENGTH_LONG).show();
                // Re-apply WITHOUT a full app restart: setLinkMode(linkMode)/switchTo no-op on the
                // same mode, so reconnect the current station (re-reads apfpv_bandwidth -> width).
                String ssid = pp.getString("apfpv_ssid", "Taiga");
                String pass = pp.getString("apfpv_pass", "@Vdslvdsl2023");
                linkModeCoordinator.reconnectCurrent(ssid, pass, (m, ok, d) -> runOnUiThread(() ->
                    Toast.makeText(this, "Bandwidth " + bandwidth + " MHz — " + d, Toast.LENGTH_SHORT).show()));
            }
        }
        // VTX (air) side: push .wireless.width over SSH in BOTH modes, mirroring the OpenIPC GS
        // (gsmenu: `wifibroadcast cli -s .wireless.width`). One control => both ends, like the GS.
        com.openipc.pixelpilot.apfpv.AirSshClient ssh = new com.openipc.pixelpilot.apfpv.AirSshClient();
        ssh.useApfpvHost();   // 192.168.0.1 — same host the Air WFB-ng submenu uses
        ssh.setWidth(bandwidth, (ok, out) -> runOnUiThread(() ->
            Toast.makeText(this, "Air width -> " + bandwidth + (ok ? " MHz" : " FAILED"), Toast.LENGTH_SHORT).show()));
    }

    @Override
    public void onVideoRatioChanged(final int videoW, final int videoH) {
        lastVideoW = videoW;
        lastVideoH = videoH;
        if (videoW > 0 && videoH > 0) { dvrCapW = videoW; dvrCapH = videoH; }  // last-valid for DVR

        Log.d(TAG, "Set resolution: " + videoW + "x" + videoH);

        updateViewRatio(R.id.mainVideo, lastVideoW, lastVideoH);
        updateViewRatio(R.id.surfaceViewLeft, lastVideoW, lastVideoH);
        updateViewRatio(R.id.surfaceViewRight, lastVideoW, lastVideoH);
    }

    private void updateViewRatio(int viewId, int videoW, int videoH) {
        if (videoW == 0 || videoH == 0) {
            return;
        }

        View view = findViewById(viewId);
        if (view != null) {
            ConstraintLayout.LayoutParams params =
                    (ConstraintLayout.LayoutParams) view.getLayoutParams();
            params.dimensionRatio = videoW + ":" + videoH;
            runOnUiThread(() -> view.setLayoutParams(params));
        } else {
            Log.w(TAG, "View with ID " + viewId + " not found.");
        }
    }

    @Override
    public void onDecodingInfoChanged(final DecodingInfo decodingInfo) {
        mDecodingInfo = decodingInfo;
        if (decodingInfo != null && decodingInfo.currentFPS > 0) { dvrCapFps = (int) decodingInfo.currentFPS; dvrCapH265 = decodingInfo.nCodec == 1; }
        runOnUiThread(() -> {
            if (lastCodec != decodingInfo.nCodec) {
                lastCodec = decodingInfo.nCodec;
            }
            if (decodingInfo.currentFPS > 0) {
                binding.tvMessage.setVisibility(View.GONE);
                binding.wifiMessage.setVisibility(View.GONE);
                binding.itemVideoStats.setVisibility(View.VISIBLE);   // on-device decode-stats overlay (also in APFPV mode)
            }
            float dropPct = decodingInfo.nNALU > 0
                    ? 100f * (decodingInfo.nNALU - decodingInfo.nNALUSFeeded) / decodingInfo.nNALU : 0f;
            // Resolution: lastVideoW/H are fed by onVideoRatioChanged, which never fires on the
            // GL-fan-out / APFPV decode path (see VideoPlayer.cpp:517) — so they stay 0 and the OSD
            // showed "0x0". Read the reliable native getter (latestVideoRatio) that the DVR already
            // uses; fall back to lastVideoW only if the player is gone.
            int statW = (videoPlayer != null && videoPlayer.getVideoWidth()  > 0) ? videoPlayer.getVideoWidth()  : lastVideoW;
            int statH = (videoPlayer != null && videoPlayer.getVideoHeight() > 0) ? videoPlayer.getVideoHeight() : lastVideoH;
            // Bitrate: currentKiloBitsPerSecond is already kbps. The old code divided by 1000 in BOTH
            // branches, so e.g. 600 kbps printed as "0.6Kpbs" (and "Kpbs" was a typo). Only the Mbps
            // branch should divide.
            boolean mbps = decodingInfo.currentKiloBitsPerSecond > 1000;
            float rate = mbps ? decodingInfo.currentKiloBitsPerSecond / 1000f : decodingInfo.currentKiloBitsPerSecond;
            String info = "%dx%d@%.0f " + (decodingInfo.nCodec == 1 ? " H265 " : " H264 ")
                    + (mbps ? " %.1fMbps " : " %.0fKbps ")
                    + " %.1fms  drop %.1f%%";
            binding.tvVideoStats.setText(String.format(Locale.US, info,
                    statW, statH, decodingInfo.currentFPS,
                    rate,
                    decodingInfo.avgTotalDecodingTime_ms, dropPct));
        });
    }

    @Override
    public void onWfbNgStatsChanged(WfbNGStats data) {
        runOnUiThread(() -> {
            if (data.count_p_all > 0) {
                binding.tvMessage.setVisibility(View.INVISIBLE);
                binding.tvMessage.setText("");

                if (data.count_p_dec_err > 0) {
                    binding.tvLinkStatus.setText("Waiting for session key.");
                } else {
                    // NOTE: The order of the entries when being added to the entries array
                    // determines their position around the center of the chart.
                    ArrayList<PieEntry> entries = new ArrayList<>();
                    entries.add(new PieEntry((float) data.count_p_dec_ok / data.count_p_all));
                    entries.add(new PieEntry((float) data.count_p_fec_recovered / data.count_p_all));
                    entries.add(new PieEntry((float) data.count_p_lost / data.count_p_all));

                    PieDataSet dataSet = new PieDataSet(entries, "Link Status");
                    dataSet.setDrawIcons(false);
                    dataSet.setDrawValues(false);

                    ArrayList<Integer> colors = new ArrayList<>();
                    colors.add(getColor(R.color.colorGreen));
                    colors.add(getColor(R.color.colorYellow));
                    colors.add(getColor(R.color.colorRed));
                    dataSet.setColors(colors);

                    PieData pieData = new PieData(dataSet);
                    pieData.setValueFormatter(new PercentFormatter());
                    pieData.setValueTextSize(11f);
                    pieData.setValueTextColor(Color.WHITE);

                    int rssiColor = getColor(R.color.colorGreenBg);
                    if (data.avg_rssi < 60 && 30 <= data.avg_rssi) {
                        rssiColor = getColor(R.color.colorYellow);
                    } else if (data.avg_rssi < 30) {
                        rssiColor = getColor(R.color.colorRed);
                    }

                    binding.pcLinkStat.setData(pieData);
                    binding.pcLinkStat.setCenterTextSize(22);
                    binding.pcLinkStat.setCenterText("" + data.avg_rssi);
                    binding.pcLinkStat.setCenterTextColor(rssiColor);
                    binding.pcLinkStat.invalidate();

                    // Set link icon tint color.
                    int color = getColor(R.color.colorGreenBg);
                    if ((float) data.count_p_fec_recovered / data.count_p_all > 0.2) {
                        color = getColor(R.color.colorYellowBg);
                    }
                    if (data.count_p_lost > 0) {
                        color = getColor(R.color.colorRedBg);
                    }
                    binding.imgLinkStatus.setImageTintList(ColorStateList.valueOf(color));

                    binding.tvLinkStatus.setText(String.format("Outgoing %3d Decoded %3d Recovered %3d Lost %3d",
                            data.count_p_outgoing,
                            data.count_p_dec_ok,
                            data.count_p_fec_recovered,
                            data.count_p_lost));
                }
            } else {
                binding.tvLinkStatus.setText("No Video Link");
            }
        });
    }

    @Override
    public void onNewMavlinkData(MavlinkData data) {
        runOnUiThread(() -> osdManager.render(data));
    }

    private void copyGSKey() {
        File file = new File(getApplicationContext().getFilesDir(), "gs.key");
        OutputStream out = null;
        try {
            byte[] keyBytes = getGsKey();
            Log.d(TAG, "Using gs.key:" + bytesToHex(keyBytes) + "; Copying to" + file.getAbsolutePath());
            out = new FileOutputStream(file);
            out.write(keyBytes, 0, keyBytes.length);
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy asset", e);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    // NOOP
                }
            }
        }
    }

    private void showLoginCredentialsDialog() {
        // Create a LinearLayout to hold our EditText fields
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(50, 30, 50, 30); // Add some padding around the content

        // EditText for username
        final android.widget.EditText usernameEditText = new android.widget.EditText(this);
        usernameEditText.setHint("Username");
        usernameEditText.setText(getDroneUsername()); // Pre-fill with current saved username
        layout.addView(usernameEditText);

        // EditText for password
        final android.widget.EditText passwordEditText = new android.widget.EditText(this);
        passwordEditText.setHint("Password");
        // Mask the password input
        passwordEditText.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordEditText.setText(getDronePassword()); // Pre-fill with current saved password
        layout.addView(passwordEditText);

        // CheckBox to toggle password visibility
        final android.widget.CheckBox showPasswordCheckBox = new android.widget.CheckBox(this);
        showPasswordCheckBox.setText("Show Password");
        layout.addView(showPasswordCheckBox);

        // Set a listener for the CheckBox
        showPasswordCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                // If checked, show the password (visible_password)
                passwordEditText.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            } else {
                // If unchecked, hide the password (password)
                passwordEditText.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            }
            // Move the cursor to the end of the text to prevent it from jumping to the beginning
            passwordEditText.setSelection(passwordEditText.getText().length());
        });

        // Build and show the AlertDialog
        new android.app.AlertDialog.Builder(this)
                .setTitle("Drone Login Credentials")
                .setView(layout) // Set our custom layout
                .setPositiveButton("Save", (dialog, which) -> {
                    // Save the new values to SharedPreferences
                    setDroneUsername(usernameEditText.getText().toString());
                    setDronePassword(passwordEditText.getText().toString());
                    Toast.makeText(this, "Drone credentials saved.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", (dialog, which) -> {
                    dialog.cancel(); // Dismiss the dialog
                })
                .show();
    }

    // Helper method to retrieve the drone username
    // Provides a default "root" if not yet set, for initial compatibility.
    private String getDroneUsername() {
        return getSharedPreferences("general", Context.MODE_PRIVATE).getString(PREF_DRONE_USERNAME, "root");
    }

    // Helper method to save the drone username
    private void setDroneUsername(String username) {
        SharedPreferences prefs = getSharedPreferences("general", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(PREF_DRONE_USERNAME, username);
        editor.apply();
    }

    // Helper method to retrieve the drone password
    // Provides a default "12345" if not yet set, for initial compatibility.
    private String getDronePassword() {
        return getSharedPreferences("general", Context.MODE_PRIVATE).getString(PREF_DRONE_PASSWORD, "12345");
    }

    // Helper method to save the drone password
    private void setDronePassword(String password) {
        SharedPreferences prefs = getSharedPreferences("general", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(PREF_DRONE_PASSWORD, password);
        editor.apply();
    }

    @SuppressLint("SetJavaScriptEnabled")
    public void startBrowser() {
        WebView view = new WebView(this);
        view.setWebViewClient(new WebViewClient());
        view.getSettings().setJavaScriptEnabled(true);
        view.loadUrl("10.5.0.10");

        Dialog dialog = new Dialog(this);
        dialog.setContentView(view);
        dialog.setCanceledOnTouchOutside(true);

        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        int screenWidth = (int) (displayMetrics.widthPixels * 0.75);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(screenWidth, WindowManager.LayoutParams.MATCH_PARENT);
        }
        dialog.show();

        view.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedHttpAuthRequest(
                    WebView view, HttpAuthHandler handler, String host, String realm) {
                // Retrieve username and password from SharedPreferences
                String username = getDroneUsername();
                String password = getDronePassword();
                handler.proceed(username, password);
            }
        });
    }
}
