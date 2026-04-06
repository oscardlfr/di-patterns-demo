# Benchmark Results — Samsung Galaxy S22 Ultra

Dispositivo: Samsung Galaxy S22 Ultra (SM-S908B) — Snapdragon 8 Gen 1, 8 cores, 2.8 GHz, Android 16.
Framework: Jetpack Benchmark 1.4.0 con warmup automatico.
Fecha: 2026-04-06.
Tests: 39 (19 monoliticos + 20 multi-modulo), todos usando facades reales.

## Patrones

| Abreviatura | Patron | Modulo | Tipo |
|-------------|--------|--------|------|
| MM-D | Component Dependencies | sdk-wiring | Multi-modulo |
| MM-E | Registry + topo-sort | wiring-e | Multi-modulo |
| MM-E2 | Auto-Init + DFS | wiring-e2 | Multi-modulo |
| MM-G | Factory Functions | wiring-g | Multi-modulo |
| Dagger-B | Per-Feature Components | impl-dagger-b | Monolitico |
| Dagger-C | ServiceLoader Discovery | impl-dagger-c | Monolitico |
| Koin | Service Locator | impl-koin | Monolitico |
| Hybrid | Koin SDK + Dagger bridge | impl-koin + bridge | Monolitico |

---

## Init Cold (grafo completo, 6 features)

Tiempo para crear el grafo DI completo desde cero e instanciar todos los singletons.

| Patron | Mediana | vs Mejor |
|--------|---------|----------|
| MM-D | 1.8 us | == |
| MM-G | 1.9 us | == |
| Dagger-B | 3.2 us | 1.7x |
| Dagger-C | 5.6 us | 3.1x |
| MM-E2 | 9.5 us | 5.2x |
| MM-E | 17.8 us | 9.8x |
| Hybrid | 53.0 us | 29.1x |
| Koin | 53.2 us | 29.2x |

**Observacion:** D y G multi-modulo son los mas rapidos (~1.8 us). E2 paga overhead de install+DFS (~9.5 us). Koin/Hybrid ~53 us — imperceptible (<0.003 frames) pero 30x mas que Dagger.

## Resolve First (primer acceso a un singleton)

| Patron | Mediana | vs Mejor |
|--------|---------|----------|
| Hybrid | 2.3 ns | == |
| Dagger-B | 8.1 ns | 3.5x |
| MM-D | 15.0 ns | 6.4x |
| MM-G | 20.0 ns | 8.6x |
| MM-E | 28.3 ns | 12.1x |
| MM-E2 | 32.7 ns | 14.0x |
| Dagger-C | 36.8 ns | 15.8x |
| Koin | 910.7 ns | 390.8x |

**Observacion:** Hybrid es el mas rapido en resolve (Dagger @Singleton cache: 2.3 ns). Koin paga lookup runtime (~911 ns). Todos los Dagger multi-modulo <33 ns.

## Lazy Init — Feature sin dependencias (Analytics)

Tiempo de anadir Analytics (solo depende de Core) a un grafo en ejecucion.

| Patron | Mediana | vs Mejor |
|--------|---------|----------|
| MM-G | 374.5 ns | == |
| MM-D | 382.3 ns | == |
| Dagger-B | 420.5 ns | 1.1x |
| Dagger-C | 517.7 ns | 1.4x |
| MM-E2 | 809.1 ns | 2.2x |
| MM-E | 2.9 us | 7.7x |
| Koin | 9.4 us | 25.1x |

**Observacion:** G y D son identicos (~375 ns). Un solo Component build. E paga topo-sort (~2.9 us). Koin paga loadModules (~9.4 us).

## Lazy Init — Cascada (Sync -> Auth + Storage + Encryption)

Tiempo de inicializacion en cascada: pedir Sync desencadena Auth -> Encryption, Storage -> Encryption.

| Patron | Mediana | vs Mejor |
|--------|---------|----------|
| MM-G | 1.1 us | == |
| MM-D | 1.1 us | == |
| MM-E2 | 2.7 us | 2.4x |
| Dagger-B | 3.4 us | 3.0x |
| Dagger-C | 3.5 us | 3.2x |
| MM-E | 9.1 us | 8.1x |
| Koin | 27.7 us | 24.7x |

**Observacion:** D y G resuelven la cascada en 1.1 us. E2 DFS (2.7 us) es mas rapido que B/C monoliticos (3.4 us) — provision interfaces son mas eficientes que CoreApis extendidas.

## Cross-Feature Op (Sync.sync())

Tiempo de una operacion real que cruza Auth + Storage + Encryption. Singletons resueltos una vez fuera del loop. Mide solo el trabajo, no el DI.

| Patron | Mediana | vs Mejor |
|--------|---------|----------|
| Hybrid | 101.3 us | == |
| Dagger-C | 106.4 us | 1.1x |
| Dagger-B | 107.8 us | 1.1x |
| MM-D | 158.4 us | 1.6x |
| MM-E | 161.3 us | 1.6x |
| MM-E2 | 164.5 us | 1.6x |
| MM-G | 166.3 us | 1.6x |
| Koin | 168.3 us | 1.7x |

**Observacion:** Con singletons resueltos, el framework DI no participa. La variacion (~101-168 us) es atribuible a thermal throttle y orden de ejecucion. Todos los approaches son equivalentes en operacion real.

---

## Resumen Multi-modulo

| Test | D | G | E2 | E |
|------|---|---|----|---|
| Init Cold | 1.8 us | 1.9 us | 9.5 us | 17.8 us |
| Resolve First | 15.0 ns | 20.0 ns | 32.7 ns | 28.3 ns |
| Lazy Init (0 deps) | 382 ns | 375 ns | 809 ns | 2.9 us |
| Lazy Init (cascade) | 1.1 us | 1.1 us | 2.7 us | 9.1 us |
| Cross-Feature Op | 158 us | 166 us | 165 us | 161 us |

**D y G son identicos en rendimiento.** G ofrece mejor encapsulacion (DaggerXxxComponent queda `internal`).
**E2 es 2-5x mas lento que D/G** en init — paga install+DFS. Pero escala a 50+ features sin editar el facade.
**E es el mas lento** — topo-sort eager es costoso vs DFS lazy.

---

## Conclusion

Todos los approaches Dagger estan en el rango de microsegundos. La diferencia maxima entre el mejor (D: 1.8 us) y el peor Dagger (E: 17.8 us) en init cold es 16 us — 0.001 frames. **Imperceptible para el usuario.** La eleccion entre approaches es de arquitectura y mantenibilidad, no de rendimiento.
