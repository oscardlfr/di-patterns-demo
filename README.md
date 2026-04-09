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
  wiring-i/               -> Pattern I multi-modulo: Pure Resolver (zero DI framework)
  wiring-j/               -> Pattern J multi-modulo: kotlin-inject (KSP, genera Kotlin)
  wiring-k/               -> Pattern K multi-modulo: AndroidManifest Discovery (Firebase-style)
  impl-common-d-c/        -> Implementaciones compartidas (solo patrones monoliticos)
  impl-koin/              -> KoinSdk (koinApplication aislado, loadModules, auto-discovery)
  impl-dagger-b/          -> DaggerBSdk (Per-Feature Components + CoreApis)
  impl-dagger-c/          -> DaggerCSdk (ServiceLoader + META-INF/services)

sample-dagger-a/    -> Educativo: @Component monolitico
sample-dagger-b/    -> Consumidor de DaggerBSdk
sample-dagger-c/    -> Consumidor de DaggerCSdk
sample-hybrid/      -> KoinSdk + puente Dagger 2
sample-multimodule/ -> Consumidor de MultiModuleSdkH (Pattern H, provision interfaces)

benchmark/          -> 277 tests (19 monoliticos + 84 multi-modulo + 37 scale + 57 memory + 80 stress)

docs/               -> Documentacion tecnica (espanol)
  monolithic/       -> Patrones monoliticos (A, B, C, Koin, Hybrid)
  multimodule/      -> Patrones multi-modulo (D, E2, G, H, I, J, K)
  shared/           -> Conceptos compartidos (requisitos, configuracion, cross-deps)
  technical-report.md -> Reporte analitico con benchmarks S22 Ultra
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
Los feature-impl contienen sus propias `Default*Service` internamente (sin dependencia de impl-common-d-c).
`impl-common-d-c` solo se usa en los patrones monoliticos.

## API del consumidor

### Multi-Module con Provision Interfaces (ejemplo realista)

```kotlin
// Init -- solo core. Features se construyen on demand.
MultiModuleSdk.init(context, SdkConfig(debug = true))

// get<T>() auto-construye la cadena de deps via provision interfaces
val auth: AuthApi = MultiModuleSdk.get()    // builds: Core -> Enc -> Auth
val sync: SyncApi = MultiModuleSdk.get()    // builds: Stor + Syn (rest cached)

// La app SOLO depende de :sdk:sdk-wiring (o wiring-e2/wiring-g/wiring-h/wiring-i/wiring-j/wiring-k). Zero imports de feature-impl.
MultiModuleSdk.shutdown()
```

Features dependen de **provision interfaces** (contratos), no de `@Component` (impl).
Cada `feature-xxx-impl` compila independientemente -- solo necesita `sdk:di-contracts` (provision interfaces) + `feature-xxx-api`.

### Multi-Module Pattern G (Factory Functions)

```kotlin
// Misma API que D, pero DaggerXxxComponent es internal en cada feature-impl.
// El wiring llama factory functions en vez de importar DaggerXxx builders.
MultiModuleSdkG.init(context, SdkConfig(debug = true))
val sync: SyncApi = MultiModuleSdkG.get()  // lazy ensure*() via factory functions
MultiModuleSdkG.shutdown()
```

### Multi-Module Pattern H (Auto-Discovery + Dagger)

```kotlin
// Wiring inmutable — descubre FeatureProviders via ServiceLoader, resuelve deps via DFS.
MultiModuleSdkH.init(context, SdkConfig(debug = true))
val sync: SyncApi = MultiModuleSdkH.get()  // resolver.provision() auto-builds chain
MultiModuleSdkH.shutdown()
```

### Multi-Module Pattern I (Pure Resolver — zero DI framework)

```kotlin
// Misma arquitectura que H, pero features construidas via constructor injection.
// Zero Dagger, zero KSP, zero codegen.
MultiModuleSdkI.init(context, SdkConfig(debug = true))
val sync: SyncApi = MultiModuleSdkI.get()
MultiModuleSdkI.shutdown()
```

### Multi-Module Pattern J (kotlin-inject)

