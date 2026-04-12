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

| Operacion | O | O2 | Factor | P | P2 | Factor | Q | Q2 | Factor |
|-----------|--:|---:|-------:|--:|---:|-------:|--:|---:|-------:|
| Init Cold | 603 | 1,127 | 1.9x | 1,064 | 1,416 | 1.3x | 676 | 1,080 | 1.6x |
| Resolve First | 288 | 315 | 1.1x | 336 | 335 | 1.0x | 257 | 306 | 1.2x |
| Lazy noDeps | 2,098 | 238 | **8.8x** | 1,941 | 284 | **6.8x** | 1,735 | 236 | **7.3x** |
| Lazy cascade | 346 | 507 | 0.7x | 607 | 734 | 0.8x | 318 | 504 | 0.6x |
| CrossFeature | 1.7M | 1.8M | 1.0x | 1.7M | 3.1M | 0.5x | 1.6M | 1.7M | 0.9x |
| E2E Startup | 1.2M | 1.5M | 0.8x | 1.4M | 993K | **1.4x** | 950K | 1.3M | 0.7x |
| Init/Shutdown | 301 | 516 | 0.6x | 293 | 508 | 0.6x | 403 | 549 | 0.7x |
| Concurrent | 586K | 587K | 1.0x | 618K | 638K | 1.0x | 591K | 586K | 1.0x |
| Resolve All | 80 | 86 | 0.9x | 165 | 156 | 1.1x | 64 | 85 | 0.8x |
| **Re-Init** | 36,000 | 2,305 | **15.6x** | 28,000 | 2,929 | **9.6x** | 25,000 | 2,157 | **11.6x** |
| Incremental | 588 | 952 | 0.6x | 1,060 | 1,321 | 0.8x | 667 | 1,218 | 0.5x |

*Factor > 1x = la variante lazy es mas rapida. Factor < 1x = la variante eager es mas rapida.*

### Donde gana Lazy (significativamente)

1. **Re-Init:** Las variantes lazy son 9.6x-15.6x mas rapidas. Este es el beneficio
   mas importante de lazy: al reinicializar, no se recrean singletons que posiblemente
   no se usaran.

2. **Lazy noDeps:** 6.8x-8.8x mas rapido. Crear un solo singleton on-demand es
   drasticamente mas barato que haberlos creado todos en init.

### Donde pierde Lazy (ligeramente)

1. **Init Cold:** 1.3x-1.9x mas lento. El setup de `LazyCreationTracker` y los
   Lazy wrappers anaden overhead al init. Sin embargo, todos estan por debajo
   de 1,500 ns -- la diferencia es de ~400-500 ns.

2. **Init/Shutdown Cycle:** ~0.6-0.7x -- el cleanup del tracker anade ~200 ns.

3. **Lazy cascade:** ~0.6-0.8x -- la cascada lazy es ligeramente mas lenta porque
   cada paso materializa un Lazy wrapper. Eager ya tiene todo construido.

### Donde es neutral

- **Resolve First:** Practicamente identico (~1.0-1.2x).
- **Concurrent:** Sin diferencia (threading domina, no DI).
- **Resolve All:** Sin diferencia significativa (cache hit en ambos).
- **CrossFeature:** Variable, dominado por logica de negocio.

---

## 3. Re-Init: El Benchmark Clave

El Re-Init simula un hot restart: `shutdown()` + `init()` completo. Este es el
escenario donde lazy brilla dramaticamente.

### Valores absolutos

| Patron | Re-Init (ns) | Equivalente |
|--------|-------------:|-------------|
| O (Metro eager) | 36,000 | 36 us |
| O2 (Metro lazy) | 2,305 | 2.3 us |
| P (kotlin-inject eager) | 28,000 | 28 us |
| P2 (kotlin-inject lazy) | 2,929 | 2.9 us |
| Q (Dagger eager) | 25,000 | 25 us |
| Q2 (Dagger lazy) | 2,157 | 2.2 us |

### Por que lazy es tan rapido en re-init?

**Eager (O, P, Q):** `shutdown()` destruye el grafo completo. `init()` reconstruye
el grafo, lo que incluye crear todos los singletons de las 5 features:

```
shutdown(): nullify graph (rapido)
init(): create graph --> create EncService --> create AuthService
        --> create StorService --> create AnaService --> create SynService
        Total: 25,000-36,000 ns
```

**Lazy (O2, P2, Q2):** `shutdown()` destruye el grafo y resetea el tracker.
`init()` solo crea el grafo/component vacio -- los singletons no se instancian:

```
shutdown(): deactivate tracker + nullify graph (rapido)
init(): create graph (NO crea singletons)
        Total: 2,157-2,929 ns
```

Los singletons se crearan on-demand cuando `get<T>()` los solicite.

---

## 4. Cuando Vale la Pena Lazy?

### SI vale la pena

| Escenario | Razon |
|-----------|-------|
| SDK con 20+ features, usuario usa ~5 | Lazy evita crear 15 singletons innecesarios |
| Hot restart frecuente | Re-init 10-16x mas rapido |
| Modular testing | Tests que solo necesitan 1 feature no pagan el coste de las otras |
| Background services | Iniciar rapido, resolver solo lo necesario |
| SDK con features pesadas | Si un singleton tarda 10ms en construirse, lazy evita ese coste si no se usa |

### NO vale la pena

| Escenario | Razon |
|-----------|-------|
| SDK pequeno (3-5 features) | La diferencia es de ~500 ns en init -- irrelevante |
| Todas las features se usan siempre | Lazy solo difiere la creacion, no la evita |
| App que no hace re-init | El beneficio principal se pierde |
| Performance critica en primer acceso | El primer `get<T>()` lazy paga la creacion del singleton |

### Regla practica

```
features_usadas / features_totales < 0.5  -->  Usar lazy
features_usadas / features_totales >= 0.5 -->  Usar eager (mas simple)
```

Si el ratio de features usadas vs totales es menor al 50%, lazy ahorra trabajo
significativo.

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
Necesitas el init mas rapido posible (y no te importa re-init)?
├── SI --> Eager (O = 603 ns, Q = 676 ns, P = 1,064 ns)
│
└── NO
    └── Haces hot restart frecuente o tienes muchas features opcionales?
        ├── SI --> Lazy (Q2 = 2,157 ns re-init, O2 = 2,305 ns, P2 = 2,929 ns)
        └── NO --> Eager (mas simple, sin overhead de tracking)
```

| Recomendacion | Patron eager | Patron lazy |
|--------------|-------------|------------|
| KMP + compiler plugin | O (Metro) | O2 (Metro Lazy) |
| KMP + KSP estandar | P (kotlin-inject-anvil) | P2 (kotlin-inject-anvil Lazy) |
| Android + Dagger | Q (Hilt-style) | Q2 (Hilt-style Lazy) |
