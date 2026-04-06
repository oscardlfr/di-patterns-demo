# Análisis de Arquitecturas DI para SDKs Modulares

Documento de referencia técnica. Presenta los resultados del proyecto `di-patterns-demo`:
múltiples implementaciones de SDK con inyección de dependencias (monolíticas y multi-módulo),
benchmarks en dispositivo real (Samsung Galaxy S22 Ultra, Android 16) y análisis de
cumplimiento de requisitos.

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

### Patrones monoliticos

| Approach | Modulo SDK | Framework | Mecanismo interno |
|----------|-----------|-----------|-------------------|
| **B: Per-Feature** | `sdk/impl-dagger-b` | Dagger 2 | N Components aislados + CoreApis manual |
| **C: ServiceLoader** | `sdk/impl-dagger-c` | Dagger 2 | N Components + descubrimiento META-INF/services |
| **Koin** | `sdk/impl-koin` | Koin 4.1 | `koinApplication` aislado + `loadModules` |
| **Hybrid** | `sdk/impl-koin` + bridge | Koin + Dagger 2 | SDK Koin, app Dagger, puente `@Component` |

### Patrones multi-modulo (provision interfaces)

| Approach | Modulo wiring | Framework | Mecanismo interno |
|----------|--------------|-----------|-------------------|
| **D: Component Dependencies** | `sdk/sdk-wiring` | Dagger 2 | Jerarquia de Components con `dependencies=[...]`, lazy `ensure*()` |
| **E: Component Registry** | `sdk/wiring-e` | Dagger 2 | ProvisionRegistry + topo-sort, explicit bindings |
| **E2: Auto-Init Registry** | `sdk/wiring-e2` | Dagger 2 | AutoProvisionRegistry + DFS lazy on-demand, sin Feature enum |
| **G: Factory Functions** | `sdk/wiring-g` | Dagger 2 | Factory functions, Components `internal` |
| **H: Auto-Discovery FeatureProviders** | `sdk/wiring-h` | Dagger 2 | FeatureProviders + DFS resolver, wiring inmutable |

D, E, E2, G y H solo existen como variantes multi-modulo. No tienen modulos SDK monoliticos.

Cada SDK expone una **API publica similar**:

```kotlin
// Multi-modulo E2 (mas simple — sin Feature enum)
MultiModuleSdkE2.init(config)
MultiModuleSdkE2.get<SyncApi>()   // auto-inits toda la cadena
MultiModuleSdkE2.shutdown()

// Monolitico B/C (con Feature enum)
Sdk.init(config, features)
Sdk.getOrInitModule(feature)
Sdk.get<EncryptionApi>()
Sdk.shutdown()
```

El consumidor no ve DaggerComponents, koinApplication, CoreApis, ni FeatureEntries.

---

## Arquitectura del proyecto

