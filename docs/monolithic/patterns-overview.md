# Patrones Monoliticos de Inyeccion de Dependencias

Guia completa de los cinco patrones monoliticos implementados en el proyecto
`di-patterns-demo`. Cada patron resuelve el mismo problema -- un SDK con N features
donde el consumidor elige cuales activar -- pero con distinto nivel de acoplamiento,
escalabilidad y seguridad en compilacion.

Para patrones multi-modulo (D, E2, G, H, I, J, K, L, M, N, O, P), ver `docs/multimodule/`.
Para el analisis de rendimiento, ver [benchmark-results.md](benchmark-results.md).
Para los criterios de evaluacion (incluido el criterio bidimensional auto-registro
grafo + facade inmutable que aplica tambien a estos patrones monoliticos), ver
`docs/shared/requirements.md`.

**Nota sobre wiring del facade en monoliticos**: B y C tienen `when (clazz)` en sus
facades (`DaggerBSdk.get()` y `DaggerCSdk` per-Component), igual que los compile-time
multi-modulo (Q/Q2/O/P). Koin y Hybrid usan `koin.get()` runtime nativo (sin `when`).
Aplica el mismo criterio Req 11 que en multi-modulo.

---

## 1. Introduccion

Un SDK monolitico empaqueta todas las features en un unico artefacto publicable.
El consumidor anade una dependencia Gradle (`sdk-impl-dagger-b`, `sdk-impl-koin`, etc.)
y obtiene acceso a todas las features disponibles.

### Cuando usar patrones monoliticos

- SDK con 10 o menos features donde la complejidad multi-modulo no se justifica.
- Equipos pequenos (1-3 personas) que mantienen el SDK.
- Publicacion como un unico artefacto Maven (no se necesita `sdk-auth` separado de `sdk-storage`).
- Prototipado rapido o proyecto educativo.

### Cuando NO usar patrones monoliticos

- SDK con 20+ features donde el binario inflado importa.
- Equipos grandes (5+) que necesitan ownership per-feature.
- Publicacion per-feature en Maven Central.
- KMP donde cada plataforma tiene features distintas.

### El problema comun

El SDK tiene N features (Encryption, Auth, Storage, Analytics, Sync) con dependencias
cruzadas entre ellas. Los consumidores deben:

1. Elegir que features activar
2. No ver clases de implementacion
3. No pagar tamano binario por features que no usan

Los cinco patrones navegan estas restricciones de forma diferente.

---

## 2. Pattern A -- Educativo (Component Unico)

```
+---------------------------------------------------------+
|                 SdkComponent (@Singleton)                |
|                                                         |
|  CoreModule --- AuthModule --- AnalyticsModule          |
|  (Logger)      (AuthApi)      (AnalyticsApi)            |
|  (Config)                                               |
|  EncModule --- StorageModule --- SyncModule              |
+---------------------------------------------------------+
```

UN `@Component` lista TODOS los modulos de features. Dagger genera UNA factory
que sabe como crear todo. Cualquier modulo puede inyectar servicios de cualquier otro.

### Codigo real -- `sample-dagger-a/di/SdkComponent.kt`

```kotlin
@Singleton
@Component(modules = [
    CoreModule::class, EncryptionModule::class, AuthModule::class,
    StorageModule::class, AnalyticsModule::class, SyncModule::class,
])
interface SdkComponent {
    fun encryptionApi(): EncryptionApi
    fun hashApi(): HashApi
    fun authApi(): AuthApi
    fun storageApi(): StorageApi
    fun analyticsApi(): AnalyticsApi
    fun syncApi(): SyncApi
    fun logger(): SdkLogger

    @Component.Builder
    interface Builder {
        @dagger.BindsInstance fun config(config: SdkConfig): Builder
        fun build(): SdkComponent
    }
}

@Module class CoreModule {
    @Provides @Singleton fun logger(): SdkLogger = AndroidSdkLogger()
    @Provides @Singleton fun coreApis(config: SdkConfig, logger: SdkLogger): CoreApis =
        CoreApisImpl(config, logger)
}

@Module class EncryptionModule {
    @Provides @Singleton fun encryption(logger: SdkLogger): EncryptionApi =
        DefaultEncryptionService(logger)
    @Provides @Singleton fun hash(): HashApi = DefaultHashService()
}

@Module class SyncModule {
    @Provides @Singleton fun sync(
        auth: AuthApi, storage: StorageApi,
        enc: EncryptionApi, logger: SdkLogger,
    ): SyncApi = DefaultSyncService(auth, storage, enc, logger)
}
```

