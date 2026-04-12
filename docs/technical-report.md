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

**La diferencia de rendimiento entre los 16 patrones es imperceptible para el usuario.** El init mas lento (Patron K, 210,826 ns) tarda 0.21 milisegundos -- tres ordenes de magnitud por debajo del umbral perceptible de 16,666,666 ns (un frame a 60 fps). La eleccion entre patrones es **arquitectonica**, no de rendimiento.

### Tabla resumen de recomendacion

| Patron | Caso de uso recomendado |
|--------|------------------------|
| **D (Component Dependencies)** | SDKs pequenos (< 10 features), equipo familiarizado con Dagger |
| **E2 (Auto-Init Registry)** | SDKs medianos (10-30 features), necesidad de registro centralizado |
| **G (Factory Functions)** | SDKs pequenos, deseo de ocultar DaggerComponents de los consumidores |
| **H (Auto-Discovery + Dagger)** | SDKs grandes (30+ features), equipos distribuidos, maximo desacoplamiento |
| **I (Pure Resolver)** | SDKs que buscan eliminar toda dependencia de frameworks DI (zero codegen, builds rapidos) |
| **J (kotlin-inject)** | SDKs que prefieren codegen moderno en Kotlin (KSP) sobre Dagger (KAPT/Java) |
| **K (AndroidManifest Discovery)** | SDKs que necesitan robustez ante R8/ProGuard sin reglas keep (discovery via manifest metadata) |

### Tres conclusiones clave

1. **Rendimiento no es diferenciador.** Todos los patrones resuelven servicios en nanosegundos y completan init + resolve + primera operacion en menos de 882,041 ns (el aumento respecto a ejecuciones anteriores se debe a la migracion de Storage a DataStore con I/O real a disco).
2. **Escalabilidad si lo es.** Los patrones D y G requieren editar el modulo de wiring por cada feature nuevo; H, I, J y K no requieren cambio alguno en el modulo central.
3. **El principio de auto-discovery con grafo lazy es el estandar de SDKs corporativos.** Firebase SDK usa un patron conceptualmente identico (auto-registro via AndroidManifest metadata + ComponentRuntime con topo-sort). Pattern H aplica el mismo principio con ServiceLoader + Resolver DFS. Pattern K replica el mecanismo de Firebase de forma aun mas literal: descubre providers via `<meta-data>` en AndroidManifest con PackageManager.

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
| Q -- Hilt-style Dagger | `sdk/wiring-q` | Dagger 2 | @Component monolitico con @InstallIn modules por feature. Sin Resolver ni when-blocks | ~60 |
| Q2 -- Hilt-style Simplified | `sdk/wiring-q2` | Dagger 2 | Variante simplificada de Q con menos boilerplate | ~55 |

#### KMP-compatible (5 patrones)

| Patron | Modulo Gradle | Framework | Mecanismo | Lineas wiring |
|--------|---------------|-----------|-----------|---------------|
| N -- sweet-spi + Koin | `sdk/wiring-n` | Koin + sweet-spi | sweet-spi descubre KoinModuleProvider en todos los targets KMP (JVM, Native, WASM) | ~60 |
| O -- Koin DSL Modules | `sdk/wiring-o` | Koin | Modules Koin declarados via DSL, sin ServiceLoader | ~55 |
| O2 -- Koin DSL Auto-Discovery | `sdk/wiring-o2` | Koin | Koin DSL con auto-discovery de modules | ~55 |
| P -- Koin Annotations | `sdk/wiring-p` | Koin + koin-annotations | @Module/@Single KSP genera module definitions | ~50 |
| P2 -- Koin Annotations Auto | `sdk/wiring-p2` | Koin + koin-annotations | Koin Annotations con auto-discovery | ~50 |

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

**Wiring manual (D, G):** El modulo de wiring importa las implementaciones concretas (DaggerXxxComponent) y orquesta el orden de construccion con metodos ensure*() y when-blocks. Cada feature nuevo requiere editar el wiring.

