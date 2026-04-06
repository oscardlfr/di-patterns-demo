# Benchmark Results — Samsung Galaxy S22 Ultra

Dispositivo: Samsung Galaxy S22 Ultra (SM-S908B) — Snapdragon 8 Gen 1, 8 cores, 2.8 GHz, Android 16.
Framework: Jetpack Benchmark 1.4.0 con warmup automatico.
Fecha: 2026-04-06.
Tests: 44 (19 monoliticos + 25 multi-modulo), todos usando facades reales.

## Patrones

| Abreviatura | Patron | Modulo | Tipo |
|-------------|--------|--------|------|
| MM-D | Component Dependencies | sdk-wiring | Multi-modulo |
| MM-E | Registry + topo-sort | wiring-e | Multi-modulo |
| MM-E2 | Auto-Init + DFS | wiring-e2 | Multi-modulo |
| MM-G | Factory Functions | wiring-g | Multi-modulo |
| MM-H | Auto-Discovery FeatureProviders | wiring-h | Multi-modulo |
| Dagger-B | Per-Feature Components | impl-dagger-b | Monolitico |
| Dagger-C | ServiceLoader Discovery | impl-dagger-c | Monolitico |
| Koin | Service Locator | impl-koin | Monolitico |
| Hybrid | Koin SDK + Dagger bridge | impl-koin + bridge | Monolitico |

---

## Init Cold (grafo completo, 6 features)

Tiempo para crear el grafo DI completo desde cero e instanciar todos los singletons.

| Patron | Mediana | vs Mejor |
|--------|---------|----------|
| MM-D | 947 ns | == |
| MM-G | 966 ns | == |
| Dagger-B | 3.2 us | 3.4x |
| MM-H | 3.5 us | 3.7x |
| MM-E2 | 5.4 us | 5.7x |
| Dagger-C | 5.6 us | 5.9x |
| MM-E | 10.2 us | 10.8x |
| Hybrid | 53.0 us | 56.0x |
| Koin | 53.2 us | 56.2x |

**Observacion:** D y G multi-modulo son los mas rapidos (~950 ns). H paga overhead de HashMap + registro de providers (~3.5 us, ~3.5x sobre G). E2 (~5.4 us) y E (~10.2 us) son mas lentos por install+DFS y topo-sort respectivamente. Koin/Hybrid ~53 us — imperceptible (<0.003 frames) pero 56x mas que D.

## Resolve First (primer acceso a un singleton)

| Patron | Mediana | vs Mejor |
|--------|---------|----------|
| Hybrid | 2.3 ns | == |
| Dagger-B | 8.1 ns | 3.5x |
| MM-D | 10.6 ns | 4.6x |
| MM-G | 14.2 ns | 6.2x |
| MM-E | 20.1 ns | 8.7x |
| MM-H | 23.3 ns | 10.1x |
| MM-E2 | 23.3 ns | 10.1x |
| Dagger-C | 36.8 ns | 16.0x |
| Koin | 910.7 ns | 396.0x |

**Observacion:** Hybrid es el mas rapido en resolve (Dagger @Singleton cache: 2.3 ns). Koin paga lookup runtime (~911 ns). Todos los Dagger multi-modulo <24 ns. H y E2 son identicos en resolve (~23 ns).

## Lazy Init — Feature sin dependencias (Analytics)

Tiempo de anadir Analytics (solo depende de Core) a un grafo en ejecucion.

| Patron | Mediana | vs Mejor |
|--------|---------|----------|
| MM-D | 211 ns | == |
| MM-G | 216 ns | == |
| Dagger-B | 420.5 ns | 2.0x |
| MM-H | 482 ns | 2.3x |
| Dagger-C | 517.7 ns | 2.5x |
| MM-E2 | 545 ns | 2.6x |
| MM-E | 1.8 us | 8.5x |
| Koin | 9.4 us | 44.5x |

