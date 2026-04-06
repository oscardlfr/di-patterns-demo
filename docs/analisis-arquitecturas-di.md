# Análisis de Arquitecturas DI para SDKs Modulares

Documento de referencia técnica. Presenta los resultados del proyecto `di-patterns-demo`:
8 implementaciones de SDK con inyección de dependencias, benchmarks en dispositivo real
(Samsung Galaxy S22 Ultra, Android 16) y análisis de cumplimiento de requisitos.

**No hay recomendación.** Cada approach tiene trade-offs. Este documento presenta los datos
para que el equipo tome la decisión informada según sus restricciones.

Para análisis específico de arquitectura multi-módulo api/impl/integration,
ver [di-multimodule-api-impl-analysis.md](di-multimodule-api-impl-analysis.md).

---

## Índice

1. [Requisitos](#requisitos)
2. [Approaches implementados](#approaches-implementados)
3. [Arquitectura del proyecto](#arquitectura-del-proyecto)
4. [Cumplimiento de requisitos](#cumplimiento-de-requisitos)
5. [Resultados de benchmarks](#resultados-de-benchmarks)
6. [Lazy init y dependencias cruzadas](#lazy-init-y-dependencias-cruzadas)
7. [Análisis por approach](#análisis-por-approach)
8. [Matriz de decisión](#matriz-de-decisión)

---

## Requisitos

Criterios para evaluar cualquier implementación de SDK modular con DI.
No todos tienen el mismo peso — depende del contexto del proyecto.

| # | Requisito | Pregunta |
|---|-----------|----------|
| 1 | Inicialización selectiva | ¿Puede el consumidor activar solo las features que necesita? |
| 2 | Aislamiento del consumidor | ¿El código del consumidor evita importar clases de implementación? |
| 3 | Singletons compartidos | ¿Los servicios compartidos (logger, config) son instancia única? |
| 4 | Instanciación lazy | ¿Las features no seleccionadas nunca se instancian? |
| 5 | Independencia del core | ¿El orquestador evita dependencias de producción en módulos impl? |
| 6 | Auto-registro | ¿Es suficiente añadir una dependencia Gradle para registrar una feature? |
| 7 | Binario eficiente | ¿El binario del consumidor excluye las features no seleccionadas? |
| 8 | Dependencias cruzadas | ¿Feature A puede inyectar un servicio de Feature B? |
| 9 | Seguridad en compilación | ¿Los bindings faltantes se detectan en tiempo de compilación? |
| 10 | Soporte KMP | ¿Funciona en iOS, macOS, Desktop? |

---

## Approaches implementados

| Approach | Módulo SDK | Framework | Mecanismo interno |
|----------|-----------|-----------|-------------------|
| **B: Per-Feature** | `sdk/impl-dagger-b` | Dagger 2 | N Components aislados + CoreApis manual |
| **C: ServiceLoader** | `sdk/impl-dagger-c` | Dagger 2 | N Components + descubrimiento META-INF/services |
| **D: Component Dependencies** | `sdk/impl-dagger-d` | Dagger 2 | Jerarquía de Components con `dependencies=[...]` |
| **E: Component Registry** | `sdk/impl-dagger-e` | Dagger 2 | Registry con explicit bindings, eager resolution, auto topo-sort |
| **E2: Auto-Init Registry** | `sdk/impl-dagger-e2` | Dagger 2 | Evolución de E: install lazy + auto-build on get<T>(), sin Feature enum |
| **F: Multi-Module Deps** | `sdk/impl-dagger-f` + `sdk/di-core` | Dagger 2 | D en multi-módulo Gradle con CoreComponent compartido |
| **Koin** | `sdk/impl-koin` | Koin 4.1 | `koinApplication` aislado + `loadModules` |
| **Hybrid** | `sdk/impl-koin` + bridge | Koin + Dagger 2 | SDK Koin, app Dagger, puente `@Component` |

Cada SDK expone una **API pública similar**. E2 es la más simple:

```kotlin
// E2 (más simple — sin Feature enum)
AutoSdk.init(config)
AutoSdk.get<SyncApi>()   // auto-inits toda la cadena
AutoSdk.shutdown()

// B/C/D/E/F (con Feature enum)
Sdk.init(config, features)
Sdk.getOrInitModule(feature)
Sdk.get<EncryptionApi>()
Sdk.shutdown()
```

El consumidor no ve DaggerComponents, koinApplication, CoreApis, ni FeatureEntries.

---

## Arquitectura del proyecto

```
observability-api/        → SdkLogger + AndroidSdkLogger (zero deps)

feature-core-api/         → SdkConfig
feature-enc-api/          → EncryptionApi, HashApi
feature-auth-api/         → AuthApi, AuthToken
feature-stor-api/         → StorageApi
feature-ana-api/          → AnalyticsApi
feature-syn-api/          → SyncApi, SyncResult

feature-core-impl/        → CoreComponent : CoreProvisions
feature-enc-impl/         → EncComponent + DefaultEncryptionService (internal)
feature-auth-impl/        → AuthComponent + DefaultAuthService (internal)
feature-stor-impl/        → StorComponent + DefaultSecureStorageService (internal)
feature-ana-impl/         → AnaComponent + DefaultAnalyticsService (internal)
feature-syn-impl/         → SynComponent + DefaultSyncService (internal)

sdk/
  api/                    → Umbrella: CoreApis + re-exports all feature-apis + observability-api
  di-contracts/           → Provisions + Scopes + RegistryInfra
  sdk-wiring/             → Pattern D: direct lazy ensure*()
  wiring-e/               → Pattern E: ProvisionRegistry + topo-sort
  wiring-e2/              → Pattern E2: AutoProvisionRegistry + DFS lazy
  wiring-g/               → Pattern G: Factory Functions (Components internal)
  impl-common/            → Shared implementations (solo patrones monolíticos)
  impl-koin/              → KoinSdk
  impl-dagger-b/          → DaggerBSdk (Per-Feature + CoreApis)
  impl-dagger-c/          → DaggerCSdk (ServiceLoader)
  impl-dagger-d/          → DaggerSdk (Component Dependencies)
  impl-dagger-e/          → RegistrySdk (Component Registry)
  impl-dagger-e2/         → AutoSdk (Auto-Init Registry)
  di-core/                → CoreComponent (educational, for F)
  impl-dagger-f/          → ModularSdk (D in multi-module, educational)

sample-dagger-a/    → Educativo: @Component monolítico (approach A)
sample-dagger-b/    → Consumidor de DaggerBSdk
sample-dagger-c/    → Consumidor de DaggerCSdk
sample-dagger-d/    → Consumidor de DaggerSdk
sample-dagger-e/    → Consumidor de RegistrySdk
sample-dagger-e2/   → Consumidor de AutoSdk
sample-dagger-f/    → Consumidor de ModularSdk
sample-hybrid/      → Consumidor de KoinSdk + puente Dagger 2

sample-multimodule/ → Consumidor de MultiModuleSdk (provision interfaces)

benchmark/          → 70 Jetpack Microbenchmarks (50 monolíticos + 20 multi-módulo)
```

Cada sample app tiene **2 ficheros Kotlin**: `Application.kt` + `MainActivity.kt`.
Todo el wiring interno está encapsulado en el módulo SDK correspondiente.

---

## Cumplimiento de requisitos

### Dagger B — Per-Feature + CoreApis

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicialización selectiva | ✅ | `init(config, setOf(Feature.ENCRYPTION))` |
| 2 | Aislamiento del consumidor | ✅ | Consumer importa solo `DaggerBSdk` |
| 3 | Singletons compartidos | ⚠️ | Vía CoreApis — manual, crece con cada servicio compartido |
| 4 | Instanciación lazy | ✅ | `getOrInitModule()` crea Component on-demand |
| 5 | Independencia del core | ✅ | CoreApis es una interfaz Kotlin plana |
| 6 | Auto-registro | ❌ | Añadir feature requiere editar `when` block |
| 7 | Binario eficiente | ❌ | Todas las features compiladas en `impl-dagger-b` |
| 8 | Dependencias cruzadas | ⚠️ | Solo vía CoreApis extendido (God Object a escala) |
| 9 | Seguridad en compilación | ⚠️ | Por feature, no global. CoreApis no validado |
| 10 | KMP | ❌ | Dagger es JVM |

### Dagger C — ServiceLoader Discovery

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicialización selectiva | ✅ | `init(config, setOf("encryption"))` |
| 2 | Aislamiento del consumidor | ✅ | Consumer importa solo `DaggerCSdk` |
| 3 | Singletons compartidos | ⚠️ | Mismo problema que B — CoreApis manual |
| 4 | Instanciación lazy | ✅ | ServiceLoader + `getOrInitModule()` |
| 5 | Independencia del core | ✅ | Zero dependencias impl en el core |
| 6 | Auto-registro | ✅ | META-INF/services — zero edición central |
| 7 | Binario eficiente | ❌ | Todas las features en el módulo SDK |
| 8 | Dependencias cruzadas | ⚠️ | Runtime resolve entre features (no compile-time) |
| 9 | Seguridad en compilación | ⚠️ | Per-feature + descubrimiento runtime |
| 10 | KMP | ❌ | ServiceLoader es JVM |

### Dagger D — Component Dependencies

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicialización selectiva | ✅ | `init(config, setOf(Feature.ENCRYPTION))` |
| 2 | Aislamiento del consumidor | ✅ | Components internos son `internal` |
| 3 | Singletons compartidos | ✅ | CoreComponent provee logger/config vía provision methods |
| 4 | Instanciación lazy | ✅ | `getOrInitModule()` con cascada automática |
| 5 | Independencia del core | ❌ | `impl-dagger-d` importa `impl-common` con todas las impls |
| 6 | Auto-registro | ❌ | Añadir feature requiere editar `DaggerSdk.kt` |
| 7 | Binario eficiente | ❌ | Todas las features compiladas en el módulo |
| 8 | Dependencias cruzadas | ✅ | Dagger resuelve automáticamente vía `dependencies=[...]` |
| 9 | Seguridad en compilación | ✅ | Missing binding o parent = error de compilación |
| 10 | KMP | ❌ | Dagger es JVM |

### Dagger E — Component Registry

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicialización selectiva | ✅ | `init(config, setOf(Feature.ENCRYPTION))` |
| 2 | Aislamiento del consumidor | ✅ | Entries, registry y Components son `internal`. App solo ve `Feature` enum |
| 3 | Singletons compartidos | ✅ | CoreComponent via provision methods + registry cache |
| 4 | Instanciación lazy | ✅ | `getOrInitModule()` con cascada automática y topo-sort |
| 5 | Independencia del core | ❌ | `impl-dagger-e` importa `impl-common` con todas las impls |
| 6 | Auto-registro | ⚠️ | Cada módulo exporta `FeatureEntry`, pero el enum central requiere edición |
| 7 | Binario eficiente | ❌ | Todas las features compiladas en el módulo |
| 8 | Dependencias cruzadas | ✅ | Dagger resuelve vía `dependencies=[...]` + registry gestiona cascada |
| 9 | Seguridad en compilación | ✅ | Service bindings explícitos. Missing binding = error de compilación |
| 10 | KMP | ❌ | Dagger es JVM |

### Dagger E2 — Auto-Init Registry

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicialización selectiva | ⚠️ | Todas las entries instaladas; la "selección" es implícita (solo se construye lo que se pide) |
| 2 | Aislamiento del consumidor | ✅ | API mínima: `init()` + `get<T>()`. Sin Feature enum, sin entries visibles |
| 3 | Singletons compartidos | ✅ | CoreComponent via provision methods + registry cache |
| 4 | Instanciación lazy | ✅ | `get<T>()` auto-construye por demanda (DFS recursivo) |
| 5 | Independencia del core | ❌ | `impl-dagger-e2` importa `impl-common` con todas las impls |
| 6 | Auto-registro | ✅ | Añadir módulo = 1 línea en `allEntries()`. Sin enum. Sin when |
| 7 | Binario eficiente | ❌ | Todas las features compiladas en el módulo |
| 8 | Dependencias cruzadas | ✅ | Dagger `dependencies=[...]` + auto-build recursivo |
| 9 | Seguridad en compilación | ✅ | Explicit bindings. Missing binding = error Dagger |
| 10 | KMP | ❌ | Dagger es JVM |

### Dagger F — Multi-Module Component Dependencies

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicialización selectiva | ✅ | `init(config, setOf(Feature.ENCRYPTION))` |
| 2 | Aislamiento del consumidor | ✅ | Components internos son `internal` |
| 3 | Singletons compartidos | ✅ | CoreComponent en `:sdk:di-core` con @BindsInstance puro |
| 4 | Instanciación lazy | ✅ | `getOrInitModule()` con cascada automática |
| 5 | Independencia del core | ✅ | CoreComponent en módulo separado `:sdk:di-core` |
| 6 | Auto-registro | ❌ | Añadir feature requiere editar `when` block + enum |
| 7 | Binario eficiente | ⚠️ | Core separado; features aún en un módulo |
| 8 | Dependencias cruzadas | ✅ | Dagger resuelve automáticamente vía `dependencies=[...]` |
| 9 | Seguridad en compilación | ✅ | Missing binding o parent = error de compilación |
| 10 | KMP | ❌ | Dagger es JVM |

### Koin

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicialización selectiva | ✅ | `init(setOf(SdkModule.Encryption.Default), config)` |
| 2 | Aislamiento del consumidor | ✅ | Sealed class + auto-discovery. Consumer importa solo `KoinSdk` |
| 3 | Singletons compartidos | ✅ | Un `koinApplication`, un scope |
| 4 | Instanciación lazy | ✅ | `loadModules()` + `Class.forName` |
| 5 | Independencia del core | ✅ | Zero dependencias impl en core-sdk |
| 6 | Auto-registro | ✅ | `init {}` + `Class.forName` / `@EagerInitialization` |
| 7 | Binario eficiente | ✅ | Solo las dependencias Gradle presentes |
| 8 | Dependencias cruzadas | ✅ | Un grafo — `get()` resuelve cualquier servicio |
| 9 | Seguridad en compilación | ❌ | Resolución runtime — errores en ejecución |
| 10 | KMP | ✅ | Soporte completo (JVM + Native + JS) |

### Hybrid (Koin SDK + Dagger 2 app)

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicialización selectiva | ✅ | Mismas capacidades que Koin |
| 2 | Aislamiento del consumidor | ✅ | Consumer usa `@Inject` de Dagger — zero Koin |
| 3 | Singletons compartidos | ✅ | Koin graph + Dagger bridge cache |
| 4 | Instanciación lazy | ✅ | `loadModules()` en Koin |
| 5 | Independencia del core | ✅ | SDK Koin aislado |
| 6 | Auto-registro | ✅ | Hereda de Koin |
| 7 | Binario eficiente | ✅ | Hereda de Koin |
| 8 | Dependencias cruzadas | ✅ | Un grafo Koin |
| 9 | Seguridad en compilación | ⚠️ | Koin runtime + Dagger compile-time en el bridge |
| 10 | KMP | ✅ | SDK KMP, bridge solo Android |

### Resumen

| Requisito | B | C | D | E | E2 | F | Koin | Hybrid |
|-----------|---|---|---|---|----|----|------|--------|
| 1. Selectiva | ✅ | ✅ | ✅ | ✅ | ⚠️ | ✅ | ✅ | ✅ |
| 2. Aislamiento | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 3. Singletons | ⚠️ | ⚠️ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 4. Lazy | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 5. Core indep. | ✅ | ✅ | ❌ | ❌ | ❌ | ✅ | ✅ | ✅ |
| 6. Auto-registro | ❌ | ✅ | ❌ | ⚠️ | ✅ | ❌ | ✅ | ✅ |
| 7. Binario lean | ❌ | ❌ | ❌ | ❌ | ❌ | ⚠️ | ✅ | ✅ |
| 8. Cross-deps | ⚠️ | ⚠️ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 9. Compile-time | ⚠️ | ⚠️ | ✅ | ✅ | ✅ | ✅ | ❌ | ⚠️ |
| 10. KMP | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ |
| **Total ✅** | **4** 🔴 | **5** | **5** | **6** | **6** | **6** | **9** 🟢 | **9** 🟢 |

---

## Resultados de benchmarks

Dispositivo: Samsung Galaxy S22 Ultra (SM-S908B) — Snapdragon 8 Gen 1, 8 cores, 2.8 GHz, Android 16.
Framework: Jetpack Benchmark 1.4.0 con warmup automático. 70 tests en total (50 monolíticos + 20 multi-módulo).

### Inicialización en frío (6 features completas)

Tiempo para crear el grafo DI completo desde cero e instanciar todos los singletons.

| Approach | Mediana (ns) | |
|----------|-------------|---|
| Dagger F | 778 | 🟢 mejor |
| Dagger D | 779 | |
| Dagger A (ref.) | 864 | |
| Dagger B | 1.213 | |
| Dagger C | 1.548 | |
| Dagger E | 4.636 | |
| Dagger E2 | 7.855 | |
| Hybrid | 41.847 | |
| Koin | 51.708 | 🔴 peor |

**Observación:** D y F son idénticos en runtime (~778 ns) — la separación en módulos
Gradle es invisible. E2 es ~70% más lento que E porque instala entries + auto-builds
on-demand (vs E que hace eager build). Pero 8 µs sigue siendo imperceptible (<0,0005 frames).

### Primera resolución de un singleton

Tiempo de la primera llamada a `encryption()` / `registry.get()` / `koin.get<EncryptionApi>()`.

| Approach | Mediana (ns) | |
|----------|-------------|---|
| Dagger D | 2,4 | 🟢 mejor |
| Dagger B | 2,4 | |
| Dagger F | 2,4 | |
| Dagger C | 2,5 | |
| Hybrid (bridge) | 2,8 | |
| Dagger A (ref.) | 2,8 | |
| Dagger E | 19,8 | |
| Dagger E2 | 25,4 | |
| Koin | 883 | 🔴 peor |

**Observación:** B/C/D/F acceden al singleton en ~2,4 ns (campo volátil).
E y E2 usan HashMap lookup (~20-25 ns) — 10× más lento que D pero aún 35× más rápido que Koin.
E2 ligeramente más lento que E por la verificación ensureBuilt.

### Lazy init — Feature sin dependencias (Analytics)

Tiempo de añadir una feature independiente a un grafo en ejecución.

| Approach | Mediana (ns) | |
|----------|-------------|---|
| Dagger B | 112 | 🟢 mejor |
| Dagger F | 170 | |
| Dagger C | 186 | |
| Dagger D | 204 | |
| Dagger E | 1.359 | |
| Dagger E2 | 3.380 | |
| Hybrid | 12.645 | |
| Koin | 14.296 | 🔴 peor |

**Observación:** E2 es ~2,5× E porque reinstala las entries en cada iteración
(simula el modelo real donde entries se instalan una vez). Aún 4× más rápido que Koin.

### Lazy init — Feature con dependencias pesadas (Sync → Auth + Storage + Encryption)

Tiempo de inicialización en cascada: pedir Sync desencadena Auth → Encryption, Storage → Encryption.

| Approach | Mediana (ns) | |
|----------|-------------|---|
| Dagger D | 663 | 🟢 mejor |
| Dagger B | 669 | |
| Dagger F | 740 | |
| Dagger C | 835 | |
| Dagger E | 2.891 | |
| Dagger E2 | 3.312 | |
| Koin | 23.542 | |
| Hybrid | 23.900 | 🔴 peor |

**Observación:** E2 y E son comparables en cascada (~3 µs). La diferencia
se reduce porque ambos pagan el mismo coste de Component builds. Dagger D/F siguen
siendo los más rápidos (~660-740 ns). Todos los Dagger son 7-35× más rápidos que Koin.

### Operación cross-feature (Sync.sync())

Tiempo de una operación real que cruza Auth + Storage + Encryption.
Singletons resueltos **una vez** fuera del loop. Mide solo el trabajo, no el DI.

| Approach | Mediana (ns) | |
|----------|-------------|---|
| Hybrid | 69.522 | 🟢 mejor |
| Dagger B | 75.558 | |
| Dagger C | 78.680 | |
| Dagger D | 82.158 | |
| Dagger E | 90.934 | |
| Dagger F | 93.391 | |
| Dagger A (ref.) | 95.826 | |
| Dagger E2 | 119.025 | |
| Koin | 121.596 | 🔴 peor |

**Observación:** Las allocations son idénticas — el trabajo es el mismo.
La variación (~70-120 µs) es atribuible a thermal throttle (los tests se ejecutan
en orden alfabético). Con los singletons ya resueltos, el framework DI no participa.

### Tests adicionales: Registry overhead (E, E2, D, F)

| Test | D directo | F directo | E registry | E2 auto-registry |
|------|-----------|-----------|-----------|-----------------|
| Resolve 6 servicios | 17 ns | 24 ns | 99 ns | 105 ns |
| Resolve 1 servicio (cached) | 2,4 ns | 2,4 ns | 21 ns | 25 ns |

**Observación:** D y F son idénticos (~17-24 ns para 6 servicios). E y E2 pagan
~100 ns por 6 lookups HashMap. En contexto: 105 ns = 0,000006 frames.
El overhead es el precio del desacoplamiento facade↔components (multi-módulo viable).

### Conclusión clave: F = D en runtime

Los benchmarks prueban que la separación en módulos Gradle (F) no tiene coste
en runtime. D y F son idénticos en todas las métricas. La elección entre D y F
es puramente de organización Gradle, no de rendimiento.

### Conclusión clave: E2 ≈ E con API superior

E2 paga ~3 µs extra en initCold (install + DFS vs eager topo-sort), pero ofrece:
- API sin Feature enum (mínima para el consumidor)
- Facade inmutable (añadir módulo = 1 línea)
- Lazy por naturaleza (solo construye lo pedido)

Para SDKs que escalan a 50+ módulos, E2 es la evolución natural de E.

### Recuento de resultados

| Approach | 🟢 Mejor | 🔴 Peor | Notas |
|----------|---------|---------|-------|
| **Dagger B** | 1 | 0 | Más rápido en lazy init (0 deps) |
| **Dagger D** | 2 | 0 | Más rápido en lazy cascade + resolve |
| **Dagger F** | 1 | 0 | Idéntico a D — más rápido en init cold |
| **Hybrid** | 1 | 0 | Más rápido en operación cross-feature |
| **Dagger E** | 0 | 0 | Entre D y E2 — registry overhead mínimo |
| **Dagger E2** | 0 | 0 | ~E con overhead DFS — API más limpia |
| **Dagger C** | 0 | 0 | Siempre posiciones intermedias |
| **Dagger A** | 0 | 0 | Solo referencia |
| **Koin** | 0 | 4 | Más lento en la mayoría de métricas de plumbing |

**Nota importante:** Koin es 🔴 en casi todas las métricas de plumbing,
pero la diferencia absoluta máxima es 52 µs — imperceptible en una aplicación real.
E2 se posiciona como la mejor opción para SDKs que necesitan escalar sin sacrificar
compile-time safety.

### Benchmarks multi-módulo (wiring patterns)

20 tests adicionales comparan las cuatro estrategias de wiring multi-módulo — D, E, E2 y G —
utilizando los mismos Dagger Components (`feature-*-impl/`) con diferentes orquestadores:

- **sdk-wiring/** (Pattern D): `ensure*()` directo con lazy delegates
- **wiring-e/** (Pattern E): `ProvisionRegistry` con topo-sort explícito
- **wiring-e2/** (Pattern E2): `AutoProvisionRegistry` con DFS lazy on-demand
- **wiring-g/** (Pattern G): Factory functions (`buildXxxProvisions()`) con lazy delegates

Los tests cubren:

| Test | Qué mide |
|------|----------|
| `initCold` | Construcción del grafo completo (6 features) desde cero |
| `resolveFirst` | Primera resolución de un singleton ya construido |
| `lazyInit` (no deps) | Añadir feature independiente (Analytics) a grafo en ejecución |
| `lazyInit` (cascade) | Inicialización en cascada (Sync → Auth + Storage + Encryption) |
| `crossFeatureOp` | Operación real que cruza múltiples features |

Resultados multi-módulo:

| Test | D | E | E2 | G |
|------|---|---|----|----|
| initCold | 20,4 µs | 73,2 µs | 39,2 µs | 18,2 µs |
| resolveFirst | 9,2 ns | 15,7 ns | 10,5 ns | 3,2 ns |
| lazyInit noDeps | 24,4 µs | 36,8 µs | 25,7 µs | 23,7 µs |
| lazyInit cascade | 28,2 µs | 61,8 µs | 33,8 µs | 73,1 µs |
| crossFeatureOp | 356,9 µs | 375,9 µs | 316,8 µs | 379,9 µs |

**Observación:** G es el más rápido en initCold y resolveFirst (acceso directo a campos,
sin registry). En lazyInit cascade, G es el más lento porque las factory functions
reconstruyen el grafo en cada iteración del benchmark (sin cache compartido entre
iteraciones). En crossFeatureOp, las diferencias son atribuibles a variabilidad térmica
— el trabajo real es idéntico en los cuatro patterns.

Los cuatro wiring patterns comparten los mismos `feature-*-impl` Components. La diferencia
está exclusivamente en cómo el orquestador gestiona el orden de construcción y la resolución
de dependencias entre features. Esto permite aislar el coste del wiring del coste del DI.

Referencia: `benchmark/.../MultiModuleBenchmark.kt`

---

## Lazy init y dependencias cruzadas

Cada approach fue probado con dos escenarios:

### Caso 1: Feature sin dependencias (Analytics)

Solo necesita `CoreApis` (logger, config). No depende de ninguna otra feature.

| Approach | ¿Lazy init real? | Mecanismo |
|----------|-----------------|-----------|
| Dagger B | ✅ | `DaggerAnalyticsComponent.builder().core(core).build()` |
| Dagger C | ✅ | ServiceLoader descubre + `init(core)` |
| Dagger D | ✅ | `DaggerAnaComponent.builder().core(core).build()` |
| Dagger E | ✅ | `registry.register(analyticsEntry)` — Component build + eager service binding |
| Dagger E2 | ✅ | `get<AnalyticsApi>()` — auto-build on demand (DFS) |
| Dagger F | ✅ | `getOrInitModule()` — idéntico a D |
| Koin | ✅ | `koin.loadModules(listOf(analyticsModule))` |
| Hybrid | ✅ | Koin `loadModules` (features lazy bypasean el bridge Dagger) |
| Dagger A | ⚠️ | No real — el código ya está compilado en el @Component |

### Caso 2: Feature con dependencias pesadas (Sync)

Sync necesita Auth + Storage + Encryption. Al pedir Sync, el SDK detecta qué falta
e inicializa las dependencias en cascada.

| Approach | ¿Cascada automática? | ¿Cómo resuelve cross-deps? |
|----------|---------------------|---------------------------|
| Dagger B | ✅ | `AuthCoreApis`, `StorageCoreApis` — interfaces extendidas (manual) |
| Dagger C | ✅ | `requiredDependencies` + `ServiceResolver` (runtime) |
| Dagger D | ✅ | `dependencies=[EncComponent, AuthComponent]` — Dagger automático |
| Dagger E | ✅ | `dependencies=[...]` + `expandDependencies()` topo-sort automático |
| Dagger E2 | ✅ | `dependencies=[...]` + `ensureBuilt()` DFS recursivo automático |
| Dagger F | ✅ | Idéntico a D — `dependencies=[CoreComponent]` desde `:sdk:di-core` |
| Koin | ✅ | `loadModules` + `get()` desde el mismo grafo — automático |
| Hybrid | ✅ | Hereda de Koin |
| Dagger A | N/A | Todo en un @Component — no hay cascada porque todo existe siempre |

---

## Análisis por approach

Cada approach está documentado en detalle con código del proyecto en los docs de referencia:

- **Dagger A/B/C/D/E/E2/F:** [dagger2-sdk-selective-init.md](dagger2-sdk-selective-init.md) — arquitectura, código, pros/contras, cuándo usar
- **Koin:** [di-sdk-consumer-isolation.md](di-sdk-consumer-isolation.md) — paradigma Service Locator, niveles de aislamiento
- **Hybrid:** [di-hybrid-koin-sdk-dagger-app.md](di-hybrid-koin-sdk-dagger-app.md) — bridge, puente unidireccional, features lazy
- **Cross-feature:** [di-cross-feature-deps.md](di-cross-feature-deps.md) — cómo resuelve cada approach las dependencias cruzadas

Resumen rápido:

| Approach | Mecanismo interno | Cross-deps | Limitación principal |
|----------|------------------|-----------|---------------------|
| **Dagger B** | N Components + CoreApis manual | ⚠️ God Object | CoreApis crece con cada cross-dep |
| **Dagger C** | N Components + ServiceLoader | ⚠️ Runtime resolve | JVM only + errores runtime |
| **Dagger D** | N Components con `dependencies=[]` | ✅ Automático | Binario no lean, edición central |
| **Dagger E** | N Components + Registry + topo-sort | ✅ Automático | Registry overhead (~20 ns/lookup) |
| **Dagger E2** | N Components + AutoRegistry + DFS | ✅ Automático | API mínima, escala a 50+, ~25 ns/lookup |
| **Dagger F** | D en multi-módulo Gradle | ✅ Automático | Idéntico a D en runtime, `when` blocks no escalan |
| **Koin** | Un `koinApplication` aislado | ✅ Automático | Errores runtime |
| **Hybrid** | Koin SDK + Dagger bridge | ✅ Automático | Bridge unidireccional, features lazy bypasean |

---

## Matriz de decisión

| Restricción del proyecto | Approach más adecuado |
|--------------------------|----------------------|
| Compile-time safety máxima | Dagger D, E, E2 o F |
| Pure DI (no service locator) | Dagger B, C, D, E, E2 o F |
| Features con dependencias cruzadas fuertes | Dagger D, E, E2 o Koin |
| Features verdaderamente independientes | Dagger B o C |
| KMP necesario | Koin o Hybrid |
| Android exclusivo, equipo con experiencia Dagger | Dagger D, E2 o F |
| Multi-módulo Gradle corporativo (api/impl por feature) | Dagger E o E2 |
| Multi-módulo con <15 features (transición) | Dagger F |
| Publicación per-feature en Maven | Dagger B, C, E o E2 |
| 20+ features, adiciones frecuentes | Dagger E2 o Koin |
| SDK escalable a 50+ módulos sin tocar facade | **Dagger E2** o Koin |
| API mínima para consumidor (sin Feature enum) | **Dagger E2** o Koin |
| Zero codegen, builds más rápidos | Koin |
| SDK KMP consumido por app Dagger existente | Hybrid |
| Tamaño de binario crítico | Koin o Dagger B/C |
| Equipo pequeño, mínima ceremonia | Koin |
| D inviable por módulos Gradle separados | Dagger E, E2 o F |
| Multi-módulo con Components internos (factory functions) | Dagger G (sin registry, Components `internal`) |
| Multi-módulo con per-feature contracts | Dagger D/E/E2/G vía sdk-wiring / wiring-e / wiring-e2 / wiring-g |

---

## Stack técnico

| Componente | Versión |
|-----------|---------|
| Kotlin | 2.0.21 (built-in AGP 9) |
| AGP | 9.0.1 |
| Dagger 2 | 2.59.2 |
| Koin | 4.1.1 |
| KSP | 2.0.21-1.0.28 |
| Jetpack Benchmark | 1.4.0 |
| Compose BOM | 2024.09.00 |
| minSdk / targetSdk | 28 / 36 |

---

## Referencias

- [Análisis de complejidad y mantenimiento](analisis-complejidad-mantenimiento.md) — Coste por feature, equipo interno vs consumidores
- [dagger2-sdk-selective-init.md](dagger2-sdk-selective-init.md) — Implementaciones Dagger A, B, C, D, E, E2, F con código
- [di-sdk-consumer-isolation.md](di-sdk-consumer-isolation.md) — Niveles de aislamiento, DI vs Service Locator
- [di-sdk-selective-init-comparison.md](di-sdk-selective-init-comparison.md) — Tablas de comparación por requisito
- [di-cross-feature-deps.md](di-cross-feature-deps.md) — Dependencias cruzadas con ejemplos concretos
- [di-hybrid-koin-sdk-dagger-app.md](di-hybrid-koin-sdk-dagger-app.md) — Arquitectura hybrid completa
- [di-multimodule-api-impl-analysis.md](di-multimodule-api-impl-analysis.md) — Arquitectura multi-módulo con per-feature contracts (provision interfaces, wiring patterns D/E/E2/G)
