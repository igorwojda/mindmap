package com.mindmap.plugin.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefBrowser
import com.mindmap.plugin.DebugLog
import com.mindmap.plugin.analysis.CallableFunction
import com.mindmap.plugin.analysis.GraphAnalyzer
import com.mindmap.plugin.analysis.GraphData
import com.mindmap.plugin.analysis.NodeType
import com.mindmap.plugin.analysis.merge
import com.mindmap.plugin.settings.PluginSettings
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Hosts the JCEF browser and coordinates the call graph lifecycle.
 *
 * # Language-neutral by contract
 *
 * This class — and the `graph.html` it loads — must never import a specific language's
 * PSI type. All per-node work goes through [CallableFunction.navigationElement], which
 * gives us a plain `PsiElement` suitable for IDE navigation regardless of whether the
 * underlying language is Kotlin, Java, Python, or a future addition. If you find
 * yourself importing `KtNamedFunction` / `PsiMethod` / `PyFunction` here, the
 * abstraction has leaked — fix [CallableFunction] / [com.mindmap.plugin.analysis.CallableProvider]
 * instead of editing this file.
 *
 * Owns three stateful components: [browser] (renders graph.html), [bridge] (JS<->Kotlin
 * IPC), and [history] (back/forward navigation). Graph analysis runs on background
 * threads via [ProgressManager]; all UI and history mutations happen on the EDT.
 */
class MindMapPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val browser = JBCefBrowser()
    private val bridge = JsBridge(browser) { event -> handleEvent(event) }
    private val history = GraphHistory()
    @Volatile
    private var isDisposed = false
    @Volatile
    private var currentGraphData: GraphData? = null

    private val settings = PluginSettings.getInstance(project)
    private val debugMode: Boolean get() = settings.debugMode

    // Snapshot of mutable settings, taken atomically before each background analysis so a
    // mid-flight slider change cannot leave the analyzer with an inconsistent
    // outbound/inbound pair.
    private data class DepthSnapshot(
        val outbound: Int,
        val inbound: Int,
        val retraceOutbound: Int,
        val retraceInbound: Int,
        val excludeTests: Boolean
    )

    private fun snapshot() = DepthSnapshot(
        settings.outboundDepth,
        settings.inboundDepth,
        settings.retraceOutboundDepth,
        settings.retraceInboundDepth,
        settings.excludeTests
    )

    companion object {
        private val LOG = Logger.getInstance(MindMapPanel::class.java)
    }

    private fun debug(msg: () -> String) {
        if (debugMode) DebugLog.log(msg())
    }

    init {
        add(browser.component, BorderLayout.CENTER)
        bridge.setup(
            onReady = { updateGraph(currentGraphData ?: return@setup) },
            initialSettings = {
                bridge.sendInitialSettings(
                    outbound = settings.outboundDepth,
                    inbound = settings.inboundDepth,
                    retraceOutbound = settings.retraceOutboundDepth,
                    retraceInbound = settings.retraceInboundDepth,
                    excludeTests = settings.excludeTests,
                    debug = settings.debugMode,
                    showClassPrefix = settings.showClassPrefix
                )
            }
        )
        bridge.loadHtml()
    }

    /**
     * Entry point from Alt+G. Resets history and renders [data] as the new root graph.
     * Use [pushGraph] instead when navigating within an existing session (expand/re-center).
     */
    fun updateGraph(data: GraphData) {
        debug { "New graph received — root: ${data.nodes.find { it.type == NodeType.ROOT }?.name}, nodes: ${data.nodes.size}, edges: ${data.edges.size}" }
        currentGraphData = data
        if (!bridge.isReady) {
            debug { "Browser not ready yet — graph queued, will render when JCEF finishes loading" }
            bridge.markPending(data)
            return
        }
        history.reset(data)
        bridge.sendGraph(data)
        sendHistoryState()
    }

    private fun handleEvent(event: JsBridge.MessageEvent) {
        when (event) {
            is JsBridge.MessageEvent.Navigate -> handleNavigate(event.nodeId)
            is JsBridge.MessageEvent.OpenUrl -> BrowserUtil.browse(event.url)
            is JsBridge.MessageEvent.Expand -> handleExpand(event.nodeId)
            is JsBridge.MessageEvent.Trace -> handleTrace(event.nodeId)
            is JsBridge.MessageEvent.HistoryBack -> SwingUtilities.invokeLater { if (!isDisposed) handleHistoryBack() }
            is JsBridge.MessageEvent.HistoryForward -> SwingUtilities.invokeLater { if (!isDisposed) handleHistoryForward() }
            is JsBridge.MessageEvent.Restart -> SwingUtilities.invokeLater { if (!isDisposed) handleRestart() }
            is JsBridge.MessageEvent.SetDepth -> {
                settings.outboundDepth = event.outbound; settings.inboundDepth = event.inbound
            }

            is JsBridge.MessageEvent.SetRetraceDepth -> {
                settings.retraceOutboundDepth = event.outbound; settings.retraceInboundDepth =
                    event.inbound
            }

            is JsBridge.MessageEvent.SetExcludeTests -> settings.excludeTests = event.enabled
            is JsBridge.MessageEvent.SetShowClassPrefix -> settings.showClassPrefix = event.enabled
            is JsBridge.MessageEvent.SetDebug -> {
                settings.debugMode = event.enabled
                DebugLog.enabled = event.enabled
                // Log only the filename — the absolute path includes the OS user home
                // directory which frequently leaks when users share debug logs on public
                // issue trackers. Power users can still retrieve the full path via
                // DebugLog.logFilePath if they need it programmatically.
                if (event.enabled) DebugLog.log("Debug mode enabled — log file: mindmap-debug.log (in IDE log directory)")
                else DebugLog.log("Debug mode disabled")
            }
        }
    }

    // Pushes a new graph onto the history stack (used by expand/re-center).
    private fun pushGraph(data: GraphData) {
        currentGraphData = data
        if (!bridge.isReady) {
            bridge.markPending(data)
            return
        }
        history.push(data)
        bridge.sendGraph(data)
        sendHistoryState()
    }

    private fun sendHistoryState() {
        if (isDisposed) return
        bridge.sendHistoryState(
            history.canBack,
            history.canForward,
            history.position,
            history.total
        )
    }

    private fun handleRestart() {
        history.clear()
        currentGraphData = null
        sendHistoryState()
    }

    // PSI reads must not block the EDT, so we dispatch to a pooled thread first.
    // Once we have the virtual file and offset, we hand off to the EDT for actual navigation.
    private fun handleNavigate(nodeId: String) {
        if (isDisposed) return
        debug { "Navigate requested — clicking node to open in editor (id: $nodeId)" }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val result =
                    ReadAction.compute<Triple<VirtualFile, Int, Project>?, RuntimeException> {
                        val node = currentGraphData?.nodes?.find { it.id == nodeId }
                        val callable = node?.psiElement
                        if (node == null) {
                            debug { "Navigate failed — node ID not found in current graph data, may have been cleared" }; return@compute null
                        }
                        if (callable == null) {
                            debug { "Navigate failed — callable reference missing for '${node.name}', function reference was lost after graph serialization" }; return@compute null
                        }
                        // Validity check is language-agnostic: navigationElement is a plain PsiElement.
                        val nav = callable.navigationElement
                        if (!nav.isValid) {
                            debug { "Navigate failed — PSI element for '${node.name}' is stale, file was likely modified since graph was built" }; return@compute null
                        }
                        val virtualFile = callable.containingFile?.virtualFile
                        if (virtualFile == null) {
                            debug { "Navigate failed — '${node.name}' has no file on disk, may be an in-memory or generated class" }; return@compute null
                        }
                        debug { "Navigate success — opening '${node.name}' at ${virtualFile.name}:offset=${nav.textOffset} (${callable.languageId})" }
                        Triple(virtualFile, nav.textOffset, project)
                    } ?: return@executeOnPooledThread

                // WriteIntentReadAction required: newer IntelliJ/Android Studio enforces
                // read access for editor model operations (caret movement) on EDT.
                SwingUtilities.invokeLater {
                    if (isDisposed) return@invokeLater
                    try {
                        WriteIntentReadAction.run {
                            OpenFileDescriptor(result.third, result.first, result.second).navigate(
                                true
                            )
                        }
                    } catch (e: Exception) {
                        LOG.error(
                            "Failed to open editor",
                            e
                        ); DebugLog.error("Failed to open editor", e)
                    }
                }
            } catch (e: Exception) {
                LOG.error(
                    "Failed to navigate to node",
                    e
                ); DebugLog.error("Failed to navigate to node", e)
            }
        }
    }

    private fun handleExpand(nodeId: String) {
        if (isDisposed) return
        debug { "Expand requested — rebuilding graph centered on node (id: $nodeId)" }
        val callable = history.findCallable(nodeId)
        if (callable == null) {
            debug { "Expand failed — no valid callable found in current or past graph history for this node" }; return
        }
        ProgressManager.getInstance()
            .run(object : Task.Backgroundable(project, "Generating Mindmap...", true) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        if (isDisposed) return
                        if (!ReadAction.compute<Boolean, RuntimeException> { callable.navigationElement.isValid }) {
                            debug { "Expand failed — callable became stale, file was modified since graph was built" }; return
                        }
                        val startTime = System.currentTimeMillis()
                        val newData = analyzeCallable(callable, indicator)
                        debug { "Expand complete — analyzed in ${System.currentTimeMillis() - startTime}ms, result: ${newData.nodes.size} nodes, ${newData.edges.size} edges" }
                        SwingUtilities.invokeLater { if (!isDisposed) pushGraph(newData) }
                    } catch (ce: com.intellij.openapi.progress.ProcessCanceledException) {
                        throw ce
                    } catch (ex: Exception) {
                        LOG.error(
                            "Failed to expand node",
                            ex
                        ); DebugLog.error("Failed to expand node", ex)
                    }
                }
            })
    }

    private fun handleTrace(nodeId: String) {
        if (isDisposed) return
        debug { "Trace requested — merging callers/callees of node into current graph (id: $nodeId)" }
        val callable = history.findCallable(nodeId)
        if (callable == null) {
            debug { "Trace failed — no valid callable found in current or past graph history for this node" }; return
        }
        ProgressManager.getInstance()
            .run(object : Task.Backgroundable(project, "Tracing function...", true) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        if (isDisposed) return
                        if (!ReadAction.compute<Boolean, RuntimeException> { callable.navigationElement.isValid }) {
                            debug { "Trace failed — callable became stale, file was modified since graph was built" }; return
                        }
                        waitForSmartMode(indicator)
                        val d = snapshot()
                        debug { "Tracing '${callable.name}' (${callable.languageId}) — outbound: ${d.retraceOutbound}, inbound: ${d.retraceInbound}" }
                        val startTime = System.currentTimeMillis()
                        val traceData = GraphAnalyzer(
                            project,
                            d.retraceOutbound,
                            d.retraceInbound,
                            debugMode,
                            d.excludeTests
                        ).buildGraph(callable, indicator)
                        val mergedData = (currentGraphData ?: return).merge(traceData)
                        debug { "Trace complete — analyzed in ${System.currentTimeMillis() - startTime}ms, merged graph: ${mergedData.nodes.size} nodes, ${mergedData.edges.size} edges" }
                        SwingUtilities.invokeLater {
                            if (isDisposed) return@invokeLater
                            currentGraphData = mergedData
                            history.updateCurrent(mergedData)
                            bridge.sendMergedGraph(mergedData)
                        }
                    } catch (ce: com.intellij.openapi.progress.ProcessCanceledException) {
                        throw ce
                    } catch (ex: Exception) {
                        LOG.error(
                            "Failed to trace node",
                            ex
                        ); DebugLog.error("Failed to trace node", ex)
                    }
                }
            })
    }

    // Language-neutral: GraphAnalyzer delegates to the correct CallableProvider based on
    // the callable's sealed case.
    private fun analyzeCallable(
        callable: CallableFunction,
        indicator: ProgressIndicator
    ): GraphData {
        waitForSmartMode(indicator)
        val d = snapshot()
        return GraphAnalyzer(project, d.outbound, d.inbound, debugMode, d.excludeTests).buildGraph(
            callable,
            indicator
        )
    }

    // Outbound/inbound resolution relies on indices that are incomplete during
    // indexing — searches don't throw, they just silently return partial/empty
    // results, which looks like a broken graph with nothing in the log to explain why.
    private fun waitForSmartMode(indicator: ProgressIndicator) {
        if (!DumbService.getInstance(project).isDumb) return
        indicator.text = "Waiting for indexing to finish..."
        DumbService.getInstance(project).waitForSmartMode()
    }

    private fun handleHistoryBack() {
        val data = history.back() ?: return
        debug { "History back — viewing graph ${history.position} of ${history.total} (${data.nodes.size} nodes)" }
        currentGraphData = data
        bridge.sendGraph(data)
        sendHistoryState()
    }

    private fun handleHistoryForward() {
        val data = history.forward() ?: return
        debug { "History forward — viewing graph ${history.position} of ${history.total} (${data.nodes.size} nodes)" }
        currentGraphData = data
        bridge.sendGraph(data)
        sendHistoryState()
    }

    override fun dispose() {
        if (!isDisposed) {
            isDisposed = true
            try {
                history.clear()
                currentGraphData = null
                bridge.dispose()
                browser.dispose()
            } catch (e: Exception) {
                LOG.error("Error during dispose", e); DebugLog.error("Error during dispose", e)
            }
        }
    }
}
