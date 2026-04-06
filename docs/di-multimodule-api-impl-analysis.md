# Approaches DI para Arquitectura api / impl / integration

Análisis de compatibilidad de cada approach DI con una arquitectura multi-módulo Gradle
donde las features se organizan en módulos `api`, `impl` e `integration`.

**Ejemplo realista implementado** en este proyecto:
- `observability-api/` — SdkLogger (interface)
- `feature-observability-impl/` — AndroidSdkLogger (impl)
- `feature-core-api/` — SdkConfig (zero deps)
- `feature-*-api/` — interfaces públicas per-feature (módulos top-level)
- `sdk/di-contracts/` — Provisions + Scopes + RegistryInfra
- `feature-*-impl/` — Dagger Components con Default*Service interno (módulos top-level, sin dependencia de impl-common)
- 4 variantes de wiring: `sdk/sdk-wiring/` (D), `sdk/wiring-e/` (E), `sdk/wiring-e2/` (E2), `sdk/wiring-g/` (G)
- `sample-multimodule/` — app consumidora que solo depende de `sdk-wiring`

Para implementaciones Dagger, ver [dagger2-sdk-selective-init.md](dagger2-sdk-selective-init.md).
Para conceptos DI, ver [di-sdk-consumer-isolation.md](di-sdk-consumer-isolation.md).
Para dependencias cruzadas, ver [di-cross-feature-deps.md](di-cross-feature-deps.md).
Para el approach hybrid, ver [di-hybrid-koin-sdk-dagger-app.md](di-hybrid-koin-sdk-dagger-app.md).

---

## La Arquitectura

```
observability-api/              → SdkLogger (interface)
feature-observability-impl/     → AndroidSdkLogger (impl)

feature-core-api/            → SdkConfig
feature-enc-api/             → EncryptionApi, HashApi
feature-auth-api/            → AuthApi, AuthToken
feature-stor-api/            → StorageApi
feature-ana-api/             → AnalyticsApi
feature-syn-api/             → SyncApi, SyncResult

feature-core-impl/           → CoreComponent : CoreProvisions
feature-enc-impl/            → EncComponent + DefaultEncryptionService (internal)
feature-auth-impl/           → AuthComponent + DefaultAuthService (internal)
feature-stor-impl/           → StorComponent + DefaultSecureStorageService (internal)
feature-ana-impl/            → AnaComponent + DefaultAnalyticsService (internal)
feature-syn-impl/            → SynComponent + DefaultSyncService (internal)

sdk/
  api/                       → Umbrella: CoreApis + re-exports all feature-apis + observability-api
  di-contracts/              → Provisions + Scopes + RegistryInfra
  sdk-wiring/                → Pattern D: direct lazy ensure*()
  wiring-e/                  → Pattern E: ProvisionRegistry + topo-sort
  wiring-e2/                 → Pattern E2: AutoProvisionRegistry + DFS lazy
  wiring-g/                  → Pattern G: Factory Functions (Components internal)

sample-multimodule/          → App consumidora que solo depende de sdk-wiring
```

### Reglas de visibilidad Gradle

```
feature-auth-impl  →  depends on  →  di-contracts (AuthProvisions, AuthScope, CoreProvisions, EncProvisions)
                   →  NUNCA depende de →  feature-enc-impl, feature-core-impl

app                →  depends on  →  sdk-wiring (implementation)
                                     feature-x-api (solo interfaces)
                   →  NUNCA depende de →  feature-x-impl

sdk-wiring         →  depends on  →  TODOS los feature-*-impl (para ensamblar)
                   →  expone solo  →  tipos de feature-*-api
```

### Requisitos derivados

| # | Requisito | Por qué |
|---|-----------|---------|
| R1 | **Compilación aislada por feature** | Cada `:impl` compila solo. No necesita otros `:impl` |
| R2 | **App no importa impl** | La app solo ve interfaces de `:api` |
| R3 | **Features solo ven apis** | `feature-auth:impl` depende de `feature-encryption:api`, no `:impl` |
| R4 | **Cross-feature automáticas** | Auth inyecta EncryptionApi sin wiring manual |
| R5 | **Lean binary** | Features no incluidas no están en el APK |
| R6 | **Escalabilidad** | Añadir la feature 51 no requiere editar archivos centrales |
| R7 | **Compile-time safety** | Dependencias faltantes detectadas en compilación |
| R8 | **Módulo wiring centralizado** | Un solo punto que ensambla todo |

---

## Evaluación por Approach

### Dagger A — Monolítico

**Compatibilidad: BAJA**

```
sdk-wiring/
  @Component(modules = [CoreModule, EncModule, AuthModule, StorModule, SynModule, ...])
  interface SdkComponent { ... }
```

El `@Component` único vive en `sdk-wiring` y lista TODOS los `@Module` de todos los `:impl`.

| Requisito | Cumple | Nota |
|-----------|--------|------|
| R1 Compilación aislada | ⚠️ | Cada `:impl` compila solo, pero `sdk-wiring` recompila todo el grafo |
| R2 App no importa impl | ✅ | App ve solo `sdk-wiring` facade |
| R3 Features solo ven apis | ✅ | Los `@Module` reciben interfaces por constructor |
| R4 Cross-feature | ✅ | Automático — mismo grafo |
| R5 Lean binary | ❌ | **TODAS las features compiladas en el binario** |
| R6 Escalabilidad | ❌ | Cada feature nueva = editar anotación `@Component` |
| R7 Compile-time safety | ✅ | Dagger valida en compilación |
| R8 Módulo wiring | ✅ | `sdk-wiring` |

