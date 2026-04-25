# Multi-Module DI SDK — Internal Architecture (Source of Truth)

> Documento normativo. Cada afirmación describe el comportamiento real del
> runtime; el código en `di-contracts/` y los módulos de wiring son
> autoritativos sobre cualquier discrepancia.

**Audiencia.** Equipos que mantienen el SDK y equipos de feature modules que
contribuyen `FeatureProvider`s al grafo. No es un manual de consumo (ver doc
de integración para apps).

**Alcance.** Implementación basada en `ServiceLoader` + `FeatureProvider` +
`Resolver` con DFS lazy (Pattern H).

---

## 1. Arquitectura general

### 1.1 Componentes reales

| Componente | Tipo | Paquete | Responsabilidad |
|---|---|---|---|
| `Resolver` | `class` | `com.grinwich.sdk.contracts` | Indexa providers, resuelve servicios, gestiona lifecycle |
| `FeatureProvider` | `abstract class` | `com.grinwich.sdk.contracts` | Contrato para contribuciones de cada feature module |
| `SyntheticFeatureProvider` | `class` | `com.grinwich.sdk.contracts` | Provider para instancias inyectadas por la wiring (Context, Config) |
| `Flavor` | `enum` | `com.grinwich.sdk.contracts` | Tag de implementación: `DAGGER`, `PURE`, `KI`, `SYNTHETIC` |
| `DependencyResolutionException` | `abstract RuntimeException` | `com.grinwich.sdk.contracts.error` | Raíz de la jerarquía tipada |
| Subtipos | 6 clases | `com.grinwich.sdk.contracts.error` | `NoProviderFound`, `CircularDependency`, `ProviderBuild`, `ProviderAlreadyFailed`, `ServiceCast`, `ServiceNotAvailable` |
| `MultiModuleSdkH` | `object` (facade) | `com.grinwich.sdk.wiring.h` | API pública: `init` / `get` / `shutdown` |

### 1.2 Estado interno del Resolver

Todos los campos son `private val` salvo donde se indica. Los nombres son
literales del código.

| Campo | Tipo | Propósito |
|---|---|---|
| `lock` | `Any` | Monitor para `synchronized()` |
| `serviceIndex` | `HashMap<Class<*>, FeatureProvider>` | Mapping `serviceClass → provider que la publica`. Poblado por `register()` |
| `built` | `Set<FeatureProvider>` (ConcurrentHashMap-backed) | Providers cuyo `build()` ya completó con éxito |
| `resolvedServices` | `ConcurrentHashMap<Class<*>, Any>` | Cache de instancias resueltas. Fast-path de `get()` |
| `buildingProviders` | `Set<FeatureProvider>` (ConcurrentHashMap-backed) | Providers cuyo `build()` está en curso. Detector de ciclos |
| `failedProviders` | `Set<FeatureProvider>` (ConcurrentHashMap-backed) | Providers cuyo último `build()` lanzó. Bloquea reintentos |
| `persistentByClass` | `ConcurrentHashMap<KClass<out FeatureProvider>, FeatureProvider>` | Dedup O(1) de providers persistentes (logger, etc.) |
| `nonPersistentBuiltCount` | `AtomicInteger` | Contador O(1) de features no-persistentes construidas |

### 1.3 Responsabilidad exacta de FeatureProvider

```kotlin
abstract class FeatureProvider : FeatureContribution {
    abstract val flavor: Flavor
    abstract override val services: Set<Class<*>>
    override val persistent: Boolean = false
    abstract fun build(resolver: Resolver): Map<Class<*>, Any>
}
```

- `services`: declara qué interfaces publica el provider. Es el contrato visible
  al `Resolver`. Las claves del map devuelto por `build()` deben coincidir
  exactamente con este set (`ServiceNotAvailableException` en caso contrario).
- `build(resolver)`: construye las instancias. Las dependencias se solicitan
  llamando a `resolver.get(OtherService::class.java)` desde dentro de `build()`.
- `persistent`: si `true`, las instancias publicadas sobreviven a `clear()`
  (caso típico: logger, ApplicationContext).
- `flavor`: tag para que cada wiring (H, I, J, K) filtre los providers que le
  competen al cargarlos vía `ServiceLoader`.

### 1.4 SDK boundary y aislamiento real

```
┌──────────────────────────────────────────────────────────────┐
│  Application (consumer)                                       │
│   └── depends on: :sdk:integration                            │
└──────────────────────────────────────────────────────────────┘
                          │
                          ▼
┌──────────────────────────────────────────────────────────────┐
│  :sdk:integration (wiring-h)                                  │
│   ├── MultiModuleSdkH (facade object)                         │
│   ├── Resolver (1 instance)                                   │
│   └── ServiceLoader.load(FeatureProvider::class.java)         │
└──────────────────────────────────────────────────────────────┘
                          │
                          ▼  (runtime classpath only)
┌──────────────────────────────────────────────────────────────┐
│  feature-X-impl (N modules)                                   │
│   ├── XFeatureProvider : FeatureProvider                      │
│   ├── META-INF/services/...FeatureProvider                    │
│   └── DEPENDS ON:                                             │
│        • :sdk:di-contracts        (FeatureProvider/Resolver)  │
│        • :feature-X-api           (su propia API pública)     │
│        • :feature-Y-api, :feature-Z-api ...                   │
│          (APIs de features de las que necesita servicios)     │
│       NUNCA depende de:                                       │
│        • :sdk:integration                                     │
│        • :feature-Y-impl (otro feature-impl)                  │
└──────────────────────────────────────────────────────────────┘
                          │
                          ▼
┌──────────────────────────────────────────────────────────────┐
│  :sdk:di-contracts                                            │
│   └── Resolver, FeatureProvider, Flavor, exception hierarchy  │
│       Zero application types; pure protocol contract          │
└──────────────────────────────────────────────────────────────┘
```

- El `Resolver` no conoce ningún tipo de `:sdk:api` ni de `feature-*-api`.
  Todo lo que maneja son `Class<*>` y `Any`.
- Cada `feature-X-impl` compila aislado: su única visibilidad sobre el SDK es
  `:sdk:di-contracts` (protocol-level, sin tipos concretos).
- Los wiring modules son los únicos que importan implementaciones concretas
  (vía dependencia runtime + carga reflexiva de `ServiceLoader`).

### 1.5 Flujo de control entre capas

```
init()    : App → SdkFacade → Resolver.register(N providers) ───────► no build
get<T>()  : App → SdkFacade → Resolver.get(T) ──┬─► fast-path (resolvedServices)
                                                 └─► ensureBuilt() ─► provider.build() ─► (recursive resolver.get for deps)
shutdown(): App → SdkFacade → Resolver.clear()
```

Capas:
1. **App** mantiene el lifecycle (`init`/`shutdown`).
2. **SdkFacade** (`MultiModuleSdkH`) es un `object` con `_initialized: Boolean`
   y un `lifecycleLock`. Centraliza precondiciones y serializa init/shutdown.
3. **Resolver** orquesta el grafo. Sólo lifecycle visible: `register`, `get`, `clear`.
4. **FeatureProvider** describe contribuciones; ejecuta `build()` cuando se le
   solicita.

---

## 2. Decisiones de diseño

Las cinco decisiones que más impactan el comportamiento del sistema. No son
intercambiables sin reescribir el contrato.

### 2.1 DFS lazy vs eager

**Elegido:** lazy. `init()` sólo indexa; el grafo se construye en el primer
`get()` que lo requiera.

**Trade-off real:** init cold ~104 µs (filtrado de `ServiceLoader` + N llamadas
a `Resolver.register`). Construir todo eager añadiría el coste agregado de
todos los `build()` al arranque (~6× para el SDK demo). Lazy paga el coste
distribuido en el primer uso de cada feature.

**Coste oculto:** la primera resolución cruzada paga la cascada (`Sync → Auth +
Stor → Enc → Core` ≈ 6.8 µs en S22 Ultra).

### 2.2 DFS recursivo vs topological sort

**Elegido:** DFS recursivo, in-process, con detección de ciclos por set
`buildingProviders`.

**Por qué no topo-sort:** el topo-sort exige declarar dependencias upfront
(grafo explícito). El contrato actual de `FeatureProvider` no las pide; las
deps son **implícitas**, descubiertas cuando `build()` llama a `resolver.get()`.
Topo-sort obligaría a duplicar declaración (`services` + `dependencies`) con
riesgo de drift.

