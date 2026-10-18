package android.app;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;

/**
 * Compile-time stub for the hidden framework interface.
 *
 * The app is platform-signed and resolves the real framework class at runtime.
 */
public interface ITaskStackListener extends IInterface {
    abstract class Stub extends Binder implements ITaskStackListener {
        @Override
        public IBinder asBinder() {
            return this;
        }
    }
}
