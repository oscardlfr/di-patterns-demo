# Recomendacion de Patron DI para SDK Corporativo (+50 modulos, 10 devs)

**Fecha:** 2026-04-12  
**Contexto:** Seleccion de patron de inyeccion de dependencias para un SDK multi-plataforma corporativo  
**Dispositivo de referencia:** Samsung Galaxy S22 Ultra (Snapdragon 8 Gen 1, Android 16)  
**Datos de benchmark:** Jetpack Benchmark 1.4.0, mediana de `timeNs`

---

## 1. Contexto

Un SDK corporativo con las siguientes caracteristicas:

- **+50 modulos funcionales** (features) mantenidos por equipos independientes
- **10 desarrolladores** trabajando en paralelo sobre el mismo repositorio
- **KMP obligatorio:** Android + iOS + Desktop (macOS/Windows/Linux)
- **Logger persistente** que sobrevive a ciclos de shutdown/reinit del SDK
- **Auto-registro:** anadir un feature nuevo = 0 cambios en el wiring central
- **Acceso concurrente thread-safe** desde multiples threads del consumidor
- **Inicializacion lazy:** el consumidor paga solo por las features que usa
- **Seguridad en compilacion preferida:** errores de bindings detectados antes de runtime

Con +50 modulos y 10 devs, la escalabilidad del patron de DI es critica. Un patron que
requiere edicion central para cada feature nuevo crea cuellos de botella en PRs y conflictos
de merge constantes.

---

## 2. Criterios de Seleccion

| # | Criterio | Peso | Justificacion |
|---|----------|------|---------------|
| 1 | Compatibilidad KMP | MUST HAVE | Sin KMP no hay iOS ni Desktop. Eliminatorio |
| 2 | Auto-registro (grafo) | MUST HAVE | Anadir feature = solo `implementation(...)` en Gradle. Edicion central por feature = cuello de botella con +50 modulos |
| 3 | Inicializacion lazy | MUST HAVE | Startup performance critico en mobile |
| 4 | Seguridad en compilacion | IMPORTANTE | 10 devs = alta probabilidad de bindings rotos |
| 5 | Thread-safe shutdown | IMPORTANTE | SDK se reinicializa en cambios de configuracion |
| 6 | Logger persistente | IMPORTANTE | Observabilidad no puede perderse entre ciclos |
| 7 | Madurez del ecosistema | IMPORTANTE | Soporte a largo plazo, documentacion, comunidad |
| 8 | Resolve performance | DESEABLE | Impacto en hot paths del consumidor |
| 9 | Init performance | DESEABLE | Impacto en cold start del consumidor |
| 10 | Footprint de memoria | DESEABLE | Impacto en dispositivos de gama baja |
| 11 | Wiring del facade inmutable | IMPORTANTE | El dispatcher `get<T>(Class)` del SDK NO requiere editar ramas manualmente al anadir una API. 50 features × 10 APIs = 500 ramas de `when` mantenidas a mano si falla |

**Criterios eliminatorios (MUST HAVE):** Si un patron no cumple los 3 MUST HAVE, se descarta
independientemente de sus otras cualidades.

**Sobre Criterio 2 vs 11 (auto-registro: dos ejes distintos):**
- **Criterio 2 (grafo)** mide si el framework DI agrega el modulo automaticamente
  (`@ContributesTo`, sweet-spi, etc).
- **Criterio 11 (facade)** mide si el dispatcher `get<T>(Class)` del SDK escala. Patrones
  compile-time (Metro, kotlin-inject) suelen requerir un `when (clazz)` manual porque no
  tienen runtime class lookup nativo. Patrones runtime (Koin) o Resolver-based (H) lo
  cumplen sin codegen propio.

**O2/P2 cumplen Criterio 2 pero fallan Criterio 11**. Ambos requieren un `when` manual
en el facade que crece linealmente por API. Mitigable con un procesador KSP propio
(~200 LOC) que genere el `when` desde el componente. Sin esa inversion, son 500 ramas
mantenidas a mano a escala.

---

## 3. Evaluacion de Candidatos

Solo se evaluan patrones con compatibilidad KMP completa. Los patrones JVM-only
(B, C, D, E2, G, H, I, K, Q, Q2) y los de KMP parcial (J, L, M) quedan descartados
para esta recomendacion.

### 3.1 Pattern N -- sweet-spi + Koin

**Arquitectura:** sweet-spi para descubrimiento KMP multi-plataforma + Koin como DI container.
Cada feature declara un `@ServiceProvider` que registra un `Module` de Koin.

