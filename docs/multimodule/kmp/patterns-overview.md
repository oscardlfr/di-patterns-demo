# Patrones Multi-Modulo KMP-Compatible (N, O, O2, P, P2)

Estos 5 patrones funcionan en los 24 targets de Kotlin (JVM, iOS, macOS, Linux,
Windows, WASM-JS, WASM-WASI). No dependen de `java.util.ServiceLoader`,
`android.content.Context`, ni ninguna API platform-specific en su mecanismo
de discovery o wiring.

Todos implementan la misma interfaz `MultiModuleSdkApi` y comparten el contrato
neutro `FeatureProvider`/`KoinFeatureProvider` de `di-contracts`/`di-contracts-koin`
(post-refactor: sin `Provisions` globales, Bundles locales en cada feature-impl).

**Abstraccion runtime-flexible (Req 12)**: solo **N** cumple en este grupo. O/O2/P/P2
ejecutan merge de `@ContributesTo`/`@MergeComponent` en tiempo de compilacion del
wiring — estructuralmente incompatible con `runtimeOnly(features)`. N usa sweet-spi
discovery puro y publica el sdk-integration sin acoplar feature-impls en compile.

---

## 1. Pattern N -- sweet-spi + Koin (wiring-n)

### Concepto

Identico a Pattern L (Koin + ServiceLoader) pero reemplaza `java.util.ServiceLoader`
por sweet-spi. En JVM, sweet-spi delega internamente a `java.util.ServiceLoader`
leyendo los mismos archivos `META-INF/services/`. En targets no-JVM (Native, WASM),
sweet-spi usa `@EagerInitialization` -- el KSP genera codigo platform-specific
para registrar providers automaticamente.

Esto convierte un patron Partial KMP (L) en Full KMP con un cambio minimo:
reemplazar una linea de import.

### Codigo del wiring

```kotlin
// sdk/wiring-n/src/main/kotlin/.../MultiModuleSdkN.kt

object MultiModuleSdkN : MultiModuleSdkApi {

    private var _koinApp: KoinApplication? = null
    private var _initialized = false
    private var _tracker: CreationTracker? = null
    private var _logger: SdkLogger = buildLogger()

    override val isInitialized: Boolean get() = _initialized
    override val builtProvisionCount: Int get() = _tracker?.count ?: 0

    override fun init(context: Context, config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdkN already initialized." }

        val appCtx = context.applicationContext
        val tracker = CreationTracker()
        _tracker = tracker

        val foundation = module {
            single<Context> { appCtx }
            single<SdkConfig> { config }
            single<StorageBackend> { config.storageBackend }
            single<SdkLogger> { _logger }
            single<CreationTracker> { tracker }
        }

        // sweet-spi discovery -- en JVM delega a java.util.ServiceLoader
        val providers = dev.whyoleg.sweetspi.ServiceLoader.load<KoinFeatureProvider>()

        val featureModules = providers.map { it.module() }
        _koinApp = koinApplication {
            modules(listOf(foundation) + featureModules)
        }

        _initialized = true
    }

    override fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdkN not initialized." }
        return _koinApp!!.koin.get(clazz.kotlin as KClass<Any>) as T
    }

    override fun shutdown() {
        if (!_initialized) return
        _koinApp?.close()
        _koinApp = null
        _tracker?.clear()
        _tracker = null
        _initialized = false
    }
}
```

### Diferencia clave con L

Una sola linea cambia:

```kotlin
// Pattern L (Partial KMP):
val providers = ServiceLoader.load(KoinFeatureProvider::class.java).toList()

// Pattern N (Full KMP):
val providers = dev.whyoleg.sweetspi.ServiceLoader.load<KoinFeatureProvider>()
```

En JVM, sweet-spi produce el mismo resultado. En Native/WASM, sweet-spi genera
el registro automaticamente via `@EagerInitialization`.

### Ventajas

- **Full KMP.** Funciona en los 24 targets de Kotlin.
- **Koin familiar.** Misma API Koin que L/M -- `single{}`, `koinApplication{}`.
- **Migracion trivial.** 1 linea de cambio respecto a L.
- **Sweet-spi KSP.** El registro de servicios es automatico.

### Desventajas

- **Overhead Koin.** Init Cold = 69,636 ns -- 115x mas lento que Metro (603 ns).
- **Resolve lento.** 5,855 ns por resolucion Koin vs 288 ns en Metro.
- **Lazy noDeps lento.** 20,018 ns -- el peor de todos los 16 patrones.
- **Runtime errors.** Koin no valida el grafo completo en compilacion.

