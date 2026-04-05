# Dagger 2: Inicialización Modular de SDKs

Siete approaches para construir un SDK Android donde los consumidores seleccionan
qué features activar. Cada uno usa Dagger 2 para DI en compilación pero difiere
en cómo se organizan, descubren e inicializan las features.

Para comparación entre frameworks, ver [di-sdk-selective-init-comparison.md](di-sdk-selective-init-comparison.md).
Para conceptos de DI, ver [di-sdk-consumer-isolation.md](di-sdk-consumer-isolation.md).
Para dependencias cruzadas, ver [di-cross-feature-deps.md](di-cross-feature-deps.md).
Para el approach hybrid, ver [di-hybrid-koin-sdk-dagger-app.md](di-hybrid-koin-sdk-dagger-app.md).
Para análisis multi-módulo api/impl/integration, ver [di-multimodule-api-impl-analysis.md](di-multimodule-api-impl-analysis.md).

---

## El Problema

Un SDK tiene N features (auth, analytics, payments, etc.). Los consumidores deben:
1. Elegir qué features activar
2. No ver clases de implementación
3. No pagar tamaño binario por features que no usan

Dagger 2 resuelve (2) vía codegen en compilación. Pero (1) y (3) entran en conflicto:
Dagger necesita conocer todos los módulos en compilación, y conocerlos significa compilarlos.
Los tres approaches navegan esta tensión de forma diferente.

---

## Approach A: Un Component, Todas las Features

```
┌─────────────────────────────────────────────────────────┐
│                   SdkComponent (@Singleton)              │
│                                                          │
│  CoreModule ─── AuthModule ─── PaymentsModule            │
│  (Logger)       (AuthService)  (PaymentService)          │
│  (Config)       puede inyectar Logger,                   │
│  (Network)      Config, AuthService                      │
└─────────────────────────────────────────────────────────┘
```

UN `@Component` lista TODOS los módulos de features. Dagger genera UNA factory
que sabe cómo crear todo. Cualquier módulo puede inyectar servicios de cualquier otro.

**Código real del proyecto** — ver `sample-dagger-a/`:

```kotlin
@Singleton
@Component(modules = [
    CoreModule::class, EncryptionModule::class,
    AuthModule::class, StorageModule::class,
    AnalyticsModule::class, SyncModule::class,
])
interface SdkComponent {
    fun encryptionService(): EncryptionService
    fun authService(): AuthService
    fun syncService(): SyncService
    // ...
}
```

### Por qué elegir A

- **Dependencias cruzadas automáticas.** SyncModule puede inyectar AuthService — mismo grafo.
- **Simple.** Un Component, un builder, una llamada init.
- **Validación completa en compilación.** Si falta un `@Provides`, el build falla.

### Por qué NO elegir A

- **Binario inflado.** Todos los módulos se compilan en el APK aunque el consumidor solo use Auth.
- **Acoplamiento central.** Añadir una feature requiere editar la anotación `@Component`.
- **No se puede publicar por feature.** `sdk-auth` no puede ser un artefacto Maven independiente.
- **Lazy init falso.** `getOrInitFeature()` solo cambia un flag — el código ya está compilado.

---

## Approach B: Component Separado por Feature

```
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│ SecurityComp │    │ PaymentsComp │    │ AnalyticsComp│
│  @Singleton  │    │  @Singleton  │    │  @Singleton  │
└──────┬───────┘    └──────┬───────┘    └──────┬───────┘
       │                   │                   │
       └──────────┬────────┘───────────────────┘
                  ↓
          ┌──────────────┐
          │   CoreApis   │   ← interfaz Kotlin plana, NO Dagger
          └──────────────┘
```

Cada feature tiene su PROPIO `DaggerComponent`. No hay grafo global. El estado compartido
pasa a través de `CoreApis` — una interfaz Kotlin plana, no un constructo de Dagger.

**Código real del proyecto** — ver `sdk/impl-dagger-b/`:

