# Conceptos de DI para SDKs

Conceptos fundamentales para construir SDKs modulares con inyección de dependencias.
Principios agnósticos al framework — aplica a Dagger, Koin, kotlin-inject o DI manual.

Para implementaciones Dagger, ver [dagger2-sdk-selective-init.md](dagger2-sdk-selective-init.md).
Para comparación de frameworks, ver [di-sdk-selective-init-comparison.md](di-sdk-selective-init-comparison.md).
Para dependencias cruzadas, ver [di-cross-feature-deps.md](di-cross-feature-deps.md).
Para el approach hybrid, ver [di-hybrid-koin-sdk-dagger-app.md](di-hybrid-koin-sdk-dagger-app.md).

---

## DI vs Service Locator

Dos paradigmas para gestionar dependencias. Entender esta distinción importa porque
determina qué garantías se obtienen en compilación.

### Inyección de Dependencias (DI puro)

El framework genera código que crea objetos y pasa sus dependencias por constructor.
La clase nunca pide nada:

```kotlin
// La clase — zero conocimiento de cualquier contenedor DI
class SecurityServiceImpl(
    private val network: NetworkExecutor,
    private val logger: Logger,
) : SecurityService

// El módulo Dagger — dice a Dagger CÓMO crear SecurityServiceImpl
@Module class SecurityModule {
    @Provides fun security(network: NetworkExecutor, logger: Logger): SecurityService =
        SecurityServiceImpl(network, logger)
}
```

Dagger lee `@Provides`, ve que SecurityServiceImpl necesita NetworkExecutor y Logger,
los encuentra en otros `@Provides`, y genera una factory que lo conecta todo en compilación.
Si NetworkExecutor no tiene provider, el **build falla** — no la app.

**Frameworks:** Dagger 2, kotlin-inject

### Service Locator

El framework mantiene un registro de «cómo crear X». El código pregunta al registro en runtime:

```kotlin
// Módulo Koin — registra CÓMO crear cosas
val securityModule = module {
    single<SecurityService> { SecurityServiceImpl(get(), get()) }
    //                                             ^^^   ^^^
    //       pregunta a Koin «dame un NetworkExecutor» y «dame un Logger»
}
```

`get()` es un lookup runtime — Koin busca en su registro algo que coincida con el tipo pedido.
Si nada está registrado, **crashea en runtime**.

**Matiz importante:** La clase SecurityServiceImpl usa inyección por constructor.
La diferencia es DÓNDE ocurre la resolución. En Dagger, en compilación (codegen).
En Koin, en runtime (`get()` dentro de la lambda del módulo).

**Frameworks:** Koin, kodein

### Trade-offs

| | DI puro (Dagger, kotlin-inject) | Service Locator (Koin) |
|---|---|---|
| **Dependencia faltante** | Build falla | App crashea en runtime |
| **Validación del grafo** | Compilación | `checkModules()` en tests |
| **Velocidad de build** | Más lento (codegen KSP) | Más rápido (zero codegen) |
| **Soporte KMP** | Dagger: solo JVM. kotlin-inject: completo | Completo (JVM + Native + JS) |
| **Flexibilidad runtime** | Grafo fijo en compilación | Módulos componibles en runtime |
| **Auto-descubrimiento** | No posible con Dagger | Sí (Class.forName, @EagerInit) |
| **Tamaño de código** | Genera clases factory | Zero código generado |

Ninguno es universalmente mejor. DI puro detecta más errores en build time.
Service Locator permite composición runtime (auto-discovery, módulos condicionales)
que el DI en compilación no puede lograr.

---

## Niveles de Aislamiento del Consumidor

¿Cuánto sabe la app consumidora sobre los internos del SDK?

### Nivel 0 — Sin aislamiento (anti-patrón)

El consumidor crea clases impl directamente:
```kotlin
import com.example.sdk.security.internal.SecurityServiceImpl
val service = SecurityServiceImpl(KtorNetworkClient())
```
Si el SDK renombra la clase o cambia su constructor, el código del consumidor se rompe.

