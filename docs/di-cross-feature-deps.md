# Dependencias Cruzadas: Ejemplos Concretos

¿Qué pasa cuando las features dentro de un SDK necesitan servicios de otras features?
Cada approach de DI responde de forma diferente.

Para approaches Dagger, ver [dagger2-sdk-selective-init.md](dagger2-sdk-selective-init.md).
Para conceptos DI, ver [di-sdk-consumer-isolation.md](di-sdk-consumer-isolation.md).
Para el approach hybrid, ver [di-hybrid-koin-sdk-dagger-app.md](di-hybrid-koin-sdk-dagger-app.md).
Para análisis multi-módulo api/impl/integration, ver [di-multimodule-api-impl-analysis.md](di-multimodule-api-impl-analysis.md).

---

## El Escenario

El SDK de este proyecto tiene 5 features con estas dependencias:

```
Encryption ← Auth (necesita cifrar contraseñas)
Encryption ← Storage (necesita cifrar datos)
Encryption + Auth + Storage ← Sync (necesita todo para sincronizar)
Analytics (independiente — solo necesita logger)
```

En el proyecto real, `DefaultSyncApi` recibe `AuthApi`, `StorageApi` y `EncryptionApi`
por constructor.

---

## Grafo Único: Resolución Automática

**Funciona en:** Dagger Monolítico (A), Koin, Dagger D (multi-módulo), Dagger E (multi-módulo)

Todos los servicios están en UN contenedor (o registry). Cualquier servicio puede pedir cualquier otro.

### Koin (`sdk/impl-koin/KoinSdk.kt`)

```kotlin
// Cada módulo declara qué provee y qué necesita.
// get() resuelve en runtime desde el grafo compartido.

object EncryptionRegistration : SdkModuleRegistration {
    override val koinModule = module {
        single<HashApi> { DefaultHashApi() }
        single<EncryptionApi> { DefaultEncryptionApi(get()) }
    }
}

object AuthRegistration : SdkModuleRegistration {
    override val koinModule = module {
        // Cross-feature: Auth obtiene EncryptionApi del mismo grafo
        single<AuthApi> { DefaultAuthApi(get(), get()) }
    }
}

object SyncRegistration : SdkModuleRegistration {
    override val koinModule = module {
        // Cross-feature pesado: necesita Auth + Storage + Encryption
        single<SyncApi> { DefaultSyncApi(get(), get(), get(), get()) }
    }
}
```

Koin ve el conjunto completo de `single<>` y resuelve la cadena:
SyncApi necesita AuthApi → encontrado en AuthRegistration.
AuthApi necesita EncryptionApi → encontrado en EncryptionRegistration.

### Dagger D — Component Dependencies (multi-módulo: `sdk/sdk-wiring/`)

```kotlin
// feature-auth-impl — Auth depende de Core + Encryption vía provision interfaces
@Component(
    dependencies = [CoreProvisions::class, EncProvisions::class],
    modules = [AuthModule::class],
)
interface AuthComponent : AuthProvisions {
    override fun auth(): AuthApi
}

// feature-syn-impl — Sync depende de Core + Enc + Auth + Storage
@Component(
    dependencies = [CoreProvisions::class, EncProvisions::class,
                    AuthProvisions::class, StorProvisions::class],
    modules = [SynModule::class],
)
interface SynComponent : SynProvisions {
    override fun sync(): SyncApi
}
```

Dagger ve `dependencies=[EncProvisions]` y resuelve `EncryptionApi` desde
`EncProvisions.encryption()` automáticamente. Sin CoreApis, sin wiring manual.

### Dagger E — Component Registry (multi-módulo: `sdk/wiring-e/`)

```kotlin
// Misma jerarquía de Components que D, pero registrados vía FeatureEntry
internal val syncEntry = FeatureEntry(
    componentClass = SynComponent::class.java,
    dependencies = setOf(CoreProvisions::class.java, EncProvisions::class.java,
                         AuthProvisions::class.java, StorProvisions::class.java),
    build = { registry ->
        DaggerSynComponent.builder()
            .core(registry.component(CoreProvisions::class.java))
            .enc(registry.component(EncProvisions::class.java))
            .auth(registry.component(AuthProvisions::class.java))
            .storage(registry.component(StorProvisions::class.java))
            .build()
    },
    services = { comp -> mapOf(SyncApi::class.java to comp.sync()) },
)
```

Dagger E usa `dependencies=[...]` igual que D para resolver cross-deps.
La diferencia es que el registry gestiona el orden de registro (topo-sort)
y desacopla el facade de los builders DaggerXxx.

---

## Per-Feature: Wiring Manual Necesario

**Funciona en:** Dagger Per-Feature (B), Dagger + ServiceLoader (C)

Cada feature tiene su PROPIO `DaggerComponent`. No se ven entre sí.

### Dagger B (`sdk/impl-dagger-b/`)

```
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│ AuthComponent│    │StorageCompon.│    │EncryptionCom.│
│  @Singleton  │    │  @Singleton  │    │  @Singleton  │
│              │    │              │    │              │
│ AuthApi  │    │SecureStorage │    │EncryptionSvc │
│  needs EncSvc│    │  needs EncSvc│    │              │
│  ← ¿DÓNDE?  │    │  ← ¿DÓNDE?  │    │              │
└──────┬───────┘    └──────┬───────┘    └──────────────┘
       │                   │
       └──────────┬────────┘
                  ↓
          ┌──────────────┐
          │   CoreApis   │  ← interfaz Kotlin plana, NO Dagger
          └──────────────┘
```

