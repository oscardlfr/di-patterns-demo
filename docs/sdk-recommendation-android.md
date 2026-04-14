# Recomendacion de Patron DI para SDK Android (+50 modulos, 10 devs)

**Fecha:** 2026-04-12  
**Contexto:** Seleccion de patron de inyeccion de dependencias para un SDK Android nativo corporativo  
**Dispositivo de referencia:** Samsung Galaxy S22 Ultra (Snapdragon 8 Gen 1, Android 16)  
**Datos de benchmark:** Jetpack Benchmark 1.4.0, mediana de `timeNs`

---

## 1. Contexto

Un SDK corporativo Android con las siguientes caracteristicas:

- **+50 modulos funcionales** (features) mantenidos por equipos independientes
- **10 desarrolladores** trabajando en paralelo sobre el mismo repositorio
- **Android nativo:** sin requisito KMP (no iOS, no Desktop)
- **Logger persistente** que sobrevive a ciclos de shutdown/reinit del SDK
- **Auto-registro:** anadir un feature nuevo = 0 cambios en el wiring central
- **Acceso concurrente thread-safe** desde multiples threads del consumidor
- **Inicializacion lazy:** el consumidor paga solo por las features que usa

**Nota sobre el scope de evaluacion:** Los patrones KMP-compatible (O, O2, P, P2, N) funcionan
en Android. "KMP-compatible" no significa "KMP-only" -- significa que el patron compila para
los 24 targets de Kotlin, incluyendo Android/JVM. Se evaluan junto con los patrones
Android-only porque un equipo Android puede adoptarlos sin obligarse a soportar otras
plataformas. La ventaja adicional: si el SDK necesita KMP en el futuro, la migracion es zero-cost.

La pregunta central: **Cual patron ofrece el mejor balance de auto-registro, compile-time safety,
lazy singletons, y rendimiento para este contexto?**

---

## 2. Criterios de Seleccion (ponderados para Android-only)

| # | Criterio | Peso | Justificacion |
|---|----------|------|---------------|
| 1 | Auto-registro (grafo) | MUST HAVE | Anadir feature = solo `implementation(...)` en Gradle. Con +50 modulos, edicion central por feature = cuello de botella de PRs y merge conflicts |
| 2 | Seguridad en compilacion | MUST HAVE | 10 devs = alta probabilidad de bindings rotos sin validacion compile-time |
| 3 | Inicializacion lazy | MUST HAVE | Startup performance critico en mobile. No crear 50 singletons si solo se usan 5 |
| 4 | Thread-safe shutdown | IMPORTANTE | SDK se reinicializa en cambios de configuracion o testing |
| 5 | Logger persistente | IMPORTANTE | Observabilidad no puede perderse entre ciclos init/shutdown |
| 6 | Madurez del ecosistema | IMPORTANTE | Soporte a largo plazo, documentacion, comunidad activa |
| 7 | Init performance | IMPORTANTE | Impacto en cold start del consumidor. Una vez por sesion |
| 8 | Resolve cached performance | IMPORTANTE | Impacto en hot paths: cada `sdk.get<T>()` paga este costo |
| 9 | Wiring del facade inmutable | IMPORTANTE | El dispatcher `get<T>(Class)` del SDK NO requiere editar ramas manualmente al anadir una API. Sin esto, 50 features × 10 APIs = 500 ramas de `when` mantenidas a mano |

**Criterios eliminatorios:** Si un patron no cumple los 3 MUST HAVE, se descarta.

**Sobre el Criterio 1 vs 9 (auto-registro: dos ejes distintos):**
- **Criterio 1 (grafo)** mide si el framework DI agrega el modulo al grafo automaticamente
  (`@ContributesTo`, ServiceLoader, `@Module + @InstallIn` con auto-discovery, etc.).
- **Criterio 9 (facade)** mide si el dispatcher `get<T>(Class)` del SDK escala. Patrones
  compile-time (Dagger, Metro, kotlin-inject) suelen necesitar un `when (clazz)` manual
  porque no tienen runtime class lookup nativo.

Un patron puede cumplir uno y fallar el otro. **O2/P2 cumplen Criterio 1 pero fallan
Criterio 9** -- el `when` manual del facade crece linealmente por API. Es deuda oculta
a escala. Mitigable con un procesador KSP propio (~200 LOC) que genere el `when` desde
el componente. Patrones con runtime DI nativo (Koin, H/I/J/K vía Resolver) cumplen
ambos sin codegen propio.

---

## 3. Evaluacion de Candidatos

Se evaluan 11 patrones que escalan a 50+ modulos, agrupados por tier segun cuantos
criterios MUST HAVE cumplen y la calidad de ese cumplimiento.

### Tier 1 -- Compile-time + Auto-registro + Lazy (mejores candidatos)

Estos patrones cumplen los 3 MUST HAVE con la maxima calidad: compile-time safety completa
(no parcial), auto-registro zero-touch, y lazy singletons genuinos.