| Criterio | Evaluacion | Detalle |
|----------|-----------|---------|
| KMP | OK | sweet-spi genera expect/actual. Koin es full KMP |
| Auto-registro (grafo) | OK | `@ServiceProvider` + sweet-spi. Zero edicion central |
| Lazy | OK | `loadModules()` de Koin + `get<T>()` on-demand |
| Compile-time safety | NO | Koin resuelve en runtime. Bindings rotos = crash en ejecucion |
| Thread-safe shutdown | OK | Koin `close()` limpia el grafo |
| Logger persistente | PARCIAL | Requiere wiring manual para persistir entre reinits |
| Madurez | ALTA | Koin: 7+ anos, documentacion extensa, comunidad grande |
| Resolve cached | ~12,150 ns | 60x mas lento que H (202 ns) |
| Init cold | ~135,200 ns | Comparable a H (106,865 ns) |
| **Wiring del facade inmutable** | **OK** | `koin.get(clazz.kotlin)` -- runtime registry nativo de Koin. Cero `when` |

**Pros:**
- API familiar para desarrolladores Android (Koin es el DI mas usado en KMP)
- Ecosistema maduro con soporte oficial de JetBrains
- sweet-spi resuelve el problema de ServiceLoader en KMP
- Flexible: cambiar bindings en runtime para testing

**Contras:**
- Sin seguridad en compilacion: un `get<T>()` sin binding registrado crashea en runtime
- Resolve 60x mas lento que patterns compile-time (Koin atraviesa su grafo en cada resolucion)
- `koin.verify()` en tests mitiga parcialmente pero no reemplaza compile-time safety
- Con +50 modulos, la probabilidad de un binding roto en produccion crece linealmente

### 3.2 Pattern O2 -- Metro Lazy

**Arquitectura:** Metro compiler plugin genera el grafo DI en compilacion.
`@ContributesTo` para auto-registro. `@SingleIn(AppScope::class)` para singletons lazy.

| Criterio | Evaluacion | Detalle |
|----------|-----------|---------|
| KMP | OK | Compiler plugin genera codigo para cada target |
| Auto-registro (grafo) | OK | `@ContributesTo` agrega al grafo automaticamente |
| Lazy | OK | Singletons lazy por defecto en Metro |
| Compile-time safety | OK | Grafo completo validado en compilacion |
| Thread-safe shutdown | OK | Grafo inmutable, destroy limpia scope |
| Logger persistente | OK | `@SingleIn(AppScope)` sobrevive a feature scopes |
| Madurez | BAJA | v0.6.6, proyecto de Slack, comunidad pequena |
| Resolve cached | ~45 ns | El mas rapido (campo directo, sin map lookup) |
| Init cold | ~891 ns | 120x mas rapido que H (106,865 ns) |
| **Wiring del facade inmutable** | **NO** | `when (clazz)` manual en `MultiModuleSdkO2.get()`. Crece 1 rama por API. Mitigable con KSP propio (~200 LOC) |

**Pros:**
- Rendimiento excepcional: init 120x mas rapido que H, resolve 4.5x mas rapido
- Compile-time safety completa: binding faltante = error de compilacion
- Auto-registro via `@ContributesTo` (herencia de Anvil/Hilt)
- KMP nativo: compiler plugin genera codigo por target

**Contras:**
- v0.6.6 -- no estable. Breaking changes posibles entre versiones
- Compiler plugin: acoplado a versiones de Kotlin (cada bump de Kotlin puede romper Metro)
- Comunidad pequena: bugs tardan en resolverse, poca documentacion
- Slack mantiene para uso interno -- riesgo de abandono si Slack cambia de estrategia

### 3.3 Pattern P2 -- kotlin-inject-anvil Lazy

**Arquitectura:** kotlin-inject como DI base + anvil extensions para auto-registro via KSP.
`@ContributesTo` para agregacion automatica. `@SingleIn` para scoped singletons.

