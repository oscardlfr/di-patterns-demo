# DI Patterns Demo

Proyecto de demostracion que implementa 8 approaches de inyeccion de dependencias
para SDKs modulares Android. Incluye benchmarks con Jetpack Benchmark en dispositivo real
(Samsung Galaxy S22 Ultra, Android 16) y documentacion analitica neutral en espanol.

## Estructura

```
observability-api/        -> SdkLogger + AndroidSdkLogger (zero deps)

feature-core-api/         -> SdkConfig
feature-enc-api/          -> EncryptionApi, HashApi
feature-auth-api/         -> AuthApi, AuthToken
feature-stor-api/         -> StorageApi
feature-ana-api/          -> AnalyticsApi
feature-syn-api/          -> SyncApi, SyncResult

feature-core-impl/        -> CoreComponent : CoreProvisions
feature-enc-impl/         -> EncComponent + DefaultEncryptionService (internal)
feature-auth-impl/        -> AuthComponent + DefaultAuthService (internal)
feature-stor-impl/        -> StorComponent + DefaultSecureStorageService (internal)
feature-ana-impl/         -> AnaComponent + DefaultAnalyticsService (internal)
feature-syn-impl/         -> SynComponent + DefaultSyncService (internal)

sdk/
  api/                    -> Umbrella: CoreApis + re-exports all feature-apis + observability-api
  di-contracts/           -> Provisions + Scopes + RegistryInfra
  sdk-wiring/             -> Pattern D: direct lazy ensure*()
  wiring-e/               -> Pattern E: ProvisionRegistry + topo-sort
  wiring-e2/              -> Pattern E2: AutoProvisionRegistry + DFS lazy
  impl-common/            -> Implementaciones compartidas (solo patrones monoliticos)
  impl-koin/              -> KoinSdk (koinApplication aislado, loadModules, auto-discovery)
  impl-dagger-b/          -> DaggerBSdk (Per-Feature Components + CoreApis)
  impl-dagger-c/          -> DaggerCSdk (ServiceLoader + META-INF/services)
  impl-dagger-d/          -> DaggerSdk (Component Dependencies - cross-deps automaticas)
  impl-dagger-e/          -> RegistrySdk (Component Registry - explicit bindings, auto topo-sort)
  impl-dagger-e2/         -> AutoSdk (Auto-Init Registry - evolucion de E, sin Feature enum)
  di-core/                -> CoreComponent compartido (F educativo)
  impl-dagger-f/          -> ModularSdk (F educativo: @Component sharing directo vs provision interfaces)

sample-dagger-a/    -> Educativo: @Component monolitico
sample-dagger-b/    -> Consumidor de DaggerBSdk
sample-dagger-c/    -> Consumidor de DaggerCSdk
sample-dagger-d/    -> Consumidor de DaggerSdk (D)
sample-dagger-e/    -> Consumidor de RegistrySdk (E)
sample-dagger-e2/   -> Consumidor de AutoSdk (E2)
sample-dagger-f/    -> Consumidor de ModularSdk (F)
sample-hybrid/      -> KoinSdk + puente Dagger 2
sample-multimodule/ -> Consumidor de MultiModuleSdk (provision interfaces)

benchmark/          -> 65 Jetpack Microbenchmarks (50 monoliticos + 15 multi-modulo)

docs/               -> 8 documentos tecnicos (espanol)
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

`:observability-api` es un modulo separado con `SdkLogger` + `AndroidSdkLogger` (zero deps).
`:feature-core-api` contiene solo `SdkConfig` (zero deps).
`:sdk:api` es un umbrella que re-exporta todas las feature-apis + observability-api, asi que
los patrones monoliticos (B-F, Koin) siguen dependiendo de `:sdk:api` sin cambios.
Los feature-impl contienen sus propias `Default*Service` internamente (sin dependencia de impl-common).
`impl-common` solo se usa en los patrones monoliticos.

## API del consumidor

### Pattern E2 -- Auto-Init Registry (API mas limpia)

```kotlin
// Init -- solo config, sin seleccion de features
AutoSdk.init(SdkConfig(debug = true))

// Pedir un servicio -- auto-inicializa toda la cadena de deps
val sync = AutoSdk.get<SyncApi>()  // auto: Core -> Enc -> Auth -> Storage -> Sync

// Apagar
AutoSdk.shutdown()
```

Sin `Feature` enum. Sin `getOrInitModule()`. El consumidor solo ve `init()` + `get<T>()`.

### Multi-Module con Provision Interfaces (ejemplo realista)

```kotlin
// Init -- solo core. Features se construyen on demand.
MultiModuleSdk.init(SdkConfig(debug = true))

// get<T>() auto-construye la cadena de deps via provision interfaces
val auth: AuthApi = MultiModuleSdk.get()    // builds: Core -> Enc -> Auth
val sync: SyncApi = MultiModuleSdk.get()    // builds: Stor + Syn (rest cached)

// La app SOLO depende de :sdk:sdk-wiring (o wiring-e/wiring-e2). Zero imports de feature-impl.
MultiModuleSdk.shutdown()
```

Features dependen de **provision interfaces** (contratos), no de `@Component` (impl).
Cada `feature-xxx-impl` compila independientemente -- solo necesita su `feature-xxx-contracts` + `feature-xxx-api`.

### Multi-Module Pattern E2 (Registry + auto-init)

```kotlin
// Mismo API que AutoSdk, pero con Dagger components en modulos separados
MultiModuleSdkE2.init(SdkConfig(debug = true))
val sync: SyncApi = MultiModuleSdkE2.get()  // auto-builds entire chain
MultiModuleSdkE2.shutdown()
```

### Pattern E -- Component Registry (control explicito)

```kotlin
// Init con features seleccionadas
RegistrySdk.init(SdkConfig(debug = true), setOf(Feature.ENCRYPTION))

// Lazy init con cascada automatica
RegistrySdk.getOrInitModule(Feature.SYNC)

// Resolver servicio
val sync = RegistrySdk.get<SyncApi>()

// Apagar
RegistrySdk.shutdown()
```

El consumidor ve el `Feature` enum -- control mas granular a cambio de
API ligeramente mas compleja.

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
| [Dagger 2: approaches A-F](docs/dagger2-sdk-selective-init.md) | Monolitico, Per-Feature, ServiceLoader, Component Dependencies, Registry, Auto-Init, Multi-Module |
| [Conceptos DI](docs/di-sdk-consumer-isolation.md) | DI vs Service Locator, niveles de aislamiento, singleton survival |
| [Dependencias cruzadas](docs/di-cross-feature-deps.md) | Como resuelve cada approach las cross-deps con ejemplos |
| [Hybrid: Koin SDK + Dagger 2 app](docs/di-hybrid-koin-sdk-dagger-app.md) | Bridge pattern, puente unidireccional, features lazy |
| [Comparacion rapida](docs/di-sdk-selective-init-comparison.md) | Tabla lado-a-lado de frameworks |
| [Multi-modulo api/impl/integration](docs/di-multimodule-api-impl-analysis.md) | Approaches para separacion Gradle estricta + ejemplo realista con provision interfaces |

## Stack

Kotlin 2.0.21 | AGP 9.0.1 | Dagger 2.59.2 | Koin 4.1.1 | KSP | Jetpack Benchmark 1.4.0
