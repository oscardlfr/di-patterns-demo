# Approaches DI para Arquitectura api / impl / integration

Análisis de compatibilidad de cada approach DI con una arquitectura multi-módulo Gradle
donde las features se organizan en módulos `api`, `impl` e `integration`.

**Ejemplo realista implementado** en este proyecto:
- `sdk/di-contracts/` — provision interfaces + scopes
- `sdk/feature-*-impl/` — Dagger Components que implementan provision interfaces
- `sdk/sdk-wiring/` — facade que ensambla todo con `api`/`implementation` correctos
- `sample-multimodule/` — app consumidora que solo depende de `sdk-wiring`

Para implementaciones Dagger, ver [dagger2-sdk-selective-init.md](dagger2-sdk-selective-init.md).
Para conceptos DI, ver [di-sdk-consumer-isolation.md](di-sdk-consumer-isolation.md).
Para dependencias cruzadas, ver [di-cross-feature-deps.md](di-cross-feature-deps.md).
Para el approach hybrid, ver [di-hybrid-koin-sdk-dagger-app.md](di-hybrid-koin-sdk-dagger-app.md).

---

## La Arquitectura

```
feature-encryption/
  api/           → EncryptionService (interfaz pura, 0 deps DI)
  impl/          → DefaultEncryptionService + wiring DI
  integration/   → test doubles, fakes, helpers para tests

feature-auth/
  api/           → AuthService
  impl/          → DefaultAuthService + wiring DI
  integration/   → test doubles

feature-storage/
  api/           → SecureStorageService
  impl/          → DefaultSecureStorageService + wiring DI
  integration/   → test doubles

feature-sync/
  api/           → SyncService
  impl/          → DefaultSyncService + wiring DI
  integration/   → test doubles

core/
  api/           → SdkConfig, Logger (interfaces compartidas)
  impl/          → CoreModule, singletons, logger real

sdk-wiring/      → Ensambla todo. Depende de TODOS los :impl
app/             → Depende de sdk-wiring + feature-x:api
```

### Reglas de visibilidad Gradle

```
feature-auth:impl  →  depends on  →  feature-auth:api
                                      feature-encryption:api  (cross-dep)
                                      core:api
                   →  NUNCA depende de →  feature-encryption:impl

app                →  depends on  →  sdk-wiring (implementation)
                                     feature-x:api (solo interfaces)
                   →  NUNCA depende de →  feature-x:impl

sdk-wiring         →  depends on  →  TODOS los :impl (para ensamblar)
                   →  expone solo  →  tipos de :api
```

### Requisitos derivados

| # | Requisito | Por qué |
|---|-----------|---------|
| R1 | **Compilación aislada por feature** | Cada `:impl` compila solo. No necesita otros `:impl` |
| R2 | **App no importa impl** | La app solo ve interfaces de `:api` |
| R3 | **Features solo ven apis** | `feature-auth:impl` depende de `feature-encryption:api`, no `:impl` |
| R4 | **Cross-feature automáticas** | Auth inyecta EncryptionService sin wiring manual |
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
    fun auth(): AuthService
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
| R3 Features solo ven apis | ✅ | `AuthCoreApis` recibe `EncryptionService` (interfaz de `:api`) |
| R4 Cross-feature | ⚠️ | **Manual** — CoreApis extendido por cada cross-dep |
| R5 Lean binary | ✅ | Solo se compilan los `:impl` incluidos |
| R6 Escalabilidad | ❌ | **God Object — cada cross-dep añade un campo a CoreApis** |
| R7 Compile-time safety | ✅ | Dagger valida cada Component en compilación |
| R8 Módulo wiring | ✅ | `sdk-wiring` construye y conecta Components |

**El problema del God Object en multi-módulo:**

```kotlin
// feature-auth:impl — necesita EncryptionService
interface AuthCoreApis : CoreApis {
    val encryptionService: EncryptionService
}

// feature-sync:impl — necesita Auth + Storage + Encryption
interface SyncCoreApis : CoreApis {
    val authService: AuthService
    val storageService: SecureStorageService
    val encryptionService: EncryptionService
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
      val enc = resolver.get<EncryptionService>()
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

**Requisito previo:** Un módulo `sdk/di-contracts` (o `sdk/di-core`) que contiene
**provision interfaces** (plain Kotlin, SIN `@Component`) y **scopes**.

Dagger 2 no requiere que `dependencies=[...]` sea un `@Component` — acepta
**cualquier interfaz** con provision methods. Esto es lo que permite la separación limpia.

```
sdk/di-contracts/                      ← contratos DI (plain Kotlin interfaces + scopes)
  interface CoreProvisions {
    fun config(): SdkConfig
    fun logger(): Logger
  }
  interface EncProvisions {
    fun encryption(): EncryptionService
    fun hash(): HashService
  }
  @Scope annotation class EncScope
  @Scope annotation class AuthScope
  // ...