### Inicializacion -- `sample-dagger-a/DaggerAApp.kt`

```kotlin
class DaggerAApp : Application() {
    lateinit var sdkComponent: SdkComponent

    override fun onCreate() {
        super.onCreate()
        sdkComponent = DaggerSdkComponent.builder()
            .config(SdkConfig(debug = true))
            .build()
    }

    // "Lazy init" es falso -- solo trackea activacion, los servicios ya existen
    fun getOrInitFeature(feature: String): Set<String> {
        if (feature in _activeModules) return emptySet()
        _activeModules.add(feature)
        return setOf(feature)
    }
}
```

### Ventajas

- **Dependencias cruzadas automaticas.** SyncModule puede inyectar AuthApi porque
  comparten el mismo grafo Dagger.
- **Simple.** Un Component, un builder, una llamada init.
- **Validacion completa en compilacion.** Si falta un `@Provides`, el build falla.

### Desventajas

- **Binario inflado.** Todos los modulos se compilan en el APK aunque el consumidor solo
  use Auth.
- **Acoplamiento central.** Anadir una feature requiere editar la anotacion `@Component`.
- **No se puede publicar per-feature.** `sdk-auth` no puede ser un artefacto Maven
  independiente.
- **Lazy init falso.** `getOrInitFeature()` solo cambia un flag -- el codigo ya esta compilado.

### Cuando elegir A

SDKs educativos o prototipos con 5 o menos features donde la simplicidad importa mas
que la escalabilidad. No es viable para produccion.

---

## 3. Pattern B -- Per-Feature Components con CoreApis

```
+--------------+    +--------------+    +--------------+
| IntEncComp   |    | IntAuthComp  |    | IntAnaComp   |
|  @Singleton  |    |  @BAuthScope |    |  @BAnaScope  |
+-------+------+    +------+-------+    +------+-------+
        |                  |                   |
        +--------+---------+-------------------+
                 v
         +--------------+
         |   CoreApis   |  <-- interfaz Kotlin plana, NO Dagger
         +--------------+
```

Cada feature tiene su PROPIO `DaggerComponent`. No hay grafo global. El estado compartido
pasa a traves de `CoreApis` -- una interfaz Kotlin plana, no un constructo de Dagger.

### Codigo real -- `sdk/impl-dagger-b/InternalComponents.kt`

```kotlin
// CoreApis es una interfaz Kotlin plana
internal class CoreApisHolder(
    val core: CoreApis,
    val logger: SdkLogger,
)

// Cada feature recibe CoreApis via @BindsInstance
@Singleton @Component(modules = [IntEncMod::class])
internal interface IntEncComp {
    fun encryption(): EncryptionApi
    fun hash(): HashApi
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun core(core: CoreApis): Builder
        fun build(): IntEncComp
    }
}
@Module internal class IntEncMod {
    @Provides @Singleton fun enc(core: CoreApis): EncryptionApi =
        DefaultEncryptionService(core.logger)
    @Provides @Singleton fun hash(): HashApi = DefaultHashService()
}
```

### El problema de CoreApis

Cuando una feature necesita servicios de otra, CoreApis se extiende con interfaces
derivadas. Cada dependencia cruzada anade una interfaz y una clase de implementacion:

```kotlin
// Auth necesita EncryptionApi -- CoreApis extendido
internal interface AuthCoreApis : CoreApis {
    val encryptionApi: EncryptionApi
}
internal class AuthCoreApisImpl(
    private val base: CoreApis,
    override val encryptionApi: EncryptionApi,
) : AuthCoreApis {
    override val config get() = base.config
    override val logger get() = base.logger
}

// Sync necesita Auth + Storage + Encryption -- mega-CoreApis
internal interface SyncCoreApis : CoreApis {
    val authApi: AuthApi
    val storageApi: StorageApi
    val encryptionApi: EncryptionApi
}
```