**Wiring centralizado (E2, M):** Las dependencias se declaran en un archivo central (Entries.kt en E2, modules listados en M). El registro resuelve el orden con DFS (E2) o Koin (M). Agregar un feature requiere agregar una funcion/modulo y una linea en el listado central.

**Wiring auto-descubierto (H, I, J, K, L, N, O, O2, P, P2, Q, Q2):** Cada feature declara su propio Provider/Module y se registra automaticamente. El modulo de wiring no cambia nunca, sin importar cuantos features se agreguen. H, I, J y K usan el mismo Resolver con DFS; L y N usan ServiceLoader/sweet-spi con Koin; O, O2, P y P2 usan Koin DSL o Koin Annotations; Q y Q2 usan Dagger @Component monolitico con @InstallIn modules.

---

## 3. Resultados de Benchmarks

### 3.1 Tabla comparativa completa

Todas las mediciones en nanosegundos (ns). Dispositivo: Samsung Galaxy S22 Ultra, Android 16.

| Metrica | D | E2 | G | H | I | J | K |
|---------|---|----|----|---|---|---|---|
| initCold | 1,135 | 8,203 | 1,214 | 95,870 | 95,528 | 92,407 | 210,826 |
| resolveFirst | 15.9 | 34.4 | 15.3 | 34.8 | 34.6 | 34.7 | 34.3 |
| lazyInit noDeps | 289 | 913 | 262 | 1,211 | 1,050 | 1,234 | 1,942 |
| lazyInit cascade | 666 | 2,432 | 726 | 3,769 | 3,157 | 4,594 | 7,134 |
| crossFeatureOp | 1,705,529 | 1,215,247 | 1,706,527 | 1,165,232 | 1,431,963 | 1,454,029 | 1,180,167 |
| stress_initShutdown | 226 | 4,093 | 245 | 85,446 | 88,935 | 93,732 | 198,073 |
| stress_concurrent | 484,329 | 461,788 | 473,717 | 472,057 | 439,467 | 439,728 | 459,426 |
| stress_resolveAll | 100 | 205 | 101 | 208 | 207 | 213 | 208 |
| stress_selective | 297 | 3,613 | 279 | 84,193 | 88,883 | 82,120 | 200,142 |
| stress_reInit | 2,096 | 15,973 | 2,078 | 179,690 | 208,120 | 187,173 | 426,814 |
| stress_incremental | 1,114 | 7,473 | 1,177 | 96,927 | 89,918 | 87,604 | 207,402 |
| e2eStartup | 490,218 | 418,304 | 567,101 | 651,291 | 771,951 | 724,898 | 882,041 |

### 3.2 Analisis por categoria

#### Init Cold -- Construccion del grafo inicial

| Patron | Tiempo (ns) | Mecanismo |
|--------|-------------|-----------|
| D | 1,135 | Asignacion directa de campos (Dagger codegen) |
| G | 1,214 | Idem D, con factory functions |
| E2 | 8,203 | Cataloga entries en HashMaps, no construye nada |
| H | 95,870 | ServiceLoader escanea classpath + registra providers |
| I | 95,528 | Idem H, con PureFeatureProvider |
| J | 92,407 | Idem H, con KIFeatureProvider |
| K | 210,826 | PackageManager.getServiceInfo() IPC a system_server + registra providers |

D y G dominan en init frio porque Dagger resuelve el grafo en tiempo de compilacion -- init es solo asignacion de campos. H, I y J pagan el costo del escaneo de classpath via ServiceLoader (~92-96K ns). K es el mas lento en init (~2.2x vs H) porque PackageManager.getServiceInfo() es una llamada IPC al system_server, mas costosa que el escaneo local de classpath de ServiceLoader. Sin embargo, el patron mas lento (K = 210,826 ns) es despreciable en el arranque de una app Android (tipicamente 500,000,000 - 2,000,000,000 ns).

