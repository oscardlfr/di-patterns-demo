# SDK Consumption Guide for Apps

> Cómo una aplicación Android integra el SDK multi-módulo y reparte el
> acceso entre sus propios módulos sin acoplar la maquinaria interna del SDK.

**Audiencia.** Equipos que consumen el SDK desde una aplicación Android. No
es documentación interna del SDK (ver `sdk-internal-architecture.md`).

---

## 1. Modelo de dependencias objetivo

### 1.1 Diagrama de Gradle

```
┌──────────────────────────────────────────────────────────────────┐
│  :app                                                             │
│   • Application class (init / shutdown del SDK)                   │
│   • Composition root: Dagger AppComponent                         │
│   • Provee la instancia del SDK al resto del grafo de la app      │
│                                                                   │
│   dependencies {                                                  │
│     implementation(project(":sdk:integration"))   ← wiring real   │
│     implementation(project(":app:core"))                          │
│     implementation(project(":app:feature-checkout"))              │
│     ...                                                           │
│   }                                                               │
└──────────────────────────────────────────────────────────────────┘
                       │
              ┌────────┴────────┐
              ▼                 ▼
┌──────────────────────┐  ┌──────────────────────────────────────┐
│  :sdk:integration    │  │  :app:feature-checkout (módulo app)  │
│  (Pattern H wiring)  │  │   • UI + lógica de feature           │
│                      │  │   • Inyecta APIs del SDK             │
│  contiene:           │  │                                       │
│   • MultiModuleSdkH  │  │   dependencies {                     │
│     (facade real)    │  │     implementation(project(":sdk:api"))   │
│   • SdkProviderImpl  │  │     // NO :sdk:integration            │
│     (impl que llama  │  │   }                                  │
│     a MultiModuleSdkH│  └──────────────────────────────────────┘
│     internamente)    │                          │
│                      │                          ▼
│  depends:            │  ┌──────────────────────────────────────┐
│   :sdk:di-contracts  │  │  :sdk:api                            │
│   :sdk:api           │  │   • EncryptionApi, AuthApi, ...      │
│   runtimeOnly(features) │ • SdkConfig                          │
│                      │  │   • SdkProvider (INTERFAZ)           │
│                      │  │   • Cero implementación              │
└──────────────────────┘  └──────────────────────────────────────┘
```

### 1.2 Reglas duras

| Regla | Razón |
|---|---|
| **Sólo `:app` depende de `:sdk:integration`** | Centraliza el lifecycle; evita que módulos de la app arrastren el wiring concreto |
| **Módulos de la app dependen sólo de `:sdk:api`** | Compile-time isolation: un cambio interno del SDK no recompila feature modules |
| **El SDK se inyecta desde `:app` vía Dagger** | Los módulos no conocen `MultiModuleSdkH.get(...)`; reciben las APIs por constructor |
| **`init()`/`shutdown()` viven en `Application`** | Único componente que conoce el lifecycle del proceso |

### 1.3 Por qué no `:sdk:integration` en cada módulo

Si un módulo `:app:feature-checkout` declarase
`implementation(project(":sdk:integration"))`:

- El módulo arrastra `Resolver`, `ServiceLoader`, providers reflexivos:
  superficie API mucho mayor que la necesaria.
- Cualquier cambio interno del SDK invalida la cache de Gradle del módulo:
  builds incrementales más lentos.
- Tentación de llamar `MultiModuleSdkH.get(...)` directamente desde el módulo:
  acoplamiento al wiring concreto. Cambiar de Pattern H a Pattern E2 (o
  cualquier otro) implicaría tocar todos los módulos.
- Tests del módulo: necesitan inicializar todo el SDK para correr. Con
  `:sdk:api` solamente, los tests del módulo inyectan fakes triviales.

---

## 2. Setup en `:app`

### 2.1 Gradle dependencies

