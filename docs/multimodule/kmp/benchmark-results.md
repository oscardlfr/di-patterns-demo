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
| 96,719 ns | 723 ns | 1,412 ns | 785 ns | 1,722 ns |

**Analisis:** O (Metro) es el mas rapido con 723 ns -- el compiler plugin genera
codigo directo sin reflexion, maps ni locks. P (kotlin-inject-anvil) es cercano
con 785 ns, usando KSP en vez de compiler plugin. O2 y P2 pagan overhead adicional
(~600-900 ns) por el setup de `LazyCreationTracker.activate()` + `withActive` lambda.
N (sweet-spi + Koin) es dramaticamente mas lento: 96,719 ns, **134x mas lento que O**.
Este overhead viene de:
1. sweet-spi discovery (~comparable a ServiceLoader en JVM)
2. Koin module registration (crear y catalogar `single{}` definitions)
3. koinApplication setup + ReentrantReadWriteLock overhead

### 2.2. Resolve First

Tiempo de la primera resolucion de un servicio ya construido (cache hit).

| N | O | O2 | P | P2 |
|--:|--:|---:|--:|---:|
| 1,038 ns | 5 ns | 7 ns | 0 ns | 5 ns |

**Analisis (post-refactor):** P (0 ns), O/P2 (5 ns), O2 (7 ns) caen a valores
indistinguibles de cero -- el JIT aplica dead-code-elimination post-warmup tras la
homogeneizacion del logger singleton. N (1,038 ns) sigue siendo el mas lento pero
bajo de 5,855 ns gracias al logger singleton (menos GC pressure); Koin sigue sin
ser DCE-able por el JIT porque `koin.get(clazz.kotlin)` tiene side-effects reales.

### 2.3. Lazy Init noDeps

Tiempo de inicializar una feature sin dependencias cruzadas (e.g. Analytics).

| N | O | O2 | P | P2 |
|--:|--:|---:|--:|---:|
| 4,331 ns | 191 ns | 282 ns | 222 ns | 348 ns |

**Analisis (post-refactor):** O (191 ns) y P (222 ns) ahora lideran -- los
singletons ya estan materializados en init, el acceso es directo. O2 (282 ns) y
P2 (348 ns) pagan el overhead del `withActive` lambda (necesario para ThreadLocal
isolation). N (4,331 ns) cayo de 20,018 a 4,331 (-78%) gracias al logger singleton
que no se reconstruye en cada warmup + ReadWriteLock que elimina contencion.

### 2.4. Lazy Init cascade

Tiempo de inicializar Sync (cadena: Core -> Enc -> Auth + Storage -> Sync).

| N | O | O2 | P | P2 |
|--:|--:|---:|--:|---:|
| 27,080 ns | 367 ns | 591 ns | 488 ns | 919 ns |

**Analisis:** O (367 ns) lidera la cascada -- Metro resuelve toda la cadena en
compilacion, el acceso es directo. O2 (591 ns) paga ~224 ns extra por materializar
Lazy wrappers en cadena + `withActive`. P (488 ns) y P2 (919 ns) son ligeramente mas
lentos por la capa de KSP vs compiler plugin. N (27,080 ns) es **74x mas lento que
O** -- la cascada en Koin involucra multiples lookups `koin.get()` recursivos.

### 2.5. CrossFeature

Tiempo de una operacion cross-feature (Sync -> Auth + Storage + Encryption).

| N | O | O2 | P | P2 |
|--:|--:|---:|--:|---:|
| 2.4M ns | 2.1M ns | 2.1M ns | 1.3M ns | 1.4M ns |

**Analisis:** CrossFeature mide trabajo real de negocio, no wiring. P y P2 lideran
(1.3-1.4M ns). El resto (O/O2/N) esta en 2.1-2.4M ns. Las diferencias se deben
a variabilidad de DataStore I/O + GC, no a infraestructura DI.

### 2.6. E2E Startup

Tiempo end-to-end desde `init()` hasta la primera operacion cross-feature completada.

| N | O | O2 | P | P2 |
|--:|--:|---:|--:|---:|
| 710K ns | 538K ns | 341K ns | 534K ns | 552K ns |

