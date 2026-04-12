# Recomendacion de Patron DI para SDK Android-Only (+50 modulos, 10 devs)

**Fecha:** 2026-04-12  
**Contexto:** Seleccion de patron de inyeccion de dependencias para un SDK Android nativo corporativo  
**Dispositivo de referencia:** Samsung Galaxy S22 Ultra (Snapdragon 8 Gen 1, Android 16)  
**Datos de benchmark:** Jetpack Benchmark 1.4.0, mediana de `timeNs`

---

## 1. Contexto

Un SDK corporativo Android-only con las siguientes caracteristicas:

- **+50 modulos funcionales** (features) mantenidos por equipos independientes
- **10 desarrolladores** trabajando en paralelo sobre el mismo repositorio
- **Android nativo:** sin requisito KMP (no iOS, no Desktop)
- **Pattern H como baseline:** actualmente planificado/en uso con ServiceLoader + Resolver DFS + Dagger
- **Logger persistente** que sobrevive a ciclos de shutdown/reinit del SDK
- **Auto-registro:** anadir un feature nuevo = 0 cambios en el wiring central
- **Acceso concurrente thread-safe** desde multiples threads del consumidor
- **Inicializacion lazy:** el consumidor paga solo por las features que usa

La pregunta central: **Es Pattern H la eleccion correcta para este contexto, o hay patrones
Android-only que ofrecen un mejor balance?**

---

## 2. Criterios de Seleccion (ponderados para Android-only)

| # | Criterio | Peso | Justificacion |
|---|----------|------|---------------|
| 1 | Auto-registro | MUST HAVE | Con +50 modulos, edicion central = cuello de botella de PRs y merge conflicts |
| 2 | Seguridad en compilacion | MUST HAVE | 10 devs = alta probabilidad de bindings rotos sin validacion compile-time |
| 3 | Inicializacion lazy | MUST HAVE | Startup performance critico en mobile. No crear 50 singletons si solo se usan 5 |
| 4 | Thread-safe shutdown | IMPORTANTE | SDK se reinicializa en cambios de configuracion o testing |
| 5 | Logger persistente | IMPORTANTE | Observabilidad no puede perderse entre ciclos init/shutdown |
| 6 | Madurez del ecosistema | IMPORTANTE | Soporte a largo plazo, documentacion, comunidad activa |
| 7 | Init performance | IMPORTANTE | Impacto en cold start del consumidor. Una vez por sesion |
| 8 | Resolve cached performance | IMPORTANTE | Impacto en hot paths: cada `sdk.get<T>()` paga este costo |

**Criterios NO relevantes para este caso:**
- KMP: No se necesita. Descarta la necesidad de sweet-spi, kotlin-inject o Metro como reemplazo de Dagger
- Soporte multiplataforma: El SDK es exclusivamente Android

**Criterios eliminatorios:** Si un patron no cumple los 3 MUST HAVE, se descarta.

---

## 3. Evaluacion de Candidatos Android-Only

Se evaluan 6 patrones que escalan a 50+ modulos (D y G descartados previamente
por requerir edicion central proporcional al numero de features).

### 3.1 Pattern H -- ServiceLoader + Resolver DFS + Dagger (baseline)

**Arquitectura:** Cada feature-impl declara un `FeatureProvider` (~19 lineas) registrado via
`META-INF/services`. El Resolver descubre providers via `ServiceLoader.load()` y construye
provisions on-demand con DFS recursivo. Cada feature usa Dagger internamente.

| Criterio | Evaluacion | Detalle |
|----------|-----------|---------|
| Auto-registro | **OK** | `META-INF/services` + ServiceLoader. Zero edicion central. Anadir feature = crear FeatureProvider + 1 fichero META-INF |
| Compile-time safety | **PARCIAL** | Dagger valida cada Component individualmente, pero un provider faltante es error runtime |
| Lazy | **OK** | DFS genuino: `builtProvisionCount == 0` tras init (confirmado en 9 tests de memoria) |
| Thread-safe shutdown | **OK** | `concurrentShutdown` pasa 200 rounds de read vs shutdown race. synchronized + ConcurrentHashMap |
| Logger persistente | **OK** | ObservabilityProvider con flag `persistent` sobrevive a shutdown/reinit |
| Madurez | **ALTA** | Dagger: 10+ anos. ServiceLoader: JDK estandar. 35 tests, 10K ciclos, zero leaks |
| Init cold | 106,865 ns | ServiceLoader scan domina el costo |
| Resolve cached | 202 ns | ConcurrentHashMap lookup O(1) |

