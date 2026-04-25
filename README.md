# DI Patterns Demo

21 patrones de inyeccion de dependencias para SDKs modulares: 5 monoliticos + 16 multi-modulo
(Android-only y KMP-compatible). Frameworks: Dagger, Koin, Metro, kotlin-inject-anvil, sweet-spi.
500+ tests con benchmarks Jetpack Benchmark en Samsung Galaxy S22 Ultra (Android 16).
Documentacion analitica en espanol.

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
                             + error/ (DependencyResolutionException + 6 subtipos: NoProviderFound,
                                       CircularDependency, ProviderBuild, ProviderAlreadyFailed,
                                       ServiceCast, ServiceNotAvailable)
  sdk-wiring/             -> Pattern D multi-modulo: direct lazy ensure*()
  wiring-e/               -> Pattern E multi-modulo: ProvisionRegistry + topo-sort
  wiring-e2/              -> Pattern E2 multi-modulo: AutoProvisionRegistry + DFS lazy
  wiring-g/               -> Pattern G multi-modulo: Factory Functions (Components internal)
  wiring-h/               -> Pattern H multi-modulo: Auto-Discovery FeatureProviders (DFS resolver)
  wiring-i/               -> Pattern I multi-modulo: Pure Resolver (zero DI framework)
  wiring-j/               -> Pattern J multi-modulo: kotlin-inject (KSP, genera Kotlin)
  wiring-k/               -> Pattern K multi-modulo: AndroidManifest Discovery (Firebase-style)
  wiring-l/               -> Pattern L: Koin eager + ServiceLoader (Partial KMP)
  wiring-m/               -> Pattern M: Koin lazy loadModules + ServiceLoader (Partial KMP)
  wiring-n/               -> Pattern N: sweet-spi + Koin (Full KMP — 24 targets)
  wiring-o/               -> Pattern O: Metro eager — compiler plugin @ContributesTo (Full KMP)
  wiring-o2/              -> Pattern O2: Metro Lazy<T> — singletons on-demand (Full KMP)
  wiring-p/               -> Pattern P: kotlin-inject-anvil eager — KSP @MergeComponent (Full KMP)
  wiring-p2/              -> Pattern P2: kotlin-inject-anvil lazy — @SingleIn tracking (Full KMP)
  wiring-q/               -> Pattern Q: Hilt-style Dagger @Module @InstallIn eager (Android-only)
  wiring-q2/              -> Pattern Q2: Hilt-style Dagger + dagger.Lazy<T> (Android-only)
  impl-common-d-c/        -> Implementaciones compartidas (solo patrones monoliticos)
  impl-koin/              -> KoinSdk (koinApplication aislado, loadModules, auto-discovery)
  impl-dagger-b/          -> DaggerBSdk (Per-Feature Components + CoreApis)
  impl-dagger-c/          -> DaggerCSdk (ServiceLoader + META-INF/services)

sample-dagger-a/    -> Educativo: @Component monolitico
sample-dagger-b/    -> Consumidor de DaggerBSdk
sample-dagger-c/    -> Consumidor de DaggerCSdk
sample-hybrid/      -> KoinSdk + puente Dagger 2
sample-multimodule/ -> Consumidor de MultiModuleSdkH (Pattern H, provision interfaces)

benchmark/          -> 630 tests (31 monoliticos + 192 multi-modulo + 67 scale + 128 memory + 212 stress)
                       + 52 unit tests JVM en di-contracts (jerarquia de excepciones del Resolver)

docs/               -> Documentacion tecnica (espanol)
  monolithic/       -> Patrones monoliticos (A, B, C, Koin, Hybrid)
  multimodule/      -> Patrones multi-modulo (16 patrones)
    android/        -> Android-only (D, E2, G, H, I, K, Q, Q2)
    kmp/            -> KMP-compatible (N, O, O2, P, P2)
    partial-kmp/    -> Partial KMP (J, L, M)
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

### Multi-modulo lazy: API uniforme (16 patrones)

Los 16 patrones multi-modulo lazy implementan la misma interfaz
`MultiModuleSdkApi` -- consumo identico, solo cambia el `object` de wiring
que importas. Sin enum de features, sin set de modulos, sin parametros
de subset: la app declara `runtimeOnly` los `feature-X-impl` que necesita
y el wiring descubre lo que esta en classpath.