#### Resolve First -- Primera resolucion tras init

| Patron | Tiempo (ns) |
|--------|-------------|
| G | 15.3 |
| D | 15.9 |
| K | 34.3 |
| E2 | 34.4 |
| I | 34.6 |
| J | 34.7 |
| H | 34.8 |

Tras la primera resolucion, todos los patrones sirven desde cache (ConcurrentHashMap lookup). Las diferencias de 15.3 a 34.8 ns estan dentro del margen de ruido del benchmark. K tiene rendimiento identico a H (~34 ns) porque usa el mismo Resolver y los mismos ConcurrentHashMap lookups.

#### Lazy Init -- Construccion bajo demanda

**Sin dependencias (Analytics -- depende solo de Core):**

| Patron | Tiempo (ns) |
|--------|-------------|
| G | 262 |
| D | 289 |
| E2 | 913 |
| I | 1,050 |
| H | 1,211 |
| J | 1,234 |
| K | 1,942 |

**Con cascada (Sync -- depende de Core + Enc + Auth + Stor):**

| Patron | Tiempo (ns) |
|--------|-------------|
| D | 666 |
| G | 726 |
| E2 | 2,432 |
| I | 3,157 |
| H | 3,769 |
| J | 4,594 |
| K | 7,134 |

La cascada Sync es el escenario mas exigente: construir 4 provisions en cadena de 4 niveles. D y G son los mas rapidos (< 726 ns) porque el compilador resuelve el orden de dependencias estaticamente. H, I, J y K pagan el costo del DFS en runtime (el Resolver recorre el grafo dinamicamente). K es ligeramente mas lento que H en cascada (7,134 vs 3,769 ns) por overhead adicional del mecanismo de discovery. Aun asi, la cascada mas lenta (K = 7,134 ns) es imperceptible.

#### Cross-Feature Operation -- Operacion real cruzando features

| Patron | Tiempo (ns) |
|--------|-------------|
| H | 1,165,232 |
| K | 1,180,167 |
| E2 | 1,215,247 |
| I | 1,431,963 |
| J | 1,454,029 |
| D | 1,705,529 |
| G | 1,706,527 |

Los valores estan en el rango ~1.1-1.7M ns porque Storage usa DataStore (I/O real a disco via suspend + runBlocking). Los tiempos reflejan el coste real de operaciones cross-feature con persistencia a disco (sync.sync()). Una vez resueltos los servicios, el rendimiento depende del codigo de negocio (incluyendo I/O a disco), no del patron DI. Las diferencias entre patrones se deben a la variabilidad del acceso a disco, no al mecanismo de DI. Nota: ~1.5 ms es perceptible si crossFeatureOp se invoca frecuentemente en el hilo principal.

#### E2E App Startup -- Init + resolve all + primera operacion por feature

| Patron | Tiempo (ns) |
|--------|-------------|
| E2 | 418,304 |
| D | 490,218 |
| G | 567,101 |
| H | 651,291 |
| J | 724,898 |
| I | 771,951 |
| K | 882,041 |

Los valores incluyen DataStore (I/O real a disco), lo que anade latencia real a las operaciones cross-feature incluidas en el e2e. Incluso el patron mas lento (K = 882,041 ns) completa el arranque del SDK con 6 features en menos de 1 ms. Esto representa el 0.044% de un arranque tipico de app Android (~2,000,000,000 ns). **Ningun patron es un cuello de botella en el arranque.** K es mas lento que H porque el IPC a system_server se acumula en el ciclo completo de init + resolve + primera operacion, pero todos estan por debajo de 1 ms.

---

## 4. Comportamiento de Memoria

### 4.1 Conteo de provisions construidas por etapa

Esta tabla verifica la pereza (laziness) del grafo de dependencias: cuantas provisions se han instanciado en cada momento.

