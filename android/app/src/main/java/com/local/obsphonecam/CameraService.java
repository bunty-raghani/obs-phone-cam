package com.local.obsphonecam;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.util.Log;
import android.util.Range;
import android.view.Surface;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Camera2 capture -> hardware H.264 surface encoder -> USB (adb reverse) relay.
 *
 * The camera catalog selects any camera the phone exposes (main, auxiliary or
 * logical rear/front), an exact size/fps pair the camera and encoder both
 * support, and the bitrate/rate-control/processing quality requested by the UI.
 */
public final class CameraService extends Service {
    public static final String ACTION_START = "com.local.obsphonecam.START";
    public static final String ACTION_STOP = "com.local.obsphonecam.STOP";
    public static final String ACTION_SWAP = "com.local.obsphonecam.SWAP";

    /** Current extras (exact selection from the UI catalog). */
    public static final String EXTRA_CAMERA_ID = "camera_id";
    public static final String EXTRA_WIDTH = "width";
    public static final String EXTRA_HEIGHT = "height";
    public static final String EXTRA_FPS = "fps";
    public static final String EXTRA_BITRATE = "bitrate";
    public static final String EXTRA_RATE_MODE = "rate_mode";
    public static final String EXTRA_HIGH_QUALITY = "high_quality";
    public static final String EXTRA_STABILIZATION = "stabilization";

    /** Legacy extras kept for older launchers/scripts. */
    public static final String EXTRA_FRONT_CAMERA = "front_camera";
    public static final String EXTRA_RESOLUTION_HEIGHT = "resolution_height";
    public static final String EXTRA_TARGET_FPS = "target_fps";

    /** Notification shortcut: swap to the opposite-facing lens, keeping the current mode request. */
    public static final String EXTRA_SWAP_FACING_FRONT = "swap_facing_front";

    private static final String CHANNEL_ID = "obsphonecam_stream";
    private static final int NOTIFICATION_ID = 41;
    private static final int PORT = 8765;
    private static final int DEFAULT_WIDTH = 1280;
    private static final int DEFAULT_HEIGHT = 720;
    private static final int DEFAULT_FPS = 30;
    private static final double AUTO_BITS_PER_PIXEL = 0.20;

    /** Network flag (bit 4; MediaCodec uses bits 0-3): in-band "WIDTH HEIGHT FPS" mode notice. */
    private static final int FLAG_MODE_UPDATE = 1 << 4;

    /**
     * The mode update travels as an Annex-B filler NAL. A server build predating the swap
     * feature reads it as a normal frame; a filler NAL decodes to nothing, so an old server
     * stays harmless instead of choking on raw text.
     */
    private static final byte[] MODE_UPDATE_PREFIX = {0, 0, 0, 1, 0x0C};

    public interface Listener {
        void onStreamState(boolean running, int width, int height, String message);
    }

    public final class LocalBinder extends Binder {
        public CameraService getService() {
            return CameraService.this;
        }
    }

    private final LocalBinder binder = new LocalBinder();
    private final ArrayBlockingQueue<Packet> sendQueue = new ArrayBlockingQueue<>(16);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile Listener listener;
    private volatile Surface previewSurface;
    private volatile Socket networkSocket;
    private volatile String lastStatus = "Ready";

    // Requested selection (validated and possibly adjusted against the catalog).
    private String requestedCameraId;
    private boolean frontPreferred;
    private Integer requestedWidth;
    private Integer requestedHeight;
    private Integer requestedFps;
    private int requestedBitrateBps;
    private boolean requestedVbr;
    private boolean highQualityProcessing = true;
    private boolean stabilization;

