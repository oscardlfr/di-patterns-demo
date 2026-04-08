package com.grinwich.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Scale benchmark — measures DI pattern overhead at 10, 50, 100, 200, 500 features.
 *
 * Uses Int-keyed harnesses that replicate the exact HashMap + DFS logic of:
 * - [ResolverHarness] (H/I/J style) — implicit deps via build() callbacks
 * - [RegistryHarness] (E2 style) — explicit deps declared upfront
 *
 * Int keys avoid the limitation of generating unique Class<*> objects at runtime
 * (object : Any() {}.javaClass inside a lambda returns the same class for all iterations).
 *
 * D/G cannot be tested at scale — their when-block/factory approach requires
 * hardcoded code per feature. This benchmark proves why registry/resolver patterns
 * are needed for 100+ features.
 *
 * Dependency graph shapes:
 * - LINEAR: 0->1->2->...->N (worst-case chain depth)
 * - TREE: each feature depends on 1-3 earlier features (realistic SDK)
 * - DIAMOND: heavy shared deps (features converge on a few roots)
 */
@RunWith(AndroidJUnit4::class)
class ScaleBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    // ════════════════════════════════════════════════════════
    // Graph generators
    // ════════════════════════════════════════════════════════

    /** LINEAR: 0<-1<-2<-...<-N. Worst case DFS depth. */
    private fun linearDeps(i: Int): Set<Int> =
        if (i == 0) emptySet() else setOf(i - 1)

    /** TREE: each feature depends on 1-3 random earlier features. */
    private fun treeDeps(i: Int, rng: Random): Set<Int> {
        if (i == 0) return emptySet()
        val count = minOf(rng.nextInt(1, 4), i)
        return (0 until i).shuffled(rng).take(count).toSet()
    }

    /** DIAMOND: features 0-4 are roots, all others depend on 2-3 roots + 0-1 peer. */
    private fun diamondDeps(i: Int, rng: Random): Set<Int> {
        if (i < 5) return if (i == 0) emptySet() else setOf(0)
        val roots = (0 until 5).shuffled(rng).take(rng.nextInt(2, 4)).toSet()
        val peer = if (i > 5 && rng.nextBoolean()) setOf(rng.nextInt(5, i)) else emptySet()
        return roots + peer
    }

    // ════════════════════════════════════════════════════════
    // Resolver harness (H/I/J style — implicit deps via build)
    // ════════════════════════════════════════════════════════

    /**
     * Replicates the Resolver's HashMap<Class, Provider> + DFS pattern
     * using Int keys. Iterative DFS to avoid StackOverflow on deep chains.
     */
    private class ResolverHarness(n: Int, depsFn: (Int) -> Set<Int>) {
        private val deps = (0 until n).associateWith { depsFn(it) }
        private val built = HashSet<Int>(n * 2)

        fun resolve(id: Int) {
            if (built.contains(id)) return
            // Iterative DFS — same traversal as Resolver.ensureBuilt but stack-safe
            val buildOrder = ArrayDeque<Int>()
            val visited = HashSet<Int>()
            val stack = ArrayDeque<Int>()
            stack.addLast(id)

            while (stack.isNotEmpty()) {
                val current = stack.removeLast()
                if (built.contains(current) || !visited.add(current)) continue
                buildOrder.addFirst(current)
                for (dep in deps[current] ?: emptySet()) {
                    if (!built.contains(dep) && dep !in visited) {
                        stack.addLast(dep)
                    }
                }
            }

            for (node in buildOrder) {
                // Simulate provision build: verify deps, mark built
                built.add(node)
            }
        }

        fun clear() = built.clear()
    }

    private fun resolverBench(n: Int, depsFn: (Int) -> Set<Int>) {
        val harness = ResolverHarness(n, depsFn)
        benchmarkRule.measureRepeated {
            harness.resolve(n - 1) // Resolve last feature (deepest in graph)
            runWithMeasurementDisabled { harness.clear() }
        }
    }

    // ── Linear chain ──

    @Test fun resolver_linear_10() = resolverBench(10) { linearDeps(it) }
    @Test fun resolver_linear_50() = resolverBench(50) { linearDeps(it) }
    @Test fun resolver_linear_100() = resolverBench(100) { linearDeps(it) }
    @Test fun resolver_linear_200() = resolverBench(200) { linearDeps(it) }
    @Test fun resolver_linear_500() = resolverBench(500) { linearDeps(it) }

    // ── Tree (realistic SDK) ──

    @Test fun resolver_tree_10() = resolverBench(10) { treeDeps(it, Random(42)) }
    @Test fun resolver_tree_50() = resolverBench(50) { treeDeps(it, Random(42)) }
    @Test fun resolver_tree_100() = resolverBench(100) { treeDeps(it, Random(42)) }
    @Test fun resolver_tree_200() = resolverBench(200) { treeDeps(it, Random(42)) }
    @Test fun resolver_tree_500() = resolverBench(500) { treeDeps(it, Random(42)) }

    // ── Diamond (heavy shared roots) ──

    @Test fun resolver_diamond_10() = resolverBench(10) { diamondDeps(it, Random(42)) }
    @Test fun resolver_diamond_50() = resolverBench(50) { diamondDeps(it, Random(42)) }
    @Test fun resolver_diamond_100() = resolverBench(100) { diamondDeps(it, Random(42)) }
    @Test fun resolver_diamond_200() = resolverBench(200) { diamondDeps(it, Random(42)) }
    @Test fun resolver_diamond_500() = resolverBench(500) { diamondDeps(it, Random(42)) }

    // ════════════════════════════════════════════════════════
    // Registry harness (E2 style — explicit deps declared upfront)
    // ════════════════════════════════════════════════════════

    /**
     * Replicates AutoProvisionRegistry's HashMap + iterative DFS pattern.
     * Deps declared upfront (vs implicit in Resolver), built on demand.
     */
    private class RegistryHarness(n: Int, depsFn: (Int) -> Set<Int>) {
        private val catalog = (0 until n).associateWith { depsFn(it) }
        private val built = HashSet<Int>(n * 2)

        fun resolve(id: Int) {
            if (built.contains(id)) return
            // Iterative DFS — same as AutoProvisionRegistry.ensureBuilt
            val buildOrder = ArrayDeque<Int>()
            val visited = HashSet<Int>()
            val stack = ArrayDeque<Int>()
            stack.addLast(id)

            while (stack.isNotEmpty()) {
                val current = stack.removeLast()
                if (built.contains(current) || !visited.add(current)) continue
                buildOrder.addFirst(current)
                for (dep in catalog[current] ?: emptySet()) {
                    if (!built.contains(dep) && dep !in visited) {
                        stack.addLast(dep)
                    }
                }
            }

            for (node in buildOrder) {
                built.add(node)
            }
        }

        fun clear() = built.clear()
    }

    private fun registryBench(n: Int, depsFn: (Int) -> Set<Int>) {
        val harness = RegistryHarness(n, depsFn)
        benchmarkRule.measureRepeated {
            harness.resolve(n - 1) // Resolve last entry (triggers full DFS)
            runWithMeasurementDisabled { harness.clear() }
        }
    }

    // ── Linear chain ──

    @Test fun registry_linear_10() = registryBench(10) { linearDeps(it) }
    @Test fun registry_linear_50() = registryBench(50) { linearDeps(it) }
    @Test fun registry_linear_100() = registryBench(100) { linearDeps(it) }
    @Test fun registry_linear_200() = registryBench(200) { linearDeps(it) }
    @Test fun registry_linear_500() = registryBench(500) { linearDeps(it) }

    // ── Tree (realistic SDK) ──

    @Test fun registry_tree_10() = registryBench(10) { treeDeps(it, Random(42)) }
    @Test fun registry_tree_50() = registryBench(50) { treeDeps(it, Random(42)) }
    @Test fun registry_tree_100() = registryBench(100) { treeDeps(it, Random(42)) }
    @Test fun registry_tree_200() = registryBench(200) { treeDeps(it, Random(42)) }
    @Test fun registry_tree_500() = registryBench(500) { treeDeps(it, Random(42)) }

    // ── Diamond (heavy shared roots) ──

    @Test fun registry_diamond_10() = registryBench(10) { diamondDeps(it, Random(42)) }
    @Test fun registry_diamond_50() = registryBench(50) { diamondDeps(it, Random(42)) }
    @Test fun registry_diamond_100() = registryBench(100) { diamondDeps(it, Random(42)) }
    @Test fun registry_diamond_200() = registryBench(200) { diamondDeps(it, Random(42)) }
    @Test fun registry_diamond_500() = registryBench(500) { diamondDeps(it, Random(42)) }

    // ════════════════════════════════════════════════════════
    // Full graph resolution — resolve ALL N features
    // ════════════════════════════════════════════════════════

    private fun resolverFullBench(n: Int, depsFn: (Int) -> Set<Int>) {
        val harness = ResolverHarness(n, depsFn)
        benchmarkRule.measureRepeated {
            for (i in 0 until n) harness.resolve(i)
            runWithMeasurementDisabled { harness.clear() }
        }
    }

    @Test fun resolver_fullGraph_tree_10() = resolverFullBench(10) { treeDeps(it, Random(42)) }
    @Test fun resolver_fullGraph_tree_50() = resolverFullBench(50) { treeDeps(it, Random(42)) }
    @Test fun resolver_fullGraph_tree_100() = resolverFullBench(100) { treeDeps(it, Random(42)) }
    @Test fun resolver_fullGraph_tree_200() = resolverFullBench(200) { treeDeps(it, Random(42)) }
    @Test fun resolver_fullGraph_tree_500() = resolverFullBench(500) { treeDeps(it, Random(42)) }

    // ════════════════════════════════════════════════════════
    // Selective resolution — resolve 1 of N (measures partial DFS)
    // ════════════════════════════════════════════════════════

    private fun resolverSelectiveBench(n: Int, depsFn: (Int) -> Set<Int>) {
        val harness = ResolverHarness(n, depsFn)
        benchmarkRule.measureRepeated {
            harness.resolve(n / 2) // Middle of graph — partial cascade
            runWithMeasurementDisabled { harness.clear() }
        }
    }

    @Test fun resolver_selective_tree_100() = resolverSelectiveBench(100) { treeDeps(it, Random(42)) }
    @Test fun resolver_selective_tree_500() = resolverSelectiveBench(500) { treeDeps(it, Random(42)) }
}