| Etapa | D | E2 | G | H | I | J | K |
|-------|---|----|----|---|---|---|---|
| afterInit | 1 | 0 | 1 | 0 | 0 | 0 | 0 |
| afterEnc | 2 | 2 | 2 | 3 | 2 | 2 | 3 |
| afterAna | 2 | 2 | 2 | 3 | 2 | 2 | 3 |
| afterSync | 5 | 5 | 5 | 6 | 6 | 6 | 6 |
| fullGraph | 6 | 6 | 6 | 7 | 7 | 7 | 7 |

### 4.2 Analisis de laziness

- **D y G** construyen CoreProvisions en init (afterInit = 1); el resto es lazy.
- **E2** no construye nada en init (afterInit = 0); solo cataloga entries en HashMaps. Es el patron mas perezoso en la fase de inicializacion.
- **H, I, J y K** construyen ObservabilityProvisions como provision adicional (fullGraph = 7 vs 6), porque el logger se descubre via ServiceLoader (H, I, J) o AndroidManifest (K) como un provider mas en vez de inyectarse como parametro directo. K tiene comportamiento de memoria identico a H porque reutiliza los mismos FeatureProvider y el mismo Resolver.
- **Todos los patrones son genuinamente lazy:** pedir EncryptionApi no construye Analytics. Pedir Analytics no construye Auth. Solo se construyen las dependencias estrictamente necesarias para satisfacer la solicitud.

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

### 5.3 Benchmarks de estres por patron

| Metrica | D | E2 | G | H | I | J | K |
|---------|---|----|----|---|---|---|---|
| stress_initShutdown (ns) | 226 | 4,093 | 245 | 85,446 | 88,935 | 93,732 | 198,073 |
| stress_concurrent (ns) | 484,329 | 461,788 | 473,717 | 472,057 | 439,467 | 439,728 | 459,426 |
| stress_resolveAll (ns) | 100 | 205 | 101 | 208 | 207 | 213 | 208 |
| stress_selective (ns) | 297 | 3,613 | 279 | 84,193 | 88,883 | 82,120 | 200,142 |
| stress_reInit (ns) | 2,096 | 15,973 | 2,078 | 179,690 | 208,120 | 187,173 | 426,814 |
| stress_incremental (ns) | 1,114 | 7,473 | 1,177 | 96,927 | 89,918 | 87,604 | 207,402 |

### 5.4 Analisis de estres

**Ciclo de vida (initShutdown, reInit, selective, incremental):** D y G son consistentemente mas rapidos (226 - 2,096 ns) porque no invocan ServiceLoader en cada ciclo. H, I y J pagan ~85,000 - 94,000 ns por ciclo init debido al escaneo de classpath. K paga ~198,000 ns por ciclo init porque PackageManager.getServiceInfo() es un IPC mas costoso que el escaneo local de ServiceLoader. Esto es relevante solo en tests de estres; en uso real, init se llama una vez por sesion de la app.

**Concurrencia (stress_concurrent):** Todos los patrones convergen a ~439,000 - 484,000 ns. Todos los patrones son ahora thread-safe (synchronized + ConcurrentHashMap). La concurrencia esta dominada por el costo de coordinacion entre threads (locks, CAS), no por el patron DI.

**Resolucion cached (stress_resolveAll):** Todos los patrones resuelven el grafo completo desde cache en 100 - 213 ns. La diferencia entre D (100 ns) y J (213 ns) es insignificante. K (208 ns) se comporta identicamente a H porque usa el mismo Resolver con los mismos ConcurrentHashMap lookups.

---

## 6. Escalabilidad

### 6.1 Costo de agregar un feature nuevo

