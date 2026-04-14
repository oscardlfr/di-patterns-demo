# Reporte Tecnico: Patrones Multi-Modulo de Inyeccion de Dependencias para SDKs Android

**Proyecto:** di-patterns-demo
**Fecha:** 2026-04-10
**Dispositivo:** Samsung Galaxy S22 Ultra (SM-S908B) -- Snapdragon 8 Gen 1, 8 nucleos, 2.8 GHz, Android 16
**Framework de medicion:** Jetpack Benchmark 1.4.0 con warmup automatico
**Total de tests:** 453 pasaron, 0 fallaron

---

## 1. Resumen Ejecutivo

Este reporte analiza 16 patrones multi-modulo de inyeccion de dependencias implementados en un SDK Android con 6 features (Core, Encryption, Auth, Storage, Analytics, Sync). Cada feature reside en su propio modulo Gradle (`features/feature-xxx-impl`) y las dependencias entre features se expresan a traves de contratos Kotlin puros en `di-contracts` (CoreProvisions, EncProvisions, etc.). Solo el modulo de wiring conoce las implementaciones concretas.

Los 16 patrones se organizan en 3 categorias: **Android-only** (D, E2, G, H, I, K, Q, Q2), **KMP-compatible** (N, O, O2, P, P2) y **Partial KMP** (J, L, M). Todos fueron instrumentados con Jetpack Benchmark en un Samsung Galaxy S22 Ultra y sometidos a pruebas de estres, concurrencia, comportamiento de memoria y escalabilidad.

### Hallazgo principal

**La diferencia de rendimiento entre los 16 patrones es imperceptible para el usuario.** El init mas lento (Patron K, 213,737 ns) tarda 0.21 milisegundos -- tres ordenes de magnitud por debajo del umbral perceptible de 16,666,666 ns (un frame a 60 fps). La eleccion entre patrones es **arquitectonica**, no de rendimiento.

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
| **O** (Metro eager) | KMP | SDK KMP nuevo con init mas rapido (603 ns), compile-time. Eager. Framework joven |
| **O2** (Metro Lazy) | KMP | = O + singletons lazy. Re-init rapido. Facade `when` manual (mitigable con KSP) |
| **P** (kotlin-inject-anvil eager) | KMP | = O con KSP estandar + Amazon maintainer. Eager |
| **P2** (kotlin-inject-anvil Lazy) | KMP | = P + lazy. Mejor balance KMP con compile-time completa |
| **Q** (Hilt-style Dagger eager) | Android-only | Hilt familiar sin @HiltAndroidApp. Doble edicion central (modules + when) |
| **Q2** (Hilt-style Dagger Lazy) | Android-only | = Q + dagger.Lazy. Re-init mas rapido de todos (2,157 ns). Doble edicion central |

### Cuatro conclusiones clave

1. **Rendimiento no es diferenciador.** Todos los patrones resuelven servicios en nanosegundos y completan init + resolve + primera operacion en menos de 882,041 ns (el aumento respecto a ejecuciones anteriores se debe a la migracion de Storage a DataStore con I/O real a disco).
2. **Escalabilidad tiene dos ejes, no uno.** El primer eje es el grafo (¿agregar feature requiere editar wiring?). El segundo eje es el facade (¿agregar API requiere editar el dispatcher `get<T>(Class)`?). Los patrones D y G fallan ambos. Los patrones compile-time DI O/O2/P/P2/Q/Q2 cumplen el primero pero fallan el segundo (el `when (clazz)` del facade crece por API). Solo H/I/J/K/L/M/N cumplen ambos nativamente. Ver `docs/shared/requirements.md` Req 6 + Req 11.
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