**Pro:** Cross-deps triviales. Un grafo, todo resuelve automáticamente.
**Contra:** No hay inicialización selectiva real. El binario incluye todo. El `@Component` crece con cada feature. Funciona como "monocomponente en módulo separado" — la separación Gradle no aporta lean binary.

**Indicado para:** SDKs pequeños (< 10 features) donde el tamaño de binario no importa y quieres máxima simplicidad con compile-time safety.

---

### Dagger B — Per-Feature Components

**Compatibilidad: MEDIA (con limitaciones serias a escala)**

```
feature-auth:impl/
  @Component(modules = [AuthModule::class])
  internal interface AuthComponent {
    fun auth(): AuthApi
    @Component.Builder interface Builder {
      @BindsInstance fun core(core: AuthCoreApis): Builder  // ← God Object
      fun build(): AuthComponent
    }
  }

sdk-wiring/
  // Conecta manualmente, necesita crear AuthCoreApisImpl
  fun getOrInitAuth(): AuthComponent {
    val enc = getOrInitEncryption()
    return DaggerAuthComponent.builder()
      .core(AuthCoreApisImpl(coreConfig, coreLogger, enc.encryption()))
      .build()
  }
```

Cada feature tiene su Component en su `:impl`. El problema: cómo pasa cross-deps.

| Requisito | Cumple | Nota |
|-----------|--------|------|
| R1 Compilación aislada | ✅ | Cada Component compila en su `:impl` |
| R2 App no importa impl | ✅ | App ve solo facade |
| R3 Features solo ven apis | ✅ | `AuthCoreApis` recibe `EncryptionApi` (interfaz de `:api`) |
| R4 Cross-feature | ⚠️ | **Manual** — CoreApis extendido por cada cross-dep |
| R5 Lean binary | ✅ | Solo se compilan los `:impl` incluidos |
| R6 Escalabilidad | ❌ | **God Object — cada cross-dep añade un campo a CoreApis** |
| R7 Compile-time safety | ✅ | Dagger valida cada Component en compilación |
| R8 Módulo wiring | ✅ | `sdk-wiring` construye y conecta Components |

**El problema del God Object en multi-módulo:**

```kotlin
// feature-auth:impl — necesita EncryptionApi
interface AuthCoreApis : CoreApis {
    val encryptionService: EncryptionApi
}

// feature-sync:impl — necesita Auth + Storage + Encryption
interface SyncCoreApis : CoreApis {
    val authService: AuthApi
    val storageService: StorageApi
    val encryptionService: EncryptionApi
}
```

Con 15 features interconectadas, `SyncCoreApis` conoce TODOS los servicios del SDK.
El `sdk-wiring` debe crear N implementaciones `XxxCoreApisImpl` manualmente.
A escala, el módulo wiring se convierte en un mapeo exhaustivo de todo el grafo.

**Pro:** Verdadero lean binary. Cada `:impl` compila independientemente. Compile-time safety.
**Contra:** CoreApis escala linealmente con cross-deps. A 50+ features con dependencias cruzadas, el wiring manual es insostenible.

**Indicado para:** SDKs con features **verdaderamente independientes** (pocas cross-deps). Ej: SDK de analytics + crash reporting + logging donde cada feature vive aislada.

---

### Dagger C — ServiceLoader

**Compatibilidad: MEDIA (solo JVM)**

```
feature-auth:impl/
  class AuthInit : FeatureInitializer {
    override val id = "auth"
    override val dependencies = setOf("encryption")
    override fun init(resolver: ServiceResolver) {
      val enc = resolver.get<EncryptionApi>()
      // build component...
    }
  }

  // META-INF/services/com.example.FeatureInitializer
  // com.example.auth.impl.AuthInit

sdk-wiring/
  ServiceLoader.load(FeatureInitializer::class.java)
    .forEach { it.init(resolver) }
```

Cada `:impl` declara su FeatureInitializer y lo registra en META-INF/services.
`sdk-wiring` descubre automáticamente.

| Requisito | Cumple | Nota |
|-----------|--------|------|
| R1 Compilación aislada | ✅ | Cada `:impl` compila solo |
| R2 App no importa impl | ✅ | ServiceLoader descubre |
| R3 Features solo ven apis | ✅ | `resolver.get<T>()` devuelve interfaz |
| R4 Cross-feature | ⚠️ | **Manual vía resolver** — similar a B pero con runtime lookup |
| R5 Lean binary | ✅ | Solo los `:impl` incluidos en classpath |
| R6 Escalabilidad | ⚠️ | Auto-discovery elimina edición central, pero cross-deps son manuales |
| R7 Compile-time safety | ⚠️ | Dagger valida cada Component, pero **ServiceLoader no valida el grafo completo** |
| R8 Módulo wiring | ✅ | `sdk-wiring` (o automático vía ServiceLoader) |

**Pro:** Auto-discovery genuino — añadir dependencia Gradle = feature registrada. Zero cambios en `sdk-wiring`.
**Contra:** Solo JVM (no KMP). Errores silenciosos si META-INF tiene typo. Cross-deps manuales vía resolver (mismo problema que B). No es compile-time safe a nivel de grafo completo.

**Indicado para:** SDKs JVM-only donde las features son mayoritariamente independientes y el auto-discovery es crítico (muchas features, muchos consumidores con distintas combinaciones).

---

### Dagger D — Component Dependencies

**Compatibilidad: ALTA (el modelo natural para esta arquitectura)**

**Requisito previo:** Módulo `sdk/di-contracts/` que contiene todas las
**provision interfaces** (plain Kotlin, SIN `@Component`), **scopes** per-feature y `RegistryInfra`.
Es un módulo único — no se divide en contratos per-feature.
Cada feature-impl depende de `di-contracts` para obtener las provision interfaces que necesita.

