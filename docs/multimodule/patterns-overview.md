# Patrones Multi-Modulo de Inyeccion de Dependencias

Guia completa de los 7 patrones multi-modulo implementados en este proyecto:
D, E2, G, H, I, J y K. Todos comparten la misma arquitectura base de provision
interfaces y contratos en `di-contracts/`, pero difieren en como se conectan
(wiring) los Components con el facade publico del SDK.

Para patrones monoliticos (A, B, C, Koin, Hybrid), ver `docs/monolithic/`.

---

## 1. Introduccion

### Que son los patrones multi-modulo

En un SDK Android con N features (encryption, auth, storage, analytics, sync),
los consumidores necesitan:

1. Elegir que features activar
2. No ver clases de implementacion
3. No pagar tamano binario por features que no usan

Los patrones multi-modulo resuelven estos tres requisitos separando cada feature
en modulos Gradle independientes (`feature-xxx-api` + `feature-xxx-impl`) y
conectandolos mediante un modulo de wiring que el consumidor importa.

### Provision Interfaces

El concepto clave compartido por todos los patrones es la **provision interface**:
una interfaz Kotlin plana que declara los servicios que una feature expone.

```kotlin
// di-contracts/src/main/kotlin/.../Provisions.kt

interface CoreProvisions {
    fun config(): SdkConfig
}

interface EncProvisions {
    fun encryption(): EncryptionApi
    fun hash(): HashApi
}

interface AuthProvisions {
    fun auth(): AuthApi
}

interface StorProvisions {
    fun storage(): StorageApi
}

interface AnaProvisions {
    fun analytics(): AnalyticsApi
}

interface SynProvisions {
    fun sync(): SyncApi
}
```

Cada `feature-xxx-impl` implementa su provision interface (generalmente via un
`@Component` de Dagger que extiende la interfaz). Los modulos de feature
**nunca** importan los `@Component` de otras features -- solo dependen de
provision interfaces definidas en `di-contracts/`.

### di-contracts

El modulo `di-contracts/` contiene:

- **Provision interfaces** (`CoreProvisions`, `EncProvisions`, etc.)
- **Scopes personalizados** (`@EncScope`, `@AuthScope`, etc.)
- **Infraestructura de registro** (`AutoProvisionRegistry`, `ProvisionRegistry`)
- **Contratos de auto-descubrimiento** (`FeatureProvider`, `PureFeatureProvider`, `KIFeatureProvider`, `Resolver`)

Este modulo es la unica dependencia compartida entre todos los feature-impl.

---

## 2. Arquitectura Comun

### Grafo de dependencias Gradle

```
app
 └── implementation(:sdk:wiring-X)          <-- el consumidor importa esto
      ├── api(:sdk:api)                     <-- interfaces publicas (EncryptionApi, AuthApi, ...)
      ├── implementation(:features:feature-core-impl)
      ├── implementation(:features:feature-enc-impl)
      ├── implementation(:features:feature-auth-impl)
      ├── implementation(:features:feature-stor-impl)
      ├── implementation(:features:feature-ana-impl)
      └── implementation(:features:feature-syn-impl)

feature-auth-impl
 ├── api(:di-contracts)              <-- CoreProvisions, EncProvisions (contratos)
 ├── implementation(:sdk:impl-common)      <-- DefaultAuthService
 X   NO depende de :feature-enc-impl ni :feature-core-impl
```

### Separacion api/impl por feature

Cada feature sigue el patron api/impl:

- `feature-xxx-api`: interfaz publica (p.ej. `EncryptionApi`)
- `feature-xxx-impl`: implementacion interna (`DefaultEncryptionService`, `EncComponent`)

El modulo impl depende de `di-contracts` para obtener las provision interfaces
de sus dependencias. Por ejemplo, `feature-auth-impl` depende de `EncProvisions`
(de `di-contracts`), no de `feature-enc-impl`.

### Cadena de dependencias entre features

```
CoreComponent --> EncComponent --> AuthComponent
                               --> StorComponent
                                                --> SynComponent
                --> AnaComponent (independiente, solo depende de Core)
```

---

## 3. Pattern D -- Component Dependencies (sdk-wiring)

### Concepto

Cada feature tiene su `DaggerComponent` con `dependencies = [ProvisionInterface::class]`.
El wiring module importa todos los `DaggerXxxComponent` y los conecta manualmente
via funciones `ensure*()` con when-blocks.

### Codigo del wiring