```kotlin
// app/build.gradle.kts
dependencies {
    // SDK wiring concreto (sólo este módulo)
    implementation(project(":sdk:integration"))

    // Módulos de la app
    implementation(project(":app:core"))
    implementation(project(":app:feature-checkout"))
    implementation(project(":app:feature-account"))

    // Dagger / Hilt
    implementation(libs.dagger)
    ksp(libs.dagger.compiler)
    // ...
}
```

### 2.2 Application class

```kotlin
class MyApplication : Application() {

    lateinit var appComponent: AppComponent
        private set

    override fun onCreate() {
        super.onCreate()

        // (1) Inicializa el SDK con el Context de la app
        MultiModuleSdkH.init(
            context = this,
            config  = SdkConfig(debug = BuildConfig.DEBUG),
        )

        // (2) Construye el grafo Dagger pasando el SDK como dependencia
        appComponent = DaggerAppComponent.factory().create(
            application = this,
            sdkProvider = SdkProviderImpl,
        )
    }

    override fun onTerminate() {
        // No siempre se invoca en producción; manejarlo correctamente
        // exige integrarse con el lifecycle de Process en lugar de
        // confiar en onTerminate. Ver sección 6.
        MultiModuleSdkH.shutdown()
        super.onTerminate()
    }
}
```

### 2.3 SdkProvider — la abstracción que se comparte

`SdkProvider` es una interfaz **que el consumidor define como parte de su
arquitectura**. Vive en `:sdk:api` (el módulo de contratos públicos del SDK)
para que cualquier módulo de la app pueda referenciarla sin tirar del
wiring concreto.

```kotlin
// :sdk:api/src/main/kotlin/com/empresa/sdk/api/SdkProvider.kt
package com.empresa.sdk.api

interface SdkProvider {
    fun encryption(): EncryptionApi
    fun auth(): AuthApi
    fun storage(): StorageApi
    fun analytics(): AnalyticsApi
    fun sync(): SyncApi
}
```

Puntos clave:

- Es **una decisión del consumidor**, no algo provisto por el SDK. El SDK
  expone `MultiModuleSdkH` directamente; `SdkProvider` es una capa fina que
  el consumidor añade para poder inyectar el acceso al SDK vía DI sin acoplar
  módulos al wiring.
- Cada método retorna una **interfaz pública** del SDK, no un tipo concreto.
- Los módulos de la app inyectan `SdkProvider` o, preferiblemente, sólo las
  APIs concretas que necesitan (ver sección 4.3).

### 2.4 Implementación en `:sdk:integration`

```kotlin
// :sdk:integration/src/main/kotlin/com/empresa/sdk/integration/SdkProviderImpl.kt
package com.empresa.sdk.integration

import com.empresa.sdk.api.*
import com.grinwich.sdk.wiring.h.MultiModuleSdkH

object SdkProviderImpl : SdkProvider {
    override fun encryption(): EncryptionApi = MultiModuleSdkH.get(EncryptionApi::class.java)
    override fun auth(): AuthApi             = MultiModuleSdkH.get(AuthApi::class.java)
    override fun storage(): StorageApi       = MultiModuleSdkH.get(StorageApi::class.java)
    override fun analytics(): AnalyticsApi   = MultiModuleSdkH.get(AnalyticsApi::class.java)
    override fun sync(): SyncApi             = MultiModuleSdkH.get(SyncApi::class.java)
}
```

- `SdkProviderImpl` es el único punto del proyecto que llama a
  `MultiModuleSdkH.get()`. Todo el resto de la app pasa por la abstracción.
- Si en el futuro se cambia el wiring (por ejemplo a Pattern E2), sólo este
  archivo cambia.

---

## 3. Composition root con Dagger

### 3.1 AppComponent

```kotlin
// :app/src/main/kotlin/com/empresa/app/di/AppComponent.kt
@Singleton
@Component(modules = [SdkBindingsModule::class, AppModule::class])
interface AppComponent {

    fun inject(activity: MainActivity)
    fun checkoutComponent(): CheckoutComponent.Factory
    fun accountComponent(): AccountComponent.Factory

    @Component.Factory
    interface Factory {
        fun create(
            @BindsInstance application: Application,
            @BindsInstance sdkProvider: SdkProvider,
        ): AppComponent
    }
}
```

