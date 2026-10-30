package io.github.almeida96.testledger

import kotlinx.serialization.Serializable
import java.math.BigDecimal

@Serializable
data class TimingPolicy(
    val window: Int = 10,
    val minSamples: Int = 3,
    val minIncreaseMs: Int = 100,
    val minIncreasePercent: Int = 50,
) {
    fun validate() {
        require(window in 3..50) { "Timing window must be between 3 and 50 runs" }
        require(minSamples in 3..window) { "minSamples must be between 3 and window" }
        require(minIncreaseMs >= 0 && minIncreasePercent >= 0) { "Timing thresholds must be nonnegative integers" }
    }
}

@Serializable
enum class TimingStatus { CLEAR, REGRESSIONS, INSUFFICIENT_DATA }

@Serializable
data class TimingSample(val runId: String, val seconds: Double, val evidence: String)

@Serializable
data class TimingRegression(
    val testId: String,
    val className: String,
    val name: String,
    val headSeconds: Double,
    val medianSeconds: Double,
    val increaseSeconds: Double,
    val evidence: String,
    val samples: List<TimingSample>,
)

@Serializable
data class TimingPage(
    val head: RunMeta,
    val policy: TimingPolicy,
    val baselineRuns: List<RunMeta>,
    val snapshot: String,
    val status: TimingStatus,
    val totalTests: Int,
    val eligibleTests: Int,
    val evaluatedTests: Int,
    val insufficientSamples: Int,
    val ignoredTests: Int,
    val totalRegressions: Int,
    val regressions: List<TimingRegression>,
    val nextOffset: Int?,
    val note: String = "Only passed tests with positive durations and no retry hints are compared. Both thresholds must be met. Timings from different commits or environments are not controlled benchmarks; a slowdown does not establish its cause.",
)

internal data class TimingReading(val testId: String, val seconds: Double)
internal data class TimingChange(val testId: String, val headSeconds: Double, val median: BigDecimal, val increase: BigDecimal)

// Decimal arithmetic keeps inclusive millisecond thresholds and even-sized medians exact.
// It also avoids overflow when an otherwise valid XML duration is close to Double.MAX_VALUE.
internal fun timingChange(testId: String, head: Double, samples: DoubleArray, policy: TimingPolicy): TimingChange? {
    val sorted = samples.filter { it > 0 }.sorted()
    if (sorted.size < policy.minSamples) return null
    val middle = sorted.size / 2
    val median = if (sorted.size % 2 == 1) BigDecimal.valueOf(sorted[middle]) else
        BigDecimal.valueOf(sorted[middle - 1]).add(BigDecimal.valueOf(sorted[middle])).divide(BigDecimal(2))
    val increase = BigDecimal.valueOf(head).subtract(median)
    val absolute = BigDecimal.valueOf(policy.minIncreaseMs.toLong(), 3)
    val relative = median.multiply(BigDecimal.valueOf(policy.minIncreasePercent.toLong(), 2))
    return if (increase.signum() > 0 && increase >= absolute && increase >= relative)
        TimingChange(testId, head, median, increase) else null
}