| Patron | initCold | resolveFirst | resolveAll | lazyNoDeps | lazyCascade | crossFeature | e2eStartup | initShutdown | reInit | concurrent |
|--------|---------:|-------------:|-----------:|-----------:|------------:|-------------:|-----------:|-------------:|-------:|-----------:|
| **D** (when-block) | 1,212 | 346 | 100 | 255 | 696 | 1.9M | 1.2M | 248 | 36K | 493K |
| **E2** (Registry DFS) | 10,983 | 199 | 211 | 1,049 | 3,088 | 2.1M | 1.4M | 4,418 | 17K | 571K |
| **G** (Factory fns) | 1,257 | 345 | 101 | 260 | 848 | 2.0M | 1.4M | 229 | 38K | 596K |
| **H** (Resolver+Dagger) | 106,865 | 202 | 212 | 1,278 | 3,892 | 1.3M | 1.7M | 99,293 | 363K | 515K |
| **I** (Pure Resolver) | 94,255 | 203 | 211 | 1,112 | 4,122 | 1.5M | 1.7M | 103,695 | 427K | 608K |
| **J** (kotlin-inject) | 97,197 | 202 | 213 | 1,493 | 4,866 | 1.5M | 1.7M | 93,732 | 371K | 439K |
| **K** (Manifest) | 213,737 | 203 | 213 | 2,996 | 7,900 | 2.0M | 2.3M | 201,490 | 767K | 554K |
| **L** (Koin+SL eager) | 154,403 | 5,664 | 6,244 | 5,473 | 24,611 | n/d | n/d | n/d | 1.1M | n/d |
| **M** (Koin+SL lazy) | 164,353 | 6,160 | 7,920 | 13,784 | 48,334 | n/d | n/d | n/d | 1.2M | n/d |
| **N** (sweet-spi+Koin) | 69,636 | 5,855 | 6,328 | 20,018 | 22,706 | 1.8M | 2.0M | 42,293 | 732K | 784K |
| **O** (Metro eager) | 603 | 288 | 80 | 2,098 | 346 | 1.7M | 1.2M | 301 | 36K | 586K |
| **O2** (Metro Lazy) | 1,127 | 315 | 86 | 238 | 507 | 1.8M | 1.5M | 516 | 2,305 | 587K |
| **P** (KI-anvil eager) | 1,064 | 336 | 165 | 1,941 | 607 | 1.7M | 1.4M | 293 | 28K | 618K |
| **P2** (KI-anvil Lazy) | 1,416 | 335 | 156 | 284 | 734 | 3.1M | 993K | 508 | 2,929 | 638K |
| **Q** (Hilt eager) | 676 | 257 | 64 | 1,735 | 318 | 1.6M | 950K | 403 | 25K | 591K |
| **Q2** (Hilt Lazy) | 1,080 | 306 | 85 | 236 | 504 | 1.7M | 1.3M | 549 | 2,157 | 586K |

**Leyenda:** `K = 1,000 ns`, `M = 1,000,000 ns`. **n/d** = no disponible (L/M solo se midieron para un subconjunto de metricas en `docs/multimodule/partial-kmp/patterns-overview.md`).

**Fuentes de datos:**
- D, E2, G, H, I, K, Q, Q2: `docs/multimodule/android/benchmark-results.md`
- N, O, O2, P, P2: `docs/multimodule/kmp/benchmark-results.md`
- J, L, M: `docs/multimodule/partial-kmp/patterns-overview.md` (subset)
- J: completa tambien en el conjunto Android por ser compatible JVM

**Ganadores por metrica:**

| Metrica | 1ro | 2do | 3ro |
|---------|-----|-----|-----|
| initCold (grafo mas rapido) | O (603) | Q (676) | O2/Q2 (1,080-1,127) |
| resolveFirst (cache warmup) | E2 (199) | J/H/I/K (~202) | Q (257) |
| resolveAll (todos cached) | Q (64) | O/O2 (80-86) | Q2 (85) |
| lazyNoDeps (feature sin deps) | Q2 (236) | O2 (238) | D (255) |
| lazyCascade (Sync chain) | Q (318) | O (346) | Q2 (504) |
| crossFeature (op real DataStore) | H (1.3M) | I (1.5M) | J (1.5M) |
| e2eStartup (fin a fin) | Q (950K) | P2 (993K) | D/O (1.2M) |
| reInit (hot restart) | **Q2 (2,157)** | O2 (2,305) | P2 (2,929) |
| initShutdown (ciclo vacio) | G (229) | D (248) | P (293) |
| concurrent (contention) | J (439K) | D (493K) | H (515K) |

**Lectura rapida:** Q/Q2 y O/O2 dominan runtime pura. E2 es el mejor Dagger pattern en resolveFirst. H/I/J/K pagan el coste del ServiceLoader/Manifest en init pero resuelven en cache con paridad. N/L/M pagan el coste de Koin runtime pero cumplen Req 11 (facade inmutable) nativamente.

### 3.2 Analisis por categoria

Las siguientes subtablas ordenan los 16 patrones multi-modulo (16 en total excepto
cuando faltan datos para L/M -- en cuyo caso aparecen como n/d). Los mismos numeros
de la tabla 3.1, reordenados por metrica.

#### Init Cold -- Construccion del grafo inicial (ranking)

