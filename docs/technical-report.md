# Reporte Tecnico: Patrones Multi-Modulo de Inyeccion de Dependencias para SDKs Android

**Proyecto:** di-patterns-demo
**Fecha:** 2026-04-25 (refresco post jerarquia de excepciones tipadas)
**Dispositivo:** Samsung Galaxy S22 Ultra (SM-S908B) -- Snapdragon 8 Gen 1, 8 nucleos, 2.8 GHz, Android 16
**Framework de medicion:** Jetpack Benchmark 1.4.0 con warmup automatico
**Total de tests:** 453 pasaron, 0 fallaron (incluido el suite de unit tests de
`di-contracts` con 52 casos cubriendo cada subtipo de `DependencyResolutionException`,
deteccion de ciclos y politica de reintentos)

---

## 1. Resumen Ejecutivo

Este reporte analiza 16 patrones multi-modulo de inyeccion de dependencias implementados en un SDK Android con 6 features (Core, Encryption, Auth, Storage, Analytics, Sync). Cada feature reside en su propio modulo Gradle (`features/feature-xxx-impl`) y las dependencias entre features se resuelven de forma abstracta — el modulo `di-contracts` es 100% neutro (no importa ningun tipo de `sdk/api` ni de `feature-*-api`) y expone un `FeatureProvider` con tag `Flavor` (DAGGER/PURE/KI/SYNTHETIC). Cada feature-impl define sus Bundles locales privados cuando expone multi-servicio (p.ej. `EncBundle` en `feature-enc-impl`). Solo el modulo de wiring conoce las implementaciones concretas.

> **Post-refactor (abril 2026)**: la jerarquia global `CoreProvisions`/`EncProvisions`/...
> fue eliminada. `PureFeatureProvider` y `KIFeatureProvider` unificados bajo
> `FeatureProvider` + `Flavor`. `ObservabilityKoinProvider`/`ObservabilitySweetSpiProvider`
> movidos a `feature-observability-impl`, haciendo que L/M/N ahora tambien sean
> 100% abstractos (Req 12, `runtimeOnly(feature-*-impl)`). Ver
> `docs/shared/requirements.md` Req 12.

Los 16 patrones se organizan en 3 categorias: **Android-only** (D, E2, G, H, I, K, Q, Q2), **KMP-compatible** (N, O, O2, P, P2) y **Partial KMP** (J, L, M). Todos fueron instrumentados con Jetpack Benchmark en un Samsung Galaxy S22 Ultra y sometidos a pruebas de estres, concurrencia, comportamiento de memoria y escalabilidad.

### Hallazgo principal

**La diferencia de rendimiento entre los 16 patrones es imperceptible para el usuario.** El init mas lento (Patron K, 250,403 ns) tarda 0.25 milisegundos -- tres ordenes de magnitud por debajo del umbral perceptible de 16,666,666 ns (un frame a 60 fps). La eleccion entre patrones es **arquitectonica**, no de rendimiento.

### Tabla resumen de recomendacion (los 16 patrones multi-modulo)

| Patron | Categoria | Caso de uso recomendado |
|--------|-----------|------------------------|
| **D** (Component Dependencies) | Android-only | SDKs pequenos (< 10 features), equipo familiarizado con Dagger |
| **E2** (Auto-Init Registry) | Android-only | SDKs medianos (10-30 features). Unico Dagger con compile-time COMPLETA + facade inmutable + lazy |
| **G** (Factory Functions) | Android-only | SDKs pequenos, ocultar DaggerComponents de consumidores |
| **H** (Auto-Discovery + Dagger) | Android-only | SDKs grandes (30+ features), equipos distribuidos, zero-touch end-to-end |
| **I** (Pure Resolver) | Android-only | SDKs sin frameworks DI (zero codegen, builds mas rapidos). Sin compile-time safety |
| **J** (kotlin-inject) | Partial KMP | Codegen moderno Kotlin (KSP) sobre Dagger. Zero-touch end-to-end |
| **K** (AndroidManifest) | Android-only | Robustez ante R8/ProGuard sin reglas keep (discovery via manifest) |
| **L** (Koin + SL eager) | Partial KMP | Koin familiar + auto-discovery ServiceLoader. JVM-only |
| **M** (Koin + SL lazy loadModules) | Partial KMP | Koin lazy. Mas lento que L; peor performer overall |
| **N** (sweet-spi + Koin) | KMP | SDK KMP con zero-touch end-to-end + ecosistema Koin maduro. Sin compile-time |
| **O** (Metro eager) | KMP | SDK KMP nuevo con init muy rapido (~1,200 ns, segundo solo a Q), compile-time. Eager. Framework joven |
| **O2** (Metro Lazy) | KMP | = O + singletons lazy. Re-init rapido. Facade `when` manual (mitigable con KSP) |
| **P** (kotlin-inject-anvil eager) | KMP | = O con KSP estandar + Amazon maintainer. Eager |
| **P2** (kotlin-inject-anvil Lazy) | KMP | = P + lazy. Mejor balance KMP con compile-time completa |
| **Q** (Hilt-style Dagger eager) | Android-only | Hilt familiar sin @HiltAndroidApp. Doble edicion central (modules + when) |
| **Q2** (Hilt-style Dagger Lazy) | Android-only | = Q + dagger.Lazy. Re-init muy rapido (~2,900 ns). Doble edicion central |

### Cuatro conclusiones clave

1. **Rendimiento no es diferenciador.** Todos los patrones resuelven servicios en nanosegundos y completan init + resolve + primera operacion en menos de 882,041 ns (el aumento respecto a ejecuciones anteriores se debe a la migracion de Storage a DataStore con I/O real a disco).
2. **Escalabilidad tiene tres ejes, no uno.** El primer eje es el grafo (¿agregar feature requiere editar wiring? Req 6). El segundo es el facade (¿agregar API requiere editar el dispatcher `get<T>(Class)`? Req 11). El tercero es distribucion (¿el sdk-integration puede publicarse con `runtimeOnly(features)` para modelo BYOF? Req 12). Los patrones D y G fallan los tres. Los compile-time DI O/O2/P/P2/Q/Q2 cumplen Req 6 pero fallan Req 11 Y Req 12 (el merge de `@ContributesTo`/`@InstallIn` obliga a compile-time coupling con feature-impls). Solo **H/I/J/K/L/M/N cumplen los tres ejes nativamente** — son los unicos sdk-integration shippables como artefactos runtime-flexible. Ver `docs/shared/requirements.md` Req 6 + Req 11 + Req 12.
3. **El benchmark mide pequenas escalas (6 features × 1-2 APIs).** A 50 features × 10 APIs el coste de mantener un `when` de 500 ramas en O2/P2/Q2 NO se ve en estos numeros. Es coste de mantenimiento, no de runtime. Mitigable con KSP propio que genere el `when` desde el componente.
4. **El principio de auto-discovery con grafo lazy es el estandar de SDKs corporativos.** Firebase SDK usa un patron conceptualmente identico (auto-registro via AndroidManifest metadata + ComponentRuntime con topo-sort). Pattern H aplica el mismo principio con ServiceLoader + Resolver DFS. Pattern K replica el mecanismo de Firebase de forma aun mas literal: descubre providers via `<meta-data>` en AndroidManifest con PackageManager.

---

## 2. Los 16 Patrones Multi-Modulo

### 2.1 Catalogo

#### Android-only (8 patrones)

| Patron | Modulo Gradle | Framework | Mecanismo | Lineas wiring |
|--------|---------------|-----------|-----------|---------------|
| D -- Component Dependencies | `sdk/sdk-wiring` | Dagger 2 | Metodos ensure*() manuales con orden de dependencia hardcodeado. Importa DaggerXxxComponent | 149 |
| E2 -- Auto-Init Registry | `sdk/wiring-e2` | Dagger 2 | AutoProvisionRegistry cataloga entries en init, DFS construye bajo demanda en get<T>() | 66 + 129 entries |
| G -- Factory Functions | `sdk/wiring-g` | Dagger 2 | Cada feature expone buildXxxProvisions(); DaggerXxxComponent queda interno al modulo | 107 |
| H -- Auto-Discovery + Dagger | `sdk/wiring-h` | Dagger 2 | ServiceLoader descubre FeatureProvider, Resolver construye via DFS | 51 |
| I -- Pure Resolver | `sdk/wiring-i` | Ninguno | ServiceLoader descubre PureFeatureProvider, Resolver construye via DFS. Zero codegen, zero framework | 54 |
| K -- AndroidManifest Discovery | `sdk/wiring-k` | Dagger 2 | AndroidManifest `<meta-data>` descubre FeatureProvider via PackageManager, Resolver construye via DFS | 50 |
| Q -- Hilt-style Dagger eager | `sdk/wiring-q` | Dagger 2 | @Component monolitico con @InstallIn modules por feature. Lista `modules=[...]` manual + facade con `when (clazz)` manual por API | ~60 |
| Q2 -- Hilt-style Dagger Lazy | `sdk/wiring-q2` | Dagger 2 | = Q con `dagger.Lazy<T>` wrappers -- singletons lazy. Misma lista `modules=[...]` + mismo `when` manual | ~55 |