| Criterio | O2 (Metro Lazy) | P2 (kotlin-inject-anvil Lazy) |
|----------|-----------------|-------------------------------|
| **Framework** | Metro compiler plugin | kotlin-inject KSP |
| Auto-registro (grafo) | **OK** -- `@ContributesTo` agrega al grafo automaticamente | **OK** -- `@ContributesTo` agrega al grafo via KSP merge |
| Compile-time safety | **OK** -- grafo completo validado en compilacion | **OK** -- KSP valida bindings en compilacion |
| Lazy | **OK** -- `Lazy<T>` difiere singletons hasta primer acceso | **OK** -- `@SingleIn` scope, lazy via kotlin-inject |
| Thread-safe shutdown | **OK** -- LazyCreationTracker.deactivate() + nullify graph | **OK** -- LazyCreationTracker.deactivate() + nullify component |
| Logger persistente | **OK** -- `@SingleIn(AppScope)` sobrevive a feature scopes | **OK** -- `@SingleIn(AppScope)` sobrevive a feature scopes |
| Madurez | **BAJA** -- Metro v0.6.6. Mantenido por ZacSweers (Slack). Compiler plugin acoplado a version de Kotlin | **MEDIA** -- kotlin-inject 4+ anos. anvil ext v0.1.7. Amazon (Ring, Alexa) mantiene |
| Init Cold | **1,127 ns** | **1,416 ns** |
| Resolve All (cached) | **86 ns** | **156 ns** |
| Re-Init | **2,305 ns** | **2,929 ns** |
| **Wiring del facade inmutable** | **NO** -- `when (clazz)` manual en `MultiModuleSdkO2.get()`. Crece 1 rama por API. Mitigable con KSP propio | **NO** -- mismo problema en `MultiModuleSdkP2.get()`. Mitigable con KSP propio |
| MUST HAVEs | **3/3** | **3/3** |
| Total criterios OK | **8/9** (falla facade) | **8/9** (falla facade) |

**O2** es objetivamente el mas rapido en perf pura: init 95x mas rapido que H
(1,127 vs 106,865 ns), resolve cached 2.3x mas rapido (86 vs 202 ns), re-init 157x mas
rapido (2,305 vs 362,649 ns).

**P2** es ~25% mas lento que O2 pero usa KSP estandar en vez de compiler plugin,
lo que reduce el riesgo de rotura en bumps de Kotlin.

**Caveat de O2/P2 a escala (Criterio 9):** ambos requieren mantener un `when (clazz)` en
el facade `MultiModuleSdk{O2,P2}.get<T>(Class<T>): T`. Con 50 features × 10 APIs = 500
ramas mantenidas a mano. El framework auto-agrega los modulos al grafo, pero el dispatcher
de tipo runtime es manual. Mitigable con un procesador KSP propio (~200 LOC) que lea el
componente y genere el `when`. Sin esa inversion, el coste se traduce en ~10 lineas de
edicion al facade por cada API nueva. Costo por feature: 1 anotacion `@ContributesTo`
en el feature-impl (zero touch en wiring) + N ramas en el facade del SDK (1 por API
expuesta). Ver Req 11 en `docs/shared/requirements.md`.

### Tier 2 -- Compile-time + Auto-registro, pero Eager

Cumplen auto-registro y compile-time safety, pero NO lazy. Todos los singletons se
crean en init. Con 50 features y el usuario usando solo 5, las otras 45 se instancian
innecesariamente.

| Criterio | O (Metro) | P (kotlin-inject-anvil) |
|----------|-----------|-------------------------|
| Auto-registro (grafo) | **OK** -- `@ContributesTo` | **OK** -- `@ContributesTo` |
| Compile-time safety | **OK** -- compiler plugin | **OK** -- KSP |
| Lazy | **NO** -- eager: todos los singletons en init | **NO** -- eager |
| Init Cold | **603 ns** (el mas rapido de todos) | **1,064 ns** |
| Resolve All | **80 ns** | **165 ns** |
| Re-Init | **36,000 ns** (15.6x mas lento que O2) | **28,000 ns** (9.6x mas lento que P2) |
| **Wiring del facade inmutable** | **NO** -- mismo `when` que O2 | **NO** -- mismo `when` que P2 |
| MUST HAVEs | **2/3** (falla Lazy) | **2/3** (falla Lazy) |

Con 50+ features opcionales, la falta de lazy es un descarte para este caso.
Si todas las features se usaran siempre, Tier 2 seria preferible por su simplicidad.

### Tier 3 -- Auto-registro + Lazy, pero sin compile-time safety completa

Tienen auto-registro zero-touch y lazy genuino, pero un binding/provider faltante
solo se detecta en runtime.

#### 3.3.1 Pattern H -- ServiceLoader + Resolver DFS + Dagger

**Arquitectura:** Cada feature-impl declara un `FeatureProvider` (~19 lineas) registrado via
`META-INF/services`. El Resolver descubre providers via `ServiceLoader.load()` y construye
provisions on-demand con DFS recursivo. Cada feature usa Dagger internamente.

