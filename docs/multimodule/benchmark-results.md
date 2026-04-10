# Resultados de Benchmarks Multi-Modulo

Resultados de benchmarks Microbenchmark (Jetpack Benchmark) para los 7 patrones
multi-modulo: D, E2, G, H, I, J y K. Todos ejecutados bajo las mismas condiciones
en el mismo dispositivo.

---

## 1. Dispositivo y Condiciones

- **Dispositivo:** Samsung Galaxy S22 Ultra (Snapdragon 8 Gen 1)
- **Framework:** Jetpack Microbenchmark (`androidx.benchmark`)
- **Clase de test:** `MultiModuleBenchmark.kt`
- **Modo:** Release (optimizaciones ProGuard/R8 activas)
- **Iteraciones:** Configuracion por defecto de Microbenchmark (warmup + medicion)
- **Clock:** Locked (CPU frequency locked para reducir variabilidad)

---

## 2. Resultados por Categoria

### 2.1. Init Cold

Tiempo de la primera llamada a `init(context, config)` desde estado completamente limpio.

| D | E2 | G | H | I | J | K |
|---|----|----|---|---|---|---|
| 1,135 ns | 8,203 ns | 1,214 ns | 95,870 ns | 95,528 ns | 92,407 ns | 210,826 ns |

**Analisis:** D y G son los mas rapidos (~1,135-1,214 ns) porque `init()` solo
construye CoreComponent directamente. E2 es ligeramente mas lento (8,203 ns)
por el coste de catalogar todas las `AutoProvisionEntry` en HashMaps. H, I y J
son ordenes de magnitud mas lentos (92,407-95,870 ns) debido al overhead de
`ServiceLoader` -- la JVM debe escanear `META-INF/services/`, instanciar cada
provider via reflexion y registrarlos en el `Resolver`. K es el mas lento
(210,826 ns) porque `PackageManager.getServiceInfo()` tiene overhead adicional
respecto a ServiceLoader: la lectura del manifest mergeado y la
desserializacion del Bundle de meta-data anaden latencia. El coste de discovery
domina completamente el init en estos cuatro patrones.

### 2.2. Resolve First

Tiempo de la primera resolucion de un servicio ya construido (cache hit).

| D | E2 | G | H | I | J | K |
|---|----|----|---|---|---|---|
| 15.9 ns | 34.4 ns | 15.3 ns | 34.8 ns | 34.6 ns | 34.7 ns | 34.3 ns |

**Analisis:** Todos estan en el rango de nanosegundos. D y G son los mas rapidos
(15.3-15.9 ns) porque acceden a un campo volatil directo sin HashMap lookup.
E2, H, I, J y K pasan por map lookup (~34-35 ns). K usa el mismo Resolver
que H, por lo que su resolucion post-init es identica. La diferencia es
irrelevante en produccion -- todos estan muy por debajo de 1 microsegundo.

### 2.3. Lazy Init noDeps

Tiempo de inicializar una feature sin dependencias cruzadas (e.g. Analytics,
que solo depende de Core).

| D | E2 | G | H | I | J | K |
|---|----|----|---|---|---|---|
| 289 ns | 913 ns | 262 ns | 1,211 ns | 1,050 ns | 1,234 ns | 1,942 ns |

**Analisis:** D y G son practicamente identicos (~262-289 ns) -- la factory function
de G no anade overhead medible. E2 (913 ns) paga el coste del DFS lookup en el
registry antes de construir. H (1,211 ns) tiene overhead adicional por la capa de
Resolver. I y J (~1,050-1,234 ns) son similares a H. K (1,942 ns) es el mas lento
en lazy init noDeps -- el overhead de la capa de manifest discovery se propaga al
primer build de cada provision.

### 2.4. Lazy Init cascade

Tiempo de inicializar una feature con cadena completa de dependencias
(e.g. Sync -> Auth + Storage + Encryption -> Core).

| D | E2 | G | H | I | J | K |
|---|----|----|---|---|---|---|
| 666 ns | 2,432 ns | 726 ns | 3,769 ns | 3,157 ns | 4,594 ns | 7,134 ns |

**Analisis:** D y G (666-726 ns) resuelven la cascada directamente con
`ensure*()` sin overhead de busqueda. E2 (2,432 ns) ejecuta DFS recursivo por
cada nivel de la cadena. H (3,769 ns) ejecuta DFS similar via el Resolver.
I (3,157 ns) y J (4,594 ns) muestran variacion por la implementacion interna
de cada provider. K (7,134 ns) es el mas lento en cascada -- mismo Resolver DFS
que H pero con providers descubiertos via manifest. Para 5 features, la
diferencia es de nanosegundos -- insignificante en el arranque de una app real.

