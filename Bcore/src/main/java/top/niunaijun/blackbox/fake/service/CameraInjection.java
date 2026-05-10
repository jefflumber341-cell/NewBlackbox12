package top.niunaijun.blackbox.fake.service;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.utils.Slog;


/**
 * Holds the customized image used to override real camera frames inside the
 * virtualized environment. Plain Java singleton — matches the existing pattern
 * used elsewhere in {@code Bcore} (no DI framework is used in the core layer).
 *
 * <p>Caches the decoded bitmap and the corresponding NV21 byte buffer keyed by
 * (path, width, height) so that we re-encode only when the path or the
 * requested preview size changes. The buffer is reused on every frame which
 * keeps the camera thread allocation-free.
 */
public final class CameraInjection {
    public static final String TAG = "CameraInjection";

    private static final CameraInjection sInstance = new CameraInjection();

    private volatile String mImagePath;
    private volatile long mConfigLastModified;

    
    private String mCachedPath;
    private int mCachedWidth;
    private int mCachedHeight;
    private byte[] mCachedNv21;

    private CameraInjection() {
    }

    public static CameraInjection get() {
        return sInstance;
    }

    
    public void setImagePath(String path) {
        synchronized (this) {
            if ((path == null && mImagePath == null)
                    || (path != null && path.equals(mImagePath))) {
                return;
            }
            mImagePath = path;
            
            mCachedPath = null;
            mCachedNv21 = null;
            mCachedWidth = 0;
            mCachedHeight = 0;
            persistPath(path);
        }
    }

    public String getImagePath() {
        return mImagePath;
    }

    public boolean isEnabled() {
        syncPathFromDiskIfNeeded();
        return mImagePath != null;
    }

    /**
     * Returns an NV21 byte buffer of the customized image, sized exactly to
     * {@code width x height}. Returns {@code null} if injection is disabled or
     * the configured image cannot be decoded.
     */
    public synchronized byte[] getNv21Frame(int width, int height) {
        syncPathFromDiskIfNeeded();
        final String path = mImagePath;
        if (path == null || width <= 0 || height <= 0) {
            return null;
        }
        if (mCachedNv21 != null
                && path.equals(mCachedPath)
                && mCachedWidth == width
                && mCachedHeight == height) {
            return mCachedNv21;
        }

        try {
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

            mCachedPath = path;
            mCachedWidth = width;
            mCachedHeight = height;
            mCachedNv21 = nv21;
            return nv21;
        } catch (Throwable t) {
            Slog.d(TAG, "getNv21Frame failed: " + t.getMessage());
            return null;
        }
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
                if ((path == null && mImagePath != null) || (path != null && !path.equals(mImagePath))) {
                    mImagePath = path;
                    mCachedPath = null;
                    mCachedNv21 = null;
                    mCachedWidth = 0;
                    mCachedHeight = 0;
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