```kotlin
// sdk/sdk-wiring/src/main/kotlin/.../MultiModuleSdk.kt

object MultiModuleSdk : MultiModuleSdkApi {

    private var _core: CoreProvisions? = null
    private var _enc: EncProvisions? = null
    private var _auth: AuthProvisions? = null
    private var _storage: StorProvisions? = null
    private var _analytics: AnaProvisions? = null
    private var _sync: SynProvisions? = null

    override fun init(context: Context, config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdk already initialized." }
        _core = DaggerCoreComponent.builder()
            .config(config)
            .build()
        _initialized = true
    }

    override fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdk not initialized." }
        val core = _core!!
        val result: Any = when (clazz) {
            EncryptionApi::class.java -> ensureEnc(core).encryption()
            HashApi::class.java -> ensureEnc(core).hash()
            AuthApi::class.java -> ensureAuth(core).auth()
            StorageApi::class.java -> ensureStor(core).storage()
            AnalyticsApi::class.java -> ensureAna(core).analytics()
            SyncApi::class.java -> ensureSyn(core).sync()
            SdkLogger::class.java -> _logger
            else -> error("Service ${clazz.simpleName} not available.")
        }
        return checkNotNull(clazz.cast(result)) { "Cast failed" }
    }

    // Lazy builders -- construyen dependencias en cascada
    private fun ensureEnc(core: CoreProvisions): EncProvisions =
        _enc ?: DaggerEncComponent.builder().core(core).logger(_logger)
            .build().also { _enc = it }

    private fun ensureAuth(core: CoreProvisions): AuthProvisions {
        val enc = ensureEnc(core)
        return _auth ?: DaggerAuthComponent.builder()
            .core(core).logger(_logger).enc(enc)
            .build().also { _auth = it }
    }

    private fun ensureSyn(core: CoreProvisions): SynProvisions {
        val enc = ensureEnc(core)
        val auth = ensureAuth(core)
        val stor = ensureStor(core)
        return _sync ?: DaggerSynComponent.builder()
            .core(core).logger(_logger).enc(enc).auth(auth).storage(stor)
            .build().also { _sync = it }
    }
}
```

### Codigo del Component (feature-impl)

```kotlin
// features/feature-auth-impl/.../AuthComponent.kt

@AuthScope
@Component(
    dependencies = [CoreProvisions::class, EncProvisions::class],
    modules = [AuthModule::class],
)
interface AuthComponent : AuthProvisions {
    override fun auth(): AuthApi

    @Component.Builder interface Builder {
        fun core(core: CoreProvisions): Builder
        @BindsInstance fun logger(logger: SdkLogger): Builder
        fun enc(enc: EncProvisions): Builder
        fun build(): AuthComponent
    }
}
```

### Ventajas

- **Cross-feature automatico.** `dependencies=[EncProvisions]` -- Dagger resuelve sin CoreApis.
- **Compile-time safe.** Parent faltante = error de compilacion.
- **Lazy init real.** `ensure*()` crea Components on-demand con cascada.
- **Sin God Object.** No hay interfaz CoreApis que crezca.

### Desventajas

- **Edicion central.** Nueva feature = editar when-blocks en el facade.
- **No escala a 50+.** El facade crece linealmente con cada feature.
- **JVM exclusivo.** Dagger no soporta KMP.

---

## 4. Pattern E2 -- Auto-Init Registry (wiring-e2)

### Concepto

Evolucion de E que elimina el `Feature` enum y el `getOrInitModule()`. El
consumidor solo necesita `init()` + `get<T>()`. Internamente, un
`AutoProvisionRegistry` cataloga las entries en init (HashMap puts baratos)
y construye Components on-demand via DFS recursivo cuando se llama `get<T>()`.

### Codigo del wiring

```kotlin
// sdk/wiring-e2/src/main/kotlin/.../MultiModuleSdkE2.kt

object MultiModuleSdkE2 : MultiModuleSdkApi {

    private val registry = AutoProvisionRegistry()

    override fun init(context: Context, config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdkE2 already initialized." }
        registry.installAll(allAutoEntries(config, _logger))
        _initialized = true
    }

    override fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdkE2 not initialized." }
        return registry.get(clazz)
    }

    override fun shutdown() {
        if (!_initialized) return
        registry.clear()
        _initialized = false
    }
}
```

### Codigo de las entries

```kotlin
// sdk/wiring-e2/src/main/kotlin/.../Entries.kt

internal fun encAutoEntry(logger: SdkLogger) = AutoProvisionEntry(
    provisionClass = EncProvisions::class.java,
    dependencies = setOf(CoreProvisions::class.java),
    serviceClasses = setOf(EncryptionApi::class.java, HashApi::class.java),
    build = { registry ->
        DaggerEncComponent.builder()
            .core(registry.provision(CoreProvisions::class.java))
            .logger(logger)
            .build()
    },
    services = { prov ->
        mapOf(
            EncryptionApi::class.java to prov.encryption(),
            HashApi::class.java to prov.hash(),
        )
    },
)

internal fun synAutoEntry(logger: SdkLogger) = AutoProvisionEntry(
    provisionClass = SynProvisions::class.java,
    dependencies = setOf(
        CoreProvisions::class.java,
        EncProvisions::class.java,
        AuthProvisions::class.java,
        StorProvisions::class.java,
    ),
    serviceClasses = setOf(SyncApi::class.java),
    build = { registry ->
        DaggerSynComponent.builder()
            .core(registry.provision(CoreProvisions::class.java))
            .logger(logger)
            .enc(registry.provision(EncProvisions::class.java))
            .auth(registry.provision(AuthProvisions::class.java))
            .storage(registry.provision(StorProvisions::class.java))
            .build()
    },
    services = { prov ->
        mapOf(SyncApi::class.java to prov.sync())
    },
)

internal fun allAutoEntries(config: SdkConfig, logger: SdkLogger) = listOf(
    coreAutoEntry(config, logger),
    encAutoEntry(logger),
    authAutoEntry(logger),
    storAutoEntry(logger),
    anaAutoEntry(logger),
    synAutoEntry(logger),
)
```

### AutoProvisionRegistry (infraestructura)