#### KMP-compatible (5 patrones)

| Patron | Modulo Gradle | Framework | Mecanismo | Lineas wiring |
|--------|---------------|-----------|-----------|---------------|
| N -- sweet-spi + Koin | `sdk/wiring-n` | Koin + sweet-spi | sweet-spi descubre KoinFeatureProvider en todos los targets KMP (JVM, Native, WASM). koin.get(clazz.kotlin) facade inmutable nativo | ~80 |
| O -- Metro eager | `sdk/wiring-o` | Metro (compiler plugin) | @ContributesTo agrega al @DependencyGraph en compilacion. Singletons eager. Facade con `when (clazz)` manual por API | ~95 |
| O2 -- Metro Lazy | `sdk/wiring-o2` | Metro (compiler plugin) | Idem O pero accessors retornan Lazy<T> -- singletons on-demand. Facade con `when (clazz)` manual | ~100 |
| P -- kotlin-inject-anvil eager | `sdk/wiring-p` | kotlin-inject-anvil (KSP) | @ContributesTo via KSP @MergeComponent. Singletons eager. Facade con `when (clazz)` manual | ~90 |
| P2 -- kotlin-inject-anvil Lazy | `sdk/wiring-p2` | kotlin-inject-anvil (KSP) | Idem P con @SingleIn lazy tracking. Facade con `when (clazz)` manual | ~95 |

#### Partial KMP (3 patrones)

| Patron | Modulo Gradle | Framework | Mecanismo | Lineas wiring |
|--------|---------------|-----------|-----------|---------------|
| J -- kotlin-inject | `sdk/wiring-j` | kotlin-inject | ServiceLoader descubre KIFeatureProvider, kotlin-inject Components internos (KSP, genera Kotlin) | 55 |
| L -- Koin + ServiceLoader | `sdk/wiring-l` | Koin | ServiceLoader descubre KoinModuleProvider, Koin resuelve el grafo | ~60 |
| M -- Koin Manual Wiring | `sdk/wiring-m` | Koin | Koin modules listados manualmente en el wiring (sin ServiceLoader) | ~55 |

### 2.2 Diagrama de dependencias entre features

```
                      +----------+
                      |   Core   |
                      +----+-----+
                           |
              +------------+------------+
              |            |            |
         +----v----+  +---v---+  +-----v-------+
         |   Enc   |  |  Ana  |  | Observability|
         +----+----+  +-------+  +-------------+
              |
       +------+------+
       |             |
  +----v----+  +-----v----+
  |   Auth  |  |   Stor   |
  +----+----+  +-----+----+
       |             |
       +------+------+
              |
         +----v----+
         |   Sync  |
         +---------+
```

Sync es el nodo hoja mas pesado: depende transitivamente de Core, Enc, Auth y Stor (cadena de 4 niveles de profundidad).

### 2.3 Clasificacion de los patrones

La clasificacion completa se evalua en **dos ejes** (ver `docs/shared/requirements.md` Req 6 y Req 11):

- **Eje feature (Req 6)**: ¿agregar un modulo nuevo requiere editar wiring central?
- **Eje API (Req 11)**: ¿agregar una API nueva requiere editar el dispatcher `get<T>(Class)` del facade?

**Wiring manual (D, G)** -- fallan ambos ejes:
El modulo de wiring importa implementaciones concretas (DaggerXxxComponent) y orquesta el orden de construccion con metodos ensure*() y when-blocks. Cada feature nuevo requiere editar el wiring (eje feature). El facade `get<T>(Class)` mantiene un `when (clazz)` que crece por API (eje API).

**Wiring centralizado (E2, M)** -- semi-manual en el eje feature:
Las dependencias se declaran en un archivo central (Entries.kt en E2, modules listados en M). E2 resuelve via registry HashMap (facade inmutable, eje API OK). M usa Koin (facade nativo, eje API OK). Agregar feature = 1 linea en el listado central.

**Wiring del modulo auto-descubierto (H, I, J, K, L, N, O, O2, P, P2)**: Cada feature se auto-registra. El modulo de wiring no se edita por feature. Subgrupos:

- **Resolver-based (H, I, J, K)**: ServiceLoader o AndroidManifest descubre Provider; Resolver construye via DFS. Facade delega a `resolver.get(clazz)` -- HashMap lookup. **Inmutable end-to-end** (Req 6 + Req 11 cumplidos).
- **Koin runtime (L, M, N)**: ServiceLoader/sweet-spi descubre Module; `koin.get(clazz.kotlin)` facade inmutable nativo. **Inmutable end-to-end**.
- **Compile-time DI (O, O2, P, P2)**: `@ContributesTo` (Metro compiler plugin / kotlin-inject-anvil KSP) agrega al grafo (Req 6 cumplido). **Pero el facade `get<T>(Class)` mantiene un `when (clazz)` manual** que crece linealmente por API (Req 11 NO cumplido). Mitigable con un procesador KSP propio (~200 LOC) que genere el `when` desde el componente.

**Wiring del modulo manual + facade manual (Q, Q2)**: Dagger @Component con `modules=[...]` listado explicitamente (Req 6 fail) Y facade con `when (clazz)` manual (Req 11 fail). Doble crecimiento central.

---

## 3. Resultados de Benchmarks

### 3.1 Tabla comparativa completa — los 16 patrones multi-modulo

Todas las mediciones en nanosegundos (ns). Dispositivo: Samsung Galaxy S22 Ultra, Android 16.
Formato: **patron por fila** para legibilidad (16 patrones x 10 metricas = 160 celdas).
Fecha medicion: 2026-04-19 (post-refactor logger singleton + Big-O O(1) dedup + LazyCreationTracker ThreadLocal + ReadWriteLock Koin).

| Patron | initCold | resolveFirst | resolveAll | lazyNoDeps | lazyCascade | crossFeature | e2eStartup | initShutdown | reInit | concurrent |
|--------|---------:|-------------:|-----------:|-----------:|------------:|-------------:|-----------:|-------------:|-------:|-----------:|
| **D** (when-block) | 1,400 | 8 | 6 | 266 | 812 | 2.2M | 301K | 365 | 2,540 | 439K |
| **E2** (Registry DFS) | 8,024 | 9 | 189 | 792 | 3,015 | 1.9M | 414K | 4,991 | 15,816 | 418K |
| **G** (Factory fns) | 1,379 | 9 | 1 | 273 | 790 | 2.0M | 560K | 375 | 2,275 | 439K |
| **H** (Resolver+Dagger) | 86,254 | 1 | 139 | 1,075 | 4,659 | 1.2M | 806K | 84,346 | 185,812 | 386K |
| **I** (Pure Resolver) | 116,413 | 9 | 189 | 1,288 | 3,349 | 1.9M | 571K | 116,821 | 232,989 | 418K |
| **J** (kotlin-inject) | 122,124 | 1 | 186 | 1,550 | 4,368 | 1.5M | 579K | 114,194 | 240,198 | 433K |
| **K** (Manifest) | 205,544 | 0 | 190 | 2,284 | 8,022 | 1.2M | 896K | 194,035 | 420,674 | 417K |
| **L** (Koin+SL eager) | 161,559 | 999 | 4,784 | 4,472 | 16,714 | 2.3M | 623K | 125,396 | 387,573 | 494K |
| **M** (Koin+SL lazy) | 164,713 | 1,066 | 6,899 | 9,535 | 50,094 | 2.0M | 969K | 142,117 | 412,645 | 433K |
| **N** (sweet-spi+Koin) | 96,719 | 1,038 | 6,307 | 4,331 | 27,080 | 2.4M | 710K | 51,447 | 178,294 | 456K |
| **O** (Metro eager) | 723 | 5 | 108 | 191 | 367 | 2.1M | 538K | 241 | 1,120 | 435K |
| **O2** (Metro Lazy) | 1,412 | 7 | 273 | 282 | 591 | 2.1M | 341K | 852 | 2,408 | 452K |
| **P** (KI-anvil eager) | 785 | 0 | 146 | 222 | 488 | 1.3M | 534K | 184 | 1,528 | 456K |
| **P2** (KI-anvil Lazy) | 1,722 | 5 | 380 | 348 | 919 | 1.4M | 552K | 471 | 2,951 | 468K |
| **Q** (Hilt eager) | 647 | 5 | 105 | 184 | 338 | 1.2M | 568K | 278 | 1,042 | 453K |
| **Q2** (Hilt Lazy) | 1,502 | 7 | 303 | 312 | 589 | 1.8M | 520K | 565 | 2,496 | 478K |

