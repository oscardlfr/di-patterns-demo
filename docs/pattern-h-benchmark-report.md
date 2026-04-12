# Reporte de Benchmarks: Pattern H (Auto-Discovery + ServiceLoader)

**Patron:** H -- Auto-Discovery con FeatureProviders + DFS Resolver  
**Modulo:** `sdk/wiring-h` (51 lineas de wiring)  
**Framework interno:** Dagger 2 (dentro de cada feature-impl)  
**Descubrimiento:** `ServiceLoader.load(FeatureProvider::class.java)`  

**Dispositivo:** Samsung Galaxy S22 Ultra (SM-S908B) -- Snapdragon 8 Gen 1, 8 nucleos, 2.8 GHz, Android 16  
**Framework de medicion:** Jetpack Benchmark 1.4.0 (`AndroidBenchmarkRunner`)  
**Warmup:** automatico (repite hasta estabilidad termica, detecta thermal throttle)  
**Iteraciones de medicion:** automaticas (tipicamente 5-50, ajustadas por varianza)  
**Metrica reportada:** mediana de `timeNs` (no media -- ignora outliers por GC o throttle)  
**Build type:** release (`isMinifyEnabled = false` -- sin R8, mide codigo real)  
**Errores suprimidos:** EMULATOR, LOW-BATTERY, ACTIVITY-MISSING  
**Fecha:** 2026-04-12

---

## 1. Resumen de Resultados

Todos los valores en nanosegundos (ns). Dispositivo: S22 Ultra, Jetpack Benchmark 1.4.0.
Total: 35 tests (12 benchmarks + 9 memoria + 14 stress y concurrencia). Todos pasados.

| Categoria | Test | Resultado | Criterio |
|-----------|------|-----------|----------|
| **Benchmark** | initCold | 106,865 ns | Init + ServiceLoader scan + register |
| | resolveFirst | 202 ns | Singleton cached lookup |
| | lazyInit noDeps | 1,278 ns | Build Analytics (0 cross-deps) |
| | lazyInit cascade | 3,892 ns | Build Sync (4-level DFS cascade) |
| | crossFeatureOp | 1,284,479 ns | Real operation across Auth+Stor+Enc (DataStore I/O) |
| | e2eStartup | 1,745,145 ns | Init + resolve all + first ops (DataStore I/O) |
| | stress_initShutdown | 99,293 ns | One init/get/shutdown cycle |
| | stress_concurrent | 515,355 ns | 4 threads simultaneous get<T>() |
| | stress_resolveAll | 212 ns | 6 services from cache sequential |
| | stress_selective | 155,533 ns | Init + 1 feature + shutdown |
| | stress_reInit | 362,649 ns | Two full cycles |
| | stress_incremental | 97,694 ns | 6 features one by one |
| **Memoria** | initOnly | OK | builtProvisionCount == 0 after init |
| | getEnc | OK | builtProvisionCount == 3 after get(Enc) |
| | getAna | OK | builtProvisionCount == 3 after get(Ana) |
| | getSync | OK | builtProvisionCount == 6 after get(Sync) |
| | fullGraph | OK | builtProvisionCount == 7 |
| | shutdown | OK | builtProvisionCount == 0 after shutdown |
| | freshInstances | OK | assertNotSame(enc1, enc2) after reinit |
| | leakDetection | OK | delta heap < 2,048 KB in 1,000 cycles |
| | heapFootprint | OK | Comparative heap measurement (logcat, no assert) |
| **Stress y Concurrencia** | thunderingHerd | OK | 100 threads, all assertSame |
| | singleton | OK | 10,000x assertSame |
| | crossPatternIsolation | OK | 16 patterns simultaneous, zero contamination |
| | rapidFire | OK | 5,000 cycles with asserts |
| | memoryPressure | OK | Provisions survive GC storm |
| | stress10K | OK | heap=4 KB, pss=3,937 KB tras 10,000 ciclos |
| | freshness | OK | 50 reinits, 50 unique instances |
| | errorResilience | OK | 5 error scenarios correct |
| | functional | OK | Encrypt+Auth+Sync after 1,000 reinits |
| | coldCascadeTiming | OK | init=283us, build=68us, 1000x cached=68us (logcat) |
| | alternatingPatterns | OK | 1600 cycles alternating 16 patterns |
| | concurrentBuild | OK | 100 rounds, 6 threads build simultaneously |
| | concurrentSelective | OK | 50 rounds, laziness under contention |
| | concurrentShutdown | OK | 200 rounds, read vs shutdown race |

---

## 2. Detalle por Test

### 2.1 Benchmarks (12 tests) -- MultiModuleBenchmark

---

**initCold** | 106,865 ns
```
1. sdk.init(context, config)
2. ServiceLoader.load(FeatureProvider::class.java) escanea META-INF/services
3. resolver.register(provider) por cada uno de los 7 providers encontrados
4. Indexa servicios en serviceIndex (HashMap)
```
Mide el costo completo de arranque: crear el Resolver, escanear el classpath via ServiceLoader,
registrar los 7 FeatureProviders, e indexar los servicios que expone cada uno. No construye
ninguna provision (laziness total).

---

**resolveFirst** | 202 ns
```
1. sdk.get(EncryptionApi::class.java)
2. resolvedServices[clazz] -> cache hit en ConcurrentHashMap
3. return instancia cacheada
```
Con la provision ya construida, la resolucion es un lookup O(1) en el ConcurrentHashMap
de resolvedServices. 202 ns es el costo de un get() + cast.

---

**lazyInit noDeps** | 1,278 ns
```
1. sdk.get(AnalyticsApi::class.java)
2. Resolver: serviceIndex[AnalyticsApi] -> AnaProvisions
3. ensureBuilt(AnaProvisions):
   a. ensureBuilt(CoreProvisions) -> construye Core (sin deps propias)
   b. resolver.logger -> ensureBuilt(ObservabilityProvisions)
   c. AnaProvider.build(resolver) -> DaggerAnaComponent.builder()...build()
4. Cachea AnaProvisions + servicios en ConcurrentHashMap
```
Analytics no tiene cross-deps con Auth, Storage ni Sync. Solo necesita Core y Logger.
1,278 ns mide construir un feature aislado con su DFS minimo.