**Implicación:** los ciclos no se detectan al registrar — sólo cuando una
resolución cruza el ciclo. La detección sigue siendo determinista
(`CircularDependencyException` antes de que el stack se profundice).

### 2.3 ServiceLoader vs registro manual

**Elegido:** `ServiceLoader.load(FeatureProvider::class.java)` filtrado por
`Flavor`.

**Implicación 1:** zero-touch al añadir features. El wiring no se edita.

**Implicación 2:** dependencia de `META-INF/services/...FeatureProvider` en el
classpath runtime. Si R8 elimina el descriptor o el feature-impl no está en
`runtimeClasspath`, el provider **no existe** desde el punto de vista del SDK
y cualquier `get()` sobre sus servicios lanza `NoProviderFoundException`.

**No-confundir:** la **ausencia de descriptor** = ausencia silenciosa de feature.
No hay warning, no hay log de discovery. La validación es siempre `get()`-time.

### 2.4 Runtime vs compile-time validation

**Elegido:** runtime. El compilador no valida que cada `services` tenga un
provider que lo publique, ni que `build()` devuelva exactamente esos servicios.

**Mitigación canónica:** test integración en CI que llame `sdk.init(...)` y
luego `sdk.get(X)` para cada `X` esperado. Si falla, falla CI, no producción.

**Lo que sí está tipado:** los errores en tiempo de ejecución
(`DependencyResolutionException` y subtipos) son tipados, con `cause` real
preservada. No hay errores opacos `IllegalStateException("...")`.

### 2.5 sdk:api vs sdk:integration

**Elegido:** dos módulos separados.

- `:sdk:api` — **interfaces y modelos públicos**. Cero implementación. Lo
  consumen módulos de la app que necesitan tipos del SDK pero no inicializarlo.
- `:sdk:integration` — **wiring concreto** (Pattern H). Lo consume sólo el
  módulo principal de la app, que es el único que llama `init()`/`shutdown()`.

**Por qué la separación es crítica:** evita que módulos de feature de la app
arrastren transitivamente la maquinaria de `Resolver`/`ServiceLoader`/Dagger.
Mantiene el grafo de Gradle plano y los builds incrementales rápidos.

---

## 3. Bootstrap del SDK

### 3.1 Secuencia exacta de `init()`

```kotlin
fun init(context: Context, config: SdkConfig) {
    synchronized(lifecycleLock) {
        check(!_initialized) { "Already initialized." }

        // [1] Synthetic — Context/Config los provee la app, no ServiceLoader
        resolver.register(SyntheticFeatureProvider(mapOf(
            SdkConfig::class.java to config,
            Context::class.java   to context.applicationContext,
        )))

        // [2] Discovery + filter por Flavor + register
        ServiceLoader.load(FeatureProvider::class.java)
            .filter { it.flavor == Flavor.DAGGER } // o PURE / KI según wiring
            .forEach { resolver.register(it) }

        _initialized = true
    }
}
```

### 3.2 Orden real de ejecución

1. `synchronized(lifecycleLock)` adquirido.
2. `check(!_initialized)` — si ya estaba inicializado, lanza
   `IllegalStateException`.
3. Wiring construye `SyntheticFeatureProvider` con `Context.applicationContext`
   y `config`. Lo registra. **No hay build aquí**, sólo indexación:
   `register(synthetic)` recorre `synthetic.services` (= claves del map
   provisto: `SdkConfig::class.java`, `Context::class.java`) y rellena
   `serviceIndex[svc] = synthetic`. `resolvedServices` no se toca todavía.
4. `ServiceLoader.load(...)` itera el classpath, instancia cada
   `FeatureProvider` con `no-arg constructor`.
5. `.filter { it.flavor == Flavor.DAGGER }` descarta providers de otras
   variantes (un mismo classpath puede contener providers Dagger + PURE + KI;
   cada wiring se queda con los suyos).
6. `forEach { resolver.register(it) }` — para cada provider:
   - Si `provider.persistent && persistentByClass.putIfAbsent(...) != null` —
     skip: ya hay una instancia persistente registrada. Reusa servicios
     existentes en `serviceIndex`.
   - En caso contrario, recorre `provider.services` y rellena `serviceIndex`.
7. `_initialized = true` y se libera el lock.

**Punto clave:** ninguna `build()` corre durante init —ni siquiera la del
sintético. `built`, `buildingProviders`, `failedProviders` y
`resolvedServices` quedan vacíos. La primera vez que la app pida `Context` o
`SdkConfig` (típicamente porque otro provider los solicita en su `build()`),
el `Resolver` ejecutará `ensureBuilt(synthetic) → synthetic.build(this) →
provided` y entonces poblará `resolvedServices` con el map sintético.

### 3.3 Fallos silenciosos reales

| Síntoma | Causa raíz | Manifestación |
|---|---|---|
| Feature no funciona en producción | `runtimeOnly(:feature-X-impl)` ausente del `:sdk:integration` | `NoProviderFoundException` en el primer `get(X)` |
| Feature aparece en debug y no en release | R8 elimina `META-INF/services/...` sin keep rule | Igual: `NoProviderFoundException` |
| Provider con `flavor` distinto al filtro | El feature-impl declaró `Flavor.PURE` pero el wiring filtra `Flavor.DAGGER` | Igual: `NoProviderFoundException` |
| Constructor no-arg ausente | `ServiceLoader` no instancia el provider | `ServiceConfigurationError` durante `init()` (no durante `get()`) |

### 3.4 Ausencia de warnings

`ServiceLoader.load()` no emite logs ni telemetría sobre qué descubrió. Si el
`META-INF/services/...FeatureProvider` está vacío, `init()` completa sin error
y todo `get()` posterior lanza. **Esta es una propiedad consciente:** la
detección se traslada al test de integración.

### 3.5 Impacto de classpath incompleto

- En desarrollo (Android Studio "Make Project"): los `META-INF/services` se
  combinan en el APK final. Si un módulo no está en `runtimeClasspath`, su
  descriptor no aparece y su provider no se carga.
- En CI: `./gradlew :app:assembleRelease` debe incluir un step que ejecute
  `:app:dependencyInsight --configuration releaseRuntimeClasspath` para
  validar la presencia de cada feature-impl.

---

## 4. Integración con SDK

### 4.1 Por qué la app controla `init()`

El SDK es un componente; la app es el `Application`. Sólo el `Application`
sabe cuándo el `Context` está disponible y cuándo el proceso comienza/termina.
Llamar `init()` en otro punto (un `Activity`, un `Service`) abre dos races:

1. **Init durante una resolución de un fragmento previo** — el caller ve
   `IllegalStateException("not initialized")` porque el thread del
   `Application.onCreate` aún no ha completado.
2. **Doble init** desde dos entry points distintos — cubierto por
   `synchronized(lifecycleLock) + check(!_initialized)` que lanza
   determinísticamente.

### 4.2 Por qué los features no conocen DI

Cada `feature-X-impl` depende sólo de `:sdk:di-contracts` (protocol) y de su
propia `:feature-X-api` (interfaces). **No depende de ningún framework DI**
(Dagger, Koin, etc.) **a nivel de gradle** (excepto si el flavor del provider
es DAGGER y el feature usa Dagger internamente para construir; en ese caso es
una decisión interna del feature, no impuesta por el SDK).

Consecuencia: añadir un feature nuevo no implica tocar el SDK ni decidir su
framework. El feature-impl elige cómo construir sus instancias en `build()`.

### 4.3 Delegación real de `sdk.get()`

```kotlin
override fun <T : Any> get(clazz: Class<T>): T {
    check(_initialized) { "Not initialized." }
    return try {
        resolver.get(clazz)
    } catch (e: DependencyResolutionException) {
        if (!_initialized) throw IllegalStateException("Not initialized.", e)
        throw e
    }
}
```

- El `check(_initialized)` no toma `lifecycleLock`. Es lectura barata.
- El `try/catch` remapea la race con un `shutdown()` concurrente al contrato
  externo (`IllegalStateException("not initialized")`), preservando el
  `DependencyResolutionException` real como `cause`.
- Si el SDK sigue inicializado y la excepción de dominio fue legítima
  (no provider, build error, etc.) se propaga sin envolver — el caller recibe
  el tipo concreto.

### 4.4 Aislamiento del Resolver

El `Resolver` nunca se expone a la app. Es un `private val` del facade. La
única superficie pública es `MultiModuleSdkApi { init, get<T>(), shutdown,
isInitialized, builtFeatureCount }`. Esto bloquea:

- Que un módulo de la app se registre como provider en runtime (sólo se
  permite vía `ServiceLoader`).
- Que un test inyecte fakes saltándose el discovery (los tests deben construir
  su propio `Resolver` o usar feature-impl de test).
- Que código de la app inspeccione/modifique `serviceIndex` o
  `resolvedServices`.

---

## 5. Flujo de resolución

### 5.1 Pasos exactos de `Resolver.get()`

```kotlin
fun <T : Any> get(clazz: Class<T>): T {
    // (a) Fast-path
    resolvedServices[clazz]?.let { return castOrThrow(clazz, it) }

    // (b) Lookup de provider en el índice
    val provider = serviceIndex[clazz]
        ?: throw NoProviderFoundException(clazz.simpleName)

    // (c) Construcción si es necesaria
    ensureBuilt(provider)

    // (d) Re-lectura del cache (poblado por ensureBuilt vía build())
    val resolved = resolvedServices[clazz]
        ?: throw ServiceNotAvailableException(clazz.simpleName, provider::class.java.simpleName)

    return castOrThrow(clazz, resolved)
}
```

### 5.2 Fast-path (`resolvedServices`)

Operación: una llamada a `ConcurrentHashMap.get()` + `Class.cast()`. ~30-40 ns
medidos en S22 Ultra. Es el path que cubren los `sdk.get()` después del
primer build de cada servicio.

### 5.3 Lookup en `serviceIndex`

Si fast-path falla (cache miss), se busca el provider responsable. El índice
fue poblado por `register()` durante init. Si el `Class` solicitado no tiene
provider:

```
throw NoProviderFoundException(clazz.simpleName)
```

Es la única forma de fallo "limpio" en este paso.

### 5.4 `ensureBuilt(provider)` — DFS lazy con doble check

```kotlin
private fun ensureBuilt(provider: FeatureProvider) {
    if (provider in built) return
    if (provider in failedProviders) throw ProviderAlreadyFailedException(...)

    synchronized(lock) {
        if (provider in built) return
        if (provider in failedProviders) throw ProviderAlreadyFailedException(...)
        if (provider in buildingProviders) throw CircularDependencyException(...)

        buildingProviders.add(provider)
        try {
            val map = provider.build(this)               // (A) puede llamar resolver.get() recursivamente
            for ((svc, inst) in map) resolvedServices[svc] = inst
            built.add(provider)                          // LAST — gate para otros threads
            if (!provider.persistent) nonPersistentBuiltCount.incrementAndGet()
        } catch (e: DependencyResolutionException) {
            throw e                                       // (B) propaga errores tipados sin tocar failedProviders
        } catch (t: Throwable) {
            failedProviders.add(provider)                 // (C) marca como fallido para futuras llamadas
            throw ProviderBuildException(provider::class.java.simpleName, t)
        } finally {
            buildingProviders.remove(provider)            // (D) drain siempre — éxito, ciclo o build error
        }
    }
}
```

Notas:

- (A) `provider.build(this)` puede llamar `resolver.get(...)` para sus deps.
  Esa llamada vuelve a entrar en `get()` arriba. Si la dep ya está en
  `resolvedServices` → fast-path. Si no, recurre en `ensureBuilt()` con el
  mismo `lock` — Java/Kotlin permiten reentrada del monitor en el mismo
  thread.
- (B) Las `DependencyResolutionException` que surjan de la cadena (ciclo,
  no provider transitivo, dep ya fallida, etc.) se **propagan sin marcar
  este provider como fallido**. La razón: si la causa requiere ser marcada
  (por ejemplo, un `ProviderBuildException` profundo) ya lo hizo el
  `ensureBuilt` que la lanzó. Marcar también a este provider intermedio
  añadiría ruido — el caller vería FAILED en una cadena de providers cuando
  el problema real está en uno solo. Para los casos que **no** marcan
  (`CircularDependencyException`, `NoProviderFoundException`,
  `ServiceCastException`, `ServiceNotAvailableException`), un retry posterior
  encontrará la misma causa y volverá a propagarla determinísticamente.
- (C) Cualquier otra excepción del usuario (`NullPointerException`,
  `IllegalArgumentException`, etc.) se envuelve en `ProviderBuildException`
  con la `cause` original preservada.
- (D) `finally { buildingProviders.remove(provider) }` garantiza que el set
  no acumule entradas residuales tras éxito, ciclo o fallo. Es la condición
  para que la detección de ciclos no produzca falsos positivos en threads
  posteriores.

### 5.5 DFS reentrante en mismo thread

Si en `(A)` el `build()` llama a `resolver.get(Self)` (auto-dependencia) o
`resolver.get(Other)` donde `Other.build()` termina llamando a
`resolver.get(Self)`, el thread re-entra en `synchronized(lock)` (reentrada
JVM permitida). Al chequear `if (provider in buildingProviders)` encuentra
al provider ya en proceso → `CircularDependencyException`. El stack no se
profundiza más allá del ciclo detectado.

---

## TRACE DE EJECUCIÓN REAL

> Trace fiel al runtime para un SDK con 3 features de ejemplo:
> `CoreProvider`, `SecurityProvider`, `NetworkProvider`.
>
> **Dependencias declaradas en código:**
> - `CoreProvider.services = { CoreCrypto::class.java }` (sin deps)
> - `SecurityProvider.services = { SecurityApi::class.java }`,
>   `build()` llama `resolver.get(CoreCrypto::class.java)`
> - `NetworkProvider.services = { NetworkClient::class.java }`,
>   `build()` llama `resolver.get(SecurityApi::class.java)`
>
> Todos `Flavor.DAGGER` (filtro del wiring asumido).

```
[init] — single-threaded, dentro de synchronized(lifecycleLock)

[step 1] register del sintético (orden literal en MultiModuleSdkH.init)
  resolver.register(SyntheticFeatureProvider({ SdkConfig→config, Context→appContext }))
    serviceIndex[SdkConfig::class.java] = synthetic
    serviceIndex[Context::class.java]   = synthetic

[step 2] ServiceLoader.load(FeatureProvider::class.java)
  → CoreProvider     (no-arg ctor instantiated)
  → SecurityProvider
  → NetworkProvider
  .filter { it.flavor == Flavor.DAGGER }    ← los tres pasan
  .forEach { resolver.register(it) }

[step 3] register phase — sólo indexación, no se invoca build()
register(CoreProvider)
  serviceIndex[CoreCrypto::class.java] = CoreProvider
register(SecurityProvider)
  serviceIndex[SecurityApi::class.java] = SecurityProvider
register(NetworkProvider)
  serviceIndex[NetworkClient::class.java] = NetworkProvider

_initialized = true
[init] returns

╌╌╌  estado tras init  ╌╌╌
serviceIndex          = { SdkConfig→synthetic, Context→synthetic,
                          CoreCrypto→Core, SecurityApi→Security,
                          NetworkClient→Network }
resolvedServices      = { }                       ← VACÍO (ni sintéticos
                                                    ni features se
                                                    pre-pueblan; todo se
                                                    construye en el primer
                                                    get() que lo pida)
built                 = { }
buildingProviders     = { }
failedProviders       = { }
nonPersistentBuiltCount = 0
╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌

[first call] sdk.get(NetworkClient::class.java)
  check(_initialized) — OK
  resolver.get(NetworkClient)
    resolvedServices[NetworkClient] → MISS
    serviceIndex[NetworkClient] = NetworkProvider
    ensureBuilt(NetworkProvider)
      NetworkProvider in built? no
      NetworkProvider in failedProviders? no
      synchronized(lock) — adquirido
        double-check: aún no built / no failed / no building
        buildingProviders.add(NetworkProvider)
        NetworkProvider.build(resolver)            ← (1) inicia build de Network
          resolver.get(SecurityApi)                  ← llamada anidada a get()
            resolvedServices[SecurityApi] → MISS
            serviceIndex[SecurityApi] = SecurityProvider
            ensureBuilt(SecurityProvider)
              synchronized(lock) — REENTRADA (mismo thread, monitor reentrante)
                buildingProviders.add(SecurityProvider)
                SecurityProvider.build(resolver)   ← (2) inicia build de Security
                  resolver.get(CoreCrypto)
                    resolvedServices[CoreCrypto] → MISS
                    serviceIndex[CoreCrypto] = CoreProvider
                    ensureBuilt(CoreProvider)
                      synchronized(lock) — REENTRADA
                        buildingProviders.add(CoreProvider)
                        CoreProvider.build(resolver) → returns { CoreCrypto: <Impl> }
                        resolvedServices[CoreCrypto] = <Impl>
                        built.add(CoreProvider)
                        nonPersistentBuiltCount = 1
                        finally: buildingProviders.remove(CoreProvider)
                    [vuelta a resolver.get(CoreCrypto)]
                    re-lectura: resolvedServices[CoreCrypto] → HIT
                    return castOrThrow(CoreCrypto, <Impl>) → <Impl>
                  SecurityProvider.build returns { SecurityApi: <Impl(crypto)> }
                  resolvedServices[SecurityApi] = <Impl>
                  built.add(SecurityProvider)
                  nonPersistentBuiltCount = 2
                  finally: buildingProviders.remove(SecurityProvider)
            [vuelta a resolver.get(SecurityApi)]
            re-lectura: resolvedServices[SecurityApi] → HIT
            return castOrThrow(SecurityApi, <Impl>) → <Impl>
          NetworkProvider.build returns { NetworkClient: <Impl(securityApi)> }
          resolvedServices[NetworkClient] = <Impl>
          built.add(NetworkProvider)
          nonPersistentBuiltCount = 3
          finally: buildingProviders.remove(NetworkProvider)
    [vuelta a resolver.get(NetworkClient)]
    re-lectura: resolvedServices[NetworkClient] → HIT
    return castOrThrow(NetworkClient, <Impl>) → <Impl>

╌╌╌  estado tras primer get(NetworkClient)  ╌╌╌
resolvedServices    = { CoreCrypto, SecurityApi, NetworkClient }
built               = { CoreProvider, SecurityProvider, NetworkProvider }
buildingProviders   = { }   ← drenado
failedProviders     = { }
nonPersistentBuiltCount = 3
╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌

[subsequent call] sdk.get(NetworkClient::class.java)
  check(_initialized) — OK
  resolver.get(NetworkClient)
    resolvedServices[NetworkClient] → HIT
    return castOrThrow(NetworkClient, <Impl>) → <Impl>   ← ~30-40 ns
```