Dagger 2 no requiere que `dependencies=[...]` sea un `@Component` — acepta
**cualquier interfaz** con provision methods. Esto es lo que permite la separación limpia.

```
feature-core-contracts/            ← contrato CoreProvisions (plain Kotlin, depends on core-api)
  interface CoreProvisions {
    fun config(): SdkConfig
    fun logger(): SdkLogger
  }

feature-enc-contracts/             ← contrato EncProvisions + scope (depends on feature-enc-api)
  interface EncProvisions {
    fun encryption(): EncryptionApi
    fun hash(): HashApi
  }
  @Scope annotation class EncScope

feature-auth-contracts/            ← contrato AuthProvisions + scope (depends on feature-auth-api)
  interface AuthProvisions {
    fun auth(): AuthApi
  }
  @Scope annotation class AuthScope

sdk/di-contracts/                      ← umbrella: re-exports all contracts + RegistryInfra
  // No define interfaces propias — solo api() dependencies hacia feature-*-contracts

feature-core-impl/                 ← IMPLEMENTA CoreProvisions
  @Singleton
  @Component
  interface CoreComponent : CoreProvisions {  // ← hereda provision methods
    @Component.Builder interface Builder {
      @BindsInstance fun config(config: SdkConfig): Builder
      @BindsInstance fun logger(logger: SdkLogger): Builder
      fun build(): CoreComponent
    }
  }

feature-enc-impl/                  ← depende de feature-core-contracts (CoreProvisions), NO de feature-core-impl
  @EncScope
  @Component(dependencies = [CoreProvisions::class], modules = [EncModule::class])
  internal interface EncComponent : EncProvisions {
    @Component.Builder interface Builder {
      fun core(core: CoreProvisions): Builder
      fun build(): EncComponent
    }
  }

feature-auth-impl/                 ← depende de feature-core-contracts + feature-enc-contracts
  @AuthScope
  @Component(dependencies = [CoreProvisions::class, EncProvisions::class])
  internal interface AuthComponent : AuthProvisions {
    override fun auth(): AuthApi
    @Component.Builder interface Builder {
      fun core(core: CoreProvisions): Builder
      fun enc(enc: EncProvisions): Builder
      fun build(): AuthComponent
    }
  }

sdk/sdk-wiring/
  val core = DaggerCoreComponent.builder()...build()    // CoreComponent : CoreProvisions
  val enc = DaggerEncComponent.builder().core(core).build()  // acepta CoreProvisions
  val auth = DaggerAuthComponent.builder().core(core).enc(enc).build()
```

**Arquitectura del proyecto:**
Las features importan **provision interfaces** (`CoreProvisions`, `EncProvisions`),
no `@Component` classes de otros feature-impl. Los Components quedan `internal`
(o públicos tipados como provision interfaces en el wiring).

**Dependencias Gradle reales:**

```
feature-auth-impl
  ├── api(feature-auth-contracts)      → AuthProvisions, AuthScope
  ├── api(feature-core-contracts)      → CoreProvisions
  ├── api(feature-enc-contracts)       → EncProvisions (cross-dep contract)
  ├── implementation(impl-common)      → DefaultAuthApi (solo patrones monolíticos)
  ✘ NO depende de feature-enc-impl, feature-core-impl, NI di-contracts umbrella

sdk-wiring
  ├── implementation(feature-core-impl)       → DaggerCoreComponent
  ├── implementation(feature-enc-impl)        → DaggerEncComponent
  ├── implementation(feature-auth-impl)       → DaggerAuthComponent
  └── api(sdk:di-contracts)                   → umbrella → all provision interfaces
```

| Requisito | Cumple | Nota |
|-----------|--------|------|
| R1 Compilación aislada | ✅ | Cada `:impl` depende solo de `feature-*-contracts` específicos + `:api` modules |
| R2 App no importa impl | ✅ | App ve solo facade |
| R3 Features solo ven apis | ✅ | **Provision interfaces, no Components** |
| R4 Cross-feature | ✅ | **Automático — `dependencies=[EncProvisions]` resuelve todo** |
| R5 Lean binary | ✅ | Solo los `:impl` incluidos |
| R6 Escalabilidad | ❌ | **`when` blocks en facade crecen linealmente** |
| R7 Compile-time safety | ✅ | Dagger valida cada Component + dependencias en compilación |
| R8 Módulo wiring | ✅ | `sdk-wiring` construye en orden |

**Detalle clave — visibilidad de Components:**

Los Components son `internal` en cada `:impl`. `sdk-wiring` necesita construirlos.
Dos opciones:

```kotlin
// Opción 1: Factory function pública (Component interno)
// feature-auth:impl/
internal interface AuthComponent { ... }

fun createAuthApis(core: CoreProvisions, enc: EncProvisions): Map<Class<*>, Any> {
    val comp = DaggerAuthComponent.builder().core(core).enc(enc).build()
    return mapOf(AuthApi::class.java to comp.auth())
}

// Opción 2: Component público que extiende provision interface
// feature-auth:impl/
@Component(dependencies = [CoreProvisions::class, EncProvisions::class])
interface AuthComponent {  // público, pero tipado como AuthProvisions en sdk-wiring
    fun auth(): AuthApi
}
```

**Pro:** Cross-deps automáticas sin God Object. Compile-time safety completo. Pattern natural de Dagger multi-módulo. Features solo conocen contratos (provision interfaces).
**Contra:** El facade en `sdk-wiring` tiene `when` blocks que crecen con cada feature. A 50+ features, el facade es un archivo de 500+ líneas de wiring manual. Requiere módulo `di-contracts` adicional.

