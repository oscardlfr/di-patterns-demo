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
| 6 | Auto-registro (grafo) | Al anadir una dependencia Gradle, queda la feature agregada al grafo DI sin editar codigo de wiring existente? |
| 7 | Binario eficiente | El binario del consumidor excluye las features no seleccionadas? |
| 8 | Dependencias cruzadas | Feature A puede inyectar un servicio de Feature B? |
| 9 | Seguridad en compilacion | Los bindings faltantes se detectan en tiempo de compilacion? |
| 10 | Soporte KMP | Funciona en iOS, macOS, Desktop? |
| 11 | Wiring del facade inmutable | El dispatcher `get<T>(Class)` NO requiere editar ramas manualmente al anadir una API? |
| 12 | Abstraccion runtime-flexible | El modulo sdk-integration declara las feature-impls como `runtimeOnly` (no compile-time), permitiendo a la app elegir versiones o subsets? |

### Por que "Auto-registro (grafo)" y "Wiring del facade inmutable" son dos criterios distintos

Muchos frameworks DI permiten auto-agregar un modulo al grafo (`@ContributesTo`, `@Module +
@InstallIn`, `ServiceLoader`, `@ServiceProvider`). Pero eso **solo cubre la mitad del
problema**. El facade del SDK tiene que exponer `get<T>(clazz: Class<T>): T` para que el
consumidor pida servicios por tipo. Y ese dispatcher, en patrones compile-time (Dagger,
Metro, kotlin-inject), suele implementarse con un `when (clazz)` que mapea cada `Class<T>`
al accessor del componente generado.

Ese `when` **crece linealmente por API**, no por feature. Con 50 features x 10 APIs por
feature = 500 ramas de `when` mantenidas a mano en el facade. Eso es edicion central
disfrazada.

Separar los dos criterios hace explicita esta asimetria:

- **Req 6 (Auto-registro grafo)**: mide si el framework DI agrega el modulo sin editar
  wiring. Metro/kotlin-inject-anvil lo hacen con `@ContributesTo`. Koin con discovery
  (ServiceLoader/sweet-spi). Dagger/Hilt clasico NO (requiere listar modules en
  `@Component`).

- **Req 11 (Facade inmutable)**: mide si el dispatcher `get<T>(Class)` del SDK escala
  sin editar ramas. Runtime DI (Koin) y Resolver-based (H/I/J/K, E2) lo consiguen con
  un HashMap nativo. Compile-time DI (O/O2/P/P2/Q/Q2) NO — el `when` manual es
  inevitable salvo que se genere con codegen (KSP propio).

Un patron que cumple Req 6 pero no Req 11 (O2, P2) **parece** zero-touch pero deja una
deuda oculta en el facade. Un patron que cumple ambos (H, E2, N, Koin) es zero-touch
end-to-end.

### Por que "Abstraccion runtime-flexible" es un criterio distinto

Los Req 5 (Independencia del core) y Req 7 (Binario eficiente) hablan de **aislamiento a
nivel de codigo fuente** dentro de un solo binario. Req 12 es diferente: habla de como se
**distribuye** el modulo sdk-integration (`sdk:wiring-X`).

Un sdk-integration cumple Req 12 si su `build.gradle.kts` declara las feature-impls como
`runtimeOnly(project(":features:feature-*-impl"))` en vez de `implementation(...)`. Esto
permite dos modelos de distribucion:

- **Bundled** (baterias incluidas): el artefacto publicado contiene `runtimeOnly` con las
  feature-impls canonicas. La app consumidora solo hace `implementation(sdk-integration)`.
- **BYOF** (Bring Your Own Features): el artefacto se publica sin `runtimeOnly`. La app
  declara `implementation(sdk-integration)` + `runtimeOnly(feature-auth-impl:1.2.0)` +
  `runtimeOnly(feature-enc-impl:1.5.0)`, eligiendo versiones concretas.

Para que este modelo funcione, el sdk-integration **no puede importar tipos de
feature-*-impl en su codigo fuente**. Si lo hace (p.ej. `import
com.grinwich.sdk.feature.enc.buildEncBundle`), la dep tiene que ser `implementation`, el
consumer no puede cambiar la version, y falla Req 12.