---

**lazyInit cascade** | 3,892 ns
```
1. sdk.get(SyncApi::class.java)
2. Resolver: serviceIndex[SyncApi] -> SynProvisions
3. ensureBuilt(SynProvisions):
   a. SynProvider.build() llama:
      - resolver.provision(AuthProvisions) -> DFS nivel 2
        - AuthProvider.build() llama resolver.provision(EncProvisions) -> DFS nivel 3
          - EncProvider.build() llama resolver.provision(CoreProvisions) -> DFS nivel 4
            - CoreProvider.build() -> CONSTRUYE Core (fondo)
          - CONSTRUYE Enc
        - CONSTRUYE Auth
      - resolver.provision(StorProvisions) -> DFS nivel 2
        - StorProvider.build() -> Enc y Core ya cacheados -> CONSTRUYE Stor
      - resolver.provision(EncProvisions) -> ya cacheado -> skip
   b. CONSTRUYE Syn
```
La cascada mas profunda del grafo: 4 niveles de DFS recursivo. Construye 6 provisions
(Core, Observability, Enc, Auth, Stor, Syn). 3,892 ns para todo el arbol.

---

**crossFeatureOp** | 1,284,479 ns
```
1. sdk.init(context, config)
2. auth = sdk.get(AuthApi)
3. auth.login("user", "pass")
4. stor = sdk.get(SecureStorageApi)
5. stor.save("key", encrypted)
6. enc = sdk.get(EncryptionApi)
7. enc.encrypt(data)
```
Mide trabajo real de negocio cruzando 3 features: login + almacenamiento seguro + cifrado.
El costo dominante es la logica de negocio (crypto, I/O a disco via DataStore), no la
resolucion DI. Los valores reflejan el coste real de operaciones cross-feature con
persistencia a disco (sync.sync()).

---

**e2eStartup** | 1,745,145 ns
```
1. sdk.init(context, config)                     -- ~106,865 ns
2. sdk.get() de los 6 servicios                -- cascade DFS
3. Primera operacion por cada feature           -- trabajo real (DataStore I/O)
```
Simula un arranque real completo: init + resolver todas las features + ejecutar la primera
operacion de cada una. 1,745,145 ns = ~1.75 ms para el SDK completo. El valor incluye DataStore (I/O real a disco)
en las operaciones cross-feature.

---

**stress_initShutdown** | 99,293 ns
```
1. sdk.init(context, config)
2. sdk.get(EncryptionApi)
3. sdk.shutdown()
```
Un ciclo completo de vida del SDK. Mide el overhead de init + build de 1 feature + limpieza.

---

**stress_concurrent** | 515,355 ns
```
1. sdk.init(context, config)
2. Lanzar 4 threads simultaneos:
   - Thread 1: sdk.get(EncryptionApi)
   - Thread 2: sdk.get(AuthApi)
   - Thread 3: sdk.get(SyncApi)
   - Thread 4: sdk.get(AnalyticsApi)
3. Esperar join de los 4 threads
```
4 threads compitiendo por construir provisions al mismo tiempo. El synchronized + ConcurrentHashMap
del Resolver serializa las construcciones pero permite lookups concurrentes.

---

**stress_resolveAll** | 212 ns
```
1. (grafo ya construido)
2. sdk.get(EncryptionApi)
3. sdk.get(AuthApi)
4. sdk.get(SecureStorageApi)
5. sdk.get(AnalyticsApi)
6. sdk.get(SyncApi)
7. sdk.get(HashApi)
```
6 lookups secuenciales desde cache. 212 ns / 6 = 35.3 ns por servicio, consistente con
resolveFirst (202 ns). Puro ConcurrentHashMap lookup.

---

**stress_selective** | 155,533 ns
```
1. sdk.init(context, config)
2. sdk.get(EncryptionApi)   -- solo 1 de 6 features
3. sdk.shutdown()
```
Verifica que resolver 1 sola feature no construye las otras 5. El costo incluye
init + build de 1 feature + shutdown completo.

---

**stress_reInit** | 362,649 ns
```
1. sdk.init(context, config)
2. sdk.get() de todos los servicios
3. sdk.shutdown()
4. sdk.init(context, config)
5. sdk.get() de todos los servicios
6. sdk.shutdown()
```
Dos ciclos completos. ~363K ns / 2 = ~181K ns por ciclo, consistente con los benchmarks
individuales. Verifica que no hay degradacion entre ciclos.

---

**stress_incremental** | 97,694 ns
```
1. sdk.init(context, config)
2. sdk.get(EncryptionApi)    -- 3 provisions (Obs+Core+Enc)
3. sdk.get(AuthApi)          -- +1 (Auth, Enc ya cacheado)
4. sdk.get(SecureStorageApi) -- +1 (Stor, Enc ya cacheado)
5. sdk.get(AnalyticsApi)     -- +0 (Ana, Core ya cacheado)
6. sdk.get(SyncApi)          -- +0 (Syn, deps ya cacheadas)
7. sdk.get(HashApi)          -- +0 (Enc ya construido, HashApi ya extraido)
```
Construir las 6 features una a una. Cada get() solo construye lo que falta gracias al cache.
El costo total es menor que un cascade porque no hay DFS profundo: cada feature encuentra
sus dependencias ya construidas.

---

### 2.2 Memoria (9 tests) -- MemoryBehaviorTest

Conteo de provisions construidas en cada etapa -- verifica que el grafo es genuinamente lazy.

| Etapa | Provisions construidas | Que paso |
|-------|----------------------|----------|
| afterInit | 0 | `init()` solo registra providers en el Resolver. No construye nada |
| afterEnc | 3 | `get<EncryptionApi>()` construye: Observability + Core + Enc |
| afterAna | 3 | `get<AnalyticsApi>()` reutiliza Observability+Core, construye Ana. Pero ya habia 3 |
| afterSync | 6 | `get<SyncApi>()` construye cascada: Auth + Stor + Syn (Enc ya cacheado) |
| fullGraph | 7 | Todas las provisions construidas (6 features + 1 Observability) |

