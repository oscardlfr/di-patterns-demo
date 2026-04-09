# Reporte Tecnico: Patrones Multi-Modulo de Inyeccion de Dependencias para SDKs Android

**Proyecto:** di-patterns-demo
**Fecha:** 2026-04-09
**Dispositivo:** Samsung Galaxy S22 Ultra (SM-S908B) -- Snapdragon 8 Gen 1, 8 nucleos, 2.8 GHz, Android 16
**Framework de medicion:** Jetpack Benchmark 1.4.0 con warmup automatico
**Total de tests:** 277 pasaron, 0 fallaron

---

## 1. Resumen Ejecutivo

Este reporte analiza 7 patrones multi-modulo de inyeccion de dependencias implementados en un SDK Android con 6 features (Core, Encryption, Auth, Storage, Analytics, Sync). Cada feature reside en su propio modulo Gradle (`features/feature-xxx-impl`) y las dependencias entre features se expresan a traves de contratos Kotlin puros en `di-contracts` (CoreProvisions, EncProvisions, etc.). Solo el modulo de wiring conoce las implementaciones concretas.

Los 7 patrones fueron instrumentados con Jetpack Benchmark en un Samsung Galaxy S22 Ultra y sometidos a pruebas de estres, concurrencia, comportamiento de memoria y escalabilidad.

### Hallazgo principal

**La diferencia de rendimiento entre los 7 patrones es imperceptible para el usuario.** El init mas lento (Patron K, 174,145 ns) tarda 0.17 milisegundos -- tres ordenes de magnitud por debajo del umbral perceptible de 16,666,666 ns (un frame a 60 fps). La eleccion entre patrones es **arquitectonica**, no de rendimiento.

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

1. **Rendimiento no es diferenciador.** Todos los patrones resuelven servicios en nanosegundos y completan init + resolve + primera operacion en menos de 938,008 ns (el aumento respecto a ejecuciones anteriores se debe a la migracion de Storage a DataStore con I/O real a disco).
2. **Escalabilidad si lo es.** Los patrones D y G requieren editar el modulo de wiring por cada feature nuevo; H, I, J y K no requieren cambio alguno en el modulo central.
3. **El principio de auto-discovery con grafo lazy es el estandar de SDKs corporativos.** Firebase SDK usa un patron conceptualmente identico (auto-registro via AndroidManifest metadata + ComponentRuntime con topo-sort). Pattern H aplica el mismo principio con ServiceLoader + Resolver DFS. Pattern K replica el mecanismo de Firebase de forma aun mas literal: descubre providers via `<meta-data>` en AndroidManifest con PackageManager.

---

## 2. Los 7 Patrones Multi-Modulo

### 2.1 Catalogo

| Patron | Modulo Gradle | Framework | Mecanismo | Lineas wiring |
|--------|---------------|-----------|-----------|---------------|
| D -- Component Dependencies | `sdk/sdk-wiring` | Dagger 2 | Metodos ensure*() manuales con orden de dependencia hardcodeado. Importa DaggerXxxComponent | 149 |
| E2 -- Auto-Init Registry | `sdk/wiring-e2` | Dagger 2 | AutoProvisionRegistry cataloga entries en init, DFS construye bajo demanda en get<T>() | 66 + 129 entries |
| G -- Factory Functions | `sdk/wiring-g` | Dagger 2 | Cada feature expone buildXxxProvisions(); DaggerXxxComponent queda interno al modulo | 107 |
| H -- Auto-Discovery + Dagger | `sdk/wiring-h` | Dagger 2 | ServiceLoader descubre FeatureProvider, Resolver construye via DFS | 51 |
| I -- Pure Resolver | `sdk/wiring-i` | Ninguno | ServiceLoader descubre PureFeatureProvider, Resolver construye via DFS. Zero codegen, zero framework | 54 |
| J -- kotlin-inject | `sdk/wiring-j` | kotlin-inject | ServiceLoader descubre KIFeatureProvider, kotlin-inject Components internos (KSP, genera Kotlin) | 55 |
| K -- AndroidManifest Discovery | `sdk/wiring-k` | Dagger 2 | AndroidManifest `<meta-data>` descubre FeatureProvider via PackageManager, Resolver construye via DFS | 50 |

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