| Criterio | Evaluacion | Detalle |
|----------|-----------|---------|
| KMP | OK | kotlin-inject es full KMP. KSP genera para cada target |
| Auto-registro (grafo) | OK | `@ContributesTo` agrega al grafo via KSP merge |
| Lazy | OK | `@SingleIn` con lazy tracking en el scope |
| Compile-time safety | OK | KSP valida bindings en compilacion |
| Thread-safe shutdown | OK | Scope destroy limpia el grafo, inmutable |
| Logger persistente | OK | `@SingleIn(AppScope)` sobrevive a feature scopes |
| Madurez | MEDIA | kotlin-inject: 4+ anos. anvil ext: 1+ ano. Amazon mantiene |
| Resolve cached | ~62 ns | 3.3x mas rapido que H (202 ns) |
| Init cold | ~1,200 ns | 89x mas rapido que H (106,865 ns) |
| **Wiring del facade inmutable** | **NO** | `when (clazz)` manual en `MultiModuleSdkP2.get()`. Mismo problema que O2. Mitigable con KSP propio |

**Pros:**
- Compile-time safety completa via KSP (no compiler plugin)
- KSP es estable y soportado oficialmente por Google/JetBrains
- Auto-registro via `@ContributesTo`: anadir feature = 1 anotacion
- Extiende kotlin-inject (framework probado con 4+ anos de produccion)
- KMP nativo: KSP genera codigo per-target
- Amazon lo usa en produccion (Ring, Alexa) -- mantenimiento garantizado

**Contras:**
- Documentacion mas escasa que Koin o Dagger
- Menos ejemplos y blog posts en la comunidad
- Amazon como maintainer: menor visibilidad que Google/JetBrains
- anvil extensions son mas recientes que el core kotlin-inject

### 3.4 Pattern H + sweet-spi (Hibrido)

**Arquitectura:** El Resolver de Pattern H pero con sweet-spi en lugar de ServiceLoader
para descubrimiento KMP. FeatureProviders descubiertos via `@ServiceProvider`.

| Criterio | Evaluacion | Detalle |
|----------|-----------|---------|
| KMP | OK | sweet-spi reemplaza ServiceLoader con expect/actual |
| Auto-registro (grafo) | OK | `@ServiceProvider` + sweet-spi. Zero edicion central |
| Lazy | OK | DFS resolver construye on-demand (demostrado en benchmarks) |
| Compile-time safety | NO | Provider faltante es error runtime (igual que H) |
| Thread-safe shutdown | OK | Demostrado: concurrentShutdown 200 rounds OK |
| Logger persistente | PARCIAL | ObservabilityProvider via ServiceLoader, requiere wiring |
| Madurez | MEDIA | Resolver propio (probado). sweet-spi maduro |
| Resolve cached | ~202 ns | ConcurrentHashMap lookup |
| Init cold | ~106,865 ns | ServiceLoader scan (sweet-spi similar) |
| **Wiring del facade inmutable** | **OK** | `resolver.get(clazz)` -- HashMap lookup. Cero `when`, cero edicion al anadir APIs |

**Pros:**
- Arquitectura probada: 35 tests, 10K ciclos, 100 threads, zero leaks
- DFS lazy genuino: builtProvisionCount == 0 tras init
- Thread-safe: synchronized + ConcurrentHashMap
- Control total del codigo: sin dependencia en framework externo para DI
- **Facade inmutable nativo**: a diferencia de O2/P2, el dispatcher `get<T>(Class)` no
  crece por API. Mismo facade de ~50 lineas para 5 o 500 APIs. Es la unica ventaja
  arquitectural que O2/P2 NO replican sin codegen propio

**Contras:**
- Sin compile-time safety: 10 devs con +50 modulos = alto riesgo de bindings rotos
  (mitigable con test `verify()` en CI)
- Init 120x mas lento que Metro, 89x mas lento que kotlin-inject-anvil
- Mantener un Resolver propio = costo de mantenimiento a largo plazo (compensado por
  facade inmutable: si el SDK tiene 50+ APIs, el `when` que NO mantienes en O2/P2
  vale mucho mas que las 105 lineas del Resolver)

---

## 4. Recomendacion

**Honestidad antes de recomendar**: la version anterior de este doc presentaba P2 como
"PRIMARIA" basandose en 9/10 criterios cumplidos. Esa evaluacion ignoraba el Criterio 11
(facade inmutable) que P2 falla. Anadirlo cambia el cuadro: **ningun patron domina los
11 criterios**. La eleccion depende de que ejes valoras.

### Tres opciones validas, tres trade-offs distintos

#### Opcion A: Pattern P2 (kotlin-inject-anvil Lazy) -- si compile-time es prioritario y aceptas KSP propio

**Cuando elegir:**
- Compile-time safety completa es innegociable (10 devs, 50 modulos = riesgo alto sin ella)
- Estas dispuesto a invertir ~200 LOC en un procesador KSP propio que genere el `when`
  del facade desde el componente, **o** aceptas mantener ese `when` a mano (1 rama por API)

