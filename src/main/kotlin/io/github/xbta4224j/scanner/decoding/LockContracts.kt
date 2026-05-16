package io.github.xbta4224j.scanner.decoding

/**
 * Known LP-token / position-NFT lock contracts on Ethereum mainnet. When the
 * first Mint of a pool credits a position to one of these addresses, the LP
 * is timelocked rather than rug-able.
 *
 * Addresses are 0x-prefixed lowercase. They should be verified against the
 * lockers' official documentation before any production use; this list is a
 * best-effort starting point.
 */
object LockContracts {

    /** Unicrypt liquidity locker (multiple deployments over time; this is the V3 one). */
    const val UNICRYPT_V3 = "0xfd235968e65b0990584585763f837a5b5330e6de"

    /** Team.Finance (formerly UniLocker) - widely-used LP lock service. */
    const val TEAM_FINANCE = "0xe2fe530c047f2d85298b07d9333c05737f1435fb"

    /** PinkSale lock - companion to PinkSale launchpad. */
    const val PINKLOCK = "0x71b5759d73262fbb223956913ecf4ecc51057641"

    /** Mudra Locker. */
    const val MUDRA_LOCKER = "0x4ac28a40fa77faca22ee30c5e16d44dee23df110"

    /** Uniswap V3 NonfungiblePositionManager - holds positions until they are minted as NFTs. */
    const val UNISWAP_V3_NPM = "0xc36442b4a4522e871399cd717abdd847ab11fe88"

    val ALL_LOCKS: Set<String> = setOf(UNICRYPT_V3, TEAM_FINANCE, PINKLOCK, MUDRA_LOCKER)

    const val ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"
    const val DEAD_ADDRESS = "0x000000000000000000000000000000000000dead"
    val BURN_SINKS: Set<String> = setOf(ZERO_ADDRESS, DEAD_ADDRESS)
}
