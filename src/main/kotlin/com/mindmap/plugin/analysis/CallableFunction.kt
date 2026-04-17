package com.mindmap.plugin.analysis

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.jetbrains.python.psi.PyFunction
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Language-neutral wrapper around a callable PSI element (a function / method).
 *
 * This is the **backend-only** handle that flows through [GraphAnalyzer] and the
 * `CallableProvider` extension point. It is stored on [CallGraphNode.psiElement] as
 * `@Transient` so Gson never serializes it — the JSON boundary between the backend
 * and the `graph.html` frontend carries only primitive strings/numbers.
 *
 * ## Architecture contract
 *
 * - **Sealed interface, one case per supported language.** Kotlin and Java ship in core
 *   (unconditional classpath). Python/Go/PHP/Ruby/… each live behind an optional
 *   `config-file` in `plugin.xml`; adding a new language = new sealed case + new
 *   `CallableProvider` impl + new `mindmap-<lang>.xml`. Nothing else.
 * - **Never leaks past `analysis/`.** UI (`MindMapPanel`, `graph.html`) and the action
 *   layer (`ShowCallGraphAction`) must not import a specific sealed case. Everything
 *   they need is exposed through the abstract members below.
 * - **Identity = underlying PSI.** Two [CallableFunction] instances are equal iff
 *   their wrapped PSI element is equal; `data class` gives this for free.
 *
 * Why a sealed interface (not an abstract class or an untyped `PsiElement`)?
 * - Compiler-checked exhaustiveness when a provider needs to unwrap to the concrete
 *   PSI type for language-specific APIs (`KaSession.analyze{}`, `PsiMethod.body`, etc.).
 * - Zero runtime overhead vs. reflective `when (element) { is KtNamedFunction -> ... }`
 *   scattered across the codebase.
 */
sealed interface CallableFunction {

    /** Simple name (e.g. `"foo"`) or `null` for anonymous/unnamed callables. */
    val name: String?

    /**
     * Fully qualified name when available (Kotlin top-level or class members; Java methods
     * via containing class FQN). `null` for local functions and anonymous callables —
     * [GraphAnalyzer] falls back to a `filePath::name` id in that case.
     */
    val fqName: String?

    /** Owning source file, or `null` for in-memory / synthetic PSI. */
    val containingFile: PsiFile?

    /**
     * `true` if the callable has a body to traverse for outbound calls. `false` for
     * abstract/interface declarations — `CallableProvider.implementations()` is called
     * in that case to widen to concrete overrides.
     */
    val hasBody: Boolean

    /**
     * The PSI element used for IDE navigation (click-to-source, Ctrl+click).
     * For Kotlin this is the `KtNamedFunction`; for Java the `PsiMethod`. For languages
     * whose physical declaration differs from the navigation target, providers may
     * override this (see future language implementations).
     */
    val navigationElement: PsiElement

    /**
     * Stable label written into [CallGraphNode.language] and shown in JSON that reaches
     * the frontend. Purely a tag — the frontend treats it as opaque and must not branch
     * on specific values. Recommended ids: `"kotlin"`, `"java"`, `"python"`, `"go"`, …
     */
    val languageId: String

    /** Approximate line count of the body; used as a size hint in the UI. */
    val lineCount: Int

    /**
     * Parameter-type signature used as an overload disambiguator inside
     * [CallGraphNode.id]. Comma-joined, no names, types only — e.g. `"Int,String"`.
     * Empty string is valid for zero-parameter callables.
     *
     * Lives on [CallableFunction] rather than in `GraphAnalyzer` so the orchestrator
     * stays language-neutral: adding a new language = one new sealed case + one new
     * implementation here, no `when` branch added elsewhere.
     */
    val parameterSignature: String

    /**
     * Full human-readable signature shown in the UI tooltip, e.g.
     * `"foo(a: Int, b: String): Boolean"`. Language-specific formatting (colon-separated
     * type annotations for Kotlin/Python, space-separated for Java) is handled per case.
     */
    val signatureDisplay: String