```kotlin
// Mismo codigo para D, E2, G, H, I, J, K, L, M, N, O, O2, P, P2, Q, Q2.
MultiModuleSdkH.init(context, SdkConfig(debug = true))
val auth: AuthApi = MultiModuleSdkH.get()
val sync: SyncApi = MultiModuleSdkH.get()
MultiModuleSdkH.shutdown()
```

Reemplaza `MultiModuleSdkH` por el wiring `object` que corresponda al
patron elegido:

| Pattern | Wiring `object` | Modulo Gradle | Discovery |
|---|---|---|---|
| D  | `MultiModuleSdk`         | `:sdk:sdk-wiring` | when-block + lazy ensure |
| E2 | `MultiModuleSdkE2`       | `:sdk:wiring-e2`  | AutoServiceRegistry DFS |
| G  | `MultiModuleSdkG`        | `:sdk:wiring-g`   | factory functions per feature |
| H  | `MultiModuleSdkH`        | `:sdk:wiring-h`   | ServiceLoader + Dagger |
| I  | `MultiModuleSdkI`        | `:sdk:wiring-i`   | ServiceLoader + Pure (zero DI) |
| J  | `MultiModuleSdkJ`        | `:sdk:wiring-j`   | ServiceLoader + kotlin-inject |
| K  | `MultiModuleSdkK`        | `:sdk:wiring-k`   | AndroidManifest meta-data |
| L  | `MultiModuleSdkL`        | `:sdk:wiring-l`   | Koin eager + ServiceLoader |
| M  | `MultiModuleSdkM`        | `:sdk:wiring-m`   | Koin lazy loadModules + ServiceLoader |
| N  | `MultiModuleSdkN`        | `:sdk:wiring-n`   | sweet-spi + Koin (Full KMP) |
| O  | `MultiModuleSdkO`        | `:sdk:wiring-o`   | Metro eager (Full KMP) |
| O2 | `MultiModuleSdkO2`       | `:sdk:wiring-o2`  | Metro `Lazy<T>` (Full KMP) |
| P  | `MultiModuleSdkP`        | `:sdk:wiring-p`   | kotlin-inject-anvil eager (Full KMP) |
| P2 | `MultiModuleSdkP2`       | `:sdk:wiring-p2`  | kotlin-inject-anvil lazy (Full KMP) |
| Q  | `MultiModuleSdkQ`        | `:sdk:wiring-q`   | Hilt-style Dagger eager |
| Q2 | `MultiModuleSdkQ2`       | `:sdk:wiring-q2`  | Hilt-style Dagger + `dagger.Lazy<T>` |

La app declara su subset asi:

```kotlin
// :app/build.gradle.kts -- ejemplo Pattern H
dependencies {
    implementation(project(":sdk:wiring-h"))                       // wiring real
    runtimeOnly(project(":features:feature-observability-impl"))   // features que esta app usa
    runtimeOnly(project(":features:feature-core-impl"))
    runtimeOnly(project(":features:feature-enc-impl"))
    runtimeOnly(project(":features:feature-auth-impl"))
    runtimeOnly(project(":features:feature-stor-impl"))
    runtimeOnly(project(":features:feature-ana-impl"))
    runtimeOnly(project(":features:feature-syn-impl"))
}
```

Los `feature-X-impl` no tocan `:sdk:wiring-*`; cada feature compila aislado
contra `:di-contracts` y `:feature-X-api` solamente.

### Patrones que SI requieren parametros adicionales

#### Multi-Module Pattern E (eager, requiere features upfront)

Pattern E construye el grafo eager con topo-sort en init, asi que el
consumidor declara que features quiere arrancar:

```kotlin
import com.grinwich.sdk.wiring.e.MultiModuleSdkE
import com.grinwich.sdk.wiring.e.MultiModuleSdkE.Feature

MultiModuleSdkE.init(
    context,
    SdkConfig(debug = true),
    features = setOf(Feature.SYNC),  // Sync arrastra Auth + Stor + Enc por dependencias
)
val sync: SyncApi = MultiModuleSdkE.get(SyncApi::class.java)
MultiModuleSdkE.shutdown()
```

#### Monolitico B (Per-Feature Components)

```kotlin
import com.grinwich.sdk.daggerb.DaggerBSdk
import com.grinwich.sdk.daggerb.DaggerBSdk.Feature

DaggerBSdk.init(
    context,
    SdkConfig(debug = true),
    features = setOf(Feature.ENCRYPTION, Feature.SYNC),
)
val enc: EncryptionApi = DaggerBSdk.get<EncryptionApi>()
DaggerBSdk.shutdown()
```

