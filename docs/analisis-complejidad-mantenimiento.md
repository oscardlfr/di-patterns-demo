# Análisis de Complejidad y Mantenimiento

Análisis orientado a dos audiencias:
- **Equipo interno del SDK:** quién mantiene el código DI, Components, módulos.
- **Equipos consumidores:** quién integra el SDK en su aplicación.

Basado en métricas reales del proyecto `di-patterns-demo` con 5 features
(Encryption, Auth, Storage, Analytics, Sync) y dependencias cruzadas entre ellas.
Incluye patrones monolíticos (Dagger A educativo, B, C y Koin) más 5 variantes
multi-módulo con provision interfaces (sdk-wiring D, wiring-e, wiring-e2, wiring-g, wiring-h).
D, E, E2, G y H solo existen como variantes multi-módulo — no tienen módulos SDK monolíticos.

---

## Parte 1: Equipo Interno del SDK

### Métricas de complejidad por approach

#### Patrones monolíticos

| Métrica | Dagger B | Dagger C | Koin | |
|---------|----------|----------|------|---|
| **Ficheros Kotlin** | 2 | 2 | 1 | 🟢 Koin |
| **Líneas de código** | 244 | 276 | 274 | 🟢 B |
| **Anotaciones DI** | 25 | 25 | 0 | 🟢 Koin |
| **Ficheros Java generados (KSP)** | 22 | 22 | 0 | 🟢 Koin |
| **Scopes personalizados** | 4 | 5 | 0 | 🟢 Koin |
| **Interfaces CoreApis extendidas** | 4 | 0 | 0 | 🔴 B (4) |
| **META-INF/services** | 0 | 1 | 0 | |
| **Feature selector** | enum | string | sealed | |
| **Escala a 50+ módulos** | ❌ | ⚠️ | ✅ | 🟢 Koin |