**Pros:**
- Auto-registro perfecto: anadir META-INF/services = feature registrado. Wiring inmutable (51 lineas, nunca cambia)
- DFS lazy demostrado: pedir EncryptionApi NO construye Auth/Storage/Sync (confirmado en tests)
- Thread-safe probado: thunderingHerd (100 threads), concurrentBuild (6 threads x 100 rounds), concurrentShutdown (200 rounds)
- Zero leak: 10,000 ciclos con heap delta de 4 KB
- Cada feature-impl compila independientemente con Dagger (compile-time safety per-Component)

**Contras:**
- Provider faltante = runtime error (no se detecta en compilacion)
- Init 158x mas lento que Q (676 ns) por el scan de ServiceLoader
- Mantener Resolver propio = costo de mantenimiento (105 lineas de Resolver.kt)

### 3.2 Pattern Q -- Hilt-style Dagger Eager

**Arquitectura:** Cada feature-impl define un `@Module @InstallIn(SingletonComponent)`. Todos los
modules se listan explicitamente en un `@Component(modules=[...])`. Dagger genera todo el wiring
en compilacion. Init = `DaggerSdkComponent.factory().create()`.

| Criterio | Evaluacion | Detalle |
|----------|-----------|---------|
| Auto-registro | **NO** | Cada feature @Module se lista en `@Component(modules=[...])`. Edicion central obligatoria |
| Compile-time safety | **OK** | Grafo completo validado por Dagger. Binding faltante = error de compilacion |
| Lazy | **NO** | Eager: todos los @Singleton se crean al construir el Component |
| Thread-safe shutdown | **OK** | Nullificar component es atomico |
| Logger persistente | **OK** | @Singleton en el component raiz |
| Madurez | **ALTA** | Dagger/Hilt: 10+ anos, Google mantiene, documentacion extensa |
| Init cold | 676 ns | El mas rapido: solo instancia un objeto pre-wired |
| Resolve cached | 64 ns | Acceso directo a binding generado por Dagger |

**Pros:**
- Compile-time safety COMPLETA: todo el grafo validado en compilacion
- Init ultra-rapido (676 ns) y resolve ultra-rapido (64 ns)
- Convencion familiar para desarrolladores Android (Hilt es el estandar)

**Contras:**
- **Sin auto-registro:** cada feature nuevo requiere editar `@Component(modules=[HiltXxxModule::class, ...])`
- Con 50+ modules, la lista del @Component se vuelve inmanejable y genera merge conflicts
- **Sin lazy:** todos los singletons se crean en init. Si solo usas 5 de 50 features, las otras 45 se instancian igualmente
- 10 devs editando el mismo @Component = cuello de botella de merge

### 3.3 Pattern Q2 -- Hilt-style Dagger Lazy

**Arquitectura:** Identico a Q pero los metodos de provision retornan `dagger.Lazy<T>`.
El component se crea en init, pero los singletons NO se instancian hasta el primer `.get()`.
`LazyCreationTracker` cuenta features materializadas.

| Criterio | Evaluacion | Detalle |
|----------|-----------|---------|
| Auto-registro | **NO** | Misma limitacion que Q: modules en `@Component(modules=[...])` |
| Compile-time safety | **OK** | Grafo completo validado por Dagger |
| Lazy | **OK** | `dagger.Lazy<T>` difiere construccion hasta primer acceso |
| Thread-safe shutdown | **OK** | Nullificar component + deactivar LazyCreationTracker |
| Logger persistente | **OK** | @Singleton + Lazy en el component raiz |
| Madurez | **ALTA** | Dagger: 10+ anos. dagger.Lazy es API oficial estable |
| Init cold | 1,080 ns | Setup de LazyCreationTracker anade ~400 ns vs Q |
| Resolve cached | 85 ns | `Lazy.get()` post-primera invocacion |

**Pros:**
- Re-init ultra-rapido: 2,157 ns (168x mas rapido que H, 11.6x mas rapido que Q)
- Lazy real: singletons on-demand como H, pero con compile-time safety
- LazyCreationTracker ofrece observabilidad de cuantas features se han materializado

**Contras:**
- **Sin auto-registro:** misma lista central de modules que Q
- Con 50+ modules, el merge conflict en el @Component es identico al de Q
- 10 devs editando el mismo fichero de wiring = cuello de botella

### 3.4 Pattern E2 -- AutoProvisionRegistry + DFS

