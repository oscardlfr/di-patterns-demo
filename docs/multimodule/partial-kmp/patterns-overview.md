# Patrones Multi-Modulo Partial KMP (J, L, M)

Estos 3 patrones usan un framework DI que soporta KMP (kotlin-inject o Koin),
pero su mecanismo de discovery depende de `java.util.ServiceLoader` -- una API
que solo existe en JVM. Esto los hace "partial KMP": la logica de DI es
multiplataforma, pero el wiring no compila para iOS, macOS ni WASM.

La buena noticia: **convertirlos en Full KMP es trivial**. Basta con reemplazar
`java.util.ServiceLoader` por sweet-spi (como hace Pattern N).

Todos implementan la misma interfaz `MultiModuleSdkApi` y usan las mismas
provision interfaces definidas en `di-contracts/`.

---

## 1. Pattern J -- kotlin-inject + ServiceLoader (wiring-j)

### Concepto

Misma arquitectura que H (ServiceLoader + FeatureProvider + Resolver DFS), pero
las features usan **kotlin-inject** en lugar de Dagger para la inyeccion de
dependencias interna. `KIFeatureProvider` es un marker que extiende `FeatureProvider`,
permitiendo al `Resolver` funcionar de forma identica.

kotlin-inject genera codigo Kotlin (no Java) via KSP, con menos boilerplate que
Dagger: el Component actua tambien como Module.

### Codigo del wiring

```kotlin
// sdk/wiring-j/src/main/kotlin/.../MultiModuleSdkJ.kt

object MultiModuleSdkJ : MultiModuleSdkApi {

    private val resolver = Resolver()

    override fun init(context: Context, config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdkJ already initialized." }
        resolver.init(config)

        ServiceLoader.load(KIFeatureProvider::class.java).forEach { provider ->
            resolver.register(provider)
        }

        _initialized = true
    }

    override fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdkJ not initialized." }
        return resolver.get(clazz)
    }

    override fun shutdown() {
        if (!_initialized) return
        resolver.clear()
        _initialized = false
    }
}
```

### Codigo del KIFeatureProvider (ejemplo: Encryption)

```kotlin
@Component
abstract class KIEncComponent(
    @get:Provides val logger: SdkLogger,
) {
    abstract val encryption: EncryptionApi
    abstract val hash: HashApi

    @Provides fun encryptionApi(): EncryptionApi = DefaultEncryptionService(logger)
    @Provides fun hashApi(): HashApi = DefaultHashService()
}

class EncKIProvider : KIFeatureProvider<EncProvisions>(EncProvisions::class.java) {
    override val services: Map<Class<*>, (EncProvisions) -> Any> = mapOf(
        EncryptionApi::class.java to EncProvisions::encryption,
        HashApi::class.java to EncProvisions::hash,
    )
    override fun build(resolver: Resolver): EncProvisions {
        val component = KIEncComponent::class.create(logger = resolver.logger)
        val enc = component.encryption
        val hash = component.hash
        return object : EncProvisions {
            override fun encryption() = enc
            override fun hash() = hash
        }
    }
}
```

### Porque es Partial KMP

```kotlin
// Esta linea es JVM-only:
ServiceLoader.load(KIFeatureProvider::class.java).forEach { provider -> ... }
```

kotlin-inject soporta todos los targets de Kotlin. El problema es exclusivamente
`java.util.ServiceLoader` en el wiring module. Los feature-impl modules (con
`@Component` de kotlin-inject) son 100% KMP-compatible.

### Ventajas

- **Codegen Kotlin.** kotlin-inject genera Kotlin, no Java.
- **Menos boilerplate.** Component = Module -- sin `@Module` separado.
- **Wiring inmutable.** ServiceLoader descubre features automaticamente.
- **Escala a 50+.** Cada feature es autocontenida.

### Desventajas

- **Init lento.** 97,197 ns por ServiceLoader + Resolver overhead.
- **ServiceLoader JVM-only.** No compila para iOS/macOS/WASM.
- **Errores parcialmente runtime.** Dependencias entre providers solo se validan en runtime.
- **Boilerplate de adaptacion.** Cada provider necesita un `object : XxxProvisions` wrapper.

