# Resultados de Benchmark -- Patrones Monoliticos

Mediciones de rendimiento para los cuatro patrones monoliticos con benchmark real
(Dagger B, Dagger C, Koin, Hybrid). El patron A (educativo) no tiene benchmarks
dedicados porque no ofrece lazy init ni features separadas -- todo esta en un unico
`@Component`.

Para la descripcion de cada patron, ver [patterns-overview.md](patterns-overview.md).

---

## 1. Dispositivo y condiciones

| Parametro | Valor |
|-----------|-------|
| Dispositivo | Samsung Galaxy S22 Ultra |
| SoC | Snapdragon 8 Gen 1 |
| Sistema operativo | Android 16 |
| Framework de benchmark | Jetpack Benchmark 1.4.0 |
| Metrica | Mediana (nanosegundos) |
| Iteraciones | Minimo 5 warmup + 5 medicion (auto-ajuste del framework) |
| Condiciones | Pantalla apagada, modo avion, perfil de rendimiento fijo |
| Facades | Facades reales del SDK (DaggerBSdk, DaggerCSdk, KoinSdk, bridge) |

Todos los benchmarks ejecutan operaciones a traves de las facades publicas del SDK,
no contra los Components internos. Esto mide el coste real que experimenta el consumidor.

---

## 2. Tabla principal de resultados

| Test | Dagger B | Dagger C | Koin | Hybrid | Mejor |
|------|----------|----------|------|--------|-------|
| **initCold** | 1,749 ns | 2,302 ns | 46,953 ns | 43,503 ns | Dagger B |
| **resolveFirst** | 7.5 ns | 26.3 ns | 647 ns | 1.9 ns | Hybrid |
| **resolveCached (bridge)** | -- | -- | -- | 1.9 ns | Hybrid |
| **lazyInit noDeps** | 246 ns | 309 ns | 5,419 ns | -- | Dagger B |
| **lazyInit cascade** | 1,182 ns | -- | 22,993 ns | -- | Dagger B |
| **crossFeatureOp** | 82,667 ns | 82,422 ns | ~102,000 ns | 85,234 ns | Dagger C |

### Definicion de cada test

- **initCold**: Crear el SDK desde cero con todas las features (shutdown + init).
  Incluye creacion de Components, instanciacion de servicios y registro en el grafo.
- **resolveFirst**: Primer acceso a un servicio singleton despues de init. Mide el
  coste de la primera resolucion (Dagger: acceso a campo volatil; Koin: HashMap lookup;
  Hybrid: campo Dagger cached post-bridge).
- **resolveCached (bridge)**: Acceso repetido a un servicio a traves del bridge Dagger
  del patron Hybrid. Identico al resolve de Dagger puro porque el `@Singleton` ya cacheo
  la instancia.
- **lazyInit noDeps**: `getOrInitModule()` de una feature sin dependencias (Analytics).
  Mide el coste de crear un Component aislado.
- **lazyInit cascade**: `getOrInitModule()` de una feature con dependencias transitivas
  (Sync necesita Auth + Storage + Encryption). Mide la cascada completa.
- **crossFeatureOp**: Operacion `sync.sync()` que invoca Auth, Storage y Encryption
  internamente. Mide el rendimiento post-init de operaciones de negocio reales.

---

## 3. Analisis por categoria

### 3.1 Inicializacion en frio (initCold)

| Ranking | Patron | Tiempo | Factor vs mejor |
|---------|--------|--------|-----------------|
| 1 | Dagger B | 1,749 ns | 1.0x |
| 2 | Dagger C | 2,302 ns | 1.3x |
| 3 | Hybrid | 43,503 ns | 24.9x |
| 4 | Koin | 46,953 ns | 26.8x |

Dagger B y C crean Components Dagger (codegen puro, sin reflexion). Los objetos generados
por KSP son factories directas -- la inicializacion es construccion de objetos Java.

Koin e Hybrid pagan el coste del contenedor Koin: creacion de `koinApplication`,
registro de modulos en HashMaps internos, y resolucion eager de singletons. Hybrid
ademas crea el `SdkBridgeComponent` Dagger, pero este coste es marginal frente al
de Koin.

**En contexto:** 46,953 ns = ~47 microsegundos. Un `Application.onCreate()` tipico
tarda 200-500 milisegundos. La inicializacion del SDK es menos del 0.01% del arranque.

### 3.2 Resolucion de servicios (resolveFirst)