**Indicado para:** SDKs medianos (10–30 features) con muchas cross-deps, donde compile-time safety es prioritario y el equipo puede mantener el wiring central.

---

### Dagger E — Component Registry

**Compatibilidad: ALTA**

Usa las mismas provision interfaces per-feature (`feature-*-contracts/`) que D. La diferencia
es que cada feature provee un `FeatureEntry` en vez de requerir wiring manual en el facade.
El wiring vive en `sdk/wiring-e/`.

```
feature-*-contracts/
  // Mismas provision interfaces + scopes per-feature que en D
sdk/di-contracts/
  // Umbrella: re-exports all contracts + FeatureEntry, ComponentRegistry (infra del registry)

feature-auth:impl/
  internal interface AuthComponent : AuthProvisions { ... }

  val authEntry = FeatureEntry(
    feature = Feature.AUTH,
    componentClass = AuthComponent::class.java,
    dependencies = setOf(CoreProvisions::class.java, EncProvisions::class.java),
    build = { registry ->
      DaggerAuthComponent.builder()
        .core(registry.component(CoreProvisions::class.java))   // ← provision interface
        .enc(registry.component(EncProvisions::class.java))     // ← provision interface
        .build()
    },
    services = { c -> mapOf(AuthApi::class.java to c.auth()) },
  )

sdk-wiring/
  val registry = ComponentRegistry()
  listOf(coreEntry, encEntry, authEntry, ...).forEach { registry.register(it) }
```

La lambda `build` se ejecuta en el classloader de `:impl` — tiene acceso a
`DaggerAuthComponent` (código generado interno). El registry solo almacena
la lambda y la ejecuta cuando toca. `sdk-wiring` nunca importa Components.

| Requisito | Cumple | Nota |
|-----------|--------|------|
| R1 Compilación aislada | ✅ | Cada entry en su `:impl` |
| R2 App no importa impl | ✅ | Feature enum como selector |
| R3 Features solo ven apis | ✅ | `dependencies=[...]` sobre **provision interfaces** |
| R4 Cross-feature | ✅ | **Automático — registry + topo-sort** |
| R5 Lean binary | ✅ | Solo los `:impl` incluidos |
| R6 Escalabilidad | ⚠️ | **Feature enum crece linealmente** |
| R7 Compile-time safety | ✅ | Dagger valida Components. Registry valida orden en init |
| R8 Módulo wiring | ✅ | `sdk-wiring` recoge entries |

**Mejora sobre D:** El facade ya no tiene `when` blocks — `registry.register(entry)` es genérico. Pero el `Feature` enum sigue creciendo y el consumidor lo ve.

**Pro:** Desacopla facade de builders Dagger. Cross-deps automáticas. El facade es estable. Components permanecen `internal`.
**Contra:** Feature enum expuesto al consumidor. El enum es un fichero central que crece. Overhead ~20 ns/lookup (negligible).

**Indicado para:** SDKs que necesitan cross-deps automáticas y un facade más limpio que D, pero donde el equipo acepta mantener un Feature enum.

---

### Dagger E2 — Auto-Init Registry

**Compatibilidad: MUY ALTA**

Misma base de provision interfaces per-feature que D/E. Evolución de E: `serviceClasses`
permite indexar `Service → Entry` al hacer `install()`. `get<T>()` dispara
DFS recursivo que construye solo lo necesario. Sin Feature enum.
El wiring vive en `sdk/wiring-e2/`.

```
feature-*-contracts/
  // Provision interfaces + scopes per-feature (= D/E)
sdk/di-contracts/
  // Umbrella: re-exports all contracts + AutoFeatureEntry, AutoRegistry (infra)

feature-auth:impl/
  internal interface AuthComponent : AuthProvisions { ... }

  val authEntry = AutoFeatureEntry(
    componentClass = AuthComponent::class.java,
    dependencies = setOf(CoreProvisions::class.java, EncProvisions::class.java),
    serviceClasses = setOf(AuthApi::class.java),  // ← clave para indexar
    build = { registry ->
      DaggerAuthComponent.builder()
        .core(registry.component(CoreProvisions::class.java))
        .enc(registry.component(EncProvisions::class.java))
        .build()
    },
    services = { c -> mapOf(AuthApi::class.java to c.auth()) },
  )

sdk-wiring/
  val registry = AutoRegistry()
  allEntries().forEach { registry.install(it) }
  // Consumer: registry.get<AuthApi>() — auto-builds Auth + Enc + Core on demand
```

La lambda `build` se ejecuta dentro de `:impl` — accede a `DaggerAuthComponent`.
El registry solo ve `Class<*>` y lambdas. `sdk-wiring` nunca importa Components.

| Requisito | Cumple | Nota |
|-----------|--------|------|
| R1 Compilación aislada | ✅ | Cada entry en su `:impl` |
| R2 App no importa impl | ✅ | **API mínima: init() + get<T>()** |
| R3 Features solo ven apis | ✅ | Entries declaran dependencias sobre **provision interfaces** |
| R4 Cross-feature | ✅ | **Automático — DFS recursivo on demand** |
| R5 Lean binary | ✅ | Solo `:impl` incluidos. Lazy — no construye lo que no se pide |
| R6 Escalabilidad | ✅ | **Añadir feature = 1 línea en `allEntries()`. Sin enum, sin `when`** |
| R7 Compile-time safety | ✅ | Dagger valida Components. DFS valida dependencias en runtime |
| R8 Módulo wiring | ✅ | `sdk-wiring` recoge entries con `allEntries()` |

**Cómo mapea a la arquitectura api/impl/integration:**

