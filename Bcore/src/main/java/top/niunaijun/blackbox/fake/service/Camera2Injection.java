package top.niunaijun.blackbox.fake.service;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.MediaMetadataRetriever;
import android.os.SystemClock;
import android.view.Surface;

import top.niunaijun.blackbox.utils.Slog;


/**
 * Camera2 / CameraX side helper for image+video injection. {@link
 * CameraInjection} produces NV21 byte buffers that fit the Camera1
 * {@code PreviewCallback} contract; the Camera2 stack on the other hand
 * delivers frames into one or more {@link Surface}s that the application
 * configures on a {@code CameraCaptureSession}. This class renders the
 * currently configured injection source straight into such a {@link
 * Surface} as RGBA via {@link Surface#lockCanvas(Rect)}.
 *
 * <p>It is intentionally side-effect free at construction time: importing
 * the class never touches the camera. It is meant to be invoked from a
 * Camera2-aware hook surface (e.g. a {@code CameraManager.openCamera}
 * wrapper that swaps the application's preview {@code Surface} for one
 * we feed). The settings UI / {@link CameraInjection} singleton is the
 * single source of truth for "what should be injected"; this class only
 * reads from it.
 *
 * <p>Style: matches the rest of {@code Bcore} — plain Java singleton, no
 * DI framework, lazy initialisation.
 */
public final class Camera2Injection {
    public static final String TAG = "Camera2Injection";

    private static final Camera2Injection sInstance = new Camera2Injection();

    private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

    
    private String mCachedImagePath;
    private Bitmap mCachedImage;

    
    private String mCachedVideoPath;
    private MediaMetadataRetriever mVideoRetriever;
    private long mVideoDurationUs;
    private long mVideoStartUptimeMs;

    private Camera2Injection() {
    }

    public static Camera2Injection get() {
        return sInstance;
    }

    /** @return whether anything is currently configured to inject. */
    public boolean isEnabled() {
        return CameraInjection.get().isEnabled();
    }

    /**
     * Render a single frame of the currently configured injection source
     * into {@code surface}, scaled to fill {@code width x height}. Safe to
     * call from any thread; failures are swallowed and logged.
     *
     * @return {@code true} if a frame was successfully drawn, {@code false}
     *         if injection is disabled or the surface is unusable.
     */
    public boolean renderCurrentFrameInto(Surface surface, int width, int height) {
        if (surface == null || !surface.isValid() || width <= 0 || height <= 0) {
            return false;
        }
        Bitmap frame = obtainCurrentBitmap(width, height);
        if (frame == null) {
            return false;
        }
        Canvas canvas = null;
        try {
            canvas = surface.lockCanvas(null);
            if (canvas == null) {
                return false;
            }
            Rect dst = new Rect(0, 0, canvas.getWidth(), canvas.getHeight());
            canvas.drawBitmap(frame, null, dst, mPaint);
            return true;
        } catch (Throwable t) {
            Slog.d(TAG, "renderCurrentFrameInto failed: " + t.getMessage());
            return false;
        } finally {
            if (canvas != null) {
                try { surface.unlockCanvasAndPost(canvas); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Returns the current injection bitmap, freshly decoded if necessary.
     * The returned bitmap is owned by this singleton; callers must not
     * recycle it. Returns {@code null} when injection is disabled or the
     * source cannot be decoded.
     */
    public synchronized Bitmap obtainCurrentBitmap(int width, int height) {
        CameraInjection ci = CameraInjection.get();
        if (!ci.isEnabled()) {
            return null;
        }
        final String path = ci.getSourcePath();
        if (path == null) return null;

        try {
            if (ci.getSourceType() == CameraInjection.SOURCE_VIDEO) {
                return obtainVideoBitmapLocked(path);
            }
            return obtainImageBitmapLocked(path);
        } catch (Throwable t) {
            Slog.d(TAG, "obtainCurrentBitmap failed: " + t.getMessage());
            return null;
        }
    }

    private Bitmap obtainImageBitmapLocked(String path) {
        if (mCachedImage != null && !mCachedImage.isRecycled() && path.equals(mCachedImagePath)) {
            return mCachedImage;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded = BitmapFactory.decodeFile(path, opts);
        if (decoded == null) {
            return null;
        }
        if (mCachedImage != null && !mCachedImage.isRecycled()) {
            mCachedImage.recycle();
        }
        mCachedImage = decoded;
        mCachedImagePath = path;
        
        releaseVideoLocked();
        return mCachedImage;
    }

    private Bitmap obtainVideoBitmapLocked(String path) {
        ensureVideoLocked(path);
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
        Bitmap frame = mVideoRetriever.getFrameAtTime(quantizedUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        if (frame == null) {
            frame = mVideoRetriever.getFrameAtTime(0L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        }
        return frame;
    }

    private void ensureVideoLocked(String path) {
        if (mVideoRetriever != null && path.equals(mCachedVideoPath)) {
            return;
        }
        releaseVideoLocked();
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
            
            if (mCachedImage != null && !mCachedImage.isRecycled()) {
                mCachedImage.recycle();
            }
            mCachedImage = null;
            mCachedImagePath = null;
        } catch (Throwable t) {
            Slog.d(TAG, "ensureVideo failed: " + t.getMessage());
            releaseVideoLocked();
        }
    }

    private void releaseVideoLocked() {
        if (mVideoRetriever != null) {
            try { mVideoRetriever.release(); } catch (Throwable ignored) {}
            mVideoRetriever = null;
        }
        mCachedVideoPath = null;
        mVideoDurationUs = 0L;
        mVideoStartUptimeMs = 0L;
    }
}
