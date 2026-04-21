package com.mindmap.plugin.ui

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.mindmap.plugin.DebugLog
import com.mindmap.plugin.analysis.CallableFunction
import com.mindmap.plugin.analysis.GraphData

/**
 * Browser-like back/forward navigation stack for call graph views.
 *
 * # Language-neutral
 *
 * This class holds only [GraphData] (primitive strings/numbers + the `@Transient`
 * [CallableFunction] handle on each node). It does not import any specific language's
 * PSI type — all traversal goes through the [CallableFunction] abstraction so new
 * languages work without touching this file.
 *
 * [reset] starts a fresh history (used when Alt+G opens a new root function).
 * [push] adds a new entry and discards any forward history (used by expand/re-center).
 * [updateCurrent] replaces the current entry in-place (used when a trace merges new
 * nodes into the visible graph without creating a new history step).
 *
 * All methods must be called on the EDT.
 */
class GraphHistory(private val maxSize: Int = 50) {

    companion object {
        private val LOG = Logger.getInstance(GraphHistory::class.java)
    }

    private val entries = mutableListOf<GraphData>()
    private var index = -1

    val current: GraphData? get() = if (index in entries.indices) entries[index] else null
    val canBack: Boolean get() = index > 0
    val canForward: Boolean get() = index < entries.size - 1
    val position: Int get() = index + 1
    val total: Int get() = entries.size

    fun reset(data: GraphData) {
        entries.clear()
        entries.add(data)
        index = 0
    }

    /** Discards forward history, then appends [data]. Evicts oldest entry if over [maxSize]. */
    fun push(data: GraphData) {
        if (index < entries.size - 1) {
            while (entries.size > index + 1) {
                entries.removeAt(entries.size - 1)
            }
        }
        if (entries.size >= maxSize) {
            entries.removeAt(0)
            index = (index - 1).coerceAtLeast(0)
        }
        entries.add(data)
        index = entries.size - 1
    }

    fun back(): GraphData? {
        if (!canBack) return null
        index--
        return entries[index]
    }

    fun forward(): GraphData? {
        if (!canForward) return null
        index++
        return entries[index]
    }

    /** Replaces the current entry in-place (used when trace merges new nodes). */
    fun updateCurrent(data: GraphData) {
        if (index in entries.indices) {
            entries[index] = data
        }
    }

    fun clear() {
        entries.clear()
        index = -1
    }

    /**
     * Finds a live [CallableFunction] for [nodeId] by searching current and past history
     * entries. History is searched because PSI elements can become invalid after file
     * edits, and an older snapshot may still hold a valid reference for the same node.
     *
     * Validity is checked against [CallableFunction.navigationElement] — the navigation
     * PSI is what the UI needs and what survives refactors on a per-language basis.
     */
    fun findCallable(nodeId: String): CallableFunction? {
        return try {
            // .isValid is a PSI read — must be inside ReadAction regardless of calling thread.
            ReadAction.compute<CallableFunction?, RuntimeException> {
                current?.nodes?.find { it.id == nodeId }?.psiElement?.takeIf { it.navigationElement.isValid }
                    ?: entries.asReversed().firstNotNullOfOrNull { graphData ->
                        graphData.nodes.find { it.id == nodeId }?.psiElement?.takeIf { it.navigationElement.isValid }
                    }
            }
        } catch (ce: ProcessCanceledException) {
            // Cancellation must always propagate — it's how the platform tears down
            // background PSI work. Do not treat as "not found".
            throw ce
        } catch (e: Exception) {
            // Real PSI failures (corrupted indexes, dead references after a reload, etc.)
            // previously masqueraded as "node not found" due to a blanket catch. Log them
            // so debug-mode users can report the root cause, then return null so the UI
            // handler degrades gracefully.
            LOG.warn("Failed to resolve callable for node id $nodeId", e)
            DebugLog.error("GraphHistory.findCallable failed for '$nodeId'", e)
            null
        }
    }
}
