package com.local.obsphonecam;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Enumerates every Camera2 device the phone exposes (main, auxiliary and
 * logical rear/front cameras) and the size/frame-rate combinations that both
 * that camera and the hardware H.264 encoder can actually run.
 *
 * The old build only ever used the first camera of the requested facing and
 * hard-coded a few resolutions. This catalog reports what the hardware really
 * supports, including the aux rear sensors that have no legacy Camera1 shim.
 */
final class CameraCatalog {

    private static final String TAG = "OBSPhoneCam";

    /** Frame rates probed against every size; filtered by camera + encoder support. */
    private static final int[] FPS_CANDIDATES = {30, 60, 90, 120, 240};

    /** Back-facing sensors the system hides from apps (this phone: the 2 MP depth helper). */
    private static int lastHiddenBackCount;

    private CameraCatalog() { }

    static final class CameraEntry {
        final String id;
        final String label;
        final String note;
        final int facing;
        final boolean hasVideoSizes;
        final CameraCharacteristics characteristics;
        final StreamConfigurationMap map;
        final Range<Integer>[] aeRanges;
        final int[] noiseReductionModes;
        final int[] edgeModes;
        final int[] shadingModes;
        final int[] hotPixelModes;
        final int[] stabilizationModes;
        final List<Format> formats;

        CameraEntry(String id, String label, String note, int facing, boolean hasVideoSizes,
                    CameraCharacteristics characteristics, StreamConfigurationMap map,
                    Range<Integer>[] aeRanges, int[] noiseReductionModes, int[] edgeModes,
                    int[] shadingModes, int[] hotPixelModes, int[] stabilizationModes,
                    List<Format> formats) {
            this.id = id;
            this.label = label;
            this.note = note;
            this.facing = facing;
            this.hasVideoSizes = hasVideoSizes;
            this.characteristics = characteristics;
            this.map = map;
            this.aeRanges = aeRanges;
            this.noiseReductionModes = noiseReductionModes;
            this.edgeModes = edgeModes;
            this.shadingModes = shadingModes;
            this.hotPixelModes = hotPixelModes;
            this.stabilizationModes = stabilizationModes;
            this.formats = formats;
        }

        boolean isBack() { return facing == CameraCharacteristics.LENS_FACING_BACK; }
        boolean isFront() { return facing == CameraCharacteristics.LENS_FACING_FRONT; }

        Format findFormat(int width, int height) {
            for (Format format : formats) {
                if (format.width == width && format.height == height) return format;
            }
            return null;
        }

        Format bestFormat() {
            Format fallback = null;
            for (Format format : formats) {
                if (format.allFps().isEmpty()) continue;
                if (fallback == null) fallback = format;
                if (format.width * 9 == format.height * 16) return format; // prefer 16:9 for OBS
            }
            return fallback;
        }

        String summary() {
            if (formats.isEmpty()) return "No video modes";
            Format largest = formats.get(0);
            return String.format(Locale.US, "%d video modes, up to %d\u00D7%d @ %d fps",
                    formats.size(), largest.width, largest.height, largest.maxFps());
        }
    }

    static final class Format {
        final int width;
        final int height;
        final List<Integer> normalFps;
        final TreeMap<Integer, Range<Integer>> highSpeedFps;

        Format(int width, int height, List<Integer> normalFps, TreeMap<Integer, Range<Integer>> highSpeedFps) {
            this.width = width;
            this.height = height;
            this.normalFps = normalFps;
            this.highSpeedFps = highSpeedFps;
        }

        boolean isWide() { return width >= height; }

        boolean hasFps(int fps) {
            return normalFps.contains(fps) || highSpeedFps.containsKey(fps);
        }

        boolean isHighSpeed(int fps) {
            return !normalFps.contains(fps) && highSpeedFps.containsKey(fps);
        }

        Range<Integer> highSpeedRange(int fps) {
            return highSpeedFps.get(fps);
        }

        List<Integer> allFps() {
            TreeSet<Integer> merged = new TreeSet<>(normalFps);
            merged.addAll(highSpeedFps.keySet());
            return new ArrayList<>(merged);
        }

        int maxFps() {
            List<Integer> all = allFps();
            return all.isEmpty() ? 0 : all.get(all.size() - 1);
        }

        /** Largest supported fps <= target, else smallest supported fps, else 0. */
        int nearestFps(int target) {
            List<Integer> all = allFps();
            int below = 0;
            for (int fps : all) {
                if (fps == target) return fps;
                if (fps < target) below = fps;
            }
            if (below > 0) return below;
            return all.isEmpty() ? 0 : all.get(0);
        }