> **Nota.** El trace ejemplo asume que `CoreProvider`, `SecurityProvider` y
> `NetworkProvider` no necesitan `Context` ni `SdkConfig` en su `build()`. Si
> alguno los pidiese, el trace mostraría una resolución adicional sobre el
> `SyntheticFeatureProvider` (mismo flujo: `ensureBuilt(synthetic) →
> synthetic.build(resolver) = provided`), poblando `resolvedServices` con
> `{ Context, SdkConfig }` antes de continuar la cascada.

### Reglas del trace

| Regla | Aplicación |
|---|---|
| No abstracción | El trace nombra clases reales del runtime (`CoreProvider`, `Resolver`, `serviceIndex`), no metáforas. |
| No explicación conceptual dentro del trace | Toda la teoría va fuera; el trace sólo lista pasos JVM. |
| Debe reflejar llamadas reales | Cada línea corresponde a una invocación o mutación de campo en el código. |
| Debe mostrar fast-path vs build-path | Las dos rutas aparecen explícitamente: la de `MISS → ensureBuilt` y la de `HIT → return`. |
| Debe mostrar reentrancia DFS implícita | Visible en la indentación: tres niveles de `synchronized(lock)` reentrante en el mismo thread. |
| Debe ser consistente con código `FeatureProvider` | `services`, `build()` y `resolver.get()` aparecen tal cual están definidos en la API. |

---

## 6. Modelo de concurrencia

### 6.1 ConcurrentHashMap — uso real

| Campo | Tipo | Por qué CHM |
|---|---|---|
| `resolvedServices` | `ConcurrentHashMap<Class<*>, Any>` | Lectura lock-free desde fast-path. Escritura sólo bajo `lock`. |
| `built` | Set CHM-backed | Membership lock-free desde fast-fail check. Escritura bajo `lock`. |
| `buildingProviders` | Set CHM-backed | Inspección y mutación bajo `lock`; nunca consultado fuera de `synchronized`. |
| `failedProviders` | Set CHM-backed | Pre-check fuera del lock + re-check dentro. |

`serviceIndex` es `HashMap` plano: no se muta tras init (read-only en hot path).

### 6.2 Double-checked locking

```kotlin
if (provider in built) return                     // (1) fuera del lock
if (provider in failedProviders) throw ...        // (2) fast-fail fuera del lock
synchronized(lock) {
    if (provider in built) return                 // (3) re-check después del wait
    if (provider in failedProviders) throw ...    // (4) idem
    if (provider in buildingProviders) throw ...  // (5) sólo dentro del lock — same-thread cycle
    ...
}
```

- (1) y (2) son optimizaciones para evitar contención en el hot path.
- (3) y (4) son obligatorios: durante el wait en `synchronized`, otro thread
  pudo construir el provider o marcarlo failed.
- (5) **sólo aparece dentro del lock**. Otro thread no puede ver
  `buildingProviders` mid-build sin haber adquirido el monitor; reentrada
  same-thread es la única forma de observar ese estado.

### 6.3 Reentrancia JVM de `synchronized`

`Object.wait()`/`notify()` y la directiva `synchronized` usan monitores
reentrantes. Si un thread T entra en `synchronized(lock)` por primera vez y
durante `provider.build(this)` llama a otro `ensureBuilt()` que vuelve a
ejecutar `synchronized(lock)`, el monitor incrementa su counter (no se
bloquea). Esto permite el DFS recursivo en mismo thread sin deadlock.

### 6.4 Garantía de single construction

```
T1: ensureBuilt(P):
      P in built?           NO
      P in failedProviders? NO
      synchronized(lock) {
         double-check: NO / NO / NO
         buildingProviders.add(P)
         try {
            map = P.build()
            for (svc, inst) in map: resolvedServices[svc] = inst   ← (i) populates cache
            built.add(P)                                            ← (ii) "LAST" — gate
            if (!P.persistent) nonPersistentBuiltCount++
         } finally {
            buildingProviders.remove(P)                             ← (iii) drain
         }
      }                                                              ← release

T2 (concurrent):
      P in built?  YES  → return    ← lock-free fast-path; nunca toma lock
```

El comentario `// LAST — gate for other threads` en `built.add(P)` aplica al
**par (i, ii)**: dentro del `try`, las instancias se publican en
`resolvedServices` ANTES de que `P` entre en `built`. Cualquier thread
externo que vea `P ∈ built` (lock-free, vía la visibilidad de
`ConcurrentHashMap`) ya verá también `resolvedServices[svc]` poblado para
todos los `svc` que `P` publica.

Las acciones posteriores (incremento del contador, `finally` con
`buildingProviders.remove`) no participan en esta garantía: el contador es
un atómico independiente y `buildingProviders` se consulta sólo dentro del
lock.

**Single construction:** un thread que llegue tarde y adquiera el lock encuentra
`P ∈ built` en el double-check y retorna sin reconstruir. Si el primer thread
falló, lo que encuentra es `P ∈ failedProviders` y lanza
`ProviderAlreadyFailedException`. Nunca se invoca `P.build()` dos veces sin
un `clear()` entre medias.

### 6.5 Comportamiento multi-thread real

| Escenario | Outcome |
|---|---|
| 100 threads `get(X)` simultáneos, `X` no construido | 1 thread ejecuta `build()`. Los 99 esperan en el monitor. Al despertar ven `X ∈ built` y devuelven la instancia cacheada. |
| Thread A `get(X)`, Thread B `init()` mientras `_initialized=false` | A pasa `check(_initialized)` o lanza `IllegalStateException` antes del lock; el lock no participa en este caso. |
| Thread A `get(X)`, Thread B `shutdown()` durante el build | A toma `resolver.lock` en `ensureBuilt`. B entra a `shutdown()` y adquiere `lifecycleLock` (lock independiente); su `resolver.clear()` intenta tomar `resolver.lock` y **bloquea hasta que A libere**. Cuando A termina su `ensureBuilt`, B entra a `clear()` y drena `serviceIndex` y `resolvedServices`. A continuación, A vuelve a la línea siguiente de `resolver.get()`: re-lee `resolvedServices[X]`. Tres outcomes posibles: (1) si A consiguió cachear el valor antes de que B drenase y A re-lee antes — A retorna OK; (2) si B drenó primero — A re-lee `null` y lanza `ServiceNotAvailableException`; (3) si A entra a `resolver.get()` después de que B haya completado clear — el `serviceIndex.get()` falla con `NoProviderFoundException`. En los casos (2) y (3) el catch+remap del facade ve `_initialized == false` y re-lanza como `IllegalStateException("not initialized")`. **Nunca se reintenta el build dentro del mismo `get()`** — `resolver.get()` es de un solo paso. |

