package android.app;

import android.content.ComponentName;

/**
 * Compile-time stub for the hidden framework class.
 *
 * Methods included here are the callbacks OpenCyvis overrides. On device the
 * boot classpath provides the real android.app.TaskStackListener.
 */
public class TaskStackListener extends ITaskStackListener.Stub {
    public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo) {
    }

    public void onTaskDisplayChanged(int taskId, int newDisplayId) {
    }

    public void onTaskCreated(int taskId, ComponentName componentName) {
    }

    public void onTaskRemoved(int taskId) {
    }
}
