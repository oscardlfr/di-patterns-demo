# Requisitos para SDKs Modulares con DI

Criterios para evaluar cualquier implementacion de SDK modular con inyeccion de dependencias.
No todos tienen el mismo peso -- depende del contexto del proyecto.

---

## Tabla de Requisitos

| # | Requisito | Pregunta |
|---|-----------|----------|
| 1 | Inicializacion selectiva | Puede el consumidor activar solo las features que necesita? |
| 2 | Aislamiento del consumidor | El codigo del consumidor evita importar clases de implementacion? |
| 3 | Singletons compartidos | Los servicios compartidos (logger, config) son instancia unica? |
| 4 | Instanciacion lazy | Las features no seleccionadas nunca se instancian? |
| 5 | Independencia del core | El orquestador evita dependencias de produccion en modulos impl? |
| 6 | Auto-registro | Es suficiente anadir una dependencia Gradle para registrar una feature? |
| 7 | Binario eficiente | El binario del consumidor excluye las features no seleccionadas? |
| 8 | Dependencias cruzadas | Feature A puede inyectar un servicio de Feature B? |
| 9 | Seguridad en compilacion | Los bindings faltantes se detectan en tiempo de compilacion? |
| 10 | Soporte KMP | Funciona en iOS, macOS, Desktop? |

---

## Cumplimiento por Patron

### Patrones Monoliticos

#### Dagger B -- Per-Feature + CoreApis

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicializacion selectiva | OK | `init(config, setOf(Feature.ENCRYPTION))` |
| 2 | Aislamiento del consumidor | OK | Consumer importa solo `DaggerBSdk` |
| 3 | Singletons compartidos | PARCIAL | Via CoreApis -- manual, crece con cada servicio compartido |
| 4 | Instanciacion lazy | OK | `getOrInitModule()` crea Component on-demand |
| 5 | Independencia del core | OK | CoreApis es una interfaz Kotlin plana |
| 6 | Auto-registro | NO | Anadir feature requiere editar `when` block |
| 7 | Binario eficiente | NO | Todas las features compiladas en `impl-dagger-b` |
| 8 | Dependencias cruzadas | PARCIAL | Solo via CoreApis extendido (God Object a escala) |
| 9 | Seguridad en compilacion | PARCIAL | Por feature, no global. CoreApis no validado |
| 10 | KMP | NO | Dagger es JVM |

#### Dagger C -- ServiceLoader Discovery

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicializacion selectiva | OK | `init(config, setOf("encryption"))` |
| 2 | Aislamiento del consumidor | OK | Consumer importa solo `DaggerCSdk` |
| 3 | Singletons compartidos | PARCIAL | Mismo problema que B -- CoreApis manual |
| 4 | Instanciacion lazy | OK | ServiceLoader + `getOrInitModule()` |
| 5 | Independencia del core | OK | Zero dependencias impl en el core |
| 6 | Auto-registro | OK | META-INF/services -- zero edicion central |
| 7 | Binario eficiente | NO | Todas las features en el modulo SDK |
| 8 | Dependencias cruzadas | PARCIAL | Runtime resolve entre features (no compile-time) |
| 9 | Seguridad en compilacion | PARCIAL | Per-feature + descubrimiento runtime |
| 10 | KMP | NO | ServiceLoader es JVM |

#### Koin

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicializacion selectiva | OK | `init(setOf(SdkModule.Encryption.Default), config)` |
| 2 | Aislamiento del consumidor | OK | Sealed class + auto-discovery. Consumer importa solo `KoinSdk` |
| 3 | Singletons compartidos | OK | Un `koinApplication`, un scope |
| 4 | Instanciacion lazy | OK | `loadModules()` + `Class.forName` |
| 5 | Independencia del core | OK | Zero dependencias impl en core-sdk |
| 6 | Auto-registro | OK | `init {}` + `Class.forName` / `@EagerInitialization` |
| 7 | Binario eficiente | OK | Solo las dependencias Gradle presentes |
| 8 | Dependencias cruzadas | OK | Un grafo -- `get()` resuelve cualquier servicio |
| 9 | Seguridad en compilacion | NO | Resolucion runtime -- errores en ejecucion |
| 10 | KMP | OK | Soporte completo (JVM + Native + JS) |

#### Hybrid (Koin SDK + Dagger 2 app)

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicializacion selectiva | OK | Mismas capacidades que Koin |
| 2 | Aislamiento del consumidor | OK | Consumer usa `@Inject` de Dagger -- zero Koin |
| 3 | Singletons compartidos | OK | Koin graph + Dagger bridge cache |
| 4 | Instanciacion lazy | OK | `loadModules()` en Koin |
| 5 | Independencia del core | OK | SDK Koin aislado |
| 6 | Auto-registro | OK | Hereda de Koin |
| 7 | Binario eficiente | OK | Hereda de Koin |
| 8 | Dependencias cruzadas | OK | Un grafo Koin |
| 9 | Seguridad en compilacion | PARCIAL | Koin runtime + Dagger compile-time en el bridge |
| 10 | KMP | OK | SDK KMP, bridge solo Android |

