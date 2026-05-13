package com.mindmap.plugin.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.wm.ToolWindowManager
import com.mindmap.plugin.Constants
import com.mindmap.plugin.DebugLog
import com.mindmap.plugin.analysis.CallableFunction
import com.mindmap.plugin.analysis.GraphAnalyzer
import com.mindmap.plugin.settings.PluginSettings
import com.mindmap.plugin.ui.MindMapPanel
import javax.swing.SwingUtilities

/**
 * Alt+G entry point. **Language-agnostic by design.**
 *
 * This class doesn't know about Kotlin, Java, Python, or any other language. It asks
 * [GraphAnalyzer.findCallable] for the nearest enclosing callable at the cursor, which
 * in turn dispatches to every registered
 * [com.mindmap.plugin.analysis.CallableProvider] until one returns a non-null hit.
 *
 * **Do not add language-specific checks here.** To onboard a new language, implement a
 * `CallableProvider` and register it via XML — this file does not need to change.
 *
 * # Thread model
 * - PSI reads happen inside [ReadAction] blocks; we never touch PSI on the EDT.
 * - The expensive analysis runs in a [Task.Backgroundable]; the final UI update is
 *   pushed back to the EDT via [SwingUtilities.invokeLater].
 */
class ShowCallGraphAction : AnAction() {

    companion object {
        private val LOG = Logger.getInstance(ShowCallGraphAction::class.java)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        try {
            val project = e.project ?: return
            val editor = e.getData(CommonDataKeys.EDITOR) ?: return
            val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return

            val offset = editor.caretModel.offset
            val element = psiFile.findElementAt(offset) ?: return

            // Dispatch across every registered CallableProvider — the first one that
            // claims the element wins. This is the only language-dispatch point in the
            // action layer.
            val callable = ReadAction.compute<CallableFunction?, RuntimeException> {
                GraphAnalyzer(project).findCallable(element)
            } ?: return

            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(Constants.TOOL_WINDOW_ID) ?: return
            toolWindow.show()

            val displayName = callable.name ?: "function"
            ProgressManager.getInstance().run(object : Task.Backgroundable(
                project, "Generating Mindmap for $displayName", true
            ) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        indicator.isIndeterminate = true
                        indicator.text = "Analyzing call graph (${callable.languageId})..."

                        val s = PluginSettings.getInstance(project)
                        val graphData = GraphAnalyzer(
                            project,
                            maxOutboundDepth = s.outboundDepth,
                            maxInboundDepth = s.inboundDepth,
                            debugMode = s.debugMode,
                            excludeTests = s.excludeTests
                        ).buildGraph(callable, indicator)

                        // UI and history mutations must happen on the EDT.
                        SwingUtilities.invokeLater {
                            val content = toolWindow.contentManager.getContent(0) ?: return@invokeLater
                            val panel = content.component as? MindMapPanel ?: return@invokeLater
                            panel.updateGraph(graphData)
                        }
                    } catch (ce: com.intellij.openapi.progress.ProcessCanceledException) {
                        throw ce
                    } catch (ex: Exception) {
                        LOG.error("Failed to generate mindmap", ex)
                        DebugLog.error("Failed to generate mindmap", ex)
                    }
                }
            })
        } catch (ex: Exception) {
            LOG.error("Mindmap action failed", ex)
            DebugLog.error("Mindmap action failed", ex)
        }
    }

    /**
     * Called on BGT (background thread) because PSI reads must not block the EDT. The
     * action is enabled whenever *any* registered provider can find a callable at the
     * cursor — i.e., automatically gains support for a new language the moment its
     * provider is registered.
     */
    override fun update(e: AnActionEvent) {
        try {
            val project = e.project
            val psiFile = e.getData(CommonDataKeys.PSI_FILE)
            val editor = e.getData(CommonDataKeys.EDITOR)

            // Skip PSI probe during indexing — keystroke-rate provider iteration
            // would stress the indexer and add no user value.
            var visible = false
            if (project != null && !DumbService.getInstance(project).isDumb
                && psiFile != null && editor != null) {
                val offset = editor.caretModel.offset
                val element = psiFile.findElementAt(offset)
                if (element != null) {
                    visible = ReadAction.compute<Boolean, RuntimeException> {
                        GraphAnalyzer(project).findCallable(element) != null
                    }
                }
            }
            e.presentation.isEnabledAndVisible = visible
        } catch (ex: Exception) {
            e.presentation.isEnabledAndVisible = false
        }
    }
}
