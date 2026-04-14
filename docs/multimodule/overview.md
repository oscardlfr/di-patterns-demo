# Patrones Multi-Modulo -- Taxonomia y Comparativa

Guia de los 16 patrones multi-modulo implementados en este proyecto, organizados
en 3 categorias segun su compatibilidad con Kotlin Multiplatform. Todos comparten
la misma arquitectura base de provision interfaces y contratos en `di-contracts/`,
pero difieren en el framework DI, mecanismo de discovery y estrategia de lazy init.

Para patrones monoliticos (A, B, C, Koin, Hybrid), ver `docs/monolithic/`.

---

## 1. Taxonomia

```
                        Patrones Multi-Modulo (16)
                                  |
          +-----------------------+-----------------------+
          |                       |                       |
   Android-Only (8)         Partial KMP (3)         Full KMP (5)
          |                       |                       |
    D  - Component Deps     J  - kotlin-inject +    N  - sweet-spi + Koin
    E2 - Auto-Init Registry      ServiceLoader     O  - Metro (eager)
    G  - Factory Functions  L  - Koin +             O2 - Metro Lazy
    H  - ServiceLoader +         ServiceLoader     P  - kotlin-inject-anvil
         FeatureProvider    M  - Koin +             P2 - kotlin-inject-anvil Lazy
    I  - Pure (zero DI)          ServiceLoader
    K  - AndroidManifest         (lazy loadModules)
    Q  - Hilt-style Dagger
    Q2 - Hilt-style Dagger Lazy
```

**Android-Only:** Usan Dagger (KSP genera Java) o pure constructors. No compilan
para iOS/macOS/WASM. Incluyen los 3 patrones de wiring manual (D, E2, G), los 3
de discovery via ServiceLoader/Manifest (H, I, K) y los 2 de Dagger Hilt-style (Q, Q2).

**Partial KMP:** El framework DI es KMP-compatible (kotlin-inject o Koin), pero
el mecanismo de discovery depende de `java.util.ServiceLoader` (JVM-only). Pueden
convertirse en Full KMP reemplazando ServiceLoader por sweet-spi.

**Full KMP:** Funcionan en los 24 targets de Kotlin (JVM, Native, WASM). Usan
agregacion en compilacion (Metro, kotlin-inject-anvil) o discovery multiplataforma
(sweet-spi). Sin dependencia en `java.util.*` ni Android APIs.

---

## 2. Tabla Comparativa Completa

### Caracteristicas generales

| Pattern | Framework | Discovery | Lazy | KMP | Thread-safe shutdown | Init Cold (ns) | Resolve cached (ns) |
|---------|-----------|-----------|------|-----|----------------------|---------------:|---------------------:|
| **D** | Dagger | Manual (when-block) | Si (ensure) | No | Si (synchronized) | 1,212 | 346 |
| **E2** | Dagger | Auto (Registry DFS) | Si (DFS) | No | Si (CHM + lock) | 10,983 | 199 |
| **G** | Dagger | Manual (factory fn) | Si (ensure) | No | Si (synchronized) | 1,257 | 345 |
| **H** | Dagger + ServiceLoader | ServiceLoader | Si (Resolver DFS) | No | Si (CHM + lock) | 106,865 | 202 |
| **I** | Ninguno | ServiceLoader | Si (Resolver DFS) | No | Si (CHM + lock) | 94,255 | 203 |
| **J** | kotlin-inject + SL | ServiceLoader | Si (Resolver DFS) | Parcial | Si (CHM + lock) | 97,197 | 202 |
| **K** | Dagger + Manifest | AndroidManifest | Si (Resolver DFS) | No | Si (CHM + lock) | 213,737 | 203 |
| **L** | Koin + ServiceLoader | ServiceLoader | Si (Koin single) | Parcial | No | 154,403 | 5,664 |
| **M** | Koin + ServiceLoader | ServiceLoader | Si (loadModules) | Parcial | Si (synchronized) | 164,353 | 6,160 |
| **N** | sweet-spi + Koin | sweet-spi | Si (Koin single) | **Si** | No | 69,636 | 5,855 |
| **O** | Metro | Compile-time | No (eager) | **Si** | Si (nullify) | 603 | 288 |
| **O2** | Metro Lazy | Compile-time | Si (Lazy\<T\>) | **Si** | Si (nullify) | 1,127 | 315 |
| **P** | kotlin-inject-anvil | Compile-time | No (eager) | **Si** | Si (nullify) | 1,064 | 336 |
| **P2** | kotlin-inject-anvil Lazy | Compile-time | Si (LazyCreationTracker) | **Si** | Si (nullify) | 1,416 | 335 |
| **Q** | Dagger (Hilt-style) | Compile-time | No (eager) | No | Si (nullify) | 676 | 257 |
| **Q2** | Dagger Lazy (Hilt-style) | Compile-time | Si (dagger.Lazy) | No | Si (nullify) | 1,080 | 306 |

### Ranking por Init Cold

| Rank | Pattern | Init Cold (ns) | Categoria |
|-----:|---------|---------------:|-----------|
| 1 | O (Metro) | 603 | KMP |
| 2 | Q (Dagger Hilt) | 676 | Android |
| 3 | P (kotlin-inject-anvil) | 1,064 | KMP |
| 4 | Q2 (Dagger Lazy) | 1,080 | Android |
| 5 | O2 (Metro Lazy) | 1,127 | KMP |
| 6 | D (Component Deps) | 1,212 | Android |
| 7 | G (Factory Functions) | 1,257 | Android |
| 8 | P2 (kotlin-inject-anvil Lazy) | 1,416 | KMP |
| 9 | E2 (Auto-Init Registry) | 10,983 | Android |
| 10 | N (sweet-spi + Koin) | 69,636 | KMP |
| 11 | I (Pure, zero DI) | 94,255 | Android |
| 12 | J (kotlin-inject + SL) | 97,197 | Partial KMP |
| 13 | H (Dagger + ServiceLoader) | 106,865 | Android |
| 14 | L (Koin + ServiceLoader) | 154,403 | Partial KMP |
| 15 | M (Koin + SL lazy) | 164,353 | Partial KMP |
| 16 | K (AndroidManifest) | 213,737 | Android |