---

## 2. Pattern O -- Metro (wiring-o)

### Concepto

Metro es un compiler plugin para Kotlin que agrega todos los `@ContributesTo`
bindings de los feature-impl modules al `@DependencyGraph` en compilacion.
Zero runtime discovery, zero ServiceLoader, zero Resolver. El grafo completo
se construye eagerly en `init()`.

`@SingleIn(AppScope)` marca singletons. `@ContributesTo(AppScope)` en cada
feature-impl contribuye bindings al grafo. El compiler plugin de Metro los
agrega automaticamente -- sin lista explicita de modules.

### Codigo del wiring

```kotlin
// sdk/wiring-o/src/main/kotlin/.../MultiModuleSdkO.kt

@DependencyGraph(AppScope::class)
interface SdkGraph {
    val context: Context
    val encryption: EncryptionApi
    val hashApi: HashApi
    val auth: AuthApi
    val storage: StorageApi
    val analytics: AnalyticsApi
    val sync: SyncApi

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides context: Context,
            @Provides config: SdkConfig,
            @Provides logger: SdkLogger,
            @Provides storageBackend: StorageBackend,
        ): SdkGraph
    }
}

object MultiModuleSdkO : MultiModuleSdkApi {

    private var _graph: SdkGraph? = null
    private var _initialized = false
    private var _logger: SdkLogger = buildLogger()

    override val isInitialized: Boolean get() = _initialized
    override val builtProvisionCount: Int get() = if (_initialized) 5 else 0

    override fun init(context: Context, config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdkO already initialized." }
        _graph = createGraphFactory<SdkGraph.Factory>().create(
            context = context.applicationContext,
            config = config,
            logger = _logger,
            storageBackend = config.storageBackend,
        )
        _initialized = true
    }

    override fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdkO not initialized." }
        val graph = _graph ?: error("graph is null")
        return when (clazz) {
            EncryptionApi::class.java -> graph.encryption
            HashApi::class.java -> graph.hashApi
            // ...demas bindings
            else -> error("No binding for ${clazz.simpleName}")
        } as T
    }

    override fun shutdown() {
        if (!_initialized) return
        _graph = null
        _initialized = false
    }
}
```

### Ventajas

- **Init mas rapido de todos.** 603 ns -- el grafo se construye con codigo generado,
  sin reflexion, sin maps, sin locks.
- **Full KMP.** Metro compila para todos los targets de Kotlin.
- **Compile-time safe.** Binding faltante = error de compilacion.
- **Auto-agregacion al grafo.** `@ContributesTo` elimina la lista explicita de modules
  en `@DependencyGraph` (Req 6 cumplido).
- **Shutdown trivial.** Solo nullifica el grafo.

### Desventajas

- **Eager.** Todos los singletons se crean en init. No hay lazy individual.
- **Compiler plugin.** Dependencia fuerte en el plugin de Metro (vs KSP en los demas).
- **Re-init mas lento que O2.** 36,000 ns vs 2,305 ns (15.6x mas lento).
- **Ecosistema joven.** Metro tiene menos adopcion que kotlin-inject o Dagger.
- **Facade no inmutable** (Req 11): aunque `@ContributesTo` agrega los modulos al
  grafo automaticamente, el dispatcher `MultiModuleSdkO.get<T>(Class)` mantiene un
  `when (clazz)` que crece 1 rama por cada API expuesta. A 50 features × 10 APIs =
  500 ramas mantenidas a mano. Mitigable con un procesador KSP propio (~200 LOC) que
  genere el `when` desde el componente. Ver `docs/shared/requirements.md` Req 11.

---

## 3. Pattern O2 -- Metro Lazy (wiring-o2)

### Concepto

Identico a O pero los accessors del `@DependencyGraph` retornan `Lazy<T>` en
vez de `T` directo. El grafo se crea en `init()`, pero los singletons NO se
instancian hasta el primer acceso via `Lazy.value`. Un `LazyCreationTracker`
cuenta cuantas features se han materializado.

### Codigo del wiring

