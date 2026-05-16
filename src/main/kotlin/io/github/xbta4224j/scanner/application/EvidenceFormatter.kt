package io.github.xbta4224j.scanner.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.springframework.stereotype.Component

/**
 * Formats heuristic evidence values for the dashboard. Handles three jobs:
 *
 *  1. Pretty-print arbitrary JSON-ish maps (the `rawResults` block on the
 *     pool-detail page) instead of dumping the Java Map.toString.
 *  2. Linkify any value that looks like an Ethereum address or tx hash so
 *     reviewers can pivot to Etherscan with one click.
 *  3. Render a single key-value cell on the heuristic-tab feed without the
 *     `overflow: hidden; ellipsis;` truncation problem - chips wrap.
 *
 * Intentionally stateless and dependency-light so the templates can call
 * `@evidenceFormatter.xxx(...)` via SpEL without ceremony.
 */
@Component
class EvidenceFormatter(private val mapper: ObjectMapper) {

    private val pretty = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT)

    /**
     * Pretty-print `value` as JSON (2-space indented), then linkify addresses
     * and tx hashes to clickable Etherscan anchors. Returns HTML; templates
     * must use `th:utext` (unescaped) to render.
     */
    fun prettyJson(value: Any?): String {
        val json = pretty.writeValueAsString(value ?: emptyMap<String, Any>())
        return linkify(escapeHtml(json))
    }

    /**
     * Render a single evidence value as inline HTML for the per-heuristic
     * feed. Addresses become links; numbers get tabular-num styling; lists
     * become comma-separated chip groups; primitives render as text.
     */
    fun cell(value: Any?): String {
        return when (value) {
            null -> "<span class=\"muted\">null</span>"
            is String -> renderString(value)
            is Boolean -> {
                val cls = if (value) "evidence-true" else "evidence-false"
                "<span class=\"$cls\">$value</span>"
            }
            is Number -> "<span class=\"evidence-num\">${formatNumber(value)}</span>"
            is List<*> -> value.joinToString(separator = "<span class=\"sep\">, </span>") { cell(it) }
            is Map<*, *> -> value.entries.joinToString(separator = " ") { (k, v) ->
                "<span class=\"evidence-kv\"><span class=\"k\">${escapeHtml(k.toString())}</span>" +
                    "<span class=\"v\">${cell(v)}</span></span>"
            }
            else -> escapeHtml(value.toString())
        }
    }

    /**
     * Render the whole evidence map as a row of key/value chips. Used by
     * the heuristic-tab feed instead of dumping `${row.evidence}`.
     */
    fun chips(evidence: Map<*, *>): String {
        if (evidence.isEmpty()) return "<span class=\"muted\">no evidence</span>"
        return evidence.entries.joinToString(separator = " ") { (k, v) ->
            "<span class=\"evidence-kv\">" +
                "<span class=\"k\">${escapeHtml(k.toString())}</span>" +
                "<span class=\"v\">${cell(v)}</span>" +
                "</span>"
        }
    }

    private fun renderString(s: String): String {
        // tx hash (32 bytes hex)
        if (TX_HASH_RE.matches(s)) {
            val short = "${s.take(10)}..${s.takeLast(8)}"
            return "<a class=\"evidence-link\" target=\"_blank\" rel=\"noreferrer\" " +
                "href=\"https://etherscan.io/tx/$s\" title=\"$s\">$short</a>"
        }
        // address (20 bytes hex)
        if (ADDRESS_RE.matches(s)) {
            val short = "${s.take(6)}..${s.takeLast(4)}"
            return "<a class=\"evidence-link\" target=\"_blank\" rel=\"noreferrer\" " +
                "href=\"https://etherscan.io/address/$s\" title=\"$s\">$short</a>"
        }
        return escapeHtml(s)
    }

    /**
     * Walk a string (typically already-pretty-printed JSON) and turn every
     * Ethereum address or tx hash into an Etherscan anchor in place. The
     * input is assumed to already be HTML-escaped; only the address/hash
     * occurrences get wrapped.
     */
    fun linkify(htmlEscaped: String): String {
        var out = htmlEscaped
        out = TX_HASH_RE_LOOSE.replace(out) { m ->
            val full = m.value
            "<a class=\"evidence-link\" target=\"_blank\" rel=\"noreferrer\" " +
                "href=\"https://etherscan.io/tx/$full\" title=\"$full\">$full</a>"
        }
        out = ADDRESS_RE_LOOSE.replace(out) { m ->
            val full = m.value
            "<a class=\"evidence-link\" target=\"_blank\" rel=\"noreferrer\" " +
                "href=\"https://etherscan.io/address/$full\" title=\"$full\">$full</a>"
        }
        return out
    }

    private fun formatNumber(n: Number): String =
        when (n) {
            is Double, is Float -> "%.4f".format(n.toDouble()).trimEnd('0').trimEnd('.')
            else -> n.toString()
        }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    companion object {
        private val ADDRESS_RE = Regex("^0x[a-fA-F0-9]{40}$")
        private val TX_HASH_RE = Regex("^0x[a-fA-F0-9]{64}$")
        // Loose forms used for linkifying addresses/hashes embedded in larger
        // text. Tx hash first - longer match wins.
        private val TX_HASH_RE_LOOSE = Regex("0x[a-fA-F0-9]{64}")
        private val ADDRESS_RE_LOOSE = Regex("0x[a-fA-F0-9]{40}")
    }
}