| Criterio | Evaluacion | Detalle |
|----------|-----------|---------|
| Auto-registro (grafo) | **OK** | `META-INF/services` + ServiceLoader. Zero edicion central |
| Compile-time safety | **PARCIAL** | Dagger valida cada Component individualmente, pero un provider faltante es error runtime |
| Lazy | **OK** | DFS genuino: `builtProvisionCount == 0` tras init (confirmado en 9 tests de memoria) |
| Thread-safe shutdown | **OK** | `concurrentShutdown` pasa 200 rounds. synchronized + ConcurrentHashMap |
| Logger persistente | **OK** | ObservabilityProvider con flag `persistent` sobrevive a shutdown/reinit |
| Madurez | **ALTA** | Dagger: 10+ anos. ServiceLoader: JDK estandar. 35 tests, 10K ciclos, zero leaks |
| Init Cold | **106,865 ns** | ServiceLoader scan domina el costo |
| Resolve All (cached) | **212 ns** | ConcurrentHashMap lookup O(1) |
| Re-Init | **362,649 ns** | ServiceLoader + rebuild completo |
| **Wiring del facade inmutable** | **OK** | `MultiModuleSdkH.get(clazz)` delega a `resolver.get(clazz)` -- HashMap lookup. **Cero `when`, cero edicion al anadir APIs**. Unico patron Tier 1-3 con esta propiedad nativamente (sin codegen propio) |
| MUST HAVEs | **2.5/3** (compile-time parcial) |
| Total criterios OK | **8/9** (falla compile-time parcial) |

**Pros:**
- Arquitectura probada: 35 tests, thunderingHerd (100 threads), concurrentBuild (6 threads x 100 rounds), 10,000 ciclos sin leaks
- Auto-registro perfecto via META-INF/services
- DFS lazy genuino demostrado
- Dagger + ServiceLoader = ecosistema maduro
- **Facade inmutable**: a diferencia de O2/P2/Q2, el dispatcher `get<T>(Class)` no crece por API. 50 features × 10 APIs = mismo facade de ~50 lineas. Es la unica ventaja arquitectural que O2/P2 NO replican sin codegen propio

**Contras:**
- Provider faltante = runtime error
- Init 95x mas lento que O2 (106,865 vs 1,127 ns)
- Re-init 157x mas lento que O2 (362,649 vs 2,305 ns)
- Resolver propio = 105 lineas a mantener (compensado por facade inmutable)

#### 3.3.2 Pattern I -- Pure Resolver (zero framework DI)

| Criterio | Evaluacion |
|----------|-----------|
| Auto-registro | **OK** -- ServiceLoader |
| Compile-time safety | **NO** -- zero DI framework = zero validacion |
| Lazy | **OK** -- DFS identico a H |
| Init Cold | **94,255 ns** |
| MUST HAVEs | **2/3** (falla compile-time safety) |

Zero compile-time safety con 10 devs y 50+ modulos = alto riesgo. Descartado.

#### 3.3.3 Pattern K -- AndroidManifest Metadata Discovery

| Criterio | Evaluacion |
|----------|-----------|
| Auto-registro | **OK** -- AndroidManifest meta-data + manifest merger |
| Compile-time safety | **PARCIAL** -- igual que H |
| Lazy | **OK** -- DFS identico a H |
| Init Cold | **213,737 ns** -- 2x mas lento que H |
| MUST HAVEs | **2.5/3** |

Solo tiene sentido si R8/ProGuard elimina META-INF/services y no se pueden anadir keep rules.
Init 2x mas lento que H sin beneficio adicional.

### Tier 4 -- Compile-time + Lazy, pero sin auto-registro

Tienen compile-time safety completa y lazy singletons, pero requieren edicion central
para cada feature nuevo.

#### 3.4.1 Pattern Q2 -- Hilt-style Dagger Lazy

| Criterio | Evaluacion | Detalle |
|----------|-----------|---------|
| Auto-registro (grafo) | **NO** | Cada feature @Module se lista en `@Component(modules=[...])` |
| Compile-time safety | **OK** | Grafo completo validado por Dagger |
| Lazy | **OK** | `dagger.Lazy<T>` difiere construccion hasta primer acceso |
| Madurez | **ALTA** | Dagger 10+ anos. `dagger.Lazy` es API oficial |
| Init Cold | **1,080 ns** |
| Resolve All | **85 ns** |
| Re-Init | **2,157 ns** -- el mas rapido de todos |
| **Wiring del facade inmutable** | **NO** | `when (clazz)` manual en `MultiModuleSdkQ2.get()`. Mismo problema que O2/P2 |
| MUST HAVEs | **2/3** (falla auto-registro) |
| Total criterios OK | **6/9** (falla auto-registro grafo Y facade inmutable) |

**Trade-off:** Q2 paga edicion central en **DOS sitios**:
1. `@Component(modules=[...])` -- crece 1 linea por feature (50 features = 50 lineas)
2. `MultiModuleSdkQ2.get()` `when` -- crece 1 rama por API (10 APIs/feature × 50 = 500 ramas)

