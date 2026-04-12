# Patrones Multi-Modulo Android-Only (D, E2, G, H, I, K, Q, Q2)

Los 8 patrones Android-only comparten una restriccion: dependen de APIs que solo
existen en JVM/Android (Dagger genera Java, `ServiceLoader` usa `META-INF/services/`,
`PackageManager` es Android-only). No compilan para iOS, macOS ni WASM.

Todos implementan la misma interfaz `MultiModuleSdkApi` y usan las mismas
provision interfaces definidas en `di-contracts/`.

Para la documentacion completa con codigo de D, E2, G, H, I y K, ver
`docs/multimodule/patterns-overview.md`. Este documento contiene un resumen
de esos 6 patrones mas la documentacion completa de Q y Q2.

---

## 1. Resumen de Patrones Existentes

### Pattern D -- Component Dependencies (sdk-wiring)

Cada feature tiene su `DaggerComponent` con `dependencies = [ProvisionInterface::class]`.
El wiring module conecta todos los Components manualmente via funciones `ensure*()`
con when-blocks. Lazy init real: cada Component se crea on-demand con cascada
de dependencias.

- **Discovery:** Manual (when-block en el facade)
- **Escala:** No escala a 50+ (when-blocks crecen linealmente)
- **Init Cold:** 1,212 ns

### Pattern E2 -- Auto-Init Registry (wiring-e2)

Un `AutoProvisionRegistry` cataloga `AutoProvisionEntry` en init (HashMap puts)
y construye Components on-demand via DFS recursivo en `get<T>()`. El facade es
casi inmutable: anadir modulo = 1 linea en `allEntries()`.

- **Discovery:** Auto (Registry DFS)
- **Escala:** Si (1 linea por feature)
- **Init Cold:** 10,983 ns

### Pattern G -- Factory Functions (wiring-g)

Identico a D, pero cada feature-impl expone una funcion factory publica
(`buildXxxProvisions(deps)`) que encapsula la creacion del `DaggerComponent`.
El Component queda `internal`. Mismo patron lazy `ensure*()`.

- **Discovery:** Manual (factory functions)
- **Escala:** No escala a 50+ (ensure crecen linealmente)
- **Init Cold:** 1,257 ns

### Pattern H -- ServiceLoader + FeatureProvider + Resolver DFS (wiring-h)

Cada feature-impl declara un `FeatureProvider` (~8 lineas). Las dependencias son
implicitas: lo que el provider pide al `Resolver` dentro de `build()` se construye
via DFS. El wiring module descubre providers via `ServiceLoader` y es completamente
inmutable.

- **Discovery:** ServiceLoader
- **Escala:** Si (zero edicion del wiring)
- **Init Cold:** 106,865 ns

### Pattern I -- Pure (zero DI framework) (wiring-i)

Misma arquitectura que H (ServiceLoader + Resolver DFS), pero las features se
construyen sin ningun framework DI. Cada `PureFeatureProvider` crea servicios
directamente via constructores Kotlin.

- **Discovery:** ServiceLoader
- **Escala:** Si (zero edicion del wiring)
- **Init Cold:** 94,255 ns

### Pattern K -- AndroidManifest Metadata Discovery (wiring-k)

Misma arquitectura que H, pero el discovery usa `PackageManager.getServiceInfo()`
para leer `<meta-data>` del AndroidManifest.xml mergeado. Requiere Android Context.
Es el mas lento en init por el overhead de PackageManager + reflexion.

- **Discovery:** AndroidManifest meta-data
- **Escala:** Si (zero edicion del wiring)
- **Init Cold:** 213,737 ns

---

## 2. Pattern Q -- Hilt-style Dagger (wiring-q)

### Concepto

Cada feature-impl define un `@Module @InstallIn(SingletonComponent)` siguiendo
las convenciones de Hilt. En una app Hilt real, estos modules se auto-descubren
via `@HiltAndroidApp`. Aqui se incluyen explicitamente en un `@Component` para
benchmarkear Dagger compile-time DI sin requerir el lifecycle de Hilt Application.

Clave: el wiring NO tiene when-blocks ni Resolver. El `@Component` de Dagger
ya conoce todos los modules en compilacion. El facade solo crea el component y
expone los bindings.

### Codigo del wiring

