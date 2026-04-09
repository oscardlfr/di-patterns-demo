package com.grinwich.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Scale benchmark — measures the cost of DFS graph traversal at 10, 50, 100, 200, 500 features.
 *
 * ## Que mide
 *
 * Cuando un SDK tiene 500 features en vez de 7, cuanto tarda el Resolver en
 * recorrer el grafo de dependencias y construir las provisions? Este benchmark
 * aisla el coste del DFS (HashMap lookups + iteracion) del coste del DI framework
 * (Dagger codegen, ServiceLoader scan, DataStore I/O).
 *
 * ## Dos motores de resolucion
 *
 * - **ResolverHarness** (simula Pattern H/I/J/K): las dependencias son implicitas.
 *   Cada feature declara sus deps en su funcion build() y el Resolver las descubre
 *   via DFS. Es el patron de FeatureProvider.build(resolver) -> resolver.provision(dep).
 *
 * - **RegistryHarness** (simula Pattern E2): las dependencias son explicitas.
 *   Cada feature las declara upfront en un Set<Int>. El registry hace DFS iterativo
 *   usando esas declaraciones. Es el patron de AutoProvisionEntry.dependencies.
 *
 * Ambos usan Int keys en vez de Class<*> porque no se pueden generar N clases
 * unicas en runtime en Android (object : Any() {}.javaClass devuelve la misma clase).
 * El algoritmo (HashMap + DFS iterativo) es identico al de produccion.
 *
 * ## Tres formas de grafo
 *
 * - **LINEAR** (peor caso): `0 <- 1 <- 2 <- ... <- N`. Cadena de N niveles.
 *   Si pides el feature 499, el DFS tiene que recorrer 499 nodos en linea.
 *   Ejemplo real: feature que depende de otro que depende de otro (raro en SDKs).
 *
 * - **TREE** (caso realista): cada feature depende de 1-3 features anteriores
 *   seleccionados al azar (seed 42 para reproducibilidad). Simula un SDK real
 *   donde Auth depende de Enc, Sync depende de Auth+Stor+Enc, etc.
 *   Profundidad tipica: ~log(N) niveles.
 *
 * - **DIAMOND** (deps compartidas): features 0-4 son raices, todos los demas
 *   dependen de 2-3 raices + 0-1 peer. Simula SDKs donde muchos features
 *   comparten Core, Logger, Config (pocos nodos base, muchos consumidores).
 *   El DFS visita las raices una vez y las cachea.
 *
 * ## Que NO mide
 *
 * - ServiceLoader scan (no hay ServiceLoader)
 * - Dagger/kotlin-inject codegen (no hay @Component)
 * - DataStore I/O (no hay Context ni disco)
 * - Thread-safety (no hay synchronized, es single-threaded)
 *
 * ## Por que D y G no aparecen
 *
 * D y G usan when-blocks y ensure*() hardcodeados -- necesitas escribir codigo
 * por cada feature. No puedes generar 500 features programaticamente.
 * Este benchmark demuestra por que los patrones de registry/resolver son
 * necesarios para SDKs con 100+ features.
 */
