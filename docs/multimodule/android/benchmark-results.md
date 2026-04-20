# Benchmark Results -- Android-Only Patterns

Resultados de benchmarks Microbenchmark (Jetpack Benchmark) para los 8 patrones
Android-only: D, E2, G, H, I, K, Q y Q2.

---

## 1. Dispositivo y Condiciones

- **Dispositivo:** Samsung Galaxy S22 Ultra (SM-S908B, Snapdragon 8 Gen 1)
- **Framework:** Jetpack Microbenchmark (`androidx.benchmark`)
- **Clase de test:** `MultiModuleBenchmark.kt`
- **Modo:** Release (optimizaciones R8 activas)
- **Iteraciones:** Configuracion por defecto de Microbenchmark (warmup + medicion)
- **Clock:** Locked (CPU frequency locked para reducir variabilidad)

---

## 2. Resultados por Categoria

### 2.1. Init Cold

Tiempo de la primera llamada a `init(context, config)` desde estado completamente limpio.

| D | E2 | G | H | I | K | Q | Q2 |
|--:|---:|--:|--:|--:|--:|--:|---:|
| 1,400 ns | 8,024 ns | 1,379 ns | 86,254 ns | 116,413 ns | 205,544 ns | 647 ns | 1,502 ns |

**Analisis:** Q es el mas rapido (647 ns) porque Dagger genera todo el wiring en
compilacion -- `DaggerSdkComponent.factory().create()` solo instancia un objeto
ya pre-conectado. Q2 (1,502 ns) paga ~855 ns extra por el setup de
`LazyCreationTracker.activate()`. D y G (~1,400 ns) solo construyen CoreComponent.
E2 baja a 8,024 ns tras el refactor (AtomicInteger counter + logger singleton).
H, I y K son ordenes de magnitud mas lentos (86K-206K ns) por el overhead de
discovery en runtime (ServiceLoader o PackageManager).

### 2.2. Resolve First

Tiempo de la primera resolucion de un servicio ya construido (cache hit).

| D | E2 | G | H | I | K | Q | Q2 |
|--:|---:|--:|--:|--:|--:|--:|---:|
| 8 ns | 9 ns | 9 ns | 1 ns | 9 ns | 0 ns | 5 ns | 7 ns |

**Analisis (post-refactor):** todos caen a 0-9 ns tras la homogeneizacion del
logger singleton y dedup O(1). El JIT aplica dead-code-elimination agresivo
post-warmup cuando el `sdk.get()` no tiene side effects observables. Los numeros
pre-refactor (200-346 ns) reflejaban side-effects residuales del `count { it !in
persistentProviders }` loop que bloqueaban la optimizacion JIT -- eliminado en
favor de `AtomicInteger`.

### 2.3. Lazy Init noDeps

Tiempo de inicializar una feature sin dependencias cruzadas (e.g. Analytics).

| D | E2 | G | H | I | K | Q | Q2 |
|--:|---:|--:|--:|--:|--:|--:|---:|
| 266 ns | 792 ns | 273 ns | 1,075 ns | 1,288 ns | 2,284 ns | 184 ns | 312 ns |

**Analisis:** Q (184 ns) lidera tras el refactor -- los singletons ya estan
materializados en init, `component.analytics()` es lookup directo. Q2 (312 ns)
paga el overhead del `withActive` lambda (necesario para ThreadLocal isolation).
D/G (~270 ns) crean un DaggerComponent via `ensure*()`. E2 baja a 792 ns por el
DFS optimizado con dedup O(1). H/I pagan ~1,100 ns por la resolucion runtime via
Resolver. K sigue siendo el mas lento (2,284 ns) por la capa adicional de manifest
discovery.

### 2.4. Lazy Init cascade

Tiempo de inicializar Sync (cadena completa: Core -> Enc -> Auth + Storage -> Sync).

| D | E2 | G | H | I | K | Q | Q2 |
|--:|---:|--:|--:|--:|--:|--:|---:|
| 812 ns | 3,015 ns | 790 ns | 4,659 ns | 3,349 ns | 8,022 ns | 338 ns | 589 ns |

