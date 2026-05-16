package io.github.xbta4224j.scanner.application

/**
 * Per-heuristic display metadata for the dashboard tabs. Lives separate from
 * the [io.github.xbta4224j.scanner.query.RiskHeuristic] beans so the UI can
 * render the planned-signal text for stubbed heuristics without the
 * heuristic itself having to expose human-facing copy.
 *
 * `description` and `plannedSignal` are rendered with `th:utext` (unescaped),
 * so embedded HTML (`<a>` for citations, `<code>` for token addresses /
 * Solidity selectors, `<strong>` for named patterns) is allowed. Only this
 * file emits HTML into those fields - no end-user input ever flows through.
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
        /**
         * Three-stage pseudocode strip rendered above the prose on the
         * heuristic tab. Order: Inputs, Source, Score. Lets a senior
         * reviewer skim the data-lineage in one glance without reading
         * the description paragraph.
         */
        val pipeline: Pipeline? = null,
    )

    data class Pipeline(val inputs: String, val source: String, val score: String)

    val ALL: List<Entry> = listOf(
        Entry(
            key = "supply_concentration",
            title = "Supply Concentration",
            tagline = "Top-K holder distribution + Gini coefficient",
            implemented = true,
            pipeline = Pipeline(
                inputs = "token address, creation block, pool-creation block",
                source = "eth_getLogs(Transfer) replayed into a balance map",
                score = "weighted(top1 share, top3 share, Gini); confidence by holder count",
            ),
            description = """
                <p>Walks every <code>Transfer</code> event on the new token between its
                creation block and the V3 pool-creation block, replays each address's
                running balance, and reports <strong>top-1 / top-3 / top-10 holder
                share</strong> alongside the <strong>Gini coefficient</strong> (the
                canonical measure of distribution inequality).</p>
                <p><em>Why it matters.</em> Cernera, La Morgia, Mei &amp; Sassi's USENIX
                Security 2023 study of token launches across Ethereum and BSC
                (<a href="https://arxiv.org/abs/2206.08202" target="_blank" rel="noreferrer">"Token Spammers, Rug Pulls, and SniperBots"</a>)
                found that the overwhelming majority of confirmed rug pulls park
                &gt;90% of supply in one or two deployer-controlled wallets at launch
                and exit within hours. This heuristic surfaces that pattern at the
                latest still-pre-rug moment - the block the V3 pool is created.</p>
                <p>Burn addresses (<code>0x000...dead</code>, <code>0x000...000</code>)
                are excluded so a deploy-time supply burn (a legitimate fixed-supply
                pattern) does not look like concentration.</p>
            """.trimIndent(),
            plannedSignal = "",
            displayHint = "histogram",
        ),
        Entry(
            key = "lp_lock",
            title = "LP Lock",
            tagline = "Initial-liquidity destination + USD value",
            implemented = true,
            pipeline = Pipeline(
                inputs = "pool address, first Mint tx",
                source = "eth_getLogs(Mint) + NonfungiblePositionManager.ownerOf + CoinGecko spot",
                score = "destination class (deployer/burn/lock) gated by USD liquidity floor",
            ),
            description = """
                <p>Decodes the first <code>Mint</code> event on the V3 pool, reads the
                position-NFT owner from the
                <a href="https://docs.uniswap.org/contracts/v3/reference/periphery/NonfungiblePositionManager"
                   target="_blank" rel="noreferrer">Uniswap NonfungiblePositionManager</a>,
                and classifies the destination into one of: <strong>deployer-held</strong>
                (high rug risk), <strong>burned</strong>, <strong>locked</strong> in a known
                third-party timelock (Unicrypt, Team.Finance, PinkLock, Mudra),
                routed via the NPM, or <strong>unknown</strong>.</p>
                <p><em>Why it matters.</em> The combination of <strong>deployer-held LP +
                thin initial liquidity (under ~$5k USD)</strong> is the highest-precision
                rug-pull indicator in the academic record - see Mazorra, Adan &amp;
                Daza's gradient-boosted classifier in
                <a href="https://www.mdpi.com/2227-7390/10/6/949" target="_blank" rel="noreferrer">"Do Not Rug on Me" (Mathematics, MDPI, 2022)</a>,
                where this two-feature combination dominated all per-pool features they
                evaluated. The initial-liquidity USD value (priced via the WETH /
                stable side of the pair) is reported alongside the destination so a
                reviewer sees both signals together.</p>
            """.trimIndent(),
            plannedSignal = "",
            displayHint = "donut",
        ),
        Entry(
            key = "deployer_history",
            title = "Deployer History",
            tagline = "Serial scam-factory deployer fingerprint",
            implemented = true,
            pipeline = Pipeline(
                inputs = "deployer EOA",
                source = "Etherscan getContractCreations + name-tag lookup (Caffeine-cached 30m)",
                score = "min(1.0, priorCount/50) * 0.6 + burst24hFactor * 0.4; +0.2 if tagged",
            ),
            description = """
                <p>Pulls the deployer EOA's full prior contract-creation history from
                Etherscan's <code>txlist</code> endpoint, computes
                <strong>prior-deployment count</strong>, the
                <strong>densest 24-hour burst</strong> (max contracts deployed in any
                rolling 24h window), and any Etherscan name tag
                (<code>Fake_Phishing*</code>, <code>SCAM</code>, <code>Heist</code>, etc.).</p>
                <p><em>Why it matters.</em> Deployer-side features carry the highest
                individual feature importance in the published scam-detection
                literature: in
                <a href="https://www.mdpi.com/2227-7390/10/6/949" target="_blank" rel="noreferrer">"Do Not Rug on Me" (Mazorra et al, 2022)</a>,
                prior-deployment count + burst rate ranked above any per-token feature
                in the gradient-boosted classifier. The
                <a href="https://www.chainalysis.com/crypto-crime-report/" target="_blank" rel="noreferrer">Chainalysis Crypto Crime Report</a>
                consistently identifies a small number of recidivist deployer
                addresses (often Etherscan-labelled <code>Fake_Phishing*</code>) as
                responsible for the long tail of low-quality token launches each
                quarter. A first-time deployer is mildly suspicious by default; an EOA
                with 50+ priors and a 20+ contract burst scores high-confidence
                adversarial.</p>
                <p><em>Validation.</em> The unit test pins this heuristic against a
                known <code>Fake_Phishing*</code>-labelled factory address and asserts
                confidence &gt; 0.85 - regressing this assertion blocks merge.</p>
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
            plannedSignal = """
                <p>Spring AI embedding-cosine similarity between the new token's
                <code>name + symbol + description</code> and a curated legit-token
                corpus (top-100 CoinGecko tokens, ingested through the corpus
                pipeline + pgvector store; see ADR-004).</p>
                <p>Catches impersonation tokens like <code>USDC2</code>, <code>uSDC</code>,
                <code>Tetherr</code>, <code>Wrapped Etherr</code> -
                <a href="https://www.chainalysis.com/crypto-crime-report/" target="_blank" rel="noreferrer">Chainalysis</a>
                has documented this lookalike-naming pattern as the dominant phishing
                vector for new-token approval scams from 2023 onward. Embedding cosine
                works where naive Levenshtein fails because it is robust to homoglyph
                substitutions and capitalisation tricks.</p>
                <p><em>Status:</em> corpus is live (3 of top-100 ingested today,
                pipeline is the same wrapper that fills the rest); the embedding-
                compare call in the heuristic body is the small remaining piece.</p>
            """.trimIndent(),
            displayHint = "stub",
        ),
        Entry(
            key = "funding_flow",
            title = "Funding Flow",
            tagline = "Deployer funding-source attribution",
            implemented = false,
            description = "",
            plannedSignal = """
                <p>Trace the deployer EOA's funding source back N hops via
                <code>eth_getTransactionByHash</code> on the deployer's first
                inbound tx; flag (a) <strong>mixer touches</strong> -
                <a href="https://home.treasury.gov/policy-issues/financial-sanctions/recent-actions/20220808"
                   target="_blank" rel="noreferrer">Tornado Cash</a> router contracts,
                Railgun, Sinbad; (b) addresses on the
                <a href="https://sanctionssearch.ofac.treas.gov/" target="_blank" rel="noreferrer">OFAC SDN list</a>;
                (c) overlap with internal known-scam-cluster wallets.</p>
                <p>Mixer-funded deployers correlate strongly with rug intent in the
                public datasets - this is the mechanism scam operators use to break
                forensic linkage between launches. Cernera et al's
                <a href="https://arxiv.org/abs/2206.08202" target="_blank" rel="noreferrer">SniperBot analysis</a>
                noted that scam-token deployers reuse a small set of mixer-exit
                addresses in tight bursts, which is the per-heuristic signal here.</p>
            """.trimIndent(),
            displayHint = "stub",
        ),
        Entry(
            key = "source_code_pattern",
            title = "Source Code",
            tagline = "Solidity AST scan for known rug patterns",
            implemented = false,
            description = "",
            plannedSignal = """
                <p>When the contract is verified on Etherscan, AST-parse the Solidity
                source for known rug-template patterns: <strong>delayed hidden fees</strong>
                (transfer-fee parameter activated after N blocks);
                <strong>whitelist transfer modifiers</strong> (only owner-blessed addresses
                can sell); <strong>owner-only mint without timelock</strong>; suspicious
                <code>rescueTokens()</code> / <code>emergencyWithdraw()</code> functions
                that drain the LP.</p>
                <p>Pattern set anchored to the published rug-template literature:
                Cernera et al's <a href="https://arxiv.org/abs/2206.08202" target="_blank" rel="noreferrer">2023 USENIX paper</a>
                cataloged ~12 reusable Solidity templates that account for the
                majority of low-effort rug deployments. The verified-contract case
                covers maybe 30% of new launches; the rest fall through this
                heuristic and rely on the on-chain signals instead.</p>
            """.trimIndent(),
            displayHint = "stub",
        ),
        Entry(
            key = "first_n_buyers",
            title = "First-N Buyers",
            tagline = "Sybil-pump funding-source clustering",
            implemented = false,
            description = "",
            plannedSignal = """
                <p>For the first 20 buyers of the token, trace each buyer's funding
                source back to the closest common ancestor address. Many buyers
                funded from a single ancestor within a tight time window =
                <strong>Sybil pump</strong> pattern.</p>
                <p>The pattern is well-characterised in
                <a href="https://arxiv.org/abs/2206.08202" target="_blank" rel="noreferrer">Cernera et al, 2023</a>:
                snipe-bot operators run dozens to hundreds of pre-funded wallets that
                trade against each new launch in the first ~10 blocks, manufacturing
                an organic-looking volume profile. The funding-source clustering is
                what gives them away even when the buy-side wallet addresses are
                fresh.</p>
            """.trimIndent(),
            displayHint = "stub",
        ),
        Entry(
            key = "social_media_correlation",
            title = "Social Media",
            tagline = "Cross-source mention correlation",
            implemented = false,
            description = "",
            plannedSignal = """
                <p>Cross-reference token name + contract address against Telegram /
                X mentions in the hour preceding pool creation. A coordinated mention
                burst from low-follower accounts that share funding lineage is the
                off-chain analogue of the First-N-Buyers cluster signal.</p>
                <p><em>Out of scope</em> for this repo's chain-only focus (it would
                need a separate ingestion path against the X API + a Telegram
                client). Scaffolded so the composite-scorer's weight vector documents
                the full intended battery and the dashboard renders a "Stub" card
                instead of pretending the heuristic does not exist.</p>
            """.trimIndent(),
            displayHint = "stub",
        ),
    )

    val BY_KEY: Map<String, Entry> = ALL.associateBy { it.key }
}
