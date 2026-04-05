# DI Patterns Demo

Proyecto de demostración que implementa 8 approaches de inyección de dependencias
para SDKs modulares Android. Incluye benchmarks con Jetpack Benchmark en dispositivo real
(Samsung Galaxy S22 Ultra, Android 16) y documentación analítica neutral en español.

## Estructura

```
sdk/
  api/              → Interfaces puras (0 dependencias DI)
  impl-common/      → Implementaciones compartidas
  impl-koin/        → KoinSdk (koinApplication aislado, loadModules, auto-discovery)
  impl-dagger-b/    → DaggerBSdk (Per-Feature Components + CoreApis)
  impl-dagger-c/    → DaggerCSdk (ServiceLoader + META-INF/services)
  impl-dagger-d/    → DaggerSdk (Component Dependencies — cross-deps automáticas)
  impl-dagger-e/    → RegistrySdk (Component Registry — explicit bindings, auto topo-sort)
  impl-dagger-e2/   → AutoSdk (Auto-Init Registry — evolución de E, sin Feature enum)
  di-core/          → CoreComponent compartido (para multi-módulo F)
  impl-dagger-f/    → ModularSdk (Multi-Module Component Dependencies — D en multi-módulo)

sample-dagger-a/    → Educativo: @Component monolítico
sample-dagger-b/    → Consumidor de DaggerBSdk
sample-dagger-c/    → Consumidor de DaggerCSdk
sample-dagger-d/    → Consumidor de DaggerSdk (D)
sample-dagger-e/    → Consumidor de RegistrySdk (E)
sample-dagger-e2/   → Consumidor de AutoSdk (E2)
sample-dagger-f/    → Consumidor de ModularSdk (F)
sample-hybrid/      → KoinSdk + puente Dagger 2

benchmark/          → 50 Jetpack Microbenchmarks

docs/               → 7 documentos técnicos (español)
```

## API del consumidor

### Pattern E2 — Auto-Init Registry (API más limpia)

```kotlin
// Init — solo config, sin selección de features
AutoSdk.init(SdkConfig(debug = true))

// Pedir un servicio — auto-inicializa toda la cadena de deps
val sync = AutoSdk.get<SyncService>()  // auto: Core → Enc → Auth → Storage → Sync

// Apagar
AutoSdk.shutdown()
```

Sin `Feature` enum. Sin `getOrInitModule()`. El consumidor solo ve `init()` + `get<T>()`.

### Pattern E — Component Registry (control explícito)

```kotlin
// Init con features seleccionadas
RegistrySdk.init(SdkConfig(debug = true), setOf(Feature.ENCRYPTION))

// Lazy init con cascada automática
RegistrySdk.getOrInitModule(Feature.SYNC)

// Resolver servicio
val sync = RegistrySdk.get<SyncService>()

// Apagar
RegistrySdk.shutdown()
```

El consumidor ve el `Feature` enum — control más granular a cambio de
API ligeramente más compleja.

## Compilar

```bash
./gradlew assembleDebug
```

## Ejecutar benchmarks

```bash
./gradlew :benchmark:connectedReleaseAndroidTest
```

Resultados en `benchmark/build/outputs/connected_android_test_additional_output/`.

## Documentación

| Documento | Contenido |
|-----------|-----------|
| [Análisis de arquitecturas DI](docs/analisis-arquitecturas-di.md) | Requisitos, cumplimiento, benchmarks S22 Ultra, matriz de decisión |
| [Análisis de complejidad y mantenimiento](docs/analisis-complejidad-mantenimiento.md) | Coste por feature, métricas, equipo interno vs consumidores |
| [Dagger 2: approaches A–F](docs/dagger2-sdk-selective-init.md) | Monolítico, Per-Feature, ServiceLoader, Component Dependencies, Registry, Auto-Init, Multi-Module |
| [Conceptos DI](docs/di-sdk-consumer-isolation.md) | DI vs Service Locator, niveles de aislamiento, singleton survival |
| [Dependencias cruzadas](docs/di-cross-feature-deps.md) | Cómo resuelve cada approach las cross-deps con ejemplos |
| [Hybrid: Koin SDK + Dagger 2 app](docs/di-hybrid-koin-sdk-dagger-app.md) | Bridge pattern, puente unidireccional, features lazy |
| [Comparación rápida](docs/di-sdk-selective-init-comparison.md) | Tabla lado-a-lado de frameworks |
| [Multi-módulo api/impl/integration](docs/di-multimodule-api-impl-analysis.md) | Qué approaches funcionan con separación Gradle estricta |

## Stack

Kotlin 2.0.21 | AGP 9.0.1 | Dagger 2.59.2 | Koin 4.1.1 | KSP | Jetpack Benchmark 1.4.0