Con 15+ servicios compartidos, estas interfaces se convierten en un God Object.

### Facade SDK -- `sdk/impl-dagger-b/DaggerBSdk.kt`

```kotlin
object DaggerBSdk {
    enum class Feature {
        ENCRYPTION, AUTH, STORAGE, ANALYTICS, SYNC;
        val requiredDependencies: Set<Feature> get() = when (this) {
            ENCRYPTION -> emptySet()
            AUTH -> setOf(ENCRYPTION)
            STORAGE -> setOf(ENCRYPTION)
            ANALYTICS -> emptySet()
            SYNC -> setOf(AUTH, STORAGE, ENCRYPTION)
        }
    }

    fun init(config: SdkConfig, features: Set<Feature>) { ... }

    fun getOrInitModule(feature: Feature): Set<Feature> {
        // Cascade: init dependencies first
        for (dep in feature.requiredDependencies) {
            if (dep !in _initializedModules) getOrInitModule(dep)
        }
        val core = _core!!
        when (feature) {
            Feature.ENCRYPTION -> {
                _enc = DaggerIntEncComp.builder().core(core).build()
            }
            Feature.AUTH -> {
                val authCore = AuthCoreApisImpl(core, _enc!!.encryption())
                _auth = DaggerIntAuthComp.builder().core(authCore).build()
            }
            Feature.SYNC -> {
                val syncCore = SyncCoreApisImpl(
                    core, _auth!!.auth(), _storage!!.storage(), _enc!!.encryption()
                )
                _sync = DaggerIntSynComp.builder().core(syncCore).build()
            }
            // ...
        }
    }

    inline fun <reified T : Any> get(): T = get(T::class.java)
    fun shutdown() { ... }
}
```

### Ventajas

- **Binario eficiente.** Solo las features con dependencia Gradle acaban en el APK.
- **Publicacion independiente.** `sdk-encryption` y `sdk-auth` son artefactos Maven separados.
- **Lazy init real.** `getOrInitModule()` crea un DaggerComponent nuevo on-demand.

### Desventajas

- **Sin DI cross-feature.** Feature A no puede `@Inject` un servicio de Feature B --
  estan en Components separados.
- **CoreApis crece.** Cada servicio compartido entre features = un campo mas en CoreApis.
- **Edicion central.** Nueva feature = editar el `when` block del facade SDK.

---

## 4. Pattern C -- Per-Feature + ServiceLoader Discovery

Misma arquitectura que B (Components separados), pero las features se auto-registran via
`ServiceLoader` de JVM. Anadir una feature = anadir dependencia Gradle + fichero META-INF.
Zero ediciones en el facade central.

### Contrato -- `FeatureInitializer`

```kotlin
interface FeatureInitializer {
    val featureName: String
    val requiredDependencies: Set<String>
    fun init(core: CoreApis, resolved: ServiceResolver)
    fun shutdown()
    fun <T> getService(serviceClass: Class<T>): T?
}

interface ServiceResolver {
    fun <T> resolve(serviceClass: Class<T>): T?
}
```

### Implementacion de una feature -- `InternalComponents.kt`

Cada feature implementa `FeatureInitializer` y declara sus dependencias:

```kotlin
class EncryptionInit : FeatureInitializer {
    private var comp: CEncComp? = null
    override val featureName = "encryption"
    override val requiredDependencies = emptySet<String>()
    override fun init(core: CoreApis, resolved: ServiceResolver) {
        comp = DaggerCEncComp.builder().core(core).build()
    }
    override fun shutdown() { comp = null }
    override fun <T> getService(serviceClass: Class<T>): T? = when (serviceClass) {
        EncryptionApi::class.java -> serviceClass.cast(comp?.encryption())
        HashApi::class.java -> serviceClass.cast(comp?.hash())
        else -> null
    }
}

class SyncInit : FeatureInitializer {
    private var comp: CSynComp? = null
    override val featureName = "sync"
    override val requiredDependencies = setOf("auth", "storage", "encryption")
    override fun init(core: CoreApis, resolved: ServiceResolver) {
        comp = DaggerCSynComp.builder()
            .auth(resolved.resolve(AuthApi::class.java)!!)
            .storage(resolved.resolve(StorageApi::class.java)!!)
            .enc(resolved.resolve(EncryptionApi::class.java)!!)
            .logger(core.logger).build()
    }
    // ...
}
```