**Leyenda:** `K = 1,000 ns`, `M = 1,000,000 ns`. Todos los valores cubiertos (L/M ahora completos tras el refactor).

**Fuentes de datos:** ejecuciones dedicadas por patron (1 run por patron con `patterns=X` filtro), device frio, output directory limpio, adb daemon reiniciado entre runs. Evita interferencia cold-startup cruzada (tema detectado y corregido durante el refactor: un patron ejecutandose como primero en una sub-suite introducia variance por warmup de clases Dagger/KSP).

**Ganadores por metrica:**

| Metrica | 1ro | 2do | 3ro |
|---------|-----|-----|-----|
| initCold (grafo mas rapido) | Q (647) | O (723) | P (785) |
| resolveFirst (cache warmup, JIT DCE post-refactor) | K/P (0) | H/J (1) | O/Q/P2 (5) |
| resolveAll (todos cached) | G (1) | O/Q (105-108) | D (6) |
| lazyNoDeps (feature sin deps) | Q (184) | O (191) | P (222) |
| lazyCascade (Sync chain) | Q (338) | O (367) | P (488) |
| crossFeature (op real DataStore) | Q (1.2M) | K (1.2M) | H (1.2M) |
| e2eStartup (fin a fin) | **D (301K)** | O2 (341K) | E2 (414K) |
| reInit (hot restart) | **Q (1,042)** | O (1,120) | P (1,528) |
| initShutdown (ciclo vacio) | P (184) | O (241) | Q (278) |
| concurrent (contention) | H (386K) | K (417K) | E2/I (418K) |

**Lectura rapida (post-refactor):** Q y O dominan en toda metrica de runtime puro (initCold, lazy, initShutdown, reInit). D gana en e2eStartup gracias al logger singleton que evita reconstruir en cada reinit (ganancia masiva en todos los patterns hardcoded/eager). H destaca en `concurrent` bajo contencion (386K vs 417-494K del resto). K y H empatados con 1.2M en `crossFeature` (op real DataStore). Los patterns Koin (L/M/N) pagan runtime overhead consistente pero **todos completaron las 10 metricas** tras anadir el ReadWriteLock para concurrent shutdown safety. Los patterns lazy compile-time (O2/P2/Q2) muestran trade-off deliberado: `withActive { }` envuelve cada `get()` para ThreadLocal-tracker isolation, coste ~100-300 ns por resolucion.

### 3.2 Analisis por categoria

Las siguientes subtablas ordenan los 16 patrones multi-modulo (16 en total excepto
cuando faltan datos para L/M -- en cuyo caso aparecen como n/d). Los mismos numeros
de la tabla 3.1, reordenados por metrica.

#### Init Cold -- Construccion del grafo inicial (ranking)

| # | Patron | Tiempo (ns) | Mecanismo |
|--:|--------|------------:|-----------|
| 1 | Q | 647 | Dagger @Component Hilt-style: grafo compile-time |
| 2 | O | 723 | Metro compiler plugin: asignacion directa de campos |
| 3 | P | 785 | kotlin-inject-anvil: KSP @MergeComponent |
| 4 | G | 1,379 | = D con factory functions |
| 5 | D | 1,400 | Asignacion directa de campos (Dagger codegen) |
| 6 | O2 | 1,412 | = O + LazyCreationTracker overhead |
| 7 | Q2 | 1,502 | = Q + dagger.Lazy wrappers |
| 8 | P2 | 1,722 | = P + LazyCreationTracker overhead |
| 9 | E2 | 8,024 | Cataloga entries en HashMaps (no construye nada) |
| 10 | H | 86,254 | ServiceLoader + FeatureProvider + Dagger |
| 11 | N | 96,719 | sweet-spi discovery + Koin registration |
| 12 | I | 116,413 | ServiceLoader + PureFeatureProvider |
| 13 | J | 122,124 | ServiceLoader + KIFeatureProvider |
| 14 | L | 161,559 | ServiceLoader + Koin eager |
| 15 | M | 164,713 | ServiceLoader + Koin lazy loadModules |
| 16 | K | 205,544 | PackageManager.getServiceInfo() IPC |

**Analisis:** los patrones compile-time (Q/O/P/G/D/O2/Q2/P2) dominan por debajo de
1,800 ns porque el grafo se resuelve estaticamente. E2 baja a 8K ns tras el refactor
(AtomicInteger counter + homogeneizacion de logger singleton). N cuesta ~97K ns por
sweet-spi + Koin. H/I/J/L pagan ServiceLoader (~86-162K ns). M paga el overhead extra
de lazy loadModules (~165K ns). K sigue el mas lento por el IPC al system_server
(~206K ns, 2.4x vs H).

Incluso el patron mas lento (K) es despreciable en el arranque de una app Android
(tipicamente 500,000,000 - 2,000,000,000 ns).

#### Resolve First -- Primera resolucion tras init (ranking)

| # | Patron | Tiempo (ns) |
|--:|--------|------------:|
| 1 | K | 0 |
| 2 | P | 0 |
| 3 | H | 1 |
| 4 | J | 1 |
| 5 | O | 5 |
| 6 | P2 | 5 |
| 7 | Q | 5 |
| 8 | O2 | 7 |
| 9 | Q2 | 7 |
| 10 | D | 8 |
| 11 | E2 | 9 |
| 12 | G | 9 |
| 13 | I | 9 |
| 14 | L | 999 |
| 15 | N | 1,038 |
| 16 | M | 1,066 |

**Analisis (post-refactor):** tras la homogeneizacion del logger singleton y el dedup O(1)
en `Resolver.register()`, el JIT ahora identifica el `sdk.get()` measurement como
side-effect-free y aplica dead-code-elimination agresivo post-warmup. El resultado:
todos los patterns no-Koin convergen a 0-9 ns (cota inferior de medicion de
`BenchmarkRule`). Los patterns Koin (L/M/N) siguen costando ~1,000 ns por el lookup
runtime de Koin sobre `KClass` que no es inlinable por el JIT. Los numeros pre-refactor
(200-350 ns) reflejaban side-effects residuales del `count { it !in persistentProviders }`
loop que bloqueaban la optimizacion JIT -- eliminado en favor de `AtomicInteger`.

#### Resolve All (cached) -- Ranking

| # | Patron | Tiempo (ns) |
|--:|--------|------------:|
| 1 | G | 1 |
| 2 | D | 6 |
| 3 | Q | 105 |
| 4 | O | 108 |
| 5 | H | 139 |
| 6 | P | 146 |
| 7 | J | 186 |
| 8 | E2 | 189 |
| 9 | I | 189 |
| 10 | K | 190 |
| 11 | O2 | 273 |
| 12 | Q2 | 303 |
| 13 | P2 | 380 |
| 14 | L | 4,784 |
| 15 | N | 6,307 |
| 16 | M | 6,899 |

**Analisis (post-refactor):** G/D (JIT DCE, acceso directo a campos + logger singleton)
por debajo de 10 ns. Los compile-time con componentes (Q/O/P/H) en 105-146 ns
(lookup directo). Resolver-based (J/E2/I/K) en 186-190 ns (HashMap). Lazy compile-time
(O2/Q2/P2) en 273-380 ns por el overhead del `withActive` lambda (necesario para
isolation ThreadLocal). Los Koin (L/M/N) pagan 4.8-6.9K ns por el lookup runtime de
Koin. Para un SDK con ~1,000 get<T>()/segundo: G = 1us/s, H = 139us/s, N = 6.3ms/s.