---

## 7. State machine de providers

### 7.1 Estados reales

Cada provider está en exactamente uno de estos estados durante el ciclo de
vida del Resolver:

```
            register()
   [unregistered] ────────────► [INDEXED]
                                    │
                                    │ ensureBuilt() entra
                                    ▼
                                [BUILDING]
                            ┌───────┴────────┐
                  build() OK│                │ build() THROWS
                            ▼                ▼
                          [BUILT]         [FAILED]
                            │                │
                  clear() (no persistent)    │ ProviderAlreadyFailedException
                  o build() de retry         │ en futuras get()
                            │                │
                            ▼                ▼
                       [INDEXED]         [FAILED]   ← terminal hasta clear()
                       (limpio)
```

### 7.2 Transiciones válidas

| De | A | Trigger |
|---|---|---|
| unregistered | INDEXED | `register(provider)` populates `serviceIndex` |
| INDEXED | BUILDING | `ensureBuilt(provider)` adquiere `lock` y añade a `buildingProviders` |
| BUILDING | BUILT | `provider.build(this)` retorna sin lanzar; añadido a `built` |
| BUILDING | FAILED | `provider.build(this)` lanza no-DependencyResolution → añadido a `failedProviders` |
| BUILDING | INDEXED | `provider.build(this)` lanza `DependencyResolutionException` (por ej. ciclo) → no se marca failed; queda como antes |
| BUILT | INDEXED | `clear()` y provider no es persistente |
| FAILED | INDEXED | `clear()` (resetea fallos) |

### 7.3 FAILED es terminal (sin clear)

Una vez en `FAILED`, todo `get()` que pase por ese provider lanza
`ProviderAlreadyFailedException` **sin re-ejecutar `build()`**. Esta política
es deliberada:

- Un `build()` que falló por config inválida o estado del entorno volverá a
  fallar igual al reintentar — devolver el mismo error de forma rápida es
  mejor diagnóstico.
- Reintentar silenciosamente puede producir grafos parciales no deterministas
  si `build()` tiene side effects.

Para forzar reintento: `sdk.shutdown()` → `sdk.init(...)`. `clear()` drena
`failedProviders`.

### 7.4 No retry behavior

Ninguna ruta del Resolver invoca `build()` dos veces para el mismo provider
sin un `clear()` entre medias. Garantizado por:
- `built.contains(provider)` corta antes del lock.
- `failedProviders.contains(provider)` corta antes del lock.
- Dentro del lock se rechequea ambos.

### 7.5 Impacto en `get()` futuras

| Estado al llamar `get(X)` donde `X ∈ provider.services` | Resultado |
|---|---|
| INDEXED | Construcción lazy. Puede transicionar a BUILT, FAILED o levantar `CircularDependency` (queda INDEXED). |
| BUILDING (mismo thread) | `CircularDependencyException` |
| BUILDING (otro thread) | Espera al monitor; al despertar ve BUILT o FAILED. |
| BUILT | Fast-path en `resolvedServices`. ~30-40 ns. |
| FAILED | `ProviderAlreadyFailedException` inmediata. |

---

## 8. Contrato de Feature Module

### 8.1 Obligatorio (formal)

1. **`META-INF/services/com.grinwich.sdk.contracts.FeatureProvider`** —
   archivo con la FQN del provider. Sin este archivo, el provider **no existe**
   para `ServiceLoader`.
2. **Constructor sin argumentos** (no-arg ctor) — `ServiceLoader` lo invoca
   por reflexión.
3. **`services == claves del map devuelto por build()`** — discrepancia →
   `ServiceNotAvailableException` cuando `get()` solicita la clave faltante.
4. **Registro completo** — todas las clases declaradas en `services` deben
   poder resolverse vía `serviceIndex` después de `register()`. Esto se
   garantiza automáticamente porque `register()` itera `services`.

### 8.2 Consecuencia de incumplir

| Incumplimiento | Manifestación |
|---|---|
| Sin `META-INF/services` | El provider no aparece en `ServiceLoader`. Sus servicios → `NoProviderFoundException` en `get()`. **Feature inexistente para el SDK.** |
| Constructor con args | `ServiceConfigurationError: Provider X could not be instantiated` durante `init()`. |
| `services` declara `X` pero `build()` no la incluye | `ServiceNotAvailableException("X not published by ProviderClass after build()")` en el `get(X)`. |

### 8.3 Implementación obligatoria (mínima)

Un feature module funcional debe contribuir un `FeatureProvider` con:

- Mínimo 2 servicios publicados (típicamente API + helper o variante con/sin
  cache). Si una feature sólo expone 1 servicio, esa restricción no aplica —
  pero siga validando con tests que el `services` set es exhaustivo.
- Dependencias declaradas vía `resolver.get()` dentro de `build()`. **No
  capture estado del provider en lambdas que sobrevivan a `build()`** — el
  `resolver` puede haber sido limpiado vía `clear()` cuando se ejecuta el
  callback.
- Interacción con otros providers limitada a `resolver.get(OtherApi)`. Cero
  acoplamiento directo a clases internas de otros features.
- Registro **completo** de servicios (incluir todas las APIs que la
  documentación del feature anuncia).
- **Coherencia con el trace.** Lo que `resolver.get(...)` aparece en el código
  debe aparecer en el trace de integración del feature.

---

## 9. Guía de integración de un nuevo Feature

Procedimiento ejecutable, paso a paso. Cada bloque es accionable sin
información adicional.

### 9.1 Crear el módulo

```
features/feature-payment-impl/
  build.gradle.kts
  src/main/
    kotlin/com/empresa/feature/payment/
      PaymentService.kt         (interna)
      PaymentFeatureProvider.kt (extiende FeatureProvider)
    resources/
      META-INF/services/
        com.grinwich.sdk.contracts.FeatureProvider
```

`build.gradle.kts` (mínimo, sin extras):

```kotlin
plugins { alias(libs.plugins.android.library) }

android {
    namespace = "com.empresa.feature.payment.impl"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
}

dependencies {
    api(project(":feature-payment-api"))      // sólo si la API pública es exportable
    implementation(project(":sdk:di-contracts"))
    implementation(project(":feature-core-api"))   // si depende de Core
    // NO importar :sdk:integration ni otros feature-impl
}
```

**Aislamiento real del SDK:** ninguna dependencia hacia `:sdk:integration`,
`:sdk:api`, ni hacia ningún otro `feature-*-impl`. Si el feature necesita
acceder a otra API (e.g. `CoreCryptoApi`), depende del `:feature-core-api`
correspondiente, **no** del impl.

### 9.2 Implementar el `FeatureProvider`

```kotlin
package com.empresa.feature.payment

import com.grinwich.sdk.contracts.FeatureProvider
import com.grinwich.sdk.contracts.Flavor
import com.grinwich.sdk.contracts.Resolver
import com.empresa.feature.core.CoreCryptoApi
import com.empresa.feature.payment.api.PaymentApi
import com.empresa.feature.payment.api.RefundApi

class PaymentFeatureProvider : FeatureProvider() {

    override val flavor = Flavor.DAGGER     // o PURE / KI según el wiring objetivo

    override val services: Set<Class<*>> = setOf(
        PaymentApi::class.java,
        RefundApi::class.java,
    )

    override fun build(resolver: Resolver): Map<Class<*>, Any> {
        val crypto = resolver.get(CoreCryptoApi::class.java)
        val payment = DefaultPaymentService(crypto)
        val refund  = DefaultRefundService(payment)
        return mapOf(
            PaymentApi::class.java to payment,
            RefundApi::class.java  to refund,
        )
    }
}
```

Reglas:

- `clase concreta` con visibilidad `public` (ServiceLoader la instancia
  reflexivamente).
- `services` declara **todas** las interfaces publicadas; las claves del map
  retornado por `build()` deben ser **exactamente** ese set.
- `register()` en el SDK ya está cubierto por `ServiceLoader` + `Resolver`. El
  feature no llama a `register` ni inicia el SDK.
- `resolver.get()` se usa **dentro de `build()`** para obtener deps; nunca se
  almacena el `resolver` para uso posterior.

### 9.3 Registrar en `ServiceLoader`