```kotlin
// CoreApis es una interfaz Kotlin plana — NO @Component
interface CoreApis {
    val logger: Logger
    val config: SdkConfig
}

// Cada feature recibe CoreApis como @BindsInstance
@Singleton
@Component(modules = [EncryptionFeatureModule::class])
interface EncryptionComponent {
    fun encryptionService(): EncryptionService
    @Component.Builder interface Builder {
        @BindsInstance fun core(core: CoreApis): Builder
        fun build(): EncryptionComponent
    }
}

// Si Auth necesita EncryptionService → CoreApis extendido
interface AuthCoreApis : CoreApis {
    val encryptionService: EncryptionService  // ← añadido para cross-dep
}
```

**El problema de CoreApis:** `core.logger` es acceso a propiedad de Kotlin, NO resolución
de Dagger. Si PaymentsService necesita SecurityService, hay que añadirlo a CoreApis.
Con 15+ servicios compartidos, CoreApis se convierte en un God Object.

### Por qué elegir B

- **Binario eficiente.** Solo las features con dependencia Gradle acaban en el APK.
- **Publicación independiente.** `sdk-security` y `sdk-payments` son artefactos Maven separados.
- **Lazy init real.** `getOrInitModule()` crea un DaggerComponent nuevo on-demand.

### Por qué NO elegir B

- **Sin DI cross-feature.** Feature A no puede `@Inject` un servicio de Feature B — están en Components separados.
- **CoreApis crece.** Cada servicio compartido entre features = un campo más en CoreApis.
- **Edición central.** Nueva feature = editar el `when` block del facade SDK.

---

## Approach C: Per-Feature + ServiceLoader Discovery

Misma arquitectura que B (Components separados + CoreApis), pero las features
se auto-registran vía `ServiceLoader` de JVM. Añadir una feature = añadir dependencia
Gradle + fichero META-INF. Zero ediciones centrales.

**Código real del proyecto** — ver `sdk/impl-dagger-c/`:

```kotlin
// Contrato que cada feature implementa
interface FeatureInitializer {
    val featureName: String
    val requiredDependencies: Set<String>
    fun init(core: CoreApis, resolved: ServiceResolver)
    fun <T> getService(serviceClass: Class<T>): T?
}

// Registro en META-INF/services/com.grinwich.sdk.daggerc.FeatureInitializer:
// com.grinwich.sdk.daggerc.EncryptionInit
// com.grinwich.sdk.daggerc.AuthInit
// com.grinwich.sdk.daggerc.StorageInit
```

### Por qué elegir C sobre B

- **Zero edición central.** `DaggerCSdk.kt` no se toca al añadir features.
- **Escalable.** Con 20+ features, el `when` block de B es inmantenible. C escala sin ediciones centrales.

### Por qué NO elegir C

- **JVM exclusivo.** `ServiceLoader` requiere `META-INF/services/` — no disponible en Kotlin/Native.
- **Errores runtime.** Dependencia Gradle ausente = crash en init, no error de compilación.
- **Mismo problema CoreApis que B.** Las dependencias cruzadas siguen siendo manuales.

### Variante: recibir Component interfaces en vez de servicios sueltos

Validado en este proyecto (compila). En vez de pasar servicios individuales al Builder,
se pasa la interfaz del Component padre y el `@Module` extrae los servicios:

```kotlin
// ESTÁNDAR — servicios sueltos como @BindsInstance
@Component.Builder interface Builder {
    @BindsInstance fun enc(enc: EncryptionService): Builder
    @BindsInstance fun hash(h: HashService): Builder
    fun build(): CStorComp
}
@Module class CStorMod {
    @Provides fun storage(enc: EncryptionService, h: HashService, l: SdkLogger) =
        DefaultSecureStorageService(enc, h, l)
}

// VARIANTE — Component interface como @BindsInstance
@Component.Builder interface Builder {
    @BindsInstance fun encComp(encComp: CEncComp): Builder  // ← Component completo
    fun build(): CStorComp
}
@Module class CStorMod {
    @Provides fun encryption(encComp: CEncComp): EncryptionService = encComp.encryption()
    @Provides fun hash(encComp: CEncComp): HashService = encComp.hash()
    @Provides fun storage(enc: EncryptionService, h: HashService, l: SdkLogger) =
        DefaultSecureStorageService(enc, h, l)
}
```

**Ventaja:** El Builder recibe un solo objeto en vez de N servicios sueltos. Más limpio cuando
un Component padre expone muchos servicios.