Solo los patrones con **descubrimiento runtime puro** (ServiceLoader, sweet-spi, manifest
merger) cumplen Req 12 sin concesiones. Compile-time DI (Metro, kotlin-inject-anvil, Hilt)
es estructuralmente incompatible: el proceso de merge (`@ContributesTo`/`@InstallIn`) se
ejecuta al compilar el sdk-integration y necesita las feature-impls en el classpath de
compilacion.

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
| 6 | Auto-registro (grafo) | NO | Anadir feature requiere editar `when` block |
| 7 | Binario eficiente | NO | Todas las features compiladas en `impl-dagger-b` |
| 8 | Dependencias cruzadas | PARCIAL | Solo via CoreApis extendido (God Object a escala) |
| 9 | Seguridad en compilacion | PARCIAL | Por feature, no global. CoreApis no validado |
| 10 | KMP | NO | Dagger es JVM |
| 11 | Wiring del facade inmutable | NO | `when (clazz)` en `DaggerBSdk.get()` crece por cada servicio (confirmado en codigo) |
| 12 | Abstraccion runtime-flexible | NO | Monolitico: no hay modulo wiring separado; features compiladas en `impl-dagger-b` |

#### Dagger C -- ServiceLoader Discovery

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicializacion selectiva | OK | `init(config, setOf("encryption"))` |
| 2 | Aislamiento del consumidor | OK | Consumer importa solo `DaggerCSdk` |
| 3 | Singletons compartidos | PARCIAL | Mismo problema que B -- CoreApis manual |
| 4 | Instanciacion lazy | OK | ServiceLoader + `getOrInitModule()` |
| 5 | Independencia del core | OK | Zero dependencias impl en el core |
| 6 | Auto-registro (grafo) | OK | META-INF/services -- zero edicion central |
| 7 | Binario eficiente | NO | Todas las features en el modulo SDK |
| 8 | Dependencias cruzadas | PARCIAL | Runtime resolve entre features (no compile-time) |
| 9 | Seguridad en compilacion | PARCIAL | Per-feature + descubrimiento runtime |
| 10 | KMP | NO | ServiceLoader es JVM |
| 11 | Wiring del facade inmutable | NO | `when (serviceClass)` en cada Component wrapper (5 lugares confirmados en InternalComponents.kt) |
| 12 | Abstraccion runtime-flexible | NO | Monolitico: features compiladas en `impl-dagger-c` |

#### Koin

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicializacion selectiva | OK | `init(setOf(SdkModule.Encryption.Default), config)` |
| 2 | Aislamiento del consumidor | OK | Sealed class + auto-discovery. Consumer importa solo `KoinSdk` |
| 3 | Singletons compartidos | OK | Un `koinApplication`, un scope |
| 4 | Instanciacion lazy | OK | `loadModules()` + `Class.forName` |
| 5 | Independencia del core | OK | Zero dependencias impl en core-sdk |
| 6 | Auto-registro (grafo) | OK | `init {}` + `Class.forName` / `@EagerInitialization` |
| 7 | Binario eficiente | OK | Solo las dependencias Gradle presentes |
| 8 | Dependencias cruzadas | OK | Un grafo -- `get()` resuelve cualquier servicio |
| 9 | Seguridad en compilacion | NO | Resolucion runtime -- errores en ejecucion |
| 10 | KMP | OK | Soporte completo (JVM + Native + JS) |
| 11 | Wiring del facade inmutable | OK | `koin.get(clazz.kotlin)` -- runtime registry nativo, sin `when` |
| 12 | Abstraccion runtime-flexible | NO | Monolitico: features compiladas en `impl-koin` |

#### Hybrid (Koin SDK + Dagger 2 app)

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicializacion selectiva | OK | Mismas capacidades que Koin |
| 2 | Aislamiento del consumidor | OK | Consumer usa `@Inject` de Dagger -- zero Koin |
| 3 | Singletons compartidos | OK | Koin graph + Dagger bridge cache |
| 4 | Instanciacion lazy | OK | `loadModules()` en Koin |
| 5 | Independencia del core | OK | SDK Koin aislado |
| 6 | Auto-registro (grafo) | OK | Hereda de Koin |
| 7 | Binario eficiente | OK | Hereda de Koin |
| 8 | Dependencias cruzadas | OK | Un grafo Koin |
| 9 | Seguridad en compilacion | PARCIAL | Koin runtime + Dagger compile-time en el bridge |
| 10 | KMP | OK | SDK KMP, bridge solo Android |
| 11 | Wiring del facade inmutable | OK | Hereda de Koin -- `koin.get()` runtime sin `when` |
| 12 | Abstraccion runtime-flexible | NO | Monolitico (hereda de Koin) |

### Patrones Multi-Modulo

