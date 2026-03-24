# Comparación de Frameworks DI para SDKs Modulares

Tablas de referencia rápida. Para el análisis completo con benchmarks, cumplimiento
de requisitos y matriz de decisión, ver [analisis-arquitecturas-di.md](analisis-arquitecturas-di.md).

Para implementaciones Dagger, ver [dagger2-sdk-selective-init.md](dagger2-sdk-selective-init.md).
Para conceptos DI, ver [di-sdk-consumer-isolation.md](di-sdk-consumer-isolation.md).
Para dependencias cruzadas, ver [di-cross-feature-deps.md](di-cross-feature-deps.md).
Para el approach hybrid, ver [di-hybrid-koin-sdk-dagger-app.md](di-hybrid-koin-sdk-dagger-app.md).

---

## Lado a Lado

| Criterio | Koin | Dagger B | Dagger C | Dagger D | |
|----------|------|----------|----------|----------|---|
| **Paradigma DI** | Service Locator | DI puro | DI puro | DI puro | |
| **Aislamiento máximo** | Nivel 2 | Nivel 1 | Nivel 1 | Nivel 1 | 🟢 Koin |
| **Cross-feature** | ✅ | ❌ | ❌ | ✅ | 🔴 B, C |
| **Binario lean** | ✅ | ✅ | ✅ | ❌ | 🔴 D |
| **Compile-time** | ❌ | ⚠️ | ⚠️ | ✅ | 🟢 D · 🔴 Koin |
| **KMP** | ✅ | ❌ | ❌ | ❌ | 🟢 Koin |
| **Auto-discovery** | ✅ | ❌ | ✅ | ❌ | |
| **Velocidad build** | ✅ | ❌ KSP | ❌ KSP | ❌ KSP | 🟢 Koin |
| **Singletons** | koinApplication | CoreApis ⚠️ | CoreApis ⚠️ | Provision methods | 🔴 B, C |

---

## Anti-patrón: Consumidor Importa Clases Impl

```kotlin
// ❌ Acoplado a implementación
import com.example.sdk.security.impl.SecurityServiceImpl
val service = SecurityServiceImpl(network)

// ✅ Depende de interfaz — todos los SDKs de este proyecto
val service = DaggerSdk.get<SecurityService>()     // Dagger D
val service = DaggerBSdk.get<SecurityService>()    // Dagger B
val service = DaggerCSdk.get<SecurityService>()    // Dagger C
val service = KoinSdk.get<SecurityService>()       // Koin
```