**Analisis:** Q (338 ns) y Q2 (589 ns) dominan la cascada porque Dagger ya tiene
toda la cadena de dependencias resuelta en compilacion. No hay DFS runtime, no hay
synchronized blocks para resolver dependencias intermedias. D (812 ns) y G (790 ns)
construyen Components en cadena con double-check locking. K (8,022 ns) es 24x mas
lento que Q.

### 2.5. CrossFeature

Tiempo de una operacion que cruza multiples features (e.g. Sync que llama Auth, Storage, Encryption).

| D | E2 | G | H | I | K | Q | Q2 |
|--:|---:|--:|--:|--:|--:|--:|---:|
| 2.2M ns | 1.9M ns | 2.0M ns | 1.2M ns | 1.9M ns | 1.2M ns | 1.2M ns | 1.8M ns |

**Analisis:** CrossFeature mide trabajo real de negocio, no wiring. Los tiempos
son similares (1.2M-2.2M ns) porque la operacion esta dominada por la logica de
las features, no por la infraestructura DI. Q/H/K (1.2M ns) empatados como los mas
rapidos; las diferencias restantes son variabilidad de medicion, no ventaja
arquitectural.

### 2.6. E2E Startup

Tiempo end-to-end desde `init()` hasta la primera operacion cross-feature completada.

| D | E2 | G | H | I | K | Q | Q2 |
|--:|---:|--:|--:|--:|--:|--:|---:|
| 301K ns | 414K ns | 560K ns | 806K ns | 571K ns | 896K ns | 568K ns | 520K ns |

**Analisis (post-refactor):** **D (301K ns) tiene el E2E mas rapido** gracias al
logger singleton que evita reconstruir en cada reinit (mejora -75% vs pre-refactor).
E2 (414K ns) segundo lugar por la misma razon. Q (568K ns) ahora en 7º puesto
porque su init ya era rapido pero la op pos-init lo iguala con los demas. K
(896K ns) sigue siendo el mas lento por el overhead de PackageManager en init.

### 2.7. Init/Shutdown Cycle

Tiempo de un ciclo `init() + shutdown()` sin resolver ningun servicio.

| D | E2 | G | H | I | K | Q | Q2 |
|--:|---:|--:|--:|--:|--:|--:|---:|
| 365 ns | 4,991 ns | 375 ns | 84,346 ns | 116,821 ns | 194,035 ns | 278 ns | 565 ns |

**Analisis:** Q (278 ns) ahora lidera, seguido de D (365 ns) y G (375 ns) -- solo
crean y destruyen CoreComponent + acceso al logger singleton. Q2 (565 ns) paga el
overhead del `withActive` lambda. Los patrones con ServiceLoader (H, I) pagan
~85-117K ns y K con PackageManager paga ~194K ns en cada ciclo.

### 2.8. Concurrent Access

Tiempo total de acceso concurrente desde multiples threads.

| D | E2 | G | H | I | K | Q | Q2 |
|--:|---:|--:|--:|--:|--:|--:|---:|
| 439K ns | 418K ns | 439K ns | 386K ns | 418K ns | 417K ns | 453K ns | 478K ns |

**Analisis:** Todos los patrones se comportan de forma similar bajo contention
(386K-478K ns). H (386K ns) es el ganador tras el refactor -- menos GC pressure y
mejor locality por el logger singleton. E2/I/K empatados en ~418K ns.
La concurrencia esta dominada por el overhead de threading, no por la
infraestructura DI.

### 2.9. Resolve All

Tiempo de resolver todos los servicios desde cache (post-init).

| D | E2 | G | H | I | K | Q | Q2 |
|--:|---:|--:|--:|--:|--:|--:|---:|
| 6 ns | 189 ns | 1 ns | 139 ns | 189 ns | 190 ns | 105 ns | 303 ns |

**Analisis (post-refactor):** G (1 ns) y D (6 ns) dominan -- JIT DCE + acceso
directo a campos + logger singleton = casi 0 coste. Q (105 ns) y H (139 ns) acceden
a bindings directos del component generado por Dagger. E2/I/K (~189-190 ns) pasan
por HashMap lookup del Resolver. Q2 (303 ns) paga el overhead del `withActive`
lambda por cada resolucion.

### 2.10. Re-Init

