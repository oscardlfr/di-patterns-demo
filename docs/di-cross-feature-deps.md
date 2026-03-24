# Dependencias Cruzadas: Ejemplos Concretos

¿Qué pasa cuando las features dentro de un SDK necesitan servicios de otras features?
Cada approach de DI responde de forma diferente.

Para approaches Dagger, ver [dagger2-sdk-selective-init.md](dagger2-sdk-selective-init.md).
Para conceptos DI, ver [di-sdk-consumer-isolation.md](di-sdk-consumer-isolation.md).
Para el approach hybrid, ver [di-hybrid-koin-sdk-dagger-app.md](di-hybrid-koin-sdk-dagger-app.md).

---

## El Escenario

El SDK de este proyecto tiene 5 features con estas dependencias:

```
Encryption ← Auth (necesita cifrar contraseñas)
Encryption ← Storage (necesita cifrar datos)
Encryption + Auth + Storage ← Sync (necesita todo para sincronizar)
Analytics (independiente — solo necesita logger)
```

En el proyecto real (`sdk/impl-common/`), `DefaultSyncService` recibe
`AuthService`, `SecureStorageService` y `EncryptionService` por constructor.

---

## Grafo Único: Resolución Automática

**Funciona en:** Dagger Monolítico (A), Koin, Dagger D (Component Dependencies)

Todos los servicios están en UN contenedor. Cualquier servicio puede pedir cualquier otro.

### Koin (`sdk/impl-koin/KoinSdk.kt`)

```kotlin
// Cada módulo declara qué provee y qué necesita.
// get() resuelve en runtime desde el grafo compartido.

object EncryptionRegistration : SdkModuleRegistration {
    override val koinModule = module {
        single<HashService> { DefaultHashService() }
        single<EncryptionService> { DefaultEncryptionService(get()) }
    }
}

object AuthRegistration : SdkModuleRegistration {
    override val koinModule = module {
        // Cross-feature: Auth obtiene EncryptionService del mismo grafo
        single<AuthService> { DefaultAuthService(get(), get()) }
    }
}

object SyncRegistration : SdkModuleRegistration {
    override val koinModule = module {
        // Cross-feature pesado: necesita Auth + Storage + Encryption
        single<SyncService> { DefaultSyncService(get(), get(), get(), get()) }
    }
}
```

Koin ve el conjunto completo de `single<>` y resuelve la cadena:
SyncService necesita AuthService → encontrado en AuthRegistration.
AuthService necesita EncryptionService → encontrado en EncryptionRegistration.

### Dagger D — Component Dependencies (`sdk/impl-dagger-d/`)

```kotlin
// Auth depende de Core + Encryption — Dagger resuelve automáticamente
@Component(
    dependencies = [CoreComponent::class, EncComponent::class],
    modules = [InternalAuthModule::class],
)
internal interface AuthComponent {
    fun auth(): AuthService
}

// Sync depende de Core + Enc + Auth + Storage
@Component(
    dependencies = [CoreComponent::class, EncComponent::class,
                    AuthComponent::class, StorComponent::class],
    modules = [InternalSynModule::class],
)
internal interface SynComponent {
    fun sync(): SyncService
}
```

Dagger ve `dependencies=[EncComponent]` y resuelve `EncryptionService` desde
`EncComponent.encryption()` automáticamente. Sin CoreApis, sin wiring manual.

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
│ AuthService  │    │SecureStorage │    │EncryptionSvc │
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

Auth necesita EncryptionService, pero está en otro Component.
Solución: extender CoreApis con el servicio necesario:

```kotlin
// Interfaz extendida — crece con cada cross-dep
interface AuthCoreApis : CoreApis {
    val encryptionService: EncryptionService
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
    val authService: AuthService
    val storageService: SecureStorageService
    val encryptionService: EncryptionService
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
| **Dagger D** (Component Deps) | ✅ Automático | `dependencies=[Parent]` | Todo compilado en binario |
| **Koin** (1 koinApplication) | ✅ Automático | `get()` desde el mismo grafo | Resolución runtime |
| **Dagger B** (per-feature) | ⚠️ Manual | CoreApis extendido | God Object a escala |
| **Dagger C** (ServiceLoader) | ⚠️ Manual | ServiceResolver runtime | God Object + JVM only |

**Conclusión práctica:** Si las features dependen unas de otras, un grafo único
(Dagger A, Dagger D o Koin) resuelve todo automáticamente. Per-feature (B, C)
funciona bien cuando las features son verdaderamente independientes.