---

## 2. Pattern L -- Koin + ServiceLoader (Eager Modules) (wiring-l)

### Concepto

`ServiceLoader` descubre `KoinFeatureProvider` instances. Todos los Koin modules
descubiertos se componen en un solo `koinApplication` en `init()`. Koin maneja
la resolucion via su standard `single{}` (lazy singleton por defecto de Koin).

Koin actua como BOTH resolver AND DI framework, a diferencia de H/I/J donde
el `Resolver` custom gestiona el lifecycle y el framework DI (Dagger, kotlin-inject,
puro) solo construye las features.

### Codigo del wiring

```kotlin
// sdk/wiring-l/src/main/kotlin/.../MultiModuleSdkL.kt

object MultiModuleSdkL : MultiModuleSdkApi {

    private var _koinApp: KoinApplication? = null
    private var _initialized = false
    private var _tracker: CreationTracker? = null
    private var _logger: SdkLogger = AndroidSdkLogger()

    override val builtProvisionCount: Int get() = _tracker?.count ?: 0

    override fun init(context: Context, config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdkL already initialized." }

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

        // Discover AND load ALL modules eagerly
        val providers = ServiceLoader.load(KoinFeatureProvider::class.java).toList()
        val featureModules = providers.map { it.module() }
        _koinApp = koinApplication {
            modules(listOf(foundation) + featureModules)
        }

        _initialized = true
    }

    override fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdkL not initialized." }
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

### Diferencia clave con M

En L, TODOS los Koin modules se cargan en `init()`:
```kotlin
val featureModules = providers.map { it.module() }
_koinApp = koinApplication { modules(listOf(foundation) + featureModules) }
```

En M, solo se carga el foundation module en `init()`. Los feature modules se
cargan on-demand via `koin.loadModules()` la primera vez que se resuelve un
servicio de esa feature.

### Porque es Partial KMP

Misma razon que J: `java.util.ServiceLoader` en la linea de discovery. Koin es
100% KMP-compatible. Reemplazando ServiceLoader por sweet-spi, se convierte en
Pattern N.

### Ventajas

- **Koin familiar.** `single{}`, `koinApplication{}` -- API conocida.
- **Wiring inmutable.** ServiceLoader descubre providers automaticamente.
- **Escala a 50+.** Cada feature expone un `KoinFeatureProvider`.
- **Lazy singletons.** Koin `single{}` crea el objeto la primera vez que se resuelve.

### Desventajas

- **Init lento.** 154,403 ns -- ServiceLoader + registrar todos los modules.
- **Resolve lento.** 5,664 ns por lookup Koin (vs ~300 ns en compile-time DI).
- **Re-init lento.** 1.1M ns -- destruir y recrear toda la infraestructura Koin.
- **ServiceLoader JVM-only.** No compila fuera de JVM.
- **No tiene thread-safe shutdown.** `_koinApp?.close()` no esta sincronizado.

---

## 3. Pattern M -- Koin + ServiceLoader (Lazy loadModules) (wiring-m)

### Concepto

Misma discovery que L (ServiceLoader + `KoinFeatureProvider`), pero con una
diferencia fundamental: solo el foundation module se carga en `init()`. Los feature
modules se cargan **on-demand** via `koin.loadModules()` la primera vez que se
resuelve un servicio de esa feature.

Esto implementa "true lazy" a nivel de modulos Koin: no solo los singletons
son lazy (como en L), sino que los modules mismos no se registran hasta que
se necesitan. Una cascada recursiva `ensureLoaded()` garantiza que las
dependencias se cargan en orden correcto.

### Codigo del wiring

```kotlin
// sdk/wiring-m/src/main/kotlin/.../MultiModuleSdkM.kt

object MultiModuleSdkM : MultiModuleSdkApi {

