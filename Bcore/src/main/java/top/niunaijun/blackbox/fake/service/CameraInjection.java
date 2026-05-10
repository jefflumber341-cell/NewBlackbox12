package top.niunaijun.blackbox.fake.service;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.utils.Slog;


/**
 * Holds the customized media (image OR video) used to override real camera
 * frames inside the virtualized environment. Plain Java singleton — matches
 * the existing pattern used elsewhere in {@code Bcore} (no DI framework is
 * used in the core layer).
 *
 * <p>For images we cache the decoded NV21 buffer keyed by (path, width,
 * height) so that we re-encode only when the path or the requested preview
 * size changes. For videos we use a {@link MediaMetadataRetriever} to pull
 * frames on demand (one frame per camera-frame request) keyed by the wall
 * clock so the video plays back in real time and loops automatically.
 *
 * <p>Both paths produce an NV21 byte buffer of exactly the requested
 * (width, height); from the call site's perspective video injection works
 * "the same way" as image injection.
 */
public final class CameraInjection {
    public static final String TAG = "CameraInjection";

    public static final int SOURCE_NONE = 0;
    public static final int SOURCE_IMAGE = 1;
    public static final int SOURCE_VIDEO = 2;

    private static final CameraInjection sInstance = new CameraInjection();

    private volatile String mSourcePath;
    private volatile int mSourceType = SOURCE_NONE;
    private volatile long mConfigLastModified;

    
    private String mCachedImagePath;
    private int mCachedImageWidth;
    private int mCachedImageHeight;
    private byte[] mCachedImageNv21;

    
    private String mCachedVideoPath;
    private MediaMetadataRetriever mVideoRetriever;
    private long mVideoDurationUs;
    private long mVideoStartUptimeMs;
    
    private String mCachedVideoFramePath;
    private int mCachedVideoFrameWidth;
    private int mCachedVideoFrameHeight;
    private long mCachedVideoFrameTimeUs = -1L;
    private byte[] mCachedVideoFrameNv21;

    private CameraInjection() {
    }

    public static CameraInjection get() {
        return sInstance;
    }

    
    public void setImagePath(String path) {
        setSourcePath(path);
    }

    public void setVideoPath(String path) {
        setSourcePath(path);
    }

    /**
     * Single entry point for both image and video paths. The source type is
     * inferred from the file extension so callers do not need to care.
     */
    public void setSourcePath(String path) {
        synchronized (this) {
            String trimmed = (path == null) ? null : path.trim();
            if (trimmed != null && trimmed.length() == 0) {
                trimmed = null;
            }

            int type = detectSourceType(trimmed);
            if (sameString(trimmed, mSourcePath) && type == mSourceType) {
                return;
            }
            mSourcePath = trimmed;
            mSourceType = type;
            invalidateCachesLocked();
            persistPath(trimmed);
        }
    }

    public String getImagePath() {
        return mSourceType == SOURCE_IMAGE ? mSourcePath : null;
    }

    public String getVideoPath() {
        return mSourceType == SOURCE_VIDEO ? mSourcePath : null;
    }

    public String getSourcePath() {
        return mSourcePath;
    }

    public int getSourceType() {
        return mSourceType;
    }

    public boolean isEnabled() {
        syncPathFromDiskIfNeeded();
        return mSourcePath != null && mSourceType != SOURCE_NONE;
    }