core:impl/                             ← IMPLEMENTA CoreProvisions
  @Singleton
  @Component
  interface CoreComponent : CoreProvisions {  // ← hereda provision methods
    @Component.Builder interface Builder {
      @BindsInstance fun config(config: SdkConfig): Builder
      @BindsInstance fun logger(logger: Logger): Builder
      fun build(): CoreComponent
    }
  }

feature-encryption:impl/               ← depende de CoreProvisions (contrato), NO de CoreComponent (impl)
  @EncScope
  @Component(dependencies = [CoreProvisions::class], modules = [EncModule::class])
  internal interface EncComponent : EncProvisions {
    @Component.Builder interface Builder {
      fun core(core: CoreProvisions): Builder
      fun build(): EncComponent
    }
  }

feature-auth:impl/                     ← depende de CoreProvisions + EncProvisions (contratos)
  @AuthScope
  @Component(dependencies = [CoreProvisions::class, EncProvisions::class])
  internal interface AuthComponent {
    fun auth(): AuthService
    @Component.Builder interface Builder {
      fun core(core: CoreProvisions): Builder
      fun enc(enc: EncProvisions): Builder
      fun build(): AuthComponent
    }
  }

sdk-wiring/
  val core = DaggerCoreComponent.builder()...build()    // CoreComponent : CoreProvisions
  val enc = DaggerEncComponent.builder().core(core).build()  // acepta CoreProvisions
  val auth = DaggerAuthComponent.builder().core(core).enc(enc).build()
```

**Lo que cambia vs el demo del proyecto:**
- En el demo, `CoreComponent` es un `@Component` en `sdk/di-core` y las features lo importan directamente.
  Esto funciona porque el demo es educativo y todo vive en pocos módulos.
- En una arquitectura real api/impl/integration, las features importan **provision interfaces**
  (`CoreProvisions`, `EncProvisions`), no `@Component` classes. Los Components quedan `internal`.

**Dependencias Gradle reales:**

```
feature-auth:impl
  ├── api(feature-auth:api)           → AuthService interface
  ├── api(feature-encryption:api)     → EncryptionService interface (para la impl)
  ├── api(sdk:di-contracts)           → CoreProvisions, EncProvisions, scopes
  └── implementation(core:api)        → SdkConfig, Logger interfaces
  ✘ NO depende de core:impl
  ✘ NO depende de feature-encryption:impl

sdk-wiring
  ├── implementation(core:impl)             → DaggerCoreComponent
  ├── implementation(feature-encryption:impl)  → factory o entry
  ├── implementation(feature-auth:impl)     → factory o entry
  └── api(sdk:di-contracts)                 → provision interfaces
```

| Requisito | Cumple | Nota |
|-----------|--------|------|
| R1 Compilación aislada | ✅ | Cada `:impl` depende solo de `di-contracts` + `:api` modules |
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

fun createAuthServices(core: CoreProvisions, enc: EncProvisions): Map<Class<*>, Any> {
    val comp = DaggerAuthComponent.builder().core(core).enc(enc).build()
    return mapOf(AuthService::class.java to comp.auth())
}

// Opción 2: Component público que extiende provision interface
// feature-auth:impl/
@Component(dependencies = [CoreProvisions::class, EncProvisions::class])
interface AuthComponent {  // público, pero tipado como AuthProvisions en sdk-wiring
    fun auth(): AuthService
}
```

**Pro:** Cross-deps automáticas sin God Object. Compile-time safety completo. Pattern natural de Dagger multi-módulo. Features solo conocen contratos (provision interfaces).
**Contra:** El facade en `sdk-wiring` tiene `when` blocks que crecen con cada feature. A 50+ features, el facade es un archivo de 500+ líneas de wiring manual. Requiere módulo `di-contracts` adicional.

**Indicado para:** SDKs medianos (10–30 features) con muchas cross-deps, donde compile-time safety es prioritario y el equipo puede mantener el wiring central.

---

### Dagger E — Component Registry

**Compatibilidad: ALTA**

Usa las mismas provision interfaces de `sdk/di-contracts` que D. La diferencia
es que cada feature provee un `FeatureEntry` en vez de requerir wiring manual en el facade.

```
sdk/di-contracts/
  // Mismas provision interfaces + scopes que en D
  // + FeatureEntry, ComponentRegistry (infra del registry)

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
    services = { c -> mapOf(AuthService::class.java to c.auth()) },
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

Misma base de provision interfaces que D/E. Evolución de E: `serviceClasses`
permite indexar `Service → Entry` al hacer `install()`. `get<T>()` dispara
DFS recursivo que construye solo lo necesario. Sin Feature enum.

```
sdk/di-contracts/
  // Provision interfaces + scopes + AutoFeatureEntry, AutoRegistry (infra)