#### D -- Component Dependencies (sdk-wiring)

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicializacion selectiva | OK | Lazy `ensure*()` construye on-demand |
| 2 | Aislamiento del consumidor | OK | Components internos, app solo ve facade |
| 3 | Singletons compartidos | OK | CoreComponent provee config via CoreProvisions |
| 4 | Instanciacion lazy | OK | `ensure*()` con cascada automatica |
| 5 | Independencia del core | OK | Cada feature-impl compila independientemente con provision interfaces |
| 6 | Auto-registro (grafo) | NO | Anadir feature requiere editar `when` blocks en el facade |
| 7 | Binario eficiente | OK | Solo los feature-impl incluidos en Gradle |
| 8 | Dependencias cruzadas | OK | Dagger resuelve automaticamente via `dependencies=[...]` |
| 9 | Seguridad en compilacion | OK | Missing binding o parent = error de compilacion |
| 10 | KMP | NO | Dagger es JVM |
| 11 | Wiring del facade inmutable | NO | `when (clazz)` en `MultiModuleSdk.get()` crece por API |
| 12 | Abstraccion runtime-flexible | NO | `implementation(feature-*-impl)` — wiring importa `DaggerXxxComponent` en codigo fuente |

#### E2 -- Auto-Init Registry (wiring-e2)

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicializacion selectiva | PARCIAL | Todas las entries instaladas; "seleccion" implicita (solo construye lo pedido) |
| 2 | Aislamiento del consumidor | OK | API minima: `init()` + `get<T>()`. Sin Feature enum |
| 3 | Singletons compartidos | OK | CoreComponent via CoreProvisions + registry cache |
| 4 | Instanciacion lazy | OK | `get<T>()` auto-construye por demanda (DFS recursivo) |
| 5 | Independencia del core | OK | Cada feature-impl compila independientemente |
| 6 | Auto-registro (grafo) | PARCIAL | Anadir modulo = 1 linea en `allEntries()`. Semi-manual pero centralizado |
| 7 | Binario eficiente | OK | Solo los feature-impl incluidos en Gradle |
| 8 | Dependencias cruzadas | OK | Dagger `dependencies=[...]` + auto-build recursivo |
| 9 | Seguridad en compilacion | OK | Explicit bindings. Missing binding = error Dagger |
| 10 | KMP | NO | Dagger es JVM |
| 11 | Wiring del facade inmutable | OK | `registry.get(clazz)` -- HashMap lookup, sin `when` |
| 12 | Abstraccion runtime-flexible | NO | `implementation(feature-*-impl)` — Entries.kt importa factories (`buildEncBundle`, ...) en codigo fuente |

#### G -- Factory Functions (wiring-g)

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicializacion selectiva | OK | Lazy `ensure*()` via factory functions |
| 2 | Aislamiento del consumidor | OK | Components `internal`, app solo ve facade |
| 3 | Singletons compartidos | OK | CoreComponent via CoreProvisions |
| 4 | Instanciacion lazy | OK | `ensure*()` con cascada automatica |
| 5 | Independencia del core | OK | Cada feature-impl compila independientemente |
| 6 | Auto-registro (grafo) | NO | Anadir feature requiere editar `ensure*()` (= D) |
| 7 | Binario eficiente | OK | Solo los feature-impl incluidos en Gradle |
| 8 | Dependencias cruzadas | OK | Factory functions reciben provision interfaces |
| 9 | Seguridad en compilacion | OK | Dagger valida cada Component |
| 10 | KMP | NO | Dagger es JVM |
| 11 | Wiring del facade inmutable | NO | `when (clazz)` en `MultiModuleSdkG.get()` crece por API |
| 12 | Abstraccion runtime-flexible | NO | `implementation(feature-*-impl)` — wiring importa factories en codigo fuente |

#### H -- Auto-Discovery FeatureProviders (wiring-h)

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicializacion selectiva | PARCIAL | Todos los providers instalados; construye solo lo pedido |
| 2 | Aislamiento del consumidor | OK | API minima: `init()` + `get<T>()`. Wiring inmutable |
| 3 | Singletons compartidos | OK | CoreComponent via CoreProvisions + resolver cache |
| 4 | Instanciacion lazy | OK | `get<T>()` dispara DFS via `resolver.provision()` |
| 5 | Independencia del core | OK | Cada feature-impl compila independientemente |
| 6 | Auto-registro (grafo) | OK | Wiring inmutable. Zero edicion central con ServiceLoader |
| 7 | Binario eficiente | OK | Solo los feature-impl incluidos en Gradle |
| 8 | Dependencias cruzadas | OK | DFS automatico via `resolver.provision()` |
| 9 | Seguridad en compilacion | PARCIAL | Dagger valida cada Component, pero provider faltante es error runtime |
| 10 | KMP | NO | Dagger es JVM |
| 11 | Wiring del facade inmutable | OK | `resolver.get(clazz)` -- HashMap lookup, sin `when` |
| 12 | Abstraccion runtime-flexible | OK | `runtimeOnly(feature-*-impl)`. Cero imports de `com.grinwich.sdk.feature.*` en el codigo del wiring |

