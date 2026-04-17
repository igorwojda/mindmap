package com.mindmap.plugin.python

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.psi.PyCallExpression
import com.jetbrains.python.psi.PyDecorator
import com.jetbrains.python.psi.PyFunction
import com.mindmap.plugin.analysis.CallableFunction
import com.mindmap.plugin.analysis.CallableProvider

/**
 * [CallableProvider] for Python.
 *
 * # Runtime gating
 *
 * This class is registered via `mindmap-python.xml`, which in turn is loaded only when
 * `com.intellij.modules.python` is present (PyCharm Community/Pro, IntelliJ with Python
 * plugin, Android Studio + Python). On IDEs without Python, neither this class nor
 * [CallableFunction.Python] is ever touched — so the absence of the Python PSI
 * classes at runtime is not an error.
 *
 * # Resolution strategy
 *
 * - **Cursor → callable** (`callableAt`): `PsiTreeUtil.getParentOfType(element, PyFunction)`.
 *   Works for top-level `def`, methods in classes, and nested functions.
 * - **Outbound** (`outboundTargets`): walk every [PyCallExpression] inside the function's
 *   `statementList`, resolve its callee reference, and keep only targets that resolve to
 *   another [PyFunction]. Calls into builtins, C-extensions, or `getattr`-style dynamic
 *   dispatch are silently skipped — Python's dynamic nature makes perfect resolution
 *   impossible; we ship a best-effort static call graph.
 * - **Inbound** (`inboundCallers`): `ReferencesSearch.search(pyFunction, scope)` with the
 *   reference's enclosing [PyFunction] as the caller. No `toLightMethods` bridge — Python
 *   doesn't participate in the JVM light-class world, so Py↔Kt / Py↔Java edges do not
 *   appear (by design; documented limitation).
 * - **Test detection** (`isTestFunction`): honors `excludeTests` when the name starts
 *   with `test_` or the function carries a `@pytest.*` / `@pytest_asyncio.*` decorator
 *   — the two conventions cover unittest, pytest, and pytest-asyncio suites.
 *
 * # Contract reminders
 * One-hop only. No recursion. No caps — [com.mindmap.plugin.analysis.GraphAnalyzer]
 * owns depth and size limits.
 */
class PythonCallableProvider : CallableProvider {

    companion object {
        private val LOG = Logger.getInstance(PythonCallableProvider::class.java)

        private const val MAX_CALLS_PER_FUNCTION = 50

        // Decorator name prefixes that mark a test. `PyDecorator.name` returns the short
        // tail of the decorator expression (e.g., `pytest.fixture` → `fixture`), so we
        // compare against the full dotted text instead.
        private val TEST_DECORATOR_PREFIXES = listOf(
            "pytest.",
            "pytest_asyncio.",
            "unittest.",
        )
    }

    override val languageId: String = "python"

    override fun supports(callable: CallableFunction): Boolean = callable is CallableFunction.Python

    override fun callableAt(element: PsiElement): CallableFunction? {
        val fn = PsiTreeUtil.getParentOfType(element, PyFunction::class.java) ?: return null
        return CallableFunction.Python(fn)
    }

    /**
     * Python has no Analysis API session — plain `reference.resolve()` is sufficient
     * because Python's PSI reference resolution runs synchronously on index data.
     *
     * `callee.reference` is used rather than `callee.references.firstOrNull()`: the
     * primary reference is what the IDE uses for Go-To-Declaration and it resolves
     * the same way here.
     */
    override fun outboundTargets(project: Project, callable: CallableFunction): List<CallableFunction> {
        val fn = (callable as? CallableFunction.Python)?.fn ?: return emptyList()
        val body = fn.statementList ?: return emptyList()

        val calls = PsiTreeUtil.findChildrenOfType(body, PyCallExpression::class.java)
            .let { if (it.size > MAX_CALLS_PER_FUNCTION) it.take(MAX_CALLS_PER_FUNCTION) else it.toList() }

        val results = mutableListOf<CallableFunction>()
        for (call in calls) {
            try {
                val callee = call.callee ?: continue
                // Primary reference — first-class PSI reference used by Go-To-Declaration.
                val resolved = callee.reference?.resolve() ?: continue
                val pyFn = resolved as? PyFunction ?: continue
                if (pyFn.name.isNullOrEmpty()) continue
                results.add(CallableFunction.Python(pyFn))
            } catch (ce: com.intellij.openapi.progress.ProcessCanceledException) {
                throw ce
            } catch (e: Exception) {
                LOG.debug("Skipped unresolved Python outbound call", e)
            }
        }
        return results
    }

    override fun inboundCallers(
        project: Project,
        callable: CallableFunction,
        scope: GlobalSearchScope
    ): List<CallableFunction> {
        val fn = (callable as? CallableFunction.Python)?.fn ?: return emptyList()
        return try {
            ReferencesSearch.search(fn, scope).findAll()
                .distinctBy { it.element }
                .mapNotNull { ref ->
                    val parent = PsiTreeUtil.getParentOfType(ref.element, PyFunction::class.java)
                    parent?.let { CallableFunction.Python(it) }
                }
                .distinct()
        } catch (ce: com.intellij.openapi.progress.ProcessCanceledException) {
            throw ce
        } catch (e: Exception) {
            LOG.warn("Error searching Python inbound references", e)
            emptyList()
        }
    }

    /**
     * Python has no direct analog to JVM `OverridingMethodsSearch` out of the box here —
     * we skip implementation expansion for abstract-like declarations. If the root is a
     * method on an abstract class (`@abc.abstractmethod`), the graph still follows
     * `outboundTargets` normally since the body exists; overrides are not auto-traced.
     * This matches user expectation for Python's duck-typed call graphs.
     */
    override fun implementations(project: Project, callable: CallableFunction): List<CallableFunction> = emptyList()

    /**
     * Test detection heuristic:
     * 1. name starts with `test_` (unittest / pytest common convention), or
     * 2. function carries a decorator whose full text starts with one of
     *    `pytest.` / `pytest_asyncio.` / `unittest.`.
     *
     * We read the decorator's raw text because PyDecorator.name returns only the tail.
     */
    override fun isTestFunction(callable: CallableFunction): Boolean {
        val fn = (callable as? CallableFunction.Python)?.fn ?: return false
        val name = fn.name ?: ""
        if (name.startsWith("test_")) return true
        val decorators = fn.decoratorList?.decorators?.toList() ?: emptyList<PyDecorator>()
        return decorators.any { dec ->
            val text = dec.text?.removePrefix("@")?.trim() ?: return@any false
            TEST_DECORATOR_PREFIXES.any { text.startsWith(it) }
        }
    }
}