| # | Patron | Tiempo (ns) | Mecanismo |
|--:|--------|------------:|-----------|
| 1 | O | 603 | Metro compiler plugin: asignacion directa de campos |
| 2 | Q | 676 | Dagger @Component Hilt-style: grafo compile-time |
| 3 | Q2 | 1,080 | = Q + dagger.Lazy wrappers |
| 4 | P | 1,064 | kotlin-inject-anvil: KSP @MergeComponent |
| 5 | O2 | 1,127 | = O + LazyCreationTracker overhead |
| 6 | D | 1,212 | Asignacion directa de campos (Dagger codegen) |
| 7 | G | 1,257 | = D con factory functions |
| 8 | P2 | 1,416 | = P + LazyCreationTracker overhead |
| 9 | E2 | 10,983 | Cataloga entries en HashMaps (no construye nada) |
| 10 | N | 69,636 | sweet-spi discovery + Koin registration |
| 11 | I | 94,255 | ServiceLoader + PureFeatureProvider |
| 12 | J | 97,197 | ServiceLoader + KIFeatureProvider |
| 13 | H | 106,865 | ServiceLoader + FeatureProvider + Dagger |
| 14 | L | 154,403 | ServiceLoader + Koin eager |
| 15 | M | 164,353 | ServiceLoader + Koin lazy loadModules |
| 16 | K | 213,737 | PackageManager.getServiceInfo() IPC |

**Analisis:** los patrones compile-time (O/Q/P/Q2/O2/P/D/G/P2) dominan por debajo de
1,500 ns porque el grafo se resuelve estaticamente. E2 esta en el medio (10,983 ns):
cataloga sin construir. N paga el coste de sweet-spi + Koin (70K ns). H/I/J/L pagan
ServiceLoader (~95-154K ns). M paga el overhead extra de lazy loadModules (~164K ns).
K es el mas lento por el IPC al system_server (~214K ns, 2.2x vs H).

Incluso el patron mas lento (K) es despreciable en el arranque de una app Android
(tipicamente 500,000,000 - 2,000,000,000 ns).

#### Resolve First -- Primera resolucion tras init (ranking)

| # | Patron | Tiempo (ns) |
|--:|--------|------------:|
| 1 | E2 | 199 |
| 2 | H | 202 |
| 3 | J | 202 |
| 4 | I | 203 |
| 5 | K | 203 |
| 6 | Q | 257 |
| 7 | O | 288 |
| 8 | Q2 | 306 |
| 9 | O2 | 315 |
| 10 | P2 | 335 |
| 11 | P | 336 |
| 12 | G | 345 |
| 13 | D | 346 |
| 14 | N | 5,855 |
| 15 | L | 5,664 |
| 16 | M | 6,160 |

**Analisis:** E2/H/I/J/K/E2 resuelven en ~200 ns via ConcurrentHashMap lookup.
Q/Q2/O/O2/P/P2 resuelven en ~257-336 ns (acceso directo al campo del componente generado
despues del `when`). D/G en ~345 ns (volatile fields). N/L/M pagan ~5,600-6,200 ns por
el lookup runtime de Koin sobre KClass.

#### Resolve All (cached) -- Ranking

| # | Patron | Tiempo (ns) |
|--:|--------|------------:|
| 1 | Q | 64 |
| 2 | O | 80 |
| 3 | Q2 | 85 |
| 4 | O2 | 86 |
| 5 | D | 100 |
| 6 | G | 101 |
| 7 | P2 | 156 |
| 8 | P | 165 |
| 9 | E2 | 211 |
| 10 | I | 211 |
| 11 | H | 212 |
| 12 | J | 213 |
| 13 | K | 213 |
| 14 | L | 6,244 |
| 15 | N | 6,328 |
| 16 | M | 7,920 |

**Analisis:** cache warm, todos los patrones compile-time sirven desde campos directos
(Q en 64 ns es imbatible). Los patrones resolver-based sirven desde HashMap (~211 ns).
Los Koin pagan el coste de su runtime definition registry (~6-8K ns). Para un SDK que
haga ~1,000 get<T>()/segundo: Q = 64us/s, H = 212us/s, N = 6.3ms/s. Solo Koin es
problematico en hot loops.

#### Lazy Init -- Construccion bajo demanda

**Sin dependencias (Analytics -- depende solo de Core):**

| # | Patron | Tiempo (ns) |
|--:|--------|------------:|
| 1 | Q2 | 236 |
| 2 | O2 | 238 |
| 3 | D | 255 |
| 4 | G | 260 |
| 5 | P2 | 284 |
| 6 | E2 | 1,049 |
| 7 | I | 1,112 |
| 8 | H | 1,278 |
| 9 | J | 1,493 |
| 10 | Q | 1,735 |
| 11 | P | 1,941 |
| 12 | O | 2,098 |
| 13 | K | 2,996 |
| 14 | L | 5,473 |
| 15 | M | 13,784 |
| 16 | N | 20,018 |