```kotlin
// sdk/wiring-o2/src/main/kotlin/.../MultiModuleSdkO2.kt

@DependencyGraph(AppScope::class)
interface SdkGraph {
    val context: Context
    val encryption: Lazy<EncryptionApi>      // <-- Lazy<T>
    val hashApi: Lazy<HashApi>
    val auth: Lazy<AuthApi>
    val storage: Lazy<StorageApi>
    val analytics: Lazy<AnalyticsApi>
    val sync: Lazy<SyncApi>

    @DependencyGraph.Factory
    fun interface Factory { /* same as O */ }
}

object MultiModuleSdkO2 : MultiModuleSdkApi {

    private var _graph: SdkGraph? = null
    private var _initialized = false
    private var _tracker: LazyCreationTracker.Instance? = null
    private var _logger: SdkLogger = buildLogger()

    override val builtProvisionCount: Int get() = _tracker?.count ?: 0

    override fun init(context: Context, config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdkO2 already initialized." }
        _tracker = LazyCreationTracker.activate()
        _graph = createGraphFactory<SdkGraph.Factory>().create(
            context = context.applicationContext,
            config = config,
            logger = _logger,
            storageBackend = config.storageBackend,
        )
        _initialized = true
    }

    override fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdkO2 not initialized." }
        val graph = _graph!!
        return when (clazz) {
            EncryptionApi::class.java -> graph.encryption.value  // <-- .value
            // ...demas bindings con .value
            else -> error("No binding for ${clazz.simpleName}")
        } as T
    }

    override fun shutdown() {
        if (!_initialized) return
        LazyCreationTracker.deactivate()
        _graph = null
        _tracker?.clear()
        _tracker = null
        _initialized = false
    }
}
```

### Diferencia clave con O

| Aspecto | O (eager) | O2 (lazy) |
|---------|----------|-----------|
| Accessor type | `val encryption: EncryptionApi` | `val encryption: Lazy<EncryptionApi>` |
| Init Cold | 603 ns | 1,127 ns |
| Lazy noDeps | 2,098 ns | 238 ns (**8.8x**) |
| Re-Init | 36,000 ns | 2,305 ns (**15.6x**) |
| builtProvisionCount | Siempre 5 despues de init | Incrementa con cada acceso |

### Ventajas

- **Re-init 15.6x mas rapido que O.** 2,305 ns vs 36,000 ns.
- **Lazy real.** Singletons se crean on-demand. Features no usadas nunca se instancian.
- **Full KMP.** Mismo soporte multiplatform que O.
- **Observabilidad.** LazyCreationTracker permite saber cuantas features se han materializado.

### Desventajas

- **Init ~1.9x mas lento que O.** 1,127 ns vs 603 ns por el setup del tracker.
- **Lazy cascade mas lento que O.** 507 ns vs 346 ns (primer acceso paga la creacion).
- **Compiler plugin.** Misma dependencia fuerte en Metro.
- **Facade no inmutable** (Req 11): hereda el mismo `when (clazz)` manual de O. Crece
  por API. Mitigable con KSP propio (~200 LOC).

---

## 4. Pattern P -- kotlin-inject-anvil (wiring-p)

### Concepto

kotlin-inject-anvil usa KSP para agregar todos los `@ContributesTo(SdkScope)`
interfaces de los feature-impl modules en un `@MergeComponent`. Es el equivalente
KSP de lo que Metro hace con compiler plugin y lo que Dagger/Anvil hace en el
ecosistema Android.

`@SingleIn(SdkScope)` marca singletons. `@ContributesTo(SdkScope)` en cada feature-impl
contribuye bindings. KSP genera el merged component automaticamente.

### Codigo del wiring

```kotlin
// sdk/wiring-p/src/main/kotlin/.../MultiModuleSdkP.kt

@MergeComponent(SdkScope::class)
@SingleIn(SdkScope::class)
abstract class SdkComponent(
    @get:Provides val context: Context,
    @get:Provides val config: SdkConfig,
    @get:Provides val logger: SdkLogger,
    @get:Provides val storageBackend: StorageBackend,
) {
    abstract val encryption: EncryptionApi
    abstract val hashApi: HashApi
    abstract val auth: AuthApi
    abstract val storage: StorageApi
    abstract val analytics: AnalyticsApi
    abstract val sync: SyncApi
}

object MultiModuleSdkP : MultiModuleSdkApi {

    private var _component: SdkComponent? = null
    private var _initialized = false
    private var _logger: SdkLogger = buildLogger()

    override val isInitialized: Boolean get() = _initialized
    override val builtProvisionCount: Int get() = if (_initialized) 5 else 0

    override fun init(context: Context, config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdkP already initialized." }
        _component = SdkComponent::class.create(
            contextDelegate = context.applicationContext,
            configDelegate = config,
            loggerDelegate = _logger,
            storageBackendDelegate = config.storageBackend,
        )
        _initialized = true
    }

    override fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdkP not initialized." }
        val component = _component!!
        return when (clazz) {
            EncryptionApi::class.java -> component.encryption
            HashApi::class.java -> component.hashApi
            // ...demas bindings
            else -> error("No binding for ${clazz.simpleName}")
        } as T
    }

    override fun shutdown() {
        if (!_initialized) return
        _component = null
        _initialized = false
    }
}
```

