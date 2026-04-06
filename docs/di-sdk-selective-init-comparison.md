# Comparación de Frameworks DI para SDKs Modulares

Tablas de referencia rápida. Para el análisis completo con benchmarks, cumplimiento
de requisitos y matriz de decisión, ver [analisis-arquitecturas-di.md](analisis-arquitecturas-di.md).

Para implementaciones Dagger, ver [dagger2-sdk-selective-init.md](dagger2-sdk-selective-init.md).
Para conceptos DI, ver [di-sdk-consumer-isolation.md](di-sdk-consumer-isolation.md).
Para dependencias cruzadas, ver [di-cross-feature-deps.md](di-cross-feature-deps.md).
Para el approach hybrid, ver [di-hybrid-koin-sdk-dagger-app.md](di-hybrid-koin-sdk-dagger-app.md).

---

## Lado a Lado

| Criterio | Koin | Dagger B | Dagger C | Dagger D | Dagger E | Dagger E2 | |
|----------|------|----------|----------|----------|----------|-----------|---|
| **Paradigma DI** | Service Locator | DI puro | DI puro | DI puro | DI + Registry | DI + AutoRegistry | |
| **Aislamiento máximo** | Nivel 2 | Nivel 1 | Nivel 1 | Nivel 1 | Nivel 1 | Nivel 1 | 🟢 Koin |
| **Cross-feature** | ✅ | ❌ | ❌ | ✅ | ✅ | ✅ | 🔴 B, C |
| **Binario lean** | ✅ | ✅ | ✅ | ❌ | ❌ | ❌ | 🔴 D, E, E2 |
| **Compile-time** | ❌ | ⚠️ | ⚠️ | ✅ | ✅ | ✅ | 🟢 D-E2 · 🔴 Koin |
| **KMP** | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | 🟢 Koin |
| **Auto-discovery** | ✅ | ❌ | ✅ | ❌ | ⚠️ topo-sort | ✅ DFS on-demand | |
| **Multi-módulo** | ✅ | ✅ | ✅ | ❌ | ✅ | ✅ | 🔴 D |
| **Escala 50+** | ✅ | ❌ | ⚠️ | ❌ | ❌ | **✅** | 🟢 Koin, E2 |
| **Feature enum** | N/A | Expuesto | N/A | Expuesto | Expuesto | **Oculto** | 🟢 E2 |
| **Build speed** | ✅ | ❌ KSP | ❌ KSP | ❌ KSP | ❌ KSP | ❌ KSP | 🟢 Koin |
| **Singletons** | koinApp | CoreApis ⚠️ | CoreApis ⚠️ | Provision | Registry | AutoRegistry | 🔴 B, C |

### Variantes multi-módulo

D, E, E2 y G tienen variantes multi-módulo (`sdk-wiring`, `wiring-e`, `wiring-e2`, `wiring-g`)
que usan provision interfaces y contratos per-feature. Las cuatro comparten los mismos
feature-impl y contratos — solo difiere el código de wiring. El rendimiento es
comparable entre variantes (20 benchmarks en `MultiModuleBenchmark.kt`).

| Criterio | Multi-D (sdk-wiring) | Multi-E (wiring-e) | Multi-E2 (wiring-e2) | Multi-G (wiring-g) |
|----------|---------------------|--------------------|-----------------------|---------------------|
| **Wiring** | 1 fichero, ~145 líneas | 2 ficheros, ~170 líneas | 2 ficheros, ~100 líneas | 1 fichero, ~95 líneas |
| **Binario lean** | ✅ (per-feature Gradle) | ✅ | ✅ | ✅ |
| **Provision interfaces** | ✅ | ✅ | ✅ | ✅ |
| **Components internal** | ❌ (públicos) | ❌ (internos vía lambda) | ❌ (internos vía lambda) | ✅ (factory functions) |
| **Escala 50+** | ❌ when blocks | ❌ enum crece | ✅ 1 línea por feature | ❌ ensure*() crece |

Para el análisis completo, ver [di-multimodule-api-impl-analysis.md](di-multimodule-api-impl-analysis.md).

---

## Anti-patrón: Consumidor Importa Clases Impl

```kotlin
// ❌ Acoplado a implementación
import com.example.sdk.security.impl.SecurityServiceImpl
val service = SecurityServiceImpl(network)

// ✅ Depende de interfaz — todos los SDKs de este proyecto
val service = DaggerSdk.get<SecurityService>()     // Dagger D
val service = RegistrySdk.get<SecurityService>()   // Dagger E
val service = AutoSdk.get<SecurityService>()       // Dagger E2
val service = DaggerBSdk.get<SecurityService>()    // Dagger B
val service = DaggerCSdk.get<SecurityService>()    // Dagger C
val service = KoinSdk.get<SecurityService>()       // Koin

// ✅ Multi-módulo — misma API, wiring diferente
val service = MultiModuleSdk.get<SecurityService>()     // Multi-módulo D (sdk-wiring)
val service = MultiModuleSdkE.get<SecurityService>()    // Multi-módulo E (wiring-e)
val service = MultiModuleSdkE2.get<SecurityService>()   // Multi-módulo E2 (wiring-e2)
val service = MultiModuleSdkG.get<SecurityService>()    // Multi-módulo G (wiring-g)
```
