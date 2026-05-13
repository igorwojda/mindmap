package com.mindmap.plugin.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Project-level persistent settings for Mindmap. Backs the depth sliders, exclude-tests
 * toggle, and debug-mode flag in the graph settings panel so user-configured values
 * survive IDE restarts and are visible to every entry point (Alt+G action, in-panel
 * expand/trace).
 *
 * Depth fields are coerced to 1..5 to match the slider range in `graph.html`.
 */
@Service(Service.Level.PROJECT)
@State(name = "MindmapPluginSettings", storages = [Storage("mindmap.xml")])
class PluginSettings : PersistentStateComponent<PluginSettings.State> {

    data class State(
        var outboundDepth: Int = 3,
        var inboundDepth: Int = 2,
        var retraceOutboundDepth: Int = 1,
        var retraceInboundDepth: Int = 1,
        var excludeTests: Boolean = true,
        var debugMode: Boolean = false
    )

    private var state = State()

    override fun getState(): State = state
    override fun loadState(s: State) { XmlSerializerUtil.copyBean(s, state) }

    var outboundDepth: Int
        get() = state.outboundDepth
        set(v) { state.outboundDepth = v.coerceIn(1, 5) }

    var inboundDepth: Int
        get() = state.inboundDepth
        set(v) { state.inboundDepth = v.coerceIn(1, 5) }

    var retraceOutboundDepth: Int
        get() = state.retraceOutboundDepth
        set(v) { state.retraceOutboundDepth = v.coerceIn(1, 5) }

    var retraceInboundDepth: Int
        get() = state.retraceInboundDepth
        set(v) { state.retraceInboundDepth = v.coerceIn(1, 5) }

    var excludeTests: Boolean
        get() = state.excludeTests
        set(v) { state.excludeTests = v }

    var debugMode: Boolean
        get() = state.debugMode
        set(v) { state.debugMode = v }

    companion object {
        fun getInstance(project: Project): PluginSettings = project.service()
    }
}