    private var _koinApp: KoinApplication? = null
    private var _initialized = false
    private var _providers = emptyList<KoinFeatureProvider>()
    private val _serviceToProvider = mutableMapOf<Class<*>, KoinFeatureProvider>()
    private val _loadedProviders: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val loadLock = Any()
    private var _tracker: CreationTracker? = null

    override val builtProvisionCount: Int get() = _loadedProviders.size

    override fun init(context: Context, config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdkM already initialized." }

        // Discover (but don't load) feature providers
        _providers = ServiceLoader.load(KoinFeatureProvider::class.java).toList()
        _serviceToProvider.clear()
        for (provider in _providers) {
            for (svc in provider.services) {
                _serviceToProvider[svc] = provider
            }
        }

        // Only foundation loaded at init
        _koinApp = koinApplication { modules(foundation) }
        _loadedProviders.clear()
        _initialized = true
    }

    override fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdkM not initialized." }
        ensureLoaded(clazz)  // cascade load on demand
        return _koinApp!!.koin.get(clazz.kotlin as KClass<Any>) as T
    }

    private fun ensureLoaded(serviceClass: Class<*>) {
        val provider = _serviceToProvider[serviceClass] ?: return
        if (provider.featureName in _loadedProviders) return // fast path
        synchronized(loadLock) {
            if (provider.featureName in _loadedProviders) return
            // Cascade: load required dependencies first
            for (requiredService in provider.requiredServices) {
                ensureLoaded(requiredService)
            }
            _koinApp!!.koin.loadModules(listOf(provider.module()))
            _loadedProviders.add(provider.featureName)
        }
    }

    override fun shutdown() {
        if (!_initialized) return
        synchronized(loadLock) {
            _koinApp?.close()
            _koinApp = null
            _loadedProviders.clear()
            _serviceToProvider.clear()
            _providers = emptyList()
            _tracker?.clear()
            _tracker = null
            _initialized = false
        }
    }
}
```

### Diferencia clave con L: init-time vs resolve-time loading

```
Pattern L (eager):
  init() --> discover ALL providers --> load ALL modules --> ready
  get<T>() --> koin.get() --> cache hit (module ya registrado)

Pattern M (lazy loadModules):
  init() --> discover ALL providers --> load ONLY foundation --> ready
  get<T>() --> ensureLoaded(T) --> cascade load modules --> koin.get()