#### I -- Pure Resolver (wiring-i)

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicializacion selectiva | PARCIAL | Todos los providers instalados; construye solo lo pedido (= H) |
| 2 | Aislamiento del consumidor | OK | API minima: `init()` + `get<T>()`. Wiring inmutable |
| 3 | Singletons compartidos | OK | Resolver cache. Sin DI framework |
| 4 | Instanciacion lazy | OK | `get<T>()` dispara DFS via `resolver.provision()` (= H) |
| 5 | Independencia del core | OK | Cada feature-impl compila independientemente |
| 6 | Auto-registro (grafo) | OK | ServiceLoader descubre PureFeatureProvider. Zero edicion central |
| 7 | Binario eficiente | OK | Solo los feature-impl incluidos en Gradle |
| 8 | Dependencias cruzadas | OK | DFS automatico via `resolver.provision()` (= H) |
| 9 | Seguridad en compilacion | NO | Zero DI framework = zero validacion en compilacion. Errores runtime |
| 10 | KMP | PARCIAL | Sin Dagger. Constructor injection puro. ServiceLoader es JVM, pero la logica es portable |
| 11 | Wiring del facade inmutable | OK | Mismo Resolver que H -- HashMap lookup, sin `when` |
| 12 | Abstraccion runtime-flexible | OK | `runtimeOnly(feature-*-impl)`. Mismo patron que H |

#### J -- kotlin-inject (wiring-j)

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicializacion selectiva | PARCIAL | Todos los providers instalados; construye solo lo pedido (= H) |
| 2 | Aislamiento del consumidor | OK | API minima: `init()` + `get<T>()`. Wiring inmutable |
| 3 | Singletons compartidos | OK | Resolver cache. kotlin-inject Components |
| 4 | Instanciacion lazy | OK | `get<T>()` dispara DFS via `resolver.provision()` (= H) |
| 5 | Independencia del core | OK | Cada feature-impl compila independientemente |
| 6 | Auto-registro (grafo) | OK | ServiceLoader descubre KIFeatureProvider. Zero edicion central |
| 7 | Binario eficiente | OK | Solo los feature-impl incluidos en Gradle |
| 8 | Dependencias cruzadas | OK | DFS automatico via `resolver.provision()` (= H) |
| 9 | Seguridad en compilacion | PARCIAL | kotlin-inject valida cada Component, pero provider faltante es error runtime (= H) |
| 10 | KMP | PARCIAL | kotlin-inject soporta KMP. ServiceLoader es JVM, pero podria sustituirse por expect/actual |
| 11 | Wiring del facade inmutable | OK | Mismo Resolver que H -- HashMap lookup, sin `when` |
| 12 | Abstraccion runtime-flexible | OK | `runtimeOnly(feature-*-impl)`. Mismo patron que H |

#### K -- AndroidManifest Discovery (wiring-k)

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicializacion selectiva | PARCIAL | Todos los providers instalados; construye solo lo pedido (= H) |
| 2 | Aislamiento del consumidor | OK | API minima: `init(context, config)` + `get<T>()`. Wiring inmutable |
| 3 | Singletons compartidos | OK | Resolver cache. Mismos FeatureProviders que H |
| 4 | Instanciacion lazy | OK | `get<T>()` dispara DFS via `resolver.provision()` (= H) |
| 5 | Independencia del core | OK | Cada feature-impl compila independientemente |
| 6 | Auto-registro (grafo) | OK | AndroidManifest meta-data + manifest merger. Zero edicion central |
| 7 | Binario eficiente | OK | Solo los feature-impl incluidos en Gradle |
| 8 | Dependencias cruzadas | OK | DFS automatico via `resolver.provision()` (= H) |
| 9 | Seguridad en compilacion | PARCIAL | Dagger valida cada Component, pero provider faltante es error runtime (= H) |
| 10 | KMP | NO | AndroidManifest + PackageManager son Android-only |
| 11 | Wiring del facade inmutable | OK | Mismo Resolver que H -- HashMap lookup, sin `when` |
| 12 | Abstraccion runtime-flexible | OK | `runtimeOnly(feature-*-impl)`. Mismo patron que H |

#### L -- Koin + ServiceLoader Eager (wiring-l)

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicializacion selectiva | PARCIAL | Todos los modulos registrados; construye solo lo pedido via `get<T>()` |
| 2 | Aislamiento del consumidor | OK | Consumer importa solo `KoinSdkL`. Modulos internos |
| 3 | Singletons compartidos | OK | Un `koinApplication`, un scope. `single {}` garantiza unicidad |
| 4 | Instanciacion lazy | OK | `get<T>()` dispara construccion on-demand |
| 5 | Independencia del core | OK | Cada feature-impl compila independientemente |
| 6 | Auto-registro (grafo) | OK | ServiceLoader descubre `KoinFeatureModule`. Zero edicion central |
| 7 | Binario eficiente | OK | Solo los feature-impl incluidos en Gradle |
| 8 | Dependencias cruzadas | OK | Un grafo Koin -- `get()` resuelve cualquier servicio registrado |
| 9 | Seguridad en compilacion | NO | Koin resuelve en runtime. Binding faltante = crash en ejecucion |
| 10 | KMP | PARCIAL | Koin es KMP, pero ServiceLoader es JVM-only. Requiere sustituir por sweet-spi para KMP completo |
| 11 | Wiring del facade inmutable | OK | `koin.get(clazz.kotlin)` -- sin `when` |
| 12 | Abstraccion runtime-flexible | OK | `runtimeOnly(feature-*-impl)`. Tras mover `ObservabilityKoinProvider` a feature-observability-impl, L no importa ningun tipo de feature-* en su codigo fuente |