### 2.5. Cross-Feature Op

Tiempo de una operacion que cruza multiples features (e.g. Sync que llama a
Auth, Storage y Encryption internamente).

| D | E2 | G | H | I | J | K |
|---|----|----|---|---|---|---|
| 1,705,529 ns | 1,215,247 ns | 1,706,527 ns | 1,165,232 ns | 1,431,963 ns | 1,454,029 ns | 1,180,167 ns |

**Analisis:** Los valores estan en el rango ~1.1-1.7M ns porque Storage usa
DataStore (I/O real a disco via suspend + runBlocking). Los tiempos reflejan
el coste real de operaciones cross-feature con persistencia a disco (sync.sync()).
Todos los patrones convergen al mismo rango (~1.1-1.7M ns) porque la operacion
de I/O domina el tiempo -- el patron de wiring es irrelevante una vez que los
servicios estan construidos. La variacion entre patrones se debe a la
variabilidad del acceso a disco, no al mecanismo de DI. Nota: ~1.5 ms es
perceptible si se llama frecuentemente en el hilo principal, pero es el coste
real de usar DataStore en operaciones sincronas.

### 2.6. Stress Init/Shutdown

Tiempo de un ciclo completo init() + shutdown() (sin resolver ningun servicio).

| D | E2 | G | H | I | J | K |
|---|----|----|---|---|---|---|
| 226 ns | 4,093 ns | 245 ns | 85,446 ns | 88,935 ns | 93,732 ns | 198,073 ns |

**Analisis:** Mismo patron que Init Cold. D y G (~226-245 ns) son los mas
ligeros. E2 (4,093 ns) paga el catalogo + limpieza del registry. H, I, J
(~85,446-93,732 ns) pagan ServiceLoader en cada ciclo. K (198,073 ns) es el mas
lento porque PackageManager.getServiceInfo() tiene mayor latencia que
ServiceLoader. Esto es relevante para tests que hacen init/shutdown
repetidamente -- con K, 1000 ciclos tardarian ~198,073,000 ns vs ~245,000 ns
con D/G.

### 2.7. Stress Concurrent

Acceso concurrente a servicios ya inicializados desde multiples threads.

| D | E2 | G | H | I | J | K |
|---|----|----|---|---|---|---|
| 484,329 ns | 461,788 ns | 473,717 ns | 472,057 ns | 439,467 ns | 439,728 ns | 459,426 ns |

**Analisis:** Todos los patrones convergen en ~439,467-484,329 ns. La
concurrencia esta dominada por el acceso a los servicios subyacentes, no por el
mecanismo de resolucion. Los siete patrones son seguros para lectura concurrente
post-init (ConcurrentHashMap para lectura segura despues de la inicializacion). I (439,467 ns) es
el mas rapido en acceso concurrente, mientras que D (484,329 ns) es el mas
lento -- la diferencia se debe a la variabilidad del scheduler de threads, no
al mecanismo de discovery.

### 2.8. Stress Resolve All

Resolver todos los servicios disponibles secuencialmente (post-init, todo cacheado).

| D | E2 | G | H | I | J | K |
|---|----|----|---|---|---|---|
| 100 ns | 205 ns | 101 ns | 208 ns | 207 ns | 213 ns | 208 ns |

**Analisis:** D y G (~100-101 ns) resuelven via campos volatiles directos. Los
patrones con registry/resolver (E2, H, I, J, K: 205-213 ns) pagan overhead extra
por ConcurrentHashMap lookup. K (208 ns) usa el mismo Resolver que H, con rendimiento
practicamente identico. En terminos absolutos, todos resuelven el grafo completo
en menos de 213 ns -- excelente rendimiento.

### 2.9. Stress Selective

Init selectivo: inicializar solo un subconjunto de features.

| D | E2 | G | H | I | J | K |
|---|----|----|---|---|---|---|
| 297 ns | 3,613 ns | 279 ns | 84,193 ns | 88,883 ns | 82,120 ns | 200,142 ns |

**Analisis:** D y G (~279-297 ns) son ultra-rapidos porque `ensure*()` construye
exactamente lo pedido sin overhead. E2 (3,613 ns) paga DFS + construir solo
las deps necesarias. H, I, J (~82,120-88,883 ns) pagan ServiceLoader completo
en cada ciclo -- el registro de todos los providers ocurre en init, incluso si
solo se usaran algunos. K (200,142 ns) es el mas lento en init selectivo porque
PackageManager descubre todos los providers del manifest en cada ciclo, con
mayor latencia que ServiceLoader. Para init selectivo frecuente, D/G tienen
ventaja significativa.

