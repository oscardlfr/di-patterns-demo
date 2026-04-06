# DI Patterns Demo

Proyecto de demostracion que implementa multiples approaches de inyeccion de dependencias
para SDKs modulares Android (monoliticos y multi-modulo). Incluye benchmarks con Jetpack
Benchmark en dispositivo real (Samsung Galaxy S22 Ultra, Android 16) y documentacion
analitica neutral en espanol.

## Estructura

```
observability-api/              -> SdkLogger (interface)
feature-observability-impl/     -> AndroidSdkLogger + ObservabilityComponent

feature-core-api/         -> SdkConfig
feature-enc-api/          -> EncryptionApi, HashApi
feature-auth-api/         -> AuthApi, AuthToken
feature-stor-api/         -> StorageApi
feature-ana-api/          -> AnalyticsApi
feature-syn-api/          -> SyncApi, SyncResult

feature-core-impl/        -> CoreComponent + buildCoreProvisions()
feature-enc-impl/         -> EncComponent + DefaultEncryptionService + buildEncProvisions() + EncProvider
feature-auth-impl/        -> AuthComponent + DefaultAuthService + buildAuthProvisions() + AuthProvider
feature-stor-impl/        -> StorComponent + DefaultSecureStorageService + buildStorProvisions() + StorProvider
feature-ana-impl/         -> AnaComponent + DefaultAnalyticsService + buildAnaProvisions() + AnaProvider
feature-syn-impl/         -> SynComponent + DefaultSyncService + buildSynProvisions() + SynProvider

sdk/
  api/                    -> Umbrella: CoreApis + re-exports all feature-apis + observability-api
  di-contracts/           -> Provisions + Scopes + RegistryInfra + FeatureProvider + Resolver
  sdk-wiring/             -> Pattern D multi-modulo: direct lazy ensure*()
  wiring-e/               -> Pattern E multi-modulo: ProvisionRegistry + topo-sort
  wiring-e2/              -> Pattern E2 multi-modulo: AutoProvisionRegistry + DFS lazy
  wiring-g/               -> Pattern G multi-modulo: Factory Functions (Components internal)
  wiring-h/               -> Pattern H multi-modulo: Auto-Discovery FeatureProviders (DFS resolver)
  impl-common/            -> Implementaciones compartidas (solo patrones monoliticos)
  impl-koin/              -> KoinSdk (koinApplication aislado, loadModules, auto-discovery)
  impl-dagger-b/          -> DaggerBSdk (Per-Feature Components + CoreApis)
  impl-dagger-c/          -> DaggerCSdk (ServiceLoader + META-INF/services)

sample-dagger-a/    -> Educativo: @Component monolitico
sample-dagger-b/    -> Consumidor de DaggerBSdk
sample-dagger-c/    -> Consumidor de DaggerCSdk
sample-hybrid/      -> KoinSdk + puente Dagger 2
sample-multimodule/ -> Consumidor de MultiModuleSdkH (Pattern H, provision interfaces)

benchmark/          -> 74 Jetpack Microbenchmarks (19 monoliticos via facades + 55 multi-modulo via facades)

docs/               -> 10 documentos tecnicos (espanol)
```

## Feature-API Modules

Las features son modulos TOP-LEVEL (no dentro de `sdk/`). Cada feature tiene su propio
modulo api con las interfaces publicas:

```
:feature-enc-api   -> EncryptionApi, HashApi
:feature-auth-api  -> AuthApi, AuthToken
:feature-stor-api  -> StorageApi
:feature-ana-api   -> AnalyticsApi
:feature-syn-api   -> SyncApi, SyncResult
```

`:observability-api` es un modulo separado con `SdkLogger` (interface).
`:feature-observability-impl` contiene `AndroidSdkLogger` (impl).
`:feature-core-api` contiene solo `SdkConfig` (zero deps).
`:sdk:api` es un umbrella que re-exporta todas las feature-apis + observability-api, asi que
los patrones monoliticos (B, C, Koin) siguen dependiendo de `:sdk:api` sin cambios.
Los feature-impl contienen sus propias `Default*Service` internamente (sin dependencia de impl-common).
`impl-common` solo se usa en los patrones monoliticos.