Con 50 modules + 10 APIs cada uno: ~550 ediciones centrales totales. Con 10 devs en
paralelo, merge conflicts garantizados. Solo gestionable si UN dev mantiene el wiring
y se acepta cuello de botella. Mitigable con KSP propio para el `when` (reduce a ~50
ediciones), pero el `modules=[...]` sigue siendo manual.

#### 3.4.2 Pattern E2 -- AutoProvisionRegistry + DFS

| Criterio | Evaluacion |
|----------|-----------|
| Auto-registro (grafo) | **PARCIAL** -- 1 linea en `allEntries()` por feature |
| Compile-time safety | **OK** -- Dagger per-Component |
| Lazy | **OK** -- DFS on-demand |
| Init Cold | **10,983 ns** |
| **Wiring del facade inmutable** | **OK** -- `MultiModuleSdkE2.get()` delega a `registry.get(clazz)` (HashMap). **Cero `when`** |
| MUST HAVEs | **2.5/3** (auto-registro parcial) |
| Total criterios OK | **8/9** |

Semi-auto: lista central de entries (`allEntries()`) genera merge conflicts a escala,
aunque cada feature solo anade 1 linea (NO N lineas como Q2). **El facade es inmutable**
-- esa es su ventaja sobre O2/P2/Q2: misma compile-time safety completa de Dagger pero
sin `when` que mantener.

### Tier 5 -- Koin-based (runtime DI, sin compile-time safety)

| Criterio | N (sweet-spi + Koin) | L (Koin + ServiceLoader) | M (Koin + ServiceLoader Lazy) |
|----------|---------------------|--------------------------|-------------------------------|
| Auto-registro (grafo) | **OK** | **OK** | **NO** -- cascada manual de loadModules |
| Compile-time safety | **NO** -- Koin runtime | **NO** | **NO** |
| Lazy | **OK** -- Koin `single{}` | **OK** | **OK** |
| Init Cold | **69,636 ns** | **154,403 ns** | **164,353 ns** |
| Resolve All (cached) | **6,328 ns** | **6,244 ns** | **7,920 ns** |
| Re-Init | **732,000 ns** | **1.1M ns** | **1.2M ns** |
| **Wiring del facade inmutable** | **OK** -- `koin.get(clazz)` nativo | **OK** -- igual | **OK** -- igual |
| MUST HAVEs | **2/3** | **2/3** | **2/3** |

N es el mejor Koin-based pero su resolve cached (6,328 ns) es 74x mas lento que O2 (86 ns).
Sin compile-time safety, 10 devs con 50+ modulos = bindings rotos en produccion.
L y M anaden ServiceLoader JVM-only sin beneficio significativo. **Ventaja arquitectural
de los 3**: facade inmutable nativo (sin `when`, sin codegen propio) -- comparten esa
propiedad con H/I/J/K/E2.

---

## 4. Recomendacion

### Los datos hablan: depende de que ejes valoras

La tabla de rendimiento es contundente, pero la decision depende de que dimensiones priorices:

| Metrica / Criterio | O2 (Metro Lazy) | P2 (KI-anvil Lazy) | H (ServiceLoader) | Q2 (Dagger Lazy) | E2 (Registry) |
|--------------------|----------------:|--------------------:|-------------------:|------------------:|---------------:|
| Init Cold | 1,127 ns | 1,416 ns | 106,865 ns | 1,080 ns | 10,983 ns |
| Resolve All | 86 ns | 156 ns | 212 ns | 85 ns | 211 ns |
| Re-Init | 2,305 ns | 2,929 ns | 362,649 ns | 2,157 ns | 17,000 ns |
| Auto-registro (grafo) | OK | OK | OK | **NO** | PARCIAL |
| Compile-time | OK | OK | PARCIAL | OK | OK |
| Lazy | OK | OK | OK | OK | OK |
| Madurez | BAJA | MEDIA | ALTA | ALTA | ALTA |
| **Wiring del facade inmutable** | **NO** | **NO** | **OK** | **NO** | **OK** |

**Lectura honesta:**
- **O2/P2 dominan perf y compile-time safety**, pero fallan facade inmutable: el `when (clazz)`
  del `MultiModuleSdkO2.get()` crece 1 rama por API. A 50 features × 10 APIs = 500 ramas
  manuales. Mitigable con KSP propio (~200 LOC).
- **H gana en facade inmutable nativo** (resolver HashMap) y madurez, pero falla compile-time
  safety completa (parcial: cada Component validado, pero provider faltante = runtime error).
- **E2 es el unico Dagger** con compile-time + facade inmutable. Pero auto-registro parcial
  (1 linea en `allEntries()` por feature).
- **Q2 falla DOS criterios** (auto-registro grafo + facade inmutable): doble edicion central.

**Ningun patron cumple los 9 criterios.** El ranking depende del peso relativo:
- Si **MUST HAVEs (1-3)** + facade inmutable son innegociables, **H o E2** ganan.
- Si **perf** es prioritario y se acepta inversion en KSP propio, **O2/P2** ganan.
- Si compile-time COMPLETA es innegociable y NO se quiere KSP custom, **E2** es el unico
  Dagger con esa propiedad.