### Nivel 1 — Facade

El consumidor llama a un objeto facade. Ve el facade, no lo que hay detrás:
```kotlin
import com.example.sdk.MySdk
MySdk.init(context, config, setOf(Feature.SECURITY))
val service = MySdk.security()
```
El consumidor no importa SecurityServiceImpl ni ninguna clase Dagger.

### Nivel 2 — Interfaz + auto-descubrimiento

El consumidor depende de una API abstracta. El SDK descubre y conecta las implementaciones:
```kotlin
import com.example.sdk.SdkModule
import com.example.sdk.SharedSdk
SharedSdk.init(modules = setOf(SdkModule.Security))
val service: SecurityService = SharedSdk.get()
```
El consumidor nunca importa ningún módulo impl en código. Solo la dependencia Gradle basta.

### Qué logra cada framework

| Framework | Nivel máximo | Por qué |
|-----------|-------------|---------|
| **Dagger Monolítico (A)** | 1 | Facade oculta impls, pero todas compiladas en binario |
| **Dagger Per-Feature (B)** | 1 | Facade por feature, consumidor importa facade |
| **Dagger + ServiceLoader (C)** | 1-2 | ServiceLoader descubre, pero limitado a JVM |
| **Dagger Component Deps (D)** | 1 | Facade con internal Components |
| **Koin** | 2 | Descubrimiento runtime vía Class.forName / @EagerInit |
| **kotlin-inject** | 1 | Consumidor compone components explícitamente |

**Por qué Dagger no llega a Nivel 2:** Dagger necesita conocer TODAS las clases `@Module`
en compilación para generar el código factory. No puede descubrir un módulo añadido como
dependencia Gradle después de compilar el Component.

---

## Dependencias Cruzadas

Ver documento dedicado: [di-cross-feature-deps.md](di-cross-feature-deps.md).

Resumen:
- **Grafo único (A / Koin):** Automático — declara dependencias, el framework resuelve.
- **Per-feature (B / C):** Manual — a través de CoreApis (God Object a escala) o init ordenado.
- **Component Dependencies (D):** Automático — `dependencies=[ParentComponent]`.

---

## Supervivencia de Singletons tras Reinicio

Algunos singletons deben sobrevivir ciclos `shutdown() → init()`: loggers con file handles,
telemetría con entradas buffereadas, generadores de correlation ID.

El patrón es agnóstico al framework: mantener estado de ciclo de vida del proceso
en un Kotlin `object` fuera del contenedor DI.

**Código real del proyecto** — todos los SDKs usan el mismo patrón:

```kotlin
// Vive fuera de cualquier contenedor DI — creado al cargar la clase
internal object FoundationSingletons {
    val logger: SdkLogger = AndroidSdkLogger()
}

// Koin — la lambda devuelve el objeto existente, no crea nuevo
fun foundationModule(config: SdkConfig) = module {
    single<SdkLogger> { FoundationSingletons.logger }   // sobrevive reinit
    single<SdkConfig> { config }                          // nuevo valor cada init
}

// Dagger — @Provides devuelve el objeto existente
@Module object CoreModule {
    @Provides @Singleton fun logger(): SdkLogger = DaggerSdk.foundationLogger
}
```

Cuando `shutdown()` destruye el contenedor DI, `FoundationSingletons` sobrevive
porque es un `object` Kotlin (singleton estático a nivel JVM/Native).

---

## Hybrid: Koin SDK + Dagger App

Ver documento dedicado: [di-hybrid-koin-sdk-dagger-app.md](di-hybrid-koin-sdk-dagger-app.md).

Resumen: El SDK usa Koin (`koinApplication` aislado). La app usa Dagger 2.
Un `@Component` bridge conecta ambos. La app nunca importa Koin.
El puente es unidireccional: app ← SDK.
