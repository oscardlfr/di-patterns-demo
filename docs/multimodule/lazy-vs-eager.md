# Lazy vs Eager en Compile-Time DI

Los patrones compile-time (O, P, Q) crean el grafo completo al llamar `init()`.
Todos los singletons se instancian durante la construccion del component/graph.
Las variantes lazy (O2, P2, Q2) difieren la creacion de singletons: el grafo
se construye en `init()`, pero los objetos solo se materializan la primera vez
que se accede a ellos.

Este documento analiza cuando vale la pena usar lazy y cuanto se gana (o pierde)
en cada escenario.

---

## 1. Mecanismo de Cada Variante

### O vs O2 (Metro)

| Aspecto | O (eager) | O2 (lazy) |
|---------|----------|-----------|
| **Grafo** | `@DependencyGraph(AppScope)` | Identico |
| **Accessors** | `val encryption: EncryptionApi` | `val encryption: Lazy<EncryptionApi>` |
| **Init** | `createGraphFactory<Factory>().create(...)` | Identico |
| **Resolucion** | `graph.encryption` (acceso directo) | `graph.encryption.value` (materializa) |
| **Tracking** | `builtProvisionCount = 5` (hardcoded) | `LazyCreationTracker.Instance` (real) |
| **Shutdown** | `_graph = null` | `LazyCreationTracker.deactivate()` + `_graph = null` |

Metro genera `Lazy<T>` wrappers que envuelven el provider. El primer `.value`
ejecuta el provider y cachea el resultado. Accesos posteriores retornan el valor
cacheado directamente.

### P vs P2 (kotlin-inject-anvil)

| Aspecto | P (eager) | P2 (lazy) |
|---------|----------|-----------|
| **Component** | `@MergeComponent(SdkScope)` | Identico |
| **Accessors** | `abstract val encryption: EncryptionApi` | Identico (pero `@SingleIn` scope) |
| **Init** | `SdkComponent::class.create(...)` | Identico |
| **Resolucion** | `component.encryption` | `component.encryption` (lazy via scope) |
| **Tracking** | `builtProvisionCount = 5` (hardcoded) | `LazyCreationTracker.Instance` (real) |
| **Shutdown** | `_component = null` | `LazyCreationTracker.deactivate()` + `_component = null` |

kotlin-inject-anvil con `@SingleIn` genera providers que internamente usan
`lazy {}` para la creacion del singleton. El acceso es transparente al consumidor
-- la misma propiedad `component.encryption` funciona en ambas variantes.

### Q vs Q2 (Dagger Hilt-style)

| Aspecto | Q (eager) | Q2 (lazy) |
|---------|----------|-----------|
| **Component** | `@Singleton @Component(modules=[...])` | Identico |
| **Accessors** | `fun encryption(): EncryptionApi` | `fun encryption(): dagger.Lazy<EncryptionApi>` |
| **Init** | `DaggerSdkComponent.factory().create(...)` | Identico |
| **Resolucion** | `component.encryption()` | `component.encryption().get()` |
| **Tracking** | `builtProvisionCount = 5` (hardcoded) | `LazyCreationTracker.Instance` (real) |
| **Shutdown** | `_component = null` | `LazyCreationTracker.deactivate()` + `_component = null` |

Dagger genera `dagger.Lazy<T>` que funciona como `DoubleCheck`: thread-safe,
single-init. El primer `.get()` ejecuta el provider bajo synchronized; los
accesos posteriores retornan el valor cacheado sin lock.

---

## 2. Comparativa de Benchmarks

### Samsung Galaxy S22 Ultra -- Todas las operaciones (ns)

| Operacion | O<br>*(Metro eager)* | O2<br>*(Metro Lazy)* | Factor | P<br>*(KI-anvil eager)* | P2<br>*(KI-anvil Lazy)* | Factor | Q<br>*(Dagger @Module)* | Q2<br>*(Dagger Lazy)* | Factor |
|-----------|---:|---:|-------:|---:|---:|-------:|---:|---:|-------:|
| Init Cold | 723 | 1,412 | 0.5x | 785 | 1,722 | 0.5x | 647 | 1,502 | 0.4x |
| Resolve First | 5 | 7 | 0.7x | 0 | 5 | 0.0x | 5 | 7 | 0.7x |
| Lazy noDeps | 191 | 282 | 0.7x | 222 | 348 | 0.6x | 184 | 312 | 0.6x |
| Lazy cascade | 367 | 591 | 0.6x | 488 | 919 | 0.5x | 338 | 589 | 0.6x |
| CrossFeature | 2.1M | 2.1M | 1.0x | 1.3M | 1.4M | 0.9x | 1.2M | 1.8M | 0.7x |
| E2E Startup | 538K | 341K | **1.6x** | 534K | 552K | 1.0x | 568K | 520K | 1.1x |
| Init/Shutdown | 241 | 852 | 0.3x | 184 | 471 | 0.4x | 278 | 565 | 0.5x |
| Concurrent | 435K | 452K | 1.0x | 456K | 468K | 1.0x | 453K | 478K | 0.9x |
| Resolve All | 108 | 273 | 0.4x | 146 | 380 | 0.4x | 105 | 303 | 0.3x |
| **Re-Init** | 1,120 | 2,408 | 0.5x | 1,528 | 2,951 | 0.5x | 1,042 | 2,496 | 0.4x |
| Incremental | 694 | 1,411 | 0.5x | 784 | 1,661 | 0.5x | 639 | 1,395 | 0.5x |