```kotlin
// di-contracts/src/main/kotlin/.../RegistryInfra.kt

class AutoProvisionRegistry {
    // Fase 1: Catalogo (instalado pero no construido)
    private val catalog = HashMap<Class<*>, AutoProvisionEntry<*>>()
    private val serviceIndex = HashMap<Class<*>, Class<*>>()

    // Fase 2: Estado construido (poblado on-demand)
    internal val provisions = java.util.concurrent.ConcurrentHashMap<Class<*>, Any>()
    internal val services = java.util.concurrent.ConcurrentHashMap<Class<*>, Any>()

    fun <T : Any> get(clazz: Class<T>): T {
        services[clazz]?.let { return clazz.cast(it) }
        val provisionClass = serviceIndex[clazz]
            ?: error("No entry provides ${clazz.simpleName}.")
        ensureBuilt(provisionClass)  // DFS recursivo
        return clazz.cast(services[clazz])
    }

    private fun ensureBuilt(provisionClass: Class<*>) {
        if (provisions.containsKey(provisionClass)) return
        val entry = catalog[provisionClass]!!
        for (dep in entry.dependencies) {
            ensureBuilt(dep)  // recursion: construye deps primero
        }
        val provision = entry.build(this)
        provisions[entry.provisionClass] = provision
        services.putAll(entry.services(provision))
    }
}
```

### API del consumidor

```kotlin
MultiModuleSdkE2.init(context, SdkConfig(debug = true))
val sync = MultiModuleSdkE2.get<SyncApi>()        // auto-inits Core->Enc->Auth->Stor->Syn
val enc  = MultiModuleSdkE2.get<EncryptionApi>()   // ya construido -- cache hit
MultiModuleSdkE2.shutdown()
```

### Ventajas

- **API minima.** `init()` + `get<T>()` -- nada mas.
- **Facade casi inmutable.** Anadir modulo = 1 linea en `allEntries()`. Sin enums. Sin when.
- **Lazy por naturaleza.** Solo construye lo que se pide (y sus deps via DFS).
- **Cross-feature automatico.** Misma jerarquia `dependencies=[...]` que D.

### Desventajas

- **Sin control granular.** El consumidor no puede excluir features por politica.
- **Registry overhead.** `get<T>()` primera vez = DFS + builds. Post-init = HashMap (~25 ns).
- **JVM exclusivo.** Dagger no soporta KMP.
- **Entries verbose.** Cada `AutoProvisionEntry` duplica dependencias (~10 lineas por feature).

---

## 5. Pattern G -- Factory Functions (wiring-g)

### Concepto

Cada feature-impl expone una funcion factory publica (`buildXxxProvisions(deps)`)
que encapsula la creacion del `DaggerXxxComponent`. El Component queda `internal`
-- el wiring module nunca lo importa directamente. Mismo patron lazy `ensure*()`
que D, pero llamando factory functions en vez de builders Dagger.

### Codigo del wiring

```kotlin
// sdk/wiring-g/src/main/kotlin/.../MultiModuleSdkG.kt

object MultiModuleSdkG : MultiModuleSdkApi {

    override fun init(context: Context, config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdkG already initialized." }
        _core = buildCoreProvisions(config)
        _initialized = true
    }

    override fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdkG not initialized." }
        val core = _core!!
        val result: Any = when (clazz) {
            EncryptionApi::class.java -> ensureEnc(core).encryption()
            HashApi::class.java -> ensureEnc(core).hash()
            AuthApi::class.java -> ensureAuth(core).auth()
            StorageApi::class.java -> ensureStor(core).storage()
            AnalyticsApi::class.java -> ensureAna(core).analytics()
            SyncApi::class.java -> ensureSyn(core).sync()
            SdkLogger::class.java -> _logger
            else -> error("Service ${clazz.simpleName} not available.")
        }
        return checkNotNull(clazz.cast(result)) { "Cast failed" }
    }

    // Lazy builders -- llaman factory functions, NO DaggerXxxComponent
    private fun ensureEnc(core: CoreProvisions): EncProvisions =
        _enc ?: buildEncProvisions(core, _logger).also { _enc = it }

    private fun ensureAuth(core: CoreProvisions): AuthProvisions {
        val enc = ensureEnc(core)
        return _auth ?: buildAuthProvisions(core, _logger, enc).also { _auth = it }
    }

    private fun ensureSyn(core: CoreProvisions): SynProvisions {
        val enc = ensureEnc(core)
        val auth = ensureAuth(core)
        val stor = ensureStor(core)
        return _sync ?: buildSynProvisions(core, _logger, enc, auth, stor)
            .also { _sync = it }
    }
}
```

### Codigo de la factory function (feature-impl)

```kotlin
// features/feature-enc-impl/.../EncComponent.kt

/** Factory: builds EncProvisions without exposing DaggerEncComponent. */
fun buildEncProvisions(core: CoreProvisions, logger: SdkLogger): EncProvisions =
    DaggerEncComponent.builder().core(core).logger(logger).build()

@EncScope
@Component(
    dependencies = [CoreProvisions::class],
    modules = [EncModule::class],
)
interface EncComponent : EncProvisions {
    // DaggerEncComponent queda internal al modulo
    override fun encryption(): EncryptionApi
    override fun hash(): HashApi
    // ...
}
```

### Diferencia clave con D

En D, el wiring importa `DaggerEncComponent`, `DaggerAuthComponent`, etc.
directamente. En G, el wiring solo importa `buildEncProvisions()`,
`buildAuthProvisions()`, etc. -- los Components quedan `internal`.

Esto mejora el encapsulamiento pero no cambia la escalabilidad: el wiring
sigue conociendo el orden de dependencias y crece linealmente.