#### Monolitico C (ServiceLoader Discovery)

```kotlin
DaggerCSdk.init(
    context,
    SdkConfig(debug = true),
    features = setOf("encryption", "sync"),  // strings -- discovery por nombre via ServiceLoader
)
val enc: EncryptionApi = DaggerCSdk.get<EncryptionApi>()
DaggerCSdk.shutdown()
```

#### Koin (Service Locator)

```kotlin
KoinSdk.init(
    context,
    modules = setOf(SdkModule.Encryption.Default),
    config = SdkConfig(debug = true),
)
val enc: EncryptionApi = KoinSdk.get<EncryptionApi>()
KoinSdk.shutdown()
```

#### Hybrid (SDK Koin + bridge Dagger 2 a la app)

```kotlin
// SDK init (Koin interno)
KoinSdk.init(
    context,
    modules = setOf(SdkModule.Encryption.Default),
    config = SdkConfig(debug = true),
)
// Bridge Dagger -- la app nunca importa Koin
val bridge = DaggerSdkBridgeComponent.builder().build()
val enc: EncryptionApi = bridge.encryption()
```

## Funcionamiento interno (resumen por patron)

> Resumen ejecutivo del mecanismo de cada patron. Para detalle completo
> ir a los `docs/*/patterns-overview.md` enlazados al final.

### Monoliticos (5 patrones)

Un solo grafo DI, un solo `@Component`/`koinApplication`. Apps pequenas
(<10 features). No escalan a equipos distribuidos.

- **A** (`sample-dagger-a`) — `@Component` Dagger 2 unico. Educativo:
  demuestra Dagger puro sin SDK boundary. No usado en produccion.
- **B** (`DaggerBSdk`) — Per-Feature Components con `Feature` enum +
  bridge `CoreApis` que comparte logger/config entre Components
  aislados. Construye eager las features pasadas a `init(features = ...)`.
  Sin discovery: anadir feature implica editar el `when` del facade.
- **C** (`DaggerCSdk`) — `META-INF/services/SdkInitializer` por feature.
  Cada feature publica un `SdkInitializer` que recibe `CoreApis +
  ServiceResolver`. Discovery por nombre (`setOf("encryption", "sync")`).
  Sigue siendo monolitico — el discovery es lo unico modular.
- **Koin** (`KoinSdk`) — `koinApplication` interno aislado del Koin de
  la app. Modules cargados eager via `init(modules = setOf(...))`.
  Service Locator: la app ve la API, Koin queda dentro.
- **Hybrid** — SDK Koin interno + `@Component` Dagger en la app
  (`SdkBridgeComponent`) que actua de adapter. La app nunca importa Koin.

### Multi-modulo eager (1 patron)

Construye todo en `init()` con orden topologico calculado.

- **E** (`MultiModuleSdkE`) — `ServiceEntry` con `dependencies: Set<Class<*>>`
  declaradas explicitamente. `ServiceRegistry.registerAll()` ejecuta
  topo-sort de Kahn al init y construye en orden. **Detecta ciclos al
  registrar** (init-time). Un build fallido aborta el init entero.
  Requiere features upfront: `init(features = setOf(Feature.SYNC))`.

### Multi-modulo lazy con factory functions (2 patrones)

El wiring escribe a mano `ensureXxx()` con double-check locking. Sin
discovery automatica.

- **D** (`MultiModuleSdk`, `:sdk:sdk-wiring`) — facade con
  `when (clazz)` que delega a `ensureEnc()`, `ensureAuth()`, etc.
  Cada `ensureXxx()` construye un `DaggerXxxComponent` lazy. Anadir
  feature implica editar el `when` y anadir un `ensureXxx()` nuevo.
- **G** (`MultiModuleSdkG`) — cada feature-impl publica una factory
  function publica (`buildEncBundle(logger)`, `buildAuthService(...)`).
  El wiring las llama desde sus `ensureXxx()`. `DaggerXxxComponent` queda
  `internal` al feature — el wiring nunca importa builders Dagger.

### Multi-modulo lazy con FeatureProvider + Resolver DFS (4 patrones)

Discovery via `ServiceLoader`/AndroidManifest, resolucion via
`Resolver` con DFS lazy reentrante. Wiring inmutable: anadir features no
toca el wiring. Las dependencias entre features son **implicitas**
(descubiertas cuando `build()` llama a `resolver.get(...)`). Ciclos
detectados con `CircularDependencyException` en el primer `get()`
(no `StackOverflowError`).