        String label() {
            return width + "\u00D7" + height + " " + aspectLabel(width, height);
        }
    }

    static List<CameraEntry> cameras(Context context) throws CameraAccessException {
        return cameras(context, false);
    }

    /**
     * @param probeHidden when true, hidden sensors are briefly opened to record whether
     *                    they can be used at all (diagnostic; never call on the main thread).
     */
    static List<CameraEntry> cameras(Context context, boolean probeHidden) throws CameraAccessException {
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        List<CameraEntry> entries = new ArrayList<>();
        lastHiddenBackCount = 0;
        String[] ids = manager.getCameraIdList();
        Log.i(TAG, "Camera2 exposes " + ids.length + " id(s): " + java.util.Arrays.toString(ids));
        for (String id : ids) {
            try {
                entries.add(describe(id, manager.getCameraCharacteristics(id)));
            } catch (Exception error) {
                Log.w(TAG, "Camera " + id + " not accessible: " + error);
            }
        }
        // Deep probe (diagnostic only): vendor builds sometimes answer characteristics
        // queries for sensors hidden from getCameraIdList. Such sensors cannot actually
        // be streamed to on this HAL, so they stay out of the list — logged for clarity.
        Set<String> listed = new LinkedHashSet<>();
        Collections.addAll(listed, ids);
        for (int probe = 0; probe <= 7; probe++) {
            String id = Integer.toString(probe);
            if (listed.contains(id)) continue;
            try {
                CameraCharacteristics chars = manager.getCameraCharacteristics(id);
                StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                Size array = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
                Log.i(TAG, "Hidden camera " + id
                        + " facing=" + chars.get(CameraCharacteristics.LENS_FACING)
                        + " sensor=" + (array == null ? "?" : array.getWidth() + "x" + array.getHeight())
                        + " level=" + chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                        + " 4k=" + has4k(map)
                        + " video=" + joinSizes(videoSizes(map)));
                Integer hiddenFacing = chars.get(CameraCharacteristics.LENS_FACING);
                if (hiddenFacing != null && hiddenFacing == CameraCharacteristics.LENS_FACING_BACK) {
                    lastHiddenBackCount++;
                }
                if (probeHidden) {
                    Log.i(TAG, "Hidden camera " + id + " open-probe: "
                            + (canOpen(manager, id) ? "opened" : "refused"));
                }
            } catch (Exception error) {
                Log.d(TAG, "Camera " + id + " not available: " + error.getMessage());
            }
        }
        return entries;
    }

    /** Hidden back-facing sensor count from the most recent scan. */
    static int lastHiddenBackCount() {
        return lastHiddenBackCount;
    }

