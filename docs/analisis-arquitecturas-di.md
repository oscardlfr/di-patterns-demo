# Análisis de Arquitecturas DI para SDKs Modulares

Documento de referencia técnica. Presenta los resultados del proyecto `di-patterns-demo`:
5 implementaciones de SDK con inyección de dependencias, benchmarks en dispositivo real
y análisis de cumplimiento de requisitos.

**No hay recomendación.** Cada approach tiene trade-offs. Este documento presenta los datos
para que el equipo tome la decisión informada según sus restricciones.

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
| **Koin** | `sdk/impl-koin` | Koin 4.1 | `koinApplication` aislado + `loadModules` |
| **Hybrid** | `sdk/impl-koin` + bridge | Koin + Dagger 2 | SDK Koin, app Dagger, puente `@Component` |

Cada SDK expone la **misma API pública**:

```kotlin
Sdk.init(config, features)
Sdk.getOrInitModule(feature)   // lazy con cascada automática
Sdk.get<EncryptionService>()
Sdk.shutdown()
```

El consumidor no ve DaggerComponents, koinApplication ni CoreApis.

---

## Arquitectura del proyecto

```
sdk/
  api/              → Interfaces puras (0 dependencias DI)
  impl-common/      → Implementaciones compartidas por todos los SDKs
  impl-koin/        → KoinSdk (sealed SdkModule, auto-discovery, loadModules)
  impl-dagger-b/    → DaggerBSdk (Per-Feature + CoreApis extendido)
  impl-dagger-c/    → DaggerCSdk (ServiceLoader + META-INF)
  impl-dagger-d/    → DaggerSdk (Component Dependencies)

sample-dagger-a/    → Educativo: @Component monolítico (approach A)
sample-dagger-b/    → Consumidor de DaggerBSdk
sample-dagger-c/    → Consumidor de DaggerCSdk
sample-dagger-d/    → Consumidor de DaggerSdk
sample-hybrid/      → Consumidor de KoinSdk + puente Dagger 2

benchmark/          → 30 Jetpack Microbenchmarks
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

| Requisito | B | C | D | Koin | Hybrid |
|-----------|---|---|---|------|--------|
| 1. Selectiva | ✅ | ✅ | ✅ | ✅ | ✅ |
| 2. Aislamiento | ✅ | ✅ | ✅ | ✅ | ✅ |
| 3. Singletons | ⚠️ | ⚠️ | ✅ | ✅ | ✅ |
| 4. Lazy | ✅ | ✅ | ✅ | ✅ | ✅ |
| 5. Core indep. | ✅ | ✅ | ❌ | ✅ | ✅ |
| 6. Auto-registro | ❌ | ✅ | ❌ | ✅ | ✅ |
| 7. Binario lean | ❌ | ❌ | ❌ | ✅ | ✅ |
| 8. Cross-deps | ⚠️ | ⚠️ | ✅ | ✅ | ✅ |
| 9. Compile-time | ⚠️ | ⚠️ | ✅ | ❌ | ⚠️ |
| 10. KMP | ❌ | ❌ | ❌ | ✅ | ✅ |
| **Total ✅** | **4** 🔴 | **5** | **5** | **9** 🟢 | **9** 🟢 |

---

## Resultados de benchmarks

Dispositivo: Samsung Galaxy S22 Ultra (SM-S908B) — Snapdragon 8 Gen 1, 8 cores, 2.8 GHz, Android 16.
Framework: Jetpack Benchmark 1.4.0 con warmup automático.

### Inicialización en frío (6 features completas)

Tiempo para crear el grafo DI completo desde cero e instanciar todos los singletons.

| Approach | Mediana (ns) | Allocations | |
|----------|-------------|-------------|---|
| Dagger D | 813 | 49 | 🟢 mejor |
| Dagger A (ref.) | 858 | 31 | |
| Dagger B | 1.179 | 45 | |
| Dagger C | 1.440 | 48 | |
| Hybrid | 43.527 | 619 | |
| Koin | 46.606 | 603 | 🔴 peor |

**Observación:** Dagger inicia entre 35× y 57× más rápido que Koin.
Sin embargo, 47 µs (caso Koin) equivale a 0,003 frames (16 ms/frame).
Ambos son imperceptibles para el usuario.

### Primera resolución de un singleton

Tiempo de la primera llamada a `encryption()` / `koin.get<EncryptionService>()` con el grafo ya construido.

| Approach | Mediana (ns) | Allocations | |
|----------|-------------|-------------|---|
| Dagger B | 2,2 | 0 | 🟢 mejor |
| Dagger C | 2,3 | 0 | |
| Dagger D | 2,3 | 0 | |
| Dagger A (ref.) | 2,8 | 0 | |
| Hybrid (bridge cached) | 2,8 | 0 | |
| Koin | 900 | 13 | 🔴 peor |

**Observación:** Dagger accede al singleton en ~2 ns (lectura de campo volátil).
Koin realiza hash lookup + scope resolution (~900 ns, 13 allocations).

### Lazy init — Feature sin dependencias (Analytics)

Tiempo de añadir una feature independiente a un grafo en ejecución.

| Approach | Mediana (ns) | Allocations | |
|----------|-------------|-------------|---|
| Dagger B | 116 | 8 | 🟢 mejor |
| Dagger D | 132 | 8 | |
| Dagger C | 197 | 8 | |
| Hybrid | 8.273 | 96 | |
| Koin | 13.247 | 96 | 🔴 peor |

**Observación:** En Dagger, lazy init sin dependencias es crear un Component (~130 ns).
En Koin, implica `loadModules` + resolución del singleton (8-13 µs).

### Lazy init — Feature con dependencias pesadas (Sync → Auth + Storage + Encryption)

Tiempo de inicialización en cascada: pedir Sync desencadena Auth → Encryption, Storage → Encryption.

| Approach | Mediana (ns) | Allocations | |
|----------|-------------|-------------|---|
| Dagger B | 670 | 25 | 🟢 mejor |
| Dagger D | 693 | 28 | |
| Dagger C | 844 | 28 | |
| Hybrid | 24.678 | 311 | |
| Koin | 24.776 | 311 | 🔴 peor |

**Observación:** Cascada Dagger = 3 Component builds (~700 ns).
Cascada Koin = 3 `loadModules` + 3 resoluciones (~25 µs).
Ambos realizan la misma cascada lógica.

### Operación cross-feature (Sync.sync())

Tiempo de una operación real que cruza Auth + Storage + Encryption.
Singletons resueltos **una vez** fuera del loop. Mide solo el trabajo, no el DI.

| Approach | Mediana (ns) | Allocations | |
|----------|-------------|-------------|---|
| Hybrid | 69.713 | 1.597 | 🟢 mejor |
| Dagger C | 75.914 | 1.597 | |
| Dagger B | 76.412 | 1.597 | |
| Dagger D | 79.602 | 1.597 | |
| Dagger A (ref.) | 82.848 | 1.597 | |
| Koin | 118.587 | 1.597 | 🔴 peor |

**Observación:** Las allocations son idénticas (1.597) — el trabajo es el mismo.
La diferencia de ~40 µs entre Koin y Dagger podría atribuirse a thermal throttle
(los tests se ejecutan en orden alfabético; Koin corre último con el chip más caliente).
Con los singletons ya resueltos, el framework DI no participa en la operación.

### Recuento de resultados

| Approach | 🟢 Mejor | 🔴 Peor | Notas |
|----------|---------|---------|-------|
| **Dagger B** | 3 | 0 | Más rápido en resolve, lazy init (0 deps y cascade) |
| **Dagger D** | 1 | 0 | Más rápido en init cold |
| **Hybrid** | 1 | 0 | Más rápido en operación real (bridge cache) |
| **Dagger C** | 0 | 0 | Siempre en posiciones intermedias |
| **Dagger A** | 0 | 0 | Solo referencia — no comparable en lazy init |
| **Koin** | 0 | 5 | Más lento en todas las métricas de plumbing |

**Nota importante:** Koin es 🔴 en todas las métricas de plumbing (init, resolve, lazy),
pero la diferencia absoluta máxima es 47 µs — imperceptible en una aplicación real.
El «peor» en nanosegundos sigue siendo excelente en la práctica.

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
| Koin | ✅ | `loadModules` + `get()` desde el mismo grafo — automático |
| Hybrid | ✅ | Hereda de Koin |
| Dagger A | N/A | Todo en un @Component — no hay cascada porque todo existe siempre |

---

## Análisis por approach

Cada approach está documentado en detalle con código del proyecto en los docs de referencia:

- **Dagger A/B/C/D:** [dagger2-sdk-selective-init.md](dagger2-sdk-selective-init.md) — arquitectura, código, pros/contras, cuándo usar
- **Koin:** [di-sdk-consumer-isolation.md](di-sdk-consumer-isolation.md) — paradigma Service Locator, niveles de aislamiento
- **Hybrid:** [di-hybrid-koin-sdk-dagger-app.md](di-hybrid-koin-sdk-dagger-app.md) — bridge, puente unidireccional, features lazy
- **Cross-feature:** [di-cross-feature-deps.md](di-cross-feature-deps.md) — cómo resuelve cada approach las dependencias cruzadas

Resumen rápido:

| Approach | Mecanismo interno | Cross-deps | Limitación principal |
|----------|------------------|-----------|---------------------|
| **Dagger B** | N Components + CoreApis manual | ⚠️ God Object | CoreApis crece con cada cross-dep |
| **Dagger C** | N Components + ServiceLoader | ⚠️ Runtime resolve | JVM only + errores runtime |
| **Dagger D** | N Components con `dependencies=[]` | ✅ Automático | Binario no lean, edición central |
| **Koin** | Un `koinApplication` aislado | ✅ Automático | Errores runtime |
| **Hybrid** | Koin SDK + Dagger bridge | ✅ Automático | Bridge unidireccional, features lazy bypasean |

---

## Matriz de decisión

| Restricción del proyecto | Approach más adecuado |
|--------------------------|----------------------|
| Compile-time safety máxima | Dagger D |
| Pure DI (no service locator) | Dagger B, C o D |
| Features con dependencias cruzadas fuertes | Dagger D o Koin |
| Features verdaderamente independientes | Dagger B o C |
| KMP necesario | Koin o Hybrid |
| Android exclusivo, equipo con experiencia Dagger | Dagger D |
| Publicación per-feature en Maven | Dagger B o C (con módulos Gradle separados) |
| 20+ features, adiciones frecuentes | Dagger C o Koin |
| Zero codegen, builds más rápidos | Koin |
| SDK KMP consumido por app Dagger existente | Hybrid |
| Tamaño de binario crítico | Koin o Dagger B/C (con módulos separados) |
| Equipo pequeño, mínima ceremonia | Koin |

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
- [dagger2-sdk-selective-init.md](dagger2-sdk-selective-init.md) — Implementaciones Dagger A, B, C con código
- [di-sdk-consumer-isolation.md](di-sdk-consumer-isolation.md) — Niveles de aislamiento, DI vs Service Locator
- [di-sdk-selective-init-comparison.md](di-sdk-selective-init-comparison.md) — Tablas de comparación por requisito
- [di-cross-feature-deps.md](di-cross-feature-deps.md) — Dependencias cruzadas con ejemplos concretos
- [di-hybrid-koin-sdk-dagger-app.md](di-hybrid-koin-sdk-dagger-app.md) — Arquitectura hybrid completa