`@BindsInstance sdkProvider: SdkProvider` recibe la implementación
construida en `Application.onCreate()`. Eso desacopla el componente de
cualquier referencia directa a `MultiModuleSdkH`.

### 3.2 SdkBindingsModule

Provee cada API individual a partir del `SdkProvider`. Esto es lo que permite
que un módulo de feature inyecte `EncryptionApi` directamente sin saber del
`SdkProvider`.

```kotlin
// :app/src/main/kotlin/com/empresa/app/di/SdkBindingsModule.kt
@Module
object SdkBindingsModule {

    @Provides @Singleton
    fun encryption(sdk: SdkProvider): EncryptionApi = sdk.encryption()

    @Provides @Singleton
    fun auth(sdk: SdkProvider): AuthApi = sdk.auth()

    @Provides @Singleton
    fun storage(sdk: SdkProvider): StorageApi = sdk.storage()

    @Provides @Singleton
    fun analytics(sdk: SdkProvider): AnalyticsApi = sdk.analytics()

    @Provides @Singleton
    fun sync(sdk: SdkProvider): SyncApi = sdk.sync()
}
```

Notas:

- Cada `@Provides` está marcado `@Singleton` en el componente raíz: el SDK ya
  garantiza singleton interno, pero declararlo aquí permite que Dagger
  optimice el grafo (no consulta `SdkProvider` repetidamente).
- Este módulo vive en `:app`, **no** en `:sdk:integration` ni en `:sdk:api`.
  Es decisión del consumidor cómo expone cada API a su grafo.

### 3.3 Subcomponents por feature

Cada módulo de feature declara su propio subcomponent con las APIs que
necesita:

```kotlin
// :app:feature-checkout
@Subcomponent(modules = [CheckoutModule::class])
@FeatureScope
interface CheckoutComponent {
    fun inject(fragment: CheckoutFragment)

    @Subcomponent.Factory
    interface Factory {
        fun create(): CheckoutComponent
    }
}

@Module
abstract class CheckoutModule {
    @Binds @FeatureScope
    abstract fun bindCheckoutRepository(impl: CheckoutRepositoryImpl): CheckoutRepository
}
```

`CheckoutFragment` recibe inyectado un `CheckoutRepository`. El repositorio,
por su parte, declara `@Inject constructor(private val auth: AuthApi, ...)`.
Dagger resuelve la cadena: AppComponent → `auth()` → `SdkProvider.auth()` →
`MultiModuleSdkH.get(AuthApi::class.java)`.

---

## 4. Patrones de consumo en módulos de la app

### 4.1 Constructor injection (recomendado)

Lo idiomático con Dagger. Los módulos de feature **no conocen `SdkProvider`**;
reciben directamente la API que necesitan.

```kotlin
// :app:feature-checkout
class CheckoutRepositoryImpl @Inject constructor(
    private val auth: AuthApi,
    private val storage: StorageApi,
    private val analytics: AnalyticsApi,
) : CheckoutRepository {

    override suspend fun submit(order: Order): SubmitResult {
        if (!auth.isAuthenticated()) return SubmitResult.NotAuthenticated
        analytics.event("checkout_submitted")
        storage.put("last_order", order.id)
        return SubmitResult.Ok
    }
}
```

Beneficios:

- El módulo `:app:feature-checkout` declara en su Gradle sólo
  `implementation(project(":sdk:api"))`. No conoce qué wiring usa la app.
- El test del repository pasa fakes triviales:
  `CheckoutRepositoryImpl(FakeAuthApi(), FakeStorageApi(), FakeAnalyticsApi())`.
  Sin Dagger, sin SDK, sin `Application` real.

### 4.2 Field injection (cuando aplique)

En clases controladas por el sistema (Activities, Fragments, Workers), donde
no controlas el constructor:

```kotlin
class CheckoutFragment : Fragment() {
    @Inject lateinit var auth: AuthApi
    @Inject lateinit var checkoutRepository: CheckoutRepository

    override fun onAttach(context: Context) {
        super.onAttach(context)
        (context.applicationContext as MyApplication)
            .appComponent
            .checkoutComponent()
            .create()
            .inject(this)
    }
}
```

### 4.3 Inyección de `SdkProvider` directa (anti-pattern salvo casos justificados)

```kotlin
// EVITAR
class MiClase @Inject constructor(private val sdk: SdkProvider) {
    fun foo() {
        val a = sdk.auth()
        val s = sdk.storage()
    }
}
```

Inyectar `SdkProvider` directamente acopla la clase a la totalidad del
contrato del SDK en lugar de a las APIs específicas que usa. Sólo es
aceptable cuando el componente realmente necesita acceso dinámico a más de
una API y la lista no es estable en tiempo de diseño (raro).

### 4.4 Hilt como alternativa

Si la app usa Hilt en lugar de Dagger puro, la estructura es equivalente:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object SdkBindingsModule {
    @Provides @Singleton fun sdkProvider(): SdkProvider = SdkProviderImpl
    @Provides @Singleton fun encryption(sdk: SdkProvider): EncryptionApi = sdk.encryption()
    @Provides @Singleton fun auth(sdk: SdkProvider): AuthApi = sdk.auth()
    // ...
}

@HiltAndroidApp
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MultiModuleSdkH.init(this, SdkConfig(debug = BuildConfig.DEBUG))
    }
}
```

La inyección en Activities/Fragments la maneja Hilt vía `@AndroidEntryPoint`.
El acoplamiento de los módulos sigue siendo a `:sdk:api` exclusivamente.

### 4.5 Gestores de dependencias alternativos

Si la app usa Koin, kotlin-inject u otro:

```kotlin
// Koin module en :app
val sdkBindingsModule = module {
    single<SdkProvider> { SdkProviderImpl }
    single<EncryptionApi> { get<SdkProvider>().encryption() }
    single<AuthApi>       { get<SdkProvider>().auth() }
    single<StorageApi>    { get<SdkProvider>().storage() }
    single<AnalyticsApi>  { get<SdkProvider>().analytics() }
    single<SyncApi>       { get<SdkProvider>().sync() }
}
```

El principio es idéntico: `:sdk:integration` se importa sólo en `:app`; el
módulo de DI de la app expone cada API individual; los módulos de feature
inyectan las APIs y nada más.

---

## 5. Estructura recomendada de `:sdk:api`

```
sdk/api/src/main/kotlin/com/empresa/sdk/api/
  SdkConfig.kt
  SdkProvider.kt          ← interfaz superior, opcional
  EncryptionApi.kt
  AuthApi.kt
  StorageApi.kt
  AnalyticsApi.kt
  SyncApi.kt
  models/
    AuthToken.kt
    SyncResult.kt
    StorageBackend.kt
```

`build.gradle.kts` mínimo:

```kotlin
plugins { alias(libs.plugins.android.library) }

android {
    namespace = "com.empresa.sdk.api"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
}