    /** Opens a hidden sensor briefly (diagnostic) and closes it immediately. */
    private static boolean canOpen(CameraManager manager, String id) {
        final CountDownLatch done = new CountDownLatch(1);
        final boolean[] opened = {false};
        HandlerThread thread = new HandlerThread("obsphonecam-probe-" + id);
        thread.start();
        try {
            manager.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    opened[0] = true;
                    device.close();
                    done.countDown();
                }

                @Override public void onDisconnected(CameraDevice device) {
                    device.close();
                    done.countDown();
                }

                @Override public void onError(CameraDevice device, int error) {
                    device.close();
                    done.countDown();
                }
            }, new Handler(thread.getLooper()));
        } catch (Exception error) {
            Log.i(TAG, "Hidden camera " + id + " open threw: " + error.getMessage());
            done.countDown();
        }
        try {
            done.await(1600, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        thread.quitSafely();
        return opened[0];
    }

    private static boolean has4k(StreamConfigurationMap map) {
        if (map == null) return false;
        return contains4k(map.getOutputSizes(MediaCodec.class))
                || contains4k(map.getOutputSizes(MediaRecorder.class))
                || contains4k(map.getOutputSizes(ImageFormat.PRIVATE));
    }

    private static boolean contains4k(Size[] sizes) {
        if (sizes == null) return false;
        for (Size size : sizes) {
            if (size.getWidth() >= 3840 || size.getHeight() >= 2160) return true;
        }
        return false;
    }

    private static CameraEntry describe(String id, CameraCharacteristics chars) {
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Integer facingValue = chars.get(CameraCharacteristics.LENS_FACING);
        int facing = facingValue == null ? -1 : facingValue;

        Set<Size> sizes = videoSizes(map);
        Log.i(TAG, "Camera " + id + " size classes — recorder:" + countOf(map, MediaRecorder.class)
                + " codec:" + countOf(map, MediaCodec.class)
                + " private:" + countOf(map, ImageFormat.PRIVATE)
                + " -> video:" + joinSizes(sizes));
        List<Format> formats = formats(sizes, chars, map);
        boolean hasVideoSizes = false;
        for (Size size : sizes) {
            if (size.getWidth() >= 640 && size.getHeight() >= 360) {
                hasVideoSizes = true;
                break;
            }
        }

        StringBuilder note = new StringBuilder();
        if (map == null) {
            note.append("camera reports no output configuration");
        } else if (!hasVideoSizes) {
            note.append("no video-sized outputs (auxiliary sensor)");
        }
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                Set<String> physical = chars.getPhysicalCameraIds();
                if (physical != null && !physical.isEmpty()) {
                    if (note.length() > 0) note.append("  ");
                    note.append("logical multi-camera of ").append(physical.size()).append(" physical sensors");
                }
            } catch (Exception ignored) { }
        }

        String name = facing == CameraCharacteristics.LENS_FACING_BACK ? "Back"
                : facing == CameraCharacteristics.LENS_FACING_FRONT ? "Front"
                : facing == CameraCharacteristics.LENS_FACING_EXTERNAL ? "External"
                : "Camera " + id;

        StringBuilder details = new StringBuilder();
        Size pixels = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        if (pixels != null && pixels.getWidth() > 0 && pixels.getHeight() > 0) {
            double mp = pixels.getWidth() * (double) pixels.getHeight() / 1_000_000.0;
            details.append(mp >= 10 ? String.format(Locale.US, "%.0f MP", mp)
                    : String.format(Locale.US, "%.1f MP", mp));
        }
        float[] focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        if (focal != null && focal.length > 0 && focal[0] > 0) {
            if (details.length() > 0) details.append("  ");
            details.append(String.format(Locale.US, "%.2f mm", focal[0]));
        }
        float[] apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
        if (apertures != null && apertures.length > 0 && apertures[0] > 0) {
            if (details.length() > 0) details.append("  ");
            details.append(String.format(Locale.US, "f/%.1f", apertures[0]));
        }
        if (details.length() > 0) details.append("  ");
        details.append("ID ").append(id);

        logFpsDetails(id, chars, map, formats);

        String label = name + "  ·  " + details;
        return new CameraEntry(id, label, note.toString(), facing, hasVideoSizes, chars, map,
                chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES),
                chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES),
                chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES),
                chars.get(CameraCharacteristics.SHADING_AVAILABLE_MODES),
                chars.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES),
                chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES),
                formats);
    }

    /** Diagnostic: raw frame-rate ceiling of one camera, including high-speed ranges. */
    private static void logFpsDetails(String id, CameraCharacteristics chars, StreamConfigurationMap map, List<Format> formats) {
        StringBuilder ae = new StringBuilder();
        Range<Integer>[] aeRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (aeRanges != null) {
            for (Range<Integer> range : aeRanges) {
                if (ae.length() > 0) ae.append(' ');
                ae.append(range.getLower()).append('-').append(range.getUpper());
            }
        }
        StringBuilder durations = new StringBuilder();
        StringBuilder modes = new StringBuilder();
        for (Format format : formats) {
            Size size = new Size(format.width, format.height);
            long minDuration = minFrameDuration(map, size);
            if (durations.length() > 0) durations.append(' ');
            durations.append(format.width).append('x').append(format.height).append('=')
                    .append(minDuration > 0 ? String.format(Locale.US, "%.1fms", minDuration / 1_000_000.0) : "?");
            if (modes.length() > 0) modes.append(' ');
            modes.append(format.width).append('x').append(format.height).append(format.allFps());
        }
        StringBuilder hs = new StringBuilder();
        try {
            Size[] highSpeed = map == null ? null : map.getHighSpeedVideoSizes();
            if (highSpeed != null) {
                for (Size size : highSpeed) {
                    if (hs.length() > 0) hs.append(' ');
                    hs.append(size.getWidth()).append('x').append(size.getHeight());
                    Range<Integer>[] ranges = map.getHighSpeedVideoFpsRangesFor(size);
                    hs.append('[');
                    if (ranges != null) {
                        for (int i = 0; i < ranges.length; i++) {
                            if (i > 0) hs.append(',');
                            hs.append(ranges[i].getLower()).append('-').append(ranges[i].getUpper());
                        }
                    }
                    hs.append(']');
                }
            }
        } catch (Exception ignored) { }
        MediaCodecInfo.VideoCapabilities avc = avcVideoCapabilities();
        String encoder = "?";
        if (avc != null) {
            encoder = "1080p60=" + avc.areSizeAndRateSupported(1920, 1080, 60)
                    + " 720p60=" + avc.areSizeAndRateSupported(1280, 720, 60)
                    + " 720p240=" + avc.areSizeAndRateSupported(1280, 720, 240);
        }
        Log.i(TAG, "Camera " + id + " fps — AE:" + (ae.length() == 0 ? "(none)" : ae)
                + " | ms:" + (durations.length() == 0 ? "(none)" : durations)
                + " | hs:" + (hs.length() == 0 ? "(none)" : hs)
                + " | encoder:" + encoder
                + " | modes:" + (modes.length() == 0 ? "(none)" : modes));
    }

    private static Set<Size> videoSizes(StreamConfigurationMap map) {
        Set<Size> sizes = new LinkedHashSet<>();
        if (map == null) return sizes;
        // Prefer the HAL's MediaRecorder surface sizes: that is the exact list the
        // phone's own camera app records at, so every size in it is guaranteed to
        // work for a video stream. Fall back to the wider MediaCodec/PRIVATE union
        // only when a HAL reports no recorder sizes.
        addSizes(sizes, map.getOutputSizes(MediaRecorder.class));
        if (sizes.isEmpty()) {
            addSizes(sizes, map.getOutputSizes(MediaCodec.class));
            addSizes(sizes, map.getOutputSizes(ImageFormat.PRIVATE));
        }
        return sizes;
    }

    private static void addSizes(Set<Size> target, Size[] sizes) {
        if (sizes == null) return;
        for (Size size : sizes) {
            if (size.getWidth() > 0 && size.getHeight() > 0) target.add(size);
        }
    }

    private static int countOf(StreamConfigurationMap map, Class<?> surfaceClass) {
        if (map == null) return 0;
        Size[] sizes = map.getOutputSizes(surfaceClass);
        return sizes == null ? 0 : sizes.length;
    }

    private static int countOf(StreamConfigurationMap map, int format) {
        if (map == null) return 0;
        Size[] sizes = map.getOutputSizes(format);
        return sizes == null ? 0 : sizes.length;
    }

    private static String joinSizes(Set<Size> sizes) {
        StringBuilder text = new StringBuilder();
        int shown = 0;
        for (Size size : sizes) {
            if (shown++ == 24) {
                text.append(" …");
                break;
            }
            if (text.length() > 0) text.append(' ');
            text.append(size.getWidth()).append('x').append(size.getHeight());
        }
        return text.length() == 0 ? "(none)" : text.toString();
    }

    private static List<Format> formats(Set<Size> sizes, CameraCharacteristics chars, StreamConfigurationMap map) {
        List<Format> formats = new ArrayList<>();
        if (map == null) return formats;
        Range<Integer>[] aeRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        Set<Size> highSpeedSizes = new LinkedHashSet<>();
        try {
            Size[] listed = map.getHighSpeedVideoSizes();
            if (listed != null) Collections.addAll(highSpeedSizes, listed);
        } catch (Exception ignored) { }
        MediaCodecInfo.VideoCapabilities encoder = avcVideoCapabilities();

        for (Size size : sizes) {
            int width = size.getWidth();
            int height = size.getHeight();
            if (width < 640 || height < 360) continue;

            long minDuration = minFrameDuration(map, size);
            double durationCapFps = minDuration > 0 ? 1_000_000_000.0 / minDuration : 240.0;

            List<Integer> normalFps = new ArrayList<>();
            for (int fps : FPS_CANDIDATES) {
                if (fps > Math.floor(durationCapFps + 0.001)) continue;
                if (!coversFps(aeRanges, fps)) continue;
                if (!encoderSupports(encoder, width, height, fps)) continue;
                normalFps.add(fps);
            }

            TreeMap<Integer, Range<Integer>> highSpeedFps = new TreeMap<>();
            if (highSpeedSizes.contains(size)) {
                try {
                    Range<Integer>[] ranges = map.getHighSpeedVideoFpsRangesFor(size);
                    if (ranges != null) {
                        for (Range<Integer> range : ranges) {
                            int fps = range.getUpper();
                            if (normalFps.contains(fps)) continue;
                            if (!encoderSupports(encoder, width, height, fps)) continue;
                            highSpeedFps.put(fps, range);
                        }
                    }
                } catch (Exception ignored) { }
            }

            if (!normalFps.isEmpty() || !highSpeedFps.isEmpty()) {
                formats.add(new Format(width, height, normalFps, highSpeedFps));
            }
        }

        formats.sort(new Comparator<Format>() {
            @Override public int compare(Format a, Format b) {
                if (a.isWide() != b.isWide()) return a.isWide() ? -1 : 1;
                int areaA = a.width * a.height;
                int areaB = b.width * b.height;
                if (areaA != areaB) return areaB - areaA;
                return b.width - a.width;
            }
        });
        return formats;
    }

    private static long minFrameDuration(StreamConfigurationMap map, Size size) {
        long best = 0;
        try {
            long value = map.getOutputMinFrameDuration(MediaCodec.class, size);
            if (value > 0) best = value;
        } catch (Exception ignored) { }
        if (best == 0) {
            try {
                long value = map.getOutputMinFrameDuration(ImageFormat.PRIVATE, size);
                if (value > 0) best = value;
            } catch (Exception ignored) { }
        }
        return best;
    }

    private static boolean coversFps(Range<Integer>[] ranges, int fps) {
        if (ranges == null) return false;
        for (Range<Integer> range : ranges) {
            if (range.getLower() <= fps && fps <= range.getUpper()) return true;
        }
        return false;
    }

    /** Narrowest AE range that covers {@code fps}; exact-upper ranges win. */
    static Range<Integer> pickFpsRange(Range<Integer>[] ranges, int fps) {
        Range<Integer> best = null;
        if (ranges == null) return null;
        for (Range<Integer> range : ranges) {
            if (!range.contains(fps)) continue;
            if (best == null) {
                best = range;
                continue;
            }
            int span = range.getUpper() - range.getLower();
            int bestSpan = best.getUpper() - best.getLower();
            boolean exactUpper = range.getUpper() == fps;
            boolean bestExactUpper = best.getUpper() == fps;
            if ((exactUpper && !bestExactUpper) || (exactUpper == bestExactUpper && span < bestSpan)) {
                best = range;
            }
        }
        return best;
    }

    private static boolean encoderSupports(MediaCodecInfo.VideoCapabilities encoder, int width, int height, int fps) {
        if (encoder == null) return true;
        try {
            if (!encoder.isSizeSupported(width, height)) return false;
            return encoder.areSizeAndRateSupported(width, height, fps);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static MediaCodecInfo.CodecCapabilities codec;
    private static boolean codecLoaded;

    static synchronized MediaCodecInfo.CodecCapabilities avcCodec() {
        if (!codecLoaded) {
            codecLoaded = true;
            try {
                for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                    if (!info.isEncoder()) continue;
                    for (String type : info.getSupportedTypes()) {
                        if (type.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_AVC)) {
                            codec = info.getCapabilitiesForType(type);
                            break;
                        }
                    }
                    if (codec != null) break;
                }
            } catch (Exception ignored) { }
        }
        return codec;
    }

    static MediaCodecInfo.VideoCapabilities avcVideoCapabilities() {
        MediaCodecInfo.CodecCapabilities capabilities = avcCodec();
        return capabilities == null ? null : capabilities.getVideoCapabilities();
    }

    static MediaCodecInfo.EncoderCapabilities avcEncoderCapabilities() {
        MediaCodecInfo.CodecCapabilities capabilities = avcCodec();
        return capabilities == null ? null : capabilities.getEncoderCapabilities();
    }

    /** True when high-quality noise reduction / edge processing is available. */
    static boolean offersHighQuality(int[] modes) {
        if (modes == null) return false;
        for (int mode : modes) {
            if (mode == CameraCharacteristics.NOISE_REDUCTION_MODE_HIGH_QUALITY) return true;
        }
        return false;
    }

    static String aspectLabel(int width, int height) {
        if (height == 0) return "";
        double ratio = width / (double) height;
        if (Math.abs(ratio - 16.0 / 9.0) < 0.02) return "16:9";
        if (Math.abs(ratio - 18.0 / 9.0) < 0.02) return "18:9";
        if (Math.abs(ratio - 4.0 / 3.0) < 0.02) return "4:3";
        if (Math.abs(ratio - 3.0 / 2.0) < 0.02) return "3:2";
        if (Math.abs(ratio - 1.0) < 0.02) return "1:1";
        return String.format(Locale.US, "%.2f:1", ratio);
    }
}
