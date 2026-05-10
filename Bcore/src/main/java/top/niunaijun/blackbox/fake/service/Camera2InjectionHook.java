package top.niunaijun.blackbox.fake.service;

import android.app.Application;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import top.niunaijun.blackbox.utils.Slog;


/**
 * Runtime installer for the Camera2 path. Mirrors {@link CameraInjectionHook}.
 *
 * <p>The Camera2 API delivers frames into application-supplied {@link Surface}s,
 * not into a {@code byte[]} callback. {@link Camera2Injection} already knows
 * how to render the configured image/video into a {@link Surface}; this class
 * simply registers Surfaces (typically captured at
 * {@code CameraDevice.createCaptureSession} time) and runs a low-frequency
 * background renderer that pushes the current frame into them while
 * {@link CameraInjection#isEnabled()} reports {@code true}.
 *
 * <p>Style: plain Java singleton; no DI; no Compose; no ViewModel.
 */
public final class Camera2InjectionHook {
    public static final String TAG = "Camera2InjectionHook";

    private static final long FRAME_INTERVAL_MS = 33L; // ~30 fps

    private static final Camera2InjectionHook sInstance = new Camera2InjectionHook();

    private final List<Surface> mTargets = new ArrayList<Surface>();

    private HandlerThread mThread;
    private Handler mHandler;
    private volatile boolean mRunning;
    private volatile boolean mInstalled;

    private Camera2InjectionHook() {
    }

    public static Camera2InjectionHook get() {
        return sInstance;
    }

    /**
     * Install the renderer thread once per cloned-app process. Idempotent.
     */
    public synchronized void installInto(Application app) {
        if (mInstalled) {
            return;
        }
        try {
            mThread = new HandlerThread("Camera2InjectionRenderer");
            mThread.start();
            mHandler = new Handler(mThread.getLooper());
            mInstalled = true;
        } catch (Throwable t) {
            Slog.d(TAG, "installInto failed: " + t.getMessage());
        }
    }

    /**
     * Register an output {@link Surface} to be fed with injected frames. Safe
     * to call from any thread; duplicate registration is ignored. Surfaces
     * automatically drop out of the rotation when they become invalid.
     */
    public void registerTarget(Surface surface) {
        if (surface == null || !surface.isValid()) return;
        synchronized (mTargets) {
            for (Surface s : mTargets) {
                if (s == surface) return;
            }
            mTargets.add(surface);
        }
        startIfNeeded();
    }

    public void unregisterTarget(Surface surface) {
        synchronized (mTargets) {
            mTargets.remove(surface);
            if (mTargets.isEmpty()) {
                mRunning = false;
            }
        }
    }

    private void startIfNeeded() {
        if (!mInstalled || mHandler == null) return;
        if (mRunning) return;
        mRunning = true;
        mHandler.post(mTick);
    }

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            if (!mRunning) return;
            try {
                if (CameraInjection.get().isEnabled()) {
                    drawCurrentFrameToTargets();
                }
            } catch (Throwable t) {
                Slog.d(TAG, "tick failed: " + t.getMessage());
            }
            if (mHandler != null && mRunning) {
                mHandler.postDelayed(this, FRAME_INTERVAL_MS);
            }
        }
    };

    private void drawCurrentFrameToTargets() {
        List<Surface> snapshot;
        synchronized (mTargets) {
            if (mTargets.isEmpty()) {
                mRunning = false;
                return;
            }
            snapshot = new ArrayList<Surface>(mTargets);
        }
        for (Iterator<Surface> it = snapshot.iterator(); it.hasNext(); ) {
            Surface s = it.next();
            if (s == null || !s.isValid()) {
                synchronized (mTargets) {
                    mTargets.remove(s);
                }
                continue;
            }
            int w = 1280, h = 720;
            try {
                Camera2Injection.get().renderCurrentFrameInto(s, w, h);
            } catch (Throwable ignored) {
                // Drop offending surface; keep going.
                synchronized (mTargets) {
                    mTargets.remove(s);
                }
            }
        }
    }
}