```
feature-*-contracts/
  → CoreProvisions, EncProvisions, AuthProvisions... (plain interfaces per-feature)
  → @EncScope, @AuthScope... (scopes per-feature)
sdk/di-contracts/
  → Umbrella: re-exports all contracts
  → AutoFeatureEntry, AutoRegistry (infra)

feature-auth-impl/
  → AuthComponent (internal, implements AuthProvisions)
  → authEntry (AutoFeatureEntry — público, es solo data + lambda)
  → depends on: feature-auth-contracts, feature-core-contracts, feature-enc-contracts

sdk/wiring-e2/
  → depends on: ALL feature-*-impl
  → fun allEntries() = listOf(coreEntry, encEntry, authEntry, ...)
  → object MySdk {
        fun init(config: SdkConfig) {
            allEntries(config).forEach { registry.install(it) }
        }
        inline fun <reified T> get(): T = registry.get(T::class.java)
    }

sample-multimodule/
  → depends on: wiring-e2 (implementation), feature-*-api
  → MySdk.init(config)
  → val auth: AuthApi = MySdk.get()  // auto-builds Enc + Core + Auth
```

**Por qué E2 encaja naturalmente:**

E2 separa declaración (entry = data) de construcción (lambda ejecutada en `:impl`).
El Component es `internal`. El entry es público pero no expone tipos Dagger — solo
`Class<*>`, `Set<Class<*>>` y lambdas. `sdk-wiring` nunca necesita `import DaggerAuthComponent`.

**Pro:**
- La API más limpia para el consumidor: `init()` + `get<T>()`.
- Sin Feature enum — el consumidor no elige features, pide servicios.
- Lazy real — `get<AuthApi>()` construye Auth + sus deps, nada más.
- Añadir feature 51 = un entry + una línea en `allEntries()`.
- Compile-time safety por Component individual.
- Components `internal` — máximo aislamiento.

**Contra:**
- `allEntries()` es un punto central (aunque solo crece en 1 línea por feature).
- DFS resuelve en runtime — si una dependencia no fue installed, falla en runtime.
- ~3 µs overhead vs D en init cold (negligible: 0.0002 frames).
- 3 clases de infraestructura propias (DiComponent, AutoFeatureEntry, AutoRegistry).
- Requiere módulos `feature-*-contracts/` (provision interfaces per-feature) + `sdk/di-contracts/` (umbrella + infra registry).

**Indicado para:** SDKs que escalan a 50+ features con cross-deps abundantes. El approach Dagger que mejor mapea a esta arquitectura.

---

### Dagger G — Factory Functions

**Compatibilidad: ALTA**

Misma base de provision interfaces per-feature que D/E/E2. Cada feature-impl expone una
**factory function** pública (`buildXxxProvisions(deps): XxxProvisions`) que encapsula la
construcción del `DaggerXxxComponent`. El Component queda `internal` — el wiring module
nunca lo importa. El wiring usa el mismo patrón lazy `ensure*()` que D, pero llama
factory functions en vez de builders Dagger.

```
feature-*-contracts/
  // Provision interfaces + scopes per-feature (= D/E/E2)
sdk/di-contracts/
  // Umbrella: re-exports all contracts

feature-enc-impl/
  internal interface EncComponent : EncProvisions { ... }

  // Factory function pública — Component oculto
  fun buildEncProvisions(core: CoreProvisions): EncProvisions {
    return DaggerEncComponent.builder().core(core).build()
  }

feature-auth-impl/
  internal interface AuthComponent : AuthProvisions { ... }

  fun buildAuthProvisions(core: CoreProvisions, enc: EncProvisions): AuthProvisions {
    return DaggerAuthComponent.builder().core(core).enc(enc).build()
  }

sdk/wiring-g/
  // Lazy ensure*() — llama factory functions, no DaggerXxx builders
  private var _enc: EncProvisions? = null

  private fun ensureEnc(core: CoreProvisions): EncProvisions {
    return _enc ?: buildEncProvisions(core).also { _enc = it }
  }

  private fun ensureAuth(core: CoreProvisions): AuthProvisions {
    val enc = ensureEnc(core)
    return _auth ?: buildAuthProvisions(core, enc).also { _auth = it }
  }
```

| Requisito | Cumple | Nota |
|-----------|--------|------|
| R1 Compilación aislada | ✅ | Cada `:impl` compila solo — Component `internal` |
| R2 App no importa impl | ✅ | App ve solo facade |
| R3 Features solo ven apis | ✅ | **Provision interfaces, no Components** |
| R4 Cross-feature | ✅ | **Automático — factory functions reciben provision interfaces** |
| R5 Lean binary | ✅ | Solo los `:impl` incluidos |
| R6 Escalabilidad | ❌ | **`ensure*()` en facade crecen linealmente (igual que D)** |
| R7 Compile-time safety | ✅ | Dagger valida cada Component + dependencias en compilación |
| R8 Módulo wiring | ✅ | `wiring-g` llama factory functions en orden |

**Diferenciador vs D:** En D, `sdk-wiring` importa `DaggerXxxComponent` directamente
(el Component es público o visible). En G, el Component es `internal` y cada feature-impl
solo expone una factory function. El wiring module tiene menos acoplamiento a tipos Dagger.

**Diferenciador vs E2:** E2 usa un registry con auto-discovery (DFS on-demand) que
elimina la necesidad de ordenar dependencias manualmente. G no tiene registry — el wiring
module ordena manualmente, igual que D. A 50+ features, G tiene la misma limitación
de escalabilidad que D.

