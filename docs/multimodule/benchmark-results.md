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
| 740 ns | 5,493 ns | 803 ns | 60,714 ns | 62,447 ns | 60,296 ns | 141,238 ns |

**Analisis:** D y G son los mas rapidos (~740-803 ns) porque `init()` solo
construye CoreComponent directamente. E2 es ligeramente mas lento (5,493 ns)
por el coste de catalogar todas las `AutoProvisionEntry` en HashMaps. H, I y J
son ordenes de magnitud mas lentos (60,296-62,447 ns) debido al overhead de
`ServiceLoader` -- la JVM debe escanear `META-INF/services/`, instanciar cada
provider via reflexion y registrarlos en el `Resolver`. K es el mas lento
(141,238 ns) porque `PackageManager.getServiceInfo()` tiene overhead adicional
respecto a ServiceLoader: la lectura del manifest mergeado y la
desserializacion del Bundle de meta-data anaden latencia. El coste de discovery
domina completamente el init en estos cuatro patrones.

### 2.2. Resolve First

Tiempo de la primera resolucion de un servicio ya construido (cache hit).

| D | E2 | G | H | I | J | K |
|---|----|----|---|---|---|---|
| 10.9 ns | 27.7 ns | 10.3 ns | 23.6 ns | 24.0 ns | 23.8 ns | 23.6 ns |

**Analisis:** Todos estan en el rango de nanosegundos. D y G son los mas rapidos
(10.3-10.9 ns) porque acceden a un campo volatil directo sin HashMap lookup.
E2, H, I, J y K pasan por map lookup (~23-28 ns). K usa el mismo Resolver
que H, por lo que su resolucion post-init es identica. La diferencia es
irrelevante en produccion -- todos estan muy por debajo de 1 microsegundo.

### 2.3. Lazy Init noDeps

Tiempo de inicializar una feature sin dependencias cruzadas (e.g. Analytics,
que solo depende de Core).

| D | E2 | G | H | I | J | K |
|---|----|----|---|---|---|---|
| 194 ns | 554 ns | 194 ns | 849 ns | 565 ns | 547 ns | 1,371 ns |

**Analisis:** D y G son practicamente identicos (194 ns) -- la factory function
de G no anade overhead medible. E2 (554 ns) paga el coste del DFS lookup en el
registry antes de construir. H (849 ns) tiene overhead adicional por la capa de
Resolver. I y J (~547-565 ns) son mas rapidos que H porque kotlin-inject genera
codigo mas ligero que Dagger en este caso. K (1,371 ns) es el mas lento en lazy
init noDeps -- el overhead de la capa de manifest discovery se propaga al primer
build de cada provision.

### 2.4. Lazy Init cascade

Tiempo de inicializar una feature con cadena completa de dependencias
(e.g. Sync -> Auth + Storage + Encryption -> Core).

| D | E2 | G | H | I | J | K |
|---|----|----|---|---|---|---|
| 475 ns | 1,671 ns | 473 ns | 2,746 ns | 1,873 ns | 2,023 ns | 4,218 ns |

**Analisis:** D y G (473-475 ns) resuelven la cascada directamente con
`ensure*()` sin overhead de busqueda. E2 (1,671 ns) ejecuta DFS recursivo por
cada nivel de la cadena. H (2,746 ns) ejecuta DFS similar via el Resolver.
I (1,873 ns) y J (2,023 ns) son mas eficientes que H en cascada. K (4,218 ns)
es el mas lento en cascada -- mismo Resolver DFS que H pero con providers
descubiertos via manifest. Para 5 features, la diferencia es de nanosegundos
-- insignificante en el arranque de una app real.

### 2.5. Cross-Feature Op

Tiempo de una operacion que cruza multiples features (e.g. Sync que llama a
Auth, Storage y Encryption internamente).

| D | E2 | G | H | I | J | K |
|---|----|----|---|---|---|---|
| 84,351 ns | 80,686 ns | 83,699 ns | 83,517 ns | 84,220 ns | 84,477 ns | 83,743 ns |

