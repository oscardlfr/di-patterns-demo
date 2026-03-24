package com.grinwich.sdk.api

/**
 * Captures timing for SDK operations across all approaches.
 * Each sample app uses this to show comparable metrics.
 */
object SdkBenchmark {

    data class Timing(val label: String, val durationMs: Double)

    private val _timings = mutableListOf<Timing>()

    fun clear() = _timings.clear()

    fun <T> measure(label: String, block: () -> T): T {
        val start = System.nanoTime()
        val result = block()
        val elapsed = (System.nanoTime() - start) / 1_000_000.0
        _timings.add(Timing(label, elapsed))
        return result
    }

    fun report(): String = buildString {
        appendLine("┌─────────────────────────────────────────────────┐")
        appendLine("│ PERFORMANCE METRICS                             │")
        appendLine("├──────────────────────────────┬──────────────────┤")
        appendLine("│ Operation                    │ Time (ms)        │")
        appendLine("├──────────────────────────────┼──────────────────┤")
        for (t in _timings) {
            val label = t.label.padEnd(28)
            val time = "%.3f".format(t.durationMs).padStart(16)
            appendLine("│ $label │ $time │")
        }
        appendLine("├──────────────────────────────┼──────────────────┤")
        val total = _timings.sumOf { it.durationMs }
        appendLine("│ ${"TOTAL".padEnd(28)} │ ${"%.3f".format(total).padStart(16)} │")
        appendLine("└──────────────────────────────┴──────────────────┘")
    }
}