- **H** (`MultiModuleSdkH`) — `flavor = DAGGER`. Cada feature trae su
  propio `@Component` Dagger **internal** dentro de `build()`.
  ServiceLoader filtra por flavor, Resolver resuelve.
- **I** (`MultiModuleSdkI`) — `flavor = PURE`. `build()` construye con
  constructores Kotlin manuales. Cero framework DI a nivel de feature
  (zero codegen, zero KSP, builds mas rapidos). Sin compile-time safety
  intra-feature.
- **J** (`MultiModuleSdkJ`) — `flavor = KI`. Cada feature usa
  `kotlin-inject` Components. KSP genera Kotlin (no Java).
  Less boilerplate que Dagger.
- **K** (`MultiModuleSdkK`) — discovery via `<meta-data>` en
  AndroidManifest.xml mergeado. `PackageManager.getServiceInfo` +
  reflexion. Estilo Firebase SDK. Mas robusto ante R8 sin keep rules
  para `META-INF/services`, mas lento en init (PackageManager IPC).

### Multi-modulo lazy explicito (1 patron)

Como H/I/J/K pero las dependencias entre features se declaran en el
entry, no se descubren implicitamente.

- **E2** (`MultiModuleSdkE2`) — `AutoServiceEntry` con
  `dependencies: Set<Class<*>>` declaradas + DFS **iterativo** con
  stack explicito (no recursivo, soporta cadenas de 500+). Cada entry
  declara `serviceClasses` upfront para indexar al instalar.

### Multi-modulo lazy con Koin (3 patrones)

Koin como contenedor DI runtime. Discovery por `KoinFeatureProvider`
via ServiceLoader o sweet-spi.

- **L** (`MultiModuleSdkL`) — `KoinFeatureProvider` via ServiceLoader.
  El wiring carga **todos los modules eager** en init via
  `koinApplication.modules(...)`. JVM-only por ServiceLoader.
- **M** (`MultiModuleSdkM`) — igual que L pero `koin.loadModules()`
  **lazy** a medida que se piden los servicios. Mas overhead runtime,
  menor coste init. Peor performer global de los 16 multimodule
  segun benchmarks.
- **N** (`MultiModuleSdkN`) — `sweet-spi` (KSP-based replacement de
  ServiceLoader) + Koin. **Full KMP** (24 targets). Mismo modelo
  Service Locator que L/M.

### Multi-modulo compile-time DI (6 patrones)

Discovery + grafo resueltos en compilacion via KSP/compiler plugin.
Compile-time safety completa. Sin reflexion runtime. La mitad eager, la
mitad con `Lazy<T>` para singletons on-demand.

- **O** (`MultiModuleSdkO`) — Metro compiler plugin con
  `@ContributesTo` para discovery automatica entre modulos. Eager.
  Full KMP. Framework joven (mantenido por ZacSweers).
- **O2** (`MultiModuleSdkO2`) — igual O pero envuelve cada singleton
  en `Lazy<T>` para construirlos on-demand. Re-init mas barato.
- **P** (`MultiModuleSdkP`) — `kotlin-inject-anvil` con `@ContributesTo`.
  KSP estandar. Eager. Full KMP. Mantenido por Amazon (mas estable
  que Metro).
- **P2** (`MultiModuleSdkP2`) — igual P pero con tracking de
  `@SingleIn` para lazy singletons.
- **Q** (`MultiModuleSdkQ`) — estilo Hilt: `@Module @InstallIn(
  SingletonComponent::class)` listados manualmente en
  `@Component(modules = [...])` del wiring. Eager. Android-only.
  Anadir feature implica editar el `@Component` central.
- **Q2** (`MultiModuleSdkQ2`) — igual Q pero envuelve singletons en
  `dagger.Lazy<T>`. Re-init ultra-rapido (no recrea singletons).

### Mapa rapido por criterio

| Necesito... | Patron recomendado |
|---|---|
| Compile-time safety + auto-discovery zero-touch + KMP | **P2** (kotlin-inject-anvil lazy) o **O2** (Metro lazy) |
| Compile-time safety completa, Android-only, framework muy maduro | **Q2** (Hilt-style Dagger lazy) |
| Auto-discovery zero-touch + ecosistema Dagger maduro + tolerar runtime validation | **H** (Dagger + ServiceLoader + Resolver) |
| Cero framework DI (zero codegen, builds rapidos), runtime safety | **I** (Pure Resolver) |
| Robustez ante R8 sin keep rules de `META-INF/services` | **K** (AndroidManifest discovery) |
| KMP con Koin maduro, sin compile-time safety | **N** (sweet-spi + Koin) |
| Educativo / academic | **A** (Dagger monolithic puro) |