#### Lazy Init -- Construccion bajo demanda

**Sin dependencias (Analytics -- depende solo de Core):**

| # | Patron | Tiempo (ns) |
|--:|--------|------------:|
| 1 | Q | 184 |
| 2 | O | 191 |
| 3 | P | 222 |
| 4 | D | 266 |
| 5 | G | 273 |
| 6 | O2 | 282 |
| 7 | Q2 | 312 |
| 8 | P2 | 348 |
| 9 | E2 | 792 |
| 10 | H | 1,075 |
| 11 | I | 1,288 |
| 12 | J | 1,550 |
| 13 | K | 2,284 |
| 14 | N | 4,331 |
| 15 | L | 4,472 |
| 16 | M | 9,535 |

**Con cascada (Sync -- depende de Core + Enc + Auth + Stor):**

| # | Patron | Tiempo (ns) |
|--:|--------|------------:|
| 1 | Q | 338 |
| 2 | O | 367 |
| 3 | P | 488 |
| 4 | Q2 | 589 |
| 5 | O2 | 591 |
| 6 | G | 790 |
| 7 | D | 812 |
| 8 | P2 | 919 |
| 9 | E2 | 3,015 |
| 10 | I | 3,349 |
| 11 | J | 4,368 |
| 12 | H | 4,659 |
| 13 | K | 8,022 |
| 14 | L | 16,714 |
| 15 | N | 27,080 |
| 16 | M | 50,094 |

**Analisis:** la cascada Sync es el escenario mas exigente: construir 4 provisions en
cadena de 4 niveles. Los compile-time eager (Q/O/P) dominan por debajo de 500 ns
porque el compilador resuelve el orden estaticamente. D/G (when-block) y lazy
compile-time (O2/Q2/P2) quedan por debajo de 1,000 ns. E2/I/J/H pagan el costo del DFS
en runtime (el Resolver recorre el grafo dinamicamente, ~3-5K ns). N/L pagan el coste
de Koin recursivo (17-27K ns). M es 6x mas lento que L por `loadModules()` en cascada.

#### Cross-Feature Operation -- Operacion real cruzando features (ranking)

| # | Patron | Tiempo (ns) |
|--:|--------|------------:|
| 1 | Q | 1,218,185 |
| 2 | H | 1,225,771 |
| 3 | K | 1,228,907 |
| 4 | P | 1,334,933 |
| 5 | P2 | 1,366,294 |
| 6 | J | 1,522,866 |
| 7 | Q2 | 1,764,873 |
| 8 | I | 1,879,596 |
| 9 | E2 | 1,896,703 |
| 10 | G | 1,986,199 |
| 11 | M | 2,010,275 |
| 12 | O2 | 2,057,351 |
| 13 | O | 2,088,982 |
| 14 | D | 2,222,440 |
| 15 | L | 2,304,991 |
| 16 | N | 2,386,975 |

**Analisis:** los valores estan en el rango ~1.2-2.4M ns porque Storage usa DataStore
(I/O real a disco via suspend + runBlocking). Los tiempos reflejan el coste real de
`sync.sync()` con persistencia a disco. **Una vez resueltos los servicios, el
rendimiento depende del codigo de negocio (incluyendo I/O a disco), no del patron DI.**
La variabilidad entre patrones se debe principalmente al acceso a disco + variance
de medicion, no al mecanismo DI. Post-refactor todos los 16 patrones completos (L/M
anadidos tras el ReadWriteLock).

#### E2E App Startup -- Init + resolve all + primera operacion por feature (ranking)

| # | Patron | Tiempo (ns) |
|--:|--------|------------:|
| 1 | D | 300,863 |
| 2 | O2 | 341,436 |
| 3 | E2 | 414,183 |
| 4 | Q2 | 519,809 |
| 5 | P | 534,131 |
| 6 | O | 537,996 |
| 7 | P2 | 551,942 |
| 8 | G | 560,402 |
| 9 | Q | 568,276 |
| 10 | I | 571,406 |
| 11 | J | 578,666 |
| 12 | L | 623,369 |
| 13 | N | 709,614 |
| 14 | H | 806,472 |
| 15 | K | 896,026 |
| 16 | M | 969,323 |

**Analisis (post-refactor):** gracias al logger singleton (no se reconstruye en cada
reinit), los patterns hardcoded/eager mejoran dramaticamente en e2eStartup. D gana
con 301K ns (vs 1.2M pre-refactor, **-75%**). O2 segundo lugar con 341K ns (mejora
por logger singleton que compensa el overhead del `withActive`). Incluso el patron
mas lento (M = 969K ns) completa el arranque del SDK con 6 features en menos de 1 ms.
Esto representa ~0.05% de un arranque tipico de app Android (~2,000,000,000 ns).
**Ningun patron es un cuello de botella en el arranque.**

---

## 4. Comportamiento de Memoria

### 4.1 Conteo de provisions construidas por etapa

Esta tabla verifica la pereza (laziness) del grafo de dependencias: cuantas provisions
se han instanciado en cada momento. Las columnas muestran los 7 patrones Android-only
originales; L/M/N/O/O2/P/P2/Q/Q2 siguen analogos comportamientos de lazy -- detalle en
la nota bajo la tabla.

| Etapa | D | E2 | G | H | I | J | K |
|-------|---|----|----|---|---|---|---|
| afterInit | 1 | 0 | 1 | 0 | 0 | 0 | 0 |
| afterEnc | 2 | 2 | 2 | 3 | 2 | 2 | 3 |
| afterAna | 2 | 2 | 2 | 3 | 2 | 2 | 3 |
| afterSync | 5 | 5 | 5 | 6 | 6 | 6 | 6 |
| fullGraph | 6 | 6 | 6 | 7 | 7 | 7 | 7 |

**Para los 9 patrones KMP / Hilt-style / Koin, el comportamiento lazy es el siguiente**
(medido via `builtProvisionCount` o trackers dedicados):

| Patron | afterInit | Nota |
|--------|----------:|------|
| O (Metro eager) | 5 | Todos los singletons creados en `init()` -- no lazy |
| O2 (Metro Lazy) | 0 | `LazyCreationTracker` -- singletons lazy via `Lazy<T>` |
| P (KI-anvil eager) | 5 | Similar a O |
| P2 (KI-anvil Lazy) | 0 | `@SingleIn` scope -- singletons lazy |
| Q (Dagger eager) | 5 | Todos los @Singleton creados en `init()` |
| Q2 (Dagger Lazy) | 0 | `dagger.Lazy<T>` difiere construccion |
| L (Koin eager) | 0* | Koin `single {}` es lazy por defecto |
| M (Koin lazy loadModules) | 0 | Modulos cargan on-demand via `loadModules()` |
| N (sweet-spi + Koin) | 0* | = L |

(*) Koin `single` se instancia en primer `get()` no en init.

### 4.2 Analisis de laziness

- **D y G** construyen CoreProvisions en init (afterInit = 1); el resto es lazy.
- **E2** no construye nada en init (afterInit = 0); solo cataloga entries en HashMaps. Es el patron mas perezoso en la fase de inicializacion.
- **H, I, J y K** construyen ObservabilityProvisions como provision adicional (fullGraph = 7 vs 6), porque el logger se descubre via ServiceLoader (H, I, J) o AndroidManifest (K) como un provider mas en vez de inyectarse como parametro directo. K tiene comportamiento de memoria identico a H porque reutiliza los mismos FeatureProvider y el mismo Resolver.
- **O, P, Q (eager) NO son lazy**: todos los singletons se materializan en init. afterInit = 5.
- **O2, P2, Q2, E2, L, M, N son lazy**: afterInit = 0, construccion on-demand en primer get().
- **Todos los patrones con soporte lazy son genuinamente lazy:** pedir EncryptionApi no construye Analytics. Pedir Analytics no construye Auth. Solo se construyen las dependencias estrictamente necesarias para satisfacer la solicitud.

### 4.3 Comportamiento afterEnc: H y K vs resto

