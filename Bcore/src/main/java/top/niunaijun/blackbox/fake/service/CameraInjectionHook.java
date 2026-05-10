package top.niunaijun.blackbox.fake.service;

import android.app.Activity;
import android.app.Application;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import top.niunaijun.blackbox.utils.Slog;


/**
 * Runtime installer that actually wires {@link CameraInjection}'s NV21 buffer
 * into every live {@link android.hardware.Camera} (Camera1) instance inside a
 * cloned app.
 *
 * <p>Why this exists: {@link CameraProxy}'s {@code @ProxyMethod} map is dead
 * for {@code Camera} because the real {@code setPreviewCallback*} entry-points
 * are routed straight to native code without going through any {@link
 * java.lang.reflect.InvocationHandler}. The framework's existing hook layer
 * therefore cannot intercept a preview frame.
 *
 * <p>However the JNI layer of {@code Camera} dispatches every preview frame
 * back into Java via the (package-private) {@code mEventHandler} field — a
 * plain {@link Handler} that delivers {@code CAMERA_MSG_PREVIEW_FRAME}
 * (msg.what == 16). By installing a {@link Handler.Callback} on that handler
 * we can rewrite the {@code byte[]} payload with the NV21 buffer produced by
 * {@link CameraInjection#getNv21Frame(int, int)} <em>before</em> the app's
 * registered preview callback runs — which transparently covers
 * {@code setPreviewCallback}, {@code setPreviewCallbackWithBuffer} and
 * {@code setOneShotPreviewCallback}.
 *
 * <p>Style: plain Java singleton, mirrors the rest of {@code Bcore}. No DI,
 * no Compose, no ViewModel.
 */
public final class CameraInjectionHook {
    public static final String TAG = "CameraInjectionHook";

    /** {@code Camera.CAMERA_MSG_PREVIEW_FRAME} — frozen since API 1. */
    private static final int CAMERA_MSG_PREVIEW_FRAME = 0x10;

    private static final CameraInjectionHook sInstance = new CameraInjectionHook();

    private final Map<Camera, Boolean> mPatched =
            Collections.synchronizedMap(new WeakHashMap<Camera, Boolean>());

    private volatile boolean mInstalled;

    private CameraInjectionHook() {
    }

    public static CameraInjectionHook get() {
        return sInstance;
    }

    /**
     * Install the lifecycle-driven scanner once per cloned-app process. Safe
     * to call multiple times; subsequent calls are no-ops.
     */
    public synchronized void installInto(Application app) {
        if (mInstalled || app == null) {
            return;
        }
        try {
            app.registerActivityLifecycleCallbacks(new LifecycleScanner());
            mInstalled = true;
        } catch (Throwable t) {
            Slog.d(TAG, "installInto failed: " + t.getMessage());
        }
    }

    /**
     * Public reflective entry-point: wires the given {@link Camera} instance
     * to deliver injected frames. Idempotent — patching an already-patched
     * instance is a no-op. Failures are swallowed (best-effort hook).
     */
    public void attach(Camera camera) {
        if (camera == null) return;
        if (Boolean.TRUE.equals(mPatched.get(camera))) {
            return;
        }
        try {
            if (patchEventHandler(camera)) {
                mPatched.put(camera, Boolean.TRUE);
            }
        } catch (Throwable t) {
            Slog.d(TAG, "attach failed: " + t.getMessage());
        }
    }