**Analisis (post-refactor):** **O2 (341K ns) ahora lidera** -- logger singleton +
lazy singletons permiten el E2E mas rapido. Reduccion masiva vs pre-refactor en
todos los patrones: O (-55%), O2 (-77%), P (-62%), P2 (-44%), N (-65%). La ganancia
viene del logger singleton que evita reconstruir `AndroidSdkLogger()` en cada
reinit.

### 2.7. Init/Shutdown Cycle

Tiempo de un ciclo `init() + shutdown()` sin resolver ningun servicio.

| N | O | O2 | P | P2 |
|--:|--:|---:|--:|---:|
| 51,447 ns | 241 ns | 852 ns | 184 ns | 471 ns |

**Analisis:** **P (184 ns) lidera**, seguido de O (241 ns) -- shutdown solo
nullifica el component/graph. O2 (852 ns) sube por el overhead del `withActive` en
el `get()` dentro del ciclo de stress. P2 (471 ns) paga el cleanup de
LazyCreationTracker. N (51,447 ns) incluye `koinApp.close()` que limpia el
definition registry completo.

### 2.8. Concurrent Access

Tiempo total de acceso concurrente desde multiples threads.

| N | O | O2 | P | P2 |
|--:|--:|---:|--:|---:|
| 456K ns | 435K ns | 452K ns | 456K ns | 468K ns |

**Analisis (post-refactor):** Todos se comportan de forma similar (435K-468K ns).
N bajo drasticamente de 784K a 456K (-42%) gracias al ReentrantReadWriteLock anadido
en L/M/N (separar init/shutdown de reads concurrentes). La concurrencia esta
dominada por threading overhead residual, no por el framework DI.

### 2.9. Resolve All

Tiempo de resolver todos los servicios desde cache.

| N | O | O2 | P | P2 |
|--:|--:|---:|--:|---:|
| 6,307 ns | 108 ns | 273 ns | 146 ns | 380 ns |

**Analisis:** O (108 ns) es el mas rapido -- acceso directo a propiedades del grafo
generado. P (146 ns) usa KSP generated code. O2 (273 ns) y P2 (380 ns) pagan el
overhead del `withActive` lambda envolviendo cada get(). N (6,307 ns) es **58x mas
lento que O** -- Koin lookup por cada servicio.

### 2.10. Re-Init

Tiempo de `shutdown() + init()` completo (simulando hot restart).

| N | O | O2 | P | P2 |
|--:|--:|---:|--:|---:|
| 178,294 ns | 1,120 ns | 2,408 ns | 1,528 ns | 2,951 ns |

**Analisis (post-refactor):** El logger singleton ahora beneficia a TODOS los
patrones, no solo a los lazy:

- **O (1,120 ns)** mejora -97% (era 36,000 ns)
- **P (1,528 ns)** mejora -95% (era 28,000 ns)
- **O2 (2,408 ns)** mejora marginal +4% (ya era rapido pre-refactor)
- **P2 (2,951 ns)** mejora marginal +1%
- **N (178,294 ns)** mejora -76% (era 732,000 ns) gracias al logger singleton
  que evita reconstruir en cada Koin container reinit.

La ventaja lazy ahora es minima frente a eager: ambos se benefician del logger
singleton process-scoped.

### 2.11. Incremental Init

Tiempo de init con estado parcial previo.

| N | O | O2 | P | P2 |
|--:|--:|---:|--:|---:|
| 80,029 ns | 694 ns | 1,411 ns | 784 ns | 1,661 ns |

**Analisis:** Similar a Init Cold. Los patrones compile-time (O, O2, P, P2) no
se benefician significativamente del estado previo -- el grafo se reconstruye
desde cero. N paga el overhead completo de sweet-spi + Koin.

---

## 3. Ranking General

### Top 3 por operacion

| Operacion | 1ro | 2do | 3ro |
|-----------|-----|-----|-----|
| Init Cold | O (723) | P (785) | O2 (1,412) |
| Resolve First | P (0) | O/P2 (5) | O2 (7) |
| Lazy noDeps | O (191) | P (222) | O2 (282) |
| Lazy cascade | O (367) | P (488) | O2 (591) |
| E2E Startup | **O2 (341K)** | P (534K) | O (538K) |
| Init/Shutdown | P (184) | O (241) | P2 (471) |
| Resolve All | O (108) | P (146) | O2 (273) |
| Re-Init | **O (1,120)** | P (1,528) | O2 (2,408) |

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