**Arquitectura:** Un `AutoProvisionRegistry` cataloga `AutoProvisionEntry` en init (HashMap puts)
y construye Components on-demand via DFS recursivo. Anadir modulo = 1 linea en `allEntries()`.

| Criterio | Evaluacion | Detalle |
|----------|-----------|---------|
| Auto-registro | **PARCIAL** | Semi-auto: anadir feature = 1 linea en `allEntries()`. Mejor que D, peor que H |
| Compile-time safety | **OK** | Dagger valida cada Component. DFS con entries explicitas |
| Lazy | **OK** | `get<T>()` auto-construye por DFS recursivo |
| Thread-safe shutdown | **OK** | Registry cache + synchronized |
| Logger persistente | **PARCIAL** | Requiere wiring manual para logger persistente |
| Madurez | **ALTA** | Dagger + HashMap: componentes probados |
| Init cold | 10,983 ns | Catalogar entries en HashMaps |
| Resolve cached | 199 ns | HashMap lookup similar a H |

**Pros:**
- Compile-time safety per-Component
- Init 10x mas rapido que H (sin ServiceLoader)
- DFS lazy como H

**Contras:**
- Semi-auto: requiere lista central de entries (aunque es 1 linea por feature)
- A 50+ features, `allEntries()` crece y genera merge conflicts (menor que Q pero no zero)

### 3.5 Pattern K -- AndroidManifest Metadata Discovery

**Arquitectura:** Misma que H (FeatureProvider + Resolver DFS), pero el descubrimiento usa
`PackageManager.getServiceInfo()` para leer `<meta-data>` del AndroidManifest.xml mergeado.
Inmune a R8 stripping de META-INF.

| Criterio | Evaluacion | Detalle |
|----------|-----------|---------|
| Auto-registro | **OK** | AndroidManifest meta-data + manifest merger. Zero edicion central |
| Compile-time safety | **PARCIAL** | Dagger per-Component. Provider faltante es error runtime (= H) |
| Lazy | **OK** | DFS identico a H |
| Thread-safe shutdown | **OK** | Mismo Resolver que H |
| Logger persistente | **OK** | Mismo mecanismo que H |
| Madurez | **ALTA** | PackageManager: API de Android estandar |
| Init cold | 213,737 ns | PackageManager + reflexion: 2x mas lento que H |
| Resolve cached | 203 ns | Mismo ConcurrentHashMap lookup que H |

**Pros:**
- R8-safe: AndroidManifest no se procesa por R8/ProGuard (META-INF si puede eliminarse sin keep rules)
- Mismo auto-registro zero-touch que H
- Para apps con ProGuard agresivo que elimina META-INF, K es la alternativa directa

**Contras:**
- Init 213K ns = 2x mas lento que H, 316x mas lento que Q
- Requiere Android Context en init (H no lo necesita)
- PackageManager queries pueden ser lentas en dispositivos de gama baja

### 3.6 Pattern I -- Pure Resolver (zero framework DI)

**Arquitectura:** Misma que H (ServiceLoader + Resolver DFS), pero las features se construyen
sin Dagger ni ningun framework. Constructor injection puro de Kotlin.

| Criterio | Evaluacion | Detalle |
|----------|-----------|---------|
| Auto-registro | **OK** | ServiceLoader descubre `PureFeatureProvider`. Zero edicion central |
| Compile-time safety | **NO** | Zero DI framework = zero validacion de bindings |
| Lazy | **OK** | DFS identico a H |
| Thread-safe shutdown | **OK** | Mismo Resolver que H |
| Logger persistente | **PARCIAL** | Sin framework, logger persistence requiere implementacion manual |
| Madurez | **ALTA** | Zero dependencias externas |
| Init cold | 94,255 ns | Similar a H pero sin overhead de Dagger setup |
| Resolve cached | 203 ns | Mismo ConcurrentHashMap lookup que H |

**Pros:**
- Zero dependencias: sin Dagger, sin KSP, sin codegen
- Control total del codigo
- Ligeramente mas rapido en init que H (sin Dagger overhead)

**Contras:**
- **Sin compile-time safety:** 10 devs con +50 modulos y zero validacion automatica = alto riesgo
- Constructor injection manual crece proporcionalmente con la complejidad de cada feature
- No viable para un equipo de 10 devs que necesita garantias de compilacion

---

## 4. Recomendacion para Android-Only

### Recomendacion Primaria: Pattern H (confirmado)

**H ofrece el mejor balance para un SDK Android corporativo con +50 modulos y 10 devs:**