**Pros:**
- KMP completo via KSP -- genera codigo para Android, iOS, Desktop
- Compile-time safety completa -- binding faltante rompe la compilacion
- Auto-registro grafo via `@ContributesTo` -- zero edicion para anadir features
- Lazy singletons via `@SingleIn`
- Amazon en produccion (Ring, Alexa) -- mantenimiento garantizado
- Rendimiento excelente -- init 89x mas rapido que H, resolve 3.3x mas rapido

**Contras:**
- **Falla Criterio 11**: facade `MultiModuleSdkP2.get<T>(Class)` requiere `when` manual
  por API. Sin codegen propio, 50 features × 10 APIs = 500 ramas a mano

**Ejemplo de auto-registro con P2:**

```kotlin
// features/feature-encryption/src/commonMain/kotlin/EncComponent.kt
@ContributesTo(AppScope::class)
@Component
interface EncComponent {
    @Provides @SingleIn(AppScope::class)
    fun provideEncryptionApi(impl: EncryptionImpl): EncryptionApi = impl
}
```

Anadir este archivo en un modulo Gradle nuevo = el servicio queda registrado en el grafo
DI globalmente. **Pero**: para que el SDK lo exponga, hay que anadir una rama al `when`
de `MultiModuleSdkP2.get()`. **Cero cambios en wiring, +1 rama en facade.**

#### Opcion B: Pattern N (sweet-spi + Koin) -- si facade inmutable es prioritario y compile-time es negociable

**Cuando elegir:**
- Quieres zero-touch end-to-end (modulo + facade): nada que mantener manualmente
- Aceptas runtime DI con `koin.verify()` en CI como sustituto de compile-time
- Equipo familiarizado con Koin

**Pros:**
- KMP completo via sweet-spi
- Auto-registro grafo via `@ServiceProvider`
- **Facade inmutable nativo** (`koin.get(clazz)`) -- el unico patron KMP-completo con
  esta propiedad sin codegen propio
- Madurez alta (Koin: 7+ anos)
- Anadir API = `single { ... }` en un modulo Koin. Zero ediciones al facade

**Contras:**
- Sin compile-time safety -- bindings rotos = crash en runtime (mitigable con
  `koin.verify()` en tests)
- Resolve 60x mas lento que P2 (12,150 vs 62 ns) -- relevante en hot loops
- Init mas lento (135K ns)

#### Opcion C: Pattern H + sweet-spi (hibrido) -- si quieres facade inmutable + Resolver propio

**Cuando elegir:**
- Quieres facade inmutable nativo Y control total del codigo (sin framework DI externo)
- Aceptas mantener un Resolver de ~105 lineas y compile-time parcial

**Pros:**
- Arquitectura probada: 35 tests, 10K ciclos, zero leaks
- DFS lazy genuino
- **Facade inmutable nativo** (`resolver.get(clazz)`)
- Sin dependencia de framework DI externo

**Contras:**
- Compile-time parcial (provider faltante = runtime error)
- Init lento (~107K ns) -- comparable a N
- Mantener Resolver propio

### Futuro: Pattern O2 (Metro Lazy)

**Cuando Metro alcance v1.0+:**

- Rendimiento superior a P2 (init 1.3x mas rapido, resolve 1.4x mas rapido)
- Compile-time safety igual de completa
- Auto-registro grafo identico (`@ContributesTo`)
- Mismo problema de Criterio 11 (facade `when` manual, mitigable con KSP propio)
- Evaluar migracion cuando: (a) v1.0 estable, (b) comunidad crezca, (c) soporte de
  Kotlin version bumps demostrado

---

## 5. Evidencia de Benchmarks

Comparacion directa de los 4 candidatos en los 11 criterios (S22 Ultra, 2026-04-12):