Archivo: `src/main/resources/META-INF/services/com.grinwich.sdk.contracts.FeatureProvider`

```
com.empresa.feature.payment.PaymentFeatureProvider
```

Reglas:

- **Naming exacto:** una línea por provider, FQN completo. Líneas vacías y
  comentarios `#...` permitidos.
- **Consecuencia de omisión:** el provider no aparece en `ServiceLoader.load`,
  por tanto el SDK no lo conoce. `get()` sobre `PaymentApi` → `NoProviderFoundException`.

### 9.4 Build & classpath

En el módulo `:sdk:integration` (o el módulo de la app que use el SDK
directamente):

```kotlin
dependencies {
    implementation(project(":sdk:di-contracts"))
    runtimeOnly(project(":features:feature-payment-impl"))   // <— clave
}
```

- `runtimeOnly` (no `implementation`) garantiza que el código fuente de
  `feature-payment-impl` no esté visible en compile-time desde `:sdk:integration`.
  Sólo está presente en runtime para que `ServiceLoader` lo descubra.
- Si se omite esta línea, el feature no entra en `runtimeClasspath` → no
  está en el APK → `NoProviderFoundException` en cuanto la app lo use.

### 9.5 Validación runtime

Test de integración mínimo que debe vivir en CI antes de mergear:

```kotlin
@Test fun payment_feature_is_resolvable() {
    MultiModuleSdkH.init(testContext, SdkConfig(debug = true))
    try {
        val payment = MultiModuleSdkH.get<PaymentApi>()
        assertNotNull(payment)
        val refund = MultiModuleSdkH.get<RefundApi>()
        assertNotNull(refund)
    } finally {
        MultiModuleSdkH.shutdown()
    }
}
```

Cubre los modos de fallo silencioso real:

| Fallo | Excepción detectada por el test |
|---|---|
| `META-INF/services` ausente | `NoProviderFoundException` en `get<PaymentApi>()` |
| `services` no incluye `RefundApi` | `NoProviderFoundException` en `get<RefundApi>()` |
| `build()` lanza por config inválida | `ProviderBuildException` con causa real |
| Dep transitiva (CoreCryptoApi) sin provider en classpath | `NoProviderFoundException` durante el `build()` de Payment |

---

## 10. Sistema de errores

### 10.1 Jerarquía completa

```
DependencyResolutionException (abstract, RuntimeException)
├── NoProviderFoundException(serviceName)
├── CircularDependencyException(providerName)
├── ProviderBuildException(providerName, cause)
├── ProviderAlreadyFailedException(providerName)
├── ServiceCastException(serviceName, cause)
└── ServiceNotAvailableException(serviceName, providerName)
```

Paquete: `com.grinwich.sdk.contracts.error`. Toda excepción del Resolver es
subtipo de `DependencyResolutionException`. Cualquier otra excepción que
escape del SDK es bug.

### 10.2 Timing exacto

| Excepción | Cuándo se lanza |
|---|---|
| `NoProviderFoundException` | `get(X)` y `serviceIndex` no contiene `X` |
| `CircularDependencyException` | `ensureBuilt(P)` y `P ∈ buildingProviders` (reentrada same-thread) |
| `ProviderBuildException` | `provider.build()` lanza una `Throwable` no tipada |
| `ProviderAlreadyFailedException` | `ensureBuilt(P)` y `P ∈ failedProviders` |
| `ServiceCastException` | `castOrThrow(clazz, instance)` y `clazz.cast(instance)` lanza `ClassCastException` |
| `ServiceNotAvailableException` | Tras `ensureBuilt(provider)`, `resolvedServices[clazz]` sigue null |

### 10.3 Wrapping en SDK

El facade SDK envuelve exclusivamente la race con shutdown:

```kotlin
override fun <T : Any> get(clazz: Class<T>): T {
    check(_initialized) { "Not initialized." }
    return try {
        resolver.get(clazz)
    } catch (e: DependencyResolutionException) {
        if (!_initialized) throw IllegalStateException("Not initialized.", e)
        throw e
    }
}
```

- `check(_initialized)` es responsabilidad del facade, no del Resolver. Lanza
  `IllegalStateException("Not initialized.")` si la app llama antes de
  `init()` o después de `shutdown()`.
- El `try/catch` cubre la race `get()/shutdown()`: si la cancelación del SDK
  ocurre durante `resolver.get()`, el caller recibe `IllegalStateException`
  (contrato lifecycle), no la excepción de dominio (que sería confusa al
  caller que no sabía que el SDK estaba siendo destruido).

### 10.4 Preservación del cause

Toda excepción tipada que envuelve otra preserva la `cause` original:

- `ProviderBuildException(providerName, cause = throwableOriginal)`.
- `ServiceCastException(serviceName, cause = classCastException)`.
- El `IllegalStateException` del wrapping de race también lleva el
  `DependencyResolutionException` como `cause`.

Para diagnóstico desde logs/Crashlytics: `Throwable.cause` da el origen real.

---

## 11. Failure modes

Tabla obligatoria de los modos de fallo que cubre el sistema. **Todos
testeados** en `di-contracts/src/test/`.

| Situación | Causa real | Excepción | Momento exacto |
|---|---|---|---|
| Ciclo entre providers (`A→B→A`) | `B.build()` llama `resolver.get(A)` mientras `A` aún está en `buildingProviders` | `CircularDependencyException(B::class.simpleName)` | Reentrada del lock del mismo thread durante la cascada de `ensureBuilt` |
| `StackOverflowError` por ciclo | **Eliminado**. La detección por `buildingProviders` corta antes de que el stack se profundice. | `CircularDependencyException` | Mismo punto que arriba |
| Race shutdown durante `get()` | T1 está en `resolver.get(X)`, T2 corre `shutdown()` que llama `resolver.clear()` (limpia `serviceIndex` y `resolvedServices` no-persistents) | Según el momento exacto en que T2 drena: T1 ve `NoProviderFoundException` (si `serviceIndex.get(X)` retorna null) o `ServiceNotAvailableException` (si la re-lectura de `resolvedServices[X]` retorna null tras `ensureBuilt`). El facade re-chequea `_initialized`; si es `false`, remapea a `IllegalStateException("not initialized")` con la DRE como `cause` | Entre cualquiera de los dos accesos a `serviceIndex`/`resolvedServices` dentro de `resolver.get()`. Nunca dentro del `synchronized(lock)` (que serializa contra `clear()`) |
| Reuso tras FAILED | `build()` lanzó previamente. Nuevo `get(X)` para `X` perteneciente a ese provider | `ProviderAlreadyFailedException(provider::class.simpleName)` | Pre-check fuera del lock o re-check dentro |
| Registro parcial (provider sin META-INF) | `META-INF/services/...FeatureProvider` no incluye el FQN del provider | El provider no se carga. Su `services` no aparecen en `serviceIndex` | `NoProviderFoundException` en el primer `get()` que solicite uno de sus servicios |
| `services` sin coincidencia con map de `build()` | `services = { A, B }` pero `build()` retorna sólo `{ A: ... }` | `ServiceNotAvailableException(B, ProviderClass)` | En `get(B)`, tras `ensureBuilt` exitoso, al re-leer `resolvedServices[B]` y encontrar null |
| Tipo incompatible publicado | `services` declara `Foo::class.java` pero `build()` retorna un objeto que no implementa `Foo` | `ServiceCastException("Foo", ClassCastException)` | En el `castOrThrow` al final de `get()` |
| `build()` lanza `IllegalArgumentException` | Config inválida pasada al constructor del servicio | `ProviderBuildException(provider, cause = IllegalArgumentException)`. Provider marcado FAILED. | En el `try` del `ensureBuilt`, capturado por el `catch (t: Throwable)` |

---

## 12. Persistencia y lifecycle

### 12.1 Persistent providers

Un `FeatureProvider` con `override val persistent = true` recibe trato
especial:

- **Dedup en `register()`:** `persistentByClass.putIfAbsent(provider::class, provider)`.
  Si ya hay una instancia persistente del mismo `KClass`, la nueva se descarta
  y se reusan los `services` de la existente. Garantiza que `ServiceLoader`
  recargando providers en cada `init()` no haga crecer indefinidamente el set
  de persistentes.
- **No-clear en `clear()`:** sus servicios sobreviven a `shutdown()` y siguen
  disponibles tras un nuevo `init()`. Caso típico: logger con file handles
  abiertos o buffers pendientes.