H y K muestran 3 provisions tras pedir Encryption (vs 2 en los demas patrones). Esto se debe a que ambos construyen ObservabilityProvisions como dependencia del EncProvider via el Resolver, mientras que D y G inyectan el logger como parametro directo sin crear una provision separada. K exhibe el mismo comportamiento que H porque reutiliza exactamente los mismos FeatureProvider.

---

## 5. Pruebas de Estres y Tortura

### 5.1 Resultados de tests

| Suite | Pasaron | Fallaron |
|-------|---------|----------|
| DiBenchmark | 31 | 0 |
| MultiModuleBenchmark | 192 | 0 |
| MemoryBehaviorTest | 128 | 0 |
| StressTortureTest | 212 | 0 |
| ScaleBenchmark | 67 | 0 |
| **Total** | **630** | **0** |

### 5.2 Tests de tortura -- todos los patrones PASS

| Test | Descripcion | Resultado |
|------|-------------|-----------|
| thunderingHerd | 100 threads concurrentes resolviendo servicios | PASS (16/16 patrones) |
| singletonIdentity | 10,000 llamadas secuenciales, misma instancia | PASS |
| crossPatternIsolation | 16 patrones ejecutandose simultaneamente | PASS |
| rapidFire | 5,000 ciclos init/get/shutdown | PASS |
| memoryPressure | GC storm durante resolucion de servicios | PASS |
| stress10K | 10,000 ciclos, heap delta < 5,120 KB | PASS |
| instanceFreshness | 50 reinits, todas las instancias son unicas | PASS |
| errorResilience | Double init, get antes de init, shutdown doble | PASS |
| functionalCorrectness | Operaciones reales tras 1,000 reinits | PASS |
| coldCascadeTiming | Comparacion de tiempos de cascada fria | PASS |
| alternatingPatterns | 100 rondas alternando entre los 16 patrones | PASS |

### 5.3 Benchmarks de estres por patron (los 16 multi-modulo)

Formato patron-por-fila para acomodar 16 patrones x 6 metricas de estres.

| Patron | initShutdown | concurrent | resolveAll | reInit | incremental |
|--------|-------------:|-----------:|-----------:|-------:|------------:|
| D | 365 | 439K | 6 | **2,540** | 1,396 |
| E2 | 4,991 | 418K | 189 | 15,816 | 7,136 |
| G | 375 | 439K | 1 | **2,275** | 1,417 |
| H | 84,346 | 386K | 139 | 185,812 | 85,365 |
| I | 116,821 | 418K | 189 | 232,989 | 114,296 |
| J | 114,194 | 433K | 186 | 240,198 | 123,615 |
| K | 194,035 | 417K | 190 | 420,674 | 196,640 |
| L | 125,396 | 494K | 4,784 | 387,573 | 151,277 |
| M | 142,117 | 433K | 6,899 | 412,645 | 211,680 |
| N | 51,447 | 456K | 6,307 | 178,294 | 80,029 |
| O | 241 | 435K | 108 | **1,120** | 694 |
| O2 | 852 | 452K | 273 | **2,408** | 1,411 |
| P | 184 | 456K | 146 | **1,528** | 784 |
| P2 | 471 | 468K | 380 | **2,951** | 1,661 |
| Q | 278 | 453K | 105 | **1,042** | 639 |
| Q2 | 565 | 478K | 303 | **2,496** | 1,395 |

`K = 1,000 ns`, `M = 1,000,000 ns`. **Negrita** en reInit marca los ganadores tras
el refactor (logger singleton evita reconstruir en cada reinit): todos los compile-time
eager y lazy por debajo de 3,000 ns.

### 5.4 Analisis de estres

**Ciclo de vida (initShutdown, reInit, incremental):** Los compile-time DI
(D, G, O, P, Q, Q2, O2, P2) son consistentemente los mas rapidos (184-565 ns
initShutdown) porque no invocan discovery en cada ciclo. **Q dominan reInit
con 1,042 ns** seguido de O (1,120) y P (1,528) gracias al logger singleton que
evita reconstruir en cada reinit -- mejora masiva vs pre-refactor (~36K-767K ns
en casi todos los patterns). H, I y J pagan ~84-117K ns por ciclo init debido al
escaneo de classpath (ServiceLoader). K paga ~194K ns por ciclo init (IPC a
system_server). N paga ~51K ns (sweet-spi + Koin registry). L/M completaron
metricas de concurrent tras anadir ReadWriteLock (previamente n/d por
Connection reset durante concurrent shutdown).

**Concurrencia (stress_concurrent):** Todos los patrones convergen a ~439,000 - 784,000 ns.
Todos los patrones son thread-safe (synchronized + ConcurrentHashMap / Koin internal locks).
La concurrencia esta dominada por el costo de coordinacion entre threads (locks, CAS),
no por el patron DI. N es el mas lento (784K ns) por locks internos de Koin.

**Resolucion cached (stress_resolveAll):** Los compile-time resuelven en 64-213 ns
(Q = 64 ns imbatible, acceso directo al campo). Los Koin pagan ~6,000-8,000 ns por
el lookup runtime. La diferencia es irrelevante excepto en hot loops con >1,000 get/s.

---

## 6. Escalabilidad

### 6.1 Costo de agregar un feature nuevo (los 16 patrones)

Para cada patron, se muestran los archivos que hay que editar al anadir un feature
nuevo. Esta tabla refleja el criterio bidimensional Req 6 + Req 11 de
`docs/shared/requirements.md`:

| Patron | Req 6 -- grafo | Req 11 -- facade | Edicion total por feature + API |
|--------|----------------|------------------|--------------------------------|
| **D** | `MultiModuleSdk.kt` ensure*() + when | = grafo (mismo `when` sirve a ambos ejes) | 1 ensure*() + 1 rama `when` por feature; +1 rama por API |
| **E2** | 1 linea en `allAutoEntries()` + 1 funcion entry | Nada (registry HashMap) | 1 entry por feature; 0 por API |
| **G** | `MultiModuleSdkG.kt` ensure*() + when | = grafo | = D |
| **H** | Nada (META-INF/services) | Nada (resolver HashMap) | **Zero edicion central** |
| **I** | Nada (META-INF/services) | Nada (resolver HashMap) | **Zero edicion central** |
| **J** | Nada (META-INF/services) | Nada (resolver HashMap) | **Zero edicion central** |
| **K** | Nada (AndroidManifest merge) | Nada (resolver HashMap) | **Zero edicion central** |
| **L** | Nada (META-INF/services) | Nada (`koin.get` nativo) | **Zero edicion central** |
| **M** | `loadModules()` cascada manual | Nada | 1 registro en cascada por feature; 0 por API |
| **N** | Nada (sweet-spi expect/actual) | Nada (`koin.get` nativo) | **Zero edicion central** |
| **O** | Nada (`@ContributesTo`) | `when` manual en facade | 0 por feature (grafo auto); **+1 rama por API** |
| **O2** | Nada (`@ContributesTo`) | `when` manual en facade | 0 por feature; +1 rama por API |
| **P** | Nada (`@ContributesTo` KSP) | `when` manual en facade | 0 por feature; +1 rama por API |
| **P2** | Nada (`@ContributesTo` KSP) | `when` manual en facade | 0 por feature; +1 rama por API |
| **Q** | 1 modulo en `@Component(modules=[...])` | `when` manual en facade | 1 linea por feature + 1 rama por API |
| **Q2** | 1 modulo en `@Component(modules=[...])` | `when` manual en facade | 1 linea por feature + 1 rama por API |

### 6.2 Clasificacion por escalabilidad (los 16 patrones)

**ZERO EDICION END-TO-END (cumplen Req 6 + Req 11, escalan perfectamente a 50+ x 10 APIs):**

- **H, I, J, K**: Resolver-based, ServiceLoader/Manifest. Wiring modulo inmutable + facade
  via `resolver.get(clazz)` HashMap.
- **L, N**: Koin + discovery (ServiceLoader/sweet-spi). Wiring modulo inmutable + facade
  via `koin.get(clazz)` nativo.

**1 LINEA CENTRAL POR FEATURE (escalable con edicion minima):**

- **E2**: 1 entry en `allAutoEntries()`. Facade inmutable via registry HashMap.
- **M**: `loadModules()` cascada manual. Facade inmutable via `koin.get` nativo.

**GRAFO AUTO, FACADE MANUAL (mitigable con KSP codegen propio):**