#### M -- Koin + ServiceLoader Lazy loadModules (wiring-m)

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicializacion selectiva | PARCIAL | Todos los modulos descubiertos; `loadModules()` registra on-demand |
| 2 | Aislamiento del consumidor | OK | Consumer importa solo `KoinSdkM`. Modulos internos |
| 3 | Singletons compartidos | OK | Un `koinApplication`, un scope. `single {}` garantiza unicidad |
| 4 | Instanciacion lazy | OK | `loadModules()` + `get<T>()` on-demand. Mas lazy que L |
| 5 | Independencia del core | OK | Cada feature-impl compila independientemente |
| 6 | Auto-registro (grafo) | NO | Requiere llamada explicita `loadModules()` en cascada. No es zero-touch |
| 7 | Binario eficiente | OK | Solo los feature-impl incluidos en Gradle |
| 8 | Dependencias cruzadas | OK | Un grafo Koin -- `get()` resuelve cualquier servicio registrado |
| 9 | Seguridad en compilacion | NO | Koin resuelve en runtime. Binding faltante = crash en ejecucion |
| 10 | KMP | PARCIAL | Koin es KMP, pero ServiceLoader es JVM-only. Requiere sustituir por sweet-spi para KMP completo |
| 11 | Wiring del facade inmutable | OK | `koin.get(clazz.kotlin)` -- sin `when` |
| 12 | Abstraccion runtime-flexible | OK | `runtimeOnly(feature-*-impl)`. Mismo patron que L |

#### N -- sweet-spi + Koin (wiring-n)

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicializacion selectiva | PARCIAL | Todos los modulos registrados; construye solo lo pedido via `get<T>()` |
| 2 | Aislamiento del consumidor | OK | Consumer importa solo `SweetSpiKoinSdk`. Modulos internos |
| 3 | Singletons compartidos | OK | Un `koinApplication`, un scope. `single {}` garantiza unicidad |
| 4 | Instanciacion lazy | OK | `get<T>()` dispara construccion on-demand |
| 5 | Independencia del core | OK | Cada feature-impl compila independientemente |
| 6 | Auto-registro (grafo) | OK | `@ServiceProvider` de sweet-spi genera expect/actual. Zero edicion central |
| 7 | Binario eficiente | OK | Solo los feature-impl incluidos en Gradle |
| 8 | Dependencias cruzadas | OK | Un grafo Koin -- `get()` resuelve cualquier servicio registrado |
| 9 | Seguridad en compilacion | NO | Koin resuelve en runtime. Binding faltante = crash en ejecucion |
| 10 | KMP | OK | sweet-spi genera expect/actual para cada target. Koin es full KMP |
| 11 | Wiring del facade inmutable | OK | `koin.get(clazz.kotlin)` -- sin `when` |
| 12 | Abstraccion runtime-flexible | OK | `runtimeOnly(feature-*-impl)`. `ObservabilitySweetSpiProvider` descubierto via sweet-spi |

#### O -- Metro Eager (wiring-o)

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicializacion selectiva | PARCIAL | Todos los singletons construidos en init (eager). Seleccion implicita via scope |
| 2 | Aislamiento del consumidor | OK | Consumer importa solo entry point Metro. Components internos |
| 3 | Singletons compartidos | OK | `@SingleIn(AppScope)` garantiza unicidad en el scope |
| 4 | Instanciacion lazy | NO | Eager: todos los singletons se construyen al crear el grafo |
| 5 | Independencia del core | OK | Cada feature-impl compila independientemente |
| 6 | Auto-registro (grafo) | OK | `@ContributesTo(AppScope)` agrega al grafo. Zero edicion central |
| 7 | Binario eficiente | OK | Solo los feature-impl incluidos en Gradle |
| 8 | Dependencias cruzadas | OK | Metro resuelve automaticamente via grafo de compilacion |
| 9 | Seguridad en compilacion | OK | Compiler plugin valida grafo completo. Binding faltante = error de compilacion |
| 10 | KMP | OK | Compiler plugin genera codigo para cada target KMP |
| 11 | Wiring del facade inmutable | NO | `when (clazz)` manual en `MultiModuleSdkO.get()` -- 1 rama por API. Mitigable con KSP codegen propio |
| 12 | Abstraccion runtime-flexible | NO | Metro compile-time merge: `@ContributesTo(AppScope)` requiere feature-impls en classpath de compilacion del wiring |

