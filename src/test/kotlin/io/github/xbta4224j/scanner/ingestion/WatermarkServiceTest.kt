package io.github.xbta4224j.scanner.ingestion

import io.github.xbta4224j.scanner.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class WatermarkServiceTest @Autowired constructor(
    private val watermark: WatermarkService,
) : PostgresIntegrationTest() {

    @Test
    fun `extendForward advances the latest pointer and seeds earliest on first write`() {
        watermark.extendForward(22_600_000, "0xfeed01")

        assertThat(watermark.getLatestProcessed()).isEqualTo(22_600_000)
        assertThat(watermark.getLatestProcessedHash()).isEqualTo("0xfeed01")
        assertThat(watermark.getEarliestProcessed()).isEqualTo(22_600_000)

        watermark.extendForward(22_600_001, "0xfeed02")
        assertThat(watermark.getLatestProcessed()).isEqualTo(22_600_001)
        assertThat(watermark.getEarliestProcessed()).isEqualTo(22_600_000)
    }

    @Test
    fun `extendForward ignores blocks at or below the current latest`() {
        watermark.extendForward(22_600_010, "0xff10")
        watermark.extendForward(22_600_005, "0xff05")  // older, should not move latest

        assertThat(watermark.getLatestProcessed()).isEqualTo(22_600_010)
        assertThat(watermark.getLatestProcessedHash()).isEqualTo("0xff10")
    }

    @Test
    fun `extendBackward advances the earliest pointer downward only`() {
        watermark.extendForward(22_600_020, "0xff20")
        watermark.extendBackward(22_500_000)
        watermark.extendBackward(22_550_000)  // higher than current earliest, should not move it

        assertThat(watermark.getEarliestProcessed()).isEqualTo(22_500_000)
        assertThat(watermark.getLatestProcessed()).isEqualTo(22_600_020)
    }
}