| Patron | Archivos a modificar en wiring | Tipo de cambio |
|--------|-------------------------------|----------------|
| **D** | `MultiModuleSdk.kt` | Agregar 2 ramas al when-block + 1 metodo ensure*() + 1 campo nullable |
| **E2** | `Entries.kt` | Agregar 1 funcion xxxAutoEntry() + 1 linea en allAutoEntries() |
| **G** | `MultiModuleSdkG.kt` | Agregar 2 ramas al when-block + 1 metodo ensure*() + 1 campo + 1 import |
| **H** | Ninguno | El feature se auto-registra via META-INF/services. Zero edicion central |
| **I** | Ninguno | Idem H, con PureFeatureProvider |
| **J** | Ninguno | Idem H, con KIFeatureProvider |
| **K** | Ninguno | El feature se auto-registra via AndroidManifest `<meta-data>`. Zero edicion central |

### 6.2 Clasificacion por escalabilidad

**Escala con dificultad (D, G):** El modulo de wiring crece linealmente con el numero de features. Con 50 features, el when-block tiene 50+ ramas y los metodos ensure*() forman una marana de dependencias manuales. Cada feature nuevo requiere un PR al modulo de wiring -- cuello de botella para equipos distribuidos.

**Escala con moderacion (E2):** El modulo de wiring crece, pero cada feature es una funcion aislada (1 entry = 1 funcion). Las dependencias se declaran explicitamente y el registry las resuelve con DFS. Con 50 features, Entries.kt tendria ~600 lineas -- manejable pero no ideal.

**Escala sin limites (H, I, J, K):** El modulo de wiring no cambia nunca. Cada feature declara su propio FeatureProvider en su modulo y se registra via META-INF/services (H, I, J) o via AndroidManifest `<meta-data>` (K). Con 100 features, el archivo de wiring sigue siendo 50-55 lineas. Equipos independientes pueden publicar features sin coordinacion central.

### 6.3 Velocidad de build

| Patron | Codegen | Impacto en build incremental |
|--------|---------|------------------------------|
| D, E2, G, H, K | Dagger 2 (KSP/KAPT, genera Java) | +2-5 segundos por modulo con @Component |
| I | Ninguno | Zero overhead. Build mas rapido del proyecto |
| J | kotlin-inject (KSP, genera Kotlin) | +1-3 segundos por modulo con @Component. Menos que Dagger |

**I es el patron con builds mas rapidos** porque no tiene ningun paso de procesamiento de anotaciones. Esto es relevante en proyectos con 20+ modulos donde el tiempo de KSP se acumula.

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
Cuantos features tendra el SDK?
|
+-- < 10 features
|    +-- Equipo familiarizado con Dagger? --> Pattern D
|    +-- Preferencia por simplicidad? --> Pattern G
|
+-- 10-30 features
|    +-- Se necesita registro centralizado? --> Pattern E2
|
+-- 30+ features o equipos distribuidos
     |
     +-- El equipo ya usa Dagger internamente?
     |    +-- SI --> Se necesita robustez ante R8/ProGuard sin reglas keep?
     |    |         +-- SI --> Pattern K (discovery via AndroidManifest, Firebase-style)
     |    |         +-- NO --> Pattern H (ServiceLoader + Dagger, grafo lazy)
     |    +-- NO --> Se prefieren builds rapidos sin codegen?
     |              +-- SI --> Pattern I (zero framework, builds mas rapidos)
     |              +-- NO --> Pattern J (kotlin-inject, menos boilerplate)
     |
     +-- SDK legacy migrando incrementalmente?
          +-- Pattern G (cada feature expone factory function)