Para detalle completo con codigo, benchmarks y trade-offs:

- [Patrones monoliticos](docs/monolithic/patterns-overview.md) — A, B, C, Koin, Hybrid
- [Patrones multi-modulo Android-only](docs/multimodule/android/patterns-overview.md) — D, E2, G, H, I, K, Q, Q2
- [Patrones multi-modulo KMP](docs/multimodule/kmp/patterns-overview.md) — N, O, O2, P, P2
- [Patrones multi-modulo Partial KMP](docs/multimodule/partial-kmp/patterns-overview.md) — J, L, M

## Manejo de errores del Resolver

Los patrones que comparten la maquinaria de `di-contracts` (E, E2, H/I/J/K)
exponen una jerarquia de excepciones unificada bajo `DependencyResolutionException`
en `com.grinwich.sdk.contracts.error`. Las rutas tipadas son:

- `NoProviderFoundException` — nadie ha registrado un provider para el servicio pedido.
- `CircularDependencyException` — dependencia circular detectada antes de que el stack se profundice (elimina el `StackOverflowError` como modo de fallo).
- `ProviderBuildException` — el `build()` del provider lanzo (causa preservada en `cause`).
- `ProviderAlreadyFailedException` — reintento sobre un provider ya fallido. Se necesita `clear()` para reiniciar.
- `ServiceCastException` — el instance publicado no es asignable al `Class<T>` solicitado.
- `ServiceNotAvailableException` — el provider termino sin publicar un servicio que declaro.

**Cuando se detectan los ciclos:**

| Pattern | Mecanismo | Cuando |
|---|---|---|
| **E** | Topo-sort de Kahn en `registerAll()` | **Eager — antes del primer `get()`** (init-time) |
| **E2/H/I/J/K** | DFS con `visiting` / `buildingProviders` set en `ensureBuilt()` | Lazy — al resolver el servicio ciclico |

Ver [docs/shared/exception-hierarchy.md](docs/shared/exception-hierarchy.md)
para la decision arquitectonica completa, politica de reintentos y costes
medidos.

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
| [Patrones multi-modulo](docs/multimodule/patterns-overview.md) | 16 patrones multi-modulo — codigo, pros/contras |
| [Android-only](docs/multimodule/android/patterns-overview.md) | D, E2, G, H, I, K, Q, Q2 — Dagger/ServiceLoader/Manifest |
| [KMP-compatible](docs/multimodule/kmp/patterns-overview.md) | N, O, O2, P, P2 — sweet-spi+Koin/Metro/kotlin-inject-anvil |
| [Partial KMP](docs/multimodule/partial-kmp/patterns-overview.md) | J, L, M — kotlin-inject/Koin + ServiceLoader |
| [Benchmarks multi-modulo](docs/multimodule/benchmark-results.md) | 144 benchmarks + 253 stress/memory tests en S22 Ultra |
| [Arquitectura api/impl](docs/multimodule/api-impl-architecture.md) | Separacion Gradle, provision interfaces (17 variantes de wiring) |
| **Compartidos** | |
| [Requisitos](docs/shared/requirements.md) | 11 requisitos (incluye criterio bidimensional auto-registro grafo + facade inmutable), cumplimiento por patron |
| [Conceptos DI](docs/shared/consumer-isolation.md) | DI vs Service Locator, niveles de aislamiento |
| [Dependencias cruzadas](docs/shared/cross-feature-deps.md) | Como resuelve cada approach las cross-deps |
| [Jerarquia de excepciones](docs/shared/exception-hierarchy.md) | DependencyResolutionException + subtipos, deteccion de ciclos eager vs lazy, politica de reintentos |
| [Threat surface por patron](docs/shared/security-threats.md) | Vulnerabilidades por mecanismo de discovery + modelo de DI, score por patron, mitigaciones |
| [Configuracion benchmarks](docs/shared/benchmark-configuration.md) | Guia para ejecutar los 630 tests |

## Stack

Kotlin 2.2.21 | AGP 9.0.1 | Dagger 2.59.2 | Koin 4.1.1 | Metro 0.6.6 | kotlin-inject 0.9.0 | kotlin-inject-anvil 0.1.7 | sweet-spi 0.1.3 | KSP 2.3.6 | Jetpack Benchmark 1.4.0
