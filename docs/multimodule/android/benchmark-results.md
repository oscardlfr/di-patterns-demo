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
| 1,212 ns | 10,983 ns | 1,257 ns | 106,865 ns | 94,255 ns | 213,737 ns | 676 ns | 1,080 ns |

**Analisis:** Q es el mas rapido (676 ns) porque Dagger genera todo el wiring en
compilacion -- `DaggerSdkComponent.factory().create()` solo instancia un objeto
ya pre-conectado. Q2 (1,080 ns) paga ~400 ns extra por el setup de
`LazyCreationTracker`. D y G (~1,200 ns) solo construyen CoreComponent. E2
(10,983 ns) paga el coste de catalogar entries en HashMaps. H, I y K son ordenes
de magnitud mas lentos (94K-214K ns) por el overhead de discovery en runtime
(ServiceLoader o PackageManager).

### 2.2. Resolve First

Tiempo de la primera resolucion de un servicio ya construido (cache hit).

| D | E2 | G | H | I | K | Q | Q2 |
|--:|---:|--:|--:|--:|--:|--:|---:|
| 346 ns | 199 ns | 345 ns | 202 ns | 203 ns | 203 ns | 257 ns | 306 ns |

**Analisis:** Todos estan por debajo de 400 ns. E2, H, I y K (~200 ns) son los mas
rapidos gracias al `ConcurrentHashMap` lookup directo. Q y Q2 (~257-306 ns) pasan
por un when-block con cast. D y G (~345 ns) acceden a campos volatiles. La
diferencia es irrelevante en produccion.

### 2.3. Lazy Init noDeps

Tiempo de inicializar una feature sin dependencias cruzadas (e.g. Analytics).

| D | E2 | G | H | I | K | Q | Q2 |
|--:|---:|--:|--:|--:|--:|--:|---:|
| 255 ns | 1,049 ns | 260 ns | 1,278 ns | 1,112 ns | 2,996 ns | 1,735 ns | 236 ns |

**Analisis:** Q2 (236 ns) y D (255 ns) son los mas rapidos. Q2 es rapido porque
`dagger.Lazy.get()` solo ejecuta el provider la primera vez, sin overhead de
framework. D crea un solo DaggerComponent via `ensure*()`. Q (1,735 ns) es mas
lento que Q2 porque el singleton ya se creo eagerly en init -- aqui mide el acceso
a un binding ya instanciado con overhead de scoping. K (2,996 ns) es el mas lento
por la capa adicional de manifest discovery.

### 2.4. Lazy Init cascade

Tiempo de inicializar Sync (cadena completa: Core -> Enc -> Auth + Storage -> Sync).

| D | E2 | G | H | I | K | Q | Q2 |
|--:|---:|--:|--:|--:|--:|--:|---:|
| 696 ns | 3,088 ns | 848 ns | 3,892 ns | 4,122 ns | 7,900 ns | 318 ns | 504 ns |

**Analisis:** Q (318 ns) y Q2 (504 ns) dominan la cascada porque Dagger ya tiene
toda la cadena de dependencias resuelta en compilacion. No hay DFS runtime, no hay
synchronized blocks para resolver dependencias intermedias. D (696 ns) construye
Components en cadena con double-check locking. K (7,900 ns) es 25x mas lento que Q.

### 2.5. CrossFeature

Tiempo de una operacion que cruza multiples features (e.g. Sync que llama Auth, Storage, Encryption).

| D | E2 | G | H | I | K | Q | Q2 |
|--:|---:|--:|--:|--:|--:|--:|---:|
| 1.9M ns | 2.1M ns | 2.0M ns | 1.3M ns | 1.5M ns | 2.0M ns | 1.6M ns | 1.7M ns |

**Analisis:** CrossFeature mide trabajo real de negocio, no wiring. Los tiempos
son similares (1.2M-2.1M ns) porque la operacion esta dominada por la logica de
las features, no por la infraestructura DI. H (1.3M ns) es ligeramente mas rapido
por variabilidad de la medicion, no por ventaja arquitectural.

### 2.6. E2E Startup

Tiempo end-to-end desde `init()` hasta la primera operacion cross-feature completada.

| D | E2 | G | H | I | K | Q | Q2 |
|--:|---:|--:|--:|--:|--:|--:|---:|
| 1.2M ns | 1.4M ns | 1.4M ns | 1.7M ns | 1.7M ns | 2.3M ns | 950K ns | 1.3M ns |

**Analisis:** Q (950K ns) tiene el E2E mas rapido -- su init ultra-rapido
(676 ns) mas la resolucion compile-time le dan ventaja. D (1.2M ns) es cercano
gracias a su init rapido. K (2.3M ns) es el mas lento por el overhead de
PackageManager en init.

### 2.7. Init/Shutdown Cycle