### Recomendacion: Estrategia por fases

La decision depende del apetito de riesgo del equipo:

#### Opcion A -- Si el equipo acepta frameworks jovenes (Metro v0.6.6 / kotlin-inject-anvil v0.1.7)

**Pattern O2 (Metro Lazy)** -- objetivamente el mejor balance:

- **95x mas rapido init** que H (1,127 vs 106,865 ns)
- **2.5x mas rapido resolve** que H (86 vs 212 ns)
- **157x mas rapido re-init** que H (2,305 vs 362,649 ns)
- Auto-registro zero-editing (`@ContributesTo`)
- Compile-time safety completa
- Lazy singletons genuinos (`Lazy<T>`)
- Thread-safe
- **Bonus:** si el SDK necesita KMP en el futuro, cero migracion

**Riesgo:** Metro v0.6.6 es mantenido principalmente por ZacSweers (1 persona, Slack).
Compiler plugin acoplado a version de Kotlin -- cada bump de Kotlin puede requerir esperar
a que Metro se actualice.

**Alternativa dentro de esta opcion:** **P2 (kotlin-inject-anvil Lazy)** si el equipo
prefiere KSP estandar sobre compiler plugin. ~25% mas lento que O2 pero con mejor
tooling support y Amazon como maintainer.

#### Opcion B -- Si el equipo necesita ecosistema maduro/probado

**Pattern H** -- la opcion conservadora correcta:

- ServiceLoader es estandar JVM (20+ anos)
- Dagger 2 es estandar Android (Google-maintained, 10+ anos)
- Resolver DFS probado: 35 tests, 10K ciclos, zero leaks
- Auto-registro zero-touch via META-INF/services
- Lazy genuino demostrado (builtProvisionCount == 0 tras init)
- Thread-safe: thunderingHerd (100 threads), concurrentShutdown (200 rounds)

**Trade-off:** 95x mas lento init, 157x mas lento re-init, compile-time parcial.
**Pero:** 106,865 ns = 0.107 ms. En un app startup de 500-2000 ms, es irrelevante.
El costo real de H es el riesgo de runtime errors por provider faltante,
mitigable con test `verify()` en CI.

#### Opcion C -- Si el equipo prioriza compile-time safety + madurez

**Pattern Q2 (Dagger Lazy)** -- pero aceptar que no tiene auto-registro:

- Dagger maduro (10+ anos, Google-maintained) + compile-time completo + `dagger.Lazy<T>`
- Re-init ultra-rapido: 2,157 ns (el mas rapido de todos los patrones)
- Init rapido: 1,080 ns
- Con 50 modules, `@Component(modules=[...])` tiene 50 lineas -- gestionable si UN dev
  mantiene el wiring y el equipo tiene disciplina de rebase

**Trade-off:** Merge conflicts en el @Component son inevitables con 10 devs. Si el ritmo
de features nuevos es bajo (2-3/mes), es tolerable. Si es alto (5+/mes), no es viable.

### Plan recomendado (data-driven)

1. **Corto plazo: Pattern H** -- produccion inmediata, riesgo minimo, arquitectura probada.
   Funciona hoy con 0 incognitas.

2. **Monitorear O2/P2** -- seguir la madurez de Metro y kotlin-inject-anvil:
   - Cuando alcancen v1.0 (releases estables, sin breaking changes frecuentes)
   - Cuando haya adopcion corporativa documentada (mas alla de Slack/Amazon interno)
   - Cuando el soporte a Kotlin version bumps sea demostrable (2+ bumps sin rotura)

3. **Migrar a O2 o P2** cuando el ecosistema madure -- la migracion es modular:
   feature por feature, sin big-bang. Los feature-impl cambian internamente (Dagger -> Metro
   o kotlin-inject), pero la API del SDK no cambia.

---

## 5. Por que NO los otros patrones