---

## 3. Arbol de Decision

### Cual patron elegir?

```
Necesitas KMP (iOS, macOS, WASM)?
├── SI
│   ├── Necesitas lazy singletons?
│   │   ├── SI --> O2 (Metro Lazy) o P2 (kotlin-inject-anvil Lazy)
│   │   └── NO --> O (Metro) o P (kotlin-inject-anvil)
│   └── Ya usas Koin?
│       └── SI --> N (sweet-spi + Koin)  [Nota: ~115x mas lento que O en init]
│
├── NO (Android-only)
│   ├── Ya usas Dagger/Hilt?
│   │   ├── SI
│   │   │   ├── Necesitas lazy singletons?
│   │   │   │   ├── SI --> Q2 (Hilt-style Dagger Lazy)
│   │   │   │   └── NO --> Q (Hilt-style Dagger)
│   │   │   └── Pocas features (<10)?
│   │   │       ├── SI --> D (Component Deps) o G (Factory Functions)
│   │   │       └── NO --> Q (inmutable, escala)
│   │   └── NO
│   │       ├── Quieres zero framework?
│   │       │   └── SI --> I (Pure constructors + ServiceLoader)
│   │       └── Auto-discovery sin editar wiring?
│   │           ├── SI --> H (ServiceLoader) o K (AndroidManifest)
│   │           └── NO --> E2 (Auto-Init Registry)
│   │
│   └── Tienes patrones J/L/M y quieres migrar a KMP?
│       └── SI --> Reemplazar ServiceLoader por sweet-spi (convierte a N)
```

### Recomendacion rapida

| Contexto | Patron recomendado | Razon | Caveat |
|----------|-------------------|-------|--------|
| SDK KMP nuevo, perf primario | **O** (Metro) | Init mas rapido (603 ns), compile-time safe | Facade `when` manual por API (Req 11). Mitigable con KSP propio |
| SDK KMP con muchas features | **O2** (Metro Lazy) | Re-init 15x mas rapido, singletons on-demand | Mismo caveat de O |
| SDK KMP zero-touch end-to-end | **N** (sweet-spi + Koin) | Auto-registro grafo + facade inmutable nativo | Sin compile-time safety. Mitigable con `koin.verify()` en CI |
| App Android con Hilt | **Q** (Hilt-style Dagger) | Familiar, compile-time safe, 676 ns init | Doble edicion central: `@Component(modules=[...])` + `when` del facade |
| SDK Android con auto-discovery | **H** (ServiceLoader) | Wiring inmutable end-to-end, escala a 50+ features × N APIs | Init lento (107 us). Compile-time parcial -- mitigable con `verify()` |
| Migracion gradual a KMP | **P** (kotlin-inject-anvil) | KSP genera Kotlin, same aggregation pattern | Mismo caveat de facade que O/P2 |

**Trade-off resumen** (ver `docs/shared/requirements.md` para criterio bidimensional):
- Patrones con **facade inmutable nativo** (HashMap/runtime lookup): H, I, J, K, L, M, N, E2
- Patrones con **`when` manual en facade** (compile-time DI): O, O2, P, P2, Q, Q2
- Mitigacion para los segundos: KSP propio (~200 LOC) que genere el `when` desde el componente

---

## 4. Documentacion por Categoria

### Android-Only

- **[Patrones Android-Only](android/patterns-overview.md):** D, E2, G, H, I, K, Q, Q2
- **[Benchmarks Android-Only](android/benchmark-results.md):** Resultados detallados

### KMP-Compatible

- **[Patrones KMP](kmp/patterns-overview.md):** N, O, O2, P, P2
- **[Benchmarks KMP](kmp/benchmark-results.md):** Resultados detallados

### Partial KMP

- **[Patrones Partial KMP](partial-kmp/patterns-overview.md):** J, L, M
- No tiene benchmark separado -- los datos estan en la tabla comparativa de arriba

### Transversal

- **[Lazy vs Eager en Compile-Time DI](lazy-vs-eager.md):** Comparativa O/O2, P/P2, Q/Q2
- **[Arquitectura API/Impl](api-impl-architecture.md):** Estructura Gradle compartida
- **[Benchmark completo (legacy)](benchmark-results.md):** Todos los 16 patrones en tabla unica

---

## 5. Glosario Rapido

| Termino | Definicion |
|---------|-----------|
| **Provision Interface** | Interfaz Kotlin que declara los servicios de una feature (e.g. `EncProvisions`) |
| **Wiring Module** | Modulo Gradle `sdk/wiring-X` que conecta features con el facade publico |
| **Discovery** | Mecanismo para encontrar features disponibles (ServiceLoader, Manifest, compile-time) |
| **Resolver DFS** | Resolucion de dependencias via busqueda en profundidad (depth-first search) |
| **Eager init** | Todo el grafo se construye en `init()` |
| **Lazy init** | Singletons se crean on-demand al primer `get<T>()` |
| **Compile-time aggregation** | El compilador/KSP reune todos los bindings en build time (Metro, kotlin-inject-anvil, Dagger) |
