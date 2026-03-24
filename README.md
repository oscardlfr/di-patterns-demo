# DI Patterns Demo

Proyecto de demostración que implementa 5 approaches de inyección de dependencias
para SDKs modulares Android. Incluye benchmarks con Jetpack Benchmark en dispositivo real
y documentación analítica neutral en español.

## Estructura

```
sdk/
  api/              → Interfaces puras (0 dependencias DI)
  impl-common/      → Implementaciones compartidas
  impl-koin/        → KoinSdk (koinApplication aislado, loadModules, auto-discovery)
  impl-dagger-b/    → DaggerBSdk (Per-Feature Components + CoreApis)
  impl-dagger-c/    → DaggerCSdk (ServiceLoader + META-INF/services)
  impl-dagger-d/    → DaggerSdk (Component Dependencies — cross-deps automáticas)

sample-dagger-a/    → Educativo: @Component monolítico
sample-dagger-b/    → Consumidor de DaggerBSdk
sample-dagger-c/    → Consumidor de DaggerCSdk
sample-dagger-d/    → Consumidor de DaggerSdk (D)
sample-hybrid/      → KoinSdk + puente Dagger 2

benchmark/          → 30 Jetpack Microbenchmarks

docs/               → 7 documentos técnicos (español)
```

## API del consumidor

Todos los SDKs exponen la misma interfaz:

```kotlin
// Inicializar con features seleccionadas
DaggerSdk.init(SdkConfig(debug = true), setOf(Feature.ENCRYPTION))

// Lazy init con cascada automática
DaggerSdk.getOrInitModule(Feature.SYNC)  // → Auth → Encryption → Storage

// Resolver servicio
val sync = DaggerSdk.get<SyncService>()

// Apagar
DaggerSdk.shutdown()
```

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
| [Dagger 2: approaches A, B, C, D](docs/dagger2-sdk-selective-init.md) | Monolítico, Per-Feature, ServiceLoader, Component Dependencies |
| [Conceptos DI](docs/di-sdk-consumer-isolation.md) | DI vs Service Locator, niveles de aislamiento, singleton survival |
| [Dependencias cruzadas](docs/di-cross-feature-deps.md) | Cómo resuelve cada approach las cross-deps con ejemplos |
| [Hybrid: Koin SDK + Dagger 2 app](docs/di-hybrid-koin-sdk-dagger-app.md) | Bridge pattern, puente unidireccional, features lazy |
| [Comparación rápida](docs/di-sdk-selective-init-comparison.md) | Tabla lado-a-lado de frameworks |

## Stack

Kotlin 2.0.21 | AGP 9.0.1 | Dagger 2.59.2 | Koin 4.1.1 | KSP | Jetpack Benchmark 1.4.0
