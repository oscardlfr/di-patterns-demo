# Arquitectura Hybrid: SDK Koin + App Dagger 2

Cómo construir un SDK con Koin internamente, consumido por apps Android
que usan Dagger 2 — sin conflictos, sin que la app sepa que el SDK usa Koin.

Para approaches Dagger puros, ver [dagger2-sdk-selective-init.md](dagger2-sdk-selective-init.md).
Para conceptos DI, ver [di-sdk-consumer-isolation.md](di-sdk-consumer-isolation.md).
Para dependencias cruzadas, ver [di-cross-feature-deps.md](di-cross-feature-deps.md).

---

## Por Qué Existe Este Patrón

Dos restricciones crean esta situación:

1. **El SDK necesita KMP** (iOS, macOS, Desktop, Android) → Dagger es solo JVM
2. **La app consumidora es Android con Dagger existente** → no va a reescribir a Koin

Pueden coexistir porque:
- Dagger es codegen puro — zero estado global runtime
- Koin 4.x soporta `koinApplication {}` — instancia aislada, NO `startKoin` global

### Contraste con una app todo-Koin

Si la app usa Koin, pasa `appModules` al SDK y todo resuelve en UN `koinApplication`:

```kotlin
// App Koin — todo en un grafo
KoinSdk.init(
    modules = setOf(SdkModule.Encryption.Default),
    config = SdkConfig(debug = false),
    appModules = listOf(dataModule, viewModelModule),  // ← app añade sus módulos
)
val vm: SettingsViewModel = KoinSdk.koin.get()  // resuelto del mismo grafo
```

Una app Dagger **no puede hacer esto** — no tiene módulos Koin que pasar.
Así que `appModules` queda vacío y los dos mundos se conectan por un puente.

---

## Arquitectura

```
┌──────────────────────────────────────────────────────────┐
│                   App Android (Dagger 2)                  │
│                                                           │
│  ┌───────────────────────────┐  ┌───────────────────────┐│
│  │    Grafo Dagger de la app  │  │   SdkBridgeComponent  ││
│  │                            │  │   @Component          ││
│  │  AppRepository ────────────│──▸  @Provides encrypt()  ││
│  │  SettingsViewModel         │  │  @Provides hash()     ││
│  └───────────────────────────┘  └──────────┬────────────┘│
│                                            │              │
│                                   KoinSdk.get<T>()        │
│                                            │              │
│  ┌─────────────────────────────────────────▼────────────┐│
│  │         SDK (Koin — koinApplication aislado)           ││
│  │                                                       ││
│  │  EncryptionModule ── AuthModule ── StorageModule       ││
│  │  FoundationSingletons (logger sobrevive reinit)       ││
│  │                                                       ││
│  │  appModules = vacío  ← la app no pone módulos Koin    ││
│  └───────────────────────────────────────────────────────┘│
└──────────────────────────────────────────────────────────┘
```

Dos contenedores DI completamente separados. El bridge es el único punto de conexión.

---

## Paso 1: Init del SDK (sin appModules)

**Código real del proyecto** — ver `sample-hybrid/HybridApp.kt`:

```kotlin
class HybridApp : Application() {
    lateinit var bridgeComponent: SdkBridgeComponent

    override fun onCreate() {
        super.onCreate()

        // SDK PRIMERO — debe existir antes de que Dagger resuelva el bridge
        KoinSdk.init(
            modules = setOf(SdkModule.Encryption.Default),
            config = SdkConfig(debug = true),
        )

        // Bridge Dagger — conecta servicios del SDK al grafo Dagger de la app
        bridgeComponent = DaggerSdkBridgeComponent.builder().build()
    }
}
```

**Si el orden es incorrecto:** `KoinSdk.koin` lanza `IllegalStateException("KoinSdk not initialized")`
durante la creación del Component Dagger. Crash al arrancar — fácil de diagnosticar.

---

## Paso 2: El Bridge

**Código real del proyecto** — ver `sample-hybrid/di/SdkBridgeModule.kt`:

```kotlin
@Singleton
@Component(modules = [SdkBridgeModule::class])
interface SdkBridgeComponent {
    fun encryption(): EncryptionService
    fun hash(): HashService
}

@Module
class SdkBridgeModule {
    @Provides @Singleton
    fun provideEncryption(): EncryptionService = KoinSdk.get()

    @Provides @Singleton
    fun provideHash(): HashService = KoinSdk.get()
}
```

**Qué pasa en runtime:**
1. Dagger crea `SdkBridgeComponent`
2. Llama a `provideEncryption()` una vez (`@Singleton`)
3. Ese método llama `KoinSdk.get<EncryptionService>()` — Koin resuelve desde su grafo
4. Dagger cachea el resultado
5. Accesos posteriores devuelven la instancia cacheada (~2.8 ns, igual que Dagger puro)

---

## Paso 3: La App (Zero Conocimiento de Koin)

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val bridge = (application as HybridApp).bridgeComponent
        val encryption = bridge.encryption()  // Dagger cached — zero Koin
        encryption.encrypt("hello")
    }
}
```

La Activity no sabe que `EncryptionService` viene de Koin.
Es acceso a un singleton Dagger.

---

## Puente Unidireccional

```
   Grafo Dagger ←──bridge──── Grafo Koin
   (puede inyectar servicios SDK)  (NO puede inyectar servicios app)
```

La app inyecta servicios del SDK vía bridge. El SDK **NO puede** inyectar
servicios de la app — el grafo Koin no conoce los bindings Dagger.

**Si el SDK necesita config de la app:**

```kotlin
// Pasarlo como SdkConfig, no como binding DI
KoinSdk.init(
    modules = setOf(SdkModule.Encryption.Default),
    config = SdkConfig(debug = true, apiBaseUrl = "https://api.example.com"),
)
```

---

## Features Lazy y el Bridge

Las features añadidas con `getOrInitModule()` después de crear el bridge
**no son visibles** vía `SdkBridgeComponent` (`@Singleton` ya cacheó).

Para features lazy, acceder directamente desde `KoinSdk.get()`:

```kotlin
// Lazy — NO pasa por el bridge Dagger
KoinSdk.getOrInitModule(SdkModule.Sync.Default)
val sync = KoinSdk.get<SyncService>()  // directo desde Koin
```

---

## Benchmarks

Del S22 Ultra (30 tests):

| Operación | Hybrid | Koin puro |
|-----------|--------|-----------|
| Init cold | 43.527 ns | 46.606 ns |
| Resolve cached (bridge) | 2,8 ns | 900 ns |
| Cross-feature op | 69.713 ns 🟢 | 118.587 ns 🔴 |

El bridge cached hace que el acceso post-init sea **idéntico a Dagger puro**.

---

## Cuándo Usar

| Escenario | ¿Hybrid? |
|----------|----------|
| SDK es KMP, app es Android con Dagger | ✅ Este patrón |
| SDK es Android exclusivo | Considerar Dagger D para todo |
| App ya usa Koin | No necesita bridge — usar `appModules` |
| Múltiples apps consumen el SDK (unas Dagger, otras Koin) | ✅ Cada app conecta diferente |
| SDK necesita acceso heavy a internos de la app | Reconsiderar — el SDK debería exponer extension points |

---

## Limitaciones

| Limitación | Impacto | Mitigación |
|-----------|--------|------------|
| Dos contenedores runtime | ~100 KB memoria extra (Koin) | Negligible |
| Bridge boilerplate | Un `@Provides` por servicio | 20 servicios = 20 líneas |
| Puente unidireccional | SDK no inyecta bindings app | `SdkConfig` o `appModules` mínimos |
| Orden de init | SDK antes que Dagger | Hacerlo en `Application.onCreate()` — falla rápido si incorrecto |
| Features lazy bypasean bridge | `@Singleton` Dagger ya cacheó | Usar `KoinSdk.get()` directo |