*Factor > 1x = la variante lazy es mas rapida. Factor < 1x = la variante eager es mas rapida.*

### ⚠ Cambio de paradigma post-refactor (2026-04-19)

Antes del refactor: **lazy dominaba re-init (~15x)** gracias a que no recreaba
singletons. Tras el refactor (logger singleton + AtomicInteger counter + ThreadLocal
tracker):

1. **El logger singleton neutraliza la ventaja lazy en re-init.** Ahora eager y
   lazy son igual de rapidos en re-init (~1,000-3,000 ns). La ventaja historica
   de lazy (15x) desaparece porque el eager ya no reconstruye el logger en cada
   init.

2. **El `tracker.withActive { }` lambda anade ~150-300 ns por cada `get()` en lazy.**
   Coste necesario para el fix de `crossPatternIsolation` (ThreadLocal tracker
   aislado por pattern). Este overhead aparece en Init Cold (+40-50%), Init/Shutdown
   (+200-300%) y Resolve All (+160-180%).

3. **Lazy ahora es mas lento que eager en casi todas las metricas** excepto E2E
   Startup en O2 (1.6x) donde el logger singleton + laziness dan sinergia.

### Donde gana Lazy (post-refactor)

1. **E2E Startup (O2 vs O):** 1.6x mas rapido. La sinergia logger-singleton +
   no-crear-singletons-innecesarios compensa el overhead del `withActive`.

### Donde pierde Lazy (sistematicamente)

1. **Init Cold:** 0.4-0.5x -- overhead del `LazyCreationTracker.activate()` + `withActive`.

2. **Init/Shutdown Cycle:** 0.3-0.5x -- overhead del tracker cleanup.

3. **Resolve All:** 0.3-0.4x -- `withActive` envuelve cada `get()`.

4. **Re-Init:** 0.4-0.5x (REVERTIDO vs pre-refactor) -- el logger singleton
   beneficia eager mas que lazy.

### Donde es neutral

- **Concurrent:** Sin diferencia (threading domina, no DI).
- **CrossFeature:** Dominado por I/O de negocio.

---

## 3. Re-Init: El Benchmark Clave

El Re-Init simula un hot restart: `shutdown()` + `init()` completo. Este es el
escenario donde lazy brilla dramaticamente.

### Valores absolutos (post-refactor)

| Patron | Re-Init (ns) | Equivalente |
|--------|-------------:|-------------|
| Q (Dagger eager) | 1,042 | 1.0 us |
| O (Metro eager) | 1,120 | 1.1 us |
| P (kotlin-inject eager) | 1,528 | 1.5 us |
| Q2 (Dagger lazy) | 2,496 | 2.5 us |
| O2 (Metro lazy) | 2,408 | 2.4 us |
| P2 (kotlin-inject lazy) | 2,951 | 3.0 us |

### Por que eager ahora es mas rapido en re-init?

**Pre-refactor:** el re-init de eager (25-36 us) venia dominado por la creacion
de un nuevo `AndroidSdkLogger()` y la reconstruccion de singletons. Lazy ganaba
porque ninguno de los dos se ejecutaba (solo se materializaba on-demand).

**Post-refactor con `buildLogger()` singleton:** el logger ya no se recrea en cada
init. Los singletons en eager (O, P, Q) se construyen con el mismo logger
compartido, reduciendo el coste absoluto:

```
Eager (post-refactor):
  shutdown(): nullify graph (rapido)
  init(): create graph + reuse logger singleton + build 5 singletons
          Total: 1,042-1,528 ns

Lazy (post-refactor):
  shutdown(): nullify graph (rapido)
  init(): create graph (NO crea singletons) + LazyCreationTracker.activate()
          Total: 2,408-2,951 ns (overhead del tracker activate!)
```

El `LazyCreationTracker.activate()` + el lambda `withActive` en cada futuro get()
anaden overhead que supera la ganancia de "no crear singletons". Para 5 features,
no vale la pena.

---

## 4. Cuando Vale la Pena Lazy?

### SI vale la pena (post-refactor, criterio ajustado)