### Ventajas

- **Components `internal`.** El wiring nunca importa `DaggerXxxComponent`.
- **Compile-time safe.** Misma validacion que D.
- **Lazy init real.** Mismo patron `ensure*()`.
- **Menos imports.** Una factory function por feature vs un builder completo.

### Desventajas

- **Edicion central.** Misma limitacion que D -- when-blocks crecen.
- **No escala a 50+.** `ensure*()` crece linealmente.
- **JVM exclusivo.** Dagger no soporta KMP.

---

## 6. Pattern H -- ServiceLoader + FeatureProvider + Resolver DFS (wiring-h)

### Concepto

Cada feature-impl declara un `FeatureProvider` (~8 lineas) que sabe construirse
a si mismo y declarar sus servicios. Las dependencias son **implicitas** -- lo que
el provider pide al `Resolver` dentro de `build()` se construye automaticamente
via DFS. El wiring module descubre providers via `ServiceLoader` y es completamente
inmutable: no se edita al anadir features.

### Codigo del wiring

```kotlin
// sdk/wiring-h/src/main/kotlin/.../MultiModuleSdkH.kt

object MultiModuleSdkH : MultiModuleSdkApi {

    private val resolver = Resolver()

    override fun init(context: Context, config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdkH already initialized." }
        resolver.init(config)

        ServiceLoader.load(FeatureProvider::class.java).forEach { provider ->
            resolver.register(provider)
        }

        _initialized = true
    }

    override fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdkH not initialized." }
        return resolver.get(clazz)
    }

    override fun shutdown() {
        if (!_initialized) return
        resolver.clear()
        _initialized = false
    }
}
```

### Codigo del FeatureProvider (feature-impl)

```kotlin
// features/feature-enc-impl/.../EncProvider.kt

class EncProvider : FeatureProvider<EncProvisions>(EncProvisions::class.java) {

    override val services: Map<Class<*>, (EncProvisions) -> Any> = mapOf(
        EncryptionApi::class.java to EncProvisions::encryption,
        HashApi::class.java to EncProvisions::hash,
    )

    override fun build(resolver: Resolver): EncProvisions =
        buildEncProvisions(
            resolver.provision(CoreProvisions::class.java),
            resolver.logger,
        )
}
```

### Resolver (infraestructura)

```kotlin
// di-contracts/src/main/kotlin/.../FeatureProvider.kt

abstract class FeatureProvider<P : Any>(val provisionClass: Class<P>) {
    abstract val services: Map<Class<*>, (P) -> Any>
    abstract fun build(resolver: Resolver): P
}

class Resolver {
    private val providers = HashMap<Class<*>, FeatureProvider<*>>()
    private val serviceIndex = HashMap<Class<*>, Class<*>>()
    private val provisions = java.util.concurrent.ConcurrentHashMap<Class<*>, Any>()
    private val resolvedServices = java.util.concurrent.ConcurrentHashMap<Class<*>, Any>()

    fun register(provider: FeatureProvider<*>) {
        providers[provider.provisionClass] = provider
        for (serviceClass in provider.services.keys) {
            serviceIndex[serviceClass] = provider.provisionClass
        }
    }

    fun <T : Any> get(clazz: Class<T>): T {
        resolvedServices[clazz]?.let { return clazz.cast(it) }
        val provisionClass = serviceIndex[clazz]
            ?: error("No provider for ${clazz.simpleName}")
        ensureBuilt(provisionClass)
        return clazz.cast(resolvedServices[clazz])
    }

    private fun ensureBuilt(provisionClass: Class<*>) {
        if (provisions.containsKey(provisionClass)) return
        val provider = providers[provisionClass]!!
        val provision = provider.buildUntyped(this)  // DFS: build() llama resolver.provision()
        provisions[provisionClass] = provision
        for (serviceClass in provider.services.keys) {
            resolvedServices[serviceClass] = provider.extractService(provision, serviceClass)
        }
    }
}
```

### Ventajas

- **Wiring inmutable.** Zero edicion al anadir features -- ServiceLoader las descubre.
- **Escala a 50+.** Cada feature es autocontenida (~8 lineas de provider).
- **Dependencies implicitas.** Sin `dependencies = setOf(...)` explicito.
- **Cross-feature automatico.** `resolver.provision(EncProvisions::class.java)` dentro de `build()`.

### Desventajas

- **Overhead de init.** ServiceLoader + ConcurrentHashMap + registro = ~61,000 ns (vs ~800 ns en D/G).
- **Errores runtime.** Dependencia Gradle ausente = crash en init, no error de compilacion.
- **JVM exclusivo.** `ServiceLoader` requiere META-INF/services.

---

## 7. Pattern I -- ServiceLoader + PureFeatureProvider (zero DI framework)

### Concepto

Misma arquitectura que H (ServiceLoader + FeatureProvider + Resolver DFS), pero
las features se construyen **sin ningun framework de DI**. Cada `PureFeatureProvider`
crea los servicios directamente via constructores Kotlin. Zero KSP, zero codegen,
zero dependencia en Dagger ni kotlin-inject.

`PureFeatureProvider` es un simple marker que extiende `FeatureProvider`, lo que
permite al `Resolver` funcionar de forma identica.

### Codigo del wiring

