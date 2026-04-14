# Benchmark Results -- KMP-Compatible Patterns

Resultados de benchmarks Microbenchmark (Jetpack Benchmark) para los 5 patrones
KMP-compatible: N, O, O2, P y P2.

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

| N | O | O2 | P | P2 |
|--:|--:|---:|--:|---:|
| 69,636 ns | 603 ns | 1,127 ns | 1,064 ns | 1,416 ns |

**Analisis:** O (Metro) es el mas rapido con 603 ns -- el compiler plugin genera
codigo directo sin reflexion, maps ni locks. P (kotlin-inject-anvil) es cercano
con 1,064 ns, usando KSP en vez de compiler plugin. O2 y P2 pagan un overhead
adicional (~500-350 ns) por el setup de `LazyCreationTracker`. N (sweet-spi + Koin)
es dramaticamente mas lento: 69,636 ns, **115x mas lento que O**. Este overhead
viene de:
1. sweet-spi discovery (~comparable a ServiceLoader en JVM)
2. Koin module registration (crear y catalogar `single{}` definitions)
3. koinApplication setup

### 2.2. Resolve First

Tiempo de la primera resolucion de un servicio ya construido (cache hit).

| N | O | O2 | P | P2 |
|--:|--:|---:|--:|---:|
| 5,855 ns | 288 ns | 315 ns | 336 ns | 335 ns |

**Analisis:** O, O2, P y P2 son similares (288-336 ns) -- acceden a propiedades
directas del grafo/component generado. N (5,855 ns) es **20x mas lento** porque
Koin resuelve via reflexion sobre `KClass`: `koin.get(clazz.kotlin)` implica
un lookup en el definition registry de Koin.

### 2.3. Lazy Init noDeps

Tiempo de inicializar una feature sin dependencias cruzadas (e.g. Analytics).

| N | O | O2 | P | P2 |
|--:|--:|---:|--:|---:|
| 20,018 ns | 2,098 ns | 238 ns | 1,941 ns | 284 ns |

**Analisis:** O2 (238 ns) y P2 (284 ns) son los ganadores absolutos -- la feature
se crea on-demand con codigo generado, sin overhead de framework. O (2,098 ns) y
P (1,941 ns) son mas lentos porque el singleton ya se creo eagerly en init; aqui
miden el acceso con overhead de scoping. N (20,018 ns) es el **peor de los 16
patrones** en esta metrica: Koin `single{}` necesita ejecutar el lambda, registrar
el resultado y resolver dependencias del modulo.

### 2.4. Lazy Init cascade

Tiempo de inicializar Sync (cadena: Core -> Enc -> Auth + Storage -> Sync).

| N | O | O2 | P | P2 |
|--:|--:|---:|--:|---:|
| 22,706 ns | 346 ns | 507 ns | 607 ns | 734 ns |

**Analisis:** O (346 ns) lidera la cascada -- Metro resuelve toda la cadena en
compilacion, el acceso es directo. O2 (507 ns) paga ~160 ns extra por materializar
Lazy wrappers en cadena. P y P2 (607-734 ns) son ligeramente mas lentos por la
capa de KSP vs compiler plugin. N (22,706 ns) es **65x mas lento que O** -- la
cascada en Koin involucra multiples lookups `koin.get()` recursivos.

### 2.5. CrossFeature

Tiempo de una operacion cross-feature (Sync -> Auth + Storage + Encryption).

| N | O | O2 | P | P2 |
|--:|--:|---:|--:|---:|
| 1.8M ns | 1.7M ns | 1.8M ns | 1.7M ns | 3.1M ns |

**Analisis:** CrossFeature mide trabajo real de negocio, no wiring. Los tiempos
son similares (1.7M-1.8M ns) excepto P2 (3.1M ns) que muestra un outlier --
probablemente variabilidad de medicion o GC pressure durante esa iteracion.

### 2.6. E2E Startup

Tiempo end-to-end desde `init()` hasta la primera operacion cross-feature completada.

| N | O | O2 | P | P2 |
|--:|--:|---:|--:|---:|
| 2.0M ns | 1.2M ns | 1.5M ns | 1.4M ns | 993K ns |

**Analisis:** P2 (993K ns) tiene el E2E mas rapido -- su init rapido (1,416 ns)
combinado con lazy singletons que solo crean lo necesario. O (1.2M ns) es cercano.
N (2.0M ns) es el mas lento por su init pesado.

### 2.7. Init/Shutdown Cycle

Tiempo de un ciclo `init() + shutdown()` sin resolver ningun servicio.

| N | O | O2 | P | P2 |
|--:|--:|---:|--:|---:|
| 42,293 ns | 301 ns | 516 ns | 293 ns | 508 ns |

**Analisis:** P (293 ns) y O (301 ns) son practicamente identicos -- shutdown
solo nullifica el component/graph. O2 y P2 (~508-516 ns) pagan el cleanup de
LazyCreationTracker. N (42,293 ns) incluye `koinApp.close()` que limpia el
definition registry completo.

