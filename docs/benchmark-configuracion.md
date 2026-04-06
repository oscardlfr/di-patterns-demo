# Configuracion de Benchmarks

Guia tecnica para ejecutar, interpretar y configurar los Jetpack Benchmarks del proyecto.

---

## Ejecutar

```bash
# Dispositivo real (recomendado para numeros de produccion)
./gradlew :benchmark:connectedReleaseAndroidTest

# Emulador (valido para comparaciones relativas, no valores absolutos)
./gradlew :benchmark:connectedReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.profiling.mode=None
```

El flag `profiling.mode=None` desactiva method tracing (ver seccion "Outputs"). Recomendado en emulador para evitar ENOSPC.

---

## Outputs

Cada test genera ficheros en el dispositivo y los copia a:
```
benchmark/build/outputs/connected_android_test_additional_output/
  releaseAndroidTest/connected/<device>/
```

### Tipos de ficheros

| Fichero | Extension | Peso | Que contiene | Cuando usarlo |
|---------|-----------|------|--------------|---------------|
| **Perfetto trace** | `.perfetto-trace` | ~1-2 MB | Timeline de sistema: scheduling, CPU, locks, GC, I/O | Siempre se genera. Abrir con [Perfetto UI](https://ui.perfetto.dev/) o Android Studio Profiler |
| **Method trace** | `.trace` | ~5-10 MB | Call stack completo: cada funcion con timestamps de entrada/salida | Solo se genera si `profiling.mode` != `None`. Abrir con Android Studio Profiler |
| **Benchmark JSON** | `-benchmarkData.json` | ~50 KB | Medianas, min, max, percentiles de cada test | Para scripts automaticos (`benchmark-summary.py`) |
| **Benchmark TXT** | `additionaltestoutput.benchmark.message_*.txt` | ~1 KB | Resumen legible por humano de cada test | Lectura rapida de resultados |

### Perfetto trace (.perfetto-trace)

Muestra la timeline del sistema durante el benchmark:
- **CPU scheduling**: en que core corrio, cuanto tiempo en cada slice
- **GC events**: pausas del garbage collector
- **Lock contention**: threads esperando locks
- **Binder transactions**: IPC overhead

Util para entender **por que** un approach es mas lento — ej: si H muestra mas GC que G, el overhead es por allocations del HashMap.

Como abrir:
1. Android Studio: File > Open > seleccionar `.perfetto-trace`
2. Web: subir a [ui.perfetto.dev](https://ui.perfetto.dev/)

### Method trace (.trace)

Graba CADA llamada a funcion con timestamps:
```
MultiModuleSdkH.get()              0-3500 ns
  └─ Resolver.get()                100-3400 ns
       └─ ensureBuilt()            200-3300 ns
            └─ EncProvider.build()  300-2800 ns
                 └─ DaggerEncComponent.builder()  400-2500 ns
```

Util para localizar **donde exactamente** se gasta el tiempo dentro de una funcion. Pero pesa mucho y distorsiona las mediciones (el propio tracing añade overhead).

Como abrir:
1. Android Studio: File > Open > seleccionar `.trace`
2. Navegar el flame chart / call tree

**Recomendacion:** desactivar por defecto, activar solo para investigar un test concreto.

### Benchmark JSON (-benchmarkData.json)

Formato estructurado con todos los resultados:
```json
{
  "benchmarks": [
    {
      "name": "initCold_multiModuleH",
      "metrics": {
        "timeNs": {
          "median": 3500.0,
          "min": 3200.0,
          "max": 4100.0,
          "standardDeviation": 150.0
        }
      }
    }
  ]
}
```

Consumido por `scripts/benchmark-summary.py` para generar tablas comparativas.

---

## Configuracion del proyecto

### build.gradle.kts (benchmark module)

```kotlin
android {
    defaultConfig {
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"

        // Suppress checks que fallan en emulador/bateria baja
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] =
            "EMULATOR,LOW-BATTERY,ACTIVITY-MISSING"

        // Desactivar analytics de benchmark
        testInstrumentationRunnerArguments["disableAnalytics"] = "true"
    }

    // IMPORTANTE: benchmarks corren en release (no debug)
    testBuildType = "release"

    buildTypes {
        release {
            isDefault = true
            isMinifyEnabled = false  // sin R8 — mide el codigo real, no optimizado
        }
    }
}
```

### Por que release sin minify?

- **release**: sin debuggable flag, sin overhead de debug checks
- **isMinifyEnabled = false**: R8 inlinea y optimiza codigo. Si lo activas, mides el codigo optimizado — que no es lo que ejecuta la app en desarrollo. Para benchmarks de DI init, quieres medir el codigo tal cual.

### suppressErrors

| Error | Que significa | Por que suprimir |
|-------|---------------|-----------------|
| `EMULATOR` | Corriendo en emulador | Numeros no son de produccion pero sirven para comparar approaches |
| `LOW-BATTERY` | Bateria baja | Thermal throttle puede afectar, pero en plugged-in es aceptable |
| `ACTIVITY-MISSING` | No hay Activity visible | Benchmarks no necesitan UI |

---

## Script de resumen

```bash
# Resultados del ultimo run
python3 scripts/benchmark-summary.py

# Fichero especifico
python3 scripts/benchmark-summary.py path/to/benchmarkData.json
```

Genera tablas agrupadas por tipo de operacion (initCold, resolveFirst, etc.) con ranking y comparativa entre approaches.

---

## Ejecucion en dispositivo especifico

Si hay multiples dispositivos conectados:

```bash
# Solo S22 Ultra
./gradlew :benchmark:connectedReleaseAndroidTest \
  -Dcom.android.build.gradle.internal.test.DEVICE_SERIAL=R3CT30KAMEH

# Listar devices
adb devices
```

---

## Tests disponibles

| Categoria | Tests | Que mide |
|-----------|-------|----------|
| **Init Cold** | 5 (D/E/E2/G/H) | Crear grafo DI completo desde cero |
| **Resolve First** | 5 | Primer acceso a un singleton |
| **Lazy Init (no deps)** | 5 | Anadir Analytics (0 cross-deps) |
| **Lazy Init (cascade)** | 5 | Anadir Sync (3 cross-deps: Auth+Stor+Enc) |
| **Cross-Feature Op** | 5 | Sync.sync() tocando Auth+Stor+Enc |
| **Stress: Init/Shutdown** | 5 | Ciclo init→get→shutdown repetido |
| **Stress: Concurrent** | 5 | 4 threads llamando get<T>() simultaneamente |
| **Stress: Resolve All** | 5 | 6 servicios resueltos secuencialmente |
| **Stress: Selective** | 5 | Solo 1 feature de 6 (las otras no se construyen) |
| **Stress: Re-Init** | 5 | Dos ciclos completos init→get all→shutdown |
| **Stress: Incremental** | 5 | Features anadidas una a una |
| **Monoliticos** | 19 | B, C, Koin, Hybrid via facades reales |
| **Total** | **74** | |

---

## Interpretar resultados

- **Mediana** (no media): ignora outliers por GC o thermal throttle
- **ns** (nanosegundos): 1 frame = 16,666,666 ns. Si init es 3500 ns = 0.0002 frames
- **us** (microsegundos): 1000 ns. Koin init ~50 us = 0.003 frames
- **vs Best**: ratio contra el approach mas rapido. 3.5x no significa "3.5 veces peor para el usuario" — ambos son imperceptibles

**Regla practica:** todo lo que este por debajo de 1 ms es invisible para el usuario. La eleccion entre approaches es de arquitectura, no de rendimiento.