**Analisis:** Todos los patrones estan en el mismo rango (~80,686-84,477 ns).
La operacion real domina el tiempo -- el patron de wiring es irrelevante una
vez que los servicios estan construidos. La variacion entre patrones es minima
(menos de 4,000 ns de diferencia entre el mas rapido y el mas lento). Esto
confirma que la eleccion de patron no afecta al rendimiento post-init.

### 2.6. Stress Init/Shutdown

Tiempo de un ciclo completo init() + shutdown() (sin resolver ningun servicio).

| D | E2 | G | H | I | J | K |
|---|----|----|---|---|---|---|
| 159 ns | 2,799 ns | 158 ns | 57,941 ns | 57,062 ns | 57,010 ns | 206,406 ns |

**Analisis:** Mismo patron que Init Cold. D y G (~158-159 ns) son los mas
ligeros. E2 (2,799 ns) paga el catalogo + limpieza del registry. H, I, J
(~57,010-57,941 ns) pagan ServiceLoader en cada ciclo. K (206,406 ns) es el mas
lento porque PackageManager.getServiceInfo() tiene mayor latencia que
ServiceLoader. Esto es relevante para tests que hacen init/shutdown
repetidamente -- con K, 1000 ciclos tardarian ~206,406,000 ns vs ~159,000 ns
con D/G.

### 2.7. Stress Concurrent

Acceso concurrente a servicios ya inicializados desde multiples threads.

| D | E2 | G | H | I | J | K |
|---|----|----|---|---|---|---|
| 365,288 ns | 401,647 ns | 365,447 ns | 354,212 ns | 320,838 ns | 273,318 ns | 333,213 ns |

**Analisis:** Todos los patrones convergen en ~273,318-401,647 ns. La
concurrencia esta dominada por el acceso a los servicios subyacentes, no por el
mecanismo de resolucion. Los siete patrones son seguros para lectura concurrente
post-init (ConcurrentHashMap para lectura segura despues de la inicializacion). J (273,318 ns) es
el mas rapido en acceso concurrente, mientras que E2 (401,647 ns) es el mas
lento -- la diferencia se debe a la variabilidad del scheduler de threads, no
al mecanismo de discovery.

### 2.8. Stress Resolve All

Resolver todos los servicios disponibles secuencialmente (post-init, todo cacheado).

| D | E2 | G | H | I | J | K |
|---|----|----|---|---|---|---|
| 70.0 ns | 143 ns | 69.9 ns | 144 ns | 144 ns | 148 ns | 144 ns |

**Analisis:** D y G (~70 ns) resuelven via campos volatiles directos. Los
patrones con registry/resolver (E2, H, I, J, K: 143-148 ns) pagan ~74 ns extra
por ConcurrentHashMap lookup. K (144 ns) usa el mismo Resolver que H, con rendimiento
practicamente identico. En terminos absolutos, todos resuelven el grafo completo
en menos de 150 ns -- excelente rendimiento.

### 2.9. Stress Selective

Init selectivo: inicializar solo un subconjunto de features.

| D | E2 | G | H | I | J | K |
|---|----|----|---|---|---|---|
| 185 ns | 2,311 ns | 188 ns | 59,696 ns | 57,932 ns | 60,671 ns | 136,825 ns |

**Analisis:** D y G (~185-188 ns) son ultra-rapidos porque `ensure*()` construye
exactamente lo pedido sin overhead. E2 (2,311 ns) paga DFS + construir solo
las deps necesarias. H, I, J (~57,932-60,671 ns) pagan ServiceLoader completo
en cada ciclo -- el registro de todos los providers ocurre en init, incluso si
solo se usaran algunos. K (136,825 ns) es el mas lento en init selectivo porque
PackageManager descubre todos los providers del manifest en cada ciclo, con
mayor latencia que ServiceLoader. Para init selectivo frecuente, D/G tienen
ventaja significativa.

### 2.10. Stress Re-Init

Ciclo completo: init -> resolver todo -> shutdown -> re-init -> resolver todo.

| D | E2 | G | H | I | J | K |
|---|----|----|---|---|---|---|
| 1,744 ns | 9,615 ns | 1,459 ns | 127,407 ns | 126,119 ns | 127,489 ns | 284,651 ns |