Tiempo de un ciclo `init() + shutdown()` sin resolver ningun servicio.

| D | E2 | G | H | I | K | Q | Q2 |
|--:|---:|--:|--:|--:|--:|--:|---:|
| 248 ns | 4,418 ns | 229 ns | 99,293 ns | 103,695 ns | 201,490 ns | 403 ns | 549 ns |

**Analisis:** G (229 ns) y D (248 ns) son los mas baratos -- solo crean y destruyen
CoreComponent. Q (403 ns) y Q2 (549 ns) son rapidos porque el shutdown solo nullifica
el component. Los patrones con ServiceLoader (H, I) pagan ~100K ns y K con
PackageManager paga ~200K ns en cada ciclo.

### 2.8. Concurrent Access

Tiempo total de acceso concurrente desde multiples threads.

| D | E2 | G | H | I | K | Q | Q2 |
|--:|---:|--:|--:|--:|--:|--:|---:|
| 493K ns | 571K ns | 596K ns | 515K ns | 608K ns | 554K ns | 591K ns | 586K ns |

**Analisis:** Todos los patrones se comportan de forma similar bajo contention
(493K-608K ns). La concurrencia esta dominada por el overhead de threading, no por
la infraestructura DI. D (493K ns) es ligeramente mejor por su synchronized simple.

### 2.9. Resolve All

Tiempo de resolver todos los servicios desde cache (post-init).

| D | E2 | G | H | I | K | Q | Q2 |
|--:|---:|--:|--:|--:|--:|--:|---:|
| 100 ns | 211 ns | 101 ns | 212 ns | 211 ns | 213 ns | 64 ns | 85 ns |

**Analisis:** Q (64 ns) y Q2 (85 ns) son los mas rapidos porque acceden a
bindings directos del component generado por Dagger. D y G (~100 ns) usan campos
volatiles. E2, H, I, K (~211-213 ns) pasan por HashMap lookup del Resolver.

### 2.10. Re-Init

Tiempo de `shutdown() + init()` completo (simulando hot restart).

| D | E2 | G | H | I | K | Q | Q2 |
|--:|---:|--:|--:|--:|--:|--:|---:|
| 36K ns | 17K ns | 38K ns | 363K ns | 427K ns | 767K ns | 25K ns | 2,157 ns |

**Analisis:** Q2 (2,157 ns) es **dramaticamente** mas rapido que todos los demas:
11.6x mas rapido que Q (25K ns), 16.7x mas rapido que D (36K ns), y 355x mas
rapido que K (767K ns). Esto se debe a que Q2 no recrea singletons en re-init --
el `LazyCreationTracker` se resetea y los Lazy wrappers se descartan sin ejecutar
sus providers. Los patrones con ServiceLoader pagan el overhead de discovery
completo en cada re-init.

### 2.11. Incremental Init

Tiempo de init con estado parcial previo (simula anadir una feature a un SDK ya inicializado).

| D | E2 | G | H | I | K | Q | Q2 |
|--:|---:|--:|--:|--:|--:|--:|---:|
| 1,172 ns | 11,688 ns | 1,223 ns | 97,694 ns | 100,488 ns | 213,696 ns | 667 ns | 1,218 ns |

**Analisis:** Similar a Init Cold. Q (667 ns) lidera, seguido de Q2 (1,218 ns)
y D/G (~1,200 ns). Los patrones con discovery runtime no se benefician
significativamente del estado previo.

---

## 3. Ranking General

### Top 3 por operacion

| Operacion | 1ro | 2do | 3ro |
|-----------|-----|-----|-----|
| Init Cold | Q (676) | Q2 (1,080) | D (1,212) |
| Resolve First | E2 (199) | H (202) | I (203) |
| Lazy noDeps | Q2 (236) | D (255) | G (260) |
| Lazy cascade | Q (318) | Q2 (504) | D (696) |
| E2E Startup | Q (950K) | D (1.2M) | Q2 (1.3M) |
| Init/Shutdown | G (229) | D (248) | Q (403) |
| Resolve All | Q (64) | Q2 (85) | D (100) |
| Re-Init | Q2 (2,157) | E2 (17K) | Q (25K) |

### Conclusiones

1. **Q y Q2 dominan la mayoria de categorias** gracias a la resolucion compile-time
   de Dagger. Q es mejor para init, Q2 es mejor para re-init y lazy.

2. **D y G son los mejores patrones manuales** -- init rapido, lazy real, pero no
   escalan a muchas features.

3. **Los patrones con ServiceLoader (H, I, K) sacrifican init speed por
   escalabilidad** -- el wiring inmutable compensa si el SDK tiene 10+ features.

4. **Q2 es el rey del re-init** -- 2,157 ns es 355x mas rapido que K. Si tu
   app hace hot restart frecuente, Q2 es la eleccion obvia.
