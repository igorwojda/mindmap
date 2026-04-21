package com.mindmap.plugin.ui

import com.google.gson.GsonBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.mindmap.plugin.DebugLog
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.mindmap.plugin.analysis.GraphData
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import javax.swing.SwingUtilities

/**
 * Handles all JS<->Kotlin communication over JCEF.
 *
 * Receives JSON messages from the browser via [JBCefJSQuery], parses and validates them
 * into typed [MessageEvent] objects, then forwards them to [onMessage].
 * Sends graph data to the browser by serializing to JSON, Base64-encoding, then calling
 * a JS function with the decoded object. Base64 prevents XSS from node names that
 * contain quotes or script tags.
 */
class JsBridge(
    private val browser: JBCefBrowser,
    private val onMessage: (MessageEvent) -> Unit
) {

    private val gson = GsonBuilder().excludeFieldsWithModifiers(java.lang.reflect.Modifier.TRANSIENT).create()
    private val jsQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    @Volatile private var isDisposed = false
    @Volatile private var isBrowserReady = false
    // @Volatile: written from background threads (markPending), read from CEF thread (onLoadEnd).
    @Volatile private var pendingData: GraphData? = null
    @Volatile private var pendingAction: (() -> Unit)? = null
    // Stored so it can be removed in dispose() to prevent post-dispose callbacks and memory leaks.
    private var loadHandler: CefLoadHandlerAdapter? = null

    companion object {
        private val LOG = Logger.getInstance(JsBridge::class.java)
        private const val MAX_MESSAGE_SIZE = 2048
        private const val MAX_NODE_ID_LENGTH = 500
        private val NODE_ID_PATTERN = Regex("^[a-zA-Z0-9._:\\\\\\-<>,?*@\\[\\] ()]+$")
        private val ALLOWED_SCHEMES = setOf("http", "https")

        // Allow-list of JS entry points the bridge is permitted to invoke. Guards against
        // any future refactor that might pass an attacker-controlled function name into
        // `executeWithEncodedData` — a hard ceiling is more reliable than hoping every
        // call site stays safe forever.
        private val ALLOWED_JS_DISPATCH_FNS = setOf("drawGraph", "mergeGraph")
    }

    /** Typed representation of each incoming JS message type. */
    sealed class MessageEvent {
        /** User clicked a node: navigate editor to that function. */
        data class Navigate(val nodeId: String) : MessageEvent()
        /** User clicked an external link (e.g. help link in the JCEF error panel). */
        data class OpenUrl(val url: String) : MessageEvent()
        /** User expanded a node: rebuild graph centered on that function. */
        data class Expand(val nodeId: String) : MessageEvent()
        /** User triggered trace: overlay callers/callees of that node on the current graph. */
        data class Trace(val nodeId: String) : MessageEvent()
        data object HistoryBack : MessageEvent()
        data object HistoryForward : MessageEvent()
        /** User restarted: clear graph and history. */
        data object Restart : MessageEvent()
        /** User changed depth sliders for normal graph generation. */
        data class SetDepth(val outbound: Int, val inbound: Int) : MessageEvent()
        /** User changed depth sliders specifically for trace operations. */
        data class SetRetraceDepth(val outbound: Int, val inbound: Int) : MessageEvent()
        /** User toggled debug mode in settings. */
        data class SetDebug(val enabled: Boolean) : MessageEvent()
        /** User toggled test function exclusion in filter panel. */
        data class SetExcludeTests(val enabled: Boolean) : MessageEvent()
    }

    /**
     * Registers the JS query handler and installs a load handler that injects the bridge
     * function into the page on every load. The bridge code must be injected in [onLoadEnd]
     * because the page context is not available before that point.
     * [onReady] is called once the page is ready and a pending graph is waiting to be drawn.
     */
    fun setup(onReady: () -> Unit) {
        jsQuery.addHandler { request ->
            try {
                handleRawMessage(request)
            } catch (e: Exception) {
                LOG.error("JS bridge handler error", e); DebugLog.error("JS bridge handler error", e)
            }
            null
        }

        loadHandler = object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                try {
                    val bridgeCode = """
                        window.jbCefQuery = function(arg) { ${jsQuery.inject("arg")} };
                    """.trimIndent()
                    cefBrowser.executeJavaScript(bridgeCode, cefBrowser.url, 0)

                    SwingUtilities.invokeLater {
                        if (isDisposed) return@invokeLater
                        isBrowserReady = true
                        pendingAction?.invoke()
                        pendingAction = null
                        pendingData?.let {
                            onReady()
                            pendingData = null
                        }
                    }
                } catch (e: Exception) {
                    LOG.error("Failed to initialize JS bridge", e); DebugLog.error("Failed to initialize JS bridge", e)
                }
            }
        }
        browser.jbCefClient.addLoadHandler(loadHandler!!, browser.cefBrowser)
    }

    fun loadHtml() {
        // Read resources on a pooled thread to avoid blocking the EDT at startup.
        // `graph.html` is ~124KB, `vis-network.min.js` is ~690KB — together ~0.8MB read
        // once at panel creation. Kept off the EDT to keep the IDE responsive.
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val htmlContent = javaClass.getResource("/webview/graph.html")?.readText()
                val logoSvg = javaClass.getResource("/icons/mindmapActionIcon.svg")?.readText().orEmpty()
                // Inject the action-icon SVG into the welcome screen so the logo stays in sync
                // with the tool-window icon. Add our ei class so welcome-screen CSS sizes it.
                val logoWithClass = logoSvg.replaceFirst("<svg", "<svg class=\"ei\"")
                // Inject the bundled vis-network library inline. We used to load this from
                // https://unpkg.com, which meant any CDN compromise or MITM attack could
                // inject arbitrary JS into the JCEF browser. Bundling the exact pinned
                // version (v9.1.9) removes that entire supply-chain surface.
                //
                // `String.replace(String, String)` in Kotlin is a literal replacement
                // (no regex), so `$` and `\` in the minified JS source are preserved.
                val visNetworkJs = javaClass.getResource("/webview/vis-network.min.js")?.readText().orEmpty()
                val finalHtml = htmlContent
                    ?.replace("<!--WELCOME_LOGO-->", logoWithClass)
                    ?.replace("<!--VIS_NETWORK_JS-->", visNetworkJs)
                SwingUtilities.invokeLater {
                    if (isDisposed) return@invokeLater
                    if (finalHtml != null) {
                        browser.loadHTML(finalHtml)
                    } else {
                        LOG.warn("Could not load graph.html from resources")
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to load HTML", e); DebugLog.error("Failed to load HTML", e)
            }
        }
    }

    val isReady: Boolean get() = isBrowserReady

    fun markPending(data: GraphData) {
        pendingData = data
    }

    fun sendGraph(data: GraphData) {
        executeWithEncodedData(data, "drawGraph")
    }

    /** Preserves existing node positions (used by trace merge). */
    fun sendMergedGraph(data: GraphData) {
        executeWithEncodedData(data, "mergeGraph")
    }

    fun sendHistoryState(canBack: Boolean, canForward: Boolean, position: Int, total: Int) {
        if (isDisposed) return
        SwingUtilities.invokeLater {
            if (isDisposed) return@invokeLater
            try {
                browser.cefBrowser.executeJavaScript(
                    "updateHistoryState($canBack, $canForward, $position, $total)",
                    browser.cefBrowser.url, 0
                )
            } catch (e: Exception) {
                LOG.error("Failed to send history state", e); DebugLog.error("Failed to send history state", e)
            }
        }
    }

    // JSON serialization and Base64 encoding run on a pooled thread so the EDT is never
    // blocked by data processing — only the lightweight JS dispatch hits the EDT.
    //
    // Dispatch hardening:
    // 1. [jsFunction] is allow-listed against [ALLOWED_JS_DISPATCH_FNS]. If a caller ever
    //    passes an unknown name (refactor slip, string concatenation bug), we refuse.
    // 2. The Base64 payload is written into a per-call window property before the
    //    function is invoked, rather than being interpolated inline. This removes the
    //    string-concatenation pattern from executeJavaScript entirely — nothing inside
    //    the JS source text depends on untrusted string content.
    private fun executeWithEncodedData(data: GraphData, jsFunction: String) {
        if (isDisposed) return
        if (jsFunction !in ALLOWED_JS_DISPATCH_FNS) {
            LOG.warn("Refused to dispatch unknown JS function: $jsFunction")
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            if (isDisposed) return@executeOnPooledThread
            try {
                val json = gson.toJson(data)
                val encoded = java.util.Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
                SwingUtilities.invokeLater {
                    if (isDisposed) return@invokeLater
                    try {
                        // Base64 output is [A-Za-z0-9+/=], all JS-safe — no escaping needed
                        // inside the string literal. The payload is stored in a property,
                        // parsed, then deleted so the global namespace stays clean.
                        browser.cefBrowser.executeJavaScript(
                            "(function(){" +
                                "window.__mmPayload='$encoded';" +
                                "try{$jsFunction(JSON.parse(atob(window.__mmPayload)));}" +
                                "finally{delete window.__mmPayload;}" +
                            "})();",
                            browser.cefBrowser.url, 0
                        )
                    } catch (e: Exception) {
                        LOG.error("Failed to execute $jsFunction", e); DebugLog.error("Failed to execute $jsFunction", e)
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to encode graph data for $jsFunction", e); DebugLog.error("Failed to encode graph data for $jsFunction", e)
            }
        }
    }

    private fun handleRawMessage(request: String) {
        if (request.length > MAX_MESSAGE_SIZE) {
            LOG.warn("JS message too large: ${request.length} bytes")
            return
        }
        try {
            // Gson safety note: [RawJsMessage] is a closed data class with concrete
            // primitive/String fields only — no type parameters, no Map/Object fields,
            // no polymorphic dispatch. This closes the common "Gson gadget chain"
            // deserialization vector. Keep it that way: if a field typed
            // `Map<String, Any>` or `Any?` is added, re-review the attack surface.
            val msg = gson.fromJson(request, RawJsMessage::class.java)
            val event = parseMessage(msg) ?: return
            onMessage(event)
        } catch (e: Exception) {
            LOG.warn("Failed to handle JS message", e)
        }
    }

    private fun parseMessage(msg: RawJsMessage): MessageEvent? {
        return when (msg.type) {
            "navigate" -> {
                if (msg.url != null) {
                    validateUrl(msg.url)?.let { MessageEvent.OpenUrl(it) }
                } else {
                    validateNodeId(msg.id)?.let { MessageEvent.Navigate(it) }
                }
            }
            "expand" -> validateNodeId(msg.id)?.let { MessageEvent.Expand(it) }
            "trace" -> validateNodeId(msg.id)?.let { MessageEvent.Trace(it) }
            "history_back" -> MessageEvent.HistoryBack
            "history_forward" -> MessageEvent.HistoryForward
            "restart" -> MessageEvent.Restart
            "set_depth" -> {
                val out = msg.outbound?.coerceIn(1, 5) ?: return null
                val inn = msg.inbound?.coerceIn(1, 5) ?: return null
                MessageEvent.SetDepth(out, inn)
            }
            "set_retrace_depth" -> {
                val out = msg.outbound?.coerceIn(1, 5) ?: return null
                val inn = msg.inbound?.coerceIn(1, 5) ?: return null
                MessageEvent.SetRetraceDepth(out, inn)
            }
            "set_debug" -> MessageEvent.SetDebug(msg.enabled ?: false)
            "set_exclude_tests" -> MessageEvent.SetExcludeTests(msg.enabled ?: true)
            else -> {
                LOG.debug("Unknown JS message type: ${msg.type}")
                null
            }
        }
    }

    // Defense-in-depth: node IDs come from the browser, so we validate length and charset
    // before using them to look up PSI elements. An overly long or script-like ID is rejected.
    private fun validateNodeId(nodeId: String?): String? {
        if (nodeId == null) return null
        if (nodeId.length > MAX_NODE_ID_LENGTH) {
            LOG.warn("Node ID exceeds max length: ${nodeId.length}")
            return null
        }
        if (!NODE_ID_PATTERN.matches(nodeId)) {
            LOG.warn("Invalid node ID format rejected")
            return null
        }
        return nodeId
    }

    /**
     * Allow-list URL validator for `openUrl` dispatch into `BrowserUtil.browse`.
     *
     * Rejects:
     * - anything outside http/https (blocks `javascript:`, `file:`, `data:`, `jar:`,
     *   `intent:`, etc. — schemes that would turn a "browse link" into code execution
     *   or local-file read);
     * - URLs with no authority/host (blocks `http:` with empty host, relative paths);
     * - URLs containing userinfo (`http://evil@example.com` — classic phishing trick
     *   where a victim sees `example.com` in the address bar but credentials are
     *   sent to `evil`).
     */
    private fun validateUrl(url: String): String? {
        return try {
            val uri = java.net.URI(url)
            val scheme = uri.scheme?.lowercase()
            val host = uri.host
            when {
                scheme !in ALLOWED_SCHEMES -> {
                    LOG.warn("Blocked URL with disallowed scheme: ${uri.scheme}")
                    null
                }
                host.isNullOrBlank() -> {
                    LOG.warn("Blocked URL with missing host")
                    null
                }
                uri.userInfo != null -> {
                    LOG.warn("Blocked URL containing userinfo component")
                    null
                }
                else -> url
            }
        } catch (e: Exception) {
            LOG.warn("Invalid URL rejected: $url", e)
            null
        }
    }

    private data class RawJsMessage(
        val type: String = "",
        val id: String? = null,
        val url: String? = null,
        val outbound: Int? = null,
        val inbound: Int? = null,
        val enabled: Boolean? = null
    )

    fun dispose() {
        isDisposed = true
        pendingData = null
        pendingAction = null
        try {
            loadHandler?.let { browser.jbCefClient.removeLoadHandler(it, browser.cefBrowser) }
            loadHandler = null
            jsQuery.dispose()
        } catch (e: Exception) {
            LOG.error("Error disposing JsBridge", e); DebugLog.error("Error disposing JsBridge", e)
        }
    }
}