### 2.10. Stress Re-Init

Ciclo completo: init -> resolver todo -> shutdown -> re-init -> resolver todo.

| D | E2 | G | H | I | J | K |
|---|----|----|---|---|---|---|
| 2,096 ns | 15,973 ns | 2,078 ns | 179,690 ns | 208,120 ns | 187,173 ns | 426,814 ns |

**Analisis:** Los patrones con discovery automatico (H, I, J: ~179,690-208,120
ns; K: 426,814 ns) pagan el doble del coste de init porque el descubrimiento se
ejecuta dos veces. K es el mas lento porque PackageManager tiene mayor latencia
que ServiceLoader. D y G (~2,078-2,096 ns) solo reconstruyen Components. E2
(15,973 ns) recataloga entries + reconstruye. En escenarios de re-inicializacion
frecuente (configuracion dinamica, tests), D/G son ~204x mas rapidos que K.

### 2.11. Stress Incremental

Anadir una feature nueva despues del init inicial.

| D | E2 | G | H | I | J | K |
|---|----|----|---|---|---|---|
| 1,114 ns | 7,473 ns | 1,177 ns | 96,927 ns | 89,918 ns | 87,604 ns | 207,402 ns |

**Analisis:** Patron similar a los anteriores. D/G (~1,114-1,177 ns) construyen el
Component directamente. E2 (7,473 ns) ejecuta DFS para la nueva feature + deps.
H/I/J (~87,604-96,927 ns) requieren un nuevo ciclo de ServiceLoader. K
(207,402 ns) requiere un nuevo ciclo de PackageManager discovery, con mayor
latencia. Nota: en produccion, la inicializacion incremental es rara -- la
mayoria de SDKs inicializan todo una vez en `Application.onCreate()`.

### 2.12. E2E App Startup

Simulacion de arranque completo de aplicacion: init -> resolver todos los
servicios -> ejecutar una operacion cross-feature.

| D | E2 | G | H | I | J | K |
|---|----|----|---|---|---|---|
| 490,218 ns | 418,304 ns | 567,101 ns | 651,291 ns | 771,951 ns | 724,898 ns | 882,041 ns |

**Analisis:** Este benchmark es el mas representativo del impacto real en
produccion. Los valores incluyen DataStore (I/O real a disco), lo que anade
latencia real a las operaciones cross-feature incluidas en el e2e. D (490,218 ns)
y E2 (418,304 ns) son los mas rapidos. G (567,101 ns) esta cerca. H, I, J
(651,291-771,951 ns) pagan overhead de ServiceLoader en init. K (882,041 ns)
es el mas lento por el overhead de PackageManager. Todos estan por debajo de
882,041 ns -- completamente imperceptible para el usuario (un frame a 60 FPS dura
16,667,000 ns). Incluso el patron mas lento (K con 882,041 ns) representa solo
el 5.3% de un frame.

---

## 3. Comportamiento de Memoria (Provision Counts)

El siguiente test verifica cuantas provisions se construyen en cada etapa del
ciclo de vida del SDK. Permite confirmar que la inicializacion lazy funciona
correctamente -- solo se construyen las provisions necesarias.

| Patron | afterInit | afterEnc | afterAna | afterSync | fullGraph |
|--------|-----------|----------|----------|-----------|-----------|
| **D** | 1 | 2 | 2 | 5 | 6 |
| **E2** | 0 | 2 | 2 | 5 | 6 |
| **G** | 1 | 2 | 2 | 5 | 6 |
| **H** | 0 | 3 | 3 | 6 | 7 |
| **I** | 0 | 2 | 2 | 6 | 7 |
| **J** | 0 | 2 | 2 | 6 | 7 |
| **K** | 0 | 3 | 3 | 6 | 7 |

### Interpretacion

- **afterInit:** D y G construyen CoreComponent en `init()` (1 provision).
  E2, H, I, J, K no construyen nada en init (0 provisions) -- todo es lazy.

- **afterEnc:** Pedir `EncryptionApi` construye Core + Enc (2 provisions en
  D/E2/G/I/J). H y K muestran 3 porque incluyen ObservabilityProvisions como
  provision separada (el logger se resuelve como un provider mas, no como
  un campo directo).