    /** Kotlin wrapper. Compiled unconditionally — Kotlin module is a hard dep. */
    data class Kotlin(val fn: KtNamedFunction) : CallableFunction {
        override val name: String? get() = fn.name
        override val fqName: String? get() = fn.fqName?.asString()
        override val containingFile: PsiFile? get() = fn.containingFile
        override val hasBody: Boolean get() = fn.bodyExpression != null
        override val navigationElement: PsiElement get() = fn
        override val languageId: String get() = "kotlin"
        override val lineCount: Int get() = fn.text?.lines()?.size ?: 0
        override val parameterSignature: String
            get() = fn.valueParameters.joinToString(",") { it.typeReference?.text ?: "Any" }
        override val signatureDisplay: String
            get() {
                val params = fn.valueParameters.joinToString(", ") { p ->
                    "${p.name ?: "_"}: ${p.typeReference?.text ?: "Any"}"
                }
                val ret = fn.typeReference?.text?.let { ": $it" } ?: ""
                return "${fn.name}($params)$ret"
            }
    }

    /**
     * Java wrapper. Compiled unconditionally — Java module (`com.intellij.modules.java`)
     * is a hard dep. Enables Kotlin↔Java cross-language edges via light methods
     * (see `KotlinCallableProvider.inboundCallers` and `JavaCallableProvider`).
     */
    data class Java(val m: PsiMethod) : CallableFunction {
        override val name: String? get() = m.name
        override val fqName: String?
            get() = m.containingClass?.qualifiedName?.let { "$it.${m.name}" } ?: m.name
        override val containingFile: PsiFile? get() = m.containingFile
        override val hasBody: Boolean get() = m.body != null
        override val navigationElement: PsiElement get() = m
        override val languageId: String get() = "java"
        override val lineCount: Int get() = m.text?.lines()?.size ?: 0
        override val parameterSignature: String
            get() = m.parameterList.parameters.joinToString(",") { it.type.presentableText }
        override val signatureDisplay: String
            get() {
                val params = m.parameterList.parameters.joinToString(", ") { p ->
                    "${p.name ?: "_"}: ${p.type.presentableText}"
                }
                val ret = ": ${m.returnType?.presentableText ?: "void"}"
                return "${m.name}($params)$ret"
            }
    }

    /**
     * Python wrapper. Compiled against the PythonCore plugin pulled in at dev time by
     * `build.gradle.kts` (`plugin("PythonCore", …)`) but **only loaded at runtime when
     * the host IDE has `com.intellij.modules.python`** — PyCharm Community/Pro, IntelliJ
     * IDEA with the Python plugin installed, or Android Studio + Python plugin.
     *
     * Runtime gating is handled by `<depends optional="true" config-file="mindmap-python.xml">`
     * in `plugin.xml`: the `PythonCallableProvider` registration lives in that config-file,
     * and `CallableFunction.Python` is never instantiated unless that provider runs —
     * so on vanilla IntelliJ CE without Python, the class is never referenced and no
     * `NoClassDefFoundError` is thrown for `PyFunction`.
     *
     * FQN strategy: Python doesn't have a single canonical FQN, so we prefer the
     * dotted path (module + qualifiedName). `qualifiedName` gives `ClassName.method`
     * for methods and just `name` for top-level / nested functions.
     */
    data class Python(val fn: PyFunction) : CallableFunction {
        override val name: String? get() = fn.name
        override val fqName: String?
            get() {
                val qualified = fn.qualifiedName
                val modulePath = fn.containingFile?.virtualFile?.nameWithoutExtension
                return when {
                    qualified != null && modulePath != null -> "$modulePath.$qualified"
                    qualified != null -> qualified
                    else -> fn.name
                }
            }
        override val containingFile: PsiFile? get() = fn.containingFile
        // Every PyFunction has a non-null statementList (a body of `pass` at minimum),
        // so outbound traversal is always safe to attempt. Kept as `true` rather than a
        // null check so the contract reads consistently across sealed cases.
        override val hasBody: Boolean get() = true
        override val navigationElement: PsiElement get() = fn
        override val languageId: String get() = "python"
        override val lineCount: Int get() = fn.text?.lines()?.size ?: 0
        // Python parameters are dynamically typed at the source level. Using names
        // alone is sufficient to disambiguate arity (Python disallows overloading by
        // signature anyway — you just get the last definition).
        override val parameterSignature: String
            get() = fn.parameterList.parameters.joinToString(",") { it.name ?: "_" }
        override val signatureDisplay: String
            get() {
                val params = fn.parameterList.parameters.joinToString(", ") { it.name ?: "_" }
                return "${fn.name}($params)"
            }
    }
}
