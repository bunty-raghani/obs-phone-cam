package com.local.obsphonecam;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCharacteristics;
import android.media.MediaCodecInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * OBS PhoneCam 1.5.
 *
 * Landscape screen: the camera preview fills the screen; a slide-in settings
 * panel on the right can be hidden once configured. A small floating button in
 * the corner starts/stops streaming, a second one swaps the live stream between
 * the front and rear lenses, and a blinking green dot shows LIVE.
 */
public final class MainActivity extends Activity implements SurfaceHolder.Callback, CameraService.Listener {

    private static final int CAMERA_PERMISSION = 7;
    private static final String PREFS = "obsphonecam";
    private static final String[] QUALITY_NAMES = {"Basic", "Balanced", "High (recommended)", "Maximum", "Custom…"};
    private static final double[] QUALITY_BITS_PER_PIXEL = {0.07, 0.12, 0.20, 0.30};
    private static final int QUALITY_CUSTOM = 4;

    private static final int COLOR_BG = Color.rgb(13, 17, 20);
    private static final int COLOR_PANEL = Color.rgb(20, 26, 31);
    private static final int COLOR_ACCENT = Color.rgb(94, 230, 168);
    private static final int COLOR_TEXT = Color.rgb(214, 221, 227);
    private static final int COLOR_MUTED = Color.rgb(138, 150, 159);
    private static final int COLOR_DOT = Color.rgb(62, 224, 130);
    private static final int COLOR_STOP = Color.rgb(206, 78, 78);
    private static final int SIDEBAR_WIDTH_DP = 320;

    private final List<CameraCatalog.CameraEntry> catalog = new ArrayList<>();
    private final List<String> cameraLabels = new ArrayList<>();
    private final List<String> ratioNames = new ArrayList<>();
    private final List<CameraCatalog.Format> resolutionFormats = new ArrayList<>();
    private final List<String> rateModes = new ArrayList<>();
    private final List<String> rateModeValues = new ArrayList<>();

    private FrameLayout previewWrap;
    private SurfaceView preview;
    private View previewDim;
    private LinearLayout sidebar;
    private TextView settingsPill;
    private TextView statusChip;
    private View liveDot;
    private TextView fab;
    private TextView swapFab;
    private Spinner cameraSpinner;
    private TextView cameraInfo;
    private Spinner ratioSpinner;
    private Spinner resolutionSpinner;
    private Spinner fpsSpinner;
    private Spinner qualitySpinner;
    private TextView bitrateHint;
    private LinearLayout bitrateRow;
    private SeekBar bitrateBar;
    private TextView bitrateValue;
    private TextView advancedToggle;
    private LinearLayout advancedWrap;
    private Spinner rateModeSpinner;
    private Spinner processingSpinner;
    private Spinner stabilizationSpinner;
    private LinearLayout stabilizationRow;

    private CameraService service;
    private SharedPreferences prefs;

    private boolean bound;
    private boolean resumed;
    private boolean surfaceReady;
    private boolean streaming;
    private boolean catalogReady;
    private boolean updating;
    private boolean startAfterPermission;
    private boolean sidebarOpen;
    private boolean swapping;

    private long bitrateBps = 8_000_000L;
    private Long encoderMaxBps;
    private ObjectAnimator dotBlink;

