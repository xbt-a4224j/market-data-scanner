package io.github.xbta4224j.scanner.api

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.xbta4224j.scanner.persistence.PoolDetection
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.beans.factory.ObjectProvider
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service

/**
 * Calls Anthropic Claude to produce a short, reviewer-facing explanation of
 * why a pool detection was scored the way it was. Reads the per-heuristic
 * results JSONB blob, packages a compact prompt, returns a Markdown summary.
 *
 * Tolerant of a missing Anthropic key: the auto-configured ChatModel bean
 * may be absent if `spring.ai.anthropic.api-key` is empty; in that case
 * [summarize] returns an explanatory message rather than throwing.
 *
 * Spring `@Cacheable` against the Caffeine cache so re-rendering the same
 * pool detail page does not re-charge Anthropic credits on every refresh.
 */
@Service
class EvidenceSummaryService(
    private val chatModelProvider: ObjectProvider<ChatModel>,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = ObjectMapper()

    @Cacheable("etherscan-labels", key = "'evsum-' + #pd.id")
    fun summarize(pd: PoolDetection): Summary {
        val chat = chatModelProvider.ifAvailable
        if (chat == null) {
            return Summary(
                markdown = """
                    *Anthropic chat client is not wired.*

                    Set `ANTHROPIC_API_KEY` in `.env` and restart to enable
                    Claude-generated evidence summaries on this page.
                """.trimIndent(),
                generated = false,
            )
        }

        val prompt = buildPrompt(pd)
        return runCatching {
            val response = chat.call(Prompt(prompt)).result.output.text
                ?: return@runCatching Summary(markdown = "_Claude returned an empty response._", generated = false)
            log.info("evidence summary generated for pool {} ({} chars)", pd.id, response.length)
            Summary(markdown = response, generated = true)
        }.getOrElse { err ->
            log.warn("evidence summary call failed for pool {}: {}", pd.id, err.message)
            Summary(
                markdown = "_Claude call failed: ${err.message?.take(200)}_",
                generated = false,
            )
        }
    }

    private fun buildPrompt(pd: PoolDetection): String {
        val resultsJson = mapper.writerWithDefaultPrettyPrinter()
            .writeValueAsString(pd.heuristicResults)
        return """
            You are a blockchain risk analyst reviewing a newly-deployed Uniswap V3 pool.
            Given the per-heuristic results below, write a short Markdown summary
            (3-6 sentences, one paragraph) explaining why this pool was scored at
            ${pd.compositeScore}/100 and whether a reviewer should take action.

            Use plain prose. Do not list every heuristic by name; instead synthesize
            the strongest signals into a narrative. Mention specific numbers
            (top-N holder %, prior-deployment count, LP destination) when they
            are decisive. End with a short "Recommended action" line in italics.

            Token: ${pd.tokenAddress}
            Deployer: ${pd.deployerAddress}
            Paired with: ${pd.pairedWith}
            Composite score: ${pd.compositeScore}
            Flagged signals: ${pd.flaggedSignals.joinToString(", ").ifBlank { "(none)" }}

            Per-heuristic results:
            ```json
            $resultsJson
            ```
        """.trimIndent()
    }

    data class Summary(val markdown: String, val generated: Boolean)
}