### Registro META-INF

Fichero `META-INF/services/com.grinwich.sdk.daggerc.FeatureInitializer`:

```
com.grinwich.sdk.daggerc.EncryptionInit
com.grinwich.sdk.daggerc.AuthInit
com.grinwich.sdk.daggerc.StorageInit
com.grinwich.sdk.daggerc.AnalyticsInit
com.grinwich.sdk.daggerc.SyncInit
```

### Facade SDK -- `sdk/impl-dagger-c/DaggerCSdk.kt`

```kotlin
object DaggerCSdk {
    private fun discover(): Map<String, FeatureInitializer> =
        _available ?: ServiceLoader.load(FeatureInitializer::class.java)
            .associateBy { it.featureName }.also { _available = it }

    fun init(config: SdkConfig, features: Set<String>) {
        _core = CoreApisImpl(config, foundationLogger)
        _initialized = true
        for (f in features) getOrInitModule(f)
    }

    fun getOrInitModule(feature: String): Set<String> {
        val available = discover()
        val init = available[feature]
            ?: throw IllegalArgumentException(
                "Feature '$feature' not on classpath. Available: ${available.keys}"
            )
        // Cascade dependencies
        for (dep in init.requiredDependencies) {
            if (dep !in _initializers) getOrInitModule(dep)
        }
        init.init(_core!!, resolver)
        _initializers[feature] = init
        return setOf(feature)
    }

    fun <T : Any> get(clazz: Class<T>): T {
        for (init in _initializers.values) {
            init.getService(clazz)?.let { return it }
        }
        error("Service ${clazz.simpleName} not found.")
    }

    inline fun <reified T : Any> get(): T = get(T::class.java)
    fun shutdown() { ... }
}
```

### Ventajas sobre B

- **Zero edicion central.** `DaggerCSdk.kt` no se toca al anadir features.
- **Escalable.** Con 20+ features, el `when` block de B es inmantenible. C escala sin
  ediciones centrales.

### Desventajas

- **JVM exclusivo.** `ServiceLoader` requiere `META-INF/services/` -- no disponible en
  Kotlin/Native ni iOS.
- **Errores runtime.** Dependencia Gradle ausente = crash en init, no error de compilacion.
- **Mismo problema CoreApis que B.** Las dependencias cruzadas entre Components siguen
  siendo manuales (via `ServiceResolver` en vez de CoreApis extendido, pero el concepto
  es el mismo).

---

## 5. Koin -- Service Locator

```
+-----------------------------------------------------------+
|              KoinApplication (aislado)                     |
|                                                            |
|  foundationModule   encryptionModule   authModule          |
|  (SdkConfig)        (EncryptionApi)    (AuthApi)           |
|  (SdkLogger)        (HashApi)          necesita get()      |
|  (CoreApis)                                                |
|                     storageModule      syncModule           |
|                     (StorageApi)       (SyncApi)            |
|                                        necesita get() x4   |
+-----------------------------------------------------------+
```

Koin usa `koinApplication {}` aislado (NO `startKoin` global) para evitar conflictos
con la app consumidora. Zero anotaciones, zero codegen. Todo es Kotlin puro resuelto
en runtime.

### Seleccion de features -- sealed class

```kotlin
sealed class SdkModule(val key: String) {
    sealed class Encryption(key: String) : SdkModule(key) {
        data object Default : Encryption("encryption-default")
    }
    sealed class Auth(key: String) : SdkModule(key) {
        data object Default : Auth("auth-default")
    }
    sealed class Storage(key: String) : SdkModule(key) {
        data object Secure : Storage("storage-secure")
    }
    sealed class Analytics(key: String) : SdkModule(key) {
        data object Default : Analytics("analytics-default")
    }
    sealed class Sync(key: String) : SdkModule(key) {
        data object Default : Sync("sync-default")
    }

    val requiredDependencies: Set<SdkModule>
        get() = when (this) {
            is Encryption.Default -> emptySet()
            is Auth.Default -> setOf(Encryption.Default)
            is Storage.Secure -> setOf(Encryption.Default)
            is Analytics.Default -> emptySet()
            is Sync.Default -> setOf(Auth.Default, Storage.Secure, Encryption.Default)
        }
}
```