Tiempo de `shutdown() + init()` completo (simulando hot restart).

| D | E2 | G | H | I | K | Q | Q2 |
|--:|---:|--:|--:|--:|--:|--:|---:|
| 2,540 ns | 15,816 ns | 2,275 ns | 185,812 ns | 232,989 ns | 420,674 ns | 1,042 ns | 2,496 ns |

**Analisis (post-refactor):** **Q (1,042 ns) es ahora el mas rapido**, seguido de
G (2,275 ns), Q2 (2,496 ns) y D (2,540 ns). El refactor del logger singleton
evita reconstruir `AndroidSdkLogger()` en cada init -- ganancia masiva en todos
los patterns no-ServiceLoader (D: 36K→2.5K ns, -93%). Los patrones con ServiceLoader
(H/I) bajan a 186-233K ns (-48% a -45% vs pre-refactor). K sigue siendo el mas lento
(420K ns) por el IPC a PackageManager en cada re-init.

### 2.11. Incremental Init

Tiempo de init con estado parcial previo (simula anadir una feature a un SDK ya inicializado).

| D | E2 | G | H | I | K | Q | Q2 |
|--:|---:|--:|--:|--:|--:|--:|---:|
| 1,396 ns | 7,136 ns | 1,417 ns | 85,365 ns | 114,296 ns | 196,640 ns | 639 ns | 1,395 ns |

**Analisis:** Similar a Init Cold. Q (639 ns) lidera, seguido de Q2 (1,395 ns)
y D/G (~1,400 ns). Los patrones con discovery runtime no se benefician
significativamente del estado previo.

---

## 3. Ranking General

### Top 3 por operacion

| Operacion | 1ro | 2do | 3ro |
|-----------|-----|-----|-----|
| Init Cold | Q (647) | G (1,379) | D (1,400) |
| Resolve First | K (0) | H (1) | Q (5) |
| Lazy noDeps | Q (184) | D (266) | G (273) |
| Lazy cascade | Q (338) | Q2 (589) | G (790) |
| E2E Startup | **D (301K)** | E2 (414K) | Q2 (520K) |
| Init/Shutdown | Q (278) | D (365) | G (375) |
| Resolve All | G (1) | D (6) | Q (105) |
| Re-Init | **Q (1,042)** | G (2,275) | Q2 (2,496) |

### Conclusiones

1. **Q y Q2 dominan la mayoria de categorias** gracias a la resolucion compile-time
   de Dagger. Q es mejor para init, Q2 es mejor para re-init y lazy. **Caveat
   no medido en estos benchmarks**: el coste de mantenimiento del `when (clazz)` del
   facade `MultiModuleSdkQ.get()` y de `@Component(modules=[...])` crece linealmente
   con cada API y feature. A 50 features × 10 APIs = 500 ramas + 50 modules listados
   manualmente. Los benchmarks miden runtime, no mantenibilidad.

2. **D y G son los mejores patrones manuales** -- init rapido, lazy real, pero no
   escalan a muchas features. Mismo caveat: el `when` del facade tambien crece.

3. **Los patrones con ServiceLoader (H, I, K) sacrifican init speed por
   escalabilidad real** -- el wiring inmutable end-to-end compensa si el SDK tiene
   10+ features. **A diferencia de Q/Q2/D/G, NO tienen `when` manual** -- el facade
   delega a `resolver.get(clazz)` (HashMap lookup). Anadir API = 0 ediciones al
   facade. Es la propiedad clave que NO se ve en estos numeros pero importa a 50+ APIs.

4. **Q2 es el rey del re-init** -- 2,157 ns es 355x mas rapido que K. Si tu
   app hace hot restart frecuente, Q2 es la eleccion obvia desde el angulo runtime.
   Pero pesa el coste del `when` manual (Req 11, ver `docs/shared/requirements.md`)
   a escala -- compensable con KSP propio para generar el `when`.

**Lectura general**: los benchmarks miden a escala 6 features × 1-2 APIs. El tradeoff
verdadero a 50 × 10 esta en los criterios de mantenimiento (Req 6 + Req 11), no en
nanosegundos. Ver `docs/sdk-recommendation-android.md` para la decision con criterio
bidimensional.