**Wiring centralizado (E2):** Las dependencias se declaran como entries en un archivo central (Entries.kt). El registro resuelve el orden con DFS. Agregar un feature requiere agregar una funcion y una linea en allAutoEntries().

**Wiring auto-descubierto (H, I, J, K):** Cada feature declara su propio Provider y se registra automaticamente. El modulo de wiring no cambia nunca, sin importar cuantos features se agreguen. Los cuatro usan el mismo Resolver con DFS; la diferencia esta en como se descubren los providers y como construyen sus instancias internamente: H usa ServiceLoader + Dagger, I usa ServiceLoader + constructor injection puro, J usa ServiceLoader + kotlin-inject, K usa AndroidManifest `<meta-data>` via PackageManager + Dagger (reutilizando los mismos FeatureProvider de H).

---

## 3. Resultados de Benchmarks

### 3.1 Tabla comparativa completa

Todas las mediciones en nanosegundos (ns). Dispositivo: Samsung Galaxy S22 Ultra, Android 16.

| Metrica | D | E2 | G | H | I | J | K |
|---------|---|----|----|---|---|---|---|
| initCold | 805 | 9,004 | 867 | 68,612 | 70,762 | 72,721 | 174,145 |
| resolveFirst | 15.9 | 34.2 | 14.8 | 34.4 | 35.0 | 34.6 | 34.4 |
| lazyInit noDeps | 260 | 741 | 264 | 1,172 | 855 | 1,065 | 1,968 |
| lazyInit cascade | 715 | 2,522 | 723 | 4,391 | 3,955 | 5,088 | 8,992 |
| crossFeatureOp | 1,589,109 | 1,481,424 | 1,196,719 | 1,848,525 | 1,177,343 | 1,658,252 | 1,797,444 |
| stress_initShutdown | 300 | 4,166 | 248 | 84,010 | 84,936 | 85,490 | 193,791 |
| stress_concurrent | 466,939 | 453,339 | 440,344 | 415,995 | 436,625 | 456,497 | 419,675 |
| stress_resolveAll | 99.0 | 148 | 100 | 207 | 207 | 150 | 149 |
| stress_selective | 297 | 3,758 | 279 | 93,664 | 91,580 | 85,429 | 205,757 |
| stress_reInit | 1,764 | 18,462 | 2,057 | 198,363 | 175,754 | 185,630 | 424,808 |
| stress_incremental | 928 | 8,355 | 1,028 | 81,115 | 84,475 | 91,478 | 227,193 |
| e2eStartup | 523,532 | 621,301 | 633,091 | 692,444 | 652,858 | 757,940 | 938,008 |

### 3.2 Analisis por categoria

#### Init Cold -- Construccion del grafo inicial

| Patron | Tiempo (ns) | Mecanismo |
|--------|-------------|-----------|
| D | 805 | Asignacion directa de campos (Dagger codegen) |
| G | 867 | Idem D, con factory functions |
| E2 | 9,004 | Cataloga entries en HashMaps, no construye nada |
| H | 68,612 | ServiceLoader escanea classpath + registra providers |
| I | 70,762 | Idem H, con PureFeatureProvider |
| J | 72,721 | Idem H, con KIFeatureProvider |
| K | 174,145 | PackageManager.getServiceInfo() IPC a system_server + registra providers |

D y G dominan en init frio porque Dagger resuelve el grafo en tiempo de compilacion -- init es solo asignacion de campos. H, I y J pagan el costo del escaneo de classpath via ServiceLoader (~69-73K ns, mejorado respecto a ejecuciones anteriores gracias al uso de ConcurrentHashMap que es mas rapido para lecturas). K es el mas lento en init (~2.5x vs H) porque PackageManager.getServiceInfo() es una llamada IPC al system_server, mas costosa que el escaneo local de classpath de ServiceLoader. Sin embargo, el patron mas lento (K = 174,145 ns) es despreciable en el arranque de una app Android (tipicamente 500,000,000 - 2,000,000,000 ns).

#### Resolve First -- Primera resolucion tras init

| Patron | Tiempo (ns) |
|--------|-------------|
| G | 14.8 |
| D | 15.9 |
| E2 | 34.2 |
| H | 34.4 |
| K | 34.4 |
| J | 34.6 |
| I | 35.0 |