#### O2 -- Metro Lazy (wiring-o2)

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicializacion selectiva | PARCIAL | Todos los bindings registrados; singletons se construyen on-demand |
| 2 | Aislamiento del consumidor | OK | Consumer importa solo entry point Metro. Components internos |
| 3 | Singletons compartidos | OK | `@SingleIn(AppScope)` con lazy tracking garantiza unicidad |
| 4 | Instanciacion lazy | OK | Singletons lazy por defecto. Se construyen en primer `get()` |
| 5 | Independencia del core | OK | Cada feature-impl compila independientemente |
| 6 | Auto-registro (grafo) | OK | `@ContributesTo(AppScope)` agrega al grafo. Zero edicion central |
| 7 | Binario eficiente | OK | Solo los feature-impl incluidos en Gradle |
| 8 | Dependencias cruzadas | OK | Metro resuelve automaticamente via grafo de compilacion |
| 9 | Seguridad en compilacion | OK | Compiler plugin valida grafo completo. Binding faltante = error de compilacion |
| 10 | KMP | OK | Compiler plugin genera codigo para cada target KMP |
| 11 | Wiring del facade inmutable | NO | `when (clazz)` manual en `MultiModuleSdkO2.get()` -- 1 rama por API. Mitigable con KSP codegen propio |
| 12 | Abstraccion runtime-flexible | NO | Idem O — Metro compiler plugin no puede fusionar contribuciones que no ve en compile |

#### P -- kotlin-inject-anvil Eager (wiring-p)

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicializacion selectiva | PARCIAL | Todos los singletons construidos en init (eager). Seleccion implicita |
| 2 | Aislamiento del consumidor | OK | Consumer importa solo entry point. Components internos |
| 3 | Singletons compartidos | OK | `@SingleIn(AppScope)` garantiza unicidad en el scope |
| 4 | Instanciacion lazy | NO | Eager: todos los singletons se construyen al crear el Component |
| 5 | Independencia del core | OK | Cada feature-impl compila independientemente |
| 6 | Auto-registro (grafo) | OK | `@ContributesTo(AppScope)` via KSP merge. Zero edicion central |
| 7 | Binario eficiente | OK | Solo los feature-impl incluidos en Gradle |
| 8 | Dependencias cruzadas | OK | KSP merge resuelve automaticamente via grafo de compilacion |
| 9 | Seguridad en compilacion | PARCIAL | KSP valida cada Component, pero merge graph puede tener gaps detectados en link |
| 10 | KMP | OK | kotlin-inject es full KMP. KSP genera per-target |
| 11 | Wiring del facade inmutable | NO | `when (clazz)` manual en `MultiModuleSdkP.get()` -- 1 rama por API. Mitigable con KSP codegen propio |
| 12 | Abstraccion runtime-flexible | NO | kotlin-inject-anvil KSP merge: `@MergeComponent(SdkScope)` requiere feature-impls en classpath |

#### P2 -- kotlin-inject-anvil Lazy (wiring-p2)

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicializacion selectiva | PARCIAL | Todos los bindings registrados; singletons se construyen on-demand |
| 2 | Aislamiento del consumidor | OK | Consumer importa solo entry point. Components internos |
| 3 | Singletons compartidos | OK | `@SingleIn(AppScope)` con lazy tracking garantiza unicidad |
| 4 | Instanciacion lazy | OK | `@SingleIn` con lazy singletons. Se construyen en primer acceso |
| 5 | Independencia del core | OK | Cada feature-impl compila independientemente |
| 6 | Auto-registro (grafo) | OK | `@ContributesTo(AppScope)` via KSP merge. Zero edicion central |
| 7 | Binario eficiente | OK | Solo los feature-impl incluidos en Gradle |
| 8 | Dependencias cruzadas | OK | KSP merge resuelve automaticamente via grafo de compilacion |
| 9 | Seguridad en compilacion | PARCIAL | KSP valida cada Component, pero merge graph puede tener gaps detectados en link |
| 10 | KMP | OK | kotlin-inject es full KMP. KSP genera per-target |
| 11 | Wiring del facade inmutable | NO | `when (clazz)` manual en `MultiModuleSdkP2.get()` -- 1 rama por API. Mitigable con KSP codegen propio |
| 12 | Abstraccion runtime-flexible | NO | Idem P |

