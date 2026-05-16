package io.github.xbta4224j.scanner.config

import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.cache.CacheManager
import org.springframework.cache.caffeine.CaffeineCache
import org.springframework.cache.support.SimpleCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.time.Duration

/**
 * Per-cache TTL config. The application.yml `spring.cache.caffeine.spec` only
 * lets us set ONE spec across all caches, which forced `etherscan-labels`
 * (slow-changing) and `coingecko-spot` (slow-changing) to share the
 * 5-minute TTL we picked for the freshest case. Net effect: too many
 * outbound calls, hitting CoinGecko + Etherscan + Infura rate limits.
 *
 * This bean replaces the auto-config with a SimpleCacheManager carrying
 * one Caffeine cache per name with a TTL appropriate to that data.
 *
 * - `coingecko-spot`     30 min - ETH price; daily-ish granularity is fine
 *                                 for risk scoring.
 * - `deployer-history`   30 min - historical txlist; scam fingerprints
 *                                 don't churn within a 30-minute window.
 * - `etherscan-labels`   30 min - name tags + contract creations are
 *                                 written once and rarely revised.
 */
@Configuration
class CacheConfig {

    @Bean
    @Primary
    fun cacheManager(): CacheManager {
        val mgr = SimpleCacheManager()
        mgr.setCaches(
            listOf(
                buildCache("coingecko-spot", ttl = Duration.ofMinutes(30), maxSize = 1_000),
                buildCache("deployer-history", ttl = Duration.ofMinutes(30), maxSize = 10_000),
                buildCache("etherscan-labels", ttl = Duration.ofMinutes(30), maxSize = 10_000),
            )
        )
        return mgr
    }

    private fun buildCache(name: String, ttl: Duration, maxSize: Long): CaffeineCache =
        CaffeineCache(
            name,
            Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maxSize)
                .recordStats()
                .build()
        )
}