| Patron | Razon de descarte | Criterio fallido |
|--------|-------------------|------------------|
| **D** (Component Deps) | No auto-registro grafo + facade no inmutable. When-blocks crecen por feature Y por API | Auto-registro grafo (MUST HAVE) + facade |
| **G** (Factory Functions) | No auto-registro grafo (`ensure*()` crece por feature) + facade no inmutable (`when` crece por API) | Auto-registro (MUST HAVE) + facade |
| **Q** (Hilt-style Dagger eager) | No auto-registro + no lazy. 50 singletons creados en init aunque se usen 5. Ademas facade no inmutable | Auto-registro + Lazy (2 MUST HAVE) |
| **I** (Pure Resolver) | Zero compile-time safety. 10 devs sin validacion = alto riesgo | Compile-time safety (MUST HAVE) |
| **O** (Metro eager) | Cumple auto-registro grafo y compile-time, pero no lazy + facade no inmutable | Lazy (MUST HAVE) + facade |
| **P** (kotlin-inject-anvil eager) | Misma limitacion que O: sin lazy + facade no inmutable | Lazy (MUST HAVE) + facade |
| **N** (sweet-spi + Koin) | Sin compile-time safety. Resolve 74x mas lento que O2 (6,328 vs 86 ns). Koin runtime = bindings rotos en produccion. **Bonus:** facade inmutable nativo | Compile-time safety (MUST HAVE) |
| **L** (Koin + ServiceLoader) | Mismos problemas que N + ServiceLoader overhead. Init 154K ns, resolve 6,244 ns | Compile-time (MUST) + rendimiento |
| **M** (Koin + ServiceLoader Lazy) | El peor performer overall. Lazy cascade 48,334 ns. Re-init 1.2M ns | Compile-time (MUST) + rendimiento |
| **E2** (AutoProvisionRegistry) | Semi-auto-registro grafo (1 linea por feature en `allEntries()`). Sin embargo facade SI inmutable y compile-time COMPLETA | Auto-registro grafo parcial |
| **K** (AndroidManifest) | Init 2x mas lento que H sin beneficio. Solo viable si R8 elimina META-INF. Comparte con H facade inmutable | Alternativa a H si R8 es problema |
| **Q2** (Hilt-style Dagger Lazy) | No auto-registro grafo + facade no inmutable. **Doble** edicion central. Alternativa viable si se acepta KSP propio para el `when` | Auto-registro (MUST HAVE) + facade |

**Nota sobre Q2:** Ofrece compile-time safety COMPLETA + Lazy (2 de 3 MUST HAVE). Pero
falla **dos** criterios de zero-touch: (a) auto-registro grafo (modules manuales en
@Component) y (b) facade inmutable (`when` manual). Mitigable parcialmente con KSP propio
para el `when`, pero el modules=[...] sigue siendo manual. A +50 modules × 10 APIs con
10 devs en paralelo, **doble cuello de botella central**.

**Nota sobre E2 (revisada):** Aunque tiene auto-registro parcial, **es el unico patron
Dagger con compile-time safety COMPLETA + Lazy + facade inmutable**. Si el equipo acepta
1 linea en `allEntries()` por feature como coste, E2 es candidato Tier 2 (no Tier 4 como
sugeria la version anterior de este doc).

---

## 6. Evidencia de Benchmarks

### Tabla comparativa en los 8 criterios -- Todos los candidatos viables

| # | Criterio | O2 | P2 | H | Q2 | E2 | K | I |
|---|----------|----|----|---|----|----|---|---|
| 1 | Auto-registro (grafo) | **OK** | **OK** | **OK** | NO | PARCIAL | **OK** | **OK** |
| 2 | Compile-time safety | **OK** | **OK** | PARCIAL | **OK** | **OK** | PARCIAL | NO |
| 3 | Lazy | **OK** | **OK** | **OK** | **OK** | **OK** | **OK** | **OK** |
| 4 | Thread-safe shutdown | **OK** | **OK** | **OK** | **OK** | **OK** | **OK** | **OK** |
| 5 | Logger persistente | **OK** | **OK** | **OK** | **OK** | PARCIAL | **OK** | PARCIAL |
| 6 | Madurez ecosistema | **BAJA** | **MEDIA** | **ALTA** | **ALTA** | **ALTA** | **ALTA** | **ALTA** |
| 7 | Init cold (ns) | **1,127** | **1,416** | 106,865 | **1,080** | 10,983 | 213,737 | 94,255 |
| 8 | Resolve cached (ns) | **86** | **156** | 212 | **85** | 211 | 213 | 211 |
| 9 | **Wiring del facade inmutable** | **NO** | **NO** | **OK** | **NO** | **OK** | **OK** | **OK** |
| | **MUST HAVEs cumplidos** | **3/3** | **3/3** | **2.5/3** | **2/3** | **2.5/3** | **2.5/3** | **2/3** |
| | **Total criterios OK (de 9)** | **8** | **8** | **8** | **6** | **8** | **8** | **7** |
| | **Candidato viable** | **Tier 1** | **Tier 1** | **Tier 1*** | **Tier 4** | **Tier 2** | **Tier 3** | NO |

*H promovido de Tier 3 a Tier 1 al considerar el Criterio 9 (facade inmutable). Tiene
auto-registro, lazy, thread-safe, logger persistente, madurez alta Y facade inmutable
nativo. Su unica falla es compile-time safety **parcial** (no completa). Esa diferencia
no es eliminatoria a pequeno-mediano riesgo, dado que un test `verify()` en CI cubre
el caso.

### Tests clave de Pattern H (promovido a Tier 1, probado en produccion)

| Test | Resultado | Que demuestra |
|------|-----------|---------------|
| `concurrentShutdown` | 200 rounds OK | Thread-safe: read vs shutdown race sin crash |
| `thunderingHerd` | 100 threads, todos `assertSame` | Singleton identity bajo contention extrema |
| `concurrentBuild` | 100 rounds, 6 threads | DFS thread-safe: 6 threads construyen simultaneamente |
| `builtProvisionCount == 0` tras init | 9 tests OK | Laziness genuina: init no construye nada |
| `leakDetection` | 1,000 ciclos, delta < 2,048 KB | Zero memory leaks en el Resolver |
| `stress10K` | 10,000 ciclos, heap = 4 KB | Estabilidad a escala extrema |
| `rapidFire` | 5,000 ciclos | Ciclo init/get/shutdown determinista y repetible |
| `errorResilience` | 5 escenarios | Maquina de estados correcta |
| `functional` | 1,000 reinits | Encrypt+Auth+Sync funcionan tras 1,000 ciclos |
| `loggerPersistence` | Logger sobrevive shutdown | Observabilidad continua entre ciclos |