**Pro:** Components 100% `internal`. Máximo encapsulamiento de Dagger. Sin registry overhead. Zero clases de infraestructura propias. Resolución directa (campo, no HashMap).
**Contra:** El facade crece linealmente con cada feature (igual que D). El wiring module conoce el orden de dependencias. Para 50+ features, el facade es verbose. No hay auto-discovery.

**Indicado para:** SDKs medianos (10–30 features) donde el encapsulamiento de Components es prioritario y el equipo prefiere simplicidad (sin registry) a cambio de wiring manual.

---

### Koin — Service Locator

**Compatibilidad: MUY ALTA (diseñado para este patrón)**

```
feature-auth:impl/
  fun authModule() = module {
    single<AuthApi> { DefaultAuthApi(get(), get()) }
  }

sdk-wiring/
  fun sdkModules() = listOf(
    coreModule(), encryptionModule(), authModule(),
    storageModule(), syncModule(), analyticsModule(),
  )

  object MySdk {
    private lateinit var koin: KoinApplication
    fun init(config: SdkConfig) {
      koin = koinApplication { modules(sdkModules()) }
    }
    inline fun <reified T : Any> get(): T = koin.koin.get()
  }
```

| Requisito | Cumple | Nota |
|-----------|--------|------|
| R1 Compilación aislada | ✅ | Cada módulo Koin en su `:impl` |
| R2 App no importa impl | ✅ | `MySdk.get<T>()` devuelve interfaz |
| R3 Features solo ven apis | ✅ | `get()` resuelve interfaces — zero imports de otros impl |
| R4 Cross-feature | ✅ | **Automático — `get()` desde el mismo grafo** |
| R5 Lean binary | ✅ | Solo `:impl` incluidos |
| R6 Escalabilidad | ✅ | **Añadir feature = 1 función módulo + 1 línea en `sdkModules()`** |
| R7 Compile-time safety | ❌ | **Resolución runtime. Errores en runtime** |
| R8 Módulo wiring | ✅ | `sdk-wiring` lista módulos |

**Opción avanzada: auto-discovery**

Con `Class.forName` o `@EagerInit`, Koin puede descubrir módulos sin listarlos:

```kotlin
// Cada :impl registra su módulo con una convención
// sdk-wiring descubre todos los que están en classpath
ServiceLoader.load(KoinModuleProvider::class.java)
  .forEach { modules.add(it.module()) }
```

Esto elimina incluso `sdkModules()` — verdadero Nivel 2 de aislamiento.

**Pro:**
- El approach que MEJOR mapea a api/impl/integration. Zero fricción.
- Cross-deps automáticas sin God Object, sin registry, sin enum.
- KMP completo (iOS, macOS, Desktop, JS).
- Auto-discovery posible — Nivel 2 de aislamiento.
- Build rápido (zero codegen).

**Contra:**
- **No compile-time safety.** Si `feature-encryption:impl` no está en classpath, `get<EncryptionApi>()` crashea en runtime.
- Mitigación: `checkModules()` en tests — pero es test-time, no compile-time.
- Overhead runtime: ~50 µs init cold, ~900 ns resolve (vs ~2.4 ns Dagger).
- En una app real, irrelevante (< 0.003 frames).

**Indicado para:** SDKs de cualquier tamaño, especialmente:
- SDKs KMP (cross-platform obligatorio).
- Equipos que priorizan velocidad de desarrollo sobre compile-time safety.
- SDKs con 50+ features — escala trivialmente.
- Cuando la app consumidora también usa Koin.

---

### Hybrid — Koin SDK + Dagger App

**Compatibilidad: MUY ALTA**

Idéntico a Koin internamente. La diferencia es que la app consumidora usa Dagger
y un `@Component` bridge conecta servicios del SDK al grafo Dagger de la app.

| Requisito | Cumple | Nota |
|-----------|--------|------|
| R1-R6 | = Koin | El SDK internamente es Koin — mismas propiedades |
| R7 Compile-time safety | ⚠️ | SDK: runtime. Bridge: compile-time (Dagger valida @Provides) |
| R8 Módulo wiring | ✅ | `sdk-wiring` (Koin) + bridge Dagger en la app |

**Pro:** Máxima flexibilidad — SDK KMP con Koin, apps Dagger con bridge.
**Contra:** Dos contenedores runtime. Un `@Provides` por servicio en el bridge. Puente unidireccional (app ← SDK).

**Indicado para:** SDKs que deben ser consumidos por apps Dagger existentes sin forzar migración a Koin.

---

## Resumen de Compatibilidad

| Approach | Compat. | R1 Compile aislado | R2 App aislada | R3 Api-only | R4 Cross-deps | R5 Lean binary | R6 Escala 50+ | R7 Compile-safe | R8 Wiring |
|----------|---------|---------------------|----------------|-------------|---------------|----------------|---------------|-----------------|-----------|
| **A** | 🔴 Baja | ⚠️ | ✅ | ✅ | ✅ | ❌ | ❌ | ✅ | ✅ |
| **B** | 🟡 Media | ✅ | ✅ | ✅ | ⚠️ | ✅ | ❌ | ✅ | ✅ |
| **C** | 🟡 Media | ✅ | ✅ | ✅ | ⚠️ | ✅ | ⚠️ | ⚠️ | ✅ |
| **D** | 🟢 Alta | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ | ✅ | ✅ |
| **E** | 🟢 Alta | ✅ | ✅ | ✅ | ✅ | ✅ | ⚠️ | ✅ | ✅ |
| **E2** | 🟢 Muy alta | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **G** | 🟢 Alta | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ | ✅ | ✅ |
| **Koin** | 🟢 Muy alta | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ | ✅ |
| **Hybrid** | 🟢 Muy alta | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ⚠️ | ✅ |

---