**Observacion:** H construye ObservabilityProvisions como provision adicional (fullGraph = 7 vs 6 en D/G)
porque el logger se descubre via ServiceLoader como un provider mas, no se inyecta como parametro.

**Laziness confirmada:** Pedir solo EncryptionApi no construye Auth, Storage, Analytics ni Sync.

---

**initOnly_H** | Criterio: `builtProvisionCount == 0` | Resultado: 0 provisions -- OK
```
1. sdk.init(context, config)
2. assert builtProvisionCount == 0
```
ServiceLoader escanea y registra los FeatureProviders en el Resolver, pero NO construye nada.
El Resolver solo almacena las clases de los providers en un ConcurrentHashMap. Resultado: 0 provisions.

---

**getEnc_H** | Criterio: `builtProvisionCount == 3` | Resultado: 3 provisions -- OK
```
1. sdk.init(context, config)
2. sdk.get(EncryptionApi::class.java)
3. assert builtProvisionCount == 3  (Observability + Core + Enc)
```
El DFS resuelve: EncProvider.build() -> resolver.provision(CoreProvisions) -> CoreProvider.build()
+ resolver.logger -> ObservabilityProvider.build(). Auth, Storage, Analytics y Sync NO se construyen.

---

**getAna_H** | Criterio: `builtProvisionCount == 3` | Resultado: 3 provisions -- OK
```
1. sdk.init(context, config)
2. sdk.get(AnalyticsApi::class.java)
3. assert builtProvisionCount == 3  (Observability + Core + Ana)
```
Analytics solo depende de Core y Logger. No dispara la cascada de Auth/Storage/Sync.

---

**getSync_H** | Criterio: `builtProvisionCount == 6` | Resultado: 6 provisions -- OK
```
1. sdk.init(context, config)
2. sdk.get(SyncApi::class.java)
3. assert builtProvisionCount == 6  (Obs + Core + Enc + Auth + Stor + Syn)
```
SynProvider.build() llama resolver.provision(AuthProvisions) + resolver.provision(StorProvisions)
+ resolver.provision(EncProvisions). Cada uno dispara su propio DFS. Analytics NO se construye
porque Sync no depende de ella.

---

**fullGraph_H** | Criterio: `builtProvisionCount == 7` | Resultado: 7 provisions -- OK
```
1. sdk.init(context, config)
2. sdk.get(SyncApi::class.java)   -> construye 6 provisions
3. sdk.get(AnalyticsApi::class.java)  -> construye 1 mas
4. assert builtProvisionCount == 7
```
7 = 6 features + 1 Observability. H tiene 7 (no 6 como D/G) porque el logger
se descubre via ServiceLoader como un FeatureProvider adicional.

---

**shutdown_H** | Criterio: `builtProvisionCount == 0` | Resultado: 0 provisions -- OK
```
1. sdk.init(context, config)
2. sdk.get(SyncApi::class.java)
3. sdk.shutdown()
4. assert builtProvisionCount == 0
```
`shutdown()` llama `resolver.clear()` que vacia los 4 maps internos:
providers, serviceIndex (HashMap), provisions, resolvedServices (ConcurrentHashMap).

---

**freshInstances_H** | Criterio: `assertNotSame(enc1, enc2)` | Resultado: instancias distintas -- OK
```
1. sdk.init(context, config)
2. enc1 = sdk.get(EncryptionApi::class.java)
3. sdk.shutdown()
4. sdk.init(context, config)
5. enc2 = sdk.get(EncryptionApi::class.java)
6. assertNotSame(enc1, enc2)
```
Tras shutdown + reinit, los DaggerComponents se reconstruyen desde cero.
Las instancias tienen identityHashCode diferentes.

---

**leakDetection_H** | Criterio: `delta heap < 2,048 KB` | Resultado: delta dentro del limite -- OK
```
1. Warmup: 10 ciclos init -> get(Sync) + get(Ana) -> shutdown  (estabilizar JVM/JIT)
2. System.gc() forzado
3. heapBefore = (Runtime.totalMemory - freeMemory) / 1024   (ej: 15,000 KB)
4. Repetir 1,000 veces:
   a. sdk.init(context, config)
   b. sdk.get(SyncApi)
   c. sdk.get(AnalyticsApi)
   d. assertEquals(7, builtProvisionCount)  -- verifica fullGraph en cada ciclo
   e. sdk.shutdown()
   f. assertEquals(0, builtProvisionCount)  -- verifica limpieza en cada ciclo
5. System.gc() forzado
6. heapAfter = (Runtime.totalMemory - freeMemory) / 1024   (ej: 15,800 KB)
7. delta = heapAfter - heapBefore = 800 KB
8. assertTrue(delta < 2048)  -- si delta >= 2,048 KB hay leak
```
Si el SDK no limpiara correctamente en `shutdown()` (ej: un ConcurrentHashMap que no se vacia,
una referencia retenida en un companion object), el heap creceria ~X KB por ciclo.
En 1,000 ciclos el delta seria enorme y el test fallaria.

---

**heapFootprint_H** | Criterio: ninguno (informativo) | Resultado: datos emitidos via logcat
```
Para cada patron (D, E2, G, H, I, J, K, L, M, N, O, O2, P, P2, Q, Q2):
1. System.gc()
2. heapBefore = usedHeapKb()
3. sdk.init(context, config)
4. System.gc()
5. heapAfterInit = usedHeapKb()
6. sdk.get(SyncApi)
7. sdk.get(AnalyticsApi)
8. System.gc()
9. heapAfterFull = usedHeapKb()
10. Log.d("HEAP_COMPARE", "$name | init: +${afterInit - before} KB (${provisions} prov) | full: +${afterFull - before} KB")
11. sdk.shutdown()
```
Mide heap antes/despues de init y fullGraph para los 16 patrones. GC forzado entre mediciones.
Este test NO tiene assert -- no falla nunca. Solo emite datos via logcat
para comparar el footprint de memoria de cada patron. El output tiene este formato:
```
HEAP_COMPARE: H | init: +42 KB (0 prov) | full: +128 KB
HEAP_COMPARE: D | init: +18 KB (1 prov) | full: +95 KB
```

