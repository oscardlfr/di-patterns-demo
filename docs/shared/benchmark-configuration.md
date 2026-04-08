# Configuracion de Benchmarks

Guia tecnica para ejecutar, interpretar y configurar los Jetpack Benchmarks del proyecto.

---

## Ejecutar

```bash
# Todos los tests (dispositivo real recomendado)
./gradlew :benchmark:connectedReleaseAndroidTest

# Clase especifica
./gradlew :benchmark:connectedReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.grinwich.benchmark.MultiModuleBenchmark

# Varias clases
./gradlew :benchmark:connectedReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.grinwich.benchmark.DiBenchmark,com.grinwich.benchmark.MultiModuleBenchmark

# Emulador (valido para comparaciones relativas, no valores absolutos)
./gradlew :benchmark:connectedReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.profiling.mode=None
```

El flag `profiling.mode=None` desactiva method tracing. Recomendado en emulador para evitar ENOSPC.

---

## Clases de Test

| Clase | Tests | Tipo | Patrones | Output |
|-------|-------|------|----------|--------|
| **DiBenchmark** | 19 | Jetpack Benchmark | Monoliticos: B, C, Koin, Hybrid | benchmarkData.json |
| **MultiModuleBenchmark** | 84 | Jetpack Benchmark | Multi-modulo: D, E2, G, H, I, J, K | benchmarkData.json |
| **ScaleBenchmark** | 37 | Jetpack Benchmark | Resolver (H/I/J) vs Registry (E2) | benchmarkData.json |
| **MemoryBehaviorTest** | 57 | JUnit assertions | Multi-modulo: D, E2, G, H, I, J, K | pass/fail + logcat |
| **StressTortureTest** | 80 | JUnit assertions | Multi-modulo: D, E2, G, H, I, J, K | pass/fail + logcat |
| **Total** | **277** | | | |

### DiBenchmark (19 tests)

Microbenchmarks de patrones monoliticos usando facades reales.

| Categoria | Tests | Patrones |
|-----------|-------|----------|
| initCold | 4 | B, C, Koin, Hybrid |
| resolveFirst | 4 | B, C, Koin, Hybrid |
| lazyInit noDeps | 4 | B, C, Koin, Hybrid |
| lazyInit cascade | 4 | B, C, Koin, Hybrid |
| crossFeatureOp | 3 | B, C, Koin |

### MultiModuleBenchmark (84 tests)

Microbenchmarks de 7 patrones multi-modulo (12 categorias x 7 patrones).

| Categoria | Que mide |
|-----------|----------|
| initCold | Grafo DI completo (6 features) desde cero |
| resolveFirst | Primer acceso a singleton |
| lazyInit noDeps | Anadir Analytics (0 cross-deps) |
| lazyInit cascade | Anadir Sync (3 cross-deps) |
| crossFeatureOp | Sync.sync() cruzando Auth+Stor+Enc |
| stress_initShutdown | Ciclo init-get-shutdown |
| stress_concurrent | 4 threads get<T>() simultaneo |
| stress_resolveAll | 6 servicios secuenciales |
| stress_selective | Solo 1 feature de 6 |
| stress_reInit | Dos ciclos completos |
| stress_incremental | Features una a una |
| e2eStartup | Startup realista: init + resolve all + ops |

### ScaleBenchmark (37 tests)

Benchmarks de escalabilidad con features sinteticas (10, 50, 100, 200, 500 features).

| Engine | Patrones que representa | Grafos |
|--------|------------------------|--------|
| Resolver | H, I, J | linear, tree, diamond |
| AutoProvisionRegistry | E2 | linear, tree, diamond |

**D y G excluidos:** sus when-blocks/ensure*() requieren codigo hardcodeado por feature.
Este benchmark demuestra por que los patrones de registry/resolver son necesarios a 100+ features.

Tambien incluye tests de fullGraph (resolver todos los N features) y selective (resolver 1 de N).

### MemoryBehaviorTest (57 tests)

Tests deterministas (assertions, no benchmarks) que verifican laziness y memoria:

| Categoria | Que verifica |
|-----------|-------------|
| initOnly | Provisions minimas tras init (D/G = 1, E2/H/I/J = 0) |
| getEnc | Solo Encryption + deps construido |
| getAna | Analytics independiente, sin cascada |
| getSync | Cascada completa (Auth+Stor+Enc+Sync) |
| fullGraph | Todas las provisions construidas |
| shutdown | Todas las provisions liberadas |
| freshInstances | Reinit produce instancias nuevas |
| leakDetection | 1000 ciclos, delta heap < 2048 KB |
| heapFootprint | Comparativa de heap entre los 7 patrones |