    private volatile ResolvedMode mode;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private int sessionGeneration;
    private int cameraGeneration;
    private volatile MediaCodec encoder;
    private Surface encoderSurface;
    private Thread networkThread;
    private PowerManager.WakeLock wakeLock;
    private NotificationManager notificationManager;
    private boolean foreground;
    private long lastNotificationNs;
    private long statStartedNs;
    private int statFrames;
    private long statBytes;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "OBS PhoneCam USB stream", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shown while the phone camera is streaming to OBS over USB");
        channel.setSound(null, null);
        notificationManager.createNotificationChannel(channel);
    }

    @Override public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override public boolean onUnbind(Intent intent) {
        setListener(null);
        if (!running.get()) stopSelf();
        return false;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopStreamingInternal("Stopped", true);
            return START_NOT_STICKY;
        }
        if (ACTION_SWAP.equals(action) && running.get()) {
            if (intent.hasExtra(EXTRA_SWAP_FACING_FRONT)) {
                // Notification shortcut: opposite lens, same mode request.
                requestedCameraId = null;
                frontPreferred = intent.getBooleanExtra(EXTRA_SWAP_FACING_FRONT, false);
            } else {
                readRequest(intent);
            }
            performSwap();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action) && !running.get()) {
            readRequest(intent);
            promoteToForeground("Starting camera and USB stream");
            acquireWakeLock();
            startStreaming();
        }
        return START_NOT_STICKY;
    }

    private void readRequest(Intent intent) {
        int width = intent.getIntExtra(EXTRA_WIDTH, 0);
        int height = intent.getIntExtra(EXTRA_HEIGHT, 0);
        if (height <= 0) height = intent.getIntExtra(EXTRA_RESOLUTION_HEIGHT, 0);
        if (width <= 0 && height > 0) width = roundEven(height * 16 / 9); // legacy callers pass height only

        int fps = intent.getIntExtra(EXTRA_FPS, 0);
        if (fps <= 0) fps = intent.getIntExtra(EXTRA_TARGET_FPS, 0);

        applyRequest(intent.getStringExtra(EXTRA_CAMERA_ID),
                intent.getBooleanExtra(EXTRA_FRONT_CAMERA, false),
                width, height, fps,
                intent.getIntExtra(EXTRA_BITRATE, 0),
                "vbr".equalsIgnoreCase(intent.getStringExtra(EXTRA_RATE_MODE)),
                intent.getBooleanExtra(EXTRA_HIGH_QUALITY, true),
                intent.getBooleanExtra(EXTRA_STABILIZATION, false));
    }

    /** The catalog selection a start or swap request runs with. Zero/empty means "let the catalog decide". */
    private void applyRequest(String cameraId, boolean front, int width, int height, int fps,
                              int bitrateBps, boolean vbr, boolean highQuality, boolean stabilize) {
        requestedCameraId = cameraId == null || cameraId.isEmpty() ? null : cameraId;
        frontPreferred = front;
        requestedWidth = width > 0 ? width : null;
        requestedHeight = height > 0 ? height : null;
        requestedFps = fps > 0 ? fps : null;
        requestedBitrateBps = Math.max(0, bitrateBps);
        requestedVbr = vbr;
        highQualityProcessing = highQuality;
        stabilization = stabilize;
    }

    public void setListener(Listener newListener) {
        listener = newListener;
        dispatchState();
    }

    public void setPreviewSurface(Surface surface) {
        if (surface != null && !surface.isValid()) surface = null;
        if (previewSurface == surface) return;
        previewSurface = surface;
        Handler handler = cameraHandler;
        if (handler != null && camera != null && running.get()) handler.post(this::createSession);
    }

    public void requestStop() {
        mainHandler.post(() -> stopStreamingInternal("Stopped", true));
    }

    /** True while a stream is being started or is live (used by the Activity to pick the swap path). */
    public boolean isRunning() {
        return running.get();
    }

    /** Swaps the live stream to another lens without dropping the USB link. */
    public void requestSwap(String cameraId, int width, int height, int fps, int bitrateBps,
                            String rateMode, boolean highQuality, boolean stabilize) {
        mainHandler.post(() -> {
            applyRequest(cameraId, false, width, height, fps, bitrateBps,
                    "vbr".equalsIgnoreCase(rateMode), highQuality, stabilize);
            if (running.get()) performSwap();
        });
    }

    // -------------------------------------------------------------------- swap

    /**
     * Restarts the camera/encoder pipeline on the newly requested lens while the USB socket,
     * wake lock and foreground notification stay up. The encoder is rebuilt for the new mode
     * and the relay is told the new geometry in band, so OBS keeps the same Media Source and
     * resumes on the next keyframe. Runs on the main thread, serialized with stop.
     */
    private void performSwap() {
        if (!running.get()) return;
        if (cameraHandler == null) {
            // Still connecting: just change the mode the pending open will use.
            try {
                mode = resolveMode();
                queueModeUpdate();
            } catch (Exception error) {
                fail("Camera switch failed: " + shortMessage(error));
            }
            return;
        }
        setStatus("Switching camera…", true);
        try {
            sessionGeneration++; // retire callbacks from the outgoing session
            cameraGeneration++;  // and from the outgoing camera device
            if (session != null) {
                try { session.close(); } catch (Exception ignored) { }
                session = null;
            }
            if (camera != null) {
                try { camera.close(); } catch (Exception ignored) { }
                camera = null;
            }
            if (encoder != null) {
                try { encoder.stop(); } catch (Exception ignored) { }
                try { encoder.release(); } catch (Exception ignored) { }
                encoder = null;
            }
            if (encoderSurface != null) {
                encoderSurface.release();
                encoderSurface = null;
            }
            sendQueue.clear(); // drop frames produced by the outgoing lens

            mode = resolveMode();
            Log.i("OBSPhoneCam", "Switched to " + mode.shortLabel() + " " + mode.width + "x" + mode.height
                    + " @ " + mode.fps + " fps, " + (mode.bitrateBps / 1_000_000) + " Mbps");
            queueModeUpdate();
            setStatus(String.format(Locale.US, "Switching camera…\n%s\n%d×%d @ %d fps",
                    mode.shortLabel(), mode.width, mode.height, mode.fps), true);
            configureEncoder();
            openCamera();
        } catch (Exception error) {
            fail("Camera switch failed: " + shortMessage(error));
        }
    }

    /** In-band "WIDTH HEIGHT FPS" notice so the relay's status keeps matching the live mode. */
    private void queueModeUpdate() {
        ResolvedMode current = mode;
        if (current == null) return;
        byte[] text = String.format(Locale.US, "%d %d %d", current.width, current.height, current.fps)
                .getBytes(StandardCharsets.US_ASCII);
        byte[] payload = new byte[MODE_UPDATE_PREFIX.length + text.length];
        System.arraycopy(MODE_UPDATE_PREFIX, 0, payload, 0, MODE_UPDATE_PREFIX.length);
        System.arraycopy(text, 0, payload, MODE_UPDATE_PREFIX.length, text.length);
        sendQueue.offer(new Packet(payload, 0, FLAG_MODE_UPDATE));
    }

    private void startStreaming() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopStreamingInternal("Camera permission is required", true);
            return;
        }
        try {
            mode = resolveMode();
            running.set(true);
            sendQueue.clear();
            Log.i("OBSPhoneCam", "Selected " + mode.shortLabel() + " " + mode.width + "x" + mode.height + " @ "
                    + mode.fps + " fps, " + (mode.bitrateBps / 1_000_000) + " Mbps"
                    + (mode.note == null ? "" : " (" + mode.note + ")"));
            setStatus(String.format(Locale.US, "Connecting over USB…\n%s\n%d×%d @ %d fps",
                    mode.shortLabel(), mode.width, mode.height, mode.fps), true);
            startNetwork();
        } catch (Exception error) {
            stopStreamingInternal("Cannot start: " + shortMessage(error), true);
        }
    }

    // ---------------------------------------------------------------- selection

    /** Resolves the requested selection against every camera the phone exposes. */
    private ResolvedMode resolveMode() throws CameraAccessException {
        List<CameraCatalog.CameraEntry> entries = CameraCatalog.cameras(this);
        if (entries.isEmpty()) {
            throw new IllegalStateException("Android reports no cameras");
        }

        List<CameraCatalog.CameraEntry> candidates = new ArrayList<>();
        if (requestedCameraId != null) {
            for (CameraCatalog.CameraEntry entry : entries) {
                if (entry.id.equals(requestedCameraId)) candidates.add(entry);
            }
            if (candidates.isEmpty()) {
                throw new IllegalStateException("Camera ID " + requestedCameraId + " not found");
            }
        } else {
            int facing = frontPreferred ? CameraCharacteristics.LENS_FACING_FRONT
                    : CameraCharacteristics.LENS_FACING_BACK;
            for (CameraCatalog.CameraEntry entry : entries) {
                if (entry.facing == facing && entry.hasVideoSizes) candidates.add(entry);
            }
            if (candidates.isEmpty()) {
                for (CameraCatalog.CameraEntry entry : entries) {
                    if (entry.hasVideoSizes) candidates.add(entry);
                }
            }
        }
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No camera exposes a video output");
        }

        int wantWidth = requestedWidth != null ? requestedWidth : DEFAULT_WIDTH;
        int wantHeight = requestedHeight != null ? requestedHeight : DEFAULT_HEIGHT;
        int wantFps = requestedFps != null ? requestedFps : DEFAULT_FPS;

        // Pass 1: exact size and frame rate.
        for (CameraCatalog.CameraEntry entry : candidates) {
            CameraCatalog.Format format = entry.findFormat(wantWidth, wantHeight);
            if (format != null && format.hasFps(wantFps)) {
                return buildResolved(entry, format, wantFps, null);
            }
        }
        // Pass 2: exact size, nearest supported frame rate.
        if (requestedFps != null) {
            for (CameraCatalog.CameraEntry entry : candidates) {
                CameraCatalog.Format format = entry.findFormat(wantWidth, wantHeight);
                if (format != null) {
                    int fps = format.nearestFps(wantFps);
                    if (fps > 0) {
                        return buildResolved(entry, format, fps,
                                String.format(Locale.US, "%d fps is not exposed at %d×%d; using %d fps",
                                        wantFps, wantWidth, wantHeight, fps));
                    }
                }
            }
        }
        // Pass 3: best mode the best camera offers.
        for (CameraCatalog.CameraEntry entry : candidates) {
            CameraCatalog.Format format = entry.bestFormat();
            if (format == null) continue;
            int fps = format.nearestFps(wantFps);
            if (fps <= 0) continue;
            return buildResolved(entry, format, fps,
                    String.format(Locale.US, "%d×%d @ %d fps is not exposed through Camera2; using %d×%d @ %d fps",
                            wantWidth, wantHeight, wantFps, format.width, format.height, fps));
        }
        throw new IllegalStateException("No usable video mode found on " + candidates.size() + " camera(s)");
    }

    private ResolvedMode buildResolved(CameraCatalog.CameraEntry entry, CameraCatalog.Format format,
                                       int fps, String note) {
        boolean highSpeed = format.isHighSpeed(fps);
        Range<Integer> range = highSpeed ? format.highSpeedRange(fps)
                : CameraCatalog.pickFpsRange(entry.aeRanges, fps);
        if (range == null) {
            range = new Range<>(fps, fps);
        }
        int bitrate = requestedBitrateBps > 0
                ? clampBitrate(requestedBitrateBps)
                : clampBitrate((int) Math.round(format.width * (double) format.height * fps * AUTO_BITS_PER_PIXEL));
        return new ResolvedMode(entry, format.width, format.height, fps, highSpeed, range, bitrate, note);
    }

    private static int clampBitrate(int bps) {
        MediaCodecInfo.VideoCapabilities video = CameraCatalog.avcVideoCapabilities();
        int value = Math.max(500_000, bps);
        if (video != null) {
            try {
                Range<Integer> allowed = video.getBitrateRange();
                value = Math.max(allowed.getLower(), Math.min(allowed.getUpper(), value));
            } catch (Exception ignored) { }
        }
        return value;
    }

    private static int roundEven(int value) {
        return value % 2 == 0 ? value : value + 1;
    }

    // ---------------------------------------------------------------- network

    private void startNetwork() {
        networkThread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
            try (Socket socket = new Socket()) {
                networkSocket = socket;
                socket.setTcpNoDelay(true);
                socket.setSendBufferSize(256 * 1024);
                socket.connect(new InetSocketAddress("127.0.0.1", PORT), 2500);
                try (DataOutputStream out = new DataOutputStream(
                        new BufferedOutputStream(socket.getOutputStream(), 256 * 1024))) {
                    String hello = String.format(Locale.US, "PHONECAM/2 %d %d %d %d\n",
                            mode.width, mode.height, mode.fps, System.nanoTime());
                    out.write(hello.getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    mainHandler.post(this::startCamera);
                    while (running.get()) {
                        Packet packet = sendQueue.take();
                        out.writeInt(packet.data.length);
                        out.writeLong(packet.ptsUs);
                        out.writeInt(packet.flags);
                        out.write(packet.data);
                        out.flush();
                    }
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                if (running.get()) fail("USB link failed: " + shortMessage(error) +
                        "\nRun the server and check USB debugging");
            } finally {
                networkSocket = null;
            }
        }, "OBSPhoneCam-network");
        networkThread.start();
    }

    private void startCamera() {
        if (!running.get()) return;
        try {
            cameraThread = new HandlerThread("OBSPhoneCam-camera", Process.THREAD_PRIORITY_DISPLAY);
            cameraThread.start();
            cameraHandler = new Handler(cameraThread.getLooper());
            configureEncoder();
            openCamera();
        } catch (Exception error) {
            fail("Start failed: " + shortMessage(error));
        }
    }

    /** Opens the resolved camera; callbacks are ignored once a newer open or teardown wins. */
    private void openCamera() throws CameraAccessException {
        final int generation = cameraGeneration;
        android.hardware.camera2.CameraManager manager =
                (android.hardware.camera2.CameraManager) getSystemService(Context.CAMERA_SERVICE);
        manager.openCamera(mode.cameraId, new CameraDevice.StateCallback() {
            @Override public void onOpened(CameraDevice device) {
                if (!running.get() || generation != cameraGeneration) {
                    device.close();
                    return;
                }
                camera = device;
                createSession();
            }

            @Override public void onDisconnected(CameraDevice device) {
                device.close();
                if (generation != cameraGeneration) return;
                if (running.get()) fail("Camera disconnected");
            }

            @Override public void onError(CameraDevice device, int error) {
                device.close();
                if (generation != cameraGeneration) return;
                if (running.get()) fail(cameraErrorText(error));
            }
        }, cameraHandler);
    }

    // ---------------------------------------------------------------- encoder

    private void configureEncoder() throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, mode.width, mode.height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, mode.bitrateBps);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, mode.fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        format.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1);
        format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        format.setInteger(MediaFormat.KEY_OPERATING_RATE, mode.fps);
        if (Build.VERSION.SDK_INT >= 29) {
            format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            format.setInteger(MediaFormat.KEY_LATENCY, 1);
        }

        MediaCodecInfo.EncoderCapabilities encoderCaps = CameraCatalog.avcEncoderCapabilities();
        int rateMode = requestedVbr ? MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                : MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR;
        boolean rateModeApplied = false;
        if (encoderCaps != null) {
            boolean vbrOk = encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
            boolean cbrOk = encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
            if (requestedVbr && vbrOk) {
                rateModeApplied = true;
            } else if (!requestedVbr && cbrOk) {
                rateModeApplied = true;
            } else if (vbrOk) {
                rateMode = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR;
                rateModeApplied = true;
            } else if (cbrOk) {
                rateMode = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR;
                rateModeApplied = true;
            } else {
                rateModeApplied = true; // no explicit mode available; leave default
                rateMode = -1;
            }
        }
        if (rateModeApplied && rateMode >= 0) {
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, rateMode);
        }
        mode.rateModeName = rateMode == MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                ? "VBR" : rateMode == MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR ? "CBR" : "auto";

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        encoder.setCallback(codecCallback, cameraHandler);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoderSurface = encoder.createInputSurface();
        encoder.start();
    }

    private static String cameraErrorText(int error) {
        switch (error) {
            case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:
                return "Camera is in use by another app";
            case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:
                return "Too many cameras are in use";
            case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:
                return "Camera is disabled by the system";
            case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE:
                return "Camera hardware glitch — try again";
            case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE:
                return "Camera service is restarting — try again";
            default:
                return "Camera error " + error;
        }
    }

    private void createSession() {
        if (!running.get() || camera == null || encoderSurface == null) return;
        final int generation = ++sessionGeneration;
        if (session != null) {
            try { session.close(); } catch (Exception ignored) { }
            session = null;
        }

        Surface candidate = previewSurface;
        final Surface activePreview = candidate != null && candidate.isValid() ? candidate : null;
        List<Surface> outputs = new ArrayList<>();
        if (activePreview != null) outputs.add(activePreview);
        outputs.add(encoderSurface);

        CameraCaptureSession.StateCallback callback = new CameraCaptureSession.StateCallback() {
            @Override public void onConfigured(CameraCaptureSession captureSession) {
                if (!running.get() || generation != sessionGeneration) {
                    captureSession.close();
                    return;
                }
                session = captureSession;
                startRepeating(captureSession, activePreview);
            }

            @Override public void onConfigureFailed(CameraCaptureSession captureSession) {
                captureSession.close();
                if (!running.get() || generation != sessionGeneration) return;
                if (activePreview != null) {
                    previewSurface = null;
                    createSession();
                } else {
                    fail(String.format(Locale.US, "Camera rejected %d×%d @ %d fps%s",
                            mode.width, mode.height, mode.fps, mode.highSpeed ? " high-speed" : ""));
                }
            }
        };

        try {
            if (mode.highSpeed) camera.createConstrainedHighSpeedCaptureSession(outputs, callback, cameraHandler);
            else camera.createCaptureSession(outputs, callback, cameraHandler);
        } catch (Exception error) {
            if (activePreview != null) {
                previewSurface = null;
                createSession();
            } else {
                fail("Session failed: " + shortMessage(error));
            }
        }
    }

    private void startRepeating(CameraCaptureSession captureSession, Surface activePreview) {
        try {
            CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            request.addTarget(encoderSurface);
            if (activePreview != null && activePreview.isValid()) request.addTarget(activePreview);
            request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, mode.range);

            if (highQualityProcessing) {
                applyIfAvailable(request, CaptureRequest.NOISE_REDUCTION_MODE,
                        CameraCharacteristics.NOISE_REDUCTION_MODE_HIGH_QUALITY, mode.entry.noiseReductionModes);
                applyIfAvailable(request, CaptureRequest.EDGE_MODE,
                        CameraCharacteristics.EDGE_MODE_HIGH_QUALITY, mode.entry.edgeModes);
                applyIfAvailable(request, CaptureRequest.SHADING_MODE,
                        CameraCharacteristics.SHADING_MODE_HIGH_QUALITY, mode.entry.shadingModes);
                applyIfAvailable(request, CaptureRequest.HOT_PIXEL_MODE,
                        CameraCharacteristics.HOT_PIXEL_MODE_HIGH_QUALITY, mode.entry.hotPixelModes);
            }
            if (contains(mode.entry.stabilizationModes, CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                    || contains(mode.entry.stabilizationModes, CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_OFF)) {
                request.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, stabilization
                        ? CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                        : CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
            }

            if (mode.highSpeed) {
                CameraConstrainedHighSpeedCaptureSession highSpeed =
                        (CameraConstrainedHighSpeedCaptureSession) captureSession;
                highSpeed.setRepeatingBurst(highSpeed.createHighSpeedRequestList(request.build()), null, cameraHandler);
            } else {
                captureSession.setRepeatingRequest(request.build(), null, cameraHandler);
            }
            statStartedNs = System.nanoTime();
            statFrames = 0;
            statBytes = 0;
            String detail = String.format(Locale.US, "LIVE over USB\n%s\n%d×%d @ %d fps  %.1f Mb/s %s",
                    mode.shortLabel(), mode.width, mode.height, mode.fps,
                    mode.bitrateBps / 1_000_000.0, mode.rateModeName);
            if (mode.note != null) detail += "\n" + mode.note;
            else detail += "\nLock the screen now if you want";
            setStatus(detail, true);
        } catch (Exception error) {
            fail("Capture failed: " + shortMessage(error));
        }
    }

    private static void applyIfAvailable(CaptureRequest.Builder request, CaptureRequest.Key<Integer> key,
                                         int value, int[] available) {
        if (contains(available, value)) request.set(key, value);
    }

    private static boolean contains(int[] values, int wanted) {
        if (values == null) return false;
        for (int value : values) {
            if (value == wanted) return true;
        }
        return false;
    }

    private final MediaCodec.Callback codecCallback = new MediaCodec.Callback() {
        @Override public void onInputBufferAvailable(MediaCodec codec, int index) { }

        @Override public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
            if (codec != encoder) return; // a swap/stop already retired this encoder
            try {
                ByteBuffer buffer = codec.getOutputBuffer(index);
                if (buffer != null && info.size > 0 && running.get()) {
                    buffer.position(info.offset);
                    buffer.limit(info.offset + info.size);
                    byte[] data = new byte[info.size];
                    buffer.get(data);
                    Packet packet = new Packet(data, info.presentationTimeUs, info.flags);
                    if (!sendQueue.offer(packet)) {
                        sendQueue.clear();
                        sendQueue.offer(packet);
                        Bundle sync = new Bundle();
                        sync.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                        codec.setParameters(sync);
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) updateStats(info.size);
                }
            } catch (IllegalStateException retired) {
                // The encoder was released while this callback was in flight; nothing to do.
            } finally {
                try {
                    codec.releaseOutputBuffer(index, false);
                } catch (IllegalStateException ignored) { }
            }
        }

        @Override public void onError(MediaCodec codec, MediaCodec.CodecException error) {
            if (codec != encoder) return;
            if (running.get()) fail("Encoder failed: " + shortMessage(error));
        }

        @Override public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
            if (codec != encoder) return;
            ByteBuffer csd0 = format.getByteBuffer("csd-0");
            ByteBuffer csd1 = format.getByteBuffer("csd-1");
            int size = (csd0 == null ? 0 : csd0.remaining()) + (csd1 == null ? 0 : csd1.remaining());
            if (size == 0) return;
            byte[] config = new byte[size];
            int at = 0;
            if (csd0 != null) {
                int count = csd0.remaining();
                csd0.get(config, at, count);
                at += count;
            }
            if (csd1 != null) csd1.get(config, at, csd1.remaining());
            sendQueue.offer(new Packet(config, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG));
        }
    };

    private void updateStats(int bytes) {
        statFrames++;
        statBytes += bytes;
        long now = System.nanoTime();
        double seconds = (now - statStartedNs) / 1_000_000_000.0;
        if (seconds < 1.0) return;
        double fps = statFrames / seconds;
        double mbps = statBytes * 8.0 / seconds / 1_000_000.0;
        statStartedNs = now;
        statFrames = 0;
        statBytes = 0;
        setStatus(String.format(Locale.US, "LIVE over USB  %.1f fps  %.1f Mb/s\n%s  %d×%d",
                fps, mbps, mode.shortLabel(), mode.width, mode.height), false);
    }

    private void fail(String message) {
        mainHandler.post(() -> stopStreamingInternal(message, true));
    }

    private void stopStreamingInternal(String message, boolean stopService) {
        running.set(false);
        sessionGeneration++;
        cameraGeneration++;
        sendQueue.clear();

        Socket socket = networkSocket;
        networkSocket = null;
        if (socket != null) {
            try { socket.close(); } catch (Exception ignored) { }
        }
        if (networkThread != null) networkThread.interrupt();
        networkThread = null;

        if (session != null) {
            try { session.close(); } catch (Exception ignored) { }
            session = null;
        }
        if (camera != null) {
            try { camera.close(); } catch (Exception ignored) { }
            camera = null;
        }
        if (encoder != null) {
            try { encoder.stop(); } catch (Exception ignored) { }
            try { encoder.release(); } catch (Exception ignored) { }
            encoder = null;
        }
        if (encoderSurface != null) {
            encoderSurface.release();
            encoderSurface = null;
        }
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
            cameraHandler = null;
        }

        mode = null;
        releaseWakeLock();
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
        lastStatus = message;
        dispatchState();
        if (stopService) stopSelf();
    }

    private void promoteToForeground(String detail) {
        Notification notification = buildNotification(detail);
        foreground = true;
        lastNotificationNs = System.nanoTime();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification(String detail) {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 1, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, CameraService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 2, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean currentFront = mode != null && mode.entry.isFront();
        Intent swapIntent = new Intent(this, CameraService.class)
                .setAction(ACTION_SWAP)
                .putExtra(EXTRA_SWAP_FACING_FRONT, !currentFront);
        PendingIntent swap = PendingIntent.getService(this, 3, swapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String oneLine = detail.replace('\n', ' ');
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("OBS PhoneCam streaming over USB")
                .setContentText(oneLine)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(android.R.drawable.ic_menu_rotate, "Switch camera", swap)
                .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
                .build();
    }

    private void setStatus(String message, boolean forceNotification) {
        lastStatus = message;
        dispatchState();
        if (!foreground) return;
        long now = System.nanoTime();
        if (forceNotification || now - lastNotificationNs >= 10_000_000_000L) {
            lastNotificationNs = now;
            notificationManager.notify(NOTIFICATION_ID, buildNotification(message));
        }
    }

    private void dispatchState() {
        Listener current = listener;
        if (current == null) return;
        boolean active = running.get();
        ResolvedMode currentMode = mode;
        int width = currentMode == null ? 0 : currentMode.width;
        int height = currentMode == null ? 0 : currentMode.height;
        String message = lastStatus;
        mainHandler.post(() -> {
            if (listener == current) current.onStreamState(active, width, height, message);
        });
    }

    @SuppressLint("WakelockTimeout")
    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager manager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OBSPhoneCam:USBStream");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private static String shortMessage(Throwable error) {
        String text = error.getMessage();
        return text == null || text.isEmpty() ? error.getClass().getSimpleName() : text;
    }

    @Override
    public void onDestroy() {
        stopStreamingInternal("Stopped", false);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- types

    private static final class ResolvedMode {
        final CameraCatalog.CameraEntry entry;
        final String cameraId;
        final int width;
        final int height;
        final int fps;
        final boolean highSpeed;
        final Range<Integer> range;
        final int bitrateBps;
        final String note;
        String rateModeName = "CBR";

        ResolvedMode(CameraCatalog.CameraEntry entry, int width, int height, int fps,
                     boolean highSpeed, Range<Integer> range, int bitrateBps, String note) {
            this.entry = entry;
            this.cameraId = entry.id;
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.highSpeed = highSpeed;
            this.range = range;
            this.bitrateBps = bitrateBps;
            this.note = note;
        }

        String shortLabel() {
            String facing = entry.isBack() ? "Back" : entry.isFront() ? "Front" : "Camera";
            return facing + " camera (ID " + entry.id + ")";
        }
    }

    private static final class Packet {
        final byte[] data;
        final long ptsUs;
        final int flags;

        Packet(byte[] data, long ptsUs, int flags) {
            this.data = data;
            this.ptsUs = ptsUs;
            this.flags = flags;
        }
    }
}