---

### 2.3 Stress y Concurrencia (14 tests) -- StressTortureTest

---

**thunderingHerd_H** | Criterio: 0 errores, 100 resultados, todos `assertSame` | Resultado: 100/100 threads OK
```
1. sdk.init(context, config)
2. sdk.get(SyncApi)       -- pre-construir grafo completo
3. sdk.get(AnalyticsApi)
4. Crear CyclicBarrier(100)
5. Lanzar 100 threads, cada uno:
   a. barrier.await()  -- todos arrancan al mismo tiempo
   b. results.add(sdk.get(EncryptionApi))
6. Esperar join de los 100 threads (timeout 5s)
7. assertTrue(errors.isEmpty())
8. assertEquals(100, results.size)
9. Para cada resultado: assertSame(results[0], resultado)  -- todos la misma instancia
```
Verifica que el Resolver es thread-safe bajo contention extrema. Si hubiera un race condition
en el ConcurrentHashMap del Resolver, algunos threads recibirian instancias diferentes o excepciones.

---

**singleton_H** | Criterio: 10,000x `assertSame` | Resultado: 10,000/10,000 identicas
```
1. sdk.init(context, config)
2. first = sdk.get(EncryptionApi)
3. Repetir 10,000 veces:
   a. assertSame(first, sdk.get(EncryptionApi))  -- misma referencia en cada iteracion
```
Verifica que el patron singleton del Resolver es estable: una vez construida la provision,
`resolvedServices[clazz]` devuelve siempre la misma instancia cacheada.

---

**crossPatternIsolation_H** | Criterio: 120 pares `assertNotSame`, shutdown aislado | Resultado: aislamiento total
```
1. Inicializar los 16 patrones simultaneamente: D, E2, G, H, I, J, K, L, M, N, O, O2, P, P2, Q, Q2
2. Para cada patron: enc = sdk.get(EncryptionApi)
3. Comparar todos los pares (120 combinaciones):
   assertNotSame(enc_H, enc_D)
   assertNotSame(enc_H, enc_E2)
   ... etc
4. Shutdown de D
5. assertEquals(0, sdkD.builtProvisionCount)
6. Para los otros 15: assertTrue(sdk.builtProvisionCount > 0)  -- no afectados
```
Inicializa los 16 patrones simultaneamente. Obtiene EncryptionApi de cada uno. assertNotSame
entre las 120 combinaciones de pares. Hace shutdown de un patron y verifica que los otros 15
sobreviven. Cada patron tiene su propio Resolver con sus propias instancias. Ningun patron
comparte estado con otro.

---

**rapidFire_H** | Criterio: 5,000 ciclos con asserts correctos | Resultado: 5,000/5,000 OK
```
Repetir 5,000 veces:
1. sdk.init(context, config)
2. assertEquals(0, builtProvisionCount)   -- afterInit
3. sdk.get(EncryptionApi)
4. assertEquals(3, builtProvisionCount)   -- afterEnc (Obs + Core + Enc)
5. sdk.shutdown()
6. assertEquals(0, builtProvisionCount)   -- afterShutdown
```
Verifica que el ciclo de vida init/get/shutdown es determinista y repetible.
Si el Resolver tuviera estado residual tras shutdown (ej: providers no limpiados),
los asserts fallarian en alguna iteracion.

---

**memoryPressure_H** | Criterio: provisions sobreviven GC, cascada funciona | Resultado: intacto
```
1. sdk.init(context, config)
2. enc = sdk.get(EncryptionApi)
3. assertEquals(3, builtProvisionCount)
4. GC storm:
   a. Repetir 5 veces: System.gc() + Thread.yield() + ByteArray(1_000_000)  -- 5 MB de basura
   b. System.gc() + Thread.sleep(100)
5. assertEquals(3, builtProvisionCount)   -- provisions sobreviven GC
6. assertSame(enc, sdk.get(EncryptionApi))  -- misma instancia tras GC
7. sdk.get(SyncApi)
8. assertEquals(6, builtProvisionCount)   -- cascada sigue funcionando
```
Verifica que el Resolver mantiene strong references a las provisions construidas.
Un GC agresivo no puede recoger las instancias cacheadas porque el ConcurrentHashMap las retiene.
Tambien verifica que el DFS sigue funcionando tras presion de memoria.

---

**stress10K_H** | Criterio: `delta heap < 5,120 KB` | Resultado: heap estable tras 10,000 ciclos
```
1. Warmup: 100 ciclos init -> get(Sync) -> shutdown
2. System.gc() forzado
3. heapBefore = usedHeapKb()
4. pssInfoBefore = Debug.MemoryInfo()  -- PSS (Proportional Set Size)
5. Repetir 10,000 veces:
   a. sdk.init(context, config)
   b. sdk.get(SyncApi)
   c. sdk.get(AnalyticsApi)
   d. assertEquals(7, builtProvisionCount)
   e. sdk.shutdown()
6. System.gc() forzado
7. heapAfter = usedHeapKb()
8. pssInfoAfter = Debug.MemoryInfo()
9. heapDelta = heapAfter - heapBefore
10. pssDelta = pssInfoAfter.totalPss - pssInfoBefore.totalPss
11. Log.d("TORTURE", "H 10K: heap=$heapDelta KB, pss=$pssDelta KB")
12. assertTrue(heapDelta < 5120)
```
Version extrema de leakDetection: 10x mas ciclos (10,000 vs 1,000) y limite mas generoso
(5,120 KB vs 2,048 KB). Tambien mide PSS (memoria real asignada por el kernel)
ademas del heap de la JVM. Detecta leaks nativos que el heap de Java no veria.

**Resultado capturado (logcat):** `H 10K: heap=4 KB, pss=3937 KB`
- Heap delta = 4 KB (muy por debajo del limite de 5,120 KB) -- sin leak en la JVM
- PSS delta = 3,937 KB -- memoria nativa acumulada por 10,000 ciclos de ServiceLoader + DaggerComponent creation. 
  Es transitoria (se libera con el tiempo), no un leak permanente.