### Patrones Multi-Modulo

#### D -- Component Dependencies (sdk-wiring)

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicializacion selectiva | OK | Lazy `ensure*()` construye on-demand |
| 2 | Aislamiento del consumidor | OK | Components internos, app solo ve facade |
| 3 | Singletons compartidos | OK | CoreComponent provee config via CoreProvisions |
| 4 | Instanciacion lazy | OK | `ensure*()` con cascada automatica |
| 5 | Independencia del core | OK | Cada feature-impl compila independientemente con provision interfaces |
| 6 | Auto-registro | NO | Anadir feature requiere editar `when` blocks en el facade |
| 7 | Binario eficiente | OK | Solo los feature-impl incluidos en Gradle |
| 8 | Dependencias cruzadas | OK | Dagger resuelve automaticamente via `dependencies=[...]` |
| 9 | Seguridad en compilacion | OK | Missing binding o parent = error de compilacion |
| 10 | KMP | NO | Dagger es JVM |

#### E2 -- Auto-Init Registry (wiring-e2)

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicializacion selectiva | PARCIAL | Todas las entries instaladas; "seleccion" implicita (solo construye lo pedido) |
| 2 | Aislamiento del consumidor | OK | API minima: `init()` + `get<T>()`. Sin Feature enum |
| 3 | Singletons compartidos | OK | CoreComponent via CoreProvisions + registry cache |
| 4 | Instanciacion lazy | OK | `get<T>()` auto-construye por demanda (DFS recursivo) |
| 5 | Independencia del core | OK | Cada feature-impl compila independientemente |
| 6 | Auto-registro | OK | Anadir modulo = 1 linea en `allEntries()`. Sin enum |
| 7 | Binario eficiente | OK | Solo los feature-impl incluidos en Gradle |
| 8 | Dependencias cruzadas | OK | Dagger `dependencies=[...]` + auto-build recursivo |
| 9 | Seguridad en compilacion | OK | Explicit bindings. Missing binding = error Dagger |
| 10 | KMP | NO | Dagger es JVM |

#### G -- Factory Functions (wiring-g)

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicializacion selectiva | OK | Lazy `ensure*()` via factory functions |
| 2 | Aislamiento del consumidor | OK | Components `internal`, app solo ve facade |
| 3 | Singletons compartidos | OK | CoreComponent via CoreProvisions |
| 4 | Instanciacion lazy | OK | `ensure*()` con cascada automatica |
| 5 | Independencia del core | OK | Cada feature-impl compila independientemente |
| 6 | Auto-registro | NO | Anadir feature requiere editar `ensure*()` (= D) |
| 7 | Binario eficiente | OK | Solo los feature-impl incluidos en Gradle |
| 8 | Dependencias cruzadas | OK | Factory functions reciben provision interfaces |
| 9 | Seguridad en compilacion | OK | Dagger valida cada Component |
| 10 | KMP | NO | Dagger es JVM |

#### H -- Auto-Discovery FeatureProviders (wiring-h)

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicializacion selectiva | PARCIAL | Todos los providers instalados; construye solo lo pedido |
| 2 | Aislamiento del consumidor | OK | API minima: `init()` + `get<T>()`. Wiring inmutable |
| 3 | Singletons compartidos | OK | CoreComponent via CoreProvisions + resolver cache |
| 4 | Instanciacion lazy | OK | `get<T>()` dispara DFS via `resolver.provision()` |
| 5 | Independencia del core | OK | Cada feature-impl compila independientemente |
| 6 | Auto-registro | OK | Wiring inmutable. Zero edicion central con ServiceLoader |
| 7 | Binario eficiente | OK | Solo los feature-impl incluidos en Gradle |
| 8 | Dependencias cruzadas | OK | DFS automatico via `resolver.provision()` |
| 9 | Seguridad en compilacion | PARCIAL | Dagger valida cada Component, pero provider faltante es error runtime |
| 10 | KMP | NO | Dagger es JVM |

#### I -- Pure Resolver (wiring-i)

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicializacion selectiva | PARCIAL | Todos los providers instalados; construye solo lo pedido (= H) |
| 2 | Aislamiento del consumidor | OK | API minima: `init()` + `get<T>()`. Wiring inmutable |
| 3 | Singletons compartidos | OK | Resolver cache. Sin DI framework |
| 4 | Instanciacion lazy | OK | `get<T>()` dispara DFS via `resolver.provision()` (= H) |
| 5 | Independencia del core | OK | Cada feature-impl compila independientemente |
| 6 | Auto-registro | OK | ServiceLoader descubre PureFeatureProvider. Zero edicion central |
| 7 | Binario eficiente | OK | Solo los feature-impl incluidos en Gradle |
| 8 | Dependencias cruzadas | OK | DFS automatico via `resolver.provision()` (= H) |
| 9 | Seguridad en compilacion | NO | Zero DI framework = zero validacion en compilacion. Errores runtime |
| 10 | KMP | PARCIAL | Sin Dagger. Constructor injection puro. ServiceLoader es JVM, pero la logica es portable |