- **No cuentan en `nonPersistentBuiltCount`:** la métrica `builtFeatureCount`
  expuesta al consumidor refleja sólo features con lifecycle SDK.

### 12.2 `Resolver.clear()`

Reproducción exacta del cuerpo del método:

```kotlin
fun clear() {
    synchronized(lock) {
        val persistent = persistentByClass.values
        // toKeep: services de providers persistents que YA estaban resueltos
        // (mapNotNull descarta los que están registrados pero nunca llegaron
        // a build() — sus services no aparecen en resolvedServices).
        val toKeep = persistent
            .filter { it in built }
            .flatMap { p -> p.services.mapNotNull { svc ->
                resolvedServices[svc]?.let { svc to it }
            } }
            .toMap()

        serviceIndex.clear()                          // reset del índice
        for (p in persistent) {
            if (p in built) {
                for (svc in p.services) serviceIndex[svc] = p
            }
        }

        built.retainAll(persistent.toHashSet())       // drop non-persistents
        resolvedServices.clear()
        resolvedServices.putAll(toKeep)               // reinyecta las preservadas
        failedProviders.clear()                       // fresh start tras shutdown
        nonPersistentBuiltCount.set(0)
    }
}
```

`buildingProviders` no se toca explícitamente: sus entradas viven sólo
mientras un thread está dentro del `try` de `ensureBuilt`. Si `clear()` corre
concurrente con un `ensureBuilt` activo, el `finally` del thread perdedor
drena su propia entrada al desenrollar.

### 12.3 Qué se elimina, qué no

| Estado en el Resolver | Sobrevive `clear()` |
|---|---|
| Persistent providers (la instancia del provider en `persistentByClass`) | Sí |
| Servicios de persistent providers (en `resolvedServices`) | Sí |
| Non-persistent providers (referencia en `serviceIndex`) | No |
| Servicios non-persistent (en `resolvedServices`) | No |
| `built` set | Sólo persistents conservan su entrada |
| `failedProviders` set | Drenado completo |
| `buildingProviders` set | No tocado (sólo se drena por `finally` del thread activo) |
| `nonPersistentBuiltCount` | Reset a 0 |
| Sintéticos (Context, Config) registrados por la wiring | **No** sobreviven (no son persistent por default). La wiring los re-registra en cada `init()` con valores nuevos. |

### 12.4 Impacto en memoria real

- Un `Resolver` vacío ocupa ~7 HashMaps + 1 Object (~1.5 KB heap base).
- Cada non-persistent registrado: 1 entry en `serviceIndex` (~64 B) + N entries
  por servicio publicado.
- Tras `clear()`, ese espacio se libera. Las instancias publicadas en
  `resolvedServices` quedan elegibles para GC (siempre que la app no haya
  retenido referencias externas).
- Persistent providers retienen sus instancias entre ciclos. Si tienen
  recursos nativos (file descriptors, threads), el contrato exige que cierren
  vía un mecanismo propio (no hay hook del SDK para "destruir persistents").

### 12.5 Leaks posibles

| Escenario | Leak | Mitigación |
|---|---|---|
| App retiene `EncryptionApi` directamente y llama `shutdown()` | La instancia sigue viva tras shutdown; la app no debería usarla pero técnicamente puede | El consumidor es responsable: liberar referencias antes de `shutdown()` |
| Provider persistent registra listeners en `Context` y nunca los desregistra | Context retenido | El feature debe desregistrar en su lógica interna; el SDK no fuerza un hook |
| Test que llama `init()` repetido sin `shutdown()` | `IllegalStateException("already initialized")`, no leak | Garantía del facade |

---

## 13. Rol de Dagger

### 13.1 No es core

Dagger **no participa** en el Resolver ni en la jerarquía de excepciones. El
Resolver es 100% Kotlin estándar (`HashMap`, `ConcurrentHashMap`,
`synchronized`).

### 13.2 Implementación interna del feature

Cada `feature-X-impl` puede usar Dagger internamente para construir su
servicio dentro de `build()`:

```kotlin
override fun build(resolver: Resolver): Map<Class<*>, Any> {
    val component = DaggerEncComponent.factory().create(
        crypto = resolver.get(CoreCryptoApi::class.java),
        config = resolver.get(SdkConfig::class.java),
    )
    return mapOf(EncryptionApi::class.java to component.encryption())
}
```

Esto es decisión interna del feature: puede usar Dagger, kotlin-inject, Koin o
constructores manuales. El Resolver no lo nota.

### 13.3 Reemplazable

Sustituir Dagger por kotlin-inject o constructores manuales en un feature
afecta sólo a ese feature-impl. El resto del sistema no cambia. La variante
`Flavor` permite que coexistan implementaciones distintas en el mismo
classpath: el wiring filtra la suya.

### 13.4 Invisible al Resolver

El Resolver maneja `Class<*>` y `Any`. Nunca consulta si una instancia fue
generada por Dagger, KSP, o new. Eso garantiza que Dagger pueda evolucionar
(o ser sustituido) sin tocar `di-contracts`.

### 13.5 Verificación en compile time

Cada `feature-X-impl` que use Dagger valida su grafo interno en compile time
(KSP). Errores como "no provider for `Encryption.SecretKey`" salen del
compilador del feature, no del SDK. **Independencia de validación entre
módulos.**

---

## 14. Extensibilidad

### 14.1 Añadir un feature real

Procedimiento de la sección 9. Costes:

- 1 módulo nuevo de Gradle (~200-500 LOC dependiendo del feature).
- 1 línea nueva en `META-INF/services/...FeatureProvider`.
- 1 línea `runtimeOnly(project(":features:feature-X-impl"))` en `:sdk:integration`.
- 0 líneas en el `Resolver`, en el facade, o en cualquier otro feature.

### 14.2 Impacto en classpath

Cada feature añade su descriptor a `META-INF/services/...FeatureProvider`. Los
descriptores se mergean automáticamente en el APK final. Sin cambios en
ProGuard/R8 si las keep rules del módulo `:sdk:di-contracts` están aplicadas
(documentado en su `consumer-rules.pro`).

### 14.3 Constraints runtime

- **Sin hot reload de features.** Una vez `init()` completa, el set de
  providers está fijo hasta `shutdown()`. No hay API para "registrar feature
  Y a las 18:00 aunque no estaba en el classpath".
- **Sin override post-init.** No hay forma de reemplazar `serviceIndex[X] = ProviderA`
  por `ProviderB` sin un ciclo `shutdown()` + nuevo classpath + `init()`.
- **Constructor sin args es obligatorio.** Si un feature requiere
  configuración, el patrón es: el provider se construye sin args; en `build()`
  obtiene la config vía `resolver.get(SdkConfig::class.java)`.

### 14.4 No hot reload — implicación

Los features son estáticos en runtime. Variar features por tipo de usuario,
flag remoto, etc., se resuelve **dentro de cada feature-impl** (su propia
lógica condicional), no añadiendo/quitando providers en runtime.

---

## 15. Testing

### 15.1 Qué se valida realmente

Cubierto en `di-contracts/src/test/`:

| Suite | Tests | Cubre |
|---|---|---|
| `ResolverTest` | 27 | Happy path, cycle detection (directo/3-nodos/self), no provider, build failure + retry, cast failure, service no publicado, persistents, concurrencia (16 hilos), aislamiento de instancias, contrato de jerarquía |
| `AutoServiceRegistryTest` | 18 | Equivalente para el registry alterno (E2): incluye deep chain de 500 (DFS iterativo no produce SOE), ciclo transitivo, dep ya fallado |
| `ServiceRegistryTest` | 7 | Topo-sort eager (Pattern E): cycle detection en registro, missing dep, build failure, cast failure |

Cubierto en benchmark/instrumented (`StressTortureTest`):

- `concurrentShutdown_*` × 16 patrones × 200 rondas = 3,200 carreras
  shutdown/get sin crash inesperado.
- `thunderingHerd_*` (100 threads contention sobre el mismo `get`) sin
  duplicar instancias.
- `concurrentBuild_*` (6 threads pidiendo servicios distintos
  simultáneamente).

### 15.2 Qué NO puede validar

- **Que un feature externo tenga el `META-INF/services` correcto.** Requiere
  un test de integración a nivel del `:sdk:integration` que arranque el SDK y
  consulte cada API esperada.
- **Que un `build()` sea idempotente o sin side effects.** El Resolver no
  inspecciona los efectos de `build()`; sólo invoca y captura excepciones.