```kotlin
// sdk/wiring-q/src/main/kotlin/.../MultiModuleSdkQ.kt

@Singleton
@Component(
    modules = [
        HiltEncModule::class,
        HiltAuthModule::class,
        HiltStorModule::class,
        HiltAnaModule::class,
        HiltSynModule::class,
    ],
)
interface SdkComponent {
    fun context(): Context
    fun encryption(): EncryptionApi
    fun hash(): HashApi
    fun auth(): AuthApi
    fun storage(): StorageApi
    fun analytics(): AnalyticsApi
    fun sync(): SyncApi

    @Component.Factory
    interface Factory {
        fun create(
            @BindsInstance context: Context,
            @BindsInstance config: SdkConfig,
            @BindsInstance logger: SdkLogger,
            @BindsInstance storageBackend: StorageBackend,
        ): SdkComponent
    }
}

object MultiModuleSdkQ : MultiModuleSdkApi {

    private var _component: SdkComponent? = null
    private var _initialized = false
    private var _logger: SdkLogger = AndroidSdkLogger()

    override val isInitialized: Boolean get() = _initialized
    override val builtProvisionCount: Int get() = if (_initialized) 5 else 0

    override fun init(context: Context, config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdkQ already initialized." }
        _component = DaggerSdkComponent.factory().create(
            context = context.applicationContext,
            config = config,
            logger = _logger,
            storageBackend = config.storageBackend,
        )
        _initialized = true
    }

    override fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdkQ not initialized." }
        val component = _component ?: error("component is null")
        return when (clazz) {
            EncryptionApi::class.java -> component.encryption()
            HashApi::class.java -> component.hash()
            AuthApi::class.java -> component.auth()
            StorageApi::class.java -> component.storage()
            AnalyticsApi::class.java -> component.analytics()
            SyncApi::class.java -> component.sync()
            SdkLogger::class.java -> _logger
            Context::class.java -> component.context()
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

### Diferencia clave con D/G

En D y G, el facade orquesta la creacion de Components individuales con `ensure*()`
y gestiona el orden de dependencias manualmente. En Q, Dagger resuelve TODO el
grafo en compilacion: el facade solo llama `DaggerSdkComponent.factory().create()`
y los bindings ya estan conectados.

En D, anadir una feature requiere editar when-blocks. En Q, anadir una feature
requiere anadir un `@Module` a la lista del `@Component` -- una sola linea.

### Ventajas

- **Compile-time safe.** Todo el grafo se valida en compilacion. Module faltante = error KSP.
- **Hilt-familiar.** Sigue las convenciones `@Module @InstallIn()` que los desarrolladores Android conocen.
- **Init ultra-rapido.** 676 ns -- segundo mas rapido despues de Metro (603 ns).
- **Zero Resolver.** Sin DFS, sin ServiceLoader, sin Registry. Dagger genera el wiring.
- **Shutdown trivial.** Basta con nullificar el component.

### Desventajas

- **Modules explicitos.** Cada feature @Module se lista en el @Component (1 linea por feature).
- **JVM exclusivo.** Dagger no soporta KMP.
- **No es Hilt real.** Sin @HiltAndroidApp, los modules no se auto-descubren.
- **Eager por defecto.** Todos los @Singleton se crean al construir el component.

---

## 3. Pattern Q2 -- Hilt-style Dagger Lazy (wiring-q2)

### Concepto

Identico a Q pero los metodos de provision del component retornan `dagger.Lazy<T>`
en vez de `T` directo. El component se crea en `init()`, pero los `@Singleton`
NO se instancian hasta el primer acceso via `Lazy.get()`. Un `LazyCreationTracker`
cuenta cuantas features se han materializado.

### Codigo del wiring

```kotlin
// sdk/wiring-q2/src/main/kotlin/.../MultiModuleSdkQ2.kt

@Singleton
@Component(
    modules = [
        HiltEncModule::class,
        HiltAuthModule::class,
        HiltStorModule::class,
        HiltAnaModule::class,
        HiltSynModule::class,
    ],
)
interface SdkComponent {
    fun context(): Context
    fun encryption(): dagger.Lazy<EncryptionApi>
    fun hash(): dagger.Lazy<HashApi>
    fun auth(): dagger.Lazy<AuthApi>
    fun storage(): dagger.Lazy<StorageApi>
    fun analytics(): dagger.Lazy<AnalyticsApi>
    fun sync(): dagger.Lazy<SyncApi>

    @Component.Factory
    interface Factory {
        fun create(
            @BindsInstance context: Context,
            @BindsInstance config: SdkConfig,
            @BindsInstance logger: SdkLogger,
            @BindsInstance storageBackend: StorageBackend,
        ): SdkComponent
    }
}

object MultiModuleSdkQ2 : MultiModuleSdkApi {

    private var _component: SdkComponent? = null
    private var _initialized = false
    private var _tracker: LazyCreationTracker.Instance? = null
    private var _logger: SdkLogger = AndroidSdkLogger()

    override val isInitialized: Boolean get() = _initialized
    override val builtProvisionCount: Int get() = _tracker?.count ?: 0

    override fun init(context: Context, config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdkQ2 already initialized." }
        _tracker = LazyCreationTracker.activate()
        _component = DaggerSdkComponent.factory().create(
            context = context.applicationContext,
            config = config,
            logger = _logger,
            storageBackend = config.storageBackend,
        )
        _initialized = true
    }