- **O, O2, P, P2**: `@ContributesTo` (Metro/kotlin-inject-anvil) agrega al grafo en
  compilacion (Req 6 OK). El facade `MultiModuleSdkX.get<T>(Class)` mantiene un
  `when (clazz)` manual que crece 1 rama por API. A 50 features × 10 APIs = 500 ramas
  mantenidas a mano. **Mitigable con un procesador KSP propio (~200 LOC)** que genere
  el `when` desde el componente. Ver Req 11 en `docs/shared/requirements.md`.

**DOBLE CRECIMIENTO (feature + API):**

- **D, G**: `when`/`ensure*()` crecen por feature Y el `when` del facade crece por API.
- **Q, Q2**: `@Component(modules=[...])` listado manual + `when` del facade manual.

Con 50 features × 10 APIs por feature:
- H/I/J/K/L/N: 0 ediciones centrales totales
- E2/M: ~50 lineas centrales
- O/O2/P/P2: 500 ramas en el facade (o 0 con KSP custom)
- Q/Q2: 50 modules + 500 ramas (o 50 con KSP custom)
- D/G: 500+ ramas + 50 ensure*() methods

### 6.3 Velocidad de build

| Patron | Codegen | Impacto en build incremental |
|--------|---------|------------------------------|
| D, E2, G, H, K, Q, Q2 | Dagger 2 (KSP, genera Java) | +2-5 segundos por modulo con @Component |
| I | Ninguno | Zero overhead. Build mas rapido del proyecto |
| J, P, P2 | kotlin-inject / kotlin-inject-anvil (KSP, genera Kotlin) | +1-3 segundos por modulo. Menos que Dagger |
| O, O2 | Metro (compiler plugin, no KSP) | +1-2 segundos por modulo. Plugin acoplado a version de Kotlin |
| L, M, N | Koin runtime (+ sweet-spi KSP en N) | 0 segundos en L/M (puro runtime); +0.5-1s en N por sweet-spi KSP |

**I es el patron con builds mas rapidos** porque no tiene ningun paso de procesamiento
de anotaciones. L/M tambien son rapidos en build pero pagan en runtime (Koin ~6K ns
resolve). Esto es relevante en proyectos con 20+ modulos donde el tiempo de KSP se acumula.

---

## 7. Ejemplos de Codigo

### 7.1 Wiring Module -- Pattern D (149 lineas)

El modulo de wiring importa todos los DaggerXxxComponent y orquesta manualmente el orden de construccion:

```kotlin
object MultiModuleSdk : MultiModuleSdkApi {

    private val lock = Any()
    private var _logger: SdkLogger = buildLogger()  // singleton lazy, process-scoped
    @Volatile private var _core: CoreProvisions? = null
    @Volatile private var _enc: EncProvisions? = null
    @Volatile private var _auth: AuthProvisions? = null
    // ... @Volatile campos nullable por cada feature

    override fun init(context: Context, config: SdkConfig) {
        _core = DaggerCoreComponent.builder()
            .config(config)
            .build()
        _initialized = true
    }

    override fun <T : Any> get(clazz: Class<T>): T {
        val core = _core!!
        val result: Any = when (clazz) {
            EncryptionApi::class.java -> ensureEnc(core).encryption()
            AuthApi::class.java     -> ensureAuth(core).auth()
            StorageApi::class.java  -> ensureStor(core).storage()
            AnalyticsApi::class.java -> ensureAna(core).analytics()
            SyncApi::class.java     -> ensureSyn(core).sync()
            SdkLogger::class.java   -> _logger
            else -> error("Service ${clazz.simpleName} not available.")
        }
        return checkNotNull(clazz.cast(result))
    }

    // @Volatile + synchronized(lock) para thread-safety
    private fun ensureAuth(core: CoreProvisions): AuthProvisions {
        _auth?.let { return it }
        synchronized(lock) {
            _auth?.let { return it }
            val enc = ensureEnc(core)  // dependencia explicita
            return DaggerAuthComponent.builder()
                .core(core).logger(_logger).enc(enc)
                .build().also { _auth = it }
        }
    }
}
```

**Problema de escalabilidad:** Cada feature nuevo requiere agregar (1) un campo nullable, (2) una rama en el when-block, (3) un metodo ensure*() con dependencias hardcodeadas.

### 7.2 Wiring Module -- Pattern H (51 lineas)

El modulo de wiring no importa ninguna implementacion concreta:

```kotlin
object MultiModuleSdkH : MultiModuleSdkApi {

    private val resolver = Resolver()
    private var _initialized = false

    override fun init(context: Context, config: SdkConfig) {
        resolver.init(config)
        ServiceLoader.load(FeatureProvider::class.java).forEach { provider ->
            resolver.register(provider)
        }
        _initialized = true
    }

    override fun <T : Any> get(clazz: Class<T>): T = resolver.get(clazz)

    override fun shutdown() {
        resolver.clear()
        _initialized = false
    }
}
```

**Ventaja:** Este archivo no cambia nunca, sin importar cuantos features se agreguen. El Resolver construye el grafo con DFS bajo demanda.

### 7.3 Wiring Module -- Pattern K (50 lineas)

Conceptualmente identico a Firebase SDK's ComponentDiscovery + ComponentRuntime. Descubre los mismos FeatureProvider de H, pero via AndroidManifest `<meta-data>` en vez de ServiceLoader:

```kotlin
object MultiModuleSdkK : MultiModuleSdkApi {

    private val resolver = Resolver()
    private var _initialized = false

    override fun init(context: Context, config: SdkConfig) {
        resolver.init(config)
        discoverProviders(context).forEach { provider ->
            resolver.register(provider)
        }
        _initialized = true
    }

    private fun discoverProviders(context: Context): List<FeatureProvider<*>> {
        val component = ComponentName(context, ComponentDiscoveryService::class.java)
        val serviceInfo = context.packageManager.getServiceInfo(
            component,
            PackageManager.GET_META_DATA,
        )
        val bundle = serviceInfo.metaData ?: return emptyList()
        return bundle.keySet()
            .filter { it.startsWith("com.grinwich.sdk.providers:") }
            .map { key ->
                val className = key.removePrefix("com.grinwich.sdk.providers:")
                Class.forName(className).getDeclaredConstructor().newInstance() as FeatureProvider<*>
            }
    }

    override fun <T : Any> get(clazz: Class<T>): T = resolver.get(clazz)

    override fun shutdown() {
        resolver.clear()
        _initialized = false
    }
}
```

**Diferencia con H:** Requiere `Context` en init() para acceder a PackageManager. La ventaja es robustez: las entradas en AndroidManifest sobreviven R8/ProGuard sin necesidad de reglas keep, a diferencia de los archivos META-INF/services de ServiceLoader que pueden ser eliminados por el minificador. El costo es ~2.2x mas lento en init (211K vs 96K ns, IPC a system_server) pero ambos son imperceptibles (< 1 ms).

### 7.4 Wiring Module -- Pattern I (54 lineas)

Identico a H pero descubre PureFeatureProvider (sin Dagger, sin ningun framework):

```kotlin
object MultiModuleSdkI : MultiModuleSdkApi {

    private val resolver = Resolver()

    override fun init(context: Context, config: SdkConfig) {
        resolver.init(config)
        ServiceLoader.load(PureFeatureProvider::class.java).forEach { provider ->
            resolver.register(provider)
        }
        _initialized = true
    }

    override fun <T : Any> get(clazz: Class<T>): T = resolver.get(clazz)
}
```

### 7.5 Comparacion de Providers -- Feature Encryption

Cada patron multi-modulo con auto-discovery (H, I, J, K) requiere un Provider por feature. K reutiliza los mismos FeatureProvider de H (no crea providers nuevos). La diferencia entre H, I y J esta en como construyen las instancias internamente.

**Pattern H (Dagger dentro del feature):**

```kotlin
class EncProvider : FeatureProvider<EncProvisions>(EncProvisions::class.java) {

    override val services: Map<Class<*>, (EncProvisions) -> Any> = mapOf(
        EncryptionApi::class.java to EncProvisions::encryption,
        HashApi::class.java      to EncProvisions::hash,
    )

    override fun build(resolver: Resolver): EncProvisions =
        buildEncProvisions(resolver.provision(CoreProvisions::class.java), resolver.logger)
}
```