#### Q -- Hilt-style Dagger Eager (wiring-q)

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicializacion selectiva | PARCIAL | Modulos listados explicitamente. Todos construidos en init (eager) |
| 2 | Aislamiento del consumidor | OK | Consumer importa solo `HiltSdkQ`. Components internos |
| 3 | Singletons compartidos | OK | `@Singleton` en Dagger garantiza unicidad en el Component |
| 4 | Instanciacion lazy | NO | Eager: Dagger construye todos los singletons al crear el Component |
| 5 | Independencia del core | OK | Cada feature-impl compila independientemente via `@Module` + `@InstallIn` |
| 6 | Auto-registro (grafo) | NO | Modulos listados explicitamente en `@Component(modules=[...])`. Edicion central requerida |
| 7 | Binario eficiente | NO | Todos los modulos compilados en el Component raiz |
| 8 | Dependencias cruzadas | OK | Dagger resuelve automaticamente via grafo de compilacion |
| 9 | Seguridad en compilacion | OK | Dagger valida grafo completo. Binding faltante = error de compilacion |
| 10 | KMP | NO | Dagger es JVM-only. No soporta iOS, macOS ni Desktop nativos |
| 11 | Wiring del facade inmutable | NO | `when (clazz)` manual en `MultiModuleSdkQ.get()` -- 1 rama por API. Mitigable con KSP codegen propio |
| 12 | Abstraccion runtime-flexible | NO | Hilt annotation processor merge: `@InstallIn(SingletonComponent)` requiere feature-impls en classpath |

#### Q2 -- Hilt-style Dagger Lazy (wiring-q2)

| # | Requisito | Estado | Notas |
|---|-----------|--------|-------|
| 1 | Inicializacion selectiva | PARCIAL | Modulos listados explicitamente. `dagger.Lazy<T>` difiere construccion |
| 2 | Aislamiento del consumidor | OK | Consumer importa solo `HiltSdkQ2`. Components internos |
| 3 | Singletons compartidos | OK | `@Singleton` en Dagger garantiza unicidad en el Component |
| 4 | Instanciacion lazy | OK | `dagger.Lazy<T>` difiere construccion hasta primer `.get()` |
| 5 | Independencia del core | OK | Cada feature-impl compila independientemente via `@Module` + `@InstallIn` |
| 6 | Auto-registro (grafo) | NO | Modulos listados explicitamente en `@Component(modules=[...])`. Edicion central requerida |
| 7 | Binario eficiente | NO | Todos los modulos compilados en el Component raiz |
| 8 | Dependencias cruzadas | OK | Dagger resuelve automaticamente via grafo de compilacion |
| 9 | Seguridad en compilacion | OK | Dagger valida grafo completo. Binding faltante = error de compilacion |
| 10 | KMP | NO | Dagger es JVM-only. No soporta iOS, macOS ni Desktop nativos |
| 11 | Wiring del facade inmutable | NO | `when (clazz)` manual en `MultiModuleSdkQ2.get()` -- 1 rama por API. Mitigable con KSP codegen propio |
| 12 | Abstraccion runtime-flexible | NO | Idem Q |

---

## Resumen de Cumplimiento

| Requisito | B | C | Koin | Hybrid | D | E2 | G | H | I | J | K | L | M | N | O | O2 | P | P2 | Q | Q2 |
|-----------|---|---|------|--------|---|----|----|---|---|---|---|---|---|---|---|----|----|----|----|-----|
| 1. Selectiva | OK | OK | OK | OK | OK | ~ | OK | ~ | ~ | ~ | ~ | ~ | ~ | ~ | ~ | ~ | ~ | ~ | ~ | ~ |
| 2. Aislamiento | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK |
| 3. Singletons | ~ | ~ | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK |
| 4. Lazy | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | NO | OK | NO | OK | NO | OK |
| 5. Core indep. | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK |
| 6. Auto-reg (grafo) | NO | OK | OK | OK | NO | ~ | NO | OK | OK | OK | OK | OK | NO | OK | OK | OK | OK | OK | NO | NO |
| 7. Binario lean | NO | NO | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | NO | NO |
| 8. Cross-deps | ~ | ~ | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK | OK |
| 9. Compile-time | ~ | ~ | NO | ~ | OK | OK | OK | ~ | NO | ~ | ~ | NO | NO | NO | OK | OK | ~ | ~ | OK | OK |
| 10. KMP | NO | NO | OK | OK | NO | NO | NO | NO | ~ | ~ | NO | ~ | ~ | OK | OK | OK | OK | OK | NO | NO |
| **11. Facade inmutable** | **NO** | **NO** | **OK** | **OK** | **NO** | **OK** | **NO** | **OK** | **OK** | **OK** | **OK** | **OK** | **OK** | **OK** | **NO** | **NO** | **NO** | **NO** | **NO** | **NO** |
| **12. Abstr. runtime-flex** | **NO** | **NO** | **NO** | **NO** | **NO** | **NO** | **NO** | **OK** | **OK** | **OK** | **OK** | **OK** | **OK** | **OK** | **NO** | **NO** | **NO** | **NO** | **NO** | **NO** |
| **Total OK** | **4** | **5** | **10** | **10** | **8** | **8** | **8** | **9** | **9** | **9** | **9** | **9** | **8** | **10** | **8** | **9** | **7** | **8** | **5** | **6** |

