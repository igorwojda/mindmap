package com.mindmap.plugin

/**
 * Plugin-wide constants.
 *
 * Keep this file tiny — anything language- or feature-specific belongs with its owner.
 * The only entries here are identifiers that must stay in sync across multiple call
 * sites (Kotlin code and, by convention, the matching strings in `plugin.xml`).
 */
object Constants {
    /**
     * Tool window id registered by `plugin.xml` (`<toolWindow id="Mindmap" ... />`).
     * Every `ToolWindowManager.getToolWindow(...)` lookup must reference this constant
     * so renaming stays mechanical. The XML side is still a literal — IntelliJ's plugin
     * descriptor DTD has no constant-substitution mechanism — but any Kotlin drift from
     * the XML value will show up as a fast grep mismatch here.
     */
    const val TOOL_WINDOW_ID = "Mindmap"
}