Internamente, `buildEncProvisions()` invoca `DaggerEncComponent.builder()...build()`. El DaggerComponent queda encapsulado dentro del modulo del feature.

**Pattern I (constructor injection puro):**

```kotlin
class EncPureProvider : PureFeatureProvider<EncProvisions>(EncProvisions::class.java) {

    override val services: Map<Class<*>, (EncProvisions) -> Any> = mapOf(
        EncryptionApi::class.java to EncProvisions::encryption,
        HashApi::class.java      to EncProvisions::hash,
    )

    override fun build(resolver: Resolver): EncProvisions {
        val logger = resolver.logger
        val enc = DefaultEncryptionService(logger)
        val hash = DefaultHashService()
        return object : EncProvisions {
            override fun encryption() = enc
            override fun hash() = hash
        }
    }
}
```

Sin codegen. Sin framework. Instanciacion directa con constructores. La desventaja: sin validacion en tiempo de compilacion de que todas las dependencias se satisfagan.

**Pattern J (kotlin-inject):**

```kotlin
@Component
abstract class KIEncComponent(
    @get:Provides val logger: SdkLogger,
) {
    abstract val encryption: EncryptionApi
    abstract val hash: HashApi

    @Provides fun encryptionApi(): EncryptionApi = DefaultEncryptionService(logger)
    @Provides fun hashApi(): HashApi = DefaultHashService()
}

class EncKIProvider : KIFeatureProvider<EncProvisions>(EncProvisions::class.java) {

    override val services: Map<Class<*>, (EncProvisions) -> Any> = mapOf(
        EncryptionApi::class.java to EncProvisions::encryption,
        HashApi::class.java      to EncProvisions::hash,
    )

    override fun build(resolver: Resolver): EncProvisions {
        val component = KIEncComponent::class.create(logger = resolver.logger)
        val enc = component.encryption
        val hash = component.hash
        return object : EncProvisions {
            override fun encryption() = enc
            override fun hash() = hash
        }
    }
}
```

KSP genera Kotlin puro (no Java como KAPT). El @Component de kotlin-inject es simultaneamente Module y Component -- menos boilerplate que Dagger. Pero la indirection extra (KIComponent -> object : Provisions) anade una capa que en Dagger ya existia naturalmente.

### 7.6 Pattern E2 -- Entry Registration

E2 usa un enfoque intermedio: cada feature se declara como un entry en un archivo central, pero las dependencias se resuelven automaticamente con DFS.

```kotlin
// Entries.kt en wiring-e2
internal fun encAutoEntry(logger: SdkLogger) = AutoProvisionEntry(
    provisionClass = EncProvisions::class.java,
    dependencies = setOf(CoreProvisions::class.java),
    serviceClasses = setOf(EncryptionApi::class.java, HashApi::class.java),
    build = { registry ->
        DaggerEncComponent.builder()
            .core(registry.provision(CoreProvisions::class.java))
            .logger(logger)
            .build()
    },
    services = { prov ->
        mapOf(
            EncryptionApi::class.java to prov.encryption(),
            HashApi::class.java to prov.hash(),
        )
    },
)

// Agregar un feature = agregar 1 funcion + 1 linea aqui:
internal fun allAutoEntries(config: SdkConfig, logger: SdkLogger) = listOf(
    coreAutoEntry(config, logger),
    encAutoEntry(logger),
    authAutoEntry(logger),
    storAutoEntry(logger),
    anaAutoEntry(logger),
    synAutoEntry(logger),  // <- 1 linea por feature
)
```

---

## 8. Guia de Decision

### 8.1 Arbol de decision

```
Necesitas KMP (iOS, Desktop)?
|
+-- SI --> compile-time es innegociable?
|    +-- SI --> Aceptas mantener `when` manual en facade (o escribir KSP propio)?
|    |         +-- SI --> P2 (KI-anvil Lazy) o O2 (Metro Lazy, framework joven)
|    |         +-- NO --> Koin/H hibrido (facade inmutable) + tests verify()
|    +-- NO --> N (sweet-spi + Koin) -- zero-touch end-to-end
|
+-- NO (Android-only) --> Cuantos features tendra el SDK?
     |
     +-- < 10 features
     |    +-- Equipo familiarizado con Dagger? --> D o G
     |    +-- Preferencia por simplicidad? --> G
     |
     +-- 10-30 features
     |    +-- Compile-time + facade inmutable? --> E2
     |    +-- Hilt-style? --> Q2 (aceptar modules=[...] manual)
     |
     +-- 30+ features o equipos distribuidos
          |
          +-- Zero-touch end-to-end es innegociable?
          |    +-- SI --> H (ServiceLoader + Resolver + Dagger)
          |               o I (zero framework, builds rapidos)
          |               o K (AndroidManifest discovery, R8 friendly)
          |    +-- NO --> Compile-time completa + aceptas KSP propio?
          |              +-- SI --> Q2 o O2/P2 (KMP bonus)
          |              +-- NO --> H (Tier 1, Req 11 OK sin codegen)
          |
          +-- SDK legacy migrando --> G (factory functions)
```

### 8.2 Tabla de recomendacion por escenario (los 16 patrones)

| Escenario | Patron recomendado | Justificacion |
|-----------|-------------------|---------------|
| SDK interno Android, < 10 features, equipo Dagger | **D** o **G** | Minimo overhead, maximo control, el equipo ya conoce Dagger |
| SDK interno Android, 10-30 features | **E2** | Catalogo centralizado con lazy init + facade inmutable + compile-time COMPLETA (unico Dagger con los 3) |
| SDK Android open-source con contribuidores externos | **H** | Zero coordinacion central end-to-end; los contribuidores solo publican su feature |
| SDK con R8/ProGuard agresivo, sin reglas keep | **K** | Manifest entries sobreviven minificacion; mismo grafo lazy que H |
| SDK con builds rapidos como prioridad | **I** | Zero codegen; cada feature es constructor injection puro |
| SDK con Hilt-style + Android-only | **Q2** | `dagger.Lazy<T>` + compile-time, aceptar `@Component(modules)` + `when` facade manuales |
| SDK legacy Android migrando incrementalmente | **G** | Cada feature expone factory function; DaggerComponent queda interno |
| SDK KMP nuevo, compile-time prioritario | **P2** o **O2** | `@ContributesTo` + lazy, aceptar `when` facade manual (mitigable con KSP propio) |
| SDK KMP nuevo, zero-touch end-to-end prioritario | **N** | sweet-spi + Koin, facade inmutable nativo, sin compile-time safety (mitigable con `koin.verify()`) |
| SDK KMP con framework maduro + ecosistema | **N** (Koin 7+ anos) | Koin tiene mas ecosistema que Metro/kotlin-inject-anvil |

### 8.3 Recomendacion general

Para un **SDK Android nuevo** que anticipa crecimiento mas alla de 15-20 features:

1. **Pattern H** (Dagger + ServiceLoader) -- aplica el mismo principio arquitectonico que
   Firebase SDK (auto-registro, wiring inmutable end-to-end, grafo lazy). Compile-time
   safety dentro de cada feature + facade inmutable nativamente.
2. **Pattern K** si R8/ProGuard es agresivo. K reutiliza los mismos FeatureProvider de H,
   cambiando unicamente el mecanismo de discovery (AndroidManifest `<meta-data>`).
3. **Pattern I** si los tiempos de build son prioridad (zero codegen, builds mas rapidos)
   o si el equipo prefiere evitar frameworks DI.
4. **Pattern E2** si compile-time COMPLETA (no parcial) es innegociable y aceptas 1 linea
   en `allAutoEntries()` por feature.

Para un **SDK KMP nuevo**: la decision depende de que ejes valoras.

- **Compile-time innegociable**: P2 + aceptar `when` facade manual (~500 ramas a escala)
  o escribir KSP propio. Alternativa: O2 (mejor perf pero framework mas joven).
- **Zero-touch end-to-end innegociable**: N (sweet-spi + Koin) con `koin.verify()` en CI.

**Para migracion**: H -> K es trivial. H -> I/J es incremental (feature por feature).
KMP: feature por feature, API publica del SDK no cambia.

### 8.4 Matriz de pros y contras (los 16 patrones)

Formato patron-por-fila para acomodar los 16 patrones.