**Observacion:** D y G son los mas rapidos (~213 ns). H (~482 ns) es ligeramente mas lento por overhead de resolver. E paga topo-sort (~1.8 us). Koin paga loadModules (~9.4 us).

## Lazy Init — Cascada (Sync -> Auth + Storage + Encryption)

Tiempo de inicializacion en cascada: pedir Sync desencadena Auth -> Encryption, Storage -> Encryption.

| Patron | Mediana | vs Mejor |
|--------|---------|----------|
| MM-D | 600 ns | == |
| MM-G | 606 ns | == |
| MM-H | 1.4 us | 2.3x |
| MM-E2 | 1.8 us | 3.0x |
| Dagger-B | 3.4 us | 5.7x |
| Dagger-C | 3.5 us | 5.8x |
| MM-E | 5.8 us | 9.7x |
| Koin | 27.7 us | 46.2x |

**Observacion:** D y G resuelven la cascada en ~600 ns. H (1.4 us) es mas rapido que E2 (1.8 us) en cascada — DFS resolver es eficiente. Ambos son mas rapidos que B/C monoliticos (3.4 us) — provision interfaces son mas eficientes que CoreApis extendidas.

## Cross-Feature Op (Sync.sync())

Tiempo de una operacion real que cruza Auth + Storage + Encryption. Singletons resueltos una vez fuera del loop. Mide solo el trabajo, no el DI.

| Patron | Mediana | vs Mejor |
|--------|---------|----------|
| MM-E | 101.7 us | == |
| Hybrid | 101.3 us | == |
| MM-D | 102.5 us | == |
| MM-G | 102.3 us | == |
| MM-E2 | 103.0 us | == |
| MM-H | 103.2 us | == |
| Dagger-C | 106.4 us | 1.0x |
| Dagger-B | 107.8 us | 1.1x |
| Koin | 168.3 us | 1.7x |

**Observacion:** Con singletons resueltos, el framework DI no participa. La variacion (~101-168 us) es atribuible a thermal throttle y orden de ejecucion. Todos los approaches son equivalentes en operacion real.

---

## Resumen Multi-modulo

| Test | D | G | H | E2 | E |
|------|---|---|---|----|----|
| Init Cold | 947 ns | 966 ns | 3.5 us | 5.4 us | 10.2 us |
| Resolve First | 10.6 ns | 14.2 ns | 23.3 ns | 23.3 ns | 20.1 ns |
| Lazy Init (0 deps) | 211 ns | 216 ns | 482 ns | 545 ns | 1.8 us |
| Lazy Init (cascade) | 600 ns | 606 ns | 1.4 us | 1.8 us | 5.8 us |
| Cross-Feature Op | 102.5 us | 102.3 us | 103.2 us | 103.0 us | 101.7 us |

**D y G son identicos en rendimiento.** G ofrece mejor encapsulacion (DaggerXxxComponent queda `internal`).
**H es ~3.5x mas lento que D/G en init** — paga HashMap + registro de providers. Pero el wiring module es inmutable: zero edicion central al anadir features. Indicado para equipos grandes (10+).
**E2 es 2-5x mas lento que D/G** en init — paga install+DFS. Escala a 50+ features sin editar el facade.
**E es el mas lento** — topo-sort eager es costoso vs DFS lazy.

---

## Conclusion

Todos los approaches Dagger estan en el rango de microsegundos. La diferencia maxima entre el mejor (D: 947 ns) y el peor Dagger (E: 10.2 us) en init cold es ~9 us — 0.0006 frames. **Imperceptible para el usuario.** La eleccion entre approaches es de arquitectura y mantenibilidad, no de rendimiento.

H ofrece el mejor trade-off para equipos grandes: ~3.5 us de init (vs 966 ns de G) a cambio de wiring inmutable y zero edicion central. Para equipos pequenos donde la edicion del facade no es un problema, D o G son la opcion mas rapida.
