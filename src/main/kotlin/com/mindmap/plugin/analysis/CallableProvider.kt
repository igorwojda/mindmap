package com.mindmap.plugin.analysis

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope

/**
 * Per-language backend for call-graph construction.
 *
 * # Architecture boundary
 *
 * This is the **only** contract the pluggable backend layer exposes. The orchestrator
 * ([GraphAnalyzer]) and the entry action ([com.mindmap.plugin.actions.ShowCallGraphAction])
 * discover implementations via [EP_NAME] and iterate them — neither knows which languages
 * are loaded.
 *
 * ```
 *   ShowCallGraphAction ──► CallableProvider.callableAt(element)
 *                                 │
 *                                 ▼                          (one-hop resolution)
 *   GraphAnalyzer  ───────► CallableProvider.outboundTargets(callable)
 *                           CallableProvider.inboundCallers(callable, scope)
 *                           CallableProvider.implementations(...)
 * ```
 *
 * # Trust model
 *
 * Public EP — third-party plugins may register providers. They gain no capability
 * beyond what any IntelliJ plugin already has. [GraphAnalyzer.findCallable] wraps
 * each provider call in try/catch, so a faulty provider can't crash Mindmap.
 *
 * ## Rules every implementation MUST follow
 *
 * 1. **One hop only.** `outboundTargets` / `inboundCallers` return *direct* callees/callers.
 *    Recursion + depth bookkeeping + node/edge caps live in [GraphAnalyzer]. Providers
 *    that try to recurse break the shared limits and duplicate work.
 * 2. **Read-action safety.** All methods are called inside a `ReadAction` by the
 *    orchestrator; providers may assume PSI reads are safe but must not start new
 *    read actions or dispatch to EDT.
 * 3. **No UI, no IO, no side effects.** Providers are pure PSI queries. They must not
 *    touch `MindMapPanel`, `graph.html`, disk, or settings state.
 * 4. **Cross-language resolution is fine.** A provider may return
 *    [CallableFunction] cases wrapping a *different* language's PSI if that is how the
 *    reference resolves (e.g. Kotlin calling into a Java method → `KotlinCallableProvider`
 *    returns a `CallableFunction.Java`). [GraphAnalyzer] then routes the follow-up queries
 *    to the matching provider via [supports].
 * 5. **Optional-module isolation.** Providers for languages whose PSI classes are not on
 *    every IDE's classpath (Python, Go, PHP, Ruby, …) must be registered in a separate
 *    `mindmap-<lang>.xml` loaded via `<depends optional="true" config-file="...">` so the
 *    plugin loads cleanly on IDEs without that language's module.
 *
 * ## Adding a new language
 *
 * 1. Add a new case to [CallableFunction] (`data class Python(val fn: PyFunction) : ...`).
 * 2. Implement [CallableProvider] for that language.
 * 3. Register the provider in `mindmap-<lang>.xml` (new resource file).
 * 4. Add `<depends optional="true" config-file="mindmap-<lang>.xml">com.intellij.modules.<lang></depends>`
 *    to the core `plugin.xml`.
 * 5. Add the matching `plugin("<PluginId>", "<ver>")` marketplace dep in `build.gradle.kts`
 *    so the PSI classes are on the compile classpath.
 *
 * **Do not edit [GraphAnalyzer], `MindMapPanel`, `graph.html`, `ShowCallGraphAction`, or
 * `CallGraphModel`** to onboard a new language. If you feel the need, the contract below
 * is insufficient — extend the contract instead of leaking language knowledge into the
 * language-neutral layers.
 */
interface CallableProvider {

    /**
     * Stable language tag written into [CallGraphNode.language]. See
     * [CallableFunction.languageId] for recommended values. The frontend is
     * **not** allowed to branch on this value — it is an opaque label only.
     */
    val languageId: String

    /**
     * Returns `true` if this provider owns the given callable. Used by [GraphAnalyzer]
     * to dispatch follow-up queries to the right provider when outbound/inbound
     * resolution crosses a language boundary.
     *
     * Typical implementation: `callable is CallableFunction.<MyCase>`.
     */
    fun supports(callable: CallableFunction): Boolean

    /**
     * Finds the nearest enclosing callable for a cursor-level [element] (or any PSI
     * element), or `null` if this provider can't handle it. Called in sequence across
     * all registered providers by [com.mindmap.plugin.actions.ShowCallGraphAction].
     *
     * Implementations should walk *up* the PSI tree using
     * `PsiTreeUtil.getParentOfType(element, <their function PSI>)`. They MUST NOT
     * consider siblings or walk into other files.
     */
    fun callableAt(element: PsiElement): CallableFunction?

    /**
     * Returns the functions called directly from [callable]'s body (one hop).
     *
     * Implementations traverse the body's call expressions and resolve each to its
     * target. May return a [CallableFunction] in a *different* language when the
     * target is defined across a language boundary — that is the seam that makes
     * Kotlin↔Java edges work.
     *
     * Ordering should match source order so the resulting graph is deterministic.
     * Return value may include duplicates; [GraphAnalyzer] dedupes edges.
     *
     * MUST NOT recurse. MUST NOT apply node caps — [GraphAnalyzer] owns those.
     */
    fun outboundTargets(project: Project, callable: CallableFunction): List<CallableFunction>

    /**
     * Returns the functions that call [callable] within [scope] (one hop).
     *
     * Implementations typically use `ReferencesSearch.search(...)` and then walk each
     * reference's PSI parent chain to find the enclosing callable. They should also
     * widen to super declarations when applicable (Kotlin/Java via light methods) so
     * calls to an abstract base are surfaced on the concrete override.
     *
     * MUST NOT recurse. MUST NOT apply node caps — [GraphAnalyzer] owns those.
     */
    fun inboundCallers(
        project: Project,
        callable: CallableFunction,
        scope: GlobalSearchScope
    ): List<CallableFunction>

    /**
     * For an abstract/interface callable (`callable.hasBody == false`), returns up to
     * a few concrete implementations. Empty list if not applicable or if this provider
     * doesn't have an implementation-search API.
     *
     * The orchestrator calls this when the root (or an outbound target) has no body —
     * the graph then follows the concrete overrides instead of a dead-end declaration.
     */
    fun implementations(project: Project, callable: CallableFunction): List<CallableFunction> = emptyList()

    /**
     * Language-specific test-function detection, honored only when the user enables the
     * `excludeTests` setting. Default: never filter. Providers that skip this can leave
     * the default — the graph will simply include test functions.
     *
     * Typical implementations: Kotlin checks `@Test` / `@ParameterizedTest` / `@RepeatedTest`
     * annotations on the function; Java checks `org.junit.*` / `org.junit.jupiter.api.*` FQNs
     * on `PsiAnnotation`; Python can match `test_` name prefix or `@pytest.*` decorators.
     */
    fun isTestFunction(callable: CallableFunction): Boolean = false

    companion object {
        /**
         * Extension-point id declared in `plugin.xml`. Resolved at runtime to the list of
         * registered providers — Kotlin/Java ship in core, optional languages ship behind
         * `<depends optional>` config-files. Iterate as
         * `EP_NAME.extensionList.firstOrNull { it.supports(callable) }`.
         */
        val EP_NAME: ExtensionPointName<CallableProvider> =
            ExtensionPointName.create("com.mindmap.plugin.callableProvider")
    }
}
