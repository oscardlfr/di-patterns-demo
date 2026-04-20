# Dependencias Cruzadas: Ejemplos Concretos

Que pasa cuando las features dentro de un SDK necesitan servicios de otras features?
Cada approach responde de forma diferente.

> **Cambios post-refactor** (aplicados a H/I/J/K/L/M/N y al resto de patrones multi-modulo):
> - La jerarquia global `CoreProvisions`/`EncProvisions`/`AuthProvisions`/... en
>   `di-contracts` ha sido **eliminada**. Cada feature-impl define su propio **Bundle local
>   `internal`** (p.ej. `EncBundle` en `feature-enc-impl`) cuando expone multi-servicio
>   desde un mismo Component.
> - `FeatureProvider` es ahora una clase base **neutra** con tag `Flavor` (DAGGER / PURE /
>   KI / SYNTHETIC). H filtra por DAGGER, I por PURE, J por KI al descubrir providers via
>   ServiceLoader.
> - El API del Resolver paso de `resolver.provision(XxxProvisions::class.java)` a
>   `resolver.get(ServiceApi::class.java)`. Los providers piden **servicios**, no
>   "provisions" tipadas intermedias.
> - Context y SdkConfig se publican via `SyntheticFeatureProvider` registrado por el
>   wiring en `init()` — mismo mecanismo que cualquier otro provider.
>
> Los fragmentos de codigo mostrados abajo reflejan el **diseño actual**.

---

## El Escenario

El SDK de este proyecto tiene 6 features con estas dependencias:

```
Core (config)
Observability (logger)
Encryption <- Auth (necesita cifrar contrasenas)
Encryption <- Storage (necesita cifrar datos)
Encryption + Auth + Storage <- Sync (necesita todo para sincronizar)
Analytics (independiente -- solo necesita logger)
```

En el proyecto real, `DefaultSyncService` recibe `AuthApi`, `StorageApi` y `EncryptionApi`
por constructor.

---

## Grafo Unico: Resolucion Automatica

**Funciona en:** Koin, Hybrid, D, E, E2, G, H, I, J, K, L, M, N, O, O2, P, P2, Q, Q2

Todos los servicios estan en UN contenedor (o registry/resolver). Cualquier servicio puede pedir cualquier otro.

### Koin (`sdk/impl-koin/KoinSdk.kt`)

```kotlin
val encModule = module {
    single<EncryptionApi> { DefaultEncryptionApi(get()) }
}
val authModule = module {
    // Cross-feature: Auth obtiene EncryptionApi del mismo grafo
    single<AuthApi> { DefaultAuthApi(get(), get()) }
}
val syncModule = module {
    // Cross-feature pesado: necesita Auth + Storage + Encryption
    single<SyncApi> { DefaultSyncApi(get(), get(), get(), get()) }
}
```

Koin ve el conjunto completo de `single<>` y resuelve la cadena:
SyncApi necesita AuthApi -> encontrado en authModule.
AuthApi necesita EncryptionApi -> encontrado en encModule.

### Dagger D/E/E2/G -- @BindsInstance + Bundles locales

```kotlin
// feature-auth-impl -- Auth es mono-servicio (solo AuthApi)
@AuthScope
@Component(modules = [AuthModule::class])
internal interface AuthComponent {
    fun auth(): AuthApi
    @Component.Builder interface Builder {
        @BindsInstance fun encryption(encryption: EncryptionApi): Builder
        @BindsInstance fun logger(logger: SdkLogger): Builder
        fun build(): AuthComponent
    }
}

// Factory publica (usada por G/sdk-wiring/E/E2)
fun buildAuthService(encryption: EncryptionApi, logger: SdkLogger): AuthApi =
    DaggerAuthComponent.builder().encryption(encryption).logger(logger).build().auth()
```

Post-refactor: Dagger ya no usa `dependencies=[XxxProvisions]`. Cada cross-feature dep
entra como `@BindsInstance` en el builder. Los wirings G/sdk-wiring/E/E2 importan las
factories publicas directamente.

Para features multi-servicio (Enc expone `EncryptionApi` + `HashApi` desde el mismo
Component), se define un **Bundle local**:

```kotlin
// feature-enc-impl/EncBundle.kt (publico para que G/sdk-wiring puedan cachearlo)
interface EncBundle {
    fun encryption(): EncryptionApi
    fun hash(): HashApi
}

// feature-enc-impl/EncComponent.kt
@EncScope
@Component(modules = [EncModule::class])
internal interface EncComponent : EncBundle {
    @Component.Builder interface Builder {
        @BindsInstance fun logger(logger: SdkLogger): Builder
        fun build(): EncComponent
    }
}

fun buildEncBundle(logger: SdkLogger): EncBundle =
    DaggerEncComponent.builder().logger(logger).build()
```