feature-auth:impl/
  internal interface AuthComponent : AuthProvisions { ... }

  val authEntry = AutoFeatureEntry(
    componentClass = AuthComponent::class.java,
    dependencies = setOf(CoreProvisions::class.java, EncProvisions::class.java),
    serviceClasses = setOf(AuthService::class.java),  // ← clave para indexar
    build = { registry ->
      DaggerAuthComponent.builder()
        .core(registry.component(CoreProvisions::class.java))
        .enc(registry.component(EncProvisions::class.java))
        .build()
    },
    services = { c -> mapOf(AuthService::class.java to c.auth()) },
  )

sdk-wiring/
  val registry = AutoRegistry()
  allEntries().forEach { registry.install(it) }
  // Consumer: registry.get<AuthService>() — auto-builds Auth + Enc + Core on demand
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
sdk/di-contracts/
  → CoreProvisions, EncProvisions, AuthProvisions... (plain interfaces)
  → @EncScope, @AuthScope... (scopes)
  → AutoFeatureEntry, AutoRegistry (infra)

feature-auth/
  api/    → AuthService (interfaz de negocio)
  impl/   → AuthComponent (internal, implements AuthProvisions)
            + authEntry (AutoFeatureEntry — público, es solo data + lambda)
            depends on: feature-auth:api, feature-encryption:api, sdk:di-contracts

sdk-wiring/
  → depends on: ALL feature-x:impl
  → fun allEntries() = listOf(coreEntry, encEntry, authEntry, ...)
  → object MySdk {
        fun init(config: SdkConfig) {
            allEntries(config).forEach { registry.install(it) }
        }
        inline fun <reified T> get(): T = registry.get(T::class.java)
    }

app/
  → depends on: sdk-wiring (implementation), feature-x:api
  → MySdk.init(config)
  → val auth: AuthService = MySdk.get()  // auto-builds Enc + Core + Auth
```

**Por qué E2 encaja naturalmente:**

E2 separa declaración (entry = data) de construcción (lambda ejecutada en `:impl`).
El Component es `internal`. El entry es público pero no expone tipos Dagger — solo
`Class<*>`, `Set<Class<*>>` y lambdas. `sdk-wiring` nunca necesita `import DaggerAuthComponent`.

**Pro:**
- La API más limpia para el consumidor: `init()` + `get<T>()`.
- Sin Feature enum — el consumidor no elige features, pide servicios.
- Lazy real — `get<AuthService>()` construye Auth + sus deps, nada más.
- Añadir feature 51 = un entry + una línea en `allEntries()`.
- Compile-time safety por Component individual.
- Components `internal` — máximo aislamiento.

**Contra:**
- `allEntries()` es un punto central (aunque solo crece en 1 línea por feature).
- DFS resuelve en runtime — si una dependencia no fue installed, falla en runtime.
- ~3 µs overhead vs D en init cold (negligible: 0.0002 frames).
- 3 clases de infraestructura propias (DiComponent, AutoFeatureEntry, AutoRegistry).
- Requiere módulo `sdk/di-contracts` (provision interfaces + infra registry).

**Indicado para:** SDKs que escalan a 50+ features con cross-deps abundantes. El approach Dagger que mejor mapea a esta arquitectura.

---

### Dagger F — Multi-Module Component Dependencies

**Compatibilidad: ALTA (es D con organización Gradle correcta)**

F = D en runtime (benchmarks lo prueban: 0% overhead). Usa las mismas
provision interfaces de `sdk/di-contracts`. La diferencia con D es que el
demo del proyecto pone `CoreComponent` en un módulo Gradle separado (`sdk/di-core`);
en la arquitectura real, `sdk/di-contracts` contendría las provision interfaces
y cada `:impl` las implementaría — idéntico a D.

```
sdk/di-contracts/                      ← provision interfaces + scopes (= D)
  interface CoreProvisions { ... }
  interface EncProvisions { ... }

core:impl/
  @Component interface CoreComponent : CoreProvisions { ... }

feature-encryption:impl/
  @Component(dependencies = [CoreProvisions::class])   ← contrato, no impl
  internal interface EncComponent : EncProvisions { ... }

sdk-wiring/
  // Idéntico a D — construye en orden, facade con when blocks