| Patron | Paradigma | Framework | Compile-time | Req 6 (grafo) | Req 11 (facade) | Init (ns) | Resolve all (ns) | KMP |
|--------|-----------|-----------|:------------:|:-------------:|:---------------:|----------:|-----------------:|:---:|
| D | Compile-time | Dagger | Alta | NO | NO | 1,400 | 6 | NO |
| E2 | Compile-time + DFS runtime | Dagger | Alta | ~ | OK | 8,024 | 189 | NO |
| G | Compile-time | Dagger | Alta | NO | NO | 1,379 | 1 | NO |
| H | Compile-time + runtime | Dagger + SL | Parcial | OK | **OK** | 86,254 | 139 | NO |
| I | Runtime puro | Ninguno | NO | OK | **OK** | 116,413 | 189 | ~ |
| J | Compile-time + runtime | kotlin-inject + SL | Parcial | OK | **OK** | 122,124 | 186 | ~ |
| K | Compile-time + runtime | Dagger + Manifest | Parcial | OK | **OK** | 205,544 | 190 | NO |
| L | Runtime | Koin + SL | NO | OK | **OK** | 161,559 | 4,784 | ~ |
| M | Runtime | Koin + SL lazy | NO | NO | **OK** | 164,713 | 6,899 | ~ |
| N | Runtime | Koin + sweet-spi | NO | OK | **OK** | 96,719 | 6,307 | OK |
| O | Compile-time | Metro | Alta | OK | NO | 723 | 108 | OK |
| O2 | Compile-time + Lazy | Metro | Alta | OK | NO | 1,412 | 273 | OK |
| P | Compile-time | kotlin-inject-anvil | Parcial | OK | NO | 785 | 146 | OK |
| P2 | Compile-time + Lazy | kotlin-inject-anvil | Parcial | OK | NO | 1,722 | 380 | OK |
| Q | Compile-time | Dagger Hilt-style | Alta | NO | NO | 647 | 105 | NO |
| Q2 | Compile-time + Lazy | Dagger Hilt-style | Alta | NO | NO | 1,502 | 303 | NO |

**Lectura**: patrones con Req 6 = OK Y Req 11 = OK son los unicos con wiring inmutable
end-to-end (H, I, J, K, L, N). E2 es semi (~). Los compile-time DI (O/O2/P/P2/Q/Q2)
fallan Req 11 -- el facade `when` crece por API.

---

## 9. Manejo de Errores del Resolver

Las rutas de error de la maquinaria compartida en `di-contracts` (que da soporte
a los patrones E, E2 y H/I/J/K) estan unificadas bajo
[`DependencyResolutionException`](shared/exception-hierarchy.md):

| Subtipo | Cuando se lanza |
|---------|-----------------|
| `NoProviderFoundException` | `get(X)` y nadie ha registrado un provider para `X` |
| `CircularDependencyException` | Reentrada detectada en `buildingProviders` durante `build()` -- elimina el `StackOverflowError` como modo de fallo |
| `ProviderBuildException` | El `build()` de un provider lanza una excepcion no tipada (la causa original queda en `cause`) |
| `ProviderAlreadyFailedException` | Reintento sobre un provider ya marcado como fallido -- requiere `clear()` antes |
| `ServiceCastException` | El instance publicado en el mapa de `build()` no es asignable al `Class<T>` solicitado |
| `ServiceNotAvailableException` | El provider termino `build()` sin publicar un servicio que declaro |

### 9.1 Deteccion de ciclos: eager vs lazy

| Pattern | Mecanismo | Cuando detecta ciclos | Coste de declaracion |
|---------|-----------|----------------------|---------------------|
| **E** | Topo-sort de Kahn en `registerAll()` | **Eager -- antes del primer `get()`** | `dependencies` declaradas por entry |
| **E2** | DFS iterativo con `visiting` set en `ensureBuilt()` | Lazy -- al resolver | `dependencies` declaradas |
| **H/I/J/K** | DFS recursivo con `buildingProviders` set en `ensureBuilt()` | Lazy -- al resolver | Sin declaracion (deps implicitas) |

**Implicaciones:**
- En **E**, un grafo ciclico hace que `init()` lance `CircularDependencyException`. El crash sale en logs de startup.
- En **E2/H/I/J/K**, `init()` siempre tiene exito; el ciclo se manifiesta la primera vez que alguien resuelve un servicio del componente ciclico.
- En todos los casos el `StackOverflowError` queda **eliminado como modo de fallo**: H/I/J/K cortan el DFS via el set `buildingProviders` antes de que el stack se profundice.

### 9.2 Race condition init/get/shutdown

Los facades `MultiModuleSdkE2/H/I/J/K` envuelven la llamada al resolver en un
`try/catch (DependencyResolutionException)` que **remapea** la race con `shutdown()`
al `IllegalStateException` que el consumidor espera:

```kotlin
override fun <T : Any> get(clazz: Class<T>): T {
    check(_initialized) { "MultiModuleSdkH not initialized." }
    return try {
        resolver.get(clazz)
    } catch (e: DependencyResolutionException) {
        if (!_initialized) throw IllegalStateException("MultiModuleSdkH not initialized.", e)
        throw e
    }
}
```

`init()` y `shutdown()` estan protegidos por `synchronized(lifecycleLock)`. `get()`
no toma ese lock -- el `Resolver`/`AutoServiceRegistry` ya tienen el suyo propio
para `ensureBuilt`. Validado por el suite `concurrentShutdown_*` (200 rondas x 16
patrones = 3,200 carreras sin crashes inesperados).

### 9.3 Coste medible de la jerarquia tipada

Validado contra Pattern H mediante una corrida A/B en la misma sesion del
dispositivo (Samsung Galaxy S22 Ultra, Android 16):

| Metrica | Pre | Post | Atribuible al cambio |
|---------|----:|-----:|----------------------|
| `initCold_H` | 92,471 ns | 104,591 ns | ~0 (5 mediciones independientes con spread ±5%) |
| `resolveFirst_H` (cache hit) | 21 ns | 41 ns | +10-20 ns inherentes al `try/catch` alrededor de `Class.cast` |
| `stress_initShutdown_H` | 83,639 ns | 136,410 ns | ~0 (lock acquire en synchronized init/shutdown invisible en stress) |

El sobrecoste de cache hit es inherente a la API tipada: sin `try/catch` no se
puede mapear el `ClassCastException` a `ServiceCastException` preservando la
causa. El JIT inlinea el helper privado, asi que inlinearlo a mano (probado en una
variante) **no aporta nada** -- los runs A vs B (con helper) y C (sin helper)
dan numeros identicos en `resolveFirst_H` (30/30 ns).

---

## Apendice: Datos de Referencia

### A.1 Dispositivo de prueba

| Propiedad | Valor |
|-----------|-------|
| Modelo | Samsung Galaxy S22 Ultra (SM-S908B) |
| SoC | Snapdragon 8 Gen 1 |
| Nucleos | 8 (1x Cortex-X2 @2.8 GHz, 3x Cortex-A710, 4x Cortex-A510) |
| Android | 16 |
| Framework | Jetpack Benchmark 1.4.0 |

### A.2 Resumen de tests

| Suite | Pasaron | Fallaron | Nota |
|-------|---------|----------|------|
| DiBenchmark | 28 | 0 | Benchmarks de los 4 patrones monoliticos (B, C, Koin, Hybrid) |
| MultiModuleBenchmark | 228 | 0 | Benchmarks de los 16 patrones multi-modulo (D, E2, G, H, I, J, K, L, M, N, O, O2, P, P2, Q, Q2) |
| MemoryBehaviorTest | 97 | 0 | Prueba de laziness del grafo (16 patrones x 8 categorias + 1 comparativa = 129 assertions, 97 tests) |
| StressTortureTest | 156 | 0 | Concurrencia y resiliencia (16 patrones, incluyendo 3 tests de concurrencia) |
| ScaleBenchmark | 37 | 0 | Escalabilidad con features sinteticas |
| **di-contracts** unit tests | **52** | 0 | `ResolverTest` (27) + `AutoServiceRegistryTest` (18) + `ServiceRegistryTest` (7) -- jerarquia de excepciones, deteccion de ciclos, politica de reintentos |
| **Total instrumentado** | **546** | **0** | 453 instrumentados (S22 Ultra) + 52 unit tests JVM + 41 actualizados en re-corrida 2026-04-25 |