**Leyenda:** OK = cumple, ~ = cumple parcialmente, NO = no cumple

**Notas clave:**

- Los 20 patrones se organizan por eje de KMP:
  - **Monoliticos (4):** B, C, Koin, Hybrid
  - **Android-only multi-modulo (8):** D, E2, G, H, I, K, Q, Q2 -- dependen de APIs JVM/Android (Dagger, ServiceLoader, PackageManager)
  - **KMP-compatible (5):** N, O, O2, P, P2 -- funcionan en todos los targets KMP (JVM, iOS, macOS, WASM)
  - **Partial KMP (3):** J, L, M -- el framework DI es KMP, pero el discovery usa ServiceLoader (JVM-only)

- **La novedad del Req 11** reordena la percepcion sobre escalabilidad:
  - **Zero-touch end-to-end (Req 6 + Req 11 ambos OK):** H, I, J, K, N, L, Koin, Hybrid, E2 (con `~` en Req 6). Estos escalan sin editar el wiring module ni el facade al anadir features/APIs.
  - **Auto-agregacion SI pero facade no-inmutable:** O, O2, P, P2. Cumplen Req 6 (el grafo se auto-agrega con `@ContributesTo`) pero fallan Req 11 (el `when` del facade crece por API). Mitigable con un procesador KSP propio que genere el `when` desde el componente.
  - **Ni lo uno ni lo otro:** D, G, Q, Q2. Requieren edicion central por feature (`when`/`ensure*`/`@Component(modules=[...])`) Y por API (`when` en el facade).

- **Q y Q2** ofrecen compile-time safety completa pero fallan dos criterios de zero-touch:
  (a) Req 6 por el listado explicito de `modules` en `@Component`, y (b) Req 11 por el
  `when` manual del facade. Doble coste de edicion central.

- **Top por totales OK estricto**: Koin (10), Hybrid (10), N (9), O2 (9). Los dos primeros
  son runtime DI (fallan Req 9, compile-time safety). O2 aparece alto pero falla Req 11
  (facade `when` manual) -- el total oculta la asimetria.

- **Sweet spot zero-touch end-to-end (Req 6 + Req 11 ambos cumplidos, con Req 7 OK)**:
  H, I, J, K, L, N. Todos 8/11 (o 9/11 para N). Diferencia entre ellos: compile-time safety
  (H parcial, I none, J/K parcial, L/N none).

- **E2 (8/11)**: semi-zero-touch (Req 6 parcial por `allEntries()`, Req 11 OK por registry).
  Unico patron Dagger con compile-time safety completa Y facade inmutable.

- **O2 (9/11) y P2 (8/11)** ganan compile-time safety completa pero pagan Req 11 con un
  `when` manual. Mitigable con un procesador KSP propio que genere el `when` desde el
  componente -- entonces suben a 10/11 y 9/11 respectivamente, y pasan a ser el sweet
  spot KMP con compile-time safety completa.

- **Los totales numericos no capturan la forma del compromiso**. Dos patrones con mismo
  total (p.ej. O2=9, N=10) pueden estar optimizando para ejes opuestos. Leer el total solo
  como "cuantos requisitos cumple", no como ranking directo. El ranking real depende de
  que ejes valora cada proyecto (compile-time vs runtime, KMP vs Android-only, etc).

- **Req 12 redefine el "sweet spot" para distribucion**: solo H, I, J, K, L, M, N cumplen
  los tres criterios zero-touch distribuible (6 + 11 + 12). Estos 7 patrones permiten
  publicar el sdk-integration como artefacto independiente (`runtimeOnly(features)`) y
  dejar que la app consumidora elija versiones de feature-impl a la carta (modelo BYOF).
  Los 13 patrones restantes atan el sdk-integration a versiones concretas de feature-impl
  en compile-time.

- **Compile-time DI (O/O2/P/P2/Q/Q2) estructuralmente no puede cumplir Req 12**: el merge
  de `@ContributesTo`/`@InstallIn` ocurre al compilar el sdk-integration. No hay
  workaround sin cambiar el paradigma a "consumer-side merge" (que obliga a la app a usar
  el mismo framework DI que el SDK).

- **N (10/12)** se consolida como el top KMP zero-touch distribuible. Tras el refactor
  que movio `ObservabilityKoinProvider` dentro de feature-observability-impl, L/M/N
  pasaron de 8 a 10 OKs y lograron Req 12.
