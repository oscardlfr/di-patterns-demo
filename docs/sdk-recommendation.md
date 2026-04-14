# Recomendacion de Patron DI para SDK Corporativo

Dos documentos de recomendacion segun el alcance de plataformas. **Ningun patron domina
todos los criterios**: la eleccion depende de los ejes que el equipo prioriza.

- **[Android-Only](sdk-recommendation-android.md)** -- SDK nativo Android, +50 modulos, 10 devs.
  - Tier 1: **H** (zero-touch end-to-end + madurez), **O2** (perf + compile-time, requiere KSP propio para facade), **P2** (variante O2 con KSP estandar)
  - Tier 2: **E2** (unico Dagger con compile-time completa + facade inmutable)

- **[KMP Multi-Plataforma](sdk-recommendation-kmp.md)** -- SDK KMP (Android + iOS + Desktop), +50 modulos, 10 devs.
  - **N (sweet-spi + Koin)**: zero-touch end-to-end nativo, sin compile-time safety
  - **P2 (kotlin-inject-anvil Lazy)**: compile-time completa + auto-registro grafo, requiere KSP propio para facade
  - **H + sweet-spi (hibrido)**: facade inmutable + control total del codigo

**Criterio clave a tener en cuenta** (introducido en `docs/shared/requirements.md` Req 11):
muchos patrones compile-time DI (Metro, kotlin-inject, Dagger) auto-agregan modulos al
grafo via `@ContributesTo`/`@Module`, pero el dispatcher `get<T>(Class)` del facade SDK
mantiene un `when (clazz)` manual que crece linealmente por API. A 50 features × 10 APIs
= 500 ramas mantenidas a mano. Mitigable con un procesador KSP propio (~200 LOC).
Patrones runtime DI (Koin) y Resolver-based (H/I/J/K/E2) cumplen este criterio nativamente
sin codegen propio.
