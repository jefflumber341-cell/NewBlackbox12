package top.niunaijun.blackbox.fake.service;

import android.hardware.Camera;

import java.lang.reflect.Method;

import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;


/**
 * Hooks the legacy {@link android.hardware.Camera} (Camera1) API so that any
 * cloned app receives the customized image (held by {@link CameraInjection})
 * instead of real camera frames in its preview callback.
 *
 * <p>We do NOT touch the open / startPreview path — the system camera is still
 * opened normally. We only swap the {@link Camera.PreviewCallback} the app
 * registers, so every frame it receives carries our injected NV21 buffer.
 *
 * <p>Style note: matches the existing pattern used by {@link MediaRecorderProxy}
 * (no DI, no ViewModel — this is a low-level reflective hook).
 */
public class CameraProxy extends ClassInvocationStub {
    public static final String TAG = "CameraProxy";

    public CameraProxy() {
        super();
    }

    @Override
    protected Object getWho() {
        return null; 
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    
    private static int getPreviewWidth(Camera camera) {
        try {
            Camera.Parameters p = camera.getParameters();
            if (p != null && p.getPreviewSize() != null) {
                return p.getPreviewSize().width;
            }
        } catch (Throwable ignored) {
        }
        return 640;
    }

    private static int getPreviewHeight(Camera camera) {
        try {
            Camera.Parameters p = camera.getParameters();
            if (p != null && p.getPreviewSize() != null) {
                return p.getPreviewSize().height;
            }
        } catch (Throwable ignored) {
        }
        return 480;
    }

    
    private static class InjectingPreviewCallback implements Camera.PreviewCallback {
        private final Camera.PreviewCallback mDelegate;

        InjectingPreviewCallback(Camera.PreviewCallback delegate) {
            this.mDelegate = delegate;
        }

        @Override
        public void onPreviewFrame(byte[] data, Camera camera) {
            try {
                if (CameraInjection.get().isEnabled()) {
                    int w = getPreviewWidth(camera);
                    int h = getPreviewHeight(camera);
                    if (data != null) {
                        
                        CameraInjection.get().fillNv21(data, w, h);
                    } else {
                        byte[] frame = CameraInjection.get().getNv21Frame(w, h);
                        if (frame != null) {
                            data = frame;
                        }
                    }
                }
            } catch (Throwable t) {
                Slog.d(TAG, "frame injection failed: " + t.getMessage());
            }
            if (mDelegate != null) {
                mDelegate.onPreviewFrame(data, camera);
            }
        }
    }

    
    private static Object[] wrapCallbackArg(Object[] args) {
        if (args == null) {
            return null;
        }
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a instanceof Camera.PreviewCallback
                    && !(a instanceof InjectingPreviewCallback)) {
                args[i] = new InjectingPreviewCallback((Camera.PreviewCallback) a);
            }
        }
        return args;
    }

    
    @ProxyMethod("setPreviewCallback")
    public static class SetPreviewCallback extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                wrapCallbackArg(args);
            } catch (Throwable t) {
                Slog.d(TAG, "setPreviewCallback wrap failed: " + t.getMessage());
            }
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("setPreviewCallbackWithBuffer")
    public static class SetPreviewCallbackWithBuffer extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                wrapCallbackArg(args);
            } catch (Throwable t) {
                Slog.d(TAG, "setPreviewCallbackWithBuffer wrap failed: " + t.getMessage());
            }
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("setOneShotPreviewCallback")
    public static class SetOneShotPreviewCallback extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                wrapCallbackArg(args);
            } catch (Throwable t) {
                Slog.d(TAG, "setOneShotPreviewCallback wrap failed: " + t.getMessage());
            }
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("addCallbackBuffer")
    public static class AddCallbackBuffer extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            return method.invoke(who, args);
        }
    }
}