### Registros per-feature

```kotlin
object EncryptionRegistration : SdkModuleRegistration {
    override val module = SdkModule.Encryption.Default
    override val koinModule = module {
        single<HashApi> { DefaultHashService() }
        single<EncryptionApi> { DefaultEncryptionService(get()) }
    }
    init { SdkModuleRegistry.register(module) { koinModule } }
}

object SyncRegistration : SdkModuleRegistration {
    override val module = SdkModule.Sync.Default
    override val koinModule = module {
        // Cross-feature: get() resuelve Auth, Storage, Encryption del mismo grafo
        single<SyncApi> { DefaultSyncService(get(), get(), get(), get()) }
    }
    init { SdkModuleRegistry.register(module) { koinModule } }
}
```

### Facade SDK -- `sdk/impl-koin/KoinSdk.kt`

```kotlin
object KoinSdk {
    private var _koinApp: KoinApplication? = null

    val koin: Koin
        get() {
            check(_initialized) { "KoinSdk not initialized. Call init() first." }
            return _koinApp!!.koin
        }

    fun init(
        modules: Set<SdkModule>,
        config: SdkConfig = SdkConfig(),
        appModules: List<Module> = emptyList(),
    ) {
        check(!_initialized) { "KoinSdk already initialized." }
        discoverRegistrations(modules)
        val foundation = foundationModule(config)
        val resolved = modules.map { SdkModuleRegistry.resolve(it) }
        _koinApp = koinApplication {
            modules(listOf(foundation) + resolved + appModules)
        }
        _initialized = true
        _initializedModules = modules.toMutableSet()
    }

    fun getOrInitModule(module: SdkModule): Set<SdkModule> {
        if (module in _initializedModules) return emptySet()
        // Cascade: init dependencies first
        val initialized = mutableSetOf<SdkModule>()
        for (dep in module.requiredDependencies) {
            if (dep !in _initializedModules) initialized += getOrInitModule(dep)
        }
        discoverRegistrations(setOf(module))
        val koinModule = SdkModuleRegistry.resolve(module)
        _koinApp!!.koin.loadModules(listOf(koinModule))
        _initializedModules.add(module)
        initialized.add(module)
        return initialized
    }

    inline fun <reified T : Any> get(): T = koin.get()
    fun shutdown() { _koinApp?.close(); _initialized = false }
}
```

### Ventajas

- **Zero codegen.** Sin anotaciones, sin KSP, sin ficheros generados. Builds mas rapidos.
- **Minima complejidad estructural.** Un solo fichero (`KoinSdk.kt`) contiene todo.
- **Cross-feature automatico.** `get()` resuelve servicios del mismo grafo Koin.
- **Lazy init real.** `loadModules()` anade modulos a un `koinApplication` vivo.
- **KMP compatible.** Koin 4.x soporta Kotlin Multiplatform.
- **Escala a 50+ modulos** sin God Object ni when blocks.

### Desventajas

- **Errores runtime.** Un binding faltante produce `NoBeanDefFoundException` en ejecucion,
  no un error de compilacion.
- **Dependencia circular = StackOverflow.** No hay deteccion en compilacion.
- **~100 KB runtime.** Koin Core anade peso al APK (insignificante en practica).
- **Resolucion mas lenta.** `get()` = HashMap lookup (~647 ns primer acceso) vs campo
  volatil Dagger (~7.5 ns).

---

## 6. Hybrid -- Koin SDK + Dagger App Bridge

