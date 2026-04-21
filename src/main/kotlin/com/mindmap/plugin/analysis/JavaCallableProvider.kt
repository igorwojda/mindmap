package com.mindmap.plugin.analysis

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.search.searches.SuperMethodsSearch
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.asJava.unwrapped
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * [CallableProvider] for Java.
 *
 * # Responsibilities
 * - Finds the nearest enclosing [PsiMethod] at the cursor (Java method bodies,
 *   including nested / anonymous classes — `PsiTreeUtil.getParentOfType` walks up).
 * - Resolves outbound call expressions via `PsiMethodCallExpression.resolveMethod()`.
 *   This call is fully indexed, so we don't need a special analysis session like
 *   Kotlin's `analyze {}` — plain read-action PSI is enough.
 * - Finds callers via `ReferencesSearch`. Because Java-side references can point
 *   into Kotlin sources (via light classes), the resolved parent may be a
 *   [KtNamedFunction] rather than a [PsiMethod] — we wrap accordingly so the
 *   Kotlin↔Java seam works both directions.
 * - Detects JUnit 4 / 5 `@Test` annotations by fully qualified name — the plugin
 *   also recognizes `@ParameterizedTest` / `@RepeatedTest` (Jupiter).
 *
 * # Cross-language seam
 *
 * A Java method calling a Kotlin function goes through a generated light method. The
 * resolved `PsiMethod` reports `navigationElement` (via `unwrapped`) as the original
 * `KtNamedFunction`. We detect that and wrap as [CallableFunction.Kotlin] so the
 * orchestrator routes the follow-up query to [KotlinCallableProvider].
 *
 * # Contract reminders
 * One-hop only. No recursion, no caps — [GraphAnalyzer] owns depth and size limits.
 */
class JavaCallableProvider : CallableProvider {

    companion object {
        private val LOG = Logger.getInstance(JavaCallableProvider::class.java)

        private const val MAX_CALLS_PER_FUNCTION = 50

        // FQN prefixes of annotations that mark test methods. Short-name matching is not
        // enough here because Java often imports the annotation by FQN or uses fully
        // qualified refs, and several frameworks share short names.
        private val TEST_ANNOTATION_FQNS = setOf(
            "org.junit.Test",
            "org.junit.jupiter.api.Test",
            "org.junit.jupiter.api.ParameterizedTest",
            "org.junit.jupiter.api.RepeatedTest",
            "org.junit.jupiter.api.TestFactory",
            "org.junit.jupiter.api.TestTemplate",
            "org.testng.annotations.Test",
            "kotlin.test.Test"
        )
    }

    override val languageId: String = "java"

    override fun supports(callable: CallableFunction): Boolean = callable is CallableFunction.Java

    override fun callableAt(element: PsiElement): CallableFunction? {
        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java) ?: return null
        return CallableFunction.Java(method)
    }

    /**
     * Walks the method body looking for every `PsiMethodCallExpression` and resolves
     * each to its target via `resolveMethod()` (fully indexed, no analysis session).
     *
     * If the resolved target's `navigationElement` is a [KtNamedFunction] (Java
     * calling Kotlin via a light method), we wrap as [CallableFunction.Kotlin] so
     * the Kotlin provider handles recursion.
     */
    override fun outboundTargets(project: Project, callable: CallableFunction): List<CallableFunction> {
        val method = (callable as? CallableFunction.Java)?.m ?: return emptyList()
        val body = method.body ?: return emptyList()

        val calls = PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression::class.java)
            .let { if (it.size > MAX_CALLS_PER_FUNCTION) it.take(MAX_CALLS_PER_FUNCTION) else it.toList() }

        val results = mutableListOf<CallableFunction>()
        for (callExpr in calls) {
            try {
                val target = callExpr.resolveMethod() ?: continue
                val wrapped = wrapResolvedTarget(target) ?: continue
                results.add(wrapped)
            } catch (ce: com.intellij.openapi.progress.ProcessCanceledException) {
                throw ce
            } catch (e: Exception) {
                LOG.debug("Skipped unresolved Java outbound call", e)
            }
        }
        return results
    }

    /**
     * Cross-language wrap: if the resolved Java method is actually the light facade
     * of a Kotlin function, route it to the Kotlin provider by wrapping as
     * [CallableFunction.Kotlin]. Otherwise it's a real Java method.
     */
    private fun wrapResolvedTarget(method: PsiMethod): CallableFunction? {
        val source = method.unwrapped
        return when (source) {
            is KtNamedFunction -> CallableFunction.Kotlin(source)
            else -> CallableFunction.Java(method)
        }
    }

    override fun inboundCallers(
        project: Project,
        callable: CallableFunction,
        scope: GlobalSearchScope
    ): List<CallableFunction> {
        val method = (callable as? CallableFunction.Java)?.m ?: return emptyList()
        return try {
            // Widen with super declarations so callers of the abstract base surface on the override.
            val targets = mutableListOf<PsiMethod>(method)
            targets.addAll(
                SuperMethodsSearch.search(method, null, true, false).findAll().map { it.method }
            )

            targets
                .flatMap { ReferencesSearch.search(it, scope).findAll() }
                .distinctBy { it.element }
                .mapNotNull { ref ->
                    val javaParent = PsiTreeUtil.getParentOfType(ref.element, PsiMethod::class.java)
                    if (javaParent != null) {
                        // If the Java parent is itself a light facade for a Kotlin function, prefer
                        // the Kotlin source so navigation lands on the .kt file.
                        val unwrapped = javaParent.unwrapped
                        return@mapNotNull if (unwrapped is KtNamedFunction) CallableFunction.Kotlin(unwrapped)
                        else CallableFunction.Java(javaParent)
                    }
                    val kotlinParent = PsiTreeUtil.getParentOfType(ref.element, KtNamedFunction::class.java)
                    if (kotlinParent != null) CallableFunction.Kotlin(kotlinParent) else null
                }
                .distinct()
        } catch (ce: com.intellij.openapi.progress.ProcessCanceledException) {
            throw ce
        } catch (e: Exception) {
            LOG.warn("Error searching Java inbound references", e)
            emptyList()
        }
    }

    override fun implementations(project: Project, callable: CallableFunction): List<CallableFunction> {
        val method = (callable as? CallableFunction.Java)?.m ?: return emptyList()
        if (method.body != null) return emptyList()
        return try {
            val scope = GlobalSearchScope.projectScope(project)
            OverridingMethodsSearch.search(method, scope, true).toList()
                .mapNotNull { overriding ->
                    when (val nav = overriding.navigationElement) {
                        is KtNamedFunction -> if (nav.bodyExpression != null) CallableFunction.Kotlin(nav) else null
                        is PsiMethod -> if (nav.body != null) CallableFunction.Java(nav) else null
                        else -> null
                    }
                }
                .take(3)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Detection by annotation FQN. We iterate the method's annotations and compare
     * against a small allow-list of known test-framework annotation classes.
     */
    override fun isTestFunction(callable: CallableFunction): Boolean {
        val method = (callable as? CallableFunction.Java)?.m ?: return false
        return method.annotations.any { it.qualifiedName in TEST_ANNOTATION_FQNS }
    }
}