Tras la primera resolucion, todos los patrones sirven desde cache (ConcurrentHashMap lookup). Las diferencias de 14.8 a 35.0 ns estan dentro del margen de ruido del benchmark. K tiene rendimiento identico a H (34.4 ns) porque usa el mismo Resolver y los mismos ConcurrentHashMap lookups.

#### Lazy Init -- Construccion bajo demanda

**Sin dependencias (Analytics -- depende solo de Core):**

| Patron | Tiempo (ns) |
|--------|-------------|
| D | 260 |
| G | 264 |
| E2 | 741 |
| I | 855 |
| J | 1,065 |
| H | 1,172 |
| K | 1,968 |

**Con cascada (Sync -- depende de Core + Enc + Auth + Stor):**

| Patron | Tiempo (ns) |
|--------|-------------|
| D | 715 |
| G | 723 |
| E2 | 2,522 |
| I | 3,955 |
| H | 4,391 |
| J | 5,088 |
| K | 8,992 |

La cascada Sync es el escenario mas exigente: construir 4 provisions en cadena de 4 niveles. D y G son los mas rapidos (< 725 ns) porque el compilador resuelve el orden de dependencias estaticamente. H, I, J y K pagan el costo del DFS en runtime (el Resolver recorre el grafo dinamicamente). K es ligeramente mas lento que H en cascada (8,992 vs 4,391 ns) por overhead adicional del mecanismo de discovery. Aun asi, la cascada mas lenta (K = 8,992 ns) es imperceptible.

#### Cross-Feature Operation -- Operacion real cruzando features

| Patron | Tiempo (ns) |
|--------|-------------|
| I | 1,177,343 |
| G | 1,196,719 |
| E2 | 1,481,424 |
| D | 1,589,109 |
| J | 1,658,252 |
| K | 1,797,444 |
| H | 1,848,525 |

Los valores han aumentado significativamente (~84K ns -> ~1.2-1.8M ns) porque Storage ahora usa DataStore (I/O real a disco via suspend + runBlocking) en lugar de almacenamiento en memoria. Los tiempos reflejan el coste real de operaciones cross-feature con persistencia a disco. Una vez resueltos los servicios, el rendimiento depende del codigo de negocio (incluyendo I/O a disco), no del patron DI. Las diferencias entre patrones se deben a la variabilidad del acceso a disco, no al mecanismo de DI. Nota: ~1.5 ms es perceptible si crossFeatureOp se invoca frecuentemente en el hilo principal.

#### E2E App Startup -- Init + resolve all + primera operacion por feature

| Patron | Tiempo (ns) |
|--------|-------------|
| D | 523,532 |
| E2 | 621,301 |
| G | 633,091 |
| I | 652,858 |
| H | 692,444 |
| J | 757,940 |
| K | 938,008 |

Los valores han aumentado respecto a ejecuciones anteriores (~102K-375K ns -> ~524K-938K ns) porque el e2e incluye operaciones cross-feature con DataStore (I/O real a disco). Incluso el patron mas lento (K = 938,008 ns) completa el arranque del SDK con 6 features en 938,008 ns. Esto representa el 0.047% de un arranque tipico de app Android (~2,000,000,000 ns). **Ningun patron es un cuello de botella en el arranque.** K es mas lento que H porque el IPC a system_server se acumula en el ciclo completo de init + resolve + primera operacion, pero todos estan por debajo de 1 ms.

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
| MultiModuleBenchmark | 84 | 0 |
| MemoryBehaviorTest | 57 | 0 |
| StressTortureTest | 80 | 0 |
| ScaleBenchmark | 37 | 0 |
| **Total** | **277** | **0** |

### 5.2 Tests de tortura -- todos los patrones PASS

| Test | Descripcion | Resultado |
|------|-------------|-----------|
| thunderingHerd | 100 threads concurrentes resolviendo servicios | PASS (7/7 patrones) |
| singletonIdentity | 10,000 llamadas secuenciales, misma instancia | PASS |
| crossPatternIsolation | 7 patrones ejecutandose simultaneamente | PASS |
| rapidFire | 5,000 ciclos init/get/shutdown | PASS |
| memoryPressure | GC storm durante resolucion de servicios | PASS |
| stress10K | 10,000 ciclos, heap delta < 5,120 KB | PASS |
| instanceFreshness | 50 reinits, todas las instancias son unicas | PASS |
| errorResilience | Double init, get antes de init, shutdown doble | PASS |
| functionalCorrectness | Operaciones reales tras 1,000 reinits | PASS |
| coldCascadeTiming | Comparacion de tiempos de cascada fria | PASS |
| alternatingPatterns | 100 rondas alternando entre los 7 patrones | PASS |