```
+--------------------------------------------------------------+
|                   App Android (Dagger 2)                      |
|                                                               |
|  +---------------------------+  +-------------------------+  |
|  |  Grafo Dagger de la app   |  |  SdkBridgeComponent     |  |
|  |                           |  |  @Component             |  |
|  |  AppRepository -----------|->|  @Provides encrypt()    |  |
|  |  SettingsViewModel        |  |  @Provides hash()       |  |
|  +---------------------------+  +-----------+-------------+  |
|                                             |                 |
|                                   KoinSdk.get<T>()            |
|                                             |                 |
|  +------------------------------------------v--------------+  |
|  |         SDK (Koin -- koinApplication aislado)            |  |
|  |                                                          |  |
|  |  EncryptionModule -- AuthModule -- StorageModule          |  |
|  |  FoundationSingletons (logger sobrevive reinit)          |  |
|  +----------------------------------------------------------+  |
+--------------------------------------------------------------+
```

Este patron existe cuando el SDK necesita KMP (Koin funciona en iOS/macOS/Desktop)
pero la app consumidora es Android con Dagger existente. Dos contenedores DI separados
conectados por un bridge Dagger.

### Paso 1: Init del SDK -- `sample-hybrid/HybridApp.kt`

```kotlin
class HybridApp : Application() {
    lateinit var bridgeComponent: SdkBridgeComponent

    override fun onCreate() {
        super.onCreate()
        // SDK PRIMERO -- debe existir antes de que Dagger resuelva el bridge
        KoinSdk.init(
            modules = setOf(SdkModule.Encryption.Default),
            config = SdkConfig(debug = true),
        )
        // Bridge Dagger -- conecta servicios del SDK al grafo Dagger de la app
        bridgeComponent = DaggerSdkBridgeComponent.builder().build()
    }
}
```

**Si el orden es incorrecto:** `KoinSdk.koin` lanza `IllegalStateException("KoinSdk not initialized")`
durante la creacion del Component Dagger. Crash al arrancar -- facil de diagnosticar.

### Paso 2: El Bridge -- `sample-hybrid/di/SdkBridgeModule.kt`

```kotlin
@Singleton
@Component(modules = [SdkBridgeModule::class])
interface SdkBridgeComponent {
    fun encryption(): EncryptionApi
    fun hash(): HashApi

    @Component.Builder
    interface Builder {
        fun build(): SdkBridgeComponent
    }
}

@Module
class SdkBridgeModule {
    @Provides @Singleton
    fun provideEncryptionApi(): EncryptionApi = KoinSdk.get()

    @Provides @Singleton
    fun provideHashApi(): HashApi = KoinSdk.get()
}
```

Lo que ocurre en runtime:

1. Dagger crea `SdkBridgeComponent`
2. Llama a `provideEncryptionApi()` una vez (`@Singleton`)
3. Ese metodo llama `KoinSdk.get<EncryptionApi>()` -- Koin resuelve desde su grafo
4. Dagger cachea el resultado
5. Accesos posteriores devuelven la instancia cacheada (~1.9 ns, igual que Dagger puro)

### Paso 3: La App -- `sample-hybrid/MainActivity.kt`

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val bridge = (application as HybridApp).bridgeComponent
        val encryption = bridge.encryption()  // Dagger cached -- zero Koin
        encryption.encrypt("hello")
    }
}
```

La Activity no sabe que `EncryptionApi` viene de Koin. Es acceso a un singleton Dagger.

### Puente unidireccional

```
   Grafo Dagger <--bridge---- Grafo Koin
   (puede inyectar servicios SDK)  (NO puede inyectar servicios app)