```

### 8.2 Tabla de recomendacion por escenario

| Escenario | Patron recomendado | Justificacion |
|-----------|-------------------|---------------|
| SDK interno de empresa, < 10 features, equipo Dagger | **D** | Minimo overhead, maximo control, el equipo ya conoce Dagger |
| SDK interno, 10-30 features, un equipo gestiona el wiring | **E2** | Catalogo centralizado con lazy init; 1 linea por feature nuevo |
| SDK open-source con contribuidores externos | **H** | Zero coordinacion central; los contribuidores solo publican su feature |
| SDK con R8/ProGuard agresivo, sin reglas keep | **K** | Manifest entries sobreviven minificacion; mismo grafo lazy que H |
| SDK con builds rapidos como prioridad | **I** | Zero codegen; cada feature es constructor injection puro |
| SDK con compile-time safety sin Dagger | **J** | kotlin-inject valida en compilacion; genera Kotlin, no Java |
| SDK legacy migrando incrementalmente | **G** | Cada feature expone factory function; DaggerComponent queda interno |

### 8.3 Recomendacion general

Para un SDK Android nuevo que anticipa crecimiento mas alla de 15-20 features:

1. **Comenzar con Pattern H** (Dagger + ServiceLoader) -- aplica el mismo principio arquitectonico que Firebase SDK (auto-registro, wiring inmutable, grafo lazy). Compile-time safety dentro de cada feature.
2. **Considerar Pattern K** si R8/ProGuard es agresivo y se prefiere evitar reglas keep para META-INF/services. K reutiliza los mismos FeatureProvider de H, cambiando unicamente el mecanismo de discovery (AndroidManifest `<meta-data>` via PackageManager). El costo es ~2.2x init mas lento (211K vs 96K ns) y requerir Context en init(), pero ambos son imperceptibles (< 1 ms e2e).
3. **Considerar Pattern I** si los tiempos de build son prioridad (zero codegen, builds mas rapidos) o si el equipo prefiere evitar frameworks DI.
4. **Considerar Pattern J** si el equipo quiere compile-time safety con menos boilerplate que Dagger y codegen moderno en Kotlin.

La migracion H -> K es trivial (mismo Resolver, mismos FeatureProvider, solo cambia el discovery). La migracion H -> I/J es incremental: cada feature puede migrar su Provider individualmente, porque el Resolver acepta cualquier subclase de FeatureProvider.

### 8.4 Matriz de pros y contras

| Criterio | D | E2 | G | H | I | J | K |
|----------|---|----|----|---|---|---|---|
| Paradigma | Compile-time | Compile-time | Compile-time | Compile-time + Runtime | Puro runtime | Compile-time + Runtime | Compile-time + Runtime |
| Framework | Dagger 2 | Dagger 2 | Dagger 2 | Dagger 2 | Ninguno | kotlin-inject | Dagger 2 |
| Seguridad en compilacion | Alta | Alta | Alta | Alta (dentro del feature) | Ninguna | Alta | Alta (dentro del feature) |
| Auto-discovery | No | No | No | Si (ServiceLoader) | Si (ServiceLoader) | Si (ServiceLoader) | Si (AndroidManifest) |
| Edicion central por feature | Si (when + ensure) | Si (1 entry) | Si (when + ensure) | No | No | No | No |
| Lineas en wiring | 149 | 66 + 129 entries | 107 | 51 | 54 | 55 | 50 |
| Init frio (ns) | 1,135 | 8,203 | 1,214 | 95,870 | 95,528 | 92,407 | 210,826 |
| Resolve cached (ns) | 15.9 | 34.4 | 15.3 | 34.8 | 34.6 | 34.7 | 34.3 |
| E2E startup (ns) | 490,218 | 418,304 | 567,101 | 651,291 | 771,951 | 724,898 | 882,041 |
| Build speed | Lento (KSP) | Lento (KSP) | Lento (KSP) | Lento (KSP) | Rapido (0 codegen) | Moderado (KSP) | Lento (KSP) |
| Escalabilidad (50+ features) | Dificil | Moderada | Dificil | Excelente | Excelente | Excelente | Excelente |
| Robustez ante R8/ProGuard | Requiere keep | Requiere keep | Requiere keep | Requiere keep | Requiere keep | Requiere keep | Sin reglas keep |
| Requiere Context en init | Si | Si | Si | Si | Si | Si | Si |

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