    /**
     * Walks across the public {@code android.hardware.Camera} API surface to
     * find any cached references to live cameras and patch each. This is a
     * best-effort scan; OEMs that ship custom Camera1 layers may need to
     * expose their instances another way.
     */
    public void scanAndAttachAll() {
        if (!CameraInjection.get().isEnabled()) {
            return;
        }
        try {
            // Many OEM ROMs cache live cameras in a static field on Camera. We
            // peek at all object-typed static fields and snag any Camera /
            // Map-of-Camera contents we recognise.
            Field[] fields = Camera.class.getDeclaredFields();
            for (Field f : fields) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object value = f.get(null);
                    if (value instanceof Camera) {
                        attach((Camera) value);
                    } else if (value instanceof Map) {
                        for (Object v : ((Map<?, ?>) value).values()) {
                            if (v instanceof Camera) {
                                attach((Camera) v);
                            }
                        }
                    } else if (value instanceof Iterable) {
                        for (Object v : (Iterable<?>) value) {
                            if (v instanceof Camera) {
                                attach((Camera) v);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Slog.d(TAG, "scanAndAttachAll failed: " + t.getMessage());
        }
    }

    private boolean patchEventHandler(Camera camera) throws Exception {
        Field handlerField;
        try {
            handlerField = Camera.class.getDeclaredField("mEventHandler");
        } catch (NoSuchFieldException nsfe) {
            // Some OEMs rename the field; fall back to scanning by type.
            handlerField = null;
            for (Field f : Camera.class.getDeclaredFields()) {
                if (Handler.class.isAssignableFrom(f.getType())) {
                    handlerField = f;
                    break;
                }
            }
            if (handlerField == null) {
                Slog.d(TAG, "no Handler field on Camera; cannot patch");
                return false;
            }
        }
        handlerField.setAccessible(true);
        Object current = handlerField.get(camera);
        if (current instanceof InjectingHandler) {
            return true; // already patched
        }
        if (!(current instanceof Handler)) {
            // The Camera instance hasn't installed its EventHandler yet; install one.
            Handler fresh = new InjectingHandler(camera, Looper.myLooper() != null
                    ? Looper.myLooper() : Looper.getMainLooper(), null);
            handlerField.set(camera, fresh);
            return true;
        }
        Handler original = (Handler) current;
        Handler wrapped = new InjectingHandler(camera, original.getLooper(), original);
        handlerField.set(camera, wrapped);
        return true;
    }

    /**
     * {@link Handler} subclass that rewrites preview-frame messages in flight.
     * It cannot extend the original handler's class (we don't know it at
     * compile time), so it re-dispatches to the original handler via {@link
     * Handler#dispatchMessage(Message)} after we've rewritten {@code msg.obj}.
     */
    private static final class InjectingHandler extends Handler {
        private final Camera mCamera;
        private final Handler mOriginal;

        InjectingHandler(Camera camera, Looper looper, Handler original) {
            super(looper);
            mCamera = camera;
            mOriginal = original;
        }

        @Override
        public void dispatchMessage(Message msg) {
            try {
                if (msg.what == CAMERA_MSG_PREVIEW_FRAME
                        && CameraInjection.get().isEnabled()) {
                    int[] size = previewSize(mCamera);
                    int w = size[0];
                    int h = size[1];
                    if (msg.obj instanceof byte[]) {
                        byte[] data = (byte[]) msg.obj;
                        CameraInjection.get().fillNv21(data, w, h);
                    } else if (msg.obj == null) {
                        byte[] frame = CameraInjection.get().getNv21Frame(w, h);
                        if (frame != null) {
                            msg.obj = frame;
                        }
                    }
                }
            } catch (Throwable t) {
                Slog.d(TAG, "rewrite preview frame failed: " + t.getMessage());
            }
            if (mOriginal != null) {
                mOriginal.dispatchMessage(msg);
            } else {
                super.dispatchMessage(msg);
            }
        }
    }

    private static int[] previewSize(Camera c) {
        int w = 640, h = 480;
        try {
            Camera.Parameters p = c.getParameters();
            if (p != null && p.getPreviewSize() != null) {
                w = p.getPreviewSize().width;
                h = p.getPreviewSize().height;
            }
        } catch (Throwable ignored) {
        }
        return new int[]{w, h};
    }

    /**
     * Cheap lifecycle-driven scanner: runs a Camera-instance scan whenever
     * any Activity transitions, which is when apps typically open or reuse
     * the camera. Avoids spawning a dedicated thread.
     */
    private final class LifecycleScanner implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityCreated(Activity a, Bundle b) { scanAndAttachAll(); }
        @Override public void onActivityStarted(Activity a)            { scanAndAttachAll(); }
        @Override public void onActivityResumed(Activity a)            { scanAndAttachAll(); }
        @Override public void onActivityPaused(Activity a)             { /* no-op */ }
        @Override public void onActivityStopped(Activity a)            { /* no-op */ }
        @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { /* no-op */ }
        @Override public void onActivityDestroyed(Activity a)          { /* no-op */ }
    }
}