dependencies {
    // CERO dependencias hacia frameworks DI
    // CERO dependencias hacia :sdk:integration o feature-impl
    // Sólo modelos, datos, interfaces
}
```

`:sdk:api` es el contrato compartido. Cualquier dependencia adicional aquí se
propaga a todos los módulos de la app. Mantenerlo limpio es crítico.

---

## 6. Lifecycle del SDK en una Application real

### 6.1 Init

`Application.onCreate()` es el único entry point sensato. Garantías:

- El `Context` está disponible (`this`).
- El proceso acaba de arrancar — no hay races con código previo.
- Cualquier inicialización lazy (Dagger graph, WorkManager configuration,
  etc.) puede asumir que el SDK está listo.

### 6.2 Shutdown

`Application.onTerminate()` **no es invocado en producción** en Android (sólo
en emuladores). El SDK no necesita shutdown determinista en una app que vive
hasta que el SO mata el proceso. Tres patrones:

#### Patrón A: no shutdown explícito (recomendado para apps)

El proceso muere → todo se libera → el SDK no necesita liberar nada
manualmente. Persistent providers (logger, etc.) cierran sus recursos en su
propia lógica usando `ProcessLifecycleOwner` o `ContentProvider.onCreate`.

#### Patrón B: shutdown en logout / cambio de cuenta

Si el SDK contiene estado per-usuario (tokens, cache cifrada con clave
distinta), el logout puede requerir reinicialización:

```kotlin
fun logout() {
    MultiModuleSdkH.shutdown()
    MultiModuleSdkH.init(application, SdkConfig(userId = null))
    // Reconstruir el grafo Dagger es opcional; las APIs siguen siendo las mismas.
}
```

#### Patrón C: shutdown en tests

```kotlin
@After fun tearDown() {
    MultiModuleSdkH.shutdown()  // limpia entre tests
}
```

### 6.3 Race shutdown ↔ get

Cubierto por el SDK: el facade envuelve `resolver.get()` en un `try/catch
DependencyResolutionException`. Cuando la excepción de dominio coincide con
un shutdown concurrente (`_initialized` ya es `false` al entrar al catch), se
remapea a `IllegalStateException("not initialized")` con la causa original
preservada en `cause`.

**Contrato específico de la race:** si la app llama `get()` mientras otro
thread ejecuta `shutdown()`, la única excepción visible es
`IllegalStateException`. No verá `NoProviderFoundException` ni
`ServiceNotAvailableException` aunque internamente sí ocurran.

**Fuera de esta race**, las excepciones de dominio pueden propagarse tal cual
al caller: una `NoProviderFoundException` por una feature ausente del
classpath, una `ProviderBuildException` por un fallo real de configuración,
etc. Esos errores son legítimos y la app debe manejarlos según corresponda.

---

## 7. Test de integración mínimo

```kotlin
// :app/src/androidTest/kotlin/com/empresa/app/SdkBindingsTest.kt
class SdkBindingsTest {

    @Before fun setUp() {
        MultiModuleSdkH.init(
            ApplicationProvider.getApplicationContext(),
            SdkConfig(debug = true),
        )
    }

    @After fun tearDown() {
        MultiModuleSdkH.shutdown()
    }

    @Test fun all_apis_resolve_through_provider() {
        val provider: SdkProvider = SdkProviderImpl
        assertNotNull(provider.encryption())
        assertNotNull(provider.auth())
        assertNotNull(provider.storage())
        assertNotNull(provider.analytics())
        assertNotNull(provider.sync())
    }