```
observability-api/              → SdkLogger (interface)
feature-observability-impl/     → AndroidSdkLogger + ObservabilityComponent

feature-core-api/         → SdkConfig
feature-enc-api/          → EncryptionApi, HashApi
feature-auth-api/         → AuthApi, AuthToken
feature-stor-api/         → StorageApi
feature-ana-api/          → AnalyticsApi
feature-syn-api/          → SyncApi, SyncResult

feature-core-impl/        → CoreComponent + buildCoreProvisions()
feature-enc-impl/         → EncComponent + DefaultEncryptionService + buildEncProvisions() + EncProvider
feature-auth-impl/        → AuthComponent + DefaultAuthService + buildAuthProvisions() + AuthProvider
feature-stor-impl/        → StorComponent + DefaultSecureStorageService + buildStorProvisions() + StorProvider
feature-ana-impl/         → AnaComponent + DefaultAnalyticsService + buildAnaProvisions() + AnaProvider
feature-syn-impl/         → SynComponent + DefaultSyncService + buildSynProvisions() + SynProvider

sdk/
  api/                    → Umbrella: CoreApis + re-exports all feature-apis + observability-api
  di-contracts/           → Provisions + Scopes + RegistryInfra + FeatureProvider + Resolver + FeatureProvider + Resolver
  sdk-wiring/             → Pattern D multi-módulo: direct lazy ensure*()
  wiring-e/               → Pattern E multi-módulo: ProvisionRegistry + topo-sort
  wiring-e2/              → Pattern E2 multi-módulo: AutoProvisionRegistry + DFS lazy
  wiring-g/               → Pattern G multi-módulo: Factory Functions (Components internal)
  wiring-h/               → Pattern H multi-módulo: Auto-Discovery FeatureProviders (DFS resolver)
  impl-common/            → Shared implementations (solo patrones monolíticos)
  impl-koin/              → KoinSdk
  impl-dagger-b/          → DaggerBSdk (Per-Feature + CoreApis)
  impl-dagger-c/          → DaggerCSdk (ServiceLoader)

sample-dagger-a/    → Educativo: @Component monolítico (approach A)
sample-dagger-b/    → Consumidor de DaggerBSdk
sample-dagger-c/    → Consumidor de DaggerCSdk
sample-hybrid/      → Consumidor de KoinSdk + puente Dagger 2
sample-multimodule/ → Consumidor de MultiModuleSdkH (Pattern H, provision interfaces)

benchmark/          → 74 Jetpack Microbenchmarks (19 monolíticos vía facades + 55 multi-módulo vía facades)
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

### Dagger D — Component Dependencies (multi-módulo: sdk-wiring)

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicialización selectiva | ✅ | Lazy `ensure*()` construye on-demand |
| 2 | Aislamiento del consumidor | ✅ | Components internos, app solo ve facade |
| 3 | Singletons compartidos | ✅ | CoreComponent provee config vía CoreProvisions, logger vía ObservabilityProvisions |
| 4 | Instanciación lazy | ✅ | `ensure*()` con cascada automática |
| 5 | Independencia del core | ✅ | Cada feature-impl compila independientemente con provision interfaces |
| 6 | Auto-registro | ❌ | Añadir feature requiere editar `when` blocks en el facade |
| 7 | Binario eficiente | ✅ | Solo los feature-impl incluidos en Gradle |
| 8 | Dependencias cruzadas | ✅ | Dagger resuelve automáticamente vía `dependencies=[...]` |
| 9 | Seguridad en compilación | ✅ | Missing binding o parent = error de compilación |
| 10 | KMP | ❌ | Dagger es JVM |

### Dagger E — Component Registry (multi-módulo: wiring-e)

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicialización selectiva | ✅ | ProvisionRegistry con Feature enum |
| 2 | Aislamiento del consumidor | ✅ | Entries, registry y Components son `internal`. App solo ve `Feature` enum |
| 3 | Singletons compartidos | ✅ | CoreComponent provee config vía CoreProvisions, logger vía ObservabilityProvisions + registry cache |
| 4 | Instanciación lazy | ✅ | Registry con cascada automática y topo-sort |
| 5 | Independencia del core | ✅ | Cada feature-impl compila independientemente con provision interfaces |
| 6 | Auto-registro | ⚠️ | Cada módulo exporta `FeatureEntry`, pero el enum central requiere edición |
| 7 | Binario eficiente | ✅ | Solo los feature-impl incluidos en Gradle |
| 8 | Dependencias cruzadas | ✅ | Dagger resuelve vía `dependencies=[...]` + registry gestiona cascada |
| 9 | Seguridad en compilación | ✅ | Service bindings explícitos. Missing binding = error de compilación |
| 10 | KMP | ❌ | Dagger es JVM |

### Dagger E2 — Auto-Init Registry (multi-módulo: wiring-e2)

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicialización selectiva | ⚠️ | Todas las entries instaladas; la "selección" es implícita (solo se construye lo que se pide) |
| 2 | Aislamiento del consumidor | ✅ | API mínima: `init()` + `get<T>()`. Sin Feature enum, sin entries visibles |
| 3 | Singletons compartidos | ✅ | CoreComponent provee config vía CoreProvisions, logger vía ObservabilityProvisions + registry cache |
| 4 | Instanciación lazy | ✅ | `get<T>()` auto-construye por demanda (DFS recursivo) |
| 5 | Independencia del core | ✅ | Cada feature-impl compila independientemente con provision interfaces |
| 6 | Auto-registro | ✅ | Añadir módulo = 1 línea en `allEntries()`. Sin enum. Sin when |
| 7 | Binario eficiente | ✅ | Solo los feature-impl incluidos en Gradle |
| 8 | Dependencias cruzadas | ✅ | Dagger `dependencies=[...]` + auto-build recursivo |
| 9 | Seguridad en compilación | ✅ | Explicit bindings. Missing binding = error Dagger |
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

### Dagger G — Factory Functions (multi-módulo: wiring-g)

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicialización selectiva | ✅ | Lazy `ensure*()` construye on-demand vía factory functions |
| 2 | Aislamiento del consumidor | ✅ | Components `internal`, app solo ve facade |
| 3 | Singletons compartidos | ✅ | CoreComponent provee config vía CoreProvisions, logger vía ObservabilityProvisions |
| 4 | Instanciación lazy | ✅ | `ensure*()` con cascada automática vía factory functions |
| 5 | Independencia del core | ✅ | Cada feature-impl compila independientemente con provision interfaces |
| 6 | Auto-registro | ❌ | Añadir feature requiere editar `ensure*()` en el facade (= D) |
| 7 | Binario eficiente | ✅ | Solo los feature-impl incluidos en Gradle |
| 8 | Dependencias cruzadas | ✅ | Factory functions reciben provision interfaces — Dagger resuelve |
| 9 | Seguridad en compilación | ✅ | Dagger valida cada Component + dependencias en compilación |
| 10 | KMP | ❌ | Dagger es JVM |

### Dagger H — Auto-Discovery FeatureProviders (multi-módulo: wiring-h)

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicialización selectiva | ⚠️ | Todos los providers instalados; la "selección" es implícita (solo construye lo que se pide) |
| 2 | Aislamiento del consumidor | ✅ | API mínima: `init()` + `get<T>()`. Wiring inmutable |
| 3 | Singletons compartidos | ✅ | CoreComponent provee config vía CoreProvisions, logger vía ObservabilityProvisions + resolver cache |
| 4 | Instanciación lazy | ✅ | `get<T>()` dispara DFS vía `resolver.provision()` |
| 5 | Independencia del core | ✅ | Cada feature-impl compila independientemente con provision interfaces |
| 6 | Auto-registro | ✅ | Wiring inmutable — descubre providers, registra, done. Zero edición central |
| 7 | Binario eficiente | ✅ | Solo los feature-impl incluidos en Gradle |
| 8 | Dependencias cruzadas | ✅ | DFS automático vía `resolver.provision(CoreProvisions::class.java)` |
| 9 | Seguridad en compilación | ⚠️ | Dagger valida cada Component, pero provider faltante es error runtime |
| 10 | KMP | ❌ | Dagger es JVM |

### Resumen

| Requisito | B | C | D (multi) | E (multi) | E2 (multi) | G (multi) | H (multi) | Koin | Hybrid |
|-----------|---|---|-----------|-----------|------------|-----------|-----------|------|--------|
| 1. Selectiva | ✅ | ✅ | ✅ | ✅ | ⚠️ | ✅ | ⚠️ | ✅ | ✅ |
| 2. Aislamiento | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 3. Singletons | ⚠️ | ⚠️ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 4. Lazy | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 5. Core indep. | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 6. Auto-registro | ❌ | ✅ | ❌ | ⚠️ | ✅ | ❌ | ✅ | ✅ | ✅ |
| 7. Binario lean | ❌ | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 8. Cross-deps | ⚠️ | ⚠️ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 9. Compile-time | ⚠️ | ⚠️ | ✅ | ✅ | ✅ | ✅ | ⚠️ | ❌ | ⚠️ |
| 10. KMP | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ |
| **Total ✅** | **4** 🔴 | **5** | **8** | **8** | **8** | **8** | **8** | **9** 🟢 | **9** 🟢 |

**Nota:** D, E, E2, G y H son exclusivamente multi-módulo (provision interfaces en módulos Gradle separados).
No existen como SDKs monolíticos.

---

## Resultados de benchmarks

Dispositivo: Samsung Galaxy S22 Ultra (SM-S908B) — Snapdragon 8 Gen 1, 8 cores, 2.8 GHz, Android 16.
Framework: Jetpack Benchmark 1.4.0 con warmup automático. 74 tests en total (19 monolíticos vía facades + 55 multi-módulo vía facades).
El benchmark no contiene Components internos propios — todos los tests usan las facades reales de los SDKs
(DaggerBSdk, DaggerCSdk, KoinSdk, Hybrid bridge para monolíticos; MultiModuleSdk/E/E2/G/H para multi-módulo).

### Inicialización en frío — Monolíticos (vía facades reales)

Tiempo para crear el grafo DI completo desde cero e instanciar todos los singletons.
Solo patrones monolíticos (A educativo, B, C, Koin, Hybrid).

| Approach | Mediana (ns) | |
|----------|-------------|---|
| Dagger A (ref.) | 864 | |
| Dagger B | 1.213 | 🟢 mejor Dagger |
| Dagger C | 1.548 | |
| Hybrid | 41.847 | |
| Koin | 51.708 | 🔴 peor |

**Observación:** B es el Dagger monolítico más rápido en cold init.
Los approaches D, E, E2, G y H se miden exclusivamente en la sección multi-módulo.

### Primera resolución de un singleton — Monolíticos

Tiempo de la primera llamada a `encryption()` / `koin.get<EncryptionApi>()`.

| Approach | Mediana (ns) | |
|----------|-------------|---|
| Dagger B | 2,4 | 🟢 mejor |
| Dagger C | 2,5 | |
| Hybrid (bridge) | 2,8 | |
| Dagger A (ref.) | 2,8 | |
| Koin | 883 | 🔴 peor |

**Observación:** B/C acceden al singleton en ~2,4 ns (campo volátil).
Koin paga ~900 ns por resolve runtime.

### Lazy init — Feature sin dependencias (Analytics) — Monolíticos

Tiempo de añadir una feature independiente a un grafo en ejecución.

| Approach | Mediana (ns) | |
|----------|-------------|---|
| Dagger B | 112 | 🟢 mejor |
| Dagger C | 186 | |
| Hybrid | 12.645 | |
| Koin | 14.296 | 🔴 peor |

**Observación:** B es el más rápido en lazy init sin dependencias (~112 ns).
Koin y Hybrid pagan ~12-14 µs.

### Lazy init — Feature con dependencias pesadas (Sync → Auth + Storage + Encryption) — Monolíticos

Tiempo de inicialización en cascada: pedir Sync desencadena Auth → Encryption, Storage → Encryption.

| Approach | Mediana (ns) | |
|----------|-------------|---|
| Dagger B | 669 | 🟢 mejor |
| Dagger C | 835 | |
| Koin | 23.542 | |
| Hybrid | 23.900 | 🔴 peor |

**Observación:** Todos los Dagger monolíticos son 7-35× más rápidos que Koin en cascada.

### Operación cross-feature (Sync.sync()) — Monolíticos

Tiempo de una operación real que cruza Auth + Storage + Encryption.
Singletons resueltos **una vez** fuera del loop. Mide solo el trabajo, no el DI.

| Approach | Mediana (ns) | |
|----------|-------------|---|
| Hybrid | 69.522 | 🟢 mejor |
| Dagger B | 75.558 | |
| Dagger C | 78.680 | |
| Dagger A (ref.) | 95.826 | |
| Koin | 121.596 | 🔴 peor |

**Observación:** Las allocations son idénticas — el trabajo es el mismo.
La variación (~70-120 µs) es atribuible a thermal throttle. Con los singletons ya resueltos,
el framework DI no participa.

### Recuento de resultados — Monolíticos

| Approach | 🟢 Mejor | 🔴 Peor | Notas |
|----------|---------|---------|-------|
| **Dagger B** | 1 | 0 | Más rápido en lazy init (0 deps) y cascada |
| **Hybrid** | 1 | 0 | Más rápido en operación cross-feature |
| **Dagger C** | 0 | 0 | Siempre posiciones intermedias |
| **Dagger A** | 0 | 0 | Solo referencia |
| **Koin** | 0 | 4 | Más lento en la mayoría de métricas de plumbing |

**Nota importante:** Koin es 🔴 en casi todas las métricas de plumbing,
pero la diferencia absoluta máxima es 52 µs — imperceptible en una aplicación real.
Los approaches D, E, E2, G y H se miden exclusivamente en los benchmarks multi-módulo (sección siguiente).

### Benchmarks multi-módulo (wiring patterns)

55 tests adicionales (25 core + 30 stress) comparan las cinco estrategias de wiring multi-módulo — D, E, E2, G y H —
utilizando los mismos Dagger Components (`feature-*-impl/`) con diferentes orquestadores:

- **sdk-wiring/** (Pattern D): `ensure*()` directo con lazy delegates
- **wiring-e/** (Pattern E): `ProvisionRegistry` con topo-sort explícito
- **wiring-e2/** (Pattern E2): `AutoProvisionRegistry` con DFS lazy on-demand
- **wiring-g/** (Pattern G): Factory functions (`buildXxxProvisions()`) con lazy delegates
- **wiring-h/** (Pattern H): Auto-Discovery FeatureProviders con DFS resolver

Los tests cubren:

| Test | Qué mide |
|------|----------|
| `initCold` | Construcción del grafo completo (6 features) desde cero |
| `resolveFirst` | Primera resolución de un singleton ya construido |
| `lazyInit` (no deps) | Añadir feature independiente (Analytics) a grafo en ejecución |
| `lazyInit` (cascade) | Inicialización en cascada (Sync → Auth + Storage + Encryption) |
| `crossFeatureOp` | Operación real que cruza múltiples features |

Resultados multi-módulo:

| Test | D | G | H | E2 | E |
|------|---|---|---|----|----|
| initCold | 947 ns | 966 ns | 3,5 µs | 5,4 µs | 10,2 µs |
| resolveFirst | 10,6 ns | 14,2 ns | 23,3 ns | 23,3 ns | 20,1 ns |
| lazyInit noDeps | 211 ns | 216 ns | 482 ns | 545 ns | 1,8 µs |
| lazyInit cascade | 600 ns | 606 ns | 1,4 µs | 1,8 µs | 5,8 µs |
| crossFeatureOp | 102,5 µs | 102,3 µs | 103,2 µs | 103,0 µs | 101,7 µs |

**Observación:** D y G son los más rápidos — acceso directo a campos, sin registry.
H paga ~3,5x sobre G en initCold por overhead de HashMap + registro de providers,
pero escala mejor (wiring inmutable). En crossFeatureOp, las diferencias son atribuibles
a variabilidad térmica — el trabajo real es idéntico en los cinco patterns.

Los cinco wiring patterns comparten los mismos `feature-*-impl` Components. La diferencia
está exclusivamente en cómo el orquestador gestiona el orden de construcción y la resolución
de dependencias entre features. Esto permite aislar el coste del wiring del coste del DI.

Referencia: `benchmark/.../MultiModuleBenchmark.kt`

### Todos los approaches — Vista unificada

Tabla consolidada con los 9 approaches (monolíticos + multi-módulo). Permite comparar
directamente entre familias de patrones. Valores del S22 Ultra.

#### initCold (grafo completo, 6 features)

| Approach | Mediana | Tipo |
|----------|---------|------|
| MM-D | 947 ns | Multi-módulo |
| MM-G | 966 ns | Multi-módulo |
| Dagger-B | 2,5 µs | Monolítico |
| MM-H | 3,5 µs | Multi-módulo |
| MM-E2 | 5,4 µs | Multi-módulo |
| Dagger-C | 5,6 µs | Monolítico |
| MM-E | 10,2 µs | Multi-módulo |
| Hybrid | 48,6 µs | Monolítico |
| Koin | 48,2 µs | Monolítico |

#### resolveFirst (primer acceso a singleton)

| Approach | Mediana | Tipo |
|----------|---------|------|
| Hybrid | 1,9 ns | Monolítico |
| Dagger-B | 7,5 ns | Monolítico |
| MM-D | 10,6 ns | Multi-módulo |
| MM-G | 14,2 ns | Multi-módulo |
| MM-E | 20,1 ns | Multi-módulo |
| MM-H | 23,3 ns | Multi-módulo |
| MM-E2 | 23,3 ns | Multi-módulo |
| Dagger-C | 33,9 ns | Monolítico |
| Koin | 835,8 ns | Monolítico |

#### lazyInit — Feature sin dependencias (Analytics)

| Approach | Mediana | Tipo |
|----------|---------|------|
| MM-D | 211 ns | Multi-módulo |
| MM-G | 216 ns | Multi-módulo |
| Dagger-B | 389 ns | Monolítico |
| Dagger-C | 436 ns | Monolítico |
| MM-H | 482 ns | Multi-módulo |
| MM-E2 | 545 ns | Multi-módulo |
| MM-E | 1,8 µs | Multi-módulo |
| Koin | 7,4 µs | Monolítico |

#### lazyInit — Cascada (Sync -> Auth + Storage + Encryption)

| Approach | Mediana | Tipo |
|----------|---------|------|
| MM-D | 600 ns | Multi-módulo |
| MM-G | 606 ns | Multi-módulo |
| MM-H | 1,4 µs | Multi-módulo |
| Dagger-B | 1,6 µs | Monolítico |
| MM-E2 | 1,8 µs | Multi-módulo |
| Dagger-C | 1,9 µs | Monolítico |
| MM-E | 5,8 µs | Multi-módulo |
| Koin | 25,3 µs | Monolítico |

#### crossFeatureOp (Sync.sync())

| Approach | Mediana | Tipo |
|----------|---------|------|
| Todos | ~98–168 µs | — |

**Observación:** En crossFeatureOp las diferencias son ruido térmico — el framework DI no participa.

---

## Lazy init y dependencias cruzadas

Cada approach fue probado con dos escenarios:

### Caso 1: Feature sin dependencias (Analytics)

Solo necesita `CoreApis` (logger, config). No depende de ninguna otra feature.

| Approach | ¿Lazy init real? | Mecanismo |
|----------|-----------------|-----------|
| Dagger B | ✅ | `DaggerAnalyticsComponent.builder().core(core).build()` |
| Dagger C | ✅ | ServiceLoader descubre + `init(core)` |
| Dagger D (multi) | ✅ | `ensure*()` con lazy delegates en sdk-wiring |
| Dagger E (multi) | ✅ | `registry.register(analyticsEntry)` — Component build + eager service binding |
| Dagger E2 (multi) | ✅ | `get<AnalyticsApi>()` — auto-build on demand (DFS) |
| Dagger G (multi) | ✅ | `ensure*()` vía factory functions |
| Dagger H (multi) | ✅ | `resolver.provision()` auto-build on demand (DFS) |
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
| Dagger D (multi) | ✅ | `dependencies=[EncProvisions, CoreProvisions]` — Dagger automático |
| Dagger E (multi) | ✅ | `dependencies=[...]` + `expandDependencies()` topo-sort automático |
| Dagger E2 (multi) | ✅ | `dependencies=[...]` + `ensureBuilt()` DFS recursivo automático |
| Dagger G (multi) | ✅ | Factory functions con lazy `ensure*()` (= D) |
| Dagger H (multi) | ✅ | FeatureProviders + DFS vía `resolver.provision()` automático |
| Koin | ✅ | `loadModules` + `get()` desde el mismo grafo — automático |
| Hybrid | ✅ | Hereda de Koin |
| Dagger A | N/A | Todo en un @Component — no hay cascada porque todo existe siempre |

---

## Análisis por approach

Cada approach está documentado en detalle con código del proyecto en los docs de referencia:

- **Dagger A/B/C/D/E/E2/G/H:** [dagger2-sdk-selective-init.md](dagger2-sdk-selective-init.md) — arquitectura, código, pros/contras, cuándo usar
- **Koin:** [di-sdk-consumer-isolation.md](di-sdk-consumer-isolation.md) — paradigma Service Locator, niveles de aislamiento
- **Hybrid:** [di-hybrid-koin-sdk-dagger-app.md](di-hybrid-koin-sdk-dagger-app.md) — bridge, puente unidireccional, features lazy
- **Cross-feature:** [di-cross-feature-deps.md](di-cross-feature-deps.md) — cómo resuelve cada approach las dependencias cruzadas

Resumen rápido:

| Approach | Mecanismo interno | Cross-deps | Limitación principal |
|----------|------------------|-----------|---------------------|
| **Dagger B** (monolítico) | N Components + CoreApis manual | ⚠️ God Object | CoreApis crece con cada cross-dep |
| **Dagger C** (monolítico) | N Components + ServiceLoader | ⚠️ Runtime resolve | JVM only + errores runtime |
| **Dagger D** (multi-módulo) | N Components con `dependencies=[]` + provision interfaces | ✅ Automático | `when` blocks crecen linealmente |
| **Dagger E** (multi-módulo) | N Components + Registry + topo-sort | ✅ Automático | Feature enum crece, registry overhead (~20 ns/lookup) |
| **Dagger E2** (multi-módulo) | N Components + AutoRegistry + DFS | ✅ Automático | API mínima, escala a 50+, ~25 ns/lookup |
| **Dagger G** (multi-módulo) | N Components + factory functions | ✅ Automático | ensure*() no escalan (= D), Components `internal` |
| **Dagger H** (multi-módulo) | N Components + FeatureProviders + DFS resolver | ✅ Automático | Wiring inmutable, ~3,5x más lento init que G |
| **Koin** | Un `koinApplication` aislado | ✅ Automático | Errores runtime |
| **Hybrid** | Koin SDK + Dagger bridge | ✅ Automático | Bridge unidireccional, features lazy bypasean |

---

## Matriz de decisión

| Restricción del proyecto | Approach más adecuado |
|--------------------------|----------------------|
| Compile-time safety máxima | Dagger D, E, E2 o G (multi-módulo) |
| Pure DI (no service locator) | Dagger B, C (monolítico) o D, E, E2, G, H (multi-módulo) |
| Features con dependencias cruzadas fuertes | Dagger D, E, E2, G, H (multi-módulo) o Koin/Hybrid |
| Features verdaderamente independientes | Dagger B o C (monolítico) |
| KMP necesario | Koin o Hybrid |
| Android exclusivo, equipo con experiencia Dagger | Dagger D, G o E2 (multi-módulo) |
| Multi-módulo Gradle corporativo (api/impl por feature) | Dagger E, E2, G o H (vía wiring-e / wiring-e2 / wiring-g / wiring-h) |
| Publicación per-feature en Maven | Dagger B, C o E, E2 (multi-módulo) |
| 20+ features, adiciones frecuentes | Dagger E2, H o Koin |
| SDK escalable a 50+ módulos sin tocar facade | **Dagger E2**, **H** o Koin |
| API mínima para consumidor (sin Feature enum) | **Dagger E2**, **H** o Koin |
| Zero codegen, builds más rápidos | Koin |
| SDK KMP consumido por app Dagger existente | Hybrid |
| Tamaño de binario crítico | Koin o Dagger B/C |
| Equipo pequeño, mínima ceremonia | Koin |
| Multi-módulo con Components internos (factory functions) | Dagger G (sin registry, Components `internal`) |
| Equipos grandes (10+), zero edición central, wiring inmutable | **Dagger H** (Auto-Discovery FeatureProviders) |
| Multi-módulo con per-feature contracts | Dagger D/E/E2/G/H vía sdk-wiring / wiring-e / wiring-e2 / wiring-g / wiring-h |

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
- [dagger2-sdk-selective-init.md](dagger2-sdk-selective-init.md) — Implementaciones Dagger A, B, C, D, E, E2, G, H con código
- [di-sdk-consumer-isolation.md](di-sdk-consumer-isolation.md) — Niveles de aislamiento, DI vs Service Locator
- [di-sdk-selective-init-comparison.md](di-sdk-selective-init-comparison.md) — Tablas de comparación por requisito
- [di-cross-feature-deps.md](di-cross-feature-deps.md) — Dependencias cruzadas con ejemplos concretos
- [di-hybrid-koin-sdk-dagger-app.md](di-hybrid-koin-sdk-dagger-app.md) — Arquitectura hybrid completa
- [di-multimodule-api-impl-analysis.md](di-multimodule-api-impl-analysis.md) — Arquitectura multi-módulo con per-feature contracts (provision interfaces, wiring patterns D/E/E2/G/H)
