package com.mindmap.plugin.ui

/**
 * Answers whether this plugin can even load the JCEF classes, without linking any of them.
 *
 * From IDE build 262 (2026.2) onward, `com.intellij.ui.jcef.*` ships in the bundled
 * "Web Browser (JCEF)" plugin instead of the platform core, and its classes are only
 * visible to plugins that declare a dependency on it (`com.intellij.modules.jcef` in
 * plugin.xml). Older IDEs and setups without a JBR/JCEF runtime can also lack these
 * classes entirely.
 *
 * Referencing `JBCefApp`/`JBCefBrowser` directly — even just to call `isSupported()` —
 * forces the JVM to resolve the type, which throws `NoClassDefFoundError` before any of
 * our own code runs if the class isn't visible. This check uses reflection so it can
 * answer "no" instead of crashing. Nothing in this file may name a JCEF type.
 */
object JcefSupport {

    private val requiredClasses = listOf(
        "com.intellij.ui.jcef.JBCefApp",
        "com.intellij.ui.jcef.JBCefBrowser",
        "com.intellij.ui.jcef.JBCefJSQuery",
    )

    /** True if the JCEF classes resolve on this plugin's class loader. */
    fun isClassLoadable(): Boolean = requiredClasses.all(::loadable)

    private fun loadable(name: String): Boolean = try {
        Class.forName(name, false, JcefSupport::class.java.classLoader)
        true
    } catch (_: ClassNotFoundException) {
        false
    } catch (_: LinkageError) {
        false
    }
}