```kotlin
// sdk/wiring-i/src/main/kotlin/.../MultiModuleSdkI.kt

object MultiModuleSdkI : MultiModuleSdkApi {

    private val resolver = Resolver()

    override fun init(context: Context, config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdkI already initialized." }
        resolver.init(config)

        ServiceLoader.load(PureFeatureProvider::class.java).forEach { provider ->
            resolver.register(provider)
        }

        _initialized = true
    }

    override fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdkI not initialized." }
        return resolver.get(clazz)
    }

    override fun shutdown() {
        if (!_initialized) return
        resolver.clear()
        _initialized = false
    }
}
```

### Codigo del PureFeatureProvider (feature-impl)

```kotlin
// features/feature-enc-impl/.../EncPureProvider.kt

class EncPureProvider : PureFeatureProvider<EncProvisions>(EncProvisions::class.java) {
    override val services: Map<Class<*>, (EncProvisions) -> Any> = mapOf(
        EncryptionApi::class.java to EncProvisions::encryption,
        HashApi::class.java to EncProvisions::hash,
    )
    override fun build(resolver: Resolver): EncProvisions {
        val logger = resolver.logger
        val enc = DefaultEncryptionService(logger)
        val hash = DefaultHashService()
        return object : EncProvisions {
            override fun encryption() = enc
            override fun hash() = hash
        }
    }
}
```

Ejemplo con dependencias cruzadas complejas (Sync depende de 4 features):

```kotlin
// features/feature-syn-impl/.../SynPureProvider.kt

class SynPureProvider : PureFeatureProvider<SynProvisions>(SynProvisions::class.java) {
    override val services: Map<Class<*>, (SynProvisions) -> Any> = mapOf(
        SyncApi::class.java to SynProvisions::sync,
    )
    override fun build(resolver: Resolver): SynProvisions {
        val core = resolver.provision(CoreProvisions::class.java)
        val logger = resolver.logger
        val enc = resolver.provision(EncProvisions::class.java)
        val auth = resolver.provision(AuthProvisions::class.java)
        val stor = resolver.provision(StorProvisions::class.java)
        val syn = DefaultSyncService(auth.auth(), stor.storage(), enc.encryption(), logger)
        return object : SynProvisions {
            override fun sync() = syn
        }
    }
}
```

### Contrato PureFeatureProvider

```kotlin
// di-contracts/src/main/kotlin/.../PureFeatureProvider.kt

abstract class PureFeatureProvider<P : Any>(
    provisionClass: Class<P>
) : FeatureProvider<P>(provisionClass)
```

Es un marker puro. El `Resolver` lo trata exactamente igual que un `FeatureProvider`.
La separacion existe para que `ServiceLoader` descubra solo los providers de
Pattern I (via `META-INF/services/...PureFeatureProvider`) sin mezclarlos con los
de Pattern H.

### Ventajas

- **Zero framework DI.** Sin Dagger, sin kotlin-inject, sin Koin. Solo Kotlin puro.
- **Zero KSP / codegen.** Compilacion mas rapida: no hay procesamiento de anotaciones.
- **Wiring inmutable.** Identico a H -- ServiceLoader descubre features.
- **Escala a 50+.** Cada feature es autocontenida.
- **Maximo control.** El desarrollador ve exactamente como se construye cada servicio.

### Desventajas

- **Sin validacion en compilacion.** Errores de wiring solo aparecen en runtime.
- **Mas boilerplate por feature.** Sin `@Inject` ni `@Provides`, cada dependencia
  se pasa manualmente como parametro de constructor.
- **Overhead de init.** Mismo overhead de ServiceLoader que H (~62,000 ns).
- **Sin singleton automatico.** El developer debe gestionar manualmente que los
  servicios no se creen multiples veces (el Resolver lo maneja a nivel de provision,
  pero dentro de `build()` es responsabilidad del provider).

---

## 8. Pattern J -- ServiceLoader + KIFeatureProvider (kotlin-inject)

### Concepto

Misma arquitectura que H (ServiceLoader + FeatureProvider + Resolver DFS), pero
las features usan **kotlin-inject** en lugar de Dagger para la inyeccion de
dependencias interna. `KIFeatureProvider` es un marker que extiende `FeatureProvider`.

kotlin-inject genera codigo Kotlin (no Java) via KSP, con menos boilerplate que
Dagger: el Component actua tambien como Module (no se necesita clase `@Module`
separada).

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

### Codigo del KIFeatureProvider + kotlin-inject Component

```kotlin
// features/feature-enc-impl/.../EncKIProvider.kt

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

Ejemplo con dependencias cruzadas complejas (Sync):

```kotlin
// features/feature-syn-impl/.../SynKIProvider.kt

@Component
abstract class KISynComponent(
    @get:Provides val auth: AuthApi,
    @get:Provides val storage: StorageApi,
    @get:Provides val encryption: EncryptionApi,
    @get:Provides val logger: SdkLogger,
) {
    abstract val sync: SyncApi

    @Provides fun syncApi(): SyncApi =
        DefaultSyncService(auth, storage, encryption, logger)
}

class SynKIProvider : KIFeatureProvider<SynProvisions>(SynProvisions::class.java) {
    override val services: Map<Class<*>, (SynProvisions) -> Any> = mapOf(
        SyncApi::class.java to SynProvisions::sync,
    )
    override fun build(resolver: Resolver): SynProvisions {
        val core = resolver.provision(CoreProvisions::class.java)
        val enc = resolver.provision(EncProvisions::class.java)
        val auth = resolver.provision(AuthProvisions::class.java)
        val stor = resolver.provision(StorProvisions::class.java)
        val component = KISynComponent::class.create(
            auth = auth.auth(),
            storage = stor.storage(),
            encryption = enc.encryption(),
            logger = resolver.logger,
        )
        val sync = component.sync
        return object : SynProvisions {
            override fun sync() = sync
        }
    }
}
```

### Contrato KIFeatureProvider

```kotlin
// di-contracts/src/main/kotlin/.../KIFeatureProvider.kt