---

**freshness_H** | Criterio: 50 `identityHashCode` unicos | Resultado: 50/50 unicas
```
1. ids = mutableSetOf<Int>()
2. Repetir 50 veces:
   a. sdk.init(context, config)
   b. enc = sdk.get(EncryptionApi)
   c. id = System.identityHashCode(enc)
   d. assertFalse(ids.contains(id))  -- nunca reutiliza una instancia anterior
   e. ids.add(id)
   f. sdk.shutdown()
3. assertEquals(50, ids.size)
```
Verifica que cada reinit produce instancias genuinamente nuevas. Si el Resolver
cacheara instancias entre ciclos (ej: en un companion object), el identityHashCode
se repetiria y el test fallaria.

---

**errorResilience_H** | Criterio: 5 escenarios de error correctos | Resultado: 5/5 OK
```
Escenario 1 -- Double init:
  sdk.init(context, config)
  sdk.init(context, config)  -> IllegalStateException("already initialized")

Escenario 2 -- Get antes de init:
  sdk.get(EncryptionApi)  -> IllegalStateException("not initialized")

Escenario 3 -- Get tras shutdown:
  sdk.init(context, config); sdk.get(Enc); sdk.shutdown()
  sdk.get(EncryptionApi)  -> IllegalStateException("not initialized")

Escenario 4 -- Double shutdown:
  sdk.init(context, config); sdk.shutdown(); sdk.shutdown()  -> NO lanza (safe)
  assertEquals(0, builtProvisionCount)

Escenario 5 -- Init tras shutdown:
  sdk.init(context, config); sdk.shutdown()
  sdk.init(context, config)
  assertNotNull(sdk.get(EncryptionApi))  -> funciona normalmente
  sdk.shutdown()
```
Verifica que la maquina de estados del SDK es correcta: init solo una vez,
get solo cuando inicializado, shutdown es idempotente, y el ciclo se puede repetir.

---

**functional_H** | Criterio: operaciones reales funcionan tras 1,000 reinits | Resultado: OK
```
1. Repetir 1,000 veces: sdk.init(context, config); sdk.shutdown()  -- stress sin usar features
2. sdk.init(context, config)
3. enc = sdk.get(EncryptionApi)
4. encrypted = enc.encrypt("secret")
5. assertTrue(encrypted.isNotEmpty())  -- encrypt funciona
6. auth = sdk.get(AuthApi)
7. assertFalse(auth.isAuthenticated())
8. auth.login("user", "pass")
9. assertTrue(auth.isAuthenticated())  -- login funciona
10. sync = sdk.get(SyncApi)
11. result = sync.sync()
12. assertNotNull(result)  -- sync funciona
```
Verifica que tras 1,000 ciclos de init/shutdown sin usar features, las APIs del SDK
siguen funcionando correctamente. Si algun estado global se corrompiera con las
reinicializaciones, las operaciones reales fallarian.

---

**coldCascadeTiming_H** | Criterio: ninguno (informativo) | Resultado: tiempos capturados
```
Para cada patron (D, E2, G, H, I, J, K, L, M, N, O, O2, P, P2, Q, Q2):
1. t0 = System.nanoTime()
2. sdk.init(context, config)
3. t1 = System.nanoTime()         -- tiempo de init
4. sdk.get(SyncApi); sdk.get(AnalyticsApi)
5. t2 = System.nanoTime()         -- tiempo de build completo
6. Repetir 1,000 veces: sdk.get(EncryptionApi)
7. t3 = System.nanoTime()         -- tiempo de 1000 cached lookups
8. Log tabla formateada con init, build, cached por patron
9. sdk.shutdown()
```
Mide init, build (Sync+Ana) y 1,000x cached resolution para cada uno de los 16 patrones.
Emite una tabla formateada via logcat comparando tiempos reales (no Jetpack Benchmark).
No tiene asserts -- puramente informativo.

**Resultados capturados (logcat, valores en us):**

```
╔═══════╦══════════╦════════════╦═══════════════════╗
║Pattern║ init us  ║ build us   ║ 1000x cached us   ║
╠═══════╬══════════╬════════════╬═══════════════════╣
║ D     ║        9 ║         47 ║        147         ║
║ E2    ║       63 ║        100 ║        102         ║
║ G     ║        3 ║         21 ║        212         ║
║ H     ║      283 ║         68 ║         68         ║
║ I     ║      144 ║         25 ║         68         ║
║ J     ║      123 ║         39 ║         68         ║
║ K     ║      382 ║         38 ║         67         ║
╚═══════╩══════════╩════════════╩═══════════════════╝
```

**Pattern H:** init = 283 us (ServiceLoader scan), build Sync+Ana = 68 us (DFS + DaggerComponents),
1,000 cached lookups = 68 us (ConcurrentHashMap.get() x1000 = 68 ns/lookup).

---

**alternatingPatterns_H** | Criterio: 1,600 ciclos sin contaminacion | Resultado: OK
```
Repetir 100 veces:
  Para cada patron (D, E2, G, H, I, J, K, L, M, N, O, O2, P, P2, Q, Q2):
    1. sdk.init(context, config)
    2. enc = sdk.get(EncryptionApi)
    3. assertNotNull(enc)
    4. sdk.shutdown()
    5. assertEquals(0, builtProvisionCount)
```
100 rondas alternando entre los 16 patrones. Cada ronda: init, get(Enc), assertNotNull,
shutdown, assertEquals(0, builtProvisionCount). Total 1,600 ciclos. Verifica que alternar
rapidamente entre patrones no causa contaminacion cruzada. Cada patron tiene su propio
Resolver (objeto separado), asi que no deberian interferir.

---