## API del consumidor

### Multi-Module con Provision Interfaces (ejemplo realista)

```kotlin
// Init -- solo core. Features se construyen on demand.
MultiModuleSdk.init(SdkConfig(debug = true))

// get<T>() auto-construye la cadena de deps via provision interfaces
val auth: AuthApi = MultiModuleSdk.get()    // builds: Core -> Enc -> Auth
val sync: SyncApi = MultiModuleSdk.get()    // builds: Stor + Syn (rest cached)

// La app SOLO depende de :sdk:sdk-wiring (o wiring-e/wiring-e2/wiring-g/wiring-h). Zero imports de feature-impl.
MultiModuleSdk.shutdown()
```

Features dependen de **provision interfaces** (contratos), no de `@Component` (impl).
Cada `feature-xxx-impl` compila independientemente -- solo necesita `sdk:di-contracts` (provision interfaces) + `feature-xxx-api`.

### Multi-Module Pattern G (Factory Functions)

```kotlin
// Misma API que D, pero DaggerXxxComponent es internal en cada feature-impl.
// El wiring llama factory functions en vez de importar DaggerXxx builders.
MultiModuleSdkG.init(SdkConfig(debug = true))
val sync: SyncApi = MultiModuleSdkG.get()  // lazy ensure*() via factory functions
MultiModuleSdkG.shutdown()
```

### Multi-Module Pattern H (Auto-Discovery FeatureProviders)

```kotlin
// Wiring inmutable — descubre FeatureProviders, resuelve deps via DFS.
// Zero edicion central al anadir features.
MultiModuleSdkH.init(SdkConfig(debug = true))
val sync: SyncApi = MultiModuleSdkH.get()  // resolver.provision() auto-builds chain
MultiModuleSdkH.shutdown()
```

### Multi-Module Pattern E2 (Registry + auto-init)

```kotlin
// API minima: init() + get<T>(). Sin Feature enum.
MultiModuleSdkE2.init(SdkConfig(debug = true))
val sync: SyncApi = MultiModuleSdkE2.get()  // auto-builds entire chain
MultiModuleSdkE2.shutdown()
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

## Documentacion

| Documento | Contenido |
|-----------|-----------|
| [Analisis de arquitecturas DI](docs/analisis-arquitecturas-di.md) | Requisitos, cumplimiento, benchmarks S22 Ultra, matriz de decision |
| [Analisis de complejidad y mantenimiento](docs/analisis-complejidad-mantenimiento.md) | Coste por feature, metricas, equipo interno vs consumidores |
| [Dagger 2: approaches A-C + multi-modulo D/E/E2/G/H](docs/dagger2-sdk-selective-init.md) | Monolitico (A, B, C), Multi-Module (D, E, E2, G, H) |
| [Conceptos DI](docs/di-sdk-consumer-isolation.md) | DI vs Service Locator, niveles de aislamiento, singleton survival |
| [Dependencias cruzadas](docs/di-cross-feature-deps.md) | Como resuelve cada approach las cross-deps con ejemplos |
| [Hybrid: Koin SDK + Dagger 2 app](docs/di-hybrid-koin-sdk-dagger-app.md) | Bridge pattern, puente unidireccional, features lazy |
| [Comparacion rapida](docs/di-sdk-selective-init-comparison.md) | Tabla lado-a-lado de frameworks |
| [Multi-modulo api/impl/integration](docs/di-multimodule-api-impl-analysis.md) | Approaches para separacion Gradle estricta + ejemplo realista con provision interfaces (D/E/E2/G/H) |
| [Benchmark results S22 Ultra](docs/benchmark-results-s22-ultra.md) | Resultados completos con tablas por operacion y ranking |
| [Benchmark configuracion](docs/benchmark-configuracion.md) | Guia tecnica para ejecutar e interpretar los 74 benchmarks |

## Stack

Kotlin 2.0.21 | AGP 9.0.1 | Dagger 2.59.2 | Koin 4.1.1 | KSP | Jetpack Benchmark 1.4.0
