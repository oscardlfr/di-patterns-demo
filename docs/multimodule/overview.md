# Patrones Multi-Modulo -- Taxonomia y Comparativa

Guia de los 16 patrones multi-modulo implementados en este proyecto, organizados
en 3 categorias segun su compatibilidad con Kotlin Multiplatform. Todos comparten
la misma API consumer (`MultiModuleSdkApi`: `init(ctx, cfg)` + `get<T>()` + `shutdown()`),
pero difieren en el framework DI, mecanismo de discovery y estrategia de lazy init.

> **Arquitectura actual (post-refactor)**: se elimino la jerarquia global de `Provisions`
> en `di-contracts/`. Ahora `di-contracts` es 100% neutro (no importa ningun tipo de
> `sdk/api` ni de feature-apis) y expone un `FeatureProvider` unificado con tag `Flavor`
> (DAGGER/PURE/KI/SYNTHETIC). Features multi-servicio declaran un Bundle interno
> (p.ej. `EncBundle` en `feature-enc-impl`), no una interface global. Detalles en
> `docs/shared/cross-feature-deps.md`.

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
| **D** | Dagger | Manual (when-block) | Si (ensure) | No | Si (synchronized) | 1,400 | 8 |
| **E2** | Dagger | Auto (Registry DFS) | Si (DFS) | No | Si (CHM + lock) | 8,024 | 9 |
| **G** | Dagger | Manual (factory fn) | Si (ensure) | No | Si (synchronized) | 1,379 | 9 |
| **H** | Dagger + ServiceLoader | ServiceLoader | Si (Resolver DFS) | No | Si (CHM + lock) | 86,254 | 1 |
| **I** | Ninguno | ServiceLoader | Si (Resolver DFS) | No | Si (CHM + lock) | 116,413 | 9 |
| **J** | kotlin-inject + SL | ServiceLoader | Si (Resolver DFS) | Parcial | Si (CHM + lock) | 122,124 | 1 |
| **K** | Dagger + Manifest | AndroidManifest | Si (Resolver DFS) | No | Si (CHM + lock) | 205,544 | 0 |
| **L** | Koin + ServiceLoader | ServiceLoader | Si (Koin single) | Parcial | Si (RWLock) | 161,559 | 999 |
| **M** | Koin + ServiceLoader | ServiceLoader | Si (loadModules) | Parcial | Si (RWLock+loadLock) | 164,713 | 1,066 |
| **N** | sweet-spi + Koin | sweet-spi | Si (Koin single) | **Si** | Si (RWLock) | 96,719 | 1,038 |
| **O** | Metro | Compile-time | No (eager) | **Si** | Si (nullify) | 723 | 5 |
| **O2** | Metro Lazy | Compile-time | Si (Lazy\<T\>+withActive) | **Si** | Si (nullify) | 1,412 | 7 |
| **P** | kotlin-inject-anvil | Compile-time | No (eager) | **Si** | Si (nullify) | 785 | 0 |
| **P2** | kotlin-inject-anvil Lazy | Compile-time | Si (LazyCreationTracker+withActive) | **Si** | Si (nullify) | 1,722 | 5 |
| **Q** | Dagger (Hilt-style) | Compile-time | No (eager) | No | Si (nullify) | 647 | 5 |
| **Q2** | Dagger Lazy (Hilt-style) | Compile-time | Si (dagger.Lazy+withActive) | No | Si (nullify) | 1,502 | 7 |

### Ranking por Init Cold

| Rank | Pattern | Init Cold (ns) | Categoria |
|-----:|---------|---------------:|-----------|
| 1 | Q (Dagger Hilt) | 647 | Android |
| 2 | O (Metro) | 723 | KMP |
| 3 | P (kotlin-inject-anvil) | 785 | KMP |
| 4 | G (Factory Functions) | 1,379 | Android |
| 5 | D (Component Deps) | 1,400 | Android |
| 6 | O2 (Metro Lazy) | 1,412 | KMP |
| 7 | Q2 (Dagger Lazy) | 1,502 | Android |
| 8 | P2 (kotlin-inject-anvil Lazy) | 1,722 | KMP |
| 9 | E2 (Auto-Init Registry) | 8,024 | Android |
| 10 | H (Dagger + ServiceLoader) | 86,254 | Android |
| 11 | N (sweet-spi + Koin) | 96,719 | KMP |
| 12 | I (Pure, zero DI) | 116,413 | Android |
| 13 | J (kotlin-inject + SL) | 122,124 | Partial KMP |
| 14 | L (Koin + ServiceLoader) | 161,559 | Partial KMP |
| 15 | M (Koin + SL lazy) | 164,713 | Partial KMP |
| 16 | K (AndroidManifest) | 205,544 | Android |

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

**Trade-off resumen** (ver `docs/shared/requirements.md` para criterios completos):
- Patrones con **facade inmutable nativo** (HashMap/runtime lookup): H, I, J, K, L, M, N, E2
- Patrones con **`when` manual en facade** (compile-time DI): O, O2, P, P2, Q, Q2
  - Mitigacion: KSP propio (~200 LOC) que genere el `when` desde el componente
- Patrones con **abstraccion runtime-flexible** (sdk-integration publicable con `runtimeOnly(features)`, permitiendo BYOF): **H, I, J, K, L, M, N** (7 de 16)
  - Los otros 9 acoplan el sdk-integration a feature-impls en compile-time (por merge de `@ContributesTo`/`@InstallIn` o por imports directos de factories)

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
| **FeatureProvider** | Clase base neutra en `di-contracts` con tag `Flavor` y `build(resolver)` que devuelve `Map<Class<*>, Any>`. Reemplaza a la antigua jerarquia `Provisions` |
| **Flavor** | Enum `{ DAGGER, PURE, KI, SYNTHETIC }` que discrimina proveedores dentro del mismo ServiceLoader. Los wirings H/I/J filtran por flavor |
| **SyntheticFeatureProvider** | Provider inyectado por el wiring en `init()` para publicar `Context` + `SdkConfig` como servicios normales (no hay path especial `bootstrap`) |
| **Bundle (local)** | Interfaz `internal` a una feature-impl que agrupa multiples servicios del mismo Component (p.ej. `EncBundle`). Reemplaza al antiguo concepto global de `Provisions` |
| **Wiring Module** | Modulo Gradle `sdk/wiring-X` que conecta features con el facade publico |
| **Discovery** | Mecanismo para encontrar features disponibles (ServiceLoader, Manifest, compile-time) |
| **Resolver DFS** | Resolucion de dependencias via busqueda en profundidad (depth-first search) |
| **Eager init** | Todo el grafo se construye en `init()` |
| **Lazy init** | Singletons se crean on-demand al primer `get<T>()` |
| **Compile-time aggregation** | El compilador/KSP reune todos los bindings en build time (Metro, kotlin-inject-anvil, Dagger) |
| **BYOF (Bring Your Own Features)** | Modelo de distribucion en el que el sdk-integration se publica sin `runtimeOnly(features)` y la app elige versiones de feature-impl. Requiere Req 12 |