### Diferencia clave con O (Metro)

Metro usa un **compiler plugin** para la agregacion. kotlin-inject-anvil usa **KSP**.
Ambos logran el mismo resultado: compile-time multi-module aggregation sin
ServiceLoader. La diferencia practica es que KSP es mas estandar y tiene mejor
soporte en el ecosistema de build tools, mientras que Metro puede optimizar mas
agresivamente al tener acceso al compilador.

En benchmarks, P (1,064 ns init) es ~1.8x mas lento que O (603 ns), pero ambos
estan en el rango sub-microsegundo.

### Ventajas

- **Full KMP.** kotlin-inject soporta todos los targets de Kotlin.
- **KSP estandar.** No requiere compiler plugin custom -- mejor tooling support.
- **Compile-time safe.** Binding faltante = error KSP.
- **Comunidad Amazon.** kotlin-inject-anvil es mantenido por Amazon/Audible.

### Desventajas

- **Eager.** Todos los singletons se crean en init (igual que O).
- **~1.8x mas lento que Metro en init.** 1,064 ns vs 603 ns.
- **Re-init lento.** 28,000 ns -- 12x mas lento que P2 (2,929 ns).
- **Delegate naming.** KSP genera `contextDelegate`, `configDelegate` etc. con sufijo.
- **Facade no inmutable** (Req 11): el dispatcher `MultiModuleSdkP.get<T>(Class)` mantiene
  un `when (clazz)` que crece por API. Mismo problema que O. Mitigable con KSP propio.

---

## 5. Pattern P2 -- kotlin-inject-anvil Lazy (wiring-p2)

### Concepto

Identico a P pero con `@SingleIn` scoping y `LazyCreationTracker` para rastrear
la creacion real de singletons. kotlin-inject con `@SingleIn` crea singletons
lazily on first access -- el component se construye en `init()` pero los providers
no se ejecutan hasta que se accede a la propiedad.

### Codigo del wiring

```kotlin
// sdk/wiring-p2/src/main/kotlin/.../MultiModuleSdkP2.kt

@MergeComponent(SdkScope::class)
@SingleIn(SdkScope::class)
abstract class SdkComponent(
    @get:Provides val context: Context,
    @get:Provides val config: SdkConfig,
    @get:Provides val logger: SdkLogger,
    @get:Provides val storageBackend: StorageBackend,
) {
    abstract val encryption: EncryptionApi
    abstract val hashApi: HashApi
    abstract val auth: AuthApi
    abstract val storage: StorageApi
    abstract val analytics: AnalyticsApi
    abstract val sync: SyncApi
}

object MultiModuleSdkP2 : MultiModuleSdkApi {

    private var _component: SdkComponent? = null
    private var _initialized = false
    private var _tracker: LazyCreationTracker.Instance? = null
    private var _logger: SdkLogger = buildLogger()

    override val builtProvisionCount: Int get() = _tracker?.count ?: 0

    override fun init(context: Context, config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdkP2 already initialized." }
        _tracker = LazyCreationTracker.activate()
        _component = SdkComponent::class.create(
            contextDelegate = context.applicationContext,
            configDelegate = config,
            loggerDelegate = _logger,
            storageBackendDelegate = config.storageBackend,
        )
        _initialized = true
    }

    override fun shutdown() {
        if (!_initialized) return
        LazyCreationTracker.deactivate()
        _component = null
        _tracker?.clear()
        _tracker = null
        _initialized = false
    }
}
```

### Diferencia clave con P

| Aspecto | P (eager) | P2 (lazy) |
|---------|----------|-----------|
| builtProvisionCount | Siempre 5 | Incrementa con cada acceso |
| Init Cold | 1,064 ns | 1,416 ns |
| Lazy noDeps | 1,941 ns | 284 ns (**6.8x**) |
| Re-Init | 28,000 ns | 2,929 ns (**9.6x**) |

### Ventajas

- **Re-init 9.6x mas rapido que P.** 2,929 ns vs 28,000 ns.
- **Full KMP.** Mismo soporte multiplatform que P.
- **Observabilidad real.** `builtProvisionCount` refleja singletons realmente creados.
- **Same component API.** El consumidor no nota la diferencia.