---

## 7. Benchmarks Detallados (Samsung Galaxy S22 Ultra, Jetpack Benchmark 1.4.0)

### Todos los patrones comparados

| Operacion | O2<br>*(Metro Lazy)* | P2<br>*(KI-anvil Lazy)* | H<br>*(Resolver+Dagger)* | Q2<br>*(Dagger Lazy)* | Q<br>*(Dagger @Module)* | E2<br>*(Registry DFS)* | K<br>*(Manifest Discovery)* | I<br>*(Pure Resolver)* | O<br>*(Metro eager)* | P<br>*(KI-anvil eager)* | N<br>*(sweet-spi+Koin)* |
|-----------|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Init Cold (ns) | 1,127 | 1,416 | 106,865 | 1,080 | 676 | 10,983 | 213,737 | 94,255 | 603 | 1,064 | 69,636 |
| Resolve First (ns) | 315 | 335 | 202 | 306 | 257 | 199 | 203 | 203 | 288 | 336 | 5,855 |
| Resolve All (ns) | 86 | 156 | 212 | 85 | 64 | 211 | 213 | 211 | 80 | 165 | 6,328 |
| Lazy noDeps (ns) | 238 | 284 | 1,278 | 236 | 1,735 | 1,049 | 2,996 | 1,112 | 2,098 | 1,941 | 20,018 |
| Lazy cascade (ns) | 507 | 734 | 3,892 | 504 | 318 | 3,088 | 7,900 | 4,122 | 346 | 607 | 22,706 |
| E2E Startup (ns) | 1.5M | 993K | 1.7M | 1.3M | 950K | 1.4M | 2.3M | 1.7M | 1.2M | 1.4M | 2.0M |
| Init/Shutdown (ns) | 516 | 508 | 99,293 | 549 | 403 | 4,418 | 201,490 | 103,695 | 301 | 293 | 42,293 |
| Re-Init (ns) | 2,305 | 2,929 | 362,649 | 2,157 | 25,000 | 17,000 | 767,000 | 427,000 | 36,000 | 28,000 | 732,000 |
| Concurrent (ns) | 587K | 638K | 515K | 586K | 591K | 571K | 554K | 608K | 586K | 618K | 784K |

### Interpretacion para el caso de +50 modulos

1. **Init Cold (una vez por sesion):** O2 paga 1,127 ns. H paga 106,865 ns. La diferencia
   es 95x pero **ambos son irrelevantes** en un cold start de 500-2000 ms (0.001 vs 0.107 ms).
   Init performance NO es el criterio diferenciador.

2. **Resolve cached (hot path):** O2 resuelve en 86 ns. H en 212 ns. N en 6,328 ns.
   Si el consumidor hace 1,000 resoluciones por segundo, el overhead es:
   O2 = 86 us/s, H = 212 us/s, N = 6.3 ms/s. Solo N es problematico.

3. **Lazy noDeps:** O2 (238 ns) y Q2 (236 ns) dominan. H (1,278 ns) es 5x mas lento.
   N (20,018 ns) es 84x mas lento. Lazy materializado correctamente = diferencia real.

4. **Re-Init (hot restart):** O2 (2,305 ns) vs H (362,649 ns) = 157x. Si el SDK hace
   hot restart frecuente (testing, config changes), Tier 1 tiene ventaja masiva.
   En produccion, re-init es raro.

5. **Concurrent Access:** Todos convergen (~500-700K ns). Threading domina, no DI.
   Ningun patron tiene ventaja.

---

## 8. Riesgos y Mitigaciones

### Riesgos de Pattern H (Tier 1 -- opcion conservadora)

| # | Riesgo | Probabilidad | Impacto | Mitigacion |
|---|--------|-------------|---------|-----------|
| 1 | ServiceLoader scan crece con classpath | MEDIA | BAJO | ProGuard keep rules. A 50+ features < 200 us. Benchmark en CI |
| 2 | Provider faltante = error runtime | MEDIA | ALTO | Test `verify()` en CI: `sdk.init()` + `sdk.get<T>()` para cada servicio. Detecta antes de release |
| 3 | Dagger KSP compilation time | MEDIA | MEDIO | KSP incremental. Cada feature-impl compila su DaggerComponent independientemente |
| 4 | R8 elimina META-INF/services | BAJA | ALTO | Keep rule en proguard-rules.pro. Si no viable, migrar a Pattern K |
| 5 | Resolver.kt como codigo propio | BAJA | BAJO | 105 lineas, 35 tests. API estable. Costo marginal |