#### J -- kotlin-inject (wiring-j)

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicializacion selectiva | PARCIAL | Todos los providers instalados; construye solo lo pedido (= H) |
| 2 | Aislamiento del consumidor | OK | API minima: `init()` + `get<T>()`. Wiring inmutable |
| 3 | Singletons compartidos | OK | Resolver cache. kotlin-inject Components |
| 4 | Instanciacion lazy | OK | `get<T>()` dispara DFS via `resolver.provision()` (= H) |
| 5 | Independencia del core | OK | Cada feature-impl compila independientemente |
| 6 | Auto-registro | OK | ServiceLoader descubre KIFeatureProvider. Zero edicion central |
| 7 | Binario eficiente | OK | Solo los feature-impl incluidos en Gradle |
| 8 | Dependencias cruzadas | OK | DFS automatico via `resolver.provision()` (= H) |
| 9 | Seguridad en compilacion | PARCIAL | kotlin-inject valida cada Component, pero provider faltante es error runtime (= H) |
| 10 | KMP | PARCIAL | kotlin-inject soporta KMP. ServiceLoader es JVM, pero podria sustituirse por expect/actual |

#### K -- AndroidManifest Discovery (wiring-k)

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicializacion selectiva | PARCIAL | Todos los providers instalados; construye solo lo pedido (= H) |
| 2 | Aislamiento del consumidor | OK | API minima: `init(context, config)` + `get<T>()`. Wiring inmutable |
| 3 | Singletons compartidos | OK | Resolver cache. Mismos FeatureProviders que H |
| 4 | Instanciacion lazy | OK | `get<T>()` dispara DFS via `resolver.provision()` (= H) |
| 5 | Independencia del core | OK | Cada feature-impl compila independientemente |
| 6 | Auto-registro | OK | AndroidManifest meta-data + manifest merger. Zero edicion central |
| 7 | Binario eficiente | OK | Solo los feature-impl incluidos en Gradle |
| 8 | Dependencias cruzadas | OK | DFS automatico via `resolver.provision()` (= H) |
| 9 | Seguridad en compilacion | PARCIAL | Dagger valida cada Component, pero provider faltante es error runtime (= H) |
| 10 | KMP | NO | AndroidManifest + PackageManager son Android-only |

---

## Resumen de Cumplimiento

| Requisito | B | C | Koin | Hybrid | D | E2 | G | H | I | J | K | L | M | N | O | O2 | P | P2 | Q | Q2 |
|-----------|---|---|------|--------|---|----|----|---|---|---|---|---|---|---|---|----|----|----|----|-----|
| 1. Selectiva | OK | OK | OK | OK | OK | ~ | OK | ~ | ~ | ~ | ~ | ~ | ~ | ~ | ~ | ~ | ~ | ~ | ~ | ~ |
| 2. Aislamiento | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK |
| 3. Singletons | ~ | ~ | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK |
| 4. Lazy | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK |
| 5. Core indep. | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK |
| 6. Auto-registro | NO | OK | OK | OK | NO | OK | NO | OK | OK | OK | OK | OK | NO | OK | OK | OK | OK | OK | NO | NO |
| 7. Binario lean | NO | NO | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | NO | NO |
| 8. Cross-deps | ~ | ~ | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK |
| 9. Compile-time | ~ | ~ | NO | ~ | OK | OK | OK | ~ | NO | ~ | ~ | NO | NO | NO | NO | NO | ~ | ~ | OK | OK |
| 10. KMP | NO | NO | OK | OK | NO | NO | NO | NO | ~ | ~ | NO | ~ | ~ | OK | OK | OK | OK | OK | NO | NO |
| **Total OK** | **4** | **5** | **9** | **9** | **8** | **8** | **8** | **8** | **8** | **8** | **8** | **8** | **7** | **8** | **8** | **8** | **8** | **8** | **7** | **7** |

**Leyenda:** OK = cumple, ~ = cumple parcialmente, NO = no cumple

**Notas clave:**
- Los 16 patrones multi-modulo se organizan en 3 categorias:
  - **Android-only (8):** D, E2, G, H, I, K, Q, Q2 -- dependen de APIs JVM/Android (Dagger, ServiceLoader, PackageManager)
  - **KMP-compatible (5):** N, O, O2, P, P2 -- funcionan en todos los targets KMP (JVM, iOS, macOS, WASM)
  - **Partial KMP (3):** J, L, M -- el framework DI es KMP, pero el discovery usa ServiceLoader (JVM-only)
- B y C son patrones monoliticos
- L, M, N, O, O2, P, P2 satisfacen los mismos requisitos core que D-K (provision interfaces en modulos Gradle separados)
- Q y Q2 (Hilt-style) ofrecen compile-time safety completa pero sin lean binary ni auto-registro
- Koin, Hybrid, N, O, O2, P y P2 tienen soporte KMP completo
- J y L tienen potencial KMP completo: basta con reemplazar ServiceLoader por sweet-spi (como hace Pattern N)