@RunWith(AndroidJUnit4::class)
class ScaleBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    // ════════════════════════════════════════════════════════
    // Graph generators — devuelven el Set de dependencias
    // para el feature en posicion i
    // ════════════════════════════════════════════════════════

    /**
     * LINEAR: 0 <- 1 <- 2 <- ... <- N
     *
     * Cada feature depende del anterior. Peor caso para DFS:
     * resolver el feature N requiere recorrer N nodos en cadena.
     *
     * Ejemplo con N=5:
     *   feature 0: sin deps
     *   feature 1: depende de {0}
     *   feature 2: depende de {1}  (transitivamente: 1 -> 0)
     *   feature 3: depende de {2}  (transitivamente: 2 -> 1 -> 0)
     *   feature 4: depende de {3}  (transitivamente: 3 -> 2 -> 1 -> 0)
     *   Resolver feature 4 = DFS de 5 niveles de profundidad.
     */
    private fun linearDeps(i: Int): Set<Int> =
        if (i == 0) emptySet() else setOf(i - 1)

    /**
     * TREE: cada feature depende de 1-3 features anteriores (aleatorio, seed 42).
     *
     * Simula un SDK real: Auth depende de Enc, Sync depende de Auth+Stor+Enc.
     * Profundidad tipica: ~log(N). Con 500 features, la profundidad maxima es ~15-20.
     *
     * Ejemplo con N=5, seed 42:
     *   feature 0: sin deps
     *   feature 1: depende de {0}
     *   feature 2: depende de {0, 1}  (2 deps aleatorias)
     *   feature 3: depende de {1}     (1 dep aleatoria)
     *   feature 4: depende de {0, 2, 3} (3 deps aleatorias)
     *   Resolver feature 4 = DFS de ~3 niveles (4 -> 2 -> 1 -> 0, con cache hits).
     */
    private fun treeDeps(i: Int, rng: Random): Set<Int> {
        if (i == 0) return emptySet()
        val count = minOf(rng.nextInt(1, 4), i) // 1-3 deps, maximo i
        return (0 until i).shuffled(rng).take(count).toSet()
    }

    /**
     * DIAMOND: features 0-4 son raices compartidas. Features 5+ dependen de 2-3 raices + 0-1 peer.
     *
     * Simula SDKs donde muchos features comparten Core, Logger, Config (pocas bases, muchos consumidores).
     * El DFS visita las raices una vez y las cachea — benchmarkea el coste del cache hit.
     *
     * Ejemplo con N=8:
     *   features 0-4: raices (0 sin deps, 1-4 dependen de {0})
     *   feature 5: depende de {0, 2, 3} (2-3 raices)
     *   feature 6: depende de {1, 4, 5} (2 raices + 1 peer)
     *   feature 7: depende de {0, 3}    (2 raices, sin peer)
     *   Resolver feature 7 = DFS de 2 niveles (7 -> 0 y 3, ambas raices -> cache hit).
     */
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
     * Simula el Resolver de los patrones H/I/J/K.
     *
     * En produccion, el Resolver guarda providers en un HashMap<Class, FeatureProvider>
     * y construye provisions bajo demanda con DFS recursivo. Aqui replicamos la misma
     * logica pero con Int keys y DFS iterativo (evita StackOverflow en cadenas de 500+).
     *
     * - `deps`: mapa de {feature -> sus dependencias}. Generado una vez antes del benchmark.
     * - `built`: set de features ya construidos. Se limpia entre iteraciones.
     * - `resolve(id)`: DFS iterativo que encuentra el orden de build (post-order)
     *   y marca cada nodo como construido. Simula el coste de `Resolver.ensureBuilt()`.
     *
     * Lo que mide: coste de N HashMap.contains() + N HashSet.add() + DFS traversal.
     * Lo que NO mide: coste de DaggerComponent.builder().build() (eso es constante por feature).
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

    /**
     * Benchmark: construir el feature mas profundo del grafo (posicion N-1).
     *
     * El DFS recorre desde N-1 hasta las raices, construyendo cada nodo
     * una sola vez. Entre iteraciones se limpia el set de construidos
     * (fuera de la medicion) para medir cold-build en cada repeticion.
     */
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
     * Simula el AutoProvisionRegistry del patron E2.
     *
     * En produccion, E2 usa AutoProvisionEntry con deps declaradas upfront
     * en un Set<Class>. El registry hace DFS iterativo post-order usando esas
     * declaraciones. Aqui replicamos la misma logica con Int keys.
     *
     * Diferencia con ResolverHarness: en el Resolver (H/I/J) las deps son
     * implicitas (descubiertas durante build()). En el Registry (E2) las deps
     * son explicitas (declaradas antes de build). El DFS es identico, la
     * diferencia es cuando se conocen las deps (compile-time vs runtime).
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
    //
    // Simula el caso de un SDK que necesita TODOS sus features
    // activos (ej: e2eStartup donde se resuelven los 6 servicios).
    // Con 500 features, mide el coste de construir el grafo entero.
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
    //
    // Simula el caso tipico: un SDK con 500 features pero la app
    // solo usa 1. Resuelve el feature en la posicion N/2 (mitad del
    // grafo) — construye solo sus dependencias transitivas, no todo.
    // Demuestra que el DFS lazy funciona a escala: no pagas por
    // features que no usas.
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