    // One-shot selection carried by the launch intent (auto_start / CLI testing).
    private boolean pendingAutoStart;
    private String pendingCameraId;
    private Integer pendingWidth;
    private Integer pendingHeight;
    private Integer pendingFps;
    private Integer pendingBitrate;
    private String pendingRateMode;
    private Boolean pendingProcessing;
    private Boolean pendingStabilization;
    private Boolean pendingFront;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((CameraService.LocalBinder) binder).getService();
            bound = true;
            service.setListener(MainActivity.this);
            attachPreview();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
            streaming = false;
            setLiveIndicator(false);
            updateControls();
            statusChip.setText("Streaming service disconnected");
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        bitrateBps = prefs.getInt("bitrate", 12_000_000);
        buildUi();
        parseSelectionExtras(getIntent());
        preview.getHolder().setFixedSize(1280, 720);
        preview.getHolder().addCallback(this);
        bindService(new Intent(this, CameraService.class), connection, Context.BIND_AUTO_CREATE);
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            loadCatalog();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        parseSelectionExtras(intent);
        if (streaming) {
            clearPendingFields();
            return;
        }
        if (catalogReady) {
            refreshSelections();
            boolean autoStart = pendingAutoStart;
            clearPendingFields();
            if (autoStart) startStream();
        }
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_BG);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(main, new FrameLayout.LayoutParams(-1, -1));

        previewWrap = new FrameLayout(this);
        previewWrap.setBackgroundColor(Color.BLACK);
        main.addView(previewWrap, new LinearLayout.LayoutParams(0, -1, 1f));

        preview = new SurfaceView(this);
        previewWrap.addView(preview, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        previewWrap.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> layoutPreview());

        previewDim = new View(this);
        previewDim.setBackgroundColor(0x66000000);
        previewDim.setVisibility(View.GONE);
        previewWrap.addView(previewDim, new FrameLayout.LayoutParams(-1, -1));

        statusChip = new TextView(this);
        statusChip.setText("Connect USB, then press start");
        statusChip.setTextColor(COLOR_TEXT);
        statusChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusChip.setMaxLines(2);
        statusChip.setMaxWidth(dp(340));
        statusChip.setEllipsize(TextUtils.TruncateAt.END);
        statusChip.setBackground(rounded(0xB312181D, 14));
        statusChip.setPadding(dp(14), dp(8), dp(14), dp(8));
        FrameLayout.LayoutParams chipParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        chipParams.setMargins(dp(14), 0, dp(14), dp(14));
        previewWrap.addView(statusChip, chipParams);

        settingsPill = new TextView(this);
        settingsPill.setText("Settings");
        settingsPill.setTextColor(COLOR_ACCENT);
        settingsPill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        settingsPill.setTypeface(Typeface.DEFAULT_BOLD);
        settingsPill.setBackground(rounded(0xCC1B222A, 18));
        settingsPill.setPadding(dp(16), dp(9), dp(16), dp(9));
        settingsPill.setOnClickListener(v -> setSidebarOpen(true));
        FrameLayout.LayoutParams pillParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        pillParams.setMargins(0, dp(14), dp(14), 0);
        previewWrap.addView(settingsPill, pillParams);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        liveDot = new View(this);
        liveDot.setBackground(circle(COLOR_DOT));
        liveDot.setVisibility(View.GONE);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(10), dp(10));
        dotParams.setMargins(0, 0, dp(12), 0);
        controls.addView(liveDot, dotParams);
        swapFab = new TextView(this);
        swapFab.setText("\u21C4");
        swapFab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        swapFab.setGravity(Gravity.CENTER);
        swapFab.setTextColor(COLOR_ACCENT);
        swapFab.setBackground(circle(0xCC1B222A));
        swapFab.setContentDescription("Switch between front and back camera");
        swapFab.setOnClickListener(v -> swapCamera());
        LinearLayout.LayoutParams swapParams = new LinearLayout.LayoutParams(dp(54), dp(54));
        swapParams.setMargins(0, 0, dp(12), 0);
        controls.addView(swapFab, swapParams);
        fab = new TextView(this);
        fab.setText("▶");
        fab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        fab.setGravity(Gravity.CENTER);
        fab.setTextColor(COLOR_BG);
        fab.setBackground(circle(COLOR_ACCENT));
        fab.setContentDescription("Start or stop streaming");
        fab.setOnClickListener(v -> toggleStreaming());
        controls.addView(fab, new LinearLayout.LayoutParams(dp(54), dp(54)));
        FrameLayout.LayoutParams controlsParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        controlsParams.setMargins(0, 0, dp(16), dp(16));
        previewWrap.addView(controls, controlsParams);

        sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setBackgroundColor(COLOR_PANEL);
        sidebar.setVisibility(View.GONE);
        main.addView(sidebar, new LinearLayout.LayoutParams(0, -1));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(12), dp(12), dp(6));
        TextView title = new TextView(this);
        title.setText("Settings");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView hide = new TextView(this);
        hide.setText("Hide ▸");
        hide.setTextColor(COLOR_ACCENT);
        hide.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hide.setPadding(dp(10), dp(6), dp(4), dp(6));
        hide.setOnClickListener(v -> setSidebarOpen(false));
        header.addView(hide, new LinearLayout.LayoutParams(-2, -2));
        sidebar.addView(header, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        sidebar.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(2), dp(16), dp(28));
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));

        content.addView(sectionLabel("CAMERA"), matchWrap());
        cameraSpinner = newSpinner();
        content.addView(cameraSpinner, matchWrap());
        cameraInfo = smallText("Scanning…");
        content.addView(cameraInfo, matchWrap());

        content.addView(sectionLabel("VIDEO"), matchWrap());
        content.addView(fieldLabel("Aspect ratio"), matchWrap());
        ratioSpinner = newSpinner();
        content.addView(ratioSpinner, matchWrap());
        content.addView(fieldLabel("Resolution"), matchWrap());
        resolutionSpinner = newSpinner();
        content.addView(resolutionSpinner, matchWrap());
        content.addView(fieldLabel("Frame rate"), matchWrap());
        fpsSpinner = newSpinner();
        content.addView(fpsSpinner, matchWrap());

        content.addView(sectionLabel("QUALITY"), matchWrap());
        qualitySpinner = newSpinner();
        qualitySpinner.setAdapter(adapter(QUALITY_NAMES));
        content.addView(qualitySpinner, matchWrap());
        bitrateHint = smallText("");
        content.addView(bitrateHint, matchWrap());
        bitrateRow = new LinearLayout(this);
        bitrateRow.setOrientation(LinearLayout.HORIZONTAL);
        bitrateRow.setGravity(Gravity.CENTER_VERTICAL);
        bitrateRow.setVisibility(View.GONE);
        bitrateBar = new SeekBar(this);
        bitrateBar.setMax(100);
        bitrateRow.addView(bitrateBar, new LinearLayout.LayoutParams(0, -2, 1f));
        bitrateValue = labelText("");
        bitrateValue.setTextColor(COLOR_ACCENT);
        bitrateValue.setGravity(Gravity.END);
        bitrateRow.addView(bitrateValue, new LinearLayout.LayoutParams(dp(84), -2));
        content.addView(bitrateRow, matchWrap());

        advancedToggle = new TextView(this);
        advancedToggle.setText("Advanced ▸");
        advancedToggle.setTextColor(COLOR_MUTED);
        advancedToggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        advancedToggle.setPadding(0, dp(18), 0, dp(4));
        advancedToggle.setOnClickListener(v -> toggleAdvanced());
        content.addView(advancedToggle, matchWrap());
        advancedWrap = new LinearLayout(this);
        advancedWrap.setOrientation(LinearLayout.VERTICAL);
        advancedWrap.setVisibility(View.GONE);
        content.addView(advancedWrap, matchWrap());
        advancedWrap.addView(fieldLabel("Rate control"), matchWrap());
        rateModeSpinner = newSpinner();
        advancedWrap.addView(rateModeSpinner, matchWrap());
        advancedWrap.addView(fieldLabel("Processing"), matchWrap());
        processingSpinner = newSpinner();
        processingSpinner.setAdapter(adapter(new String[]{"High quality", "Fastest"}));
        advancedWrap.addView(processingSpinner, matchWrap());
        stabilizationSpinner = newSpinner();
        stabilizationSpinner.setAdapter(adapter(new String[]{"Off", "On"}));
        stabilizationRow = new LinearLayout(this);
        stabilizationRow.setOrientation(LinearLayout.VERTICAL);
        stabilizationRow.addView(fieldLabel("Stabilization"), matchWrap());
        stabilizationRow.addView(stabilizationSpinner, matchWrap());
        advancedWrap.addView(stabilizationRow, matchWrap());

        setContentView(root);

        onSpinnerSelected(cameraSpinner, () -> { saveSelections(); onCameraSelected(); });
        onSpinnerSelected(ratioSpinner, this::onRatioSelected);
        onSpinnerSelected(resolutionSpinner, () -> { saveSelections(); onResolutionSelected(); });
        onSpinnerSelected(fpsSpinner, () -> { applyPresetBitrate(); saveSelections(); });
        onSpinnerSelected(qualitySpinner, () -> {
            if (qualitySpinner.getSelectedItemPosition() != QUALITY_CUSTOM) applyPresetBitrate();
            updateBitrateUi();
            saveSelections();
        });
        onSpinnerSelected(rateModeSpinner, this::saveSelections);
        onSpinnerSelected(processingSpinner, this::saveSelections);
        onSpinnerSelected(stabilizationSpinner, this::saveSelections);
        bitrateBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || updating) return;
                bitrateBps = progress * 1_000_000L;
                updating = true;
                qualitySpinner.setSelection(QUALITY_CUSTOM);
                updating = false;
                updateBitrateUi();
                saveSelections();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
    }

    private void layoutPreview() {
        int availableWidth = previewWrap.getWidth();
        int availableHeight = previewWrap.getHeight();
        if (availableWidth <= 0 || availableHeight <= 0) return;
        int width = availableWidth;
        int height = width * 9 / 16;
        if (height > availableHeight) {
            height = availableHeight;
            width = height * 16 / 9;
        }
        ViewGroup.LayoutParams params = preview.getLayoutParams();
        if (params.width == width && params.height == height) return;
        params.width = width;
        params.height = height;
        preview.setLayoutParams(params);
    }

    private void setSidebarOpen(boolean open) {
        if (sidebarOpen == open) return;
        sidebarOpen = open;
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) sidebar.getLayoutParams();
        params.width = open ? dp(SIDEBAR_WIDTH_DP) : 0;
        sidebar.setLayoutParams(params);
        sidebar.setVisibility(open ? View.VISIBLE : View.GONE);
        if (open) {
            sidebar.animate().cancel();
            sidebar.setAlpha(0f);
            sidebar.animate().alpha(1f).setDuration(150).start();
        }
        settingsPill.setVisibility(open ? View.GONE : View.VISIBLE);
    }

    private void toggleAdvanced() {
        boolean show = advancedWrap.getVisibility() != View.VISIBLE;
        advancedWrap.setVisibility(show ? View.VISIBLE : View.GONE);
        advancedToggle.setText(show ? "Advanced ▾" : "Advanced ▸");
    }

    private void setLiveIndicator(boolean live) {
        if (live) {
            liveDot.setVisibility(View.VISIBLE);
            if (dotBlink == null) {
                dotBlink = ObjectAnimator.ofFloat(liveDot, "alpha", 1f, 0.25f);
                dotBlink.setDuration(650);
                dotBlink.setRepeatMode(ObjectAnimator.REVERSE);
                dotBlink.setRepeatCount(ObjectAnimator.INFINITE);
            }
            if (!dotBlink.isStarted()) dotBlink.start();
        } else {
            if (dotBlink != null) dotBlink.cancel();
            liveDot.setAlpha(1f);
            liveDot.setVisibility(View.GONE);
        }
        fab.setText(live ? "■" : "▶");
        fab.setBackground(circle(live ? COLOR_STOP : COLOR_ACCENT));
        fab.setTextColor(live ? Color.WHITE : COLOR_BG);
        previewDim.setVisibility(live ? View.GONE : View.VISIBLE);
    }

    private void updateControls() {
        boolean enabled = !streaming;
        cameraSpinner.setEnabled(enabled);
        ratioSpinner.setEnabled(enabled);
        resolutionSpinner.setEnabled(enabled);
        fpsSpinner.setEnabled(enabled);
        qualitySpinner.setEnabled(enabled);
        bitrateBar.setEnabled(enabled);
        rateModeSpinner.setEnabled(enabled);
        processingSpinner.setEnabled(enabled);
        stabilizationSpinner.setEnabled(enabled);
    }

    // ------------------------------------------------------------ camera swap

    /** The other lens: opposite facing preferred, then any other usable camera. */
    private CameraCatalog.CameraEntry swapTargetEntry() {
        CameraCatalog.CameraEntry current = currentEntry();
        CameraCatalog.CameraEntry opposite = null;
        CameraCatalog.CameraEntry other = null;
        boolean currentFront = current != null && current.isFront();
        for (CameraCatalog.CameraEntry entry : catalog) {
            if (!entry.hasVideoSizes) continue;
            if (current != null && entry.id.equals(current.id)) continue;
            if (other == null || swapScore(entry) > swapScore(other)) other = entry;
            boolean isOpposite = current == null || (currentFront ? entry.isBack() : entry.isFront());
            if (isOpposite && (opposite == null || swapScore(entry) > swapScore(opposite))) opposite = entry;
        }
        return opposite != null ? opposite : other;
    }

    private static long swapScore(CameraCatalog.CameraEntry entry) {
        CameraCatalog.Format best = entry.bestFormat();
        return best == null ? 0 : (long) best.width * best.height;
    }

    private void updateSwapAvailability() {
        swapFab.setVisibility(swapTargetEntry() != null ? View.VISIBLE : View.GONE);
    }

    /**
     * Switches lenses with one tap. While live, the service restarts the camera pipeline
     * on the other lens without dropping the USB link; while idle, the button just selects
     * that lens for the next start. The last selected resolution and frame rate are kept
     * whenever the other lens exposes them; otherwise the largest mode supporting the frame
     * rate (same aspect ratio first), then the largest mode overall, with the bitrate the
     * selected quality preset recommends.
     */
    private void swapCamera() {
        if (swapping) return;
        if (!catalogReady || catalog.isEmpty()) {
            statusChip.setText("Camera list is still loading");
            return;
        }
        CameraCatalog.CameraEntry target = swapTargetEntry();
        if (target == null) {
            statusChip.setText("No other usable camera on this phone");
            return;
        }
        CameraCatalog.Format previous = currentFormat();
        int wantWidth = previous == null ? 0 : previous.width;
        int wantHeight = previous == null ? 0 : previous.height;
        int wantFps = currentFps();

        swapping = true;
        updating = true;
        cameraSpinner.setSelection(indexOfCamera(target.id));
        updating = false;
        onCameraSelected();

        updating = true;
        resolutionSpinner.setSelection(chooseSwapFormatIndex(wantWidth, wantHeight, wantFps));
        updating = false;
        onResolutionSelected();
        applyPresetBitrate();
        saveSelections();

        CameraCatalog.Format chosen = currentFormat();
        if (chosen == null) {
            swapping = false;
            statusChip.setText("This camera exposes no video mode");
            return;
        }
        String facing = target.isFront() ? "front" : target.isBack() ? "back" : "camera " + target.id;
        if (streaming) {
            swapFab.setAlpha(0.4f);
            statusChip.setText(String.format(Locale.US, "Switching to %s camera…", facing));
            if (service != null && service.isRunning()) {
                service.requestSwap(target.id, chosen.width, chosen.height, currentFps(),
                        (int) Math.min(Integer.MAX_VALUE, bitrateBps), currentRateModeValue(),
                        processingSpinner.getSelectedItemPosition() == 0,
                        stabilizationSpinner.getSelectedItemPosition() == 1);
            } else {
                startService(new Intent(this, CameraService.class)
                        .setAction(CameraService.ACTION_SWAP)
                        .putExtra(CameraService.EXTRA_CAMERA_ID, target.id)
                        .putExtra(CameraService.EXTRA_WIDTH, chosen.width)
                        .putExtra(CameraService.EXTRA_HEIGHT, chosen.height)
                        .putExtra(CameraService.EXTRA_FPS, currentFps())
                        .putExtra(CameraService.EXTRA_BITRATE, (int) Math.min(Integer.MAX_VALUE, bitrateBps))
                        .putExtra(CameraService.EXTRA_RATE_MODE, currentRateModeValue())
                        .putExtra(CameraService.EXTRA_HIGH_QUALITY, processingSpinner.getSelectedItemPosition() == 0)
                        .putExtra(CameraService.EXTRA_STABILIZATION, stabilizationSpinner.getSelectedItemPosition() == 1));
            }
        } else {
            swapping = false;
            swapFab.setAlpha(1f);
            statusChip.setText(String.format(Locale.US, "Set to %s camera · %d×%d @ %d fps — press ▶ to start",
                    facing, chosen.width, chosen.height, currentFps()));
        }
    }

    /**
     * Resolution index for the other lens, from the list it currently offers: the last
     * selected size and rate, then the last selected size with the nearest rate, then the
     * largest mode that supports the last rate, then simply the largest mode. The list is
     * largest-first and the fps spinner settles on the nearest supported rate.
     */
    private int chooseSwapFormatIndex(int wantWidth, int wantHeight, int wantFps) {
        if (resolutionFormats.isEmpty()) return 0;
        if (wantWidth > 0 && wantHeight > 0) {
            for (int i = 0; i < resolutionFormats.size(); i++) {
                CameraCatalog.Format format = resolutionFormats.get(i);
                if (format.width == wantWidth && format.height == wantHeight && format.hasFps(wantFps)) return i;
            }
            for (int i = 0; i < resolutionFormats.size(); i++) {
                CameraCatalog.Format format = resolutionFormats.get(i);
                if (format.width == wantWidth && format.height == wantHeight) return i;
            }
        }
        for (int i = 0; i < resolutionFormats.size(); i++) {
            if (resolutionFormats.get(i).hasFps(wantFps)) return i;
        }
        return 0;
    }

    // ------------------------------------------------------- camera catalog

    private void loadCatalog() {
        cameraInfo.setText("Scanning…");
        new Thread(() -> {
            try {
                final List<CameraCatalog.CameraEntry> found = CameraCatalog.cameras(MainActivity.this);
                MediaCodecInfo.CodecCapabilities capabilities = CameraCatalog.avcCodec();
                final Long maxBps = capabilities == null ? null
                        : (long) capabilities.getVideoCapabilities().getBitrateRange().getUpper();
                runOnUiThread(() -> onCatalogLoaded(found, maxBps));
            } catch (final Exception error) {
                runOnUiThread(() -> {
                    cameraInfo.setText("Camera scan failed");
                    statusChip.setText("Camera scan failed: " + error);
                });
            }
        }, "obsphonecam-catalog").start();
    }

    private void onCatalogLoaded(List<CameraCatalog.CameraEntry> found, Long maxBps) {
        catalog.clear();
        catalog.addAll(found);
        catalogReady = true;
        encoderMaxBps = maxBps;
        bitrateBar.setMax(barMaxMbps());
        populateRateModes();
        refreshSelections();
        boolean autoStart = pendingAutoStart;
        clearPendingFields();
        if (autoStart) startStream();
    }

    private void refreshSelections() {
        buildCameraLabels();
        int defaultCamera = defaultCameraIndex();
        Log.i("OBSPhoneCam", "Selections: default camera index " + defaultCamera + " (saved="
                + prefs.getString("camera_id", "none") + ", pending=" + pendingCameraId + ")");
        updating = true;
        cameraSpinner.setAdapter(adapter(cameraLabels));
        cameraSpinner.setSelection(defaultCamera);
        updating = false;
        onCameraSelected();

        int quality = prefs.getInt("preset", 2);
        Integer bitrateOverride = pendingBitrate;
        int qualityIndex = bitrateOverride != null ? QUALITY_CUSTOM : Math.max(0, Math.min(QUALITY_CUSTOM, quality));
        updating = true;
        qualitySpinner.setSelection(qualityIndex);
        updating = false;
        if (qualityIndex == QUALITY_CUSTOM) {
            bitrateBps = clampBitrate(bitrateOverride != null ? bitrateOverride : prefs.getInt("bitrate", 12_000_000));
            updating = true;
            bitrateBar.setProgress(progressForBps(bitrateBps));
            updating = false;
            updateBitrateUi();
        } else {
            applyPresetBitrate();
        }

        updating = true;
        boolean processing = pendingProcessing != null ? pendingProcessing : prefs.getBoolean("processing", true);
        processingSpinner.setSelection(processing ? 0 : 1);
        boolean stabilization = pendingStabilization != null ? pendingStabilization : prefs.getBoolean("stabilization", false);
        stabilizationSpinner.setSelection(stabilization ? 1 : 0);
        updating = false;
        updateControls();
    }

    private void buildCameraLabels() {
        cameraLabels.clear();
        int rear = 0;
        int front = 0;
        for (CameraCatalog.CameraEntry entry : catalog) {
            String label;
            if (entry.isFront()) {
                front++;
                label = front > 1 ? "Front camera " + front : "Front camera";
            } else if (entry.isBack()) {
                rear++;
                label = rear > 1 ? "Rear camera " + rear : "Rear camera";
            } else {
                label = "Camera " + entry.id;
            }
            if (!entry.hasVideoSizes) label += " (no video)";
            cameraLabels.add(label);
        }
        if (cameraLabels.isEmpty()) cameraLabels.add("No cameras");
    }

    private void onCameraSelected() {
        CameraCatalog.CameraEntry entry = currentEntry();
        buildRatioList(entry);
        updating = true;
        ratioSpinner.setAdapter(adapter(ratioNames));
        ratioSpinner.setSelection(indexOfRatio(desiredWidth(), desiredHeight()));
        updating = false;
        buildResolutionList(entry);
        updating = true;
        resolutionSpinner.setAdapter(adapter(resolutionLabels()));
        resolutionSpinner.setSelection(indexOfFormat(desiredWidth(), desiredHeight()));
        updating = false;

        int rear = 0;
        int front = 0;
        for (CameraCatalog.CameraEntry camera : catalog) {
            if (camera.hasVideoSizes && camera.isBack()) rear++;
            if (camera.hasVideoSizes && camera.isFront()) front++;
        }
        String availability = rear + " rear · " + front + " front usable";
        if (CameraCatalog.lastHiddenBackCount() > 0) {
            availability += " · extra rear sensor is system-only";
        }
        cameraInfo.setText(availability);

        boolean stabilizationSupported = false;
        if (entry != null && entry.stabilizationModes != null) {
            for (int availableMode : entry.stabilizationModes) {
                if (availableMode == CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON) {
                    stabilizationSupported = true;
                    break;
                }
            }
        }
        stabilizationRow.setVisibility(stabilizationSupported ? View.VISIBLE : View.GONE);
        updateSwapAvailability();

        onResolutionSelected();
    }

    /** Aspect-ratio dropdown: only ratios with 720p-or-better modes, 16:9 first. */
    private void buildRatioList(CameraCatalog.CameraEntry entry) {
        ratioNames.clear();
        if (entry != null) {
            for (CameraCatalog.Format format : entry.formats) {
                if (!offeredTier(format.width, format.height)) continue;
                String key = CameraCatalog.aspectLabel(format.width, format.height);
                if (!ratioNames.contains(key)) ratioNames.add(key);
            }
            if (ratioNames.isEmpty()) {
                for (CameraCatalog.Format format : entry.formats) {
                    String key = CameraCatalog.aspectLabel(format.width, format.height);
                    if (!ratioNames.contains(key)) ratioNames.add(key);
                }
            }
        }
        if (ratioNames.isEmpty()) ratioNames.add("16:9");
        Collections.sort(ratioNames, (a, b) -> ratioRank(a) - ratioRank(b));
    }

    private static int ratioRank(String name) {
        if ("16:9".equals(name)) return 0;
        if ("4:3".equals(name)) return 1;
        if ("18:9".equals(name)) return 2;
        if ("3:2".equals(name)) return 3;
        return 4;
    }

    private String currentRatioName() {
        int index = ratioSpinner.getSelectedItemPosition();
        if (index < 0 || index >= ratioNames.size()) index = 0;
        return ratioNames.get(index);
    }

    private int indexOfRatio(int width, int height) {
        CameraCatalog.CameraEntry entry = currentEntry();
        if (entry != null && width > 0 && height > 0 && offeredTier(width, height)) {
            for (CameraCatalog.Format format : entry.formats) {
                if (format.width == width && format.height == height) {
                    return Math.max(0, ratioNames.indexOf(CameraCatalog.aspectLabel(width, height)));
                }
            }
        }
        return defaultRatioIndex();
    }

    /**
     * Default aspect: the shape that carries a 1080p-class mode (16:9 preferred),
     * so e.g. the front camera opens on its 1080p 4:3 mode instead of 720p.
     */
    private int defaultRatioIndex() {
        CameraCatalog.CameraEntry entry = currentEntry();
        if (entry == null || ratioNames.isEmpty()) return 0;
        int sixteenNine = ratioNames.indexOf("16:9");
        if (sixteenNine >= 0 && ratioHasHeight(entry, "16:9", 1080)) return sixteenNine;
        for (int i = 0; i < ratioNames.size(); i++) {
            if (ratioHasHeight(entry, ratioNames.get(i), 1080)) return i;
        }
        if (sixteenNine >= 0) return sixteenNine;
        return 0;
    }

    private static boolean ratioHasHeight(CameraCatalog.CameraEntry entry, String ratio, int height) {
        for (CameraCatalog.Format format : entry.formats) {
            if (!offeredTier(format.width, format.height)) continue;
            if (format.height == height && CameraCatalog.aspectLabel(format.width, format.height).equals(ratio)) {
                return true;
            }
        }
        return false;
    }

    /** The familiar options plus the phone's single largest capture mode. */
    private static boolean offeredTier(int width, int height) {
        if (width == 2592 && height == 1458) return true; // largest mode this phone offers
        if (height != 480 && height != 720 && height != 960 && height != 1080) return false;
        String ratio = CameraCatalog.aspectLabel(width, height);
        return ratio.equals("16:9") || ratio.equals("4:3") || ratio.equals("18:9");
    }

    private void onRatioSelected() {
        buildResolutionList(currentEntry());
        updating = true;
        resolutionSpinner.setAdapter(adapter(resolutionLabels()));
        resolutionSpinner.setSelection(indexOfFormat(desiredWidth(), desiredHeight()));
        updating = false;
        onResolutionSelected();
        saveSelections();
    }

    /** Resolution dropdown: the selected camera's modes for the selected aspect ratio. */
    private void buildResolutionList(CameraCatalog.CameraEntry entry) {
        resolutionFormats.clear();
        if (entry == null) return;
        String ratio = currentRatioName();
        for (CameraCatalog.Format format : entry.formats) {
            if (!CameraCatalog.aspectLabel(format.width, format.height).equals(ratio)) continue;
            if (offeredTier(format.width, format.height)) resolutionFormats.add(format);
        }
        if (resolutionFormats.isEmpty()) {
            for (CameraCatalog.Format format : entry.formats) {
                if (CameraCatalog.aspectLabel(format.width, format.height).equals(ratio)) {
                    resolutionFormats.add(format);
                }
            }
        }
    }

    private List<String> resolutionLabels() {
        List<String> labels = new ArrayList<>();
        for (CameraCatalog.Format format : resolutionFormats) {
            labels.add(niceName(format.width, format.height) + " · " + format.width + "×" + format.height);
        }
        if (labels.isEmpty()) labels.add("No video modes");
        return labels;
    }

    /** Common names people know, e.g. "1080p (Full HD)"; fallback is the pixel height. */
    private static String niceName(int width, int height) {
        if (width == 3840 && height == 2160) return "4K (2160p)";
        if (width == 2592 && height == 1458) return "Maximum";
        if (width == 2560 && height == 1440) return "1440p (2K)";
        if (width == 1920 && height == 1080) return "1080p (Full HD)";
        if (width == 1280 && height == 720) return "720p (HD)";
        if (height == 1080) return "1080p";
        if (height == 720) return "720p";
        if (height == 960) return "960p";
        if (height == 480) return "480p";
        return height + "p";
    }

    private void onResolutionSelected() {
        CameraCatalog.Format format = currentFormat();
        List<String> labels = new ArrayList<>();
        if (format != null) {
            for (int fps : format.allFps()) {
                labels.add(format.isHighSpeed(fps) ? fps + " fps (high-speed)" : fps + " fps");
            }
        }
        if (labels.isEmpty()) labels.add("—");
        updating = true;
        fpsSpinner.setAdapter(adapter(labels));
        fpsSpinner.setSelection(indexOfFps(format, desiredFps()));
        updating = false;
        applyPresetBitrate();
    }

    private void applyPresetBitrate() {
        CameraCatalog.Format format = currentFormat();
        if (format == null) return;
        int quality = qualitySpinner.getSelectedItemPosition();
        if (quality < 0 || quality >= QUALITY_BITS_PER_PIXEL.length) quality = 1;
        long bits = (long) (format.width * (double) format.height * currentFps() * QUALITY_BITS_PER_PIXEL[quality]);
        bitrateBps = clampBitrate(bits);
        updating = true;
        bitrateBar.setProgress(progressForBps(bitrateBps));
        updating = false;
        updateBitrateUi();
    }

    private void updateBitrateUi() {
        boolean custom = qualitySpinner.getSelectedItemPosition() == QUALITY_CUSTOM;
        bitrateRow.setVisibility(custom ? View.VISIBLE : View.GONE);
        bitrateHint.setVisibility(custom ? View.GONE : View.VISIBLE);
        if (custom) {
            bitrateValue.setText(String.format(Locale.US, "%.1f Mb/s", bitrateBps / 1_000_000.0));
        } else {
            bitrateHint.setText(String.format(Locale.US, "Auto: ~%.1f Mb/s at %d fps",
                    bitrateBps / 1_000_000.0, currentFps()));
        }
    }

    private void populateRateModes() {
        rateModes.clear();
        rateModeValues.clear();
        MediaCodecInfo.EncoderCapabilities encoder = CameraCatalog.avcEncoderCapabilities();
        boolean cbr = encoder == null || encoder.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        boolean vbr = encoder == null || encoder.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        if (cbr) {
            rateModes.add("CBR (constant bitrate)");
            rateModeValues.add("cbr");
        }
        if (vbr) {
            rateModes.add("VBR (variable bitrate)");
            rateModeValues.add("vbr");
        }
        if (rateModes.isEmpty()) {
            rateModes.add("Default");
            rateModeValues.add("cbr");
        }
        updating = true;
        rateModeSpinner.setAdapter(adapter(rateModes));
        rateModeSpinner.setSelection(indexOfRateMode(desiredRateMode()));
        updating = false;
    }

    private int barMaxMbps() {
        int maximum = 100;
        if (encoderMaxBps != null) maximum = (int) Math.max(10, Math.min(100, encoderMaxBps / 1_000_000));
        return Math.max(maximum, 20);
    }

    // ---------------------------------------------------------- selections

    private void parseSelectionExtras(Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra("auto_start", false)) pendingAutoStart = true;
        if (intent.hasExtra(CameraService.EXTRA_CAMERA_ID)) pendingCameraId = intent.getStringExtra(CameraService.EXTRA_CAMERA_ID);
        int width = intent.getIntExtra(CameraService.EXTRA_WIDTH, 0);
        int height = intent.getIntExtra(CameraService.EXTRA_HEIGHT, 0);
        if (height <= 0) height = intent.getIntExtra(CameraService.EXTRA_RESOLUTION_HEIGHT, 0);
        if (width <= 0 && height > 0) width = roundEven(height * 16 / 9);
        if (width > 0) pendingWidth = width;
        if (height > 0) pendingHeight = height;
        int fps = intent.getIntExtra(CameraService.EXTRA_FPS, 0);
        if (fps <= 0) fps = intent.getIntExtra(CameraService.EXTRA_TARGET_FPS, 0);
        if (fps > 0) pendingFps = fps;
        if (intent.hasExtra(CameraService.EXTRA_BITRATE)) pendingBitrate = intent.getIntExtra(CameraService.EXTRA_BITRATE, 0);
        if (intent.hasExtra(CameraService.EXTRA_RATE_MODE)) pendingRateMode = intent.getStringExtra(CameraService.EXTRA_RATE_MODE);
        if (intent.hasExtra(CameraService.EXTRA_HIGH_QUALITY)) pendingProcessing = intent.getBooleanExtra(CameraService.EXTRA_HIGH_QUALITY, true);
        if (intent.hasExtra(CameraService.EXTRA_STABILIZATION)) pendingStabilization = intent.getBooleanExtra(CameraService.EXTRA_STABILIZATION, false);
        if (intent.hasExtra(CameraService.EXTRA_FRONT_CAMERA)) pendingFront = intent.getBooleanExtra(CameraService.EXTRA_FRONT_CAMERA, false);
    }

    private void clearPendingFields() {
        pendingAutoStart = false;
        pendingCameraId = null;
        pendingWidth = null;
        pendingHeight = null;
        pendingFps = null;
        pendingBitrate = null;
        pendingRateMode = null;
        pendingProcessing = null;
        pendingStabilization = null;
        pendingFront = null;
    }

    private int defaultCameraIndex() {
        if (pendingCameraId != null) {
            int index = indexOfCamera(pendingCameraId);
            if (index >= 0) return index;
        }
        String saved = prefs.getString("camera_id", null);
        if (saved != null) {
            int index = indexOfCamera(saved);
            if (index >= 0) return index;
        }
        boolean wantFront = pendingFront != null && pendingFront;
        for (int i = 0; i < catalog.size(); i++) {
            CameraCatalog.CameraEntry entry = catalog.get(i);
            if (entry.hasVideoSizes && entry.isFront() == wantFront) return i;
        }
        for (int i = 0; i < catalog.size(); i++) {
            if (catalog.get(i).hasVideoSizes) return i;
        }
        return 0;
    }

    private int indexOfCamera(String id) {
        for (int i = 0; i < catalog.size(); i++) {
            if (catalog.get(i).id.equals(id)) return i;
        }
        return -1;
    }

    private CameraCatalog.CameraEntry currentEntry() {
        if (catalog.isEmpty()) return null;
        int index = cameraSpinner.getSelectedItemPosition();
        if (index < 0 || index >= catalog.size()) index = 0;
        return catalog.get(index);
    }

    private CameraCatalog.Format currentFormat() {
        if (resolutionFormats.isEmpty()) return null;
        int index = resolutionSpinner.getSelectedItemPosition();
        if (index < 0 || index >= resolutionFormats.size()) index = 0;
        return resolutionFormats.get(index);
    }

    private int currentFps() {
        CameraCatalog.Format format = currentFormat();
        if (format == null) return 30;
        List<Integer> all = format.allFps();
        if (all.isEmpty()) return 30;
        int index = fpsSpinner.getSelectedItemPosition();
        if (index < 0 || index >= all.size()) index = 0;
        return all.get(index);
    }

    private String currentRateModeValue() {
        int index = rateModeSpinner.getSelectedItemPosition();
        if (index < 0 || index >= rateModeValues.size()) return "cbr";
        return rateModeValues.get(index);
    }

    private int desiredWidth() {
        if (pendingWidth != null) return pendingWidth;
        return prefs.getInt("width", 0);
    }

    private int desiredHeight() {
        if (pendingHeight != null) return pendingHeight;
        return prefs.getInt("height", 0);
    }

    private int desiredFps() {
        if (pendingFps != null) return pendingFps;
        return prefs.getInt("fps", 30);
    }

    private String desiredRateMode() {
        if (pendingRateMode != null) return pendingRateMode;
        return prefs.getString("rate_mode", "cbr");
    }

    private int indexOfFormat(int width, int height) {
        if (resolutionFormats.isEmpty()) return 0;
        if (width > 0 && height > 0) {
            for (int i = 0; i < resolutionFormats.size(); i++) {
                CameraCatalog.Format format = resolutionFormats.get(i);
                if (format.width == width && format.height == height) return i;
            }
        }
        if (height > 0) {
            for (int i = 0; i < resolutionFormats.size(); i++) {
                if (resolutionFormats.get(i).height == height) return i;
            }
        }
        for (int i = 0; i < resolutionFormats.size(); i++) {
            if (resolutionFormats.get(i).height == 1080 && resolutionFormats.get(i).hasFps(30)) return i;
        }
        for (int i = 0; i < resolutionFormats.size(); i++) {
            if (resolutionFormats.get(i).hasFps(30)) return i;
        }
        return 0;
    }

    private int indexOfFps(CameraCatalog.Format format, int fps) {
        if (format == null) return 0;
        List<Integer> all = format.allFps();
        if (all.isEmpty()) return 0;
        int nearest = format.nearestFps(fps);
        int index = all.indexOf(nearest);
        return index < 0 ? 0 : index;
    }

    private int indexOfRateMode(String value) {
        int index = rateModeValues.indexOf(value);
        return index < 0 ? 0 : index;
    }

    private void saveSelections() {
        if (!catalogReady) return;
        CameraCatalog.CameraEntry entry = currentEntry();
        CameraCatalog.Format format = currentFormat();
        SharedPreferences.Editor editor = prefs.edit();
        if (entry != null) editor.putString("camera_id", entry.id);
        if (format != null) {
            editor.putInt("width", format.width);
            editor.putInt("height", format.height);
        }
        editor.putInt("fps", currentFps());
        editor.putInt("preset", qualitySpinner.getSelectedItemPosition());
        editor.putInt("bitrate", (int) Math.min(Integer.MAX_VALUE, bitrateBps));
        editor.putString("rate_mode", currentRateModeValue());
        editor.putBoolean("processing", processingSpinner.getSelectedItemPosition() == 0);
        editor.putBoolean("stabilization", stabilizationSpinner.getSelectedItemPosition() == 1);
        editor.apply();
    }

    // ------------------------------------------------------------ streaming

    private void toggleStreaming() {
        if (streaming) {
            if (service != null) service.requestStop();
            else startService(new Intent(this, CameraService.class).setAction(CameraService.ACTION_STOP));
            return;
        }
        startStream();
    }

    private void startStream() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            startAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
            return;
        }
        CameraCatalog.CameraEntry entry = currentEntry();
        CameraCatalog.Format format = currentFormat();
        if (entry == null || format == null) {
            statusChip.setText("No usable camera mode — pick another camera or resolution");
            return;
        }
        saveSelections();
        Intent intent = new Intent(this, CameraService.class)
                .setAction(CameraService.ACTION_START)
                .putExtra(CameraService.EXTRA_CAMERA_ID, entry.id)
                .putExtra(CameraService.EXTRA_WIDTH, format.width)
                .putExtra(CameraService.EXTRA_HEIGHT, format.height)
                .putExtra(CameraService.EXTRA_FPS, currentFps())
                .putExtra(CameraService.EXTRA_BITRATE, (int) Math.min(Integer.MAX_VALUE, bitrateBps))
                .putExtra(CameraService.EXTRA_RATE_MODE, currentRateModeValue())
                .putExtra(CameraService.EXTRA_HIGH_QUALITY, processingSpinner.getSelectedItemPosition() == 0)
                .putExtra(CameraService.EXTRA_STABILIZATION, stabilizationSpinner.getSelectedItemPosition() == 1);
        startForegroundService(intent);
        streaming = true;
        setSidebarOpen(false);
        statusChip.setText("Starting camera and USB stream…");
        updateControls();
    }

    @Override
    public void onStreamState(boolean isRunning, int width, int height, String message) {
        runOnUiThread(() -> {
            streaming = isRunning;
            if (message != null && !message.isEmpty()) statusChip.setText(message);
            // A swap stays pending until the new lens reports LIVE (or the stream stops/fails).
            if (!isRunning || message == null || !message.startsWith("Switching")) swapping = false;
            swapFab.setAlpha(swapping ? 0.4f : 1f);
            setLiveIndicator(isRunning);
            updateControls();
            attachPreview();
        });
    }

    // ------------------------------------------------------------ lifecycle

    private void attachPreview() {
        if (!bound || service == null) return;
        Surface surface = preview.getHolder().getSurface();
        service.setPreviewSurface(resumed && surfaceReady && surface != null && surface.isValid() ? surface : null);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        attachPreview();
    }

    @Override protected void onPause() {
        resumed = false;
        if (service != null) service.setPreviewSurface(null);
        super.onPause();
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        attachPreview();
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        surfaceReady = true;
        attachPreview();
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        if (service != null) service.setPreviewSurface(null);
    }

    @Override
    protected void onDestroy() {
        if (dotBlink != null) dotBlink.cancel();
        if (service != null) {
            service.setPreviewSurface(null);
            service.setListener(null);
        }
        if (bound) unbindService(connection);
        bound = false;
        service = null;
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != CAMERA_PERMISSION) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            if (!catalogReady) loadCatalog();
            else if (startAfterPermission) startStream();
        } else {
            statusChip.setText("Camera permission is required");
        }
        startAfterPermission = false;
    }

    // -------------------------------------------------------------- widgets

    private void onSpinnerSelected(Spinner spinner, Runnable action) {
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!updating) action.run();
            }

            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private Spinner newSpinner() {
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(adapter(new ArrayList<String>()));
        return spinner;
    }

    private ArrayAdapter<String> adapter(String[] values) {
        return adapter(Arrays.asList(values));
    }

    private ArrayAdapter<String> adapter(List<String> values) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private TextView sectionLabel(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(COLOR_ACCENT);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        view.setPadding(0, dp(18), 0, dp(4));
        return view;
    }

    private TextView fieldLabel(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(COLOR_MUTED);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        view.setPadding(0, dp(12), 0, 0);
        return view;
    }

    private TextView smallText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(COLOR_MUTED);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        return view;
    }

    private TextView labelText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(COLOR_TEXT);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        return shape;
    }

    private GradientDrawable circle(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(color);
        return shape;
    }

    private long clampBitrate(long bits) {
        long value = Math.max(1_000_000L, bits);
        if (encoderMaxBps != null) value = Math.min(value, encoderMaxBps);
        return value;
    }

    private int progressForBps(long bits) {
        return (int) Math.max(1, Math.min(bitrateBar.getMax(), Math.round(bits / 1_000_000.0)));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int roundEven(int value) {
        return value % 2 == 0 ? value : value + 1;
    }
}