**Con cascada (Sync -- depende de Core + Enc + Auth + Stor):**

| # | Patron | Tiempo (ns) |
|--:|--------|------------:|
| 1 | Q | 318 |
| 2 | O | 346 |
| 3 | Q2 | 504 |
| 4 | O2 | 507 |
| 5 | P | 607 |
| 6 | D | 666 |
| 7 | P2 | 734 |
| 8 | G | 848 |
| 9 | E2 | 3,088 |
| 10 | H | 3,892 |
| 11 | I | 4,122 |
| 12 | J | 4,866 |
| 13 | K | 7,900 |
| 14 | N | 22,706 |
| 15 | L | 24,611 |
| 16 | M | 48,334 |

**Analisis:** la cascada Sync es el escenario mas exigente: construir 4 provisions en
cadena de 4 niveles. Los compile-time (Q/O/Q2/O2/P/D/P2/G) dominan por debajo de 850 ns
porque el compilador resuelve el orden estaticamente. E2/H/I/J/K pagan el costo del
DFS en runtime (el Resolver recorre el grafo dinamicamente). N/L/M pagan el coste de
Koin recursivo. M es 8x mas lento que la peor alternativa compile-time debido a
`loadModules()` en cascada.

#### Cross-Feature Operation -- Operacion real cruzando features (ranking)

| # | Patron | Tiempo (ns) |
|--:|--------|------------:|
| 1 | H | 1,300,000 |
| 2 | I | 1,500,000 |
| 3 | J | 1,500,000 |
| 4 | Q | 1,600,000 |
| 5 | O | 1,700,000 |
| 6 | P | 1,700,000 |
| 7 | Q2 | 1,700,000 |
| 8 | N | 1,800,000 |
| 9 | O2 | 1,800,000 |
| 10 | D | 1,900,000 |
| 11 | G | 2,000,000 |
| 12 | K | 2,000,000 |
| 13 | E2 | 2,100,000 |
| 14 | P2 | 3,100,000 |
| - | L, M | n/d (no medido) |

**Analisis:** los valores estan en el rango ~1.3-3.1M ns porque Storage usa DataStore
(I/O real a disco via suspend + runBlocking). Los tiempos reflejan el coste real de
`sync.sync()` con persistencia a disco. **Una vez resueltos los servicios, el
rendimiento depende del codigo de negocio (incluyendo I/O a disco), no del patron DI.**
La variabilidad entre patrones aqui se debe principalmente al acceso a disco, no al
mecanismo DI. P2 muestra un outlier (3.1M) que probablemente es variabilidad de medicion.

#### E2E App Startup -- Init + resolve all + primera operacion por feature (ranking)

| # | Patron | Tiempo (ns) |
|--:|--------|------------:|
| 1 | Q | 950,000 |
| 2 | P2 | 993,000 |
| 3 | D | 1,200,000 |
| 4 | O | 1,200,000 |
| 5 | Q2 | 1,300,000 |
| 6 | E2 | 1,400,000 |
| 7 | G | 1,400,000 |
| 8 | P | 1,400,000 |
| 9 | O2 | 1,500,000 |
| 10 | I | 1,700,000 |
| 11 | H | 1,700,000 |
| 12 | J | 1,700,000 |
| 13 | N | 2,000,000 |
| 14 | K | 2,300,000 |
| - | L, M | n/d (no medido) |

**Analisis:** incluso el patron mas lento (K = 2.3M ns) completa el arranque del SDK
con 6 features en menos de 2.5 ms. Esto representa ~0.1% de un arranque tipico de app
Android (~2,000,000,000 ns). **Ningun patron es un cuello de botella en el arranque.**

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
| DiBenchmark | 19 | 0 |
| MultiModuleBenchmark | 144 | 0 |
| MemoryBehaviorTest | 97 | 0 |
| StressTortureTest | 156 | 0 |
| ScaleBenchmark | 37 | 0 |
| **Total** | **453** | **0** |

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
| D | 248 | 493K | 100 | 36K | 1,172 |
| E2 | 4,418 | 571K | 211 | 17K | 11,688 |
| G | 229 | 596K | 101 | 38K | 1,223 |
| H | 99,293 | 515K | 212 | 363K | 97,694 |
| I | 103,695 | 608K | 211 | 427K | 100,488 |
| J | 93,732 | 439K | 213 | 371K | 87,604 |
| K | 201,490 | 554K | 213 | 767K | 213,696 |
| N | 42,293 | 784K | 6,328 | 732K | 71,509 |
| O | 301 | 586K | 80 | 36K | 588 |
| O2 | 516 | 587K | 86 | **2,305** | 952 |
| P | 293 | 618K | 165 | 28K | 1,060 |
| P2 | 508 | 638K | 156 | **2,929** | 1,321 |
| Q | 403 | 591K | 64 | 25K | 667 |
| Q2 | 549 | 586K | 85 | **2,157** | 1,218 |
| L | n/d | n/d | 6,244 | 1.1M | n/d |
| M | n/d | n/d | 7,920 | 1.2M | n/d |

