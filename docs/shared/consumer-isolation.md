# Conceptos de DI para SDKs

Conceptos fundamentales para construir SDKs modulares con inyeccion de dependencias.
Principios agnosticos al framework -- aplica a Dagger, Koin, kotlin-inject o DI manual.

---

## DI vs Service Locator

Dos paradigmas para gestionar dependencias.

### Inyeccion de Dependencias (DI puro)

El framework genera codigo que crea objetos y pasa sus dependencias por constructor.
La clase nunca pide nada:

```kotlin
// La clase -- zero conocimiento de cualquier contenedor DI
class SecurityServiceImpl(
    private val network: NetworkExecutor,
    private val logger: Logger,
) : SecurityService

// El modulo Dagger -- dice a Dagger COMO crear SecurityServiceImpl
@Module class SecurityModule {
    @Provides fun security(network: NetworkExecutor, logger: Logger): SecurityService =
        SecurityServiceImpl(network, logger)
}
```

Si NetworkExecutor no tiene provider, el **build falla** -- no la app.

**Frameworks:** Dagger 2, kotlin-inject

### Service Locator

El framework mantiene un registro. El codigo pregunta al registro en runtime:

```kotlin
val securityModule = module {
    single<SecurityService> { SecurityServiceImpl(get(), get()) }
}
```

`get()` es un lookup runtime. Si nada esta registrado, **crashea en runtime**.

**Frameworks:** Koin, kodein

### Trade-offs

| | DI puro (Dagger, kotlin-inject) | Service Locator (Koin) |
|---|---|---|
| Dependencia faltante | Build falla | App crashea en runtime |
| Validacion del grafo | Compilacion | `checkModules()` en tests |
| Velocidad de build | Mas lento (codegen KSP) | Mas rapido (zero codegen) |
| Soporte KMP | Dagger: solo JVM. kotlin-inject: completo | Completo (JVM + Native + JS) |
| Flexibilidad runtime | Grafo fijo en compilacion | Modulos componibles en runtime |
| Auto-descubrimiento | No posible con Dagger | Si (Class.forName, @EagerInit) |
| Tamano de codigo | Genera clases factory | Zero codigo generado |

---

## Niveles de Aislamiento del Consumidor

Cuanto sabe la app consumidora sobre los internos del SDK?

### Nivel 0 -- Sin aislamiento (anti-patron)

```kotlin
import com.example.sdk.security.internal.SecurityServiceImpl
val service = SecurityServiceImpl(KtorNetworkClient())
```
Si el SDK renombra la clase, el codigo del consumidor se rompe.

### Nivel 1 -- Facade

```kotlin
import com.example.sdk.MySdk
MySdk.init(context, config, setOf(Feature.SECURITY))
val service = MySdk.security()
```
El consumidor no importa SecurityServiceImpl ni ninguna clase Dagger.

### Nivel 2 -- Interfaz + auto-descubrimiento

```kotlin
SharedSdk.init(modules = setOf(SdkModule.Security))
val service: SecurityService = SharedSdk.get()
```
El consumidor nunca importa ningun modulo impl.

### Que logra cada framework

| Framework | Nivel | Por que |
|-----------|-------|---------|
| Dagger Monolitico (A) | 1 | Facade oculta impls |
| Dagger Per-Feature (B) | 1 | Facade por feature |
| Dagger + ServiceLoader (C) | 1-2 | ServiceLoader descubre (JVM only) |
| Dagger D (multi) | 1 | Facade con provision interfaces |
| Dagger E2 (multi) | 1 | API minima: init() + get<T>() |
| Dagger G (multi) | 1 | Factory functions, Components `internal` |
| Dagger H (multi) | 1 | FeatureProviders + DFS resolver |
| **Pattern I** (multi) | 1 | PureFeatureProviders + DFS resolver. Zero DI framework |
| **Pattern J** (multi) | 1 | KIFeatureProviders + DFS resolver. kotlin-inject |
| **Pattern K** (multi) | 1 | FeatureProviders via AndroidManifest metadata. Firebase-style |
| Koin | 2 | Descubrimiento runtime |
| Hybrid | 2 (SDK) / 1 (app) | SDK Koin + bridge Dagger |
| kotlin-inject | 1 | Consumidor compone components |

**Por que Dagger no llega a Nivel 2:** Dagger necesita conocer TODAS las clases `@Module`
en compilacion. No puede descubrir un modulo anadido despues de compilar el Component.

**Patrones I y J:** Usan la misma arquitectura de Resolver y ServiceLoader que H.
La diferencia es interna: I construye via constructor directo (zero framework),
J usa kotlin-inject Components (KSP, genera Kotlin). Ambos ofrecen Nivel 1 de aislamiento.

---

## Supervivencia de Singletons tras Reinicio

Algunos singletons deben sobrevivir ciclos `shutdown() -> init()`: loggers con file handles,
telemetria con entradas buffereadas.

El patron es agnostico al framework: mantener estado de ciclo de vida del proceso
en un Kotlin `object` fuera del contenedor DI.

```kotlin
// Vive fuera del contenedor DI
internal object FoundationSingletons {
    val logger: SdkLogger = AndroidSdkLogger()
}

// Koin — devuelve el objeto existente
fun foundationModule(config: SdkConfig) = module {
    single<SdkLogger> { FoundationSingletons.logger }
}

// Dagger — devuelve el objeto existente
@Module object CoreModule {
    @Provides @Singleton fun logger(): SdkLogger = FoundationSingletons.logger
}
```

En los patrones H/I/J, el logger persiste automaticamente en el `Resolver`
(se resuelve lazily desde `ObservabilityProvider` en el primer acceso y se mantiene
entre ciclos init/shutdown).

---

## Provision Interfaces: Aislamiento a Nivel Gradle

Las variantes multi-modulo (D, E2, G, H, I, J, K) usan **provision interfaces** para
aislamiento a nivel Gradle:

```
feature-auth-impl
  +-- depends on: di-contracts     (EncProvisions, CoreProvisions)
  +-- NOT on: feature-enc-impl     <- nunca ve DaggerEncComponent
```

Cada feature-impl depende de `di-contracts` (interfaces Kotlin planas),
NO de otros feature-impl. Solo los modulos de wiring importan las implementaciones concretas.

| Patron | Que importa el wiring |
|--------|----------------------|
| D | `DaggerXxxComponent` directamente |
| E2 | `DaggerXxxComponent` via `AutoProvisionEntry` lambdas |
| G | `buildXxxProvisions()` factory functions (Component `internal`) |
| H | Nada -- `FeatureProvider` descubierto via ServiceLoader |
| I | Nada -- `PureFeatureProvider` descubierto via ServiceLoader |
| J | Nada -- `KIFeatureProvider` descubierto via ServiceLoader |
| K | Nada -- `FeatureProvider` descubierto via AndroidManifest `<meta-data>` |

H, I, J y K tienen wiring inmutable: el modulo de wiring no importa ninguna clase de
implementacion. Cada feature se auto-registra via ServiceLoader (META-INF/services).

Para el analisis completo de la arquitectura multi-modulo, ver
[multimodule/api-impl-architecture.md](../multimodule/api-impl-architecture.md).