- **Que las APIs declaradas en `services` cubran todas las que `build()`
  publica.** Discrepancia → `ServiceNotAvailableException` en el primer
  `get()` que la solicite.

### 15.3 Dependencia runtime contract

El test que demuestra el contrato runtime es:

```kotlin
@Test fun all_declared_services_are_resolvable() {
    MultiModuleSdkH.init(testContext, SdkConfig())
    try {
        for (api in listOf(
            EncryptionApi::class.java, AuthApi::class.java,
            StorageApi::class.java, AnalyticsApi::class.java,
            SyncApi::class.java,
        )) {
            assertNotNull("$api debería resolverse", MultiModuleSdkH.get(api))
        }
    } finally {
        MultiModuleSdkH.shutdown()
    }
}
```

Si pasa: el classpath está correcto, los providers cargan, sus
`build()` no lanzan, sus `services` cubren las APIs. Si falla: la causa es la
excepción tipada que recibe el test — diagnóstico inmediato.

---

## 16. NON-GOALS

El sistema **no pretende** estos atributos. Cualquier expectativa en su contra
es un malentendido del contrato.

### 16.1 Graph-based DI

No hay representación explícita del grafo de dependencias accesible al
consumidor (no hay `resolver.dependenciesOf(X)`, no hay export DOT). El grafo
es **implícito** en las llamadas a `resolver.get()` durante `build()`.

### 16.2 Compile-time safety completa

El sistema es runtime-DI. Faltas en el classpath, mismatch entre `services` y
`build()`, ciclos: todo se detecta al ejecutar. Mitigación: tests de
integración en CI.

### 16.3 Deterministic global build

`init()` no construye ningún provider. Cuando ocurre el primer `get()`, el
orden de construcción sigue el patrón de las llamadas a `resolver.get()` en
la cascada. **No hay garantía de que dos rutas distintas hacia el mismo
servicio produzcan el mismo orden** (sólo que producen la misma instancia).

### 16.4 Cycle-safe DI

Los ciclos se **detectan**, no se **resuelven**. Detectar un ciclo lanza
`CircularDependencyException`; no hay reordenación automática ni proxies para
cortar el ciclo. La responsabilidad es del autor del feature.

### 16.5 Scoped DI framework

No hay scopes (`@Singleton`, `@RequestScoped`, etc.) gestionados por el
Resolver. Cada provider gestiona la vida de sus servicios internamente;
desde fuera, una instancia publicada en `resolvedServices` es **singleton
implícito** durante el ciclo del SDK (entre `init()` y `shutdown()`). Para
otros scopes el feature debe componer sus propios mecanismos.

---

## 17. Performance

Mediciones en Samsung Galaxy S22 Ultra (Snapdragon 8 Gen 1, Android 16,
Jetpack Benchmark 1.4.0).

### 17.1 Init complexity

`init()` ≈ O(P + Σ services_p) donde P = número de providers descubiertos.

| Métrica | Valor S22 Ultra |
|---|---|
| `init()` con 6 features (Pattern H, Dagger flavor) | ~104 µs |
| Coste dominante | ServiceLoader scan (~70 µs) + N `resolver.register()` (~5 µs cada uno) |
| Allocation | 1 `SyntheticFeatureProvider` + N instancias `FeatureProvider` reflexivas |

### 17.2 First get cost

`get(X)` con `X` no construido = O(deps de X) llamadas a `ensureBuilt`.

| Escenario | Coste medido |
|---|---|
| `get(Analytics)` (sin deps) | ~1.7 µs (build directo) |
| `get(Sync)` (cascada Sync→Auth+Stor→Enc→Core) | ~6.8 µs |
| `get(NetworkClient)` del trace ejemplo (cadena Net→Sec→Core) | ~5-8 µs (depende de coste de cada `build()`) |

### 17.3 Warm get cost

`get(X)` con `X` ya en `resolvedServices`:

| Métrica | Valor |
|---|---|
| Fast-path completo | ~30-40 ns (ConcurrentHashMap.get + Class.cast + try/catch overhead) |
| Coste atribuible a la API tipada (vs runtime errors no tipados) | +10 ns inherentes al `try/catch` alrededor de `Class.cast` |

### 17.4 DFS overhead real

| Profundidad | Overhead vs build directo |
|---|---|
| 1 (no deps) | 0 (igual al `build()` puro) |
| 5-10 (cascada típica) | ~2-3 µs total para todo el cascade (synchronized reentrante + check sets) |
| 100+ (no medido en producción) | Linear; no hay degradación cuadrática |

### 17.5 Stack depth risk

`Resolver.ensureBuilt` es **recursivo** por diseño: cada `resolver.get()`
anidado dentro de un `build()` añade frames al stack del thread llamante
(varios por nivel: `get`, `ensureBuilt`, `synchronized`, `build`). Una cadena
de profundidad N consume del orden de N×k frames JVM, donde k es el factor
constante de la implementación.

En grafos realistas de SDK (típicamente ≤10 niveles), el riesgo es nulo — el
stack JVM de Android admite varios miles de frames antes de
`StackOverflowError`. La única ruta que **anteriormente** producía
profundidades arbitrarias eran los ciclos: la introducción del set
`buildingProviders` los detecta y los reporta como
`CircularDependencyException` antes de profundizar.

Para grafos extraordinariamente profundos (cientos de niveles), el patrón
alterno **`AutoServiceRegistry`** (Pattern E2) usa un DFS iterativo con stack
explícito (`ArrayDeque`) y soporta profundidades muy superiores sin riesgo
de SOE. Validado en su test suite con depth=500.

---

## 18. Invariantes del sistema

Propiedades que siempre se cumplen mientras el código de `Resolver` no se
modifique. Cualquier cambio que viole alguna es un bug.

### 18.1 Singleton por `Class`

Para una instancia de `Resolver`, dada una `clazz: Class<T>`, todo `get(clazz)`
exitoso devuelve la **misma referencia** entre `init()` y `shutdown()`. La
identidad se preserva incluso bajo race condition: el doble check + el
ordenamiento `built.add(provider)` LAST garantizan single construction.

### 18.2 Provider built una vez

Para un provider P entre dos `init()` consecutivos, `P.build()` se invoca
**exactamente una vez** (cero veces si nadie pide sus servicios). Si `build()`
lanza, P se marca FAILED y futuras `get()` lanzan
`ProviderAlreadyFailedException` sin reinvocar `build()`.

### 18.3 FAILED es terminal (sin clear)

Una vez `provider ∈ failedProviders`, ninguna `get()` reinvoca su `build()`.
Sólo `clear()` (vía `shutdown()`) lo saca de ese set.

### 18.4 DFS precedence local guarantee

Cuando `build(X)` llama a `resolver.get(Y)`, la resolución de `Y` (incluida
toda su cascada) **completa antes** de que el `build(X)` original avance. Eso
garantiza que `X` puede asumir que `Y` está totalmente disponible al usarlo.

### 18.5 `resolvedServices` source of truth

La instancia que el caller recibe siempre proviene de `resolvedServices`, no
del map devuelto por `build()`. Eso permite que `build()` retorne objetos
nuevos, pero el Resolver garantiza unicidad pasando siempre por la cache.

### 18.6 Ausencia de provider = ausencia de feature

Si un feature module no aparece en el classpath runtime, su provider no se
registra, sus servicios no entran en `serviceIndex`, y todo `get()` sobre
ellos lanza `NoProviderFoundException`. **No existe estado intermedio "feature
parcialmente disponible".**

---

## REGLA FINAL

Cualquier cambio que se aplique al SDK debe mantener el documento
**internamente consistente**. Antes de mergear, validar:

- **Consistencia interna.** Las secciones no se contradicen entre sí. Si la
  sección 5 dice "fast-path no toma lock" y la sección 6 dice lo contrario,
  el documento miente.
- **Ejecutable mentalmente paso a paso.** Un lector debería poder simular el
  trace de la sección 5 línea por línea y obtener el mismo resultado que
  daría la JVM al ejecutarlo.
- **Fiel al runtime real.** Cada nombre de campo, método, excepción, set, set
  son los del código fuente. Sin renombrados ni simplificaciones para "clarity".
- **Sin contradicciones entre trace, código y explicación.** El trace de la
  sección 5 debe poder mapearse línea a línea contra el código de
  `Resolver.kt` y `MultiModuleSdkH.kt`.

Si estas cuatro propiedades no se cumplen, el documento **no es la fuente de
verdad** que pretende ser.
