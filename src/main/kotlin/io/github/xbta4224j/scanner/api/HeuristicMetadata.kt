package io.github.xbta4224j.scanner.api

/**
 * Per-heuristic display metadata for the dashboard tabs. Lives separate
 * from the [io.github.xbta4224j.scanner.analysis.RiskHeuristic] beans so
 * the UI can render the planned-signal text for stubbed heuristics without
 * the heuristic itself having to expose human-facing copy.
 */
object HeuristicMetadata {

    data class Entry(
        val key: String,
        val title: String,
        val tagline: String,
        val implemented: Boolean,
        val description: String,
        val plannedSignal: String,
        val displayHint: String,
    )

    val ALL: List<Entry> = listOf(
        Entry(
            key = "supply_concentration",
            title = "Supply Concentration",
            tagline = "Top-K holder distribution + Gini coefficient",
            implemented = true,
            description = """
                Walks Transfer events on the new token from its creation block to the
                pool-creation block, replays the holder balances, and reports
                top-1 / top-3 / top-10 percentages plus the Gini coefficient. Burn
                addresses are excluded so a deploy-time burn does not look like
                concentrated holding.
            """.trimIndent(),
            plannedSignal = "",
            displayHint = "histogram",
        ),
        Entry(
            key = "lp_lock",
            title = "LP Lock",
            tagline = "Initial-liquidity destination classification",
            implemented = true,
            description = """
                Reads the first Mint event on the pool, classifies the position-NFT
                owner as deployer-held (high rug risk), burned, locked in a known
                timelock contract (Unicrypt / Team.Finance / PinkLock / Mudra),
                routed via NPM, or unknown.
            """.trimIndent(),
            plannedSignal = "",
            displayHint = "donut",
        ),
        Entry(
            key = "deployer_history",
            title = "Deployer History",
            tagline = "Serial scam-factory deployer fingerprint",
            implemented = true,
            description = """
                Pulls the deployer EOA's full prior contract-creation history from
                Etherscan, computes count + densest 24h burst, and scores. A
                first-time deployer is mildly suspicious by default; an EOA with
                50+ priors and high burst rate scores high-confidence adversarial.
            """.trimIndent(),
            plannedSignal = "",
            displayHint = "table",
        ),
        Entry(
            key = "metadata_similarity",
            title = "Metadata Similarity",
            tagline = "Embedding cosine vs legitimate-token corpus",
            implemented = false,
            description = "",
            plannedSignal = "Spring AI embedding cosine similarity between the new token's name + symbol + description and the curated legitimate-token corpus (Issue #19). Catches impersonation attacks like \"USDC2\" or \"uSDC\".",
            displayHint = "stub",
        ),
        Entry(
            key = "funding_flow",
            title = "Funding Flow",
            tagline = "Deployer funding-source attribution",
            implemented = false,
            description = "",
            plannedSignal = "Trace the deployer EOA's funding source back N hops; flag mixer touches (Tornado Cash, Sinbad), sanctioned-entity exposure (OFAC SDN), and overlap with known scam-cluster wallets.",
            displayHint = "stub",
        ),
        Entry(
            key = "source_code_pattern",
            title = "Source Code",
            tagline = "Solidity AST scan for known rug patterns",
            implemented = false,
            description = "",
            plannedSignal = "When the contract is verified on Etherscan, AST-parse the source for hidden fees activated after N blocks, whitelist transfer modifiers, owner-only mint without timelock, suspicious rescue functions.",
            displayHint = "stub",
        ),
        Entry(
            key = "first_n_buyers",
            title = "First-N Buyers",
            tagline = "Sybil-pump funding-source clustering",
            implemented = false,
            description = "",
            plannedSignal = "For the first 20 buyers of the token, trace their funding sources. Many buyers funded from a single source within a short window = Sybil pump pattern.",
            displayHint = "stub",
        ),
        Entry(
            key = "social_media_correlation",
            title = "Social Media",
            tagline = "Cross-source mention correlation",
            implemented = false,
            description = "",
            plannedSignal = "Cross-reference token name + contract address against Telegram / X mentions in the prior hour. Out of scope for the chain-side focus of this repo; scaffolded for completeness.",
            displayHint = "stub",
        ),
    )

    val BY_KEY: Map<String, Entry> = ALL.associateBy { it.key }
}
