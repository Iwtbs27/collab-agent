package ai.opencyvis.backend;

import android.os.SharedMemory;
import android.view.Surface;

interface IPrivilegedService {
    int getServiceUid();

    boolean injectMotionEvent(in byte[] parceledEvent, int displayId, int mode);
    boolean injectKeyEvent(int action, int keyCode, int repeat, int metaState, int displayId, int mode);

    int createVirtualDisplay(String name, int width, int height, int dpi, int flags);
    void releaseVirtualDisplay();
    void setVirtualDisplaySurface(in Surface surface);

    SharedMemory captureScreen(int displayId, int maxWidth, int quality);

    int getTopTaskIdOnDisplay(int displayId, String callerPackage);
    boolean moveTaskToDisplay(int taskId, int targetDisplayId);

    void setDisplayImePolicy(int displayId, int policy);

    int probeCapabilities();

    void destroy();

    boolean startActivityOnDisplay(String intentUri, int displayId);

    /** Launch home/launcher on a display to ensure something renders (recovery for empty VD). */
    void ensureVdHasContent(int displayId);

    /** Force-stop a package (shell uid). Used to dismiss split-screen by killing the
        adjacent Settings pane after ADB pairing completes. */
    void forceStopPackage(String packageName);
}