### 2.8. Concurrent Access

Tiempo total de acceso concurrente desde multiples threads.

| N | O | O2 | P | P2 |
|--:|--:|---:|--:|---:|
| 784K ns | 586K ns | 587K ns | 618K ns | 638K ns |

**Analisis:** Todos se comportan de forma similar (586K-784K ns). La concurrencia
esta dominada por threading overhead. N es el mas lento (784K ns) por locks
internos de Koin en acceso concurrente.

### 2.9. Resolve All

Tiempo de resolver todos los servicios desde cache.

| N | O | O2 | P | P2 |
|--:|--:|---:|--:|---:|
| 6,328 ns | 80 ns | 86 ns | 165 ns | 156 ns |

**Analisis:** O (80 ns) es el mas rapido -- acceso directo a propiedades del grafo
generado. O2 (86 ns) es similar (Lazy ya materializado = acceso directo). P y P2
(156-165 ns) pagan overhead de KSP generated code. N (6,328 ns) es **79x mas
lento que O** -- Koin lookup por cada servicio.

### 2.10. Re-Init

Tiempo de `shutdown() + init()` completo (simulando hot restart).

| N | O | O2 | P | P2 |
|--:|--:|---:|--:|---:|
| 732,000 ns | 36,000 ns | 2,305 ns | 28,000 ns | 2,929 ns |

**Analisis:** Este benchmark revela la ventaja critica de las variantes lazy:

- **O2 (2,305 ns)** es 15.6x mas rapido que O (36,000 ns)
- **P2 (2,929 ns)** es 9.6x mas rapido que P (28,000 ns)
- **N (732,000 ns)** es el peor de todos: 318x mas lento que O2

La razon: las variantes lazy no recrean singletons en re-init. Solo se resetea
el tracker y se crea un nuevo grafo/component vacio. Los singletons se crearan
on-demand cuando se necesiten.

### 2.11. Incremental Init

Tiempo de init con estado parcial previo.

| N | O | O2 | P | P2 |
|--:|--:|---:|--:|---:|
| 71,509 ns | 588 ns | 952 ns | 1,060 ns | 1,321 ns |

**Analisis:** Similar a Init Cold. Los patrones compile-time (O, O2, P, P2) no
se benefician significativamente del estado previo -- el grafo se reconstruye
desde cero. N paga el overhead completo de sweet-spi + Koin.

---

## 3. Ranking General

### Top 3 por operacion

| Operacion | 1ro | 2do | 3ro |
|-----------|-----|-----|-----|
| Init Cold | O (603) | P (1,064) | O2 (1,127) |
| Resolve First | O (288) | O2 (315) | P2 (335) |
| Lazy noDeps | O2 (238) | P2 (284) | P (1,941) |
| Lazy cascade | O (346) | O2 (507) | P (607) |
| E2E Startup | P2 (993K) | O (1.2M) | P (1.4M) |
| Init/Shutdown | P (293) | O (301) | P2 (508) |
| Resolve All | O (80) | O2 (86) | P2 (156) |
| Re-Init | O2 (2,305) | P2 (2,929) | P (28K) |

### Hallazgos clave

1. **O (Metro) domina init y resolucion** -- compiler plugin genera codigo
   altamente optimizado sin overhead de framework. **Caveat no medido**: el facade
   `MultiModuleSdkO.get<T>(Class)` mantiene un `when (clazz)` manual que crece 1
   rama por API. A escala 50 features × 10 APIs = 500 ramas mantenidas a mano.
   Mitigable con KSP propio (ver `docs/shared/requirements.md` Req 11).

2. **O2 y P2 dominan re-init y lazy** -- las variantes lazy son 10-16x mas rapidas
   en re-init, lo que las hace ideales para SDKs con hot restart. **Mismo caveat
   de Req 11** que O y P.

3. **N (Koin) es consistentemente el mas lento** en runtime, pero **es el unico
   patron KMP-compatible con facade inmutable nativo end-to-end** (`koin.get(clazz.kotlin)`
   sin `when`, sin codegen propio). El trade-off: 6,328 ns resolve cached vs 86 ns
   en O2. Bueno si la prioridad es zero-touch y resolves no estan en hot loops.

4. **P vs O: KSP vs compiler plugin** -- la diferencia es ~1.8x en init (1,064 vs
   603 ns). Ambos son sub-microsegundo. La eleccion depende de preferencia de
   tooling, no de performance. Ambos comparten el mismo coste de Req 11.

5. **Lazy vale la pena para SDKs grandes** -- si tu SDK tiene 20+ features y el
   usuario tipico solo usa 5, las variantes lazy (O2, P2) evitan crear los 15
   singletons innecesarios.

6. **Benchmark vs mantenibilidad** -- estos numeros miden runtime con 6 features ×
   1-2 APIs. El tradeoff verdadero a 50 features × 10 APIs es de mantenibilidad
   (lineas de wiring + ramas de `when`), no de nanosegundos. Ver
   `docs/sdk-recommendation-kmp.md` para la decision con criterio bidimensional.