`K = 1,000 ns`, `M = 1,000,000 ns`. **Negrita** en reInit marca los 3 ganadores
(lazy compile-time DI: Q2/O2/P2).

### 5.4 Analisis de estres

**Ciclo de vida (initShutdown, reInit, incremental):** Los compile-time DI
(D, G, O, P, Q, Q2, O2, P2) son consistentemente los mas rapidos (229 - 549 ns
initShutdown) porque no invocan discovery en cada ciclo. Q2/O2/P2 dominan reInit
con ~2,100-2,900 ns gracias a sus trackers lazy. H, I y J pagan ~85,000 - 104,000 ns
por ciclo init debido al escaneo de classpath (ServiceLoader). K paga ~201,000 ns por
ciclo init (IPC a system_server). N paga ~42,000 ns (sweet-spi + Koin registry). L/M
son los peor performer en reInit (1.1-1.2M ns) -- Koin + ServiceLoader = doble coste.

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
    private var _logger: SdkLogger = AndroidSdkLogger()
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
| D | Compile-time | Dagger | Alta | NO | NO | 1,212 | 100 | NO |
| E2 | Compile-time + DFS runtime | Dagger | Alta | ~ | OK | 10,983 | 211 | NO |
| G | Compile-time | Dagger | Alta | NO | NO | 1,257 | 101 | NO |
| H | Compile-time + runtime | Dagger + SL | Parcial | OK | **OK** | 106,865 | 212 | NO |
| I | Runtime puro | Ninguno | NO | OK | **OK** | 94,255 | 211 | ~ |
| J | Compile-time + runtime | kotlin-inject + SL | Parcial | OK | **OK** | 97,197 | 213 | ~ |
| K | Compile-time + runtime | Dagger + Manifest | Parcial | OK | **OK** | 213,737 | 213 | NO |
| L | Runtime | Koin + SL | NO | OK | **OK** | 154,403 | 6,244 | ~ |
| M | Runtime | Koin + SL lazy | NO | NO | **OK** | 164,353 | 7,920 | ~ |
| N | Runtime | Koin + sweet-spi | NO | OK | **OK** | 69,636 | 6,328 | OK |
| O | Compile-time | Metro | Alta | OK | NO | 603 | 80 | OK |
| O2 | Compile-time + Lazy | Metro | Alta | OK | NO | 1,127 | 86 | OK |
| P | Compile-time | kotlin-inject-anvil | Parcial | OK | NO | 1,064 | 165 | OK |
| P2 | Compile-time + Lazy | kotlin-inject-anvil | Parcial | OK | NO | 1,416 | 156 | OK |
| Q | Compile-time | Dagger Hilt-style | Alta | NO | NO | 676 | 64 | NO |
| Q2 | Compile-time + Lazy | Dagger Hilt-style | Alta | NO | NO | 1,080 | 85 | NO |

**Lectura**: patrones con Req 6 = OK Y Req 11 = OK son los unicos con wiring inmutable
end-to-end (H, I, J, K, L, N). E2 es semi (~). Los compile-time DI (O/O2/P/P2/Q/Q2)
fallan Req 11 -- el facade `when` crece por API.

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
| DiBenchmark | 19 | 0 | Benchmarks de los 4 patrones monoliticos (B, C, Koin, Hybrid) |
| MultiModuleBenchmark | 144 | 0 | Benchmarks de los 16 patrones multi-modulo (D, E2, G, H, I, J, K, L, M, N, O, O2, P, P2, Q, Q2) |
| MemoryBehaviorTest | 97 | 0 | Prueba de laziness del grafo (16 patrones x 8 categorias + 1 comparativa = 129 assertions, 97 tests) |
| StressTortureTest | 156 | 0 | Concurrencia y resiliencia (16 patrones, incluyendo 3 tests de concurrencia) |
| ScaleBenchmark | 37 | 0 | Escalabilidad con features sinteticas |
| **Total** | **453** | **0** | |
