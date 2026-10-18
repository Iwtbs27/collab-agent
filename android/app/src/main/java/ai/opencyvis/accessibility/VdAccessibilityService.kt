package ai.opencyvis.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class VdAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "VdA11yService"
        private const val MAX_DEPTH = 15
        private const val MAX_NODES = 200

        @Volatile
        var instance: VdAccessibilityService? = null
            private set

        fun captureViewTree(displayId: Int, displayWidth: Int, displayHeight: Int): String? {
            return instance?.captureViewTreeInternal(displayId, displayWidth, displayHeight)
        }

        fun getTopPackageOnDisplay(displayId: Int): String? {
            val service = instance ?: return null
            return try {
                val windowList = if (displayId == 0) {
                    service.windows
                } else {
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val allWindows = service.javaClass.getMethod("getWindowsOnAllDisplays")
                            .invoke(service) as? android.util.SparseArray<List<android.view.accessibility.AccessibilityWindowInfo>>
                        allWindows?.get(displayId)
                    } catch (e: Exception) {
                        service.windows
                    }
                }
                windowList?.firstNotNullOfOrNull { window ->
                    val root = window.getRoot(0) ?: return@firstNotNullOfOrNull null
                    val pkg = root.packageName?.toString()
                    root.recycle()
                    pkg?.takeIf { it != "com.android.systemui" }
                }
            } catch (e: Exception) {
                Log.w(TAG, "getTopPackageOnDisplay failed", e)
                null
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo.apply {
            flags = flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        Log.i(TAG, "AccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We only use on-demand tree queries, not event-driven
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    private fun captureViewTreeInternal(
        displayId: Int, displayWidth: Int, displayHeight: Int
    ): String? {
        return try {
            val windowsMap = windows?.takeIf { displayId == 0 }
                ?: try {
                    @Suppress("UNCHECKED_CAST")
                    val allWindows = javaClass.getMethod("getWindowsOnAllDisplays").invoke(this)
                        as? android.util.SparseArray<List<android.view.accessibility.AccessibilityWindowInfo>>
                    allWindows?.get(displayId)
                } catch (e: Exception) {
                    Log.w(TAG, "getWindowsOnAllDisplays failed, falling back to getWindows()", e)
                    windows
                }

            if (windowsMap.isNullOrEmpty()) {
                Log.w(TAG, "No windows found for displayId=$displayId")
                return null
            }

            val sb = StringBuilder()
            var nodeCount = 0

            for (window in windowsMap) {
                val root = window.getRoot(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID)
                    ?: continue

                val packageName = root.packageName?.toString() ?: ""
                if (packageName == "com.android.systemui") {
                    root.recycle()
                    continue
                }

                traverseNode(root, 0, sb, displayWidth, displayHeight,
                    nodeCount = { nodeCount }, incCount = { nodeCount++ })
                root.recycle()
            }

            if (sb.isEmpty()) null else sb.toString().trimEnd()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture view tree", e)
            null
        }
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        depth: Int,
        sb: StringBuilder,
        displayWidth: Int,
        displayHeight: Int,
        nodeCount: () -> Int,
        incCount: () -> Unit,
    ) {
        if (depth > MAX_DEPTH || nodeCount() >= MAX_NODES) return
        if (!node.isVisibleToUser) return

        val text = node.text?.toString()?.take(50)
        val desc = node.contentDescription?.toString()?.take(50)
        val className = node.className?.toString()?.substringAfterLast('.') ?: ""

        val isClickable = node.isClickable
        val isEditable = node.isEditable
        val isScrollable = node.isScrollable
        val isCheckable = node.isCheckable
        val isChecked = node.isChecked

        val hasContent = !text.isNullOrBlank() || !desc.isNullOrBlank()
        val isInteractive = isClickable || isEditable || isScrollable || isCheckable

        // Skip leaf nodes with no content and no interactivity
        val childCount = node.childCount
        if (!hasContent && !isInteractive && childCount == 0) return

        incCount()

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        // Normalize to 0-1000 range
        val nx1 = (bounds.left * 1000 / displayWidth).coerceIn(0, 1000)
        val ny1 = (bounds.top * 1000 / displayHeight).coerceIn(0, 1000)
        val nx2 = (bounds.right * 1000 / displayWidth).coerceIn(0, 1000)
        val ny2 = (bounds.bottom * 1000 / displayHeight).coerceIn(0, 1000)

        // Only emit line if this node has content or is interactive
        if (hasContent || isInteractive) {
            val indent = "  ".repeat(depth)

            sb.append(indent).append(className)

            if (!text.isNullOrBlank()) sb.append(" \"$text\"")
            if (!desc.isNullOrBlank() && desc != text) sb.append(" ($desc)")

            val attrs = mutableListOf<String>()
            if (isEditable) attrs.add("editable")
            if (isScrollable) attrs.add("scrollable")
            if (isCheckable) attrs.add(if (isChecked) "checked" else "unchecked")
            if (isClickable && !isEditable) attrs.add("clickable")
            if (attrs.isNotEmpty()) sb.append(" ").append(attrs.joinToString(","))

            sb.append(" ($nx1,$ny1)-($nx2,$ny2)")
            sb.append('\n')
        }

        // Recurse children
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, depth + 1, sb, displayWidth, displayHeight,
                nodeCount, incCount)
            child.recycle()
        }
    }
}