    /**
     * Returns an NV21 byte buffer of the customized media, sized exactly to
     * {@code width x height}. Returns {@code null} if injection is disabled
     * or the configured media cannot be decoded. For video sources, each
     * call advances along the wall clock so successive camera frames see
     * successive video frames; the video loops at end-of-stream.
     */
    public synchronized byte[] getNv21Frame(int width, int height) {
        syncPathFromDiskIfNeeded();
        final String path = mSourcePath;
        final int type = mSourceType;
        if (path == null || width <= 0 || height <= 0 || type == SOURCE_NONE) {
            return null;
        }
        try {
            if (type == SOURCE_VIDEO) {
                return getVideoNv21FrameLocked(path, width, height);
            }
            return getImageNv21FrameLocked(path, width, height);
        } catch (Throwable t) {
            Slog.d(TAG, "getNv21Frame failed: " + t.getMessage());
            return null;
        }
    }

    
    private byte[] getImageNv21FrameLocked(String path, int width, int height) {
        if (mCachedImageNv21 != null
                && path.equals(mCachedImagePath)
                && mCachedImageWidth == width
                && mCachedImageHeight == height) {
            return mCachedImageNv21;
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap src = BitmapFactory.decodeFile(path, opts);
        if (src == null) {
            Slog.d(TAG, "decodeFile returned null for: " + path);
            return null;
        }
        Bitmap scaled = (src.getWidth() == width && src.getHeight() == height)
                ? src
                : Bitmap.createScaledBitmap(src, width, height, true);

        byte[] nv21 = encodeNv21(scaled, width, height);

        if (scaled != src) {
            scaled.recycle();
        }
        src.recycle();

        mCachedImagePath = path;
        mCachedImageWidth = width;
        mCachedImageHeight = height;
        mCachedImageNv21 = nv21;
        return nv21;
    }

    
    private byte[] getVideoNv21FrameLocked(String path, int width, int height) {
        ensureVideoRetrieverLocked(path);
        if (mVideoRetriever == null) {
            return null;
        }

        long timeUs;
        if (mVideoDurationUs <= 0L) {
            timeUs = 0L;
        } else {
            long elapsedMs = SystemClock.uptimeMillis() - mVideoStartUptimeMs;
            if (elapsedMs < 0L) elapsedMs = 0L;
            timeUs = (elapsedMs * 1000L) % mVideoDurationUs;
        }

        
        long quantizedUs = (timeUs / 33333L) * 33333L;

        if (mCachedVideoFrameNv21 != null
                && path.equals(mCachedVideoFramePath)
                && mCachedVideoFrameWidth == width
                && mCachedVideoFrameHeight == height
                && mCachedVideoFrameTimeUs == quantizedUs) {
            return mCachedVideoFrameNv21;
        }

        Bitmap frame = mVideoRetriever.getFrameAtTime(quantizedUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        if (frame == null) {
            
            frame = mVideoRetriever.getFrameAtTime(0L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        }
        if (frame == null) {
            Slog.d(TAG, "video getFrameAtTime returned null for: " + path);
            return null;
        }

        Bitmap scaled = (frame.getWidth() == width && frame.getHeight() == height)
                ? frame
                : Bitmap.createScaledBitmap(frame, width, height, true);

        byte[] nv21 = encodeNv21(scaled, width, height);

        if (scaled != frame) {
            scaled.recycle();
        }
        frame.recycle();

        mCachedVideoFramePath = path;
        mCachedVideoFrameWidth = width;
        mCachedVideoFrameHeight = height;
        mCachedVideoFrameTimeUs = quantizedUs;
        mCachedVideoFrameNv21 = nv21;
        return nv21;
    }

    private void ensureVideoRetrieverLocked(String path) {
        if (mVideoRetriever != null && path.equals(mCachedVideoPath)) {
            return;
        }
        releaseVideoRetrieverLocked();
        try {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            mmr.setDataSource(path);
            String durStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durMs = 0L;
            if (durStr != null) {
                try { durMs = Long.parseLong(durStr); } catch (NumberFormatException ignored) {}
            }
            mVideoRetriever = mmr;
            mCachedVideoPath = path;
            mVideoDurationUs = durMs * 1000L;
            mVideoStartUptimeMs = SystemClock.uptimeMillis();
        } catch (Throwable t) {
            Slog.d(TAG, "ensureVideoRetriever failed: " + t.getMessage());
            releaseVideoRetrieverLocked();
        }
    }

    private void releaseVideoRetrieverLocked() {
        if (mVideoRetriever != null) {
            try { mVideoRetriever.release(); } catch (Throwable ignored) {}
            mVideoRetriever = null;
        }
        mCachedVideoPath = null;
        mVideoDurationUs = 0L;
        mVideoStartUptimeMs = 0L;
        mCachedVideoFramePath = null;
        mCachedVideoFrameNv21 = null;
        mCachedVideoFrameTimeUs = -1L;
        mCachedVideoFrameWidth = 0;
        mCachedVideoFrameHeight = 0;
    }

    private void invalidateCachesLocked() {
        mCachedImagePath = null;
        mCachedImageNv21 = null;
        mCachedImageWidth = 0;
        mCachedImageHeight = 0;
        releaseVideoRetrieverLocked();
    }

    private static int detectSourceType(String path) {
        if (path == null) return SOURCE_NONE;
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp4") || lower.endsWith(".m4v") || lower.endsWith(".mov")
                || lower.endsWith(".webm") || lower.endsWith(".mkv") || lower.endsWith(".3gp")
                || lower.endsWith(".avi") || lower.endsWith(".ts")) {
            return SOURCE_VIDEO;
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".bmp") || lower.endsWith(".heic")
                || lower.endsWith(".heif")) {
            return SOURCE_IMAGE;
        }
        
        return SOURCE_IMAGE;
    }

    private static boolean sameString(String a, String b) {
        if (a == null) return b == null;
        return a.equals(b);
    }

    private File getConfigFile() {
        if (BlackBoxCore.getContext() == null) return null;
        return new File(BlackBoxCore.getContext().getFilesDir(), "camera_injection_path.txt");
    }

    private void persistPath(String path) {
        try {
            File config = getConfigFile();
            if (config == null) return;
            if (path == null || path.length() == 0) {
                if (config.exists()) {
                    config.delete();
                }
            } else {
                FileOutputStream fos = new FileOutputStream(config, false);
                try {
                    fos.write(path.getBytes(StandardCharsets.UTF_8));
                } finally {
                    fos.close();
                }
            }
            mConfigLastModified = config.exists() ? config.lastModified() : 0L;
        } catch (Throwable t) {
            Slog.d(TAG, "persistPath failed: " + t.getMessage());
        }
    }

    private void syncPathFromDiskIfNeeded() {
        try {
            File config = getConfigFile();
            if (config == null) return;
            long lm = config.exists() ? config.lastModified() : 0L;
            if (lm == mConfigLastModified) return;
            String path = null;
            if (lm > 0L) {
                FileInputStream fis = new FileInputStream(config);
                byte[] raw;
                try {
                    raw = new byte[(int) config.length()];
                    int read = fis.read(raw);
                    if (read <= 0) {
                        raw = new byte[0];
                    } else if (read < raw.length) {
                        byte[] exact = new byte[read];
                        System.arraycopy(raw, 0, exact, 0, read);
                        raw = exact;
                    }
                } finally {
                    fis.close();
                }
                String value = new String(raw, StandardCharsets.UTF_8).trim();
                if (value.length() > 0) {
                    path = value;
                }
            }
            synchronized (this) {
                int type = detectSourceType(path);
                if (!sameString(path, mSourcePath) || type != mSourceType) {
                    mSourcePath = path;
                    mSourceType = type;
                    invalidateCachesLocked();
                }
                mConfigLastModified = lm;
            }
        } catch (Throwable t) {
            Slog.d(TAG, "syncPathFromDiskIfNeeded failed: " + t.getMessage());
        }
    }

    
    public synchronized void fillNv21(byte[] dst, int width, int height) {
        byte[] src = getNv21Frame(width, height);
        if (src == null || dst == null) {
            return;
        }
        int len = Math.min(src.length, dst.length);
        System.arraycopy(src, 0, dst, 0, len);
    }

    /**
     * Bitmap (ARGB_8888) -> NV21 (YYYY...VU VU...). Compact, branch-light
     * implementation that runs once per cache miss.
     */
    private static byte[] encodeNv21(Bitmap bitmap, int width, int height) {
        int[] argb = new int[width * height];
        bitmap.getPixels(argb, 0, width, 0, 0, width, height);

        final int frameSize = width * height;
        byte[] nv21 = new byte[frameSize + (frameSize / 2)];

        int yIndex = 0;
        int uvIndex = frameSize;

        int idx = 0;
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int color = argb[idx++];
                int r = (color >> 16) & 0xFF;
                int g = (color >> 8) & 0xFF;
                int b = color & 0xFF;

                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                if (y < 0) y = 0; else if (y > 255) y = 255;
                nv21[yIndex++] = (byte) y;

                if ((j & 1) == 0 && (i & 1) == 0) {
                    int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    if (u < 0) u = 0; else if (u > 255) u = 255;
                    if (v < 0) v = 0; else if (v > 255) v = 255;
                    nv21[uvIndex++] = (byte) v;
                    nv21[uvIndex++] = (byte) u;
                }
            }
        }
        return nv21;
    }
}