```kotlin
// Misma arquitectura que H, pero features usan kotlin-inject Components.
// KSP genera Kotlin (no Java). Menos boilerplate que Dagger.
MultiModuleSdkJ.init(context, SdkConfig(debug = true))
val sync: SyncApi = MultiModuleSdkJ.get()
MultiModuleSdkJ.shutdown()
```

### Multi-Module Pattern K (AndroidManifest Discovery — Firebase-style)

```kotlin
// Mismo principio que Firebase SDK: descubre providers via AndroidManifest <meta-data>.
// PackageManager.getServiceInfo() en vez de ServiceLoader.
MultiModuleSdkK.init(context, SdkConfig(debug = true))
val sync: SyncApi = MultiModuleSdkK.get()
MultiModuleSdkK.shutdown()
```

### Multi-Module Pattern E (Registry + topo-sort)

```kotlin
// Feature enum expuesto al consumidor.
MultiModuleSdkE.init(context, SdkConfig(debug = true), features = setOf(Feature.SYNC))
val sync: SyncApi = MultiModuleSdkE.get()
MultiModuleSdkE.shutdown()
```

### Multi-Module Pattern E2 (Registry + auto-init)

```kotlin
// API minima: init() + get<T>(). Sin Feature enum.
MultiModuleSdkE2.init(context, SdkConfig(debug = true))
val sync: SyncApi = MultiModuleSdkE2.get()  // auto-builds entire chain
MultiModuleSdkE2.shutdown()
```

### Monolitico B (Per-Feature Components)

```kotlin
DaggerBSdk.init(SdkConfig(debug = true), setOf(Feature.ENCRYPTION, Feature.SYNC))
val enc: EncryptionApi = DaggerBSdk.get()
DaggerBSdk.shutdown()
```

### Monolitico C (ServiceLoader Discovery)

```kotlin
DaggerCSdk.init(SdkConfig(debug = true), setOf("encryption", "sync"))
val enc: EncryptionApi = DaggerCSdk.get()
DaggerCSdk.shutdown()
```

### Koin

```kotlin
KoinSdk.init(modules = setOf(SdkModule.Encryption.Default), config = SdkConfig(debug = true))
val enc: EncryptionApi = KoinSdk.get()
KoinSdk.shutdown()
```

### Hybrid (Koin SDK + Dagger 2 app)

```kotlin
// SDK init (Koin interno)
KoinSdk.init(modules = setOf(SdkModule.Encryption.Default), config = SdkConfig(debug = true))
// Bridge Dagger — la app nunca importa Koin
val bridge = DaggerSdkBridgeComponent.builder().build()
val enc: EncryptionApi = bridge.encryption()  // Dagger cached (~1.9 ns)
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
| **Reporte tecnico** | |
| [Reporte analitico](docs/technical-report.md) | Benchmarks S22 Ultra, comparativas, guia de decision (I/J incluidos) |
| **Monoliticos** | |
| [Patrones monoliticos](docs/monolithic/patterns-overview.md) | A (educativo), B, C, Koin, Hybrid — codigo, pros/contras |
| [Benchmarks monoliticos](docs/monolithic/benchmark-results.md) | DiBenchmark: 19 tests en S22 Ultra |
| **Multi-modulo** | |
| [Patrones multi-modulo](docs/multimodule/patterns-overview.md) | D, E2, G, H, I, J, K — codigo, pros/contras |
| [Benchmarks multi-modulo](docs/multimodule/benchmark-results.md) | 84 benchmarks + 137 stress/memory tests en S22 Ultra |
| [Arquitectura api/impl](docs/multimodule/api-impl-architecture.md) | Separacion Gradle, provision interfaces (D/E2/G/H/I/J/K) |
| **Compartidos** | |
| [Requisitos](docs/shared/requirements.md) | 10 requisitos, cumplimiento por patron |
| [Conceptos DI](docs/shared/consumer-isolation.md) | DI vs Service Locator, niveles de aislamiento |
| [Dependencias cruzadas](docs/shared/cross-feature-deps.md) | Como resuelve cada approach las cross-deps |
| [Configuracion benchmarks](docs/shared/benchmark-configuration.md) | Guia para ejecutar los 277 tests |

## Stack

Kotlin 2.0.21 | AGP 9.0.1 | Dagger 2.59.2 | Koin 4.1.1 | KSP | Jetpack Benchmark 1.4.0