**Coste:** Un `@Provides` extractor por cada servicio que se necesite del Component padre.
Con 5 servicios del padre, son 5 líneas de boilerplate.

**Relación con approach D:** Esta variante es funcionalmente equivalente a
`@Component(dependencies = [CEncComp::class])` del approach D, pero hecho manualmente.
D lo declara en la anotación y Dagger genera los extractores automáticamente — zero boilerplate.

---

## Approach D: Component Dependencies

```
CoreComponent → EncComponent → AuthComponent
                             → StorComponent
                                            → SyncComponent
```

Cada feature tiene su `DaggerComponent`, pero los Components hijo declaran
`dependencies = [ParentComponent::class]`. El hijo ve las provision methods del padre
**automáticamente** — sin CoreApis, sin wiring manual.

**Código real del proyecto** — ver `sdk/impl-dagger-d/`:

```kotlin
// Auth depende de Core + Encryption
@AuthScope
@Component(
    dependencies = [CoreComponent::class, EncComponent::class],
    modules = [InternalAuthModule::class],
)
internal interface AuthComponent {
    fun auth(): AuthService
}

@Module
internal class InternalAuthModule {
    @Provides @AuthScope
    fun auth(enc: EncryptionService, logger: SdkLogger): AuthService =
        DefaultAuthService(enc, logger)
    //   Dagger resuelve enc desde EncComponent.encryption() — AUTOMÁTICO
}
```

### Por qué elegir D

- **Cross-feature automático.** `dependencies=[EncComponent]` — Dagger resuelve sin CoreApis.
- **Compile-time safe.** Parent faltante = error de compilación.
- **Lazy init real.** `getOrInitModule()` crea Components on-demand con cascada.
- **Sin God Object.** No hay interfaz CoreApis que crezca.

### Por qué NO elegir D

- **Binario no lean.** Todas las features están en `impl-dagger-d`. Para binario lean, cada feature necesitaría su propio módulo Gradle.
- **Edición central.** Nueva feature = editar `Feature` enum + `when` block en `DaggerSdk.kt`.
- **JVM exclusivo.** Dagger no soporta KMP.

---

## Approach E: Component Registry

```
              ┌─────────────────────────────────────────┐
              │           ComponentRegistry              │
              │  HashMap<Class, DiComponent> components  │
              │  HashMap<Class, Any>         services    │
              └──────────────────┬──────────────────────┘
                                 │
           ┌─────────────────────┼──────────────────────┐
           ↓                     ↓                      ↓
   ┌──────────────┐    ┌──────────────┐       ┌──────────────┐
   │ EncComponent │    │ AuthComponent│       │ SynComponent │
   │ dependencies │    │ dependencies │       │ dependencies │
   │   = [Core]   │    │ = [Core,Enc] │       │=[Core,Enc,   │
   └──────────────┘    └──────────────┘       │  Auth,Stor]  │
                                              └──────────────┘
```

Evolución de D para entornos corporativos multi-módulo. Cada feature sigue teniendo
su `DaggerComponent` con `dependencies=[...]`, pero los Components y servicios se
registran en un `ComponentRegistry` central vía `FeatureEntry`.

**Código real del proyecto** — ver `sdk/impl-dagger-e/`:

```kotlin
// DiComponent — marker interface para Components gestionados por el registry
interface DiComponent

// FeatureEntry — cada módulo Gradle exporta uno de estos
class FeatureEntry<C : DiComponent>(
    val componentClass: Class<C>,
    val dependencies: Set<Class<out DiComponent>> = emptySet(),
    val build: (ComponentRegistry) -> C,
    val services: (C) -> Map<Class<*>, Any>,  // eager instances, NO lambdas
)

// ComponentRegistry — HashMap (single-threaded init, read-only post-init)
class ComponentRegistry {
    fun <C : DiComponent> register(entry: FeatureEntry<C>) { ... }
    fun registerAll(entries: List<FeatureEntry<*>>) { /* topo-sort + register */ }
    fun <T : Any> get(clazz: Class<T>): T = services[clazz] as T
}
```

Ejemplo de un FeatureEntry para Encryption:

```kotlin
// Interno al módulo :integration:features:encryption:impl
internal val encryptionEntry = FeatureEntry(
    componentClass = EncComponent::class.java,
    dependencies = setOf(CoreComponent::class.java),
    build = { registry ->
        DaggerEncComponent.builder()
            .core(registry.component(CoreComponent::class.java))
            .build()
    },
    services = { comp ->
        mapOf(
            EncryptionService::class.java to comp.encryption(),  // eager
            HashService::class.java to comp.hash(),              // eager
        )
    },
)
```

El facade público `RegistrySdk` expone un `Feature` enum — el consumidor nunca ve
las entries ni el registry:

```kotlin
object RegistrySdk {
    enum class Feature(internal val entry: FeatureEntry<out DiComponent>) {
        ENCRYPTION(encryptionEntry),
        AUTH(authEntry),
        STORAGE(storageEntry),
        ANALYTICS(analyticsEntry),
        SYNC(syncEntry);
    }

    fun init(config: SdkConfig, features: Set<Feature>) { ... }
    fun getOrInitModule(feature: Feature): Set<Feature> { ... }
    inline fun <reified T : Any> get(): T = ...
    fun shutdown() { ... }
}
```

### Tres mejoras clave sobre la reflexión corporativa típica

| Problema corporativo | Solución E |
|---------------------|-----------|
| `toServiceMap()` usa reflexión para descubrir services | `services: (C) -> Map<Class<*>, Any>` — explicit, compile-time checked |
| Servicios almacenados como lambdas (overhead por access) | Instances directas (eager resolution) — Dagger scoped ya cachea |
| `ConcurrentHashMap` para registry (overhead innecesario) | `HashMap` — init single-threaded, post-init read-only |

### Auto topo-sort

Los FeatureEntries declaran sus dependencias. `registerAll()` usa el algoritmo de Kahn
para ordenar topológicamente antes de registrar — sin import order manual:

```kotlin
// Orden de registro es irrelevante — el registry lo ordena
registry.registerAll(listOf(syncEntry, encEntry, authEntry, storEntry))
// Internamente: enc → auth → stor → sync
```

### Por qué elegir E

- **Multi-módulo Gradle viable.** Cada módulo exporta un FeatureEntry — no necesita importar
  todos los DaggerComponents como D.
- **Explicit service bindings.** Sin reflexión. Si un servicio no está en el map, no existe.
- **Cross-feature automático.** Misma jerarquía `dependencies=[...]` que D — Dagger resuelve.
- **Compile-time safe.** Missing binding = error Dagger en compilación.
- **Lazy init con cascada.** `getOrInitModule()` expande dependencias transitivas y registra via topo-sort.
- **Eager resolution.** Services resueltos al registrar, no al acceder (~0 overhead post-init).

### Por qué NO elegir E

- **Registry overhead.** `get<T>()` = HashMap lookup (~20 ns) vs campo volátil Dagger (~3 ns).
- **Más código infra.** DiComponent + FeatureEntry + ComponentRegistry = ~135 líneas de infra.
- **Binario no lean.** Mismo problema que D — todas las features compiladas en el módulo SDK.
- **JVM exclusivo.** Dagger no soporta KMP.
- **Enum central.** El `Feature` enum sigue requiriendo edición al añadir features (mitigable
  en setup corporativo donde el enum vive en el módulo SDK, no en cada feature).

---

## Approach E2: Auto-Init Registry (Evolución de E)

```
              ┌─────────────────────────────────────────┐
              │            AutoRegistry                  │
              │  catalog:      Class → AutoFeatureEntry  │  ← installed (cheap)
              │  serviceIndex: Class → componentClass    │  ← service → provider
              │  components:   Class → DiComponent       │  ← built on demand
              │  services:     Class → Any               │  ← cached eagerly
              └──────────────────┬──────────────────────┘
                                 │
                      get<SyncService>()
                                 │
                      ensureBuilt(SynComponent)
                          │ DFS recursivo
                    ┌─────┼──────┬──────────┐
                    ↓     ↓      ↓          ↓
                 Core   Enc    Auth      Storage
               (built) (built) (build)   (build)
```

Evolución de E que elimina las dos limitaciones principales del consumidor:
1. **Sin `Feature` enum** — el consumidor nunca selecciona features
2. **Auto-init on `get<T>()`** — pedir un servicio construye toda la cadena de deps