| Ranking | Patron | Tiempo | Factor vs mejor |
|---------|--------|--------|-----------------|
| 1 | Hybrid | 1.9 ns | 1.0x |
| 2 | Dagger B | 7.5 ns | 3.9x |
| 3 | Dagger C | 26.3 ns | 13.8x |
| 4 | Koin | 647 ns | 340x |

Hybrid gana porque el `@Singleton` de Dagger cachea la instancia en un campo. Acceder
a un campo es ~2 ns. Dagger B accede a un campo volatil con double-check locking (~7.5 ns).
Dagger C itera sobre los `FeatureInitializer` registrados buscando quien provee el
servicio (~26 ns).

Koin resuelve via HashMap lookup + type matching (~647 ns). Es el mas lento en
resolucion, pero sigue siendo sub-microsegundo.

### 3.3 Inicializacion lazy (lazyInit)

| Ranking | Patron | noDeps | cascade |
|---------|--------|--------|---------|
| 1 | Dagger B | 246 ns | 1,182 ns |
| 2 | Dagger C | 309 ns | -- |
| 3 | Koin | 5,419 ns | 22,993 ns |

Lazy init mide la capacidad de anadir features al SDK despues de `init()`. Dagger crea
un nuevo Component por feature (~250-300 ns). Koin ejecuta `loadModules()` que registra
nuevos bindings en el HashMap del `koinApplication` (~5,400 ns).

La cascada de Dagger B (Sync con 3 dependencias transitivas) tarda ~1.2 microsegundos.
La de Koin tarda ~23 microsegundos. Ambos son imperceptibles para el usuario.

El Hybrid no tiene benchmarks de lazy init separados porque las features lazy
no pasan por el bridge Dagger -- se acceden directamente via `KoinSdk.get()`,
con el mismo rendimiento que Koin puro.

### 3.4 Operaciones cross-feature (crossFeatureOp)

| Ranking | Patron | Tiempo | Factor vs mejor |
|---------|--------|--------|-----------------|
| 1 | Dagger C | 82,422 ns | 1.0x |
| 2 | Dagger B | 82,667 ns | 1.0x |
| 3 | Hybrid | 85,234 ns | 1.03x |
| 4 | Koin | ~102,000 ns | 1.24x |

La operacion `sync.sync()` invoca logica de negocio real (Auth, Storage, Encryption).
Los tiempos son dominados por la logica de negocio, no por la resolucion DI. La
diferencia entre el mas rapido (82,422 ns) y el mas lento (~102,000 ns) es ~20
microsegundos -- ruido termico del procesador.

**Conclusion:** Post-init, todos los patrones son equivalentes. La eleccion del
framework DI es invisible en operaciones de negocio.

---

## 4. Conclusiones

### 4.1 Rendimiento puro

Dagger B es el patron monolitico mas rapido en inicializacion (1,749 ns) y lazy init
(246 ns). Hybrid es el mas rapido en resolucion post-init (1.9 ns) gracias al cache
`@Singleton` de Dagger.

### 4.2 Diferencias imperceptibles

Todas las diferencias estan por debajo de 1 milisegundo:

- initCold: 1.7 microsegundos (Dagger B) vs 47 microsegundos (Koin) = 45 microsegundos de diferencia
- resolveFirst: 1.9 ns (Hybrid) vs 647 ns (Koin) = 645 nanosegundos de diferencia
- crossFeatureOp: ~82 microsegundos vs ~102 microsegundos = ~20 microsegundos de diferencia

Un frame de Android a 60 FPS son 16.6 milisegundos. Ninguna de estas diferencias
es perceptible por el usuario ni afecta al rendimiento de la aplicacion.

### 4.3 Trade-offs reales

La eleccion entre patrones monoliticos no deberia basarse en rendimiento. Los
trade-offs reales son:

| Criterio | Dagger (B/C) | Koin | Hybrid |
|----------|-------------|------|--------|
| Errores en compilacion | Si | No | Parcial (bridge Dagger) |
| KMP compatible | No | Si | Si (SDK Koin, bridge Android) |
| Codegen / build time | ~2-4s extra (KSP) | 0s extra | ~1s extra (bridge KSP) |
| Complejidad estructural | Media-Alta | Baja | Alta |
| Escalabilidad 50+ | B: No (God Object); C: Si | Si | Si |

### 4.4 Recomendacion

Elegir el patron basandose en las restricciones del equipo y del proyecto:

- **Compile-time safety critica** -- Dagger B o C
- **Velocidad de desarrollo / equipo junior** -- Koin
- **SDK KMP con consumidores Dagger** -- Hybrid
- **Rendimiento** -- Irrelevante. Todos son suficientes.