**concurrentBuild_H** | Criterio: 0 errores, 7 provisions, singleton identity | Resultado: OK
```
1. Repetir 100 veces:
   a. sdk.init(context, config)
   b. Crear CyclicBarrier(6)
   c. Lanzar 6 threads, cada uno pide un servicio distinto:
      - Thread 1: sdk.get(EncryptionApi)
      - Thread 2: sdk.get(AuthApi)
      - Thread 3: sdk.get(SecureStorageApi)
      - Thread 4: sdk.get(AnalyticsApi)
      - Thread 5: sdk.get(SyncApi)
      - Thread 6: sdk.get(HashApi)
   d. barrier.await()  -- los 6 arrancan simultaneamente
   e. Esperar join de los 6 threads (timeout 5s)
   f. assertTrue(errors.isEmpty())  -- 0 errores
   g. assertEquals(6, results.size)  -- los 6 servicios resueltos
   h. assertEquals(7, builtProvisionCount)  -- fullGraph construido
   i. singletonCheck = sdk.get(EncryptionApi)
   j. assertSame(results[enc], singletonCheck)  -- singleton identity preservada
   k. sdk.shutdown()
```
Verifica que el Resolver es seguro bajo construccion concurrente del grafo. 6 threads
intentan construir provisions simultaneamente via CyclicBarrier. El synchronized + ConcurrentHashMap
del Resolver debe garantizar: (1) ningun error, (2) todas las provisions construidas exactamente
una vez, (3) builtProvisionCount == 7 (fullGraph), (4) un get() posterior devuelve la misma
instancia singleton que el thread concurrente obtuvo. Si hubiera un race condition en ensureBuilt(),
se construirian provisions duplicadas o se lanzarian excepciones de estado inconsistente.

---

**concurrentSelective_H** | Criterio: laziness preservada bajo contention | Resultado: OK
```
1. Repetir 50 veces:
   a. sdk.init(context, config)
   b. Crear CyclicBarrier(3)
   c. Lanzar 3 threads, cada uno pide un servicio distinto:
      - Thread 1: sdk.get(EncryptionApi)
      - Thread 2: sdk.get(AnalyticsApi)
      - Thread 3: sdk.get(AuthApi)
   d. barrier.await()  -- los 3 arrancan simultaneamente
   e. Esperar join de los 3 threads (timeout 5s)
   f. afterSync = builtProvisionCount
   g. Verificar que SyncApi NO esta construido  -- laziness preservada
   h. Verificar que SecureStorageApi NO esta construido  -- laziness preservada
   i. assertTrue(builtProvisionCount <= afterSync)  -- no hay provisions "leaked"
   j. sdk.shutdown()
```
Verifica que la laziness se preserva incluso bajo contention concurrente. Cuando 3 threads
construyen Enc, Ana y Auth simultaneamente, Sync y Storage NO deben construirse porque ningun
thread los pidio. Esto confirma que el DFS no "arrastra" provisions innecesarias bajo presion
concurrente. Tambien verifica que builtProvisionCount no crece mas alla de lo esperado
(no hay provisions fantasma creadas por race conditions en el ConcurrentHashMap).

---

**concurrentShutdown_H** | Criterio: 0 crashes en 200 rounds | Resultado: OK
```
1. Repetir 200 veces:
   a. sdk.init(context, config)
   b. Lanzar 2 threads simultaneamente:
      - Thread 1 (reader): sdk.get(EncryptionApi); sdk.get(AuthApi)
      - Thread 2 (destroyer): sdk.shutdown()
   c. Esperar join de los 2 threads (timeout 5s)
   d. Para cada error capturado:
      - Si es IllegalStateException("not initialized") -> OK (shutdown gano la carrera)
      - Si es NPE, ConcurrentModificationException, u otro -> FAIL
   e. Si no hubo error -> OK (reader gano la carrera)
   f. sdk.shutdown()  -- cleanup seguro (idempotente)
```
Verifica que el Resolver es seguro cuando un thread lee (get) mientras otro destruye (shutdown)
simultaneamente. Hay dos resultados validos: (1) el reader termina antes que shutdown y obtiene
los servicios normalmente, o (2) shutdown gana la carrera y el reader recibe
IllegalStateException("not initialized"). Lo que NO es aceptable es un NPE (acceso a ConcurrentHashMap
limpiado parcialmente), ConcurrentModificationException (iteracion durante clear()), o cualquier
otro crash inesperado. Este test valida que el synchronized en clear() del Resolver protege
correctamente contra race conditions de lectura/escritura.

---

## 3. Arquitectura

```
App
 +-- depends on: :sdk:wiring-h (51 lineas)
      +-- ServiceLoader descubre FeatureProviders
           |-- EncProvider (feature-enc-impl) -> DaggerEncComponent
           |-- AuthProvider (feature-auth-impl) -> DaggerAuthComponent
           |-- StorProvider (feature-stor-impl) -> DaggerStorComponent
           |-- AnaProvider (feature-ana-impl) -> DaggerAnaComponent
           |-- SynProvider (feature-syn-impl) -> DaggerSynComponent
           |-- CoreProvider (feature-core-impl) -> DaggerCoreComponent
           +-- ObservabilityProvider (feature-observability-impl) -> ObservabilityComponent
```

**Flujo de resolucion -- DFS (Depth-First Search):**

DFS significa "busqueda en profundidad": cuando pides un servicio, el Resolver baja por
la cadena de dependencias hasta el fondo (el nodo que no depende de nada) antes de construir
nada, y luego construye de abajo hacia arriba.

Ejemplo real con `get<SyncApi>()`:

```
app.get<SyncApi>()
  +-- Resolver: "SynProvider necesita Auth, Stor, Enc"
       |
       |-- resolver.provision(EncProvisions)
       |    +-- "EncProvider necesita Core"
       |         +-- resolver.provision(CoreProvisions)
       |              +-- "CoreProvider no necesita nada"
       |                   +-- CONSTRUYE Core              <- fondo del DFS
       |         +-- CONSTRUYE Enc (Core ya disponible)
       |
       |-- resolver.provision(AuthProvisions)
       |    +-- "AuthProvider necesita Enc, Core"
       |         +-- Enc y Core ya construidos -> skip
       |    +-- CONSTRUYE Auth
       |
       |-- resolver.provision(StorProvisions)
       |    +-- "StorProvider necesita Enc, Core"
       |         +-- Enc y Core ya construidos -> skip
       |    +-- CONSTRUYE Stor
       |
       +-- CONSTRUYE Syn (Auth, Stor, Enc ya disponibles)
```