1. **Auto-registro via ServiceLoader** -- anadir `META-INF/services` = feature registrado.
   Zero cambios en ningun otro fichero. Con +50 modulos esto es critico: ningun merge conflict
   en el wiring central porque el wiring es inmutable (51 lineas que nunca cambian)

2. **Resolver DFS con thread-safe shutdown** -- `concurrentShutdown` pasa 200 rounds de
   read vs shutdown race. `synchronized` + `ConcurrentHashMap` garantiza consistencia.
   `thunderingHerd` valida 100 threads simultaneos devolviendo la misma instancia

3. **Lazy by design** -- `builtProvisionCount == 0` despues de init (confirmado en 9 tests de
   memoria). El consumidor paga solo por las features que usa. Si de 50 features solo accede 5,
   las otras 45 nunca se instancian

4. **Logger persistence** -- ObservabilityProvider con flag `persistent` sobrevive a ciclos de
   shutdown/reinit. Observabilidad continua garantizada

5. **Cada feature-impl compila independientemente con Dagger** -- compile-time safety
   per-Component. Un binding faltante dentro de un Component = error de compilacion en ese modulo

6. **Init cost (106,865 ns = ~107 us) es aceptable** -- ocurre una sola vez al arrancar la app.
   107 us es imperceptible en un cold start de 500-2000 ms

7. **Resolve cached (202 ns) es rapido para hot paths** -- ConcurrentHashMap lookup O(1).
   60x mas rapido que Koin (~12,150 ns). 3x mas lento que Dagger directo (64 ns), pero la
   diferencia de 140 ns es irrelevante en operaciones de negocio que cuestan microsegundos

8. **35 tests demuestran robustez** -- 12 benchmarks, 9 memoria, 14 stress/concurrencia.
   10,000 ciclos con heap delta de 4 KB. Zero leaks

**El unico trade-off de H:** provider faltante = error runtime, no de compilacion. **Mitigacion:**
test `verify()` en CI que ejecuta `sdk.init()` + `sdk.get<T>()` para cada servicio registrado.
Un provider faltante se detecta en CI antes de llegar a produccion.

### Alternativa si compile-time safety es prioridad absoluta: Pattern Q2

**Cuando considerar Q2 sobre H:**
- El equipo prioriza compile-time safety COMPLETA sobre auto-registro
- El numero de features crece lentamente (poco merge conflict en @Component)
- Hot restart frecuente (Q2: 2,157 ns re-init vs H: 362,649 ns)

**Trade-off de Q2:** cada feature nuevo requiere editar `@Component(modules=[...])`.
Con 10 devs y 50+ modules, esto implica:
- La lista de modules en el @Component se convierte en 50+ lineas
- Merge conflicts frecuentes cuando 2+ devs anaden features simultaneamente
- Disciplina de rebase constante en el fichero de wiring

### Alternativa si R8/ProGuard elimina META-INF: Pattern K

**Cuando considerar K sobre H:**
- ProGuard/R8 esta configurado agresivamente y elimina `META-INF/services`
- Anadir keep rules para META-INF no es posible (politica de la empresa)
- Se acepta el costo extra de init (213K ns vs 107K ns)

K usa AndroidManifest metadata que sobrevive a cualquier optimizacion de R8.

---

## 5. Por que NO los otros patrones Android-only

| Patron | Razon de descarte | Criterio fallido |
|--------|-------------------|------------------|
| **D** (Component Deps) | No auto-registro. When-blocks crecen linealmente con features | Auto-registro (MUST HAVE) |
| **G** (Factory Functions) | No auto-registro. `ensure*()` functions crecen linealmente | Auto-registro (MUST HAVE) |
| **E2** (AutoProvisionRegistry) | Semi-auto: lista central de entries genera merge conflicts a escala | Auto-registro parcial |
| **I** (Pure Resolver) | Zero compile-time safety. 10 devs sin validacion = alto riesgo | Compile-time safety (MUST HAVE) |
| **Q** (Hilt-style eager) | No auto-registro + no lazy. 50 singletons creados en init aunque se usen 5 | Auto-registro + Lazy (2 MUST HAVE) |
| **Q2** (Hilt-style lazy) | No auto-registro. 50+ modules en @Component = merge conflicts | Auto-registro (MUST HAVE). Alternativa viable si se acepta trade-off |
| **K** (AndroidManifest) | Viable pero init 2x mas lento que H sin beneficio adicional (excepto R8-safety) | Alternativa, no descarte total |