abstract class KIFeatureProvider<P : Any>(
    provisionClass: Class<P>
) : FeatureProvider<P>(provisionClass)
```

Marker puro, igual que `PureFeatureProvider`. Permite que `ServiceLoader` descubra
solo los providers de Pattern J sin mezclar con H o I.

### Ventajas

- **Wiring inmutable.** Identico a H -- ServiceLoader descubre features.
- **Codegen Kotlin.** kotlin-inject genera Kotlin, no Java. Mejor interoperabilidad KMP.
- **Menos boilerplate.** Component = Module -- sin clase `@Module` separada.
- **Escala a 50+.** Cada feature es autocontenida.
- **Potencial KMP.** kotlin-inject soporta Kotlin Multiplatform (a diferencia de Dagger).

### Desventajas

- **Overhead de init.** ServiceLoader + ConcurrentHashMap + registro = ~60,000 ns.
- **Errores parcialmente runtime.** kotlin-inject valida en compilacion dentro del modulo,
  pero dependencias entre providers solo se validan en runtime.
- **Ecosistema menor.** kotlin-inject tiene menos adopcion y documentacion que Dagger.
- **Boilerplate de adaptacion.** Cada provider crea un `object : XxxProvisions` intermediario
  para adaptarse a la provision interface (necesario porque kotlin-inject Components
  no extienden directamente las provision interfaces como Dagger).

---

## 9. Pattern K -- AndroidManifest Metadata Discovery (wiring-k)

### Concepto

Misma arquitectura que H (FeatureProviders + Resolver DFS), pero el mecanismo
de descubrimiento cambia: en vez de `ServiceLoader` + `META-INF/services/`,
las features se registran como entradas `<meta-data>` en un `<service>` dummy
del AndroidManifest.xml. El merger de manifiestos de Gradle/AGP agrega las
entradas de cada feature-impl automaticamente en build time.

El wiring module lee las entradas via `PackageManager.getServiceInfo()` e
instancia cada `FeatureProvider` por reflexion. Requiere `Context` en
`init(context, config)` -- a diferencia de H/I/J que solo requieren `config`.

### Codigo de ComponentDiscovery

```kotlin
// sdk/wiring-k/src/main/kotlin/.../ComponentDiscovery.kt

/**
 * Dummy Service -- existe solo para que los feature-impl puedan adjuntar
 * entradas <meta-data> en su propio AndroidManifest.xml.
 * Nunca se inicia ni se vincula.
 */
class ComponentDiscoveryService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}

private const val META_PREFIX = "com.grinwich.sdk.providers:"

/**
 * Firebase-style discovery: lee <meta-data> de [ComponentDiscoveryService]
 * a traves de todos los manifiestos mergeados e instancia clases [FeatureProvider].
 */
object ComponentDiscovery {

    fun discover(context: Context): List<FeatureProvider<*>> {
        val component = ComponentName(context, ComponentDiscoveryService::class.java)
        val serviceInfo = context.packageManager.getServiceInfo(
            component,
            PackageManager.GET_META_DATA,
        )
        val bundle = serviceInfo.metaData ?: return emptyList()

        return bundle.keySet()
            .filter { it.startsWith(META_PREFIX) }
            .map { key ->
                val className = key.removePrefix(META_PREFIX)
                @Suppress("UNCHECKED_CAST")
                Class.forName(className)
                    .getDeclaredConstructor()
                    .newInstance() as FeatureProvider<*>
            }
    }
}
```

### Codigo del wiring

```kotlin
// sdk/wiring-k/src/main/kotlin/.../MultiModuleSdkK.kt

object MultiModuleSdkK : MultiModuleSdkApi {

    private val resolver = Resolver()
    private var _initialized = false

    override val isInitialized: Boolean get() = _initialized
    override val builtProvisionCount: Int get() = resolver.builtProvisionCount

    override fun init(context: Context, config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdkK already initialized. Call shutdown() first." }
        resolver.init(config)

        ComponentDiscovery.discover(context).forEach { provider ->
            resolver.register(provider)
        }

        _initialized = true
    }