| # | Criterio | N (sweet-spi+Koin) | O2 (Metro Lazy) | P2 (KI-anvil Lazy) | H+sweet-spi |
|---|----------|--------------------|-----------------|---------------------|-------------|
| 1 | KMP | OK | OK | OK | OK |
| 2 | Auto-registro (grafo) | OK | OK | OK | OK |
| 3 | Lazy | OK | OK | OK | OK |
| 4 | Compile-time safety | NO | OK | OK | NO |
| 5 | Thread-safe shutdown | OK | OK | OK | OK |
| 6 | Logger persistente | PARCIAL | OK | OK | PARCIAL |
| 7 | Madurez ecosistema | ALTA | BAJA | MEDIA | MEDIA |
| 8 | Resolve cached (ns) | ~12,150 | ~45 | ~62 | ~202 |
| 9 | Init cold (ns) | ~135,200 | ~891 | ~1,200 | ~106,865 |
| 10 | Memory footprint | MEDIO | BAJO | BAJO | MEDIO |
| 11 | **Wiring del facade inmutable** | **OK** | **NO** | **NO** | **OK** |
| | **MUST HAVEs** | **3/3** | **3/3** | **3/3** | **3/3** |
| | **IMPORTANTE (de 5)** | **3.5/5** | **3/5** | **4/5** | **3.5/5** |
| | **DESEABLE** | **1/3** | **3/3** | **3/3** | **2/3** |
| | **Total criterios OK** | **8/11** | **9/11** | **10/11** | **8/11** |

**Lectura honesta del cuadro:**
- **N y H** ganan facade inmutable nativo, pero pierden compile-time. Buenos si zero-touch
  end-to-end es prioritario y se acepta runtime safety con `verify()` en CI.
- **O2 y P2** ganan compile-time + perf, pero pierden facade inmutable. Buenos si
  compile-time es prioritario y se acepta KSP propio (~200 LOC) o `when` manual.
- **P2 con KSP custom** = 11/11 hipoteticamente (mejor candidato con la inversion).
- **N + Koin maduro** = 8/11 sin inversion adicional (mejor candidato sin la inversion).
- **Sin un ganador absoluto** -- la decision depende de los tradeoffs aceptables.

### Benchmarks de Rendimiento Detallados

| Operacion | N<br>*(sweet-spi+Koin)* (ns) | O2<br>*(Metro Lazy)* (ns) | P2<br>*(KI-anvil Lazy)* (ns) | H<br>*(Resolver+Dagger)* (ns) |
|-----------|--------|---------|---------|--------|
| initCold | ~135,200 | ~891 | ~1,200 | 106,865 |
| resolve cached | ~12,150 | ~45 | ~62 | 202 |
| lazyInit cascade | ~5,400 | ~320 | ~480 | 3,892 |
| e2eStartup | ~2,100,000 | ~1,200,000 | ~1,350,000 | 1,745,145 |
| stress_initShutdown | ~140,000 | ~2,100 | ~3,500 | 99,293 |
| stress_reInit | ~520,000 | ~4,800 | ~7,200 | 362,649 |

**Conclusiones de rendimiento:**
- O2 y P2 dominan en todas las metricas de velocidad
- N es consistentemente el mas lento por el overhead de Koin
- H se situa entre P2 y N: mas rapido que Koin, mas lento que compile-time
- Para +50 modulos, la diferencia de resolve (62 ns vs 12,150 ns) se amplifica en hot paths

---

## 6. Plan de Migracion desde Pattern H

Para un equipo actualmente usando una arquitectura tipo H (Resolver + ServiceLoader + Dagger):

### Fase 1: Preparacion (1-2 semanas)

1. **Agregar kotlin-inject-anvil al version catalog**
   ```toml
   [versions]
   kotlin-inject = "0.7.x"
   kotlin-inject-anvil = "0.1.x"
   
   [libraries]
   kotlin-inject-runtime = { module = "me.tatarka.inject:kotlin-inject-runtime", version.ref = "kotlin-inject" }
   kotlin-inject-anvil-runtime = { module = "software.amazon.lastmile.kotlin.inject.anvil:runtime", version.ref = "kotlin-inject-anvil" }
   ```

2. **Crear modulo `di-kotlin-inject` con AppScope y scopes**
   ```kotlin
   @Scope
   @Retention(AnnotationRetention.RUNTIME)
   annotation class SingleIn(val scope: KClass<*>)
   
   abstract class AppScope private constructor()
   ```

3. **Configurar KSP en build-logic convention plugin**

### Fase 2: Migracion feature por feature (2-4 semanas)

Para cada feature-impl (empezar por las mas simples):

1. **Crear `@Component` con `@ContributesTo`** en el feature-impl
2. **Mover bindings** de DaggerXComponent a kotlin-inject Component
3. **Eliminar** FeatureProvider + META-INF/services
4. **Verificar** que el feature se resuelve via kotlin-inject

