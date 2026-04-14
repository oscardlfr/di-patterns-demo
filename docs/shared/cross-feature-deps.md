# Dependencias Cruzadas: Ejemplos Concretos

Que pasa cuando las features dentro de un SDK necesitan servicios de otras features?
Cada approach responde de forma diferente.

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

### Dagger D/E/E2/G -- Provision Interfaces con `dependencies=[...]`

```kotlin
// feature-auth-impl -- Auth depende de Core + Encryption via provision interfaces
@Component(
    dependencies = [CoreProvisions::class, EncProvisions::class],
    modules = [AuthModule::class],
)
interface AuthComponent : AuthProvisions {
    override fun auth(): AuthApi
}

// feature-syn-impl -- Sync depende de Core + Enc + Auth + Storage
@Component(
    dependencies = [CoreProvisions::class, EncProvisions::class,
                    AuthProvisions::class, StorProvisions::class],
    modules = [SynModule::class],
)
interface SynComponent : SynProvisions {
    override fun sync(): SyncApi
}
```

Dagger ve `dependencies=[EncProvisions]` y resuelve `EncryptionApi` desde
`EncProvisions.encryption()` automaticamente.

**Diferencia entre variantes:**
- **D**: wiring module llama `ensureEnc()` manualmente antes de `ensureAuth()`
- **E2**: DFS recursivo resuelve el orden automaticamente
- **G**: factory functions `buildAuthProvisions(core, enc)` reciben provision interfaces
- **H**: `FeatureProvider.build()` llama `resolver.provision(EncProvisions::class.java)` (DFS)

### Pattern H -- FeatureProvider + DFS Resolver

```kotlin
class AuthProvider : FeatureProvider<AuthProvisions>(AuthProvisions::class.java) {
    override val services = mapOf(AuthApi::class.java to AuthProvisions::auth)

    override fun build(resolver: Resolver): AuthProvisions {
        val core = resolver.provision(CoreProvisions::class.java)  // DFS auto-build
        val enc = resolver.provision(EncProvisions::class.java)    // DFS auto-build
        return buildAuthProvisions(core, resolver.logger, enc)
    }
}
```

El resolver construye la cadena automaticamente: `get<AuthApi>()` -> `AuthProvider.build()` ->
`resolver.provision(EncProvisions)` -> `EncProvider.build()` -> `resolver.provision(CoreProvisions)`.

### Pattern I -- PureFeatureProvider (zero DI framework)

```kotlin
class AuthPureProvider : PureFeatureProvider<AuthProvisions>(AuthProvisions::class.java) {
    override val services = mapOf(AuthApi::class.java to AuthProvisions::auth)

    override fun build(resolver: Resolver): AuthProvisions {
        val enc = resolver.provision(EncProvisions::class.java)  // DFS auto-build
        val logger = resolver.logger
        val auth = DefaultAuthService(enc.encryption(), logger)  // constructor directo
        return object : AuthProvisions { override fun auth() = auth }
    }
}
```

Misma arquitectura de Resolver que H. La diferencia: `DefaultAuthService` se construye
via constructor directo, sin Dagger `@Component` ni KSP codegen.

### Pattern J -- KIFeatureProvider (kotlin-inject)

```kotlin
@Component
abstract class KIAuthComponent(
    @get:Provides val encryption: EncryptionApi,
    @get:Provides val logger: SdkLogger,
) {
    abstract val auth: AuthApi
    @Provides fun authApi(): AuthApi = DefaultAuthService(encryption, logger)
}

class AuthKIProvider : KIFeatureProvider<AuthProvisions>(AuthProvisions::class.java) {
    override val services = mapOf(AuthApi::class.java to AuthProvisions::auth)

    override fun build(resolver: Resolver): AuthProvisions {
        val enc = resolver.provision(EncProvisions::class.java)
        val component = KIAuthComponent::class.create(
            encryption = enc.encryption(), logger = resolver.logger
        )
        val auth = component.auth
        return object : AuthProvisions { override fun auth() = auth }
    }
}
```

Misma arquitectura de Resolver que H/I. Usa kotlin-inject `@Component` (KSP) en vez de
Dagger. Menos boilerplate: Component = Module (no se necesita `@Module` separado).

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

## Provision Interfaces (Multi-Modulo)

En las variantes multi-modulo (D, E2, G, H, I, J, K, L, M, N, O, O2, P, P2, Q, Q2), las dependencias cruzadas se resuelven
mediante **provision interfaces** -- contratos Kotlin planos que exponen servicios sin acoplar
al `@Component` concreto:

```
feature-auth-impl
  +-- depends on: di-contracts     (EncProvisions, CoreProvisions)
  +-- NOT on: feature-enc-impl     <- nunca ve DaggerEncComponent
```

Las provision interfaces (`CoreProvisions`, `EncProvisions`, etc.) definen que servicios
expone cada feature. El modulo de wiring es el **unico lugar** que conecta todo:

| Patron | Como satisface provision interfaces |
|--------|-------------------------------------|
| D | `DaggerEncComponent.builder().core(coreProvisions).build()` directo |
| E2 | `AutoProvisionEntry` con DFS automatico |
| G | `buildEncProvisions(coreProvisions)` via factory functions |
| H | `EncProvider.build(resolver)` via FeatureProvider + DFS resolver |
| I | `EncPureProvider.build(resolver)` via constructor directo + DFS resolver |
| J | `EncKIProvider.build(resolver)` via kotlin-inject Component + DFS resolver |
| K | `EncProvider.build(resolver)` via Dagger (mismos providers que H, descubiertos via AndroidManifest) |
| L | Koin `get()` resuelve provision interfaces via ServiceLoader-discovered modules |
| M | Koin `get()` resuelve provision interfaces via modules listados manualmente |
| N | Koin `get()` resuelve via sweet-spi-discovered modules (Full KMP) |
| O/O2 | Metro `@ContributesTo(AppScope)` -- compiler plugin agrega al grafo en compilacion |
| P/P2 | kotlin-inject-anvil `@ContributesTo(AppScope)` -- KSP `@MergeComponent` en compilacion |
| Q/Q2 | Dagger @Component monolitico -- `dependencies=[...]` resuelve todo en compilacion |

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