**Diferencia entre variantes:**
- **D/sdk-wiring baseline**: wiring cachea `_enc: EncBundle?` y llama `ensureEnc()` antes
  de `ensureAuth()`.
- **E/E2**: entries clavean por `XxxFeatureId::class.java` (marker class neutra) y el
  `ServiceRegistry`/`AutoServiceRegistry` resuelve dependencias automaticamente.
- **G**: factory functions directas (`buildEncBundle`, `buildAuthService`) con cache
  `@Volatile` en el wiring.
- **H/I/J/K**: `FeatureProvider.build(resolver)` llama `resolver.get(EncryptionApi::class.java)`
  — el DFS del Resolver resuelve providers implicitamente.

### Pattern H -- FeatureProvider (Flavor.DAGGER) + DFS Resolver

```kotlin
class AuthProvider : FeatureProvider() {
    override val flavor = Flavor.DAGGER
    override val services = setOf(AuthApi::class.java)

    override fun build(resolver: Resolver): Map<Class<*>, Any> {
        val auth = buildAuthService(
            encryption = resolver.get(EncryptionApi::class.java),  // DFS auto-build
            logger = resolver.get(SdkLogger::class.java),          // DFS auto-build
        )
        return mapOf(AuthApi::class.java to auth)
    }
}
```

El resolver construye la cadena automaticamente: `get<AuthApi>()` -> `AuthProvider.build()` ->
`resolver.get(EncryptionApi)` -> `EncProvider.build()` (devuelve mapa con EncryptionApi + HashApi
del mismo Component Dagger).

### Pattern I -- FeatureProvider (Flavor.PURE, zero DI framework)

```kotlin
class AuthPureProvider : FeatureProvider() {
    override val flavor = Flavor.PURE
    override val services = setOf(AuthApi::class.java)

    override fun build(resolver: Resolver): Map<Class<*>, Any> {
        val auth = DefaultAuthService(
            encryption = resolver.get(EncryptionApi::class.java),
            logger = resolver.get(SdkLogger::class.java),
        )
        return mapOf(AuthApi::class.java to auth)
    }
}
```

Misma arquitectura de Resolver que H (ahora unificado bajo el mismo `FeatureProvider`
con `Flavor.PURE` en vez de una subclase distinta `PureFeatureProvider`). La diferencia:
`DefaultAuthService` se construye via constructor directo, sin Dagger `@Component`.

### Pattern J -- FeatureProvider (Flavor.KI, kotlin-inject)

```kotlin
@Component
internal abstract class KIAuthComponent(
    @get:Provides val encryption: EncryptionApi,
    @get:Provides val logger: SdkLogger,
) {
    abstract val auth: AuthApi
    @Provides fun authApi(): AuthApi = DefaultAuthService(encryption, logger)
}

class AuthKIProvider : FeatureProvider() {
    override val flavor = Flavor.KI
    override val services = setOf(AuthApi::class.java)

    override fun build(resolver: Resolver): Map<Class<*>, Any> {
        val component = KIAuthComponent::class.create(
            encryption = resolver.get(EncryptionApi::class.java),
            logger = resolver.get(SdkLogger::class.java),
        )
        return mapOf(AuthApi::class.java to component.auth)
    }
}
```

Misma arquitectura de Resolver que H/I (tambien unificado bajo `FeatureProvider` con
`Flavor.KI`). Usa kotlin-inject `@Component` (KSP) en vez de Dagger.

### Hybrid -- Koin SDK + Dagger App

Hereda de Koin: internamente el SDK resuelve cross-deps via `get()` desde el mismo grafo Koin.
La app Dagger consume los servicios del SDK via un bridge `@Component`.

---

## Per-Feature: Wiring Manual Necesario

**Funciona en:** Dagger Per-Feature (B), Dagger + ServiceLoader (C)

Cada feature tiene su PROPIO `DaggerComponent`. No se ven entre si.

### Dagger B (`sdk/impl-dagger-b/`)

```
+----------------+    +----------------+    +----------------+
| AuthComponent  |    | StorComponent  |    | EncComponent   |
| needs EncSvc   |    | needs EncSvc   |    | (standalone)   |
|   <- DONDE?    |    |   <- DONDE?    |    |                |
+-------+--------+    +-------+--------+    +----------------+
        |                      |
        +----------+-----------+
                   |
           +-----------+
           |  CoreApis | <- interfaz Kotlin plana, NO Dagger
           +-----------+
```