**Analisis:** Los patrones con discovery automatico (H, I, J: ~126,119-127,489
ns; K: 284,651 ns) pagan el doble del coste de init porque el descubrimiento se
ejecuta dos veces. K es el mas lento porque PackageManager tiene mayor latencia
que ServiceLoader. D y G (~1,459-1,744 ns) solo reconstruyen Components. E2
(9,615 ns) recataloga entries + reconstruye. En escenarios de re-inicializacion
frecuente (configuracion dinamica, tests), D/G son ~195x mas rapidos que K.

### 2.11. Stress Incremental

Anadir una feature nueva despues del init inicial.

| D | E2 | G | H | I | J | K |
|---|----|----|---|---|---|---|
| 737 ns | 5,206 ns | 770 ns | 60,633 ns | 63,155 ns | 61,678 ns | 142,351 ns |

**Analisis:** Patron similar a los anteriores. D/G (~737-770 ns) construyen el
Component directamente. E2 (5,206 ns) ejecuta DFS para la nueva feature + deps.
H/I/J (~60,633-63,155 ns) requieren un nuevo ciclo de ServiceLoader. K
(142,351 ns) requiere un nuevo ciclo de PackageManager discovery, con mayor
latencia. Nota: en produccion, la inicializacion incremental es rara -- la
mayoria de SDKs inicializan todo una vez en `Application.onCreate()`.

### 2.12. E2E App Startup

Simulacion de arranque completo de aplicacion: init -> resolver todos los
servicios -> ejecutar una operacion cross-feature.

| D | E2 | G | H | I | J | K |
|---|----|----|---|---|---|---|
| 101,500 ns | 115,339 ns | 102,331 ns | 222,713 ns | 243,625 ns | 228,707 ns | 375,308 ns |

**Analisis:** Este benchmark es el mas representativo del impacto real en
produccion. D (101,500 ns) y G (102,331 ns) son los mas rapidos. E2
(115,339 ns) esta cerca. H, I, J (222,713-243,625 ns) duplican el tiempo por
el overhead de ServiceLoader en init. K (375,308 ns) es el mas lento por el
overhead de PackageManager. Sin embargo, todos estan por debajo de 375,308 ns
-- completamente imperceptible para el usuario (un frame a 60 FPS dura
16,667,000 ns). Incluso el patron mas lento (K con 375,308 ns) representa solo
el 2.3% de un frame.

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
muestra que incluso el patron mas lento (K con 375,308 ns) es completamente
imperceptible: representa menos del 2.3% del tiempo de un frame a 60 FPS.

### Tres tiers de rendimiento

Los patrones se agrupan naturalmente en tres niveles:

**Tier 1 -- Ultra-rapido (D, G):**
- Init: ~740-803 ns
- Resolucion: ~10-11 ns
- Ideal para: SDKs que necesitan init/shutdown frecuente, tests unitarios.

**Tier 2 -- Rapido (E2):**
- Init: ~5,493 ns
- Resolucion: ~28 ns
- Ideal para: SDKs con muchas features donde la escalabilidad del wiring
  importa mas que nanosegundos de init.

**Tier 3 -- Moderado (H, I, J, K):**
- Init: ~60,296-141,238 ns
- Resolucion: ~24 ns
- Ideal para: SDKs donde zero edicion central del wiring es prioritario
  y el init ocurre una sola vez. K es el mas lento del tier por el overhead
  de PackageManager, pero sigue siendo imperceptible en produccion.

### Donde importa la diferencia

La diferencia entre tiers solo es significativa en:

1. **Tests con init/shutdown repetido:** D/G son ~360x mas rapidos que H/I/J
   y ~1,300x mas rapidos que K en ciclos de init/shutdown.
2. **Re-inicializacion dinamica:** Si el SDK se reinicializa en caliente
   (cambio de configuracion), D/G completan en ~1,459-1,744 ns vs
   ~126,119-127,489 ns en H/I/J y ~284,651 ns en K.

En el caso tipico de produccion (un init en `Application.onCreate()`, servicios
resueltos on-demand), los siete patrones son equivalentes.

### Resolucion post-init

Una vez inicializados, los siete patrones resuelven servicios en nanosegundos
(10-148 ns para todo el grafo). La eleccion del patron no impacta el
rendimiento de la aplicacion despues del arranque.