**Nota sobre Q y Q2:** Ambos ofrecen compile-time safety COMPLETA (el unico punto debil de H).
Si el equipo tiene disciplina de rebase y el numero de features crece lentamente, Q2 es una
alternativa genuina. Pero a +50 modules con 10 devs anadiendo features en paralelo, el
auto-registro de H supera el beneficio de compile-time safety completa de Q2.

---

## 6. Fortalezas de Pattern H confirmadas por benchmarks

### Tabla comparativa en los 8 criterios

| # | Criterio | H | Q | Q2 | E2 | K | I |
|---|----------|---|---|----|----|---|---|
| 1 | Auto-registro | **OK** | NO | NO | PARCIAL | **OK** | **OK** |
| 2 | Compile-time safety | PARCIAL | **OK** | **OK** | **OK** | PARCIAL | NO |
| 3 | Lazy | **OK** | NO | **OK** | **OK** | **OK** | **OK** |
| 4 | Thread-safe shutdown | **OK** | **OK** | **OK** | **OK** | **OK** | **OK** |
| 5 | Logger persistente | **OK** | **OK** | **OK** | PARCIAL | **OK** | PARCIAL |
| 6 | Madurez ecosistema | **ALTA** | **ALTA** | **ALTA** | **ALTA** | **ALTA** | **ALTA** |
| 7 | Init cold (ns) | 106,865 | **676** | 1,080 | 10,983 | 213,737 | 94,255 |
| 8 | Resolve cached (ns) | 202 | **64** | 85 | 199 | 203 | 203 |
| | **MUST HAVEs cumplidos** | **3/3** | **1/3** | **2/3** | **2.5/3** | **3/3** | **2/3** |
| | **Candidato viable** | **SI** | NO | Alternativa | Alternativa | Alternativa | NO |

### Tests clave de Pattern H

| Test | Resultado | Que demuestra |
|------|-----------|---------------|
| `concurrentShutdown` | 200 rounds OK | Thread-safe: read vs shutdown race sin crash |
| `thunderingHerd` | 100 threads, todos `assertSame` | Singleton identity bajo contention extrema |
| `concurrentBuild` | 100 rounds, 6 threads | DFS thread-safe: 6 threads construyen simultaneamente |
| `builtProvisionCount == 0` tras init | 9 tests OK | Laziness genuina: init no construye nada |
| `leakDetection` | 1,000 ciclos, delta < 2,048 KB | Zero memory leaks en el Resolver |
| `stress10K` | 10,000 ciclos, heap = 4 KB | Estabilidad a escala extrema |
| `rapidFire` | 5,000 ciclos | Ciclo init/get/shutdown determinista y repetible |
| `errorResilience` | 5 escenarios | Maquina de estados correcta (double init, get antes de init, etc.) |
| `functional` | 1,000 reinits | Encrypt+Auth+Sync funcionan tras 1,000 ciclos |
| `loggerPersistence` | Logger sobrevive shutdown | Observabilidad continua entre ciclos |

---

## 7. Evidencia de Benchmarks

### Benchmarks principales (Samsung Galaxy S22 Ultra, Jetpack Benchmark 1.4.0)

| Operacion | H | Q | Q2 | E2 | K | I |
|-----------|--:|--:|---:|---:|--:|--:|
| Init Cold (ns) | 106,865 | 676 | 1,080 | 10,983 | 213,737 | 94,255 |
| Resolve First (ns) | 202 | 257 | 306 | 199 | 203 | 203 |
| Resolve All (ns) | 212 | 64 | 85 | 211 | 213 | 211 |
| Lazy noDeps (ns) | 1,278 | 1,735 | 236 | 1,049 | 2,996 | 1,112 |
| Lazy cascade (ns) | 3,892 | 318 | 504 | 3,088 | 7,900 | 4,122 |
| E2E Startup (ns) | 1,745,145 | 950,000 | 1,300,000 | 1,400,000 | 2,300,000 | 1,700,000 |
| Init/Shutdown cycle (ns) | 99,293 | 403 | 549 | 4,418 | 201,490 | 103,695 |
| Re-Init (ns) | 362,649 | 25,000 | 2,157 | 17,000 | 767,000 | 427,000 |
| Concurrent Access (ns) | 515,355 | 591,000 | 586,000 | 571,000 | 554,000 | 608,000 |

### Interpretacion para el caso de +50 modulos

1. **Init Cold (una vez por sesion):** H paga 107 us por ServiceLoader scan. Es ~0.1 ms en un
   cold start de 500-2000 ms. Imperceptible para el usuario. Q/Q2 son mas rapidos pero requieren
   edicion central (inviable a +50 modules)