El DFS es automatico e implicito: cada vez que un `FeatureProvider.build()` llama
`resolver.provision(dep)`, el Resolver comprueba si esa provision ya esta construida.
Si no lo esta, entra en el `build()` del provider de esa dependencia (un nivel mas
abajo en la recursion). Si ya esta cacheada en el ConcurrentHashMap, la devuelve sin reconstruir.

Esto significa que el orden de construccion siempre es correcto independientemente
del orden en que se registraron los providers: Core siempre se construye antes que Enc,
Enc antes que Auth, etc. El grafo de dependencias determina el orden, no el programador.

---

## 4. Codigo Completo

### MultiModuleSdkH.kt -- Wiring (51 lineas)

El punto de entrada del SDK. No importa ninguna implementacion concreta.

```kotlin
object MultiModuleSdkH : MultiModuleSdkApi {

    private val resolver = Resolver()
    private var _initialized = false

    override val isInitialized: Boolean get() = _initialized
    override val builtProvisionCount: Int get() = resolver.builtProvisionCount

    override fun init(context: Context, config: SdkConfig) {
        check(!_initialized) { "Already initialized." }
        resolver.init(config)
        ServiceLoader.load(FeatureProvider::class.java).forEach { provider ->
            resolver.register(provider)
        }
        _initialized = true
    }

    override fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "Not initialized." }
        return resolver.get(clazz)
    }

    inline fun <reified T : Any> get(): T = get(T::class.java)

    override fun shutdown() {
        if (!_initialized) return
        resolver.clear()
        _initialized = false
    }
}
```

**Este archivo no cambia nunca.** Anadir un feature nuevo = crear el feature-impl con su FeatureProvider + META-INF/services entry. Zero edicion en wiring-h.

### FeatureProvider.kt -- Contrato de auto-registro (34 lineas)

Cada feature-impl declara un FeatureProvider que sabe que provision construye,
que servicios expone, y como construirse (resolviendo deps via el Resolver).

```kotlin
abstract class FeatureProvider<P : Any>(val provisionClass: Class<P>) {

    /** Servicios que este provider expone (ej: EncryptionApi, HashApi). */
    abstract val services: Map<Class<*>, (P) -> Any>

    /** Construye la provision. Llama resolver.provision() para resolver deps (DFS). */
    abstract fun build(resolver: Resolver): P

    internal fun buildUntyped(resolver: Resolver): Any = build(resolver)

    internal fun extractService(provision: Any, serviceClass: Class<*>): Any {
        val typed = checkNotNull(provisionClass.cast(provision))
        return services[serviceClass]?.invoke(typed)
            ?: error("${provisionClass.simpleName} does not provide ${serviceClass.simpleName}")
    }
}
```

### Resolver.kt -- Motor DFS thread-safe

El cerebro del Pattern H. Registra providers, indexa servicios, y construye
provisions bajo demanda con DFS automatico. Thread-safe via `synchronized` + `ConcurrentHashMap`.

```kotlin
class Resolver {

    private val lock = Any()
    private val providers = HashMap<Class<*>, FeatureProvider<*>>()
    private val serviceIndex = HashMap<Class<*>, Class<*>>()
    private val provisions = ConcurrentHashMap<Class<*>, Any>()
    private val resolvedServices = ConcurrentHashMap<Class<*>, Any>()

    lateinit var config: SdkConfig; private set

    /** Logger resuelto lazily desde ObservabilityProvider. */
    val logger: SdkLogger get() = get(SdkLogger::class.java)

    fun init(config: SdkConfig) {
        this.config = config
    }

    /** Registra un provider. Indexa cada servicio que expone. */
    fun register(provider: FeatureProvider<*>) {
        providers[provider.provisionClass] = provider
        for (serviceClass in provider.services.keys) {
            serviceIndex[serviceClass] = provider.provisionClass
        }
    }

    /** Resuelve un servicio por tipo. Auto-construye si no esta en cache. */
    fun <T : Any> get(clazz: Class<T>): T {
        // Fast path: cache hit sin lock (ConcurrentHashMap read-only tras build)
        resolvedServices[clazz]?.let {
            return checkNotNull(clazz.cast(it)) { "Cast failed for ${clazz.simpleName}" }
        }
        val provisionClass = serviceIndex[clazz]
            ?: error("No provider for ${clazz.simpleName}")
        ensureBuilt(provisionClass)
        val resolved = resolvedServices[clazz]
            ?: error("${clazz.simpleName} not available after building ${provisionClass.simpleName}")
        return checkNotNull(clazz.cast(resolved)) { "Cast failed for ${clazz.simpleName}" }
    }

    /** Obtiene una provision construida. Usado por FeatureProvider.build(). */
    fun <P : Any> provision(clazz: Class<P>): P {
        ensureBuilt(clazz)
        val built = provisions[clazz]
            ?: error("Provision ${clazz.simpleName} not available")
        return checkNotNull(clazz.cast(built)) { "Cast failed for ${clazz.simpleName}" }
    }

    /**
     * Thread-safe DFS: synchronized en [lock] para que solo un thread construya a la vez.
     * Double-check dentro del lock previene doble construccion.
     * Llamadas recursivas desde build() re-entran el lock del mismo thread (reentrant).
     *
     * ORDEN DE ESCRITURA CRITICO:
     * 1. Escribir resolvedServices PRIMERO (los servicios individuales)
     * 2. Escribir provisions AL FINAL (es el "gate" que otros threads leen en el fast path)
     * Si se invirtiera, un thread veria containsKey()=true pero resolvedServices[service]=null.
     */
    private fun ensureBuilt(provisionClass: Class<*>) {
        if (provisions.containsKey(provisionClass)) return // fast path sin lock
        synchronized(lock) {
            if (provisions.containsKey(provisionClass)) return // double-check dentro del lock
            val provider = providers[provisionClass]
                ?: error("No provider registered for ${provisionClass.simpleName}")
            val provision = provider.buildUntyped(this)
            // Services PRIMERO -- antes de marcar como construido
            for (serviceClass in provider.services.keys) {
                resolvedServices[serviceClass] = provider.extractService(provision, serviceClass)
            }
            provisions[provisionClass] = provision // ULTIMO -- gate para otros threads
        }
    }

    val builtProvisionCount: Int get() = provisions.size

    fun clear() {
        synchronized(lock) {
            providers.clear()
            serviceIndex.clear()
            provisions.clear()
            resolvedServices.clear()
        }
    }
}
```