## Detalle de Limitaciones a Escala

### ¿Qué crece linealmente al añadir features?

| Approach | Qué crece | Dónde crece | Impacto real a 50+ |
|----------|-----------|-------------|---------------------|
| **A** | Lista `modules=[...]` en @Component | `sdk-wiring` | Anotación ilegible |
| **B** | Interfaces CoreApis | Cada `:impl` con cross-deps | God Object — sabotea aislamiento |
| **C** | Archivos META-INF | Cada `:impl` | Bajo — pero errores silenciosos |
| **D** | `when` blocks en facade | `sdk-wiring` | Facade de 500+ líneas |
| **E** | Feature enum + `when` blocks | `sdk-wiring` + api | Enum de 50+ entries |
| **E2** | `allEntries()` list | `sdk-wiring` | 1 línea por feature — manejable |
| **G** | `ensure*()` en facade | `wiring-g` | = D (sin imports de DaggerXxx) |
| **Koin** | `sdkModules()` list | `sdk-wiring` | 1 línea por feature — manejable |
| **Hybrid** | = Koin + bridge @Provides | `sdk-wiring` + app bridge | Bridge crece 1 línea/servicio |

### ¿Qué rompe la arquitectura a escala?

```
                     ┌──────────────────────────────────┐
                     │  ESCALA SIN PROBLEMAS (50+)      │
                     │                                   │
                     │  E2    Koin    Hybrid             │
                     └──────────────────────────────────┘

    ┌───────────────────────────────────┐
    │  FUNCIONA HASTA ~30 FEATURES      │
    │                                    │
    │  D    E    G                       │
    │  (when blocks, enum maintenance)   │
    └───────────────────────────────────┘

 ┌──────────────────────────────────────────┐
 │  RIESGOSO A > 15 FEATURES               │
 │                                           │
 │  B (God Object)    C (errores silenciosos)│
 └──────────────────────────────────────────┘

┌────────────────────────────────────────────────┐
│  NO ADECUADO PARA ESTA ARQUITECTURA            │
│                                                 │
│  A (sin lean binary, sin init selectiva real)   │
└────────────────────────────────────────────────┘
```

---

## Visibilidad de Components: El Detalle Gradle

**Implementado en:** `feature-*-impl/`, `sdk/sdk-wiring/` (D), `sdk/wiring-e/` (E), `sdk/wiring-e2/` (E2), `sdk/wiring-g/` (G), `sample-multimodule/`

En la arquitectura api/impl, `sdk-wiring` necesita construir `DaggerAuthComponent`.
¿El Component de `feature-auth-impl` debe ser público?

### Solución implementada: Component público con Provision Interface

**Código real** — ver `feature-auth-impl/AuthComponent.kt`:

```kotlin
// feature-auth-impl — Component público, pero tipado como AuthProvisions
@AuthScope
@Component(
    dependencies = [CoreProvisions::class, EncProvisions::class],  // ← contratos
    modules = [AuthModule::class],
)
interface AuthComponent : AuthProvisions {
    override fun auth(): AuthApi
    @Component.Builder interface Builder {
        fun core(core: CoreProvisions): Builder
        fun enc(enc: EncProvisions): Builder
        fun build(): AuthComponent
    }
}
```

`sdk-wiring` importa `DaggerAuthComponent` pero almacena el resultado como
`AuthProvisions` (la provision interface). El resto del código solo ve el contrato.

**Código real** — ver `sdk/sdk-wiring/MultiModuleSdk.kt`:

```kotlin
// sdk-wiring — almacena provision interfaces, no Components
private var _auth: AuthProvisions? = null

private fun ensureAuth(core: CoreProvisions): AuthProvisions {
    val enc = ensureEnc(core)
    return _auth ?: DaggerAuthComponent.builder()
        .core(core).enc(enc).build()
        .also { _auth = it }
}
```

### Alternativa: Factory function (Component interno) — Pattern G

```kotlin
// feature-auth-impl — Component completamente interno
internal interface AuthComponent : AuthProvisions { ... }

// Factory pública que oculta el Component
fun buildAuthProvisions(core: CoreProvisions, enc: EncProvisions): AuthProvisions {
    return DaggerAuthComponent.builder().core(core).enc(enc).build()
}
```

**Pro:** Component 100% oculto. Solo la factory es pública.
**Contra:** Boilerplate — una factory por feature.