| Escenario | Razon |
|-----------|-------|
| SDK con 30+ features, usuario usa ~5 (<17%) | Lazy evita crear 25 singletons innecesarios. Con logger singleton, el umbral de rentabilidad sube. |
| SDK con features pesadas (construccion >5ms) | Si un singleton tarda 5ms en construirse, lazy evita ese coste si no se usa. |
| Testing modular con tests que solo necesitan 1 feature | Tests evitan construir 29 singletons que no tocan. |

### NO vale la pena

| Escenario | Razon |
|-----------|-------|
| SDK pequeno (3-10 features) | El overhead del `withActive` + tracker supera el ahorro de no crear 5 singletons. |
| Todas las features se usan siempre | Lazy solo difiere la creacion, no la evita. |
| Hot restart frecuente (post-refactor) | **Eager ahora es 1-2x mas rapido que lazy en re-init** gracias al logger singleton. |
| Performance critica en get() | Lazy paga ~150-300 ns extra por cada `get()` debido al `withActive` lambda. |

### Regla practica (post-refactor)

```
features_usadas / features_totales < 0.17  -->  Usar lazy (>30 features, usuario usa 5)
features_usadas / features_totales >= 0.17 -->  Usar eager (mas simple, mas rapido)
```

**El umbral subio de 0.5 a ~0.17 post-refactor.** La razon: eager ya no tiene el
penalty masivo de reconstruir el logger en cada init, lo que reduce dramaticamente
la ventaja relativa de lazy.

### Nota: lazy/eager es ortogonal al criterio bidimensional de wiring

La discusion lazy-vs-eager se refiere SOLO a si los singletons se crean en init
(eager) o en primer acceso (lazy). NO se refiere al wiring del modulo (Req 6) ni
al wiring del facade (Req 11). Ver `docs/shared/requirements.md`.

Para Re-Init, post-refactor los eager empatan o superan a los lazy:

| Patron | Re-Init (ns) | Notas |
|--------|--------------:|-------|
| Q (Dagger eager) | 1,042 | Mejor de todos -- logger singleton + reuse |
| O (Metro eager) | 1,120 | Idem |
| P (KI-anvil eager) | 1,528 | KSP generated code eager |
| O2 (Metro Lazy) | 2,408 | Tracker activate + withActive overhead |
| Q2 (Dagger Lazy) | 2,496 | Idem |
| P2 (KI-anvil Lazy) | 2,951 | Idem |
| D (hardcoded when) | 2,540 | Logger singleton + field clear |
| G (factory fns) | 2,275 | Idem |
| E2 (Registry DFS) | 15,816 | Auto-Init Registry preserva catalog, recrea provisions |
| N (sweet-spi+Koin) | 178,294 | Logger singleton evita reconstruir Koin container completo (-76% vs pre-refactor) |
| H (Resolver+ServiceLoader) | 185,812 | Re-discovery de providers via ServiceLoader |

H y N pagan el coste de descubrir providers cada vez. Es el trade-off de su facade
inmutable nativo: el dispatcher es trivial pero el descubrimiento es caro. Tras el
refactor ambos reducen costes significativamente via logger singleton.

---

## 5. Coste Real del Lazy Wrapper

### Overhead en primer acceso

| Framework | Mecanismo | Overhead primer acceso | Overhead accesos posteriores |
|-----------|-----------|----------------------:|-----------------------------:|
| Metro (O2) | `Lazy<T>.value` | ~200-300 ns | 0 ns (campo directo) |
| kotlin-inject (P2) | `@SingleIn` scope | ~200-350 ns | 0 ns (campo directo) |
| Dagger (Q2) | `dagger.Lazy<T>.get()` | ~200-300 ns | ~5 ns (volatile read) |

El overhead del lazy wrapper es de ~200-350 ns en el primer acceso -- insignificante
comparado con el coste de crear el singleton real (que tipicamente es 1,000-10,000 ns
dependiendo de la complejidad del servicio).

### Thread-safety

Los tres mecanismos son thread-safe:
- Metro `Lazy<T>`: generated code con synchronized
- kotlin-inject `@SingleIn`: `lazy {}` de Kotlin (synchronized por defecto)
- Dagger `DoubleCheck`: volatile + synchronized (double-check locking)

---

## 6. Resumen de Decision

```
Post-refactor: eager gana en casi todos los escenarios.

Necesitas el init mas rapido posible?
├── SI --> Eager (Q = 647, O = 723, P = 785 ns)
│
└── NO
    └── Tienes >30 features y el usuario tipico usa <20% de ellas?
        ├── SI --> Lazy (aun vale la pena por evitar construir 25+ singletons)
        └── NO --> Eager (mas rapido, mas simple, sin overhead del withActive)
```

| Recomendacion | Patron eager | Patron lazy |
|--------------|-------------|------------|
| KMP + compiler plugin | O (Metro) | O2 (Metro Lazy) |
| KMP + KSP estandar | P (kotlin-inject-anvil) | P2 (kotlin-inject-anvil Lazy) |
| Android + Dagger | Q (Hilt-style) | Q2 (Hilt-style Lazy) |