### Desventajas

- **Init ~1.3x mas lento que P.** 1,416 ns vs 1,064 ns.
- **CrossFeature mas lento.** 3.1M ns vs 1.7M ns en P (variabilidad alta).
- **Facade no inmutable** (Req 11): hereda el mismo `when (clazz)` manual de P. Crece
  por API. Mitigable con KSP propio.

---

## 6. Tabla Comparativa KMP

### Caracteristicas

| Criterio | N<br>*(sweet-spi+Koin)* | O<br>*(Metro eager)* | O2<br>*(Metro Lazy)* | P<br>*(KI-anvil eager)* | P2<br>*(KI-anvil Lazy)* |
|----------|---|---|---|---|---|
| **Framework** | sweet-spi + Koin | Metro | Metro | kotlin-inject-anvil | kotlin-inject-anvil |
| **Discovery** | sweet-spi (runtime) | Compile-time | Compile-time | Compile-time (KSP) | Compile-time (KSP) |
| **Codegen** | KSP (sweet-spi) | Compiler plugin | Compiler plugin | KSP | KSP |
| **Lazy singletons** | Si (Koin single) | No | Si (Lazy\<T\>) | No | Si (SingleIn) |
| **Wiring del modulo (Req 6)** | Si -- @ServiceProvider | Si -- @ContributesTo | Si -- @ContributesTo | Si -- @ContributesTo | Si -- @ContributesTo |
| **Wiring del facade (Req 11)** | **Si -- koin.get nativo** | **No -- when manual** | **No -- when manual** | **No -- when manual** | **No -- when manual** |
| **Compile-time safe** | No (Koin) | Si | Si | Si | Si |

**Nota sobre wiring inmutable**: el grafo se auto-agrega con `@ContributesTo` (Req 6) en
todos los compile-time. Pero el dispatcher `get<T>(Class)` del facade solo es inmutable
nativamente en N (gracias a `koin.get(clazz.kotlin)`). En O/O2/P/P2, el `when` del facade
crece linealmente por API expuesta. Mitigable con un procesador KSP propio. Ver
`docs/shared/requirements.md` Req 11 para definicion completa.

### Benchmarks resumen (Samsung Galaxy S22 Ultra)

| Operacion | N<br>*(sweet-spi+Koin)* | O<br>*(Metro eager)* | O2<br>*(Metro Lazy)* | P<br>*(KI-anvil eager)* | P2<br>*(KI-anvil Lazy)* |
|-----------|---:|---:|---:|---:|---:|
| Init Cold (ns) | 69,636 | 603 | 1,127 | 1,064 | 1,416 |
| Resolve First (ns) | 5,855 | 288 | 315 | 336 | 335 |
| Lazy noDeps (ns) | 20,018 | 2,098 | 238 | 1,941 | 284 |
| Lazy cascade (ns) | 22,706 | 346 | 507 | 607 | 734 |
| Re-Init (ns) | 732,000 | 36,000 | 2,305 | 28,000 | 2,929 |
| E2E Startup (ns) | 2.0M | 1.2M | 1.5M | 1.4M | 993K |

---

## 7. Cual Elegir para KMP?

### Arbol de decision

```
Ya usas Koin en el proyecto?
├── SI --> N (sweet-spi + Koin) -- minimo cambio, pero 115x mas lento que Metro
│
└── NO
    ├── Necesitas lazy singletons?
    │   ├── SI
    │   │   ├── Prefieres compiler plugin? --> O2 (Metro Lazy)
    │   │   └── Prefieres KSP estandar? --> P2 (kotlin-inject-anvil Lazy)
    │   └── NO
    │       ├── Quieres el init mas rapido posible? --> O (Metro, 603 ns)
    │       └── Quieres KSP sin compiler plugin? --> P (kotlin-inject-anvil, 1,064 ns)
    │
    └── Tienes patrones L/M (Koin + ServiceLoader)?
        └── SI --> Migra a N (1 linea de cambio: import sweet-spi)
```

### Recomendacion

| Contexto | Patron | Razon |
|----------|--------|-------|
| SDK KMP nuevo, performance critica | **O** | Init mas rapido (603 ns), compile-time safe |
| SDK KMP con muchas features opcionales | **O2** | Re-init 15.6x mas rapido que O |
| Equipo que prefiere KSP estandar | **P** | Mejor tooling support, comunidad Amazon |
| Migracion desde Koin monolitico | **N** | Minimo esfuerzo, sweet-spi es drop-in |