**Nota:** D, E, E2, G y H no tienen módulos SDK monolíticos. Existen exclusivamente como variantes
multi-módulo (sdk-wiring, wiring-e, wiring-e2, wiring-g, wiring-h). Para sus métricas, ver la sección
[Multi-módulo: Complejidad del Wiring](#multi-módulo-complejidad-del-wiring).

Koin sigue siendo el más ligero en complejidad estructural (0 anotaciones, 0 codegen).

### Coste de añadir una nueva feature

#### Dagger B — Per-Feature + CoreApis

| Paso | Fichero | Tipo de cambio |
|------|---------|---------------|
| 1 | `sdk/api/SdkApi.kt` | Nueva interfaz del servicio |
| 2 | `sdk/impl-common/Implementations.kt` | Nueva clase de implementación |
| 3 | `InternalComponents.kt` | Nuevo `@Component` + `@Module` + `@Scope` |
| 4 | `InternalComponents.kt` | Nueva interfaz `XxxCoreApis` extendiendo `CoreApis` (si tiene cross-deps) |
| 5 | `InternalComponents.kt` | Nueva clase `XxxCoreApisImpl` (si tiene cross-deps) |
| 6 | `DaggerBSdk.kt` | Editar `when` block en `getOrInitModule()` + `get()` |

**Total: 6 puntos de contacto.** El paso 4-5 es el más problemático: cada dependencia
cruzada nueva requiere una interfaz y una clase adicional. Con 10 features que comparten
servicios, el fichero de Components crece con N interfaces CoreApis extendidas.

**Riesgo a escala:** Las interfaces `AuthCoreApis`, `StorageCoreApis`, `SyncCoreApis`
evolucionan hacia un God Object — una interfaz que conoce todos los servicios del SDK.

#### Dagger C — ServiceLoader Discovery

| Paso | Fichero | Tipo de cambio |
|------|---------|---------------|
| 1 | `sdk/api/SdkApi.kt` | Nueva interfaz del servicio |
| 2 | `sdk/impl-common/Implementations.kt` | Nueva clase de implementación |
| 3 | `InternalComponents.kt` | Nuevo `@Component` + `@Module` + `@Scope` |
| 4 | `InternalComponents.kt` | Nueva clase `XxxInit : FeatureInitializer` |
| 5 | `META-INF/services/...FeatureInitializer` | Añadir línea con la clase |
| 6 | `DaggerCSdk.kt` | **No requiere edición** |

**Total: 5 puntos de contacto (ninguno central).** La ventaja de C es que `DaggerCSdk.kt`
no se toca — ServiceLoader descubre features automáticamente. El coste es que los errores
de configuración (META-INF incorrecto, dependencia Gradle ausente) solo aparecen en runtime.

**Riesgo a escala:** Errores silenciosos. Si el META-INF tiene un typo, la feature no se
descubre y el error aparece cuando el usuario navega a esa pantalla, no al compilar.

#### Koin

| Paso | Fichero | Tipo de cambio |
|------|---------|---------------|
| 1 | `sdk/api/SdkApi.kt` | Nueva interfaz del servicio |
| 2 | `sdk/impl-common/Implementations.kt` | Nueva clase de implementación |
| 3 | `KoinSdk.kt` | Nuevo `object XxxRegistration` con `module { single<XxxService> { ... } }` |
| 4 | `KoinSdk.kt` | Editar `SdkModule` sealed class + `registrationClassName` |

**Total: 4 puntos de contacto.** Sin anotaciones, sin Components, sin Scopes.
Una nueva feature son ~15 líneas de código Kotlin.

**Riesgo a escala:** El fichero `KoinSdk.kt` crece (registrations + sealed class).
En un SDK real con 15 módulos, el fichero tiene ~180 líneas. Manejable.

### Resumen: coste por feature nueva

| Approach | Puntos de contacto | Ficheros tocados | Artefactos generados | Riesgo principal | |
|----------|-------------------|------------------|---------------------|-----------------|---|
| **Dagger B** (monolítico) | 6 | 3 | +2 Components, +2 CoreApis interfaces | God Object | 🔴 más coste |
| **Dagger C** (monolítico) | 5 | 3 + META-INF | +2 Components, +1 Initializer | Errores runtime | |
| **Koin** (monolítico) | 4 | 3 | 0 | Errores runtime | 🟢 menos coste |

#### Dagger D — Component Dependencies (multi-módulo: sdk-wiring)

| Paso | Fichero | Tipo de cambio |
|------|---------|---------------|
| 1 | `feature-xxx-api/XxxApi.kt` | Nueva interfaz del servicio (módulo nuevo) |
| 2 | `sdk/di-contracts/Provisions.kt` | Nuevo `XxxProvisions` + `@XxxScope` |
| 3 | `feature-xxx-impl/XxxComponent.kt` | Nuevo `@Component` + `@Module` + factory `buildXxxProvisions()` |
| 4 | `feature-xxx-impl/DefaultXxxService.kt` | Nueva implementación (`internal`) |
| 5 | `sdk/sdk-wiring/MultiModuleSdk.kt` | Nuevo `ensureXxx()` + caso en `when` block de `get()` |
| 6 | `sdk/sdk-wiring/build.gradle.kts` | Añadir `implementation(project(":feature-xxx-impl"))` |
| 7 | `settings.gradle.kts` | Añadir 2 `include()` (api + impl) |

**Total: 7 puntos de contacto en 5 módulos.** El paso 5 es el cuello de botella — el wiring
module crece linealmente. Compile-time safe por Component individual.

**Riesgo a escala:** `MultiModuleSdk.kt` crece con cada feature (1 `ensureXxx()` + 1-N `when` cases).
A 30 features, el fichero supera las 500 líneas de wiring manual.

#### Dagger E — Registry + Topo-sort (multi-módulo: wiring-e)

| Paso | Fichero | Tipo de cambio |
|------|---------|---------------|
| 1 | `feature-xxx-api/XxxApi.kt` | Nueva interfaz del servicio (módulo nuevo) |
| 2 | `sdk/di-contracts/Provisions.kt` | Nuevo `XxxProvisions` + `@XxxScope` |
| 3 | `feature-xxx-impl/XxxComponent.kt` | Nuevo `@Component` + `@Module` + factory |
| 4 | `feature-xxx-impl/DefaultXxxService.kt` | Nueva implementación (`internal`) |
| 5 | `sdk/wiring-e/Entries.kt` | Nuevo `ProvisionEntry` (~10 líneas) |
| 6 | `sdk/wiring-e/MultiModuleSdkE.kt` | Nuevo caso en `Feature` enum |
| 7 | `sdk/wiring-e/build.gradle.kts` | Añadir `implementation(project(":feature-xxx-impl"))` |
| 8 | `settings.gradle.kts` | Añadir 2 `include()` |

**Total: 8 puntos de contacto.** Más boilerplate que D por el `ProvisionEntry` que duplica
las dependencias que Dagger ya conoce via `dependencies=[...]`.

**Riesgo a escala:** El `Feature` enum se expone al consumidor y crece linealmente.
El `ProvisionEntry` duplica información de dependencias.

#### Dagger E2 — Auto-Init + DFS (multi-módulo: wiring-e2)

| Paso | Fichero | Tipo de cambio |
|------|---------|---------------|
| 1 | `feature-xxx-api/XxxApi.kt` | Nueva interfaz del servicio (módulo nuevo) |
| 2 | `sdk/di-contracts/Provisions.kt` | Nuevo `XxxProvisions` + `@XxxScope` |
| 3 | `feature-xxx-impl/XxxComponent.kt` | Nuevo `@Component` + `@Module` + factory |
| 4 | `feature-xxx-impl/DefaultXxxService.kt` | Nueva implementación (`internal`) |
| 5 | `sdk/wiring-e2/Entries.kt` | Nuevo `AutoProvisionEntry` (~10 líneas) |
| 6 | `sdk/wiring-e2/build.gradle.kts` | Añadir `implementation(project(":feature-xxx-impl"))` |
| 7 | `settings.gradle.kts` | Añadir 2 `include()` |

**Total: 7 puntos de contacto.** Sin `Feature` enum (vs E). Pero el `AutoProvisionEntry`
sigue duplicando dependencias y service mappings.

**Riesgo a escala:** `AutoProvisionEntry` es boilerplate mecánico (~10 líneas por feature).
A 50 features, el fichero de entries tiene ~500 líneas de declaraciones repetitivas.

#### Dagger G — Factory Functions (multi-módulo: wiring-g)

| Paso | Fichero | Tipo de cambio |
|------|---------|---------------|
| 1 | `feature-xxx-api/XxxApi.kt` | Nueva interfaz del servicio (módulo nuevo) |
| 2 | `sdk/di-contracts/Provisions.kt` | Nuevo `XxxProvisions` + `@XxxScope` |
| 3 | `feature-xxx-impl/XxxComponent.kt` | Nuevo `@Component` + `@Module` + factory `buildXxxProvisions()` |
| 4 | `feature-xxx-impl/DefaultXxxService.kt` | Nueva implementación (`internal`) |
| 5 | `sdk/wiring-g/MultiModuleSdkG.kt` | Nuevo `ensureXxx()` + caso en `when` block |
| 6 | `sdk/wiring-g/build.gradle.kts` | Añadir `implementation(project(":feature-xxx-impl"))` |
| 7 | `settings.gradle.kts` | Añadir 2 `include()` |

**Total: 7 puntos de contacto.** Idéntico a D pero `DaggerXxxComponent` queda `internal`.
Mismo cuello de botella: el wiring file crece linealmente.

**Riesgo a escala:** Igual que D — `when` blocks + `ensureXxx()` crecen.

#### Dagger H — Auto-Discovery FeatureProviders (multi-módulo: wiring-h)

| Paso | Fichero | Tipo de cambio |
|------|---------|---------------|
| 1 | `feature-xxx-api/XxxApi.kt` | Nueva interfaz del servicio (módulo nuevo) |
| 2 | `sdk/di-contracts/Provisions.kt` | Nuevo `XxxProvisions` + `@XxxScope` |
| 3 | `feature-xxx-impl/XxxComponent.kt` | Nuevo `@Component` + `@Module` + factory `buildXxxProvisions()` |
| 4 | `feature-xxx-impl/DefaultXxxService.kt` | Nueva implementación (`internal`) |
| 5 | `feature-xxx-impl/XxxProvider.kt` | Nuevo `FeatureProvider` (~8 líneas, deps implícitas) |
| 6 | `sdk/wiring-h/MultiModuleSdkH.kt` | Añadir `resolver.register(XxxProvider())` |
| 7 | `sdk/wiring-h/build.gradle.kts` | Añadir `implementation(project(":feature-xxx-impl"))` |
| 8 | `settings.gradle.kts` | Añadir 2 `include()` |

**Total: 8 puntos de contacto.** El paso 6 es 1 línea (vs ensureXxx + when en D/G).
El `FeatureProvider` es autocontenido: deps implícitas via `resolver.provision()`.
Con ServiceLoader, el paso 6 desaparece — zero edición del wiring module.

**Riesgo a escala:** Mínimo. El `FeatureProvider` es mecánico (~8 líneas). El wiring module
crece 1 línea por feature (solo `register()`). Con ServiceLoader, 0 líneas.

### Resumen: coste por feature nueva (todos los approaches)

| Approach | Puntos de contacto | Edición central | Boilerplate por feature | Riesgo principal | |
|----------|-------------------|----------------|------------------------|-----------------|---|
| **Dagger B** (mono) | 6 | DaggerBSdk.kt (when + CoreApis) | Alto | God Object | 🔴 |
| **Dagger C** (mono) | 5 | Ninguno (ServiceLoader) | Medio | Errores silenciosos | |
| **Dagger D** (multi) | 7 | MultiModuleSdk.kt (when + ensure) | Bajo | Wiring crece | |
| **Dagger E** (multi) | 8 | Entries.kt + Feature enum | Alto (entry duplica deps) | Enum crece | 🔴 |
| **Dagger E2** (multi) | 7 | Entries.kt (1 línea) | Alto (entry duplica deps) | Entries verbose | |
| **Dagger G** (multi) | 7 | MultiModuleSdkG.kt (when + ensure) | Bajo | Wiring crece | |
| **Dagger H** (multi) | 8 (7 con ServiceLoader) | 1 línea register() (0 con SL) | Bajo (8 líneas provider) | ~3.5x init overhead | 🟢 |
| **Koin** (mono) | 4 | KoinSdk.kt (sealed class) | Mínimo | Errores runtime | 🟢 |

### Depuración: ¿qué pasa cuando algo falla?

| Escenario | Dagger B/C/D/E/E2/G/H | Koin |
|-----------|-------------|------|
| Binding faltante | **Error de compilación** (Dagger B/D). Error runtime (C si META-INF incorrecto) | **Crash en runtime** con `NoBeanDefFoundException` |
| Dependencia circular | **Error de compilación** con mensaje claro | Crash con `StackOverflowError` en runtime |
| Scope incorrecto | Error de compilación (scoped binding sin scope) | No aplica — no hay scopes Dagger |
| Feature no inicializada | `IllegalStateException` con mensaje descriptivo (todos) | Igual — `requireModule()` en todos |
| Clase impl renombrada | Error de compilación (todos Dagger) | Error runtime (Class.forName falla) |

**Observación:** Dagger detecta problemas estructurales en compilación. Koin los detecta
en ejecución. Ambos fallan rápido cuando una feature no está inicializada (guard explícito
en el facade). La diferencia es **cuándo** te enteras del problema.

### Tiempo de compilación

Cada approach usa un mecanismo distinto para conectar interfaces con implementaciones.
Eso afecta a lo que pasa en cada build incremental (cambias 1 fichero → Build).

#### Dagger B/C/D/E/E2/G/H — KSP (Kotlin Symbol Processing)

Dagger usa **generación de código en compilación**. Cuando escribes `@Component` y `@Module`,
KSP lee las anotaciones y genera clases Java con las factories:

```kotlin
// TÚ ESCRIBES (en InternalComponents.kt):
@Singleton
@Component(modules = [EncModule::class])
interface EncComponent {
    fun encryption(): EncryptionApi
}

@Module
class EncModule {
    @Provides @Singleton
    fun encryption(logger: SdkLogger): EncryptionApi = DefaultEncryptionApi(logger)
}
```

```java
// KSP GENERA AUTOMÁTICAMENTE (DaggerEncComponent.java):
public final class DaggerEncComponent implements EncComponent {
    private final EncModule encModule;
    private volatile Object encryptionService; // double-check locking cache

    @Override
    public EncryptionApi encryption() {
        Object local = encryptionService;
        if (local == null) {
            synchronized (this) {
                local = encryptionService;
                if (local == null) {
                    local = encModule.encryption(loggerProvider.get());
                    encryptionService = local;
                }
            }
        }
        return (EncryptionApi) local;
    }
}
```

**Cada vez que cambias un `@Module` o `@Provides`**, KSP regenera estas factories.
Con 6 features en este proyecto: 22-28 ficheros Java generados, ~2-4s extra por build.

| Features | Ficheros generados | Tiempo KSP estimado |
|----------|-------------------|---------------------|
| 6 | 22-28 | ~2-4s |
| 20 | ~80-100 | ~8-12s |
| 50+ | ~200+ | ~15-30s |

#### Koin — Zero codegen

Koin no genera ningún fichero. La configuración es Kotlin puro ejecutado en runtime:

```kotlin
// TÚ ESCRIBES (y esto es TODO — no hay paso de generación):
val encryptionModule = module {
    single<EncryptionApi> { DefaultEncryptionApi(get()) }
}
```

`module {}` es una lambda Kotlin. `single<>` registra una factory en un `HashMap` interno.
`get()` busca en ese `HashMap` en runtime. No hay anotaciones, no hay procesador, no hay
ficheros generados.

**Cada vez que cambias un módulo Koin**, el compilador Kotlin compila solo ese fichero.
No hay paso extra de procesamiento.

#### Comparación

| | Dagger (KSP) | Koin |
|---|---|---|
| **Qué procesa** | `@Component`, `@Module`, `@Provides` | Nada — Kotlin puro |
| **Qué genera** | Clases Java con factories + double-check locking | Nada |
| **Cuándo procesa** | En cada build que toca anotaciones DI | Nunca |
| **Ventaja** | Errores en compilación. Singleton cache = 2 ns | Zero overhead de build |
| **Coste** | +2-30s por build según tamaño del grafo | 0s extra |

**Nota sobre KAPT vs KSP:** Dagger históricamente usaba KAPT (Kotlin Annotation Processing Tool),
que era significativamente más lento porque requería generar stubs Java intermedios.
KSP (usado en este proyecto) es ~2× más rápido que KAPT porque procesa símbolos Kotlin
directamente. Dagger soporta KSP desde la versión 2.48 (alpha) y 2.59+ (estable con AGP 9).

Para SDKs pequeños (≤10 features) la diferencia es insignificante (~2-4s).
Para SDKs grandes (50+ features), el ciclo de desarrollo se nota:
50 builds/día × 20s extra = ~17 minutos de espera adicionales.

---

## Parte 2: Equipos Consumidores (Externos)

### ¿Qué ve el consumidor?

Con cualquier approach, el consumidor ve **exactamente la misma API**:

```kotlin
// Inicializar
MySdk.init(SdkConfig(debug = true), setOf(Feature.ENCRYPTION, Feature.AUTH))

// Lazy init posterior
MySdk.getOrInitModule(Feature.SYNC)

// Usar servicios
val encryption = MySdk.get<EncryptionApi>()
val sync = MySdk.get<SyncApi>()

// Apagar
MySdk.shutdown()
```

El consumidor **no sabe** qué framework DI usa el SDK internamente.

### Complejidad de integración

| Aspecto | Dagger B/C (monolítico) | Koin | Hybrid | |
|---------|------------------------|------|--------|---|
| **Dependencias Gradle** | `sdk-api` + `sdk-impl-dagger-x` | `sdk-api` + `sdk-impl-koin` | `sdk-api` + `sdk-impl-koin` | |
| **Dependencias transitivas** | Dagger 2 (codegen, no runtime) | Koin Core (~100 KB runtime) | Koin Core transitivo | 🟢 Dagger (0 KB runtime) |
| **Conflictos con app existente** | Ninguno (Dagger = codegen puro) | Ninguno si app usa `startKoin` y SDK usa `koinApplication` aislado | Ninguno (dos contenedores separados) | |
| **Líneas de código del consumidor** | ~77 (Application + Activity) | ~77 (Application + Activity) | ~185 (+ bridge Component) | 🔴 Hybrid (185 líneas) |
| **Imports del SDK** | 2-3 | 2-3 | 4-5 (incluye bridge) | 🔴 Hybrid (4-5) |

### Hybrid: complejidad adicional para el consumidor

El approach hybrid requiere que el consumidor cree un **bridge Component**:

```kotlin
@Component(modules = [SdkBridgeModule::class])
interface SdkBridgeComponent {
    fun encryption(): EncryptionApi
    fun hash(): HashApi
}
```

Esto añade:
- 1 fichero extra (`SdkBridgeModule.kt`)
- 1 `@Provides` por servicio que el consumidor quiera inyectar vía Dagger
- Conocimiento de que el orden de init importa (SDK antes que Dagger)
- Features lazy no pasan por el bridge — acceso directo vía `KoinSdk.get()`

**Para el consumidor, hybrid es el approach más complejo.** Los demás son transparentes.

### Migración entre approaches

Si el equipo interno decide cambiar de Koin a Dagger D multi-módulo (o viceversa), el impacto
en el consumidor es:

| Cambio | Impacto en consumidor |
|--------|----------------------|
| Koin → Dagger D/E/E2/G/H (multi-módulo) | Cambiar dependencia Gradle. API idéntica (E2/H aún más simple). |
| Dagger B → Dagger D/E/E2/G/H (multi-módulo) | Cambiar dependencia Gradle. API idéntica. |
| Dagger D multi → E/E2 multi | Cambiar dependencia Gradle. API idéntica. |
| Dagger E multi → E2 multi | Cambiar dependencia. Consumidor elimina Feature enum — API más simple. |
| Koin → Hybrid | Consumidor debe crear bridge Component |
| Cualquier Dagger → Koin | Cambiar dependencia Gradle. API idéntica. |

**El facade SDK aísla al consumidor del framework DI interno.** Cambiar el motor
no rompe la API pública.

### ¿Qué debería importarle al consumidor?

| Preocupación | Respuesta |
|--------------|-----------|
| ¿Aumenta el tamaño de mi APK? | Dagger: ~0 KB (codegen compile-time). Koin: ~100 KB (runtime) |
| ¿Afecta al tiempo de arranque? | <50 µs en todos los approaches. Imperceptible |
| ¿Conflictos con mi Dagger/Koin? | No. Los SDKs usan instancias aisladas |
| ¿Puedo hacer lazy init? | Sí en todos los approaches |
| ¿Qué pasa si falta una feature? | `IllegalStateException` con mensaje claro en todos |
| ¿Necesito saber Koin/Dagger? | No. Solo `Sdk.init()`, `Sdk.get<T>()`, `Sdk.shutdown()` |

### Documentación mínima para el consumidor

Independientemente del approach elegido, el consumidor necesita saber:

1. **Qué features existen** y cuáles son opcionales
2. **Cómo inicializar** (`init` en `Application.onCreate`)
3. **Cómo hacer lazy init** si una feature no se necesita al arrancar
4. **Qué dependencias cruzadas existen** (Sync requiere Auth + Storage + Encryption)
5. **Cómo resolver servicios** (`get<T>()`)

Todo esto es agnóstico al framework DI.

---

## Conclusión

### Para el equipo interno

La complejidad de mantenimiento depende del tamaño del SDK y la frecuencia de cambios:

| Escenario | Menor complejidad |
|-----------|------------------|
| SDK pequeño (≤5 features), equipo Dagger | Dagger B (monolítico) o D (multi-módulo) |
| SDK grande (20+ features), adiciones frecuentes | Koin, **Dagger E2** o **H** (multi-módulo) |
| SDK escalable a 50+ módulos | **Dagger E2**, **H** (multi-módulo) o Koin |
| Features con muchas dependencias cruzadas | Dagger D, E, E2, H (multi-módulo) o Koin |
| Equipo sin experiencia Dagger | Koin |
| Compile-time safety prioritaria | Dagger D, E o E2 (multi-módulo) |
| Multi-módulo Gradle corporativo (api/impl) | Dagger E o E2 (vía wiring-e / wiring-e2) |
| Equipos grandes (10+), zero edición central | **Dagger H** (wiring inmutable) |
| API mínima para consumidor | **Dagger E2** o **H** (sin Feature enum) |

### Para los equipos consumidores

Todos los approaches presentan la **misma complejidad de integración** (2 dependencias
Gradle, 3 líneas de init, `get<T>()` para resolver). La excepción es hybrid, que
requiere un bridge Component adicional.

El consumidor no debería elegir el approach — es decisión del equipo del SDK.
La API pública es idéntica independientemente del motor DI interno.

---

## Multi-módulo: Complejidad del Wiring

Las variantes multi-módulo (sdk-wiring, wiring-e, wiring-e2, wiring-g, wiring-h) usan los **mismos**
módulos feature-impl y los mismos contratos per-feature. La única diferencia es
el código de wiring que conecta los `DaggerXxxComponent` builders con el facade público.

| Variante | Ficheros wiring | Líneas wiring | Escala |
|----------|----------------|---------------|--------|
| D (sdk-wiring) | 1 | ~145 | `when` blocks crecen linealmente |
| E (wiring-e) | 2 (Entries + Facade) | ~170 | `Feature` enum crece |
| E2 (wiring-e2) | 2 (Entries + Facade) | ~100 | 1 línea por feature |
| G (wiring-g) | 1 (Facade) | ~95 | `when` blocks crecen (igual que D) |
| H (wiring-h) | 1 (Facade) | ~50 | Inmutable — zero edición |

G tiene menos líneas que D porque llama factory functions (`buildXxxProvisions(deps)`)
en vez de importar `DaggerXxxComponent` builders directamente. Los Components quedan
`internal` en cada feature-impl. El trade-off es el mismo que D: el wiring module
sigue conociendo el orden de dependencias y crece linealmente con cada feature.

H tiene menos código de wiring que cualquier otro pattern porque el módulo wiring es
inmutable: descubre FeatureProviders, los registra y resuelve dependencias via DFS.
No hay `when` blocks, no hay `ensureXxx()`, no hay edición central al añadir features.
Internamente usa factory functions de G (`buildXxxProvisions`). El trade-off es ~3,5x
más lento en init que G (3,5 µs vs 966 ns) por overhead de HashMap + registro de providers.

El coste de wiring es puramente código de integración — no afecta a los feature-impl
ni a los contratos. Añadir una feature nueva en E2 multi-módulo requiere crear el
feature-impl, su contrato, y añadir **una línea** al fichero de entries.

74 benchmarks totales (19 monolíticos vía facades + 55 multi-módulo vía facades) confirman que
la separación en módulos Gradle no introduce overhead en runtime. Para el análisis detallado, ver
[di-multimodule-api-impl-analysis.md](di-multimodule-api-impl-analysis.md).