```

La app inyecta servicios del SDK via bridge. El SDK NO puede inyectar servicios de la app.

### Features lazy y el bridge

Las features anadidas con `getOrInitModule()` despues de crear el bridge NO son visibles
via `SdkBridgeComponent` (el `@Singleton` ya cacheo). Para features lazy, acceder
directamente desde `KoinSdk.get()`:

```kotlin
// Lazy -- NO pasa por el bridge Dagger
KoinSdk.getOrInitModule(SdkModule.Sync.Default)
val sync = KoinSdk.get<SyncApi>()  // directo desde Koin
```

### Ventajas

- **Coexistencia Dagger + Koin.** Dagger es codegen puro (zero estado global runtime).
  Koin 4.x soporta `koinApplication {}` aislado.
- **Resolucion post-bridge igual a Dagger puro.** ~1.9 ns por acceso cached.
- **SDK KMP-ready.** Koin funciona en todas las plataformas. El bridge solo existe en Android.
- **App zero conocimiento de Koin.** La Activity solo ve interfaces Dagger.

### Desventajas

- **Dos contenedores runtime.** ~100 KB memoria extra (Koin). Negligible.
- **Bridge boilerplate.** Un `@Provides` por servicio que el consumidor quiera inyectar
  via Dagger. 20 servicios = 20 lineas.
- **Orden de init.** SDK antes que Dagger. Error facil de cometer, pero falla rapido.
- **Features lazy bypasean bridge.** El consumidor debe saber que para features lazy
  se usa `KoinSdk.get()` directo.
- **Patron mas complejo para el consumidor.** Los demas patrones son transparentes
  (misma API facade). Hybrid requiere crear un bridge Component.

---

## 7. Coste de anadir una feature

### Tabla comparativa

| Paso | Dagger A | Dagger B | Dagger C | Koin | Hybrid |
|------|----------|----------|----------|------|--------|
| Nueva interfaz API | 1 fichero | 1 fichero | 1 fichero | 1 fichero | 1 fichero (usa Koin) |
| Nueva implementacion | 1 fichero | 1 fichero | 1 fichero | 1 fichero | 1 fichero (usa Koin) |
| Nuevo Component/Module DI | Editar @Component | +@Component +@Module +@Scope | +@Component +@Module +@Scope +FeatureInitializer | +object Registration (~15 lineas) | +object Registration (~15 lineas) |
| CoreApis extendido (si cross-deps) | No necesario | +interfaz +clase impl | No necesario (ServiceResolver) | No necesario (get()) | No necesario (get()) |
| Edicion central facade | Editar @Component | Editar when block | Ninguna (ServiceLoader) | Editar sealed class | Editar sealed class |
| META-INF | No | No | +1 linea | No | No |
| Bridge (si consumidor Dagger) | N/A | N/A | N/A | N/A | +1 @Provides |

### Resumen numerico

| Patron | Puntos de contacto | Ficheros tocados | Riesgo principal |
|--------|-------------------|------------------|-----------------|
| **A** (educativo) | 3 | 2 | Binario inflado |
| **B** (Per-Feature) | 6 | 3 | God Object (CoreApis) |
| **C** (ServiceLoader) | 5 | 3 + META-INF | Errores runtime silenciosos |
| **Koin** | 4 | 3 | Errores runtime |
| **Hybrid** | 4 + bridge | 3 + bridge | Dos contenedores, orden de init |

---

## 8. Cuando usar cada patron

| Escenario | Patron recomendado | Justificacion |
|-----------|-------------------|---------------|
| Prototipo / educativo, <=5 features | **A** | Simplicidad maxima, todo en un grafo |
| SDK modular monolitico, publicacion per-feature | **B** | Components independientes, artefactos Maven separados |
| Features independientes + auto-discovery JVM | **C** | Zero edicion central, ServiceLoader |
| Equipo sin experiencia Dagger | **Koin** | Zero anotaciones, zero codegen, curva de aprendizaje minima |
| SDK escalable a 50+ modulos | **Koin** | Sealed class crece linealmente pero sin God Object |
| SDK KMP, app consumidora con Dagger | **Hybrid** | Koin para KMP, bridge para compatibilidad Dagger |
| App ya usa Koin | **Koin** (sin bridge) | Un solo grafo Koin con `appModules` |
| Compile-time safety prioritaria | **B** | Errores de Dagger en compilacion (per-Component) |
| Multiples apps consumen el SDK (Dagger y Koin) | **Hybrid** | Cada app conecta diferente al mismo SDK Koin |

### Nota sobre patrones multi-modulo

Cuando el SDK crece mas alla de 10 features o necesita ownership per-feature en equipos
grandes, los patrones monoliticos dejan de ser adecuados. Los patrones multi-modulo
(D, E2, G, H, I, J, K) ofrecen:

- Separacion Gradle real (api/impl por feature)
- Components `internal` por feature
- Provision interfaces como contratos entre modulos
- Wiring modules independientes del codigo de feature

Ver `docs/multimodule/` para la documentacion completa de patrones multi-modulo.