Auth necesita EncryptionApi, pero está en otro Component.
Solución: extender CoreApis con el servicio necesario:

```kotlin
// Interfaz extendida — crece con cada cross-dep
interface AuthCoreApis : CoreApis {
    val encryptionService: EncryptionApi
}

// El facade SDK conecta manualmente:
fun getOrInitAuth(): AuthComponent {
    val enc = getOrInitEncryption()
    val authCore = AuthCoreApisImpl(core, enc.encryption())
    return DaggerAuthComponent.builder().core(authCore).build()
}
```

Para Sync (que necesita Auth + Storage + Encryption):

```kotlin
interface SyncCoreApis : CoreApis {
    val authService: AuthApi
    val storageService: StorageApi
    val encryptionService: EncryptionApi
    // ← cada campo es una cross-dep manual
}
```

**El problema a escala:** Con 15+ servicios compartidos, SyncCoreApis conoce
todo el SDK — es un God Object que anula el propósito del aislamiento per-feature.

---

## Resumen

| Approach | Cross-feature | Mecanismo | Limitación |
|----------|--------------|-----------|-----------|
| **Dagger A** (1 Component) | ✅ Automático | Mismo grafo @Component | Todo compilado en binario |
| **Dagger D** (multi-módulo) | ✅ Automático | `dependencies=[ProvisionInterface]` | `when` blocks crecen linealmente |
| **Dagger E** (multi-módulo) | ✅ Automático | `dependencies=[...]` + registry topo-sort | Registry overhead (~20 ns/lookup) |
| **Dagger E2** (multi-módulo) | ✅ Automático | `dependencies=[...]` + DFS on-demand | ~25 ns/lookup, API más simple |
| **Dagger G** (multi-módulo) | ✅ Automático | Factory functions reciben provision interfaces | ensure*() no escalan (= D) |
| **Dagger H** (multi-módulo) | ✅ Automático | FeatureProviders + DFS vía `resolver.provision()` | ~3,5x más lento init que G, wiring inmutable |
| **Koin** (1 koinApplication) | ✅ Automático | `get()` desde el mismo grafo | Resolución runtime |
| **Dagger B** (monolítico) | ⚠️ Manual | CoreApis extendido | God Object a escala |
| **Dagger C** (monolítico) | ⚠️ Manual | ServiceResolver runtime | God Object + JVM only |

**Conclusión práctica:** Si las features dependen unas de otras, un grafo único
(Dagger A, D, E, E2, G, H o Koin) resuelve todo automáticamente. Per-feature monolítico (B, C)
funciona bien cuando las features son verdaderamente independientes.
D, E, E2, G y H solo existen como variantes multi-módulo con provision interfaces.
E2 es la evolución de E para escalar a 50+ módulos: auto-init on `get<T>()`, sin Feature enum.
G = D con factory functions (Components `internal`, mejor encapsulamiento, misma limitación de escalabilidad).
H = G con auto-discovery: cada feature declara un FeatureProvider (~8 líneas), el resolver
construye dependencias vía DFS (`resolver.provision()`). El wiring module es inmutable.
Indicado para equipos grandes (10+) donde zero edición central es prioritario.

### Multi-módulo: Provision Interfaces

En las variantes multi-módulo (sdk-wiring, wiring-e, wiring-e2, wiring-g, wiring-h), las dependencias
cruzadas se resuelven mediante **provision interfaces** — contratos Kotlin planos
que exponen los servicios de una feature sin acoplar al `@Component` concreto.

Cada feature-impl depende de contratos per-feature específicos, NO del umbrella
`di-contracts` ni de otros feature-impl:

```
feature-auth-impl
  ├── depends on: sdk/di-contracts       (EncProvisions, CoreProvisions)
  └── NOT on: feature-enc-impl           ← nunca ve DaggerEncComponent
```

Las provision interfaces (`CoreProvisions`, `EncProvisions`, `AuthProvisions`, etc.)
definen qué servicios expone cada feature. El módulo de wiring es el **único lugar**
que importa `DaggerXxxComponent` y satisface las provision interfaces:

```kotlin
// En sdk-wiring (o wiring-e, wiring-e2) — el único punto que conoce Dagger impls
val encComponent = DaggerEncComponent.builder().core(coreProvisions).build()
// encComponent implementa EncProvisions → visible para feature-auth-impl

// En wiring-g — factory functions, Components internos
val enc = buildEncProvisions(coreProvisions)  // DaggerEncComponent queda internal
val auth = buildAuthProvisions(coreProvisions, enc)  // recibe EncProvisions

// En wiring-h — DFS resolver, wiring inmutable
// Dentro del FeatureProvider de Auth:
val core = resolver.provision(CoreProvisions::class.java)  // DFS auto-build
val enc = resolver.provision(EncProvisions::class.java)    // DFS auto-build
buildAuthProvisions(core, enc)
```

`sdk/di-contracts/` contiene todas las provision interfaces, scopes, `RegistryInfra`,
`FeatureProvider` y `Resolver`. Cada feature-impl depende de este módulo para obtener
las provision interfaces que necesita.

Esto evita el problema de CoreApis (approach B) a escala multi-módulo: en vez de
un God Object que crece con cada cross-dep, cada feature declara provision interfaces
tipadas y Gradle garantiza que solo ve lo que necesita.

Para el análisis completo, ver
[di-multimodule-api-impl-analysis.md](di-multimodule-api-impl-analysis.md).