**Orden recomendado:**
1. Core (sin dependencias) -- valida setup
2. Observability (solo Core) -- valida logger persistente
3. Encryption (Core + Observability) -- valida dependencias simples
4. Analytics (Core + Observability) -- similar a Encryption
5. Auth (Encryption + Core) -- valida cross-deps
6. Storage (Encryption + Core) -- similar a Auth
7. Sync (Auth + Storage + Encryption) -- valida cascada completa (DFS mas profundo)

### Fase 3: Eliminar Resolver (1 semana)

1. **Reemplazar** MultiModuleSdkH con entry point de kotlin-inject
2. **Eliminar** Resolver.kt, FeatureProvider.kt, ServiceLoader config
3. **Verificar** todos los tests (35 tests deben seguir pasando con el nuevo backend DI)
4. **Benchmark** para confirmar mejoras de rendimiento

### Fase 4: KMP (2-4 semanas)

1. **Mover** commonMain code a modulos KMP
2. **Generar** targets iOS/Desktop con KSP
3. **Verificar** compilacion y tests en cada target

**Duracion total estimada:** 6-11 semanas para un equipo de 2-3 devs dedicados.

---

## 7. Riesgos y Mitigaciones

| # | Riesgo | Probabilidad | Impacto | Mitigacion |
|---|--------|-------------|---------|-----------|
| 1 | kotlin-inject-anvil abandona mantenimiento | BAJA | ALTO | kotlin-inject core es independiente. Anvil extensions son open-source y forkable. Peor caso: se mantiene fork interno |
| 2 | KSP breaking changes en Kotlin 2.x | MEDIA | MEDIO | KSP es mantenido por Google/JetBrains. Historial de migraciones suaves. Actualizar incrementalmente |
| 3 | Performance degrada con +50 modulos | BAJA | MEDIO | KSP procesa en compilacion, no en runtime. El grafo generado es O(1) resolve. Benchmarks lo confirman |
| 4 | Curva de aprendizaje para 10 devs | MEDIA | BAJO | kotlin-inject es similar a Dagger (anotaciones familiares). 1-2 dias de training + guia interna |
| 5 | Metro v1.0 sale y es superior | MEDIA | BAJO | P2 y O2 comparten `@ContributesTo` semantica. Migracion futura seria incremental |
| 6 | Dagger familiarity del equipo se pierde | BAJA | BAJO | kotlin-inject usa los mismos conceptos (Component, Provides, Scope). Transferencia directa |

### Mitigacion general: Benchmark continuo

Mantener la suite de 35 tests (12 benchmarks + 9 memoria + 14 stress) ejecutandose en CI
contra el patron elegido. Si el rendimiento degrada tras una actualizacion, se detecta inmediatamente.

---

## Resumen Ejecutivo

| Opcion | Cuando elegir | Trade-off |
|--------|--------------|-----------|
| **P2 (kotlin-inject-anvil Lazy)** | Compile-time safety es **innegociable** y aceptas KSP propio (~200 LOC) o `when` manual en facade | Mejor rendimiento (10/11 criterios); falla Criterio 11 (facade) |
| **N (sweet-spi + Koin)** | Facade inmutable end-to-end es **innegociable** y aceptas runtime DI con `verify()` test | Wiring zero-touch end-to-end nativo (8/11); falla Criterio 4 (compile-time) |
| **H + sweet-spi (hibrido)** | Quieres facade inmutable + control total del codigo + compile-time parcial es aceptable | Resolver propio (~105 LOC) + facade inmutable; init lento |
| O2 (Metro Lazy) | Cuando Metro alcance v1.0+ | Mismo trade-off que P2 pero con rendimiento superior |

**Decision honesta:** No hay un patron PRIMARIO universal.

- **Si el SDK tendra >50 APIs y compile-time safety completa es innegociable**: P2 + KSP propio
  para generar el `when` del facade (10/11 con la inversion = 11/11).
- **Si quieres zero-touch end-to-end sin escribir codegen**: N (sweet-spi + Koin) +
  `koin.verify()` en CI. Aceptas runtime DI.
- **Si compile-time parcial es aceptable y quieres control total**: H + sweet-spi.

**Cambio respecto a la version anterior:** este doc anteriormente recomendaba P2 como
"PRIMARIA" basado en 9/10 criterios cumplidos. Anadir Criterio 11 (facade inmutable)
revela que P2 deja una deuda oculta en el dispatcher del SDK. Sin un KSP propio, esa
deuda crece con cada API y a 50+ APIs supera el coste del Resolver de H. La eleccion
correcta depende de los ejes que el equipo prioriza.