    override fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdkQ2 not initialized." }
        val component = _component ?: error("component is null")
        return when (clazz) {
            EncryptionApi::class.java -> component.encryption().get()
            HashApi::class.java -> component.hash().get()
            AuthApi::class.java -> component.auth().get()
            // ...demas bindings con .get()
            else -> error("No binding for ${clazz.simpleName}")
        } as T
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

### Diferencia clave con Q

En Q, `component.encryption()` retorna `EncryptionApi` directamente -- el singleton
ya esta instanciado. En Q2, `component.encryption()` retorna `dagger.Lazy<EncryptionApi>`
y el singleton se crea la primera vez que se llama `.get()`.

Impacto en benchmarks:
- **Init Cold:** Q = 676 ns, Q2 = 1,080 ns (el Lazy wrapper anade ~400 ns)
- **Re-Init:** Q = 25,000 ns, Q2 = 2,157 ns (**11.6x mas rapido** -- no recrea singletons)
- **Lazy noDeps:** Q = 1,735 ns, Q2 = 236 ns (6.5x -- singleton bajo demanda)

### Ventajas

- **Re-init ultra-rapido.** 2,157 ns vs 25,000 ns de Q -- crucial para hot restart.
- **Lazy real.** Singletons se crean solo cuando se usan. Si solo accedes 2 de 5 features,
  las otras 3 nunca se instancian.
- **Mismo compile-time safety que Q.** Todo validado por Dagger en compilacion.
- **LazyCreationTracker.** Observabilidad: puedes saber cuantas features se han materializado.

### Desventajas

- **Init ligeramente mas lento.** 1,080 ns vs 676 ns (+60%) por el setup de LazyCreationTracker.
- **JVM exclusivo.** Dagger no soporta KMP.
- **dagger.Lazy wrapper.** Cada acceso paga el coste de `.get()` (trivial despues de la primera vez).

---

## 4. Tabla Comparativa Android-Only

### Caracteristicas

| Criterio | D<br>*(Dagger when-block)* | E2<br>*(Registry DFS)* | G<br>*(Factory functions)* | H<br>*(Resolver+Dagger)* | I<br>*(Pure Resolver)* | K<br>*(Manifest Discovery)* | Q<br>*(Dagger @Module)* | Q2<br>*(Dagger Lazy)* |
|----------|---|---|----|---|---|---|---|---|
| **Framework DI** | Dagger | Dagger | Dagger | Dagger + SL | Ninguno | Dagger + Manifest | Dagger | Dagger |
| **Wiring inmutable** | No | No | No | Si | Si | Si | Semi | Semi |
| **Auto-descubrimiento** | No | No | No | ServiceLoader | ServiceLoader | Manifest | Compile-time | Compile-time |
| **Escala 50+** | No | Si | No | Si | Si | Si | Si | Si |
| **Compile-time safe** | Completo | Completo | Completo | Per-Component | No | Per-Component | Completo | Completo |
| **Lazy singletons** | Si (ensure) | Si (DFS) | Si (ensure) | Si (DFS) | Si (DFS) | Si (DFS) | No | Si (dagger.Lazy) |
| **Codegen** | KSP -> Java | KSP -> Java | KSP -> Java | KSP -> Java | Ninguno | KSP -> Java | KSP -> Java | KSP -> Java |

### Benchmarks resumen (Samsung Galaxy S22 Ultra)

| Operacion | D<br>*(Dagger when-block)* | E2<br>*(Registry DFS)* | G<br>*(Factory functions)* | H<br>*(Resolver+Dagger)* | I<br>*(Pure Resolver)* | K<br>*(Manifest Discovery)* | Q<br>*(Dagger @Module)* | Q2<br>*(Dagger Lazy)* |
|-----------|---:|---:|---:|---:|---:|---:|---:|---:|
| Init Cold (ns) | 1,212 | 10,983 | 1,257 | 106,865 | 94,255 | 213,737 | 676 | 1,080 |
| Resolve First (ns) | 346 | 199 | 345 | 202 | 203 | 203 | 257 | 306 |
| Lazy noDeps (ns) | 255 | 1,049 | 260 | 1,278 | 1,112 | 2,996 | 1,735 | 236 |
| Lazy cascade (ns) | 696 | 3,088 | 848 | 3,892 | 4,122 | 7,900 | 318 | 504 |
| Re-Init (ns) | 36,000 | 17,000 | 38,000 | 363,000 | 427,000 | 767,000 | 25,000 | 2,157 |

### Ranking Init Cold

1. **Q** (676 ns) -- Dagger compile-time, todo pre-wired
2. **Q2** (1,080 ns) -- Dagger Lazy, setup de tracker anade ~400 ns
3. **D** (1,212 ns) -- Solo construye CoreComponent en init
4. **G** (1,257 ns) -- Identico a D con factory functions
5. **E2** (10,983 ns) -- Catalogar entries en HashMaps
6. **I** (94,255 ns) -- ServiceLoader sin framework DI
7. **H** (106,865 ns) -- ServiceLoader + Dagger
8. **K** (213,737 ns) -- PackageManager + reflexion

---

## 5. Cuando Elegir Cada Patron

| Situacion | Patron | Razon |
|-----------|--------|-------|
| App con Hilt existente | **Q** | Convenciones familiares, init rapido |
| SDK con hot restart frecuente | **Q2** | Re-init 11.6x mas rapido que Q |
| Proyecto pequeno (<10 features) | **D** o **G** | Simple, compile-time safe, sin overhead |
| SDK con plugins dinamicos | **H** | Auto-discovery, wiring inmutable |
| Sin dependencia en frameworks | **I** | Zero DI, zero KSP, maximo control |
| Firebase-style init | **K** | AndroidManifest discovery |