- **afterAna:** Analytics no depende de Encryption, asi que no anade
  provisions nuevas respecto a afterEnc. Los conteos permanecen iguales.

- **afterSync:** Sync depende de Core + Enc + Auth + Stor, lo que dispara
  la cascada completa. D/E2 alcanzan 5, H/I/J/K alcanzan 6 (por la provision
  de Observability separada).

- **fullGraph:** Todas las provisions construidas. D/E2/G: 6 (Core, Enc,
  Auth, Stor, Ana, Syn). H/I/J/K: 7 (las mismas 6 + Observability).

La diferencia en conteos de H/I/J/K se debe a que el logger se gestiona como
una provision independiente descubierta via ServiceLoader o manifest metadata,
mientras que en D/E2/G es un campo directo del facade.

---

## 4. Resultados de Tests de Stress/Torture

### Estado: 277/277 tests pasados, 0 fallos

Desglose:

- **MultiModuleBenchmark:** 84 tests de rendimiento para los 7 patrones.

- **MemoryBehaviorTest:** 57 tests que verifican los conteos de provisions en
  cada etapa del ciclo de vida para los 7 patrones multi-modulo mas los
  patrones monoliticos.

- **StressTortureTest:** 80 tests (incluyendo 3 nuevos tests de concurrencia)
  que incluyen:
  - Init/shutdown rapidos repetidos (1000 ciclos)
  - Acceso concurrente desde multiples threads
  - Re-inicializacion con configuracion diferente
  - Resolucion de todos los servicios en secuencia
  - Inicializacion selectiva de features
  - Operaciones cross-feature bajo stress

- **ScaleBenchmark:** 37 tests de escalabilidad.

- **DiBenchmark:** 19 tests de inyeccion de dependencias.

Los 7 patrones multi-modulo pasan todos los tests de stress sin excepciones,
deadlocks ni corrucion de estado. Esto confirma que los mecanismos de wiring
son robustos bajo condiciones adversas.

---

## 5. Estado del Scale Benchmark

### Estado: 37/37 tests pasados

---

## 6. Conclusiones

### Rendimiento en produccion

Todos los patrones son viables para produccion. El benchmark E2E App Startup
muestra que incluso el patron mas lento (K con 882,041 ns) es completamente
imperceptible: representa menos del 5.3% del tiempo de un frame a 60 FPS.

### Impacto de la migracion a DataStore

crossFeatureOp se mantiene en el rango ~1.1-1.7M ns porque Storage usa DataStore
(I/O real a disco via suspend + runBlocking). Los valores reflejan el coste
real de usar DataStore en operaciones sincronas (sync.sync()). Esto tambien
afecta e2eStartup, que incluye la inicializacion de DataStore.

### Tres tiers de rendimiento

Los patrones se agrupan naturalmente en tres niveles:

**Tier 1 -- Ultra-rapido (D, G):**
- Init: ~1,135-1,214 ns
- Resolucion: ~15-16 ns
- Ideal para: SDKs que necesitan init/shutdown frecuente, tests unitarios.

**Tier 2 -- Rapido (E2):**
- Init: ~8,203 ns
- Resolucion: ~34 ns
- Ideal para: SDKs con muchas features donde la escalabilidad del wiring
  importa mas que nanosegundos de init.

**Tier 3 -- Moderado (H, I, J, K):**
- Init: ~92,407-210,826 ns
- Resolucion: ~34-35 ns
- Ideal para: SDKs donde zero edicion central del wiring es prioritario
  y el init ocurre una sola vez. K es el mas lento del tier por el overhead
  de PackageManager, pero sigue siendo imperceptible en produccion.

### Donde importa la diferencia

La diferencia entre tiers solo es significativa en:

1. **Tests con init/shutdown repetido:** D/G son ~370-410x mas rapidos que H/I/J
   y ~860-880x mas rapidos que K en ciclos de init/shutdown.
2. **Re-inicializacion dinamica:** Si el SDK se reinicializa en caliente
   (cambio de configuracion), D/G completan en ~2,078-2,096 ns vs
   ~179,690-208,120 ns en H/I/J y ~426,814 ns en K.

En el caso tipico de produccion (un init en `Application.onCreate()`, servicios
resueltos on-demand), los siete patrones son equivalentes.

### Resolucion post-init

Una vez inicializados, los siete patrones resuelven servicios en nanosegundos
(15-213 ns para todo el grafo). La eleccion del patron no impacta el
rendimiento de la aplicacion despues del arranque.