Este es el patrón que implementa Pattern G (`sdk/wiring-g/`). El wiring llama
`buildAuthProvisions(core, enc)` en vez de `DaggerAuthComponent.builder()`.
Ver sección [Dagger G — Factory Functions](#dagger-g--factory-functions) para
el análisis completo.

### Alternativa: Entry con build lambda (E2 natural)

```kotlin
// feature-auth-impl — Component interno, entry público
internal interface AuthComponent : AuthProvisions { ... }

val authEntry = AutoFeatureEntry(
    componentClass = AuthComponent::class.java,
    dependencies = setOf(CoreProvisions::class.java, EncProvisions::class.java),
    build = { registry ->
        DaggerAuthComponent.builder()
            .core(registry.component(CoreProvisions::class.java))
            .enc(registry.component(EncProvisions::class.java))
            .build()
    },
    ...
)
```

**Pro:** El Component es `internal`. La lambda se ejecuta en `:impl` — accede al código generado.
**Contra:** El entry expone `Class<AuthComponent>` como `Class<*>`, no como tipo usable.

**E2 encaja naturalmente** porque su diseño separa declaración (entry) de construcción (lambda).

---

## Matriz de Decisión

| Escenario | Approach recomendado | Alternativa |
|-----------|---------------------|-------------|
| SDK KMP (iOS + Android + Desktop) | **Koin** | Hybrid (si app usa Dagger) |
| SDK Android-only, < 15 features, independientes | **D** o **G** | B (si zero cross-deps) |
| SDK Android-only, 15–30 features, con cross-deps | **G** o **E** | E2 (si quieres futuro-proof) |
| SDK Android-only, 30+ features, cross-deps pesadas | **E2** | Koin (si compile-time no es crítico) |
| SDK Android-only, 50+ features | **E2** o **Koin** | Híbrido (ambos mundos) |
| App consumidora usa Dagger, SDK debe ser KMP | **Hybrid** | — |
| Máxima compile-time safety, < 20 features | **D** / **G** | — |
| Encapsulamiento máximo de Components (internal) | **G** o **E2** | — |
| Máxima velocidad de desarrollo | **Koin** | — |
| Features 100% independientes (zero cross-deps) | **B** | C (si auto-discovery importa) |

---

## Implementacion Real: Per-Feature Contracts

### Por que se dividio di-contracts en contratos per-feature

Originalmente, `sdk/di-contracts/` contenia TODAS las provision interfaces y scopes en un solo
modulo. Esto tenia un problema Gradle: cualquier feature-impl que dependiera de `di-contracts`
veia transitivamente TODAS las provision interfaces de TODAS las features, incluso las que no
necesitaba. Cada cambio en cualquier contrato invalidaba la compilacion de todos los feature-impl.

La solucion fue extraer cada contrato a su propio modulo:

```
feature-enc-contracts/     → solo EncProvisions + EncScope
feature-auth-contracts/    → solo AuthProvisions + AuthScope
sdk/di-contracts/              → umbrella: api() hacia todos los contracts + RegistryInfra
```

Ahora `feature-auth-impl` depende de `feature-auth-contracts` + `feature-core-contracts` +
`feature-enc-contracts` (cross-dep). Si `feature-ana-contracts` cambia, `feature-auth-impl`
**no recompila** — aislamiento Gradle real.

### Por que se extrajo core-api

`CoreProvisions` (en `feature-core-contracts`) expone `SdkConfig` y `SdkLogger`. Si esos tipos
vivieran en `sdk/api` (umbrella de todas las feature-apis), entonces `feature-core-contracts`
dependeria del umbrella, y cualquier feature-impl que dependiera de `feature-core-contracts`
veria transitivamente todas las APIs de todas las features.

Para evitarlo, `SdkConfig` se extrajo a `feature-core-api/` y `SdkLogger` a `observability-api/`:

```
feature-core-api/              → SdkConfig (zero deps)
observability-api/             → SdkLogger (interface)
feature-observability-impl/    → AndroidSdkLogger (impl)
feature-core-contracts/    → CoreProvisions (depende solo de core-api, no de sdk/api)
```

### Limitacion conocida: impl-common y sdk/api

Los feature-impl dependen de `impl-common` para compartir implementaciones base. `impl-common`
a su vez depende de `sdk/api` (umbrella), lo que transitivamente expone todas las feature-apis
a todos los feature-impl. Esto es una limitacion aceptada en el demo:

```
feature-auth-impl → impl-common → sdk/api → EncryptionApi, AuthApi, SyncApi...
```

En produccion, `impl-common` deberia depender solo de `core-api` y los feature-impl deberian
depender directamente de los feature-*-api especificos que necesiten.

### Insight clave: aislamiento a nivel de contratos

Aunque impl-common filtre todas las APIs, lo importante es que **a nivel de contratos DI** cada
feature-impl solo ve las provision interfaces que necesita. `feature-auth-impl` depende de
`feature-core-contracts` + `feature-enc-contracts` — nunca ve `AnaProvisions` ni `SynProvisions`.
Esto significa que el grafo de dependencias DI esta correctamente acotado, incluso si el grafo
de APIs de negocio es mas permisivo.

---

## Conclusion

Para la arquitectura **api / impl / integration** con visibilidad estricta:

1. **Koin y E2 son los approaches que mejor escalan** sin comprometer los requisitos.
   - **Koin:** si el equipo acepta resolución runtime (mitigable con `checkModules()` en tests).
   - **E2:** si compile-time safety es requisito duro (Dagger valida cada Component).

2. **D y G son sólidos hasta ~30 features.**
   G es D con factory functions (Components `internal`). En ambos, el wiring manual
   en el facade es el cuello de botella a escala.

3. **E mejora sobre D/G** eliminando `when` blocks, pero el Feature enum expuesto
   al consumidor y su crecimiento lineal lo limitan.

4. **B y C son para features independientes.** Si las features tienen cross-deps
   significativas, estos approaches no escalan.

5. **A no es adecuado** para esta arquitectura — la separación Gradle pierde sentido
   si todo se compila en un `@Component` monolítico.

6. **Hybrid** es la respuesta cuando el SDK es KMP pero la app consumidora es Dagger.

7. **Provision interfaces son el enabler** para D, E, E2 y G en multi-módulo.
   Sin ellas, los features dependerían de `@Component` classes (implementaciones)
   de otros features — violando la regla de "features solo conocen apis".
   Dagger acepta cualquier interfaz en `dependencies=[...]`, no requiere `@Component`.
   G lleva esto un paso más allá: los Components son `internal` y solo se exponen
   factory functions que retornan provision interfaces.
   Ver implementación real en `feature-*-contracts/` (per-feature) + `sdk/di-contracts/` (umbrella) + `feature-*-impl/`.

**No hay approach universalmente mejor.** La elección depende de: tamaño del SDK,
cantidad de cross-deps, requisito de KMP, compile-time safety, y equipo disponible
para mantener el wiring.