    @Test fun dagger_graph_resolves_individual_apis() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val component = DaggerAppComponent.factory().create(app, SdkProviderImpl)
        // Si alguno de los @Provides está roto, esto lanza al construir el componente.
        assertNotNull(component)
    }
}
```

> Si la app usa Robolectric, el mismo test puede vivir en `src/test/` con
> `@RunWith(AndroidJUnit4::class)` y `@Config` apropiado. La diferencia
> material es de dónde sale el `Context`; el resto del test no cambia.

Cubre:

- El classpath runtime tiene los feature-impl correctos
  (`SdkProviderImpl.encryption()` falla con `NoProviderFoundException` si
  falta `feature-enc-impl`).
- El módulo Dagger `SdkBindingsModule` está bien declarado (un `@Provides`
  con la firma errónea o un import faltante rompe el compile-time).

---

## 8. Tabla resumen de responsabilidades

| Componente | Sabe del SDK | Importa | Responsabilidad |
|---|---|---|---|
| `:app` | sí, todo | `:sdk:integration`, `:sdk:api`, módulos de feature de la app | Lifecycle (init/shutdown). Configura Dagger AppComponent. Provee SdkProvider. |
| `:app:feature-X` | sólo la API que usa | `:sdk:api` | Implementa el feature usando inyección por constructor. Cero conocimiento del wiring. |
| `:sdk:integration` | implementación interna | `:sdk:di-contracts`, `:sdk:api`, runtimeOnly(`:features:feature-*-impl`) | Expone `MultiModuleSdkH` y `SdkProviderImpl`. |
| `:sdk:api` | contrato | nada | Interfaces y modelos públicos. |
| `:features:feature-X-impl` | la API que publica | `:sdk:di-contracts`, `:feature-X-api`, otras `:feature-Y-api` que necesite | Contribuye un `FeatureProvider` al SDK. |

---

## 9. Anti-patterns

### 9.1 Importar `:sdk:integration` en módulos de feature

```kotlin
// :app:feature-checkout/build.gradle.kts — INCORRECTO
implementation(project(":sdk:integration"))
```

**Por qué falla:** acopla el módulo al wiring concreto. Tests más lentos.
Builds incrementales más lentos. Imposible cambiar de Pattern H a otro
sin tocar todos los módulos.

### 9.2 Llamar `MultiModuleSdkH.get(...)` desde código de feature

```kotlin
// :app:feature-checkout — INCORRECTO
class CheckoutRepositoryImpl {
    private val auth = MultiModuleSdkH.get(AuthApi::class.java)
}
```

**Por qué falla:**
- Acoplamiento al singleton `MultiModuleSdkH` (Pattern H específico).
- Test del repositorio requiere inicializar todo el SDK.
- Si `MultiModuleSdkH` no está inicializado al construir la clase, lanza
  `IllegalStateException`. La inyección por constructor lo evita
  porque el grafo Dagger coordina la inicialización.

### 9.3 `init()` fuera de `Application.onCreate()`

```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MultiModuleSdkH.init(this, SdkConfig())  // INCORRECTO
    }
}
```

**Por qué falla:**
- Race con cualquier otro código que se ejecute antes de la `Activity` (BroadcastReceivers, ContentProviders).
- El `Context` de la `Activity` no es el `applicationContext` y puede causar
  leaks si algún provider lo retiene.
- Si la app tiene varias `Activities` que pueden ser entry points, el
  `_initialized` check del facade rechaza llamadas posteriores.

### 9.4 Múltiples instancias de `SdkProvider`

```kotlin
// INCORRECTO: bypass de Dagger
val provider1 = SdkProviderImpl
val provider2: SdkProvider = object : SdkProvider {
    override fun encryption() = MultiModuleSdkH.get(EncryptionApi::class.java)
    // ...
}
```

**Por qué falla:** ambos resuelven la misma instancia interna del SDK
(garantía del Resolver), pero introduce dos rutas paralelas al SDK que el
diseño quiere centralizar en una. Mantenerlo simple: `SdkProviderImpl` es
`object`, se inyecta vía `@BindsInstance`, fin.

---

## 10. Checklist de integración

Antes de mergear el primer PR que integra el SDK:

- [ ] `:sdk:integration` añadido sólo a las dependencias de `:app`.
- [ ] `:sdk:api` añadido a las dependencias de cada módulo de feature que use
      las APIs.
- [ ] `Application.onCreate()` llama `MultiModuleSdkH.init(this, SdkConfig(...))`.
- [ ] `SdkProviderImpl` (o equivalente) está implementado y registrado vía
      `@BindsInstance` en `AppComponent.Factory.create(...)`.
- [ ] `SdkBindingsModule` (o equivalente) provee cada API que la app consume,
      delegando en `SdkProvider`.
- [ ] Ningún archivo de un módulo de feature importa `MultiModuleSdkH`,
      `Resolver`, `FeatureProvider` ni cualquier tipo de
      `:sdk:integration` o `:sdk:di-contracts`.
- [ ] Los tests de cada módulo de feature pasan fakes de las APIs (no
      arrancan el SDK real).
- [ ] Test de integración en `:app` que llama `init()` y resuelve cada API
      esperada.
- [ ] Si la app usa logout / cambio de usuario: política de
      shutdown/reinit documentada.