```

M difiere la carga de modules pero NO difiere el discovery. ServiceLoader se
ejecuta completamente en `init()` en ambos patrones.

### Performance

M es consistentemente el **peor performer** de los 3 patrones partial KMP:

| Operacion | J | L | M |
|-----------|--:|--:|--:|
| Init Cold (ns) | 97,197 | 154,403 | 164,353 |
| Resolve First (ns) | 202 | 5,664 | 6,160 |
| Lazy noDeps (ns) | 1,493 | 5,473 | 13,784 |
| Lazy cascade (ns) | 4,866 | 24,611 | 48,334 |
| Re-Init (ns) | 371,000 | 1.1M | 1.2M |

**Lazy noDeps** es donde M deberia brillar (true lazy), pero 13,784 ns es
**9.2x mas lento que J** (1,493 ns). El overhead de `koin.loadModules()` en
runtime es mayor que el beneficio de no haber cargado el module en init.

**Lazy cascade** amplifica el problema: 48,334 ns implica resolver la cascada
`ensureLoaded()` recursiva + `loadModules()` por cada provider en la cadena.

### Porque es Partial KMP

Misma razon que J y L: `java.util.ServiceLoader`. Koin es 100% KMP-compatible.

### Ventajas

- **True lazy modules.** Features no accedidas nunca cargan su module Koin.
- **Koin familiar.** Misma API que L.
- **Thread-safe shutdown.** `synchronized(loadLock)` protege el cleanup.
- **Wiring inmutable.** ServiceLoader descubre features.

### Desventajas

- **Peor performer overall.** Lazy cascade 48,334 ns -- 2x mas lento que L.
- **`loadModules()` overhead.** Cargar un module en runtime es caro en Koin.
- **Re-init el mas lento.** 1.2M ns -- destruir y recrear toda la infraestructura.
- **ServiceLoader JVM-only.** No compila fuera de JVM.
- **Complejidad adicional.** `ensureLoaded()` recursivo + `_loadedProviders` tracking.

---

## 4. Tabla Comparativa Partial KMP

### Caracteristicas

| Criterio | J | L | M |
|----------|---|---|---|
| **Framework DI** | kotlin-inject | Koin | Koin |
| **Discovery** | ServiceLoader | ServiceLoader | ServiceLoader |
| **Module loading** | Eager (init) | Eager (init) | Lazy (on-demand) |
| **Singleton strategy** | Eager (build) | Lazy (Koin single) | Lazy (Koin single) |
| **Codegen** | KSP -> Kotlin | Ninguno | Ninguno |
| **Compile-time safe** | Per-Component | No (Koin) | No (Koin) |
| **Thread-safe shutdown** | Si (CHM + lock) | No | Si (synchronized) |

### Benchmarks resumen (Samsung Galaxy S22 Ultra)

| Operacion | J | L | M |
|-----------|--:|--:|--:|
| Init Cold (ns) | 97,197 | 154,403 | 164,353 |
| Resolve First (ns) | 202 | 5,664 | 6,160 |
| Lazy noDeps (ns) | 1,493 | 5,473 | 13,784 |
| Lazy cascade (ns) | 4,866 | 24,611 | 48,334 |
| CrossFeature (ns) | 1.2M | 2.1M | 1.8M |
| E2E Startup (ns) | 1.6M | 2.3M | 1.9M |
| Init/Shutdown (ns) | 91,244 | 134,268 | 125,626 |
| Concurrent (ns) | 589K | 761K | 725K |
| Resolve All (ns) | 216 | 6,244 | 7,920 |
| Re-Init (ns) | 371K | 1.1M | 1.2M |
| Incremental (ns) | 97,711 | 172,609 | 165,162 |

---

## 5. Como Hacer Estos Patrones Full KMP

### El cambio: ServiceLoader por sweet-spi

El unico cambio necesario es reemplazar el mecanismo de discovery. Los feature-impl
modules no cambian.

#### Para J (kotlin-inject + ServiceLoader):

```kotlin
// Antes (Partial KMP):
import java.util.ServiceLoader
ServiceLoader.load(KIFeatureProvider::class.java).forEach { ... }

// Despues (Full KMP):
import dev.whyoleg.sweetspi.ServiceLoader
ServiceLoader.load<KIFeatureProvider>().forEach { ... }
```

Nota: kotlin-inject + sweet-spi no tiene un patron dedicado en este proyecto
porque kotlin-inject-anvil (Pattern P) ya resuelve el problema de forma mas
elegante con `@MergeComponent`.

#### Para L y M (Koin + ServiceLoader):

```kotlin
// Antes (Partial KMP):
import java.util.ServiceLoader
val providers = ServiceLoader.load(KoinFeatureProvider::class.java).toList()

// Despues (Full KMP) -- esto es exactamente Pattern N:
import dev.whyoleg.sweetspi.ServiceLoader
val providers = ServiceLoader.load<KoinFeatureProvider>()
```

### Dependencia Gradle

```kotlin
// build.gradle.kts
plugins {
    id("dev.whyoleg.sweetspi") version "0.3.0"
}

dependencies {
    implementation("dev.whyoleg.sweetspi:sweetspi-runtime:0.3.0")
}
```

### Anotaciones en los providers

```kotlin
// En cada KoinFeatureProvider impl:
@dev.whyoleg.sweetspi.Service
class EncKoinFeatureProvider : KoinFeatureProvider { ... }

// En la interfaz base:
@dev.whyoleg.sweetspi.ServiceProvider
interface KoinFeatureProvider { ... }
```

sweet-spi KSP genera los archivos `META-INF/services/` en JVM y el codigo de
registro `@EagerInitialization` en Native/WASM automaticamente.