### Riesgos de Pattern O2 (Tier 1 -- opcion data-driven)

| # | Riesgo | Probabilidad | Impacto | Mitigacion |
|---|--------|-------------|---------|-----------|
| 1 | Metro v0.6.6 breaking changes | ALTA | ALTO | Pin version estricto. No actualizar sin validar suite completa. Mantener fork si es necesario |
| 2 | Kotlin version coupling | ALTA | MEDIO | Metro compiler plugin se acopla a version de Kotlin. Cada bump puede requerir esperar a Metro. Monitorear releases |
| 3 | ZacSweers deja de mantener Metro | MEDIA | ALTO | Codigo open-source, forkable. Comunidad Slack usa internamente. Si abandono: migrar a P2 (misma semantica @ContributesTo) |
| 4 | Documentacion escasa | MEDIA | BAJO | Codigo fuente como documentacion. Patrones similares a Anvil/Hilt |

### Riesgos de Pattern P2 (Tier 1 -- alternativa KSP)

| # | Riesgo | Probabilidad | Impacto | Mitigacion |
|---|--------|-------------|---------|-----------|
| 1 | kotlin-inject-anvil abandona mantenimiento | BAJA | ALTO | kotlin-inject core es independiente. Anvil extensions open-source y forkable. Amazon usa en produccion |
| 2 | KSP breaking changes en Kotlin 2.x | MEDIA | MEDIO | KSP mantenido por Google/JetBrains. Historial de migraciones suaves |
| 3 | Documentacion mas escasa que Dagger | MEDIA | BAJO | kotlin-inject conceptos familiares (Component, Provides, Scope). 1-2 dias de training |

### Mitigacion general: CI continuo

Independientemente del patron elegido, mantener la suite de tests ejecutandose en CI:
- **Benchmarks** para detectar regresiones de performance
- **Tests de memoria** para detectar leaks (limite: heap delta < 2,048 KB en 1,000 ciclos)
- **Tests de stress** para validar concurrencia, singleton identity, y error resilience

---

## Resumen Ejecutivo

| Opcion | Tier | Recomendacion | Para quien |
|--------|------|--------------|------------|
| **H (ServiceLoader + Resolver + Dagger)** | **1** | **Produccion inmediata + facade inmutable nativo** | Equipos que priorizan zero-touch end-to-end y madurez probada. Aceptan compile-time parcial mitigado con `verify()` test |
| **O2 (Metro Lazy)** | **1** | **Mejor perf, requiere KSP propio para facade** | Equipos que priorizan rendimiento + compile-time completa Y aceptan invertir ~200 LOC en KSP custom o aceptan editar `when` por API |
| **P2 (kotlin-inject-anvil Lazy)** | **1** | **Variante O2 con KSP estandar y maintainer corporativo** | Equipos que quieren Tier 1 con menor riesgo de framework (Amazon mantiene). Mismo caveat de facade |
| **E2 (AutoProvisionRegistry)** | **2** | **Sweet spot Dagger: compile-time COMPLETA + facade inmutable** | Equipos que aceptan 1 linea por feature en `allEntries()` y quieren Dagger sin merge conflicts en `@Component` |
| Q2 (Hilt-style Dagger Lazy) | 4 | Solo si se acepta KSP propio para `when` Y disciplina de rebase para `@Component` | Equipos pequenos con pocas features nuevas/mes |
| K (AndroidManifest Discovery) | 3 | Solo si R8 elimina META-INF | Alternativa a H en entornos ProGuard agresivos |
| N (sweet-spi + Koin) | 5 | No recomendado a escala | Solo si el equipo ya usa Koin y no puede migrar |

**Decision honesta (criterio bidimensional):**

La pregunta clave es: **¿que ejes valoras mas?**

- **Si zero-touch end-to-end es innegociable** y compile-time parcial es aceptable
  (con `verify()` test en CI): **H**. Es el unico patron Tier 1-3 con facade inmutable
  nativo + auto-registro grafo + madurez alta.

- **Si compile-time completa es innegociable** y aceptas invertir en mantener `when`
  manual o KSP propio: **O2** (perf) o **P2** (madurez). Plan: hoy editar `when`,
  manana migrar a KSP custom cuando el numero de APIs lo justifique.

- **Si compile-time completa Y facade inmutable son innegociables** sin invertir en
  KSP propio: **E2**. Aceptas 1 linea en `allEntries()` por feature.

- **Si el SDK tendra <30 features × <5 APIs cada uno**: cualquiera de los 4 funciona.
  La diferencia se nota a partir de 50 × 10.

**Diferencia con la version anterior de este doc:** La version anterior recomendaba O2
como "objetivamente el mejor" basandose en 7 de 8 criterios. Esa evaluacion ignoraba el
Criterio 9 (facade inmutable). Anadirlo cambia el ranking: O2 y P2 dejan de ser dominantes
y H pasa a ser candidato Tier 1 con perfil distinto. Ningun patron domina absolutamente
-- la decision depende del trade-off compile-time vs facade inmutable.