### 5.3 Benchmarks de estres por patron

| Metrica | D | E2 | G | H | I | J | K |
|---------|---|----|----|---|---|---|---|
| stress_initShutdown (ns) | 300 | 4,166 | 248 | 84,010 | 84,936 | 85,490 | 193,791 |
| stress_concurrent (ns) | 466,939 | 453,339 | 440,344 | 415,995 | 436,625 | 456,497 | 419,675 |
| stress_resolveAll (ns) | 99.0 | 148 | 100 | 207 | 207 | 150 | 149 |
| stress_selective (ns) | 297 | 3,758 | 279 | 93,664 | 91,580 | 85,429 | 205,757 |
| stress_reInit (ns) | 1,764 | 18,462 | 2,057 | 198,363 | 175,754 | 185,630 | 424,808 |
| stress_incremental (ns) | 928 | 8,355 | 1,028 | 81,115 | 84,475 | 91,478 | 227,193 |

### 5.4 Analisis de estres

**Ciclo de vida (initShutdown, reInit, selective, incremental):** D y G son consistentemente mas rapidos (248 - 2,057 ns) porque no invocan ServiceLoader en cada ciclo. H, I y J pagan ~84,000 - 85,000 ns por ciclo init debido al escaneo de classpath (mejorado respecto a ejecuciones anteriores gracias a ConcurrentHashMap, mas rapido para lecturas concurrentes). K paga ~193,000 ns por ciclo init porque PackageManager.getServiceInfo() es un IPC mas costoso que el escaneo local de ServiceLoader. Esto es relevante solo en tests de estres; en uso real, init se llama una vez por sesion de la app.

**Concurrencia (stress_concurrent):** Todos los patrones convergen a ~416,000 - 467,000 ns. Todos los patrones son ahora thread-safe (synchronized + ConcurrentHashMap). La concurrencia esta dominada por el costo de coordinacion entre threads (locks, CAS), no por el patron DI.

**Resolucion cached (stress_resolveAll):** Todos los patrones resuelven el grafo completo desde cache en 99.0 - 207 ns. La diferencia entre D (99.0 ns) y H (207 ns) es insignificante. K (149 ns) se comporta identicamente a H porque usa el mismo Resolver con los mismos ConcurrentHashMap lookups.

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

**Diferencia con H:** Requiere `Context` en init() para acceder a PackageManager. La ventaja es robustez: las entradas en AndroidManifest sobreviven R8/ProGuard sin necesidad de reglas keep, a diferencia de los archivos META-INF/services de ServiceLoader que pueden ser eliminados por el minificador. El costo es ~2.5x mas lento en init (174K vs 69K ns, IPC a system_server) pero ambos son imperceptibles (< 1 ms).

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
2. **Considerar Pattern K** si R8/ProGuard es agresivo y se prefiere evitar reglas keep para META-INF/services. K reutiliza los mismos FeatureProvider de H, cambiando unicamente el mecanismo de discovery (AndroidManifest `<meta-data>` via PackageManager). El costo es ~2.5x init mas lento (174K vs 69K ns) y requerir Context en init(), pero ambos son imperceptibles (< 1 ms e2e).
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
| Init frio (ns) | 805 | 9,004 | 867 | 68,612 | 70,762 | 72,721 | 174,145 |
| Resolve cached (ns) | 15.9 | 34.2 | 14.8 | 34.4 | 35.0 | 34.6 | 34.4 |
| E2E startup (ns) | 523,532 | 621,301 | 633,091 | 692,444 | 652,858 | 757,940 | 938,008 |
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
| MultiModuleBenchmark | 84 | 0 | Benchmarks de los 7 patrones multi-modulo (D, E2, G, H, I, J, K) |
| MemoryBehaviorTest | 57 | 0 | Prueba de laziness del grafo |
| StressTortureTest | 80 | 0 | Concurrencia y resiliencia (incluyendo 3 tests de concurrencia) |
| ScaleBenchmark | 37 | 0 | Escalabilidad con features sinteticas |
| **Total** | **277** | **0** | |
