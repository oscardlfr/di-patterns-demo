package com.grinwich.benchmark

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * JUnit Rule that filters benchmark tests by pattern name.
 *
 * Reads `patterns` from instrumentation arguments. If set, only tests whose
 * method name contains a matching pattern suffix will run. Unmatched tests
 * are skipped via [Assume.assumeTrue] (reported as "ignored", not "failed").
 *
 * Usage:
 * ```bash
 * # Run only Pattern L and M tests:
 * ./gradlew :benchmark:connectedReleaseAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.patterns=L,M
 *
 * # Run all patterns (default — no filter):
 * ./gradlew :benchmark:connectedReleaseAndroidTest
 * ```
 *
 * Pattern detection: extracts the pattern letter(s) from the test method name.
 * Methods like `initCold_L`, `crossFeatureOp_H_fake`, `leakDetection_E2` are
 * matched. Methods without a pattern suffix (e.g., `heapFootprint_comparative`)
 * always run.
 */
class PatternFilterRule : TestRule {

    override fun apply(base: Statement, description: Description): Statement {
        val args = InstrumentationRegistry.getArguments()
        val filterArg = args.getString("patterns") ?: return base // no filter = run all

        val enabledPatterns = filterArg.split(",").map { it.trim() }.toSet()
        if (enabledPatterns.isEmpty()) return base

        val methodName = description.methodName ?: return base
        val detectedPattern = extractPattern(methodName)

        // Methods without a recognized pattern always run (e.g., heapFootprint_comparative)
        if (detectedPattern == null) return base

        // Skip if pattern not in filter
        if (detectedPattern !in enabledPatterns) {
            return object : Statement() {
                override fun evaluate() {
                    Assume.assumeTrue(
                        "Pattern '$detectedPattern' not in filter $enabledPatterns — skipped",
                        false,
                    )
                }
            }
        }

        return base
    }

    companion object {
        /** Known pattern names from ALL_LAZY_SDKS + monolithic patterns. */
        private val KNOWN_PATTERNS = setOf(
            // Multi-module
            "D", "E2", "G", "H", "I", "J", "K",
            "L", "M", "N", "O", "P", "Q",
            "O2", "P2", "Q2",
            // Monolithic
            "daggerB", "daggerC", "koinSdk", "hybrid",
        )

        /**
         * Extract pattern name from test method name.
         *
         * Splits by `_` and finds the first segment that matches a known pattern.
         * Returns null if no pattern is found (test runs unconditionally).
         *
         * Examples:
         * - "initCold_L" → "L"
         * - "crossFeatureOp_H_fake" → "H"
         * - "leakDetection_E2" → "E2"
         * - "heapFootprint_comparative" → null (always runs)
         */
        fun extractPattern(methodName: String): String? {
            val segments = methodName.split("_")
            // Check segments from index 1 onward (index 0 is the scenario prefix)
            for (i in 1 until segments.size) {
                val segment = segments[i]
                if (segment in KNOWN_PATTERNS) return segment
                // Handle compound like "daggerB" in segment
                if (KNOWN_PATTERNS.any { segment == it }) return segment
            }
            return null
        }
    }
}