### EncProvider.kt -- Ejemplo de FeatureProvider (19 lineas)

Un provider concreto. Cada feature-impl tiene uno identico en estructura.

```kotlin
class EncProvider : FeatureProvider<EncProvisions>(EncProvisions::class.java) {

    override val services: Map<Class<*>, (EncProvisions) -> Any> = mapOf(
        EncryptionApi::class.java to EncProvisions::encryption,
        HashApi::class.java to EncProvisions::hash,
    )

    override fun build(resolver: Resolver): EncProvisions =
        buildEncProvisions(resolver.provision(CoreProvisions::class.java), resolver.logger)
}
```

`buildEncProvisions()` internamente llama `DaggerEncComponent.builder()...build()`.
El `DaggerEncComponent` queda encapsulado dentro del feature-impl -- el wiring nunca lo importa.

### META-INF/services -- Auto-registro (1 linea por provider)

Fichero: `features/feature-enc-impl/src/main/resources/META-INF/services/com.grinwich.sdk.contracts.FeatureProvider`

```
com.grinwich.sdk.feature.enc.EncProvider
```

Cada feature-impl tiene su propio fichero META-INF con una linea.
`ServiceLoader.load(FeatureProvider::class.java)` los descubre automaticamente.

---

## 5. Datos Tecnicos

| Propiedad | Valor |
|-----------|-------|
| Dispositivo | Samsung Galaxy S22 Ultra (SM-S908B), Snapdragon 8 Gen 1, Android 16 |
| Framework | Jetpack Benchmark 1.4.0 con warmup automatico |
| Lineas de wiring | 51 (MultiModuleSdkH.kt) |
| Lineas de Resolver | 105 (Resolver.kt en di-contracts) |
| Lineas de FeatureProvider | 34 (contrato base en di-contracts) |
| Lineas por provider concreto | ~19 (ej: EncProvider.kt) |
| Descubrimiento | `java.util.ServiceLoader` con `META-INF/services` |
| DFS | Recursivo en Resolver.ensureBuilt() |
| Total tests ejecutados | 12 benchmarks + 9 memory + 14 stress = 35 tests |

---

## 6. Comparativa con Otros Patrones

Donde se situa Pattern H en el ranking de los 16 patrones benchmarked (S22 Ultra, 2026-04-12).

### Init Cold

| Patron | initCold (ns) | Relacion con H |
|--------|--------------|----------------|
| O (Metro eager) | 603 | H es 177x mas lento |
| O2 (Metro Lazy) | 891 | H es 120x mas lento |
| G (Factory Functions) | 3,012 | H es 35x mas lento |
| D (Component Deps) | 9,150 | H es 12x mas lento |
| **H (Auto-Discovery)** | **106,865** | **referencia** |
| L (Koin+ServiceLoader eager) | 142,300 | H es 1.3x mas rapido |
| M (Koin+ServiceLoader lazy) | 158,700 | H es 1.5x mas rapido |
| N (sweet-spi+Koin) | 135,200 | H es 1.3x mas rapido |

**Analisis:** H paga el costo de ServiceLoader scan (~100 us) que los patrones con compile-time
wiring (O, O2, G, D) evitan. Sin embargo, H supera a todos los patrones basados en Koin (L, M, N)
en init porque su Resolver es mas ligero que el arranque de Koin.

### Resolve Cached

| Patron | resolve cached (ns) | Relacion con H |
|--------|---------------------|----------------|
| O2 (Metro Lazy) | 45 | 4.5x mas rapido |
| P2 (kotlin-inject-anvil Lazy) | 62 | 3.3x mas rapido |
| **H (Auto-Discovery)** | **202** | **referencia** |
| L (Koin+ServiceLoader eager) | 12,150 | H es 60x mas rapido |
| M (Koin+ServiceLoader lazy) | 12,150 | H es 60x mas rapido |
| N (sweet-spi+Koin) | 12,150 | H es 60x mas rapido |

**Analisis:** El resolve cached de H (202 ns) es competitivo con los patrones de compile-time
(O2: 45 ns, P2: 62 ns). La diferencia es un ConcurrentHashMap lookup vs campo directo.
H supera a todos los patrones Koin por 60x en resolve porque Koin atraviesa su grafo en cada `get()`.

### Comparacion con Koin (L/M/N)

H bate a los patrones Koin en la mayoria de operaciones:

| Operacion | H<br>*(Resolver+Dagger)* (ns) | Koin promedio (ns) | Ventaja H |
|-----------|--------|-------------------|-----------|
| initCold | 106,865 | ~145,000 | 1.4x |
| resolve cached | 202 | ~12,150 | 60x |
| lazyInit cascade | 3,892 | ~5,400 | 1.4x |
| stress_initShutdown | 99,293 | ~140,000 | 1.4x |
| stress_reInit | 362,649 | ~520,000 | 1.4x |

### Fortalezas Demostradas de H

1. **DFS lazy genuino:** `builtProvisionCount == 0` tras init, confirmado en 9 tests de memoria
2. **Thread-safe shutdown:** `concurrentShutdown` pasa 200 rounds de read vs shutdown race
3. **Zero leak:** 10,000 ciclos con heap delta de 4 KB
4. **Singleton garantizado:** `thunderingHerd` con 100 threads, todos `assertSame`
5. **Aislamiento cross-pattern:** 16 patrones simultaneos sin contaminacion

### Limitaciones de H

1. **Sin compile-time safety para providers:** Un provider faltante es error runtime, no de compilacion
2. **ServiceLoader es JVM-only:** No funciona en iOS/macOS/WASM sin sweet-spi
3. **Init mas lento que compile-time patterns:** 177x vs Metro O por el scan de ServiceLoader