```

**Nota sobre el demo vs la arquitectura real:**

En el demo (`sdk/di-core`), `CoreComponent` es un `@Component` público que los
features importan directamente. Esto funciona porque el demo no tiene separación
api/impl real. En la arquitectura api/impl/integration, los features importarían
`CoreProvisions` (provision interface en `di-contracts`), no el `@Component`.

| Requisito | Cumple | Nota |
|-----------|--------|------|
| R1 Compilación aislada | ✅ | Provision interfaces en módulo compartido, features compilan solo |
| R2 App no importa impl | ✅ | App ve solo facade |
| R3 Features solo ven apis | ✅ | **Provision interfaces, no Components** |
| R4 Cross-feature | ✅ | `dependencies=[EncProvisions]` de Dagger |
| R5 Lean binary | ✅ | Solo `:impl` incluidos |
| R6 Escalabilidad | ❌ | **`when` blocks en facade no escalan** |
| R7 Compile-time safety | ✅ | Compile-time completo |
| R8 Módulo wiring | ✅ | `sdk-wiring` |

**Pro:** Es D con la organización Gradle correcta. Zero overhead runtime. Compile-time safety completo. Cross-deps automáticas.
**Contra:** `when` blocks en el facade crecen linealmente. A 50+ features, el facade es un mantenimiento pesado. Requiere módulo `di-contracts`.

**Indicado para:** SDKs medianos (10–30 features) donde compile-time safety y zero overhead runtime son la prioridad absoluta.

---

### Koin — Service Locator

**Compatibilidad: MUY ALTA (diseñado para este patrón)**

```
feature-auth:impl/
  fun authModule() = module {
    single<AuthService> { DefaultAuthService(get(), get()) }
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
- **No compile-time safety.** Si `feature-encryption:impl` no está en classpath, `get<EncryptionService>()` crashea en runtime.
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
| **F** | 🟢 Alta | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ | ✅ | ✅ |
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
| **F** | `when` blocks en facade | `sdk-wiring` | = D |
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
    │  D    E    F                       │
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

**Implementado en:** `sdk/feature-*-impl/`, `sdk/sdk-wiring/`, `sample-multimodule/`

En la arquitectura api/impl, `sdk-wiring` necesita construir `DaggerAuthComponent`.
¿El Component de `feature-auth-impl` debe ser público?

### Solución implementada: Component público con Provision Interface

**Código real** — ver `sdk/feature-auth-impl/AuthComponent.kt`:

```kotlin
// feature-auth-impl — Component público, pero tipado como AuthProvisions
@AuthScope
@Component(
    dependencies = [CoreProvisions::class, EncProvisions::class],  // ← contratos
    modules = [AuthModule::class],
)
interface AuthComponent : AuthProvisions {
    override fun auth(): AuthService
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

### Alternativa: Factory function (Component interno)

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
| SDK Android-only, < 15 features, independientes | **D** o **F** | B (si zero cross-deps) |
| SDK Android-only, 15–30 features, con cross-deps | **F** o **E** | E2 (si quieres futuro-proof) |
| SDK Android-only, 30+ features, cross-deps pesadas | **E2** | Koin (si compile-time no es crítico) |
| SDK Android-only, 50+ features | **E2** o **Koin** | Híbrido (ambos mundos) |
| App consumidora usa Dagger, SDK debe ser KMP | **Hybrid** | — |
| Máxima compile-time safety, < 20 features | **D** / **F** | — |
| Máxima velocidad de desarrollo | **Koin** | — |
| Features 100% independientes (zero cross-deps) | **B** | C (si auto-discovery importa) |

---

## Conclusión

Para la arquitectura **api / impl / integration** con visibilidad estricta:

1. **Koin y E2 son los approaches que mejor escalan** sin comprometer los requisitos.
   - **Koin:** si el equipo acepta resolución runtime (mitigable con `checkModules()` en tests).
   - **E2:** si compile-time safety es requisito duro (Dagger valida cada Component).

2. **D y F son sólidos hasta ~30 features.** F es la organización Gradle correcta de D.
   El `when` block en el facade es el cuello de botella a escala.

3. **E mejora sobre D/F** eliminando `when` blocks, pero el Feature enum expuesto
   al consumidor y su crecimiento lineal lo limitan.

4. **B y C son para features independientes.** Si las features tienen cross-deps
   significativas, estos approaches no escalan.

5. **A no es adecuado** para esta arquitectura — la separación Gradle pierde sentido
   si todo se compila en un `@Component` monolítico.

6. **Hybrid** es la respuesta cuando el SDK es KMP pero la app consumidora es Dagger.

7. **Provision interfaces son el enabler** para D, E, E2 y F en multi-módulo.
   Sin ellas, los features dependerían de `@Component` classes (implementaciones)
   de otros features — violando la regla de "features solo conocen apis".
   Dagger acepta cualquier interfaz en `dependencies=[...]`, no requiere `@Component`.
   Ver implementación real en `sdk/di-contracts/` + `sdk/feature-*-impl/`.

**No hay approach universalmente mejor.** La elección depende de: tamaño del SDK,
cantidad de cross-deps, requisito de KMP, compile-time safety, y equipo disponible
para mantener el wiring.