Solucion: extender CoreApis con el servicio necesario:

```kotlin
interface AuthCoreApis : CoreApis {
    val encryptionService: EncryptionApi
}

interface SyncCoreApis : CoreApis {
    val authService: AuthApi
    val storageService: StorageApi
    val encryptionService: EncryptionApi
    // <- cada campo es una cross-dep manual
}
```

**Problema a escala:** Con 15+ servicios compartidos, SyncCoreApis conoce
todo el SDK -- es un God Object que anula el aislamiento per-feature.

---

## Arquitectura post-refactor: FeatureProvider + Bundles locales

En las variantes multi-modulo (D, E2, G, H, I, J, K, L, M, N, O, O2, P, P2, Q, Q2), las
dependencias cruzadas se resuelven con el **contrato neutro `FeatureProvider`** (en
`di-contracts`) + **Bundles locales** privados por feature-impl cuando hay multi-servicio.

```
di-contracts
  +-- FeatureProvider (flavor-tagged, neutro, zero imports de sdk/api o feature-*-api)
  +-- Resolver (fast-path HashMap, DFS bajo demanda, SyntheticFeatureProvider para infra)

feature-enc-impl
  +-- depends on: di-contracts + feature-enc-api + observability-api
  +-- NOT on: feature-core-api, feature-auth-impl, ...
  +-- EncBundle (interfaz publica local que agrupa EncryptionApi + HashApi)
  +-- buildEncBundle(logger) factory publica
  +-- EncProvider (Flavor.DAGGER), EncPureProvider (Flavor.PURE), EncKIProvider (Flavor.KI)
  +-- EncKoinProvider (Koin modules), EncSweetSpiProvider (sweet-spi @ServiceProvider)
```

Cada feature-impl declara **solo sus deps minimas**: su feature-api + observability-api +
cross-feature apis que necesite (Auth importa EncryptionApi). Ningun feature-impl conoce
otros feature-impl.

| Patron | Como resuelve cross-feature deps |
|--------|----------------------------------|
| D / sdk-wiring | Wiring cachea `_enc: EncBundle?`, `_auth: AuthApi?`, ... + llama factories publicas con `ensureXxx()` manual |
| E | `ServiceEntry` con `featureId = EncFeatureId::class.java` marker + topological sort |
| E2 | `AutoServiceEntry` con `serviceClasses` upfront + DFS on-demand |
| G | Factory functions publicas (`buildEncBundle(logger)`, `buildAuthService(enc, logger)`) con cache `@Volatile` en el wiring |
| H | `EncProvider : FeatureProvider() { flavor = DAGGER }` — wiring filtra ServiceLoader por flavor |
| I | `EncPureProvider : FeatureProvider() { flavor = PURE }` — mismo Resolver que H |
| J | `EncKIProvider : FeatureProvider() { flavor = KI }` — kotlin-inject Component + mismo Resolver |
| K | Mismo `EncProvider` que H, descubierto via AndroidManifest `<meta-data>` + manifest merger |
| L | Koin `get()` resuelve via modulos descubiertos por `java.util.ServiceLoader` (`KoinFeatureProvider`) |
| M | Koin `get()` + `koin.loadModules()` on-demand con cascade de `requiredServices` |
| N | Koin `get()` via sweet-spi `@ServiceProvider` (Full KMP) |
| O/O2 | Metro `@ContributesTo(AppScope)` -- compiler plugin agrega al grafo en compilacion |
| P/P2 | kotlin-inject-anvil `@ContributesTo(AppScope)` -- KSP `@MergeComponent` en compilacion |
| Q/Q2 | Dagger @Component monolitico -- todos los `@Module` en `@Component(modules=[...])` |

---

## Resumen