El cambio clave es la separación en dos fases:
- **install()** — cataloga entries (solo HashMap puts, ~50 ns total)
- **get<T>()** — auto-descubre qué entry provee T, construye recursivamente

**Código real del proyecto** — ver `sdk/impl-dagger-e2/`:

```kotlin
// AutoFeatureEntry — declara serviceClasses ANTES de construir
class AutoFeatureEntry<C : DiComponent>(
    val componentClass: Class<C>,
    val dependencies: Set<Class<out DiComponent>> = emptySet(),
    val serviceClasses: Set<Class<*>>,  // ← permite indexar Service → Entry
    val build: (AutoRegistry) -> C,
    val services: (C) -> Map<Class<*>, Any>,
)

// AutoRegistry — install (catalog) + get (auto-build on demand)
class AutoRegistry {
    fun install(entry: AutoFeatureEntry<*>) { /* index only */ }
    fun <T : Any> get(clazz: Class<T>): T { /* ensureBuilt + cache */ }
}
```

El facade `AutoSdk` — la API de consumidor más simple de todos los approaches:

```kotlin
object AutoSdk {
    fun init(config: SdkConfig) {
        registry.installAll(allEntries(config, logger))  // catalog only
    }
    inline fun <reified T : Any> get(): T = registry.get(T::class.java)
    fun shutdown() { ... }
}
```

**API del consumidor:**

```kotlin
AutoSdk.init(SdkConfig(debug = true))
val sync = AutoSdk.get<SyncService>()   // auto-inits Core→Enc→Auth→Stor→Sync
val enc  = AutoSdk.get<EncryptionService>() // ya construido — cache hit
AutoSdk.shutdown()
```

### Escalabilidad: E2 vs E

Añadir un módulo nuevo en E2:
1. Crear `AutoFeatureEntry` en el módulo
2. Añadir a `allEntries()` — **una línea**

Añadir un módulo nuevo en E:
1. Crear `FeatureEntry` en el módulo
2. Añadir caso al `Feature` enum
3. Añadir `requiredDependencies` al `when` block

Con 50+ módulos, E2 escala linealmente sin tocar el facade. E requiere editar
el enum y el when block (misma limitación que D/F).

### Por qué elegir E2

- **API mínima.** `init()` + `get<T>()` — nada más que el consumidor necesite saber.
- **Facade inmutable.** Añadir módulo = 1 línea en `allEntries()`. Sin enums. Sin when.
- **Lazy por naturaleza.** Solo construye lo que se pide (y sus deps).
- **Cross-feature automático.** Misma jerarquía `dependencies=[...]` que D/E.
- **Compile-time safe.** Missing binding = error Dagger.

### Por qué NO elegir E2

- **Sin control granular.** El consumidor no puede elegir qué features cargar — todo está instalado.
  Para SDKs donde el consumidor NECESITA excluir features por política, E con Feature enum es mejor.
- **Registry overhead.** `get<T>()` primera vez = DFS + builds. Post-init = HashMap (~25 ns vs ~3 ns Dagger).
- **initCold más lento que E.** Install + on-demand builds (~8 μs) vs eager register (~5 μs).
  Diferencia de ~3 μs — irrelevante en producción.
- **JVM exclusivo.** Dagger no soporta KMP.

---

## Approach F: Multi-Module Component Dependencies

```
:sdk:di-core/              :sdk:impl-dagger-f/
┌────────────────┐         ┌──────────────────────────────┐
│ CoreComponent  │◄────────│ EncComponent                 │
│  logger()      │         │ AuthComponent                │
│  config()      │         │ StorComponent                │
│  @BindsInstance│         │ AnaComponent                 │
└────────────────┘         │ SyncComponent                │
                           │                              │
                           │ ModularSdk (facade + when)   │
                           └──────────────────────────────┘
```

Pattern D aplicado a multi-módulo Gradle real. `CoreComponent` vive en un módulo
separado (`:sdk:di-core`) para romper la dependencia circular: si el facade importa
CoreComponent y las features dependen del facade → ciclo.

**Código real del proyecto** — ver `sdk/di-core/` y `sdk/impl-dagger-f/`:

```kotlin
// :sdk:di-core — CoreComponent con pure @BindsInstance (sin @Module)
@Singleton @Component
interface CoreComponent {
    fun logger(): SdkLogger
    fun config(): SdkConfig
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun config(config: SdkConfig): Builder
        @dagger.BindsInstance fun logger(logger: SdkLogger): Builder
        fun build(): CoreComponent
    }
}

// :sdk:impl-dagger-f — feature components con dependencies=[CoreComponent]
@EncScope @Component(dependencies = [CoreComponent::class], modules = [EncModule::class])
internal interface EncComponent { ... }
```

### F = D en runtime

Los benchmarks lo confirman: F y D tienen rendimiento idéntico (±2%).
La separación en módulos Gradle es invisible en runtime — Dagger genera
el mismo código sin importar cómo estén organizados los módulos.

### Por qué elegir F

- **Multi-módulo Gradle real.** CoreComponent compartido entre N módulos feature sin ciclos.
- **Zero overhead.** Idéntico a D en runtime (benchmarks lo prueban).
- **Publicación independiente.** CoreComponent es un artefacto Maven reutilizable.

### Por qué NO elegir F

- **No escala.** Mismo `when` block que D — crece linealmente con features.
  Con 50+ módulos, el facade se vuelve inmanejable.
- **`Feature` enum expuesto.** El consumidor ve y selecciona features — API más compleja que E2.
- **No evoluciona.** Para escalar hay que migrar a E o E2 (registry).

### Evolución: F no puede evolucionar

F es estructuralmente D con CoreComponent separado. Su esencia son los `when` blocks
y variables per-component. Eliminar esos `when` blocks = eliminar F y convertirlo en E/E2.
F es un approach de transición para equipos que necesitan multi-módulo con Dagger
pero aún no requieren la escala que demanda un registry.

---

## Comparación

|  | A | B | C | D | E | E2 | F |
|---|---|---|---|---|---|---|---|
| **Arquitectura** | 1 Component global | N Components + CoreApis | N Components + ServiceLoader | N Components `dependencies=[]` | N Components + Registry + topo-sort | N Components + AutoRegistry + DFS | D en multi-módulo Gradle |
| **Cross-feature** | ✅ Auto | ❌ CoreApis | ❌ CoreApis | ✅ Auto | ✅ Auto | ✅ Auto | ✅ Auto |
| **Singletons** | ✅ @Singleton | ⚠️ Manual | ⚠️ Manual | ✅ Provision | ✅ Registry | ✅ Registry | ✅ Provision |
| **Binario lean** | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ | ⚠️ Core separado |
| **Lazy init** | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ On-demand | ✅ |
| **Añadir feature** | Editar @Component | +CoreApis +when | +META-INF | +deps +when | +Entry +enum | **+Entry (1 línea)** | +deps +when |
| **Compile-time** | ✅ Completo | ⚠️ Per-feature | ⚠️ Runtime | ✅ Con deps | ✅ Explicit | ✅ Explicit | ✅ Con deps |
| **Multi-módulo** | ❌ | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ |
| **Feature enum** | N/A | ✅ Expuesto | N/A | ✅ Expuesto | ✅ Expuesto | **❌ Oculto** | ✅ Expuesto |
| **Escala 50+** | ❌ | ❌ God Object | ⚠️ Cross-deps | ❌ when blocks | ❌ enum+when | **✅** | ❌ when blocks |
| **Complejidad** | Baja | Media | Alta | Media | Media-Alta | Media-Alta | Media |

### Cuándo usar

| Escenario | Approach |
|----------|----------|
| SDK pequeño (≤5 features), features interdependientes | **A** |
| SDK modular, publicación per-feature Maven | **B** |
| 20+ features, adiciones frecuentes, JVM | **C**, **E**, o **E2** |
| Cross-deps complejas + compile-time safety | **D**, **E**, o **E2** |
| Multi-módulo Gradle corporativo (api/impl por feature) | **E** o **E2** |
| API mínima para consumidor (sin Feature enum) | **E2** |
| Multi-módulo pero <15 features (transición) | **F** |
| SDK escalable a 50+ módulos | **E2** o Koin |
| Consumidor necesita excluir features explícitamente | **E** (Feature enum) |
| KMP necesario | Ninguno — ver Koin en [comparación](di-sdk-selective-init-comparison.md) |