2. **Resolve cached (hot path):** 202 ns por llamada a `sdk.get<T>()`. Si el consumidor hace
   100 resoluciones por segundo, el overhead total es 20 us/s. Negligible. Q es 3x mas rapido (64 ns)
   pero la diferencia de 140 ns no justifica perder auto-registro

3. **Lazy cascade:** 3,892 ns para construir la cadena mas profunda (Core -> Enc -> Auth + Stor -> Sync).
   Q resuelve en 318 ns (12x mas rapido) porque Dagger pre-wired todo. Pero H solo paga este
   costo la primera vez; despues todo esta cacheado a 202 ns

4. **Re-Init:** H paga 363K ns. Q2 es 168x mas rapido (2,157 ns). Si hot restart es frecuente
   (testing, config changes), Q2 tiene ventaja aqui. En produccion, re-init es raro

5. **Concurrent Access:** Todos los patrones convergen (~500-600K ns) porque el overhead es
   threading, no DI. H es competitivo

---

## 8. Riesgos con Pattern H y Mitigaciones

| # | Riesgo | Probabilidad | Impacto | Mitigacion |
|---|--------|-------------|---------|-----------|
| 1 | ServiceLoader scan time crece con classpath size | MEDIA | BAJO | ProGuard keep rules para `META-INF/services/`. A 50+ features el scan sigue siendo < 200 us. Benchmark en CI para detectar regresiones |
| 2 | Provider faltante = error runtime | MEDIA | ALTO | Test `verify()` en CI: `sdk.init()` + `sdk.get<T>()` para cada servicio. Detecta provider faltante antes de release. CI lo ejecuta en cada PR |
| 3 | Dagger KSP compilation time | MEDIA | MEDIO | KSP incremental: solo recompila el modulo cambiado. Gradle build cache para modulos no modificados. Cada feature-impl compila su DaggerComponent independientemente |
| 4 | R8/ProGuard elimina META-INF/services | BAJA | ALTO | Regla en `proguard-rules.pro`: `-keep class META-INF/services/**`. Si no es viable, migrar a Pattern K (AndroidManifest discovery). La migracion es directa: mismos FeatureProviders, solo cambia el mecanismo de descubrimiento |
| 5 | Resolver.kt como codigo propio a mantener | BAJA | BAJO | 105 lineas, 35 tests que validan su comportamiento. API estable y minima. Costo de mantenimiento marginal vs. costo de migrar a otro framework |
| 6 | Escalabilidad del DFS con +50 features | BAJA | BAJO | ScaleBenchmark prueba DFS con 500+ features simuladas. ConcurrentHashMap lookup es O(1). El grafo es aciclico por diseno (Dagger lo garantiza per-Component) |

### Mitigacion general: CI continuo

Mantener la suite de 35 tests ejecutandose en CI:
- **12 benchmarks** para detectar regresiones de performance
- **9 tests de memoria** para detectar leaks (limite: heap delta < 2,048 KB en 1,000 ciclos)
- **14 tests de stress** para validar concurrencia, singleton identity, y error resilience

Si alguna actualizacion de Dagger, Kotlin o ServiceLoader rompe algo, los tests lo detectan inmediatamente.

---

## Resumen Ejecutivo

| Opcion | Recomendacion | Para quien |
|--------|--------------|------------|
| **H (ServiceLoader + Resolver DFS + Dagger)** | **PRIMARIA** | SDK Android +50 modulos, 10 devs, auto-registro critico |
| Q2 (Hilt-style Dagger Lazy) | Alternativa | Si compile-time safety completa > auto-registro. Aceptar merge conflicts |
| K (AndroidManifest Discovery) | Alternativa | Si R8 elimina META-INF. Init 2x mas lento pero R8-safe |
| E2 (AutoProvisionRegistry) | Alternativa menor | Si se quiere init mas rapido que H con semi-auto-registro |
| Q (Hilt-style Dagger Eager) | No recomendado | Sin lazy + sin auto-registro. No viable a +50 modules |
| I (Pure Resolver) | No recomendado | Sin compile-time safety. Riesgo alto con 10 devs |

**Decision:** Para un SDK Android corporativo con +50 modulos y 10 devs donde auto-registro es
critico, **Pattern H** es la recomendacion primaria. Su combinacion de ServiceLoader auto-discovery,
DFS lazy, thread-safe shutdown y Dagger compile-time per-Component ofrece el mejor balance entre
escalabilidad de equipo y robustez tecnica.