| Approach | Cross-feature | Mecanismo | Limitacion |
|----------|--------------|-----------|-----------|
| **D** (multi) | Automatico | `dependencies=[ProvisionInterface]` | `when` blocks en facade (crecen por feature Y API) |
| **E2** (multi) | Automatico | `dependencies=[...]` + DFS on-demand | 1 linea por feature en `allEntries()`; facade inmutable via registry |
| **G** (multi) | Automatico | Factory functions + provision interfaces | `ensure*()` crecen por feature + `when` en facade |
| **H** (multi) | Automatico | FeatureProviders + DFS resolver | ~3.5x init overhead; facade inmutable via resolver |
| **I** (multi) | Automatico | PureFeatureProviders + DFS resolver | Zero compile-time safety; facade inmutable |
| **J** (multi) | Automatico | KIFeatureProviders + DFS resolver | KSP overhead, = H; facade inmutable |
| **K** (multi) | Automatico | FeatureProviders via AndroidManifest + DFS resolver | IPC overhead (PackageManager); facade inmutable |
| **L** (multi) | Automatico | Koin modules via ServiceLoader + `get()` | ServiceLoader es JVM-only; facade inmutable via koin.get |
| **M** (multi) | Automatico | Koin modules listados manualmente + `get()` | Modulos crecen linealmente; facade inmutable |
| **N** (multi) | Automatico | Koin modules via sweet-spi + `get()` | Full KMP, resolucion runtime; facade inmutable |
| **O/O2** (multi) | Automatico | Metro `@ContributesTo` -- compiler plugin | Full KMP, compile-time; **facade `when` manual por API** (mitigable con KSP propio) |
| **P/P2** (multi) | Automatico | kotlin-inject-anvil `@ContributesTo` -- KSP | Full KMP, compile-time; **facade `when` manual por API** (mitigable con KSP propio) |
| **Q/Q2** (multi) | Automatico | Dagger @Component monolitico | Sin lean binary, @Component crece + facade `when` manual |
| **Koin** | Automatico | `get()` desde el mismo grafo | Resolucion runtime; facade inmutable |
| **Hybrid** | Automatico | Koin `get()` + bridge Dagger | Puente unidireccional; facade inmutable (Koin) |
| **B** (mono) | Manual | CoreApis extendido | God Object a escala; facade `when` manual |
| **C** (mono) | Manual | ServiceResolver runtime | God Object + JVM only; `when` per Component wrapper |

**Conclusion:** Si las features dependen unas de otras, un grafo unico (D, E2, G, H, I, J, K, L, M, N, O, O2, P, P2, Q, Q2, Koin)
resuelve todo automaticamente. Per-feature monolitico (B, C) funciona cuando las features son
verdaderamente independientes.

H, I, J y K comparten la misma arquitectura de Resolver con DFS automatico. La diferencia
es exclusivamente como se construyen y descubren los FeatureProviders:
- **H:** Dagger `@Component` + ServiceLoader (`META-INF/services`)
- **I:** Constructor injection puro + ServiceLoader
- **J:** kotlin-inject `@Component` + ServiceLoader
- **K:** Dagger `@Component` + AndroidManifest `<meta-data>` (Firebase-style)

---

## Cross-feature-deps vs Wiring del facade (Req 11)

**Nota importante**: este doc cubre solo la **resolucion de dependencias cruzadas al construir
provisions** (cuando `AuthProvisions.auth()` necesita `EncryptionApi`). Es ortogonal al
**wiring del facade del SDK** (cuando el consumidor llama `sdk.get<EncryptionApi>()`).

- La resolucion cross-feature es **automatica en todos los patrones multi-modulo** (17 de 22).
- El wiring del facade **NO es automatico en todos**: H/I/J/K/L/M/N/E2 delegan a un registry
  runtime (HashMap/Koin.get) sin `when`; O/O2/P/P2/Q/Q2 mantienen un `when (clazz)` manual que
  crece por API. Ver `docs/shared/requirements.md` Req 11 para definicion completa.

Los dos ejes son independientes. Un patron puede tener cross-feature-deps automatico pero
facade manual (O/O2/P/P2/Q/Q2). Para el caso opuesto (facade automatico pero cross-deps
manual) no hay patrones en este proyecto -- seria un antipatron.

---

## Cross-feature-deps vs Abstraccion runtime-flexible (Req 12)

Un tercer eje ortogonal: el **acoplamiento compile-time del sdk-integration con los
feature-impls**. Mide si el modulo de wiring puede declararse con `runtimeOnly(features)`
en su `build.gradle.kts` y distribuirse como artefacto BYOF.

- La resolucion cross-feature (este doc) funciona si las feature-apis estan disponibles
  en compile-time — se satisface en 16 de 20 patrones.
- Req 12 (runtime-flexible) adicionalmente exige que el sdk-integration **no importe
  `com.grinwich.sdk.feature.*` en su codigo fuente**. Solo H/I/J/K/L/M/N cumplen.

Patrones como O/O2/P/P2/Q/Q2 resuelven cross-feature deps perfectamente (via
`@ContributesTo` o `@InstallIn`), pero el mecanismo mismo de merge en compile-time los
acopla al classpath — falla Req 12. Ver `docs/shared/requirements.md` para la matriz
completa.