### StressTortureTest (80 tests)

Tests de stress extremo (incluyendo 3 tests de concurrencia: concurrentBuild,
concurrentSelective, concurrentShutdown) que verifican correctness bajo presion:

| Categoria | Que hace |
|-----------|---------|
| thunderingHerd | 100 threads concurrentes en barrera |
| singletonIdentity | 10,000 get() devuelven misma instancia |
| crossPatternIsolation | 7 patrones simultaneos, zero contaminacion |
| rapidFire | 5,000 ciclos init/get/shutdown |
| memoryPressure | GC storm durante resolucion |
| stress10K | 10,000 ciclos con heap delta < 5120 KB |
| instanceFreshness | 50 reinits, todas instancias unicas |
| errorResilience | Double init, get antes de init, etc. |
| functionalCorrectness | Operaciones reales tras 1000 reinits |
| coldCascadeTiming | Comparativa de timing entre los 7 patrones |
| alternatingPatterns | 100 rondas alternando entre patrones |

---

## Capturar Logcat (MemoryBehaviorTest / StressTortureTest)

Los tests de asercion emiten tablas comparativas via `Log.d()`:

```bash
# Capturar durante la ejecucion
adb logcat -s HEAP_COMPARE:D TORTURE:D LEAK_D:D LEAK_E2:D LEAK_G:D LEAK_H:D LEAK_I:D LEAK_J:D
```

---

## Outputs

Cada test de BenchmarkRule genera ficheros en:
```
benchmark/build/outputs/connected_android_test_additional_output/
  releaseAndroidTest/connected/<device>/
```

### Tipos de ficheros

| Fichero | Extension | Peso | Que contiene | Cuando usarlo |
|---------|-----------|------|--------------|---------------|
| **Perfetto trace** | `.perfetto-trace` | ~1-2 MB | Timeline: CPU, GC, locks, I/O | Abrir con [Perfetto UI](https://ui.perfetto.dev/) |
| **Method trace** | `.trace` | ~5-10 MB | Call stack con timestamps | Solo si `profiling.mode` activo |
| **Benchmark JSON** | `-benchmarkData.json` | ~50 KB | Medianas, min, max, percentiles | Para `benchmark-summary.py` |
| **Benchmark TXT** | `*.txt` | ~1 KB | Resumen legible | Lectura rapida |

---

## Script de Resumen

```bash
# Resultados del ultimo run
python3 scripts/benchmark-summary.py

# Fichero especifico
python3 scripts/benchmark-summary.py path/to/benchmarkData.json
```

Genera tablas agrupadas por operacion con ranking y comparativa.
Soporta patrones: B, C, Koin, Hybrid (monoliticos) + D, E2, G, H, I, J, K (multi-modulo).
Incluye seccion de scale benchmarks (Resolver vs Registry).

---

## Configuracion del Proyecto

### build.gradle.kts (benchmark module)

```kotlin
android {
    defaultConfig {
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] =
            "EMULATOR,LOW-BATTERY,ACTIVITY-MISSING"
        testInstrumentationRunnerArguments["disableAnalytics"] = "true"
    }
    testBuildType = "release"
    buildTypes {
        release {
            isDefault = true
            isMinifyEnabled = false  // sin R8 -- mide codigo real
        }
    }
}
```

### Por que release sin minify?

- **release**: sin debuggable flag, sin overhead de debug checks
- **isMinifyEnabled = false**: R8 inlinea y optimiza. Si lo activas, mides el codigo optimizado, no el que ejecuta la app en desarrollo

### suppressErrors

| Error | Que significa | Por que suprimir |
|-------|---------------|-----------------|
| `EMULATOR` | Corriendo en emulador | Numeros sirven para comparar approaches |
| `LOW-BATTERY` | Bateria baja | Plugged-in es aceptable |
| `ACTIVITY-MISSING` | No hay Activity | Benchmarks no necesitan UI |

---

## Dispositivo Especifico

```bash
# Solo S22 Ultra
./gradlew :benchmark:connectedReleaseAndroidTest \
  -Dcom.android.build.gradle.internal.test.DEVICE_SERIAL=R3CT30KAMEH

# Listar devices
adb devices
```

---

## Interpretar Resultados

- **Mediana** (no media): ignora outliers por GC o thermal throttle
- **ns** (nanosegundos): 1 frame = 16,666,666 ns. Init de 3,500 ns = 0.0002 frames. Koin init ~50,000 ns = 0.003 frames
- **vs Best**: ratio contra el approach mas rapido

**Regla practica:** todo lo que este por debajo de 1 ms es invisible para el usuario.
La eleccion entre approaches es de arquitectura, no de rendimiento.
