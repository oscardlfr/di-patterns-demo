# Análisis de Complejidad y Mantenimiento

Análisis orientado a dos audiencias:
- **Equipo interno del SDK:** quién mantiene el código DI, Components, módulos.
- **Equipos consumidores:** quién integra el SDK en su aplicación.

Basado en métricas reales del proyecto `di-patterns-demo` con 5 features
(Encryption, Auth, Storage, Analytics, Sync) y dependencias cruzadas entre ellas.

---

## Parte 1: Equipo Interno del SDK

### Métricas de complejidad por approach

| Métrica | Dagger B | Dagger C | Dagger D | Koin | |
|---------|----------|----------|----------|------|---|
| **Ficheros Kotlin** | 2 | 2 | 2 | 1 | 🟢 Koin |
| **Líneas de código** | 244 | 276 | 251 | 274 | 🟢 B (244) |
| **Anotaciones DI** | 25 | 25 | 33 | 0 | 🟢 Koin · 🔴 D (33) |
| **Ficheros Java generados (KSP)** | 22 | 22 | 28 | 0 | 🟢 Koin · 🔴 D (28) |
| **Scopes personalizados** | 4 | 5 | 5 | 0 | 🟢 Koin |
| **Interfaces CoreApis extendidas** | 4 | 0 | 0 | 0 | 🔴 B (4) |
| **META-INF/services** | 0 | 1 | 0 | 0 | |
| **Sealed class / Enum features** | enum | string | enum | sealed class | |

**Lectura:** Las líneas de código son similares (~250-275). La diferencia está en la
complejidad estructural: Dagger requiere anotaciones, scopes y código generado.
Koin no tiene codegen ni anotaciones — toda la configuración es Kotlin puro.

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

#### Dagger D — Component Dependencies

| Paso | Fichero | Tipo de cambio |
|------|---------|---------------|
| 1 | `sdk/api/SdkApi.kt` | Nueva interfaz del servicio |
| 2 | `sdk/impl-common/Implementations.kt` | Nueva clase de implementación |
| 3 | `InternalComponents.kt` | Nuevo `@Component(dependencies=[...])` + `@Module` + `@Scope` |
| 4 | `DaggerSdk.kt` | Editar `Feature` enum + `when` block en `getOrInitModule()` + `get()` |

**Total: 4 puntos de contacto.** No hay CoreApis extendido — las dependencias cruzadas
se declaran en `dependencies=[ParentComponent::class]` y Dagger las resuelve.

**Riesgo a escala:** El `when` block en el facade crece linealmente (1 case por feature).
Pero no hay interfaces multiplicándose como en B.

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
| **Dagger B** | 6 | 3 | +2 Components, +2 CoreApis interfaces | God Object | 🔴 más coste |
| **Dagger C** | 5 | 3 + META-INF | +2 Components, +1 Initializer | Errores runtime | |
| **Dagger D** | 4 | 3 | +1 Component | `when` block crece | |
| **Koin** | 4 | 3 | 0 | Errores runtime | 🟢 menos coste |

### Depuración: ¿qué pasa cuando algo falla?

| Escenario | Dagger B/C/D | Koin |
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

#### Dagger B/C/D — KSP (Kotlin Symbol Processing)

Dagger usa **generación de código en compilación**. Cuando escribes `@Component` y `@Module`,
KSP lee las anotaciones y genera clases Java con las factories:

```kotlin
// TÚ ESCRIBES (en InternalComponents.kt):
@Singleton
@Component(modules = [EncModule::class])
interface EncComponent {
    fun encryption(): EncryptionService
}

@Module
class EncModule {
    @Provides @Singleton
    fun encryption(logger: SdkLogger): EncryptionService = DefaultEncryptionService(logger)
}
```

```java
// KSP GENERA AUTOMÁTICAMENTE (DaggerEncComponent.java):
public final class DaggerEncComponent implements EncComponent {
    private final EncModule encModule;
    private volatile Object encryptionService; // double-check locking cache

    @Override
    public EncryptionService encryption() {
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
        return (EncryptionService) local;
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
    single<EncryptionService> { DefaultEncryptionService(get()) }
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
val encryption = MySdk.get<EncryptionService>()
val sync = MySdk.get<SyncService>()

// Apagar
MySdk.shutdown()
```

El consumidor **no sabe** qué framework DI usa el SDK internamente.

### Complejidad de integración

| Aspecto | Dagger B/C/D | Koin | Hybrid | |
|---------|-------------|------|--------|---|
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
    fun encryption(): EncryptionService
    fun hash(): HashService
}
```

Esto añade:
- 1 fichero extra (`SdkBridgeModule.kt`)
- 1 `@Provides` por servicio que el consumidor quiera inyectar vía Dagger
- Conocimiento de que el orden de init importa (SDK antes que Dagger)
- Features lazy no pasan por el bridge — acceso directo vía `KoinSdk.get()`

**Para el consumidor, hybrid es el approach más complejo.** Los demás son transparentes.

### Migración entre approaches

Si el equipo interno decide cambiar de Koin a Dagger D (o viceversa), el impacto
en el consumidor es:

| Cambio | Impacto en consumidor |
|--------|----------------------|
| Koin → Dagger D | Cambiar dependencia Gradle. API idéntica. |
| Dagger B → Dagger D | Cambiar dependencia Gradle. API idéntica. |
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
| SDK pequeño (≤5 features), equipo Dagger | Dagger D |
| SDK grande (20+ features), adiciones frecuentes | Koin o Dagger C |
| Features con muchas dependencias cruzadas | Dagger D o Koin |
| Equipo sin experiencia Dagger | Koin |
| Compile-time safety prioritaria | Dagger D |

### Para los equipos consumidores

Todos los approaches presentan la **misma complejidad de integración** (2 dependencias
Gradle, 3 líneas de init, `get<T>()` para resolver). La excepción es hybrid, que
requiere un bridge Component adicional.

El consumidor no debería elegir el approach — es decisión del equipo del SDK.
La API pública es idéntica independientemente del motor DI interno.