    override fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdkK not initialized." }
        return resolver.get(clazz)
    }

    inline fun <reified T : Any> get(): T = get(T::class.java)

    override fun shutdown() {
        if (!_initialized) return
        resolver.clear()
        _initialized = false
    }
}
```

### Diferencia clave con H

En H, el descubrimiento usa `ServiceLoader.load(FeatureProvider::class.java)`,
que escanea `META-INF/services/` en el classpath. En K, el descubrimiento usa
`PackageManager.getServiceInfo()` para leer `<meta-data>` del AndroidManifest.xml
mergeado. Ambos patrones reutilizan los mismos `FeatureProvider` y el mismo
`Resolver` -- solo cambia el mecanismo de discovery.

La implicacion principal es que K requiere `Context` en `init()`, lo que lo hace
exclusivamente Android. H tambien es JVM-only (por ServiceLoader), pero no
requiere Android `Context`.

### Ventajas

- **Wiring inmutable.** Zero edicion al anadir features -- el manifest merger las agrega.
- **Escala a 50+.** Cada feature es autocontenida (1 meta-data entry + provider).
- **Reutiliza FeatureProviders.** Los mismos providers de Pattern H funcionan sin cambios.
- **Firebase-style.** Patron familiar en el ecosistema Android (ContentProvider init).
- **Sin META-INF/services.** Alternativa para entornos donde ServiceLoader es problematico.

### Desventajas

- **Requiere Context.** `init(context, config)` -- necesita Android Context para leer el manifest.
- **Android exclusivo.** PackageManager no existe fuera de Android.
- **Overhead de init.** PackageManager + reflexion + ConcurrentHashMap = ~141,000 ns.
- **Errores runtime.** Meta-data con typo o clase inexistente = crash en init.
- **Service dummy.** Requiere declarar un `<service>` vacio en el manifest.

---

## 10. Tabla Comparativa

### Caracteristicas generales

| Criterio | D | E2 | G | H | I | J | K |
|----------|---|----|----|---|---|---|---|
| **Framework DI** | Dagger | Dagger | Dagger | Dagger + ServiceLoader | Ninguno | kotlin-inject | Dagger + Manifest |
| **Wiring lines** | ~145 | ~100 | ~95 | ~50 | ~50 | ~50 | ~50 |
| **Wiring inmutable** | No | No | No | Si | Si | Si | Si |
| **Auto-registro** | No | No | No | Si (ServiceLoader) | Si (ServiceLoader) | Si (ServiceLoader) | Si (Manifest meta-data) |
| **Escala 50+** | No | Si | No | Si | Si | Si | Si |
| **Compile-time safe** | Completo | Completo | Completo | Per-Component | No (runtime) | Per-Component | Per-Component |
| **Components internal** | No | No (via lambda) | Si | Si (factory) | N/A | Si (KI) | Si (factory) |
| **Cross-feature** | Auto | Auto | Auto | Auto (DFS) | Auto (DFS) | Auto (DFS) | Auto (DFS) |
| **KMP potencial** | No | No | No | No (ServiceLoader JVM) | No (ServiceLoader JVM) | Parcial (KI es KMP, SL no) | No (Android-only) |
| **Codegen** | KSP -> Java | KSP -> Java | KSP -> Java | KSP -> Java | Ninguno | KSP -> Kotlin | KSP -> Java |

### Lineas de wiring por variante

| Variante | Ficheros wiring | Lineas wiring | Mecanismo de escala |
|----------|----------------|---------------|---------------------|
| D (sdk-wiring) | 1 | ~145 | when-blocks crecen linealmente |
| E2 (wiring-e2) | 2 (Entries + Facade) | ~100 | 1 linea por feature |
| G (wiring-g) | 1 (Facade) | ~95 | ensure*() crecen linealmente |
| H (wiring-h) | 1 (Facade) | ~50 | Inmutable -- zero edicion |
| I (wiring-i) | 1 (Facade) | ~50 | Inmutable -- zero edicion |
| J (wiring-j) | 1 (Facade) | ~50 | Inmutable -- zero edicion |
| K (wiring-k) | 2 (Discovery + Facade) | ~50 | Inmutable -- zero edicion |

---

## 11. Coste de Anadir una Feature

### Por patron

#### Pattern D -- Component Dependencies

| Paso | Fichero | Cambio |
|------|---------|--------|
| 1 | `feature-xxx-api/XxxApi.kt` | Nueva interfaz del servicio |
| 2 | `di-contracts/Provisions.kt` | Nueva `XxxProvisions` + `@XxxScope` |
| 3 | `feature-xxx-impl/XxxComponent.kt` | `@Component` + `@Module` + factory |
| 4 | `feature-xxx-impl/DefaultXxxService.kt` | Implementacion (`internal`) |
| 5 | `sdk/sdk-wiring/MultiModuleSdk.kt` | `ensureXxx()` + caso en when-block |
| 6 | `sdk/sdk-wiring/build.gradle.kts` | `implementation(project(":feature-xxx-impl"))` |
| 7 | `settings.gradle.kts` | 2 `include()` (api + impl) |

**Total: 7 puntos de contacto en 5 modulos.**

#### Pattern E2 -- Auto-Init Registry

| Paso | Fichero | Cambio |
|------|---------|--------|
| 1 | `feature-xxx-api/XxxApi.kt` | Nueva interfaz del servicio |
| 2 | `di-contracts/Provisions.kt` | Nueva `XxxProvisions` + `@XxxScope` |
| 3 | `feature-xxx-impl/XxxComponent.kt` | `@Component` + `@Module` + factory |
| 4 | `feature-xxx-impl/DefaultXxxService.kt` | Implementacion (`internal`) |
| 5 | `sdk/wiring-e2/Entries.kt` | `AutoProvisionEntry` (~10 lineas) |
| 6 | `sdk/wiring-e2/build.gradle.kts` | `implementation(project(":feature-xxx-impl"))` |
| 7 | `settings.gradle.kts` | 2 `include()` |

**Total: 7 puntos de contacto. Sin edicion del facade.**

#### Pattern G -- Factory Functions

| Paso | Fichero | Cambio |
|------|---------|--------|
| 1 | `feature-xxx-api/XxxApi.kt` | Nueva interfaz del servicio |
| 2 | `di-contracts/Provisions.kt` | Nueva `XxxProvisions` + `@XxxScope` |
| 3 | `feature-xxx-impl/XxxComponent.kt` | `@Component` + `@Module` + factory `buildXxxProvisions()` |
| 4 | `feature-xxx-impl/DefaultXxxService.kt` | Implementacion (`internal`) |
| 5 | `sdk/wiring-g/MultiModuleSdkG.kt` | `ensureXxx()` + caso en when-block |
| 6 | `sdk/wiring-g/build.gradle.kts` | `implementation(project(":feature-xxx-impl"))` |
| 7 | `settings.gradle.kts` | 2 `include()` |

**Total: 7 puntos de contacto. Identico a D.**

#### Pattern H -- ServiceLoader + FeatureProvider

| Paso | Fichero | Cambio |
|------|---------|--------|
| 1 | `feature-xxx-api/XxxApi.kt` | Nueva interfaz del servicio |
| 2 | `di-contracts/Provisions.kt` | Nueva `XxxProvisions` + `@XxxScope` |
| 3 | `feature-xxx-impl/XxxComponent.kt` | `@Component` + `@Module` + factory |
| 4 | `feature-xxx-impl/DefaultXxxService.kt` | Implementacion (`internal`) |
| 5 | `feature-xxx-impl/XxxProvider.kt` | `FeatureProvider` (~8 lineas) |
| 6 | `META-INF/services/...FeatureProvider` | 1 linea con la clase |
| 7 | `feature-xxx-impl/build.gradle.kts` | Dependencias Gradle |
| 8 | `settings.gradle.kts` | 2 `include()` |

**Total: 8 puntos de contacto. Zero edicion del wiring module.**

#### Pattern I -- PureFeatureProvider (zero DI)

| Paso | Fichero | Cambio |
|------|---------|--------|
| 1 | `feature-xxx-api/XxxApi.kt` | Nueva interfaz del servicio |
| 2 | `di-contracts/Provisions.kt` | Nueva `XxxProvisions` |
| 3 | `feature-xxx-impl/DefaultXxxService.kt` | Implementacion |
| 4 | `feature-xxx-impl/XxxPureProvider.kt` | `PureFeatureProvider` (~12 lineas, build manual) |
| 5 | `META-INF/services/...PureFeatureProvider` | 1 linea con la clase |
| 6 | `feature-xxx-impl/build.gradle.kts` | Dependencias Gradle |
| 7 | `settings.gradle.kts` | 2 `include()` |

**Total: 7 puntos de contacto. Sin Component, sin Module, sin codegen.**

#### Pattern J -- KIFeatureProvider (kotlin-inject)

| Paso | Fichero | Cambio |
|------|---------|--------|
| 1 | `feature-xxx-api/XxxApi.kt` | Nueva interfaz del servicio |
| 2 | `di-contracts/Provisions.kt` | Nueva `XxxProvisions` |
| 3 | `feature-xxx-impl/KIXxxComponent.kt` | kotlin-inject `@Component` (~6 lineas) |
| 4 | `feature-xxx-impl/DefaultXxxService.kt` | Implementacion |
| 5 | `feature-xxx-impl/XxxKIProvider.kt` | `KIFeatureProvider` (~12 lineas, build via KI) |
| 6 | `META-INF/services/...KIFeatureProvider` | 1 linea con la clase |
| 7 | `feature-xxx-impl/build.gradle.kts` | Dependencias Gradle |
| 8 | `settings.gradle.kts` | 2 `include()` |

**Total: 8 puntos de contacto. Zero edicion del wiring module.**

#### Pattern K -- AndroidManifest Metadata Discovery

| Paso | Fichero | Cambio |
|------|---------|--------|
| 1 | `feature-xxx-api/XxxApi.kt` | Nueva interfaz del servicio |
| 2 | `di-contracts/Provisions.kt` | Nueva `XxxProvisions` + `@XxxScope` |
| 3 | `feature-xxx-impl/XxxComponent.kt` | `@Component` + `@Module` + factory |
| 4 | `feature-xxx-impl/DefaultXxxService.kt` | Implementacion (`internal`) |
| 5 | `feature-xxx-impl/XxxProvider.kt` | `FeatureProvider` (~8 lineas, mismos que H) |
| 6 | `feature-xxx-impl/AndroidManifest.xml` | 1 entrada `<meta-data>` en el service dummy |
| 7 | `feature-xxx-impl/build.gradle.kts` | Dependencias Gradle |
| 8 | `settings.gradle.kts` | 2 `include()` |

**Total: 8 puntos de contacto. Zero edicion del wiring module. Zero cambios de codigo en wiring.**

### Resumen comparativo

| Patron | Puntos de contacto | Edicion central del wiring | Boilerplate por feature | Riesgo principal |
|--------|-------------------|---------------------------|------------------------|-----------------|
| **D** | 7 | Si (when + ensure) | Bajo | Wiring crece |
| **E2** | 7 | Minima (1 linea entries) | Alto (entry ~10 lineas) | Entries verbose |
| **G** | 7 | Si (when + ensure) | Bajo | Wiring crece |
| **H** | 8 (7 con SL auto) | Zero | Bajo (~8 lineas provider) | Overhead init |
| **I** | 7 | Zero | Medio (~12 lineas, build manual) | Sin compile-time safety |
| **J** | 8 | Zero | Medio (~12 lineas, KI + provider) | Ecosistema menor |
| **K** | 8 | Zero | Bajo (~8 lineas provider + 1 meta-data) | Requiere Context |
