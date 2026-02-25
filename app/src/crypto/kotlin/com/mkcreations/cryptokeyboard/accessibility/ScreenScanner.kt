package com.mkcreations.cryptokeyboard.accessibility

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.mkcreations.cryptokeyboard.crypto.InvisibleEncoder

object ScreenScanner {

    private const val TAG = "ScreenScanner"

    data class EncryptedNode(
        val nodeId: String,
        val fullText: String,
        val bounds: Rect,
        val parentBounds: Rect?
    )

    /**
     * Scans the current screen for text nodes containing hidden encrypted data
     */
    fun findEncryptedNodes(rootNode: AccessibilityNodeInfo?): List<EncryptedNode> {
        if (rootNode == null) return emptyList()

        // Skip system UI
        val pkg = rootNode.packageName?.toString()
        if (pkg == "com.android.systemui" || pkg == "android") return emptyList()

        val results = mutableListOf<EncryptedNode>()
        scanNode(rootNode, results, 0)
        return results
    }

    private fun scanNode(
        node: AccessibilityNodeInfo,
        results: MutableList<EncryptedNode>,
        depth: Int
    ) {
        if (depth > 20) return
        if (results.size >= 15) return

        try {
            val text = node.text?.toString()
            if (!text.isNullOrEmpty() && text.length > 8) {
                if (InvisibleEncoder.containsHiddenData(text)) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)

                    if (bounds.width() > 0 && bounds.height() > 0) {
                        val parentBounds = getParentBounds(node)
                        val nodeId = generateNodeId(text, bounds)

                        results.add(
                            EncryptedNode(
                                nodeId = nodeId,
                                fullText = text,
                                bounds = bounds,
                                parentBounds = parentBounds
                            )
                        )
                    }
                }
            }

            // Also check content description
            val contentDesc = node.contentDescription?.toString()
            if (!contentDesc.isNullOrEmpty() && contentDesc.length > 8) {
                if (InvisibleEncoder.containsHiddenData(contentDesc)) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)

                    if (bounds.width() > 0 && bounds.height() > 0) {
                        val parentBounds = getParentBounds(node)
                        val nodeId = generateNodeId(contentDesc, bounds)

                        results.add(
                            EncryptedNode(
                                nodeId = nodeId,
                                fullText = contentDesc,
                                bounds = bounds,
                                parentBounds = parentBounds
                            )
                        )
                    }
                }
            }

            val childCount = node.childCount.coerceAtMost(30)
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    scanNode(child, results, depth + 1)
                } finally {
                    child.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error at depth $depth", e)
        }
    }

    private fun getParentBounds(node: AccessibilityNodeInfo): Rect? {
        return try {
            val parent = node.parent ?: return null
            val bounds = Rect()
            parent.getBoundsInScreen(bounds)
            parent.recycle()
            bounds
        } catch (e: Exception) {
            null
        }
    }

    private fun generateNodeId(text: String, bounds: Rect): String {
        val textHash = text.hashCode()
        return "${textHash}_${bounds.left}_${bounds.top}"
    }

    /**
     * Quick check if screen likely contains any encrypted content
     */
    fun quickCheck(rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false
        return quickCheckNode(rootNode, 0)
    }

    private fun quickCheckNode(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > 10) return false

        try {
            val text = node.text?.toString()
            if (!text.isNullOrEmpty() && text.length > 8) {
                if (InvisibleEncoder.containsHiddenData(text)) return true
            }

            val contentDesc = node.contentDescription?.toString()
            if (!contentDesc.isNullOrEmpty() && contentDesc.length > 8) {
                if (InvisibleEncoder.containsHiddenData(contentDesc)) return true
            }

            for (i in 0 until node.childCount.coerceAtMost(15)) {
                val child = node.getChild(i) ?: continue
                try {
                    if (quickCheckNode(child, depth + 1)) return true
                } finally {
                    child.recycle()
                }
            }
        } catch (e: Exception) {}

        return false
    }
}