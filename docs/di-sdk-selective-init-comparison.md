# Comparación de Frameworks DI para SDKs Modulares

Tablas de referencia rápida. Para el análisis completo con benchmarks, cumplimiento
de requisitos y matriz de decisión, ver [analisis-arquitecturas-di.md](analisis-arquitecturas-di.md).

Para implementaciones Dagger, ver [dagger2-sdk-selective-init.md](dagger2-sdk-selective-init.md).
Para conceptos DI, ver [di-sdk-consumer-isolation.md](di-sdk-consumer-isolation.md).
Para dependencias cruzadas, ver [di-cross-feature-deps.md](di-cross-feature-deps.md).
Para el approach hybrid, ver [di-hybrid-koin-sdk-dagger-app.md](di-hybrid-koin-sdk-dagger-app.md).

---

## Lado a Lado

| Criterio | Koin | Dagger B | Dagger C | Dagger D | Dagger E | Dagger E2 | Dagger F | |
|----------|------|----------|----------|----------|----------|-----------|----------|----|
| **Paradigma DI** | Service Locator | DI puro | DI puro | DI puro | DI + Registry | DI + AutoRegistry | DI puro | |
| **Aislamiento máximo** | Nivel 2 | Nivel 1 | Nivel 1 | Nivel 1 | Nivel 1 | Nivel 1 | Nivel 1 | 🟢 Koin |
| **Cross-feature** | ✅ | ❌ | ❌ | ✅ | ✅ | ✅ | ✅ | 🔴 B, C |
| **Binario lean** | ✅ | ✅ | ✅ | ❌ | ❌ | ❌ | ⚠️ | 🔴 D, E, E2 |
| **Compile-time** | ❌ | ⚠️ | ⚠️ | ✅ | ✅ | ✅ | ✅ | 🟢 D-F · 🔴 Koin |
| **KMP** | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | 🟢 Koin |
| **Auto-discovery** | ✅ | ❌ | ✅ | ❌ | ⚠️ topo-sort | ✅ DFS on-demand | ❌ | |
| **Multi-módulo** | ✅ | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ | 🔴 D |
| **Escala 50+** | ✅ | ❌ | ⚠️ | ❌ | ❌ | **✅** | ❌ | 🟢 Koin, E2 |
| **Feature enum** | N/A | Expuesto | N/A | Expuesto | Expuesto | **Oculto** | Expuesto | 🟢 E2 |
| **Build speed** | ✅ | ❌ KSP | ❌ KSP | ❌ KSP | ❌ KSP | ❌ KSP | ❌ KSP | 🟢 Koin |
| **Singletons** | koinApp | CoreApis ⚠️ | CoreApis ⚠️ | Provision | Registry | AutoRegistry | Provision | 🔴 B, C |

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
```
