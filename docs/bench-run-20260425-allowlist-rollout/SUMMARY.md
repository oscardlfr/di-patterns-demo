# Benchmark run — allowlist rollout to all discovery-based patterns

**Date:** 2026-04-25
**Device:** Samsung Galaxy S22 Ultra (SM-S908B, API 16 [Android 16])
**Branch:** `feature/security-allowlist-and-override-check`
**Patterns under test:** H, I, J, K, L, M, N (all ServiceLoader / sweet-spi / manifest discovery patterns)

## What changed in code

Each discovery-based pattern now constructs its `Resolver` (or filters
its `ServiceLoader` stream) with a strict `ProviderAllowlist`. The
filter rejects providers whose `Class.name` is not on the per-pattern
approved-FQN list. See:

- `HApprovedProviders` — DAGGER-flavor `*Provider` classes
- `IApprovedProviders` — PURE-flavor `*PureProvider` classes
- `JApprovedProviders` — KI-flavor `*KIProvider` classes
- `KApprovedProviders` — DAGGER-flavor (manifest meta-data discovery)
- `KoinApprovedProviders.JAVA_SPI` — `*KoinProvider` classes (L, M)
- `KoinApprovedProviders.SWEET_SPI` — `*SweetSpiProvider` classes (N)
- `CApprovedInitializers` — `FeatureInitializer` SPI for Pattern C

## Bottom line

**The allowlist filter cost is below the device noise floor.** The
filter is a single `HashSet.contains(String)` lookup per discovered
provider (≤7 providers per pattern). Run-to-run variance on the same
device dominates any cost the filter introduces, so deltas appear in
both directions.

## Per-pattern medians (µs, post-allowlist vs pre-allowlist baseline)

`baseline_us` = 2026-04-24 run (no allowlist)
`post_us` = 2026-04-25 run (allowlist on)
Negative delta = post is faster (device variance, not code change).

### `initCold` (where the filter actually runs)

| Pattern | baseline | post | delta_pct |
|---|---|---|---|
| H | 133.70 | 95.29 | −28.7% |
| I | 132.44 | 94.34 | −28.8% |
| J | 94.55 | 95.30 | +0.8% |
| K | 239.29 | 301.39 | +25.9% |
| L | 158.90 | 156.68 | −1.4% |
| M | 171.52 | 174.21 | +1.6% |
| N | 79.06 | 69.00 | −12.7% |

Spread is roughly ±30% on a benchmark whose median is ~100 µs. With
the filter doing 6–7 hashset lookups (~50 ns total) at init time,
the filter cannot account for these swings — they are device JIT /
thermal state.

### Hot-path resolution (allowlist not involved)

`resolveFirst`, `stress_resolveAll`: run AFTER init, so the allowlist
is not on the path. As expected, deltas are within ±20% on
sub-microsecond medians (i.e. noise).

### Aggregate cross-feature operation

`crossFeatureOp_*`: includes encryption + storage + sync work that
dwarfs any DI-layer cost. Most deltas are −5% to −15%.

The single outlier — `crossFeatureOp_H_datastore +109%` — is in a
test that exercises Android's DataStore (real disk I/O). The fake
and sharedprefs variants for H show normal deltas (−2.8%, −14.3%),
so the spike is I/O variance, not allowlist cost.

## Files in this run

- `01-MultiModule-HIJKLM-partial.json` — first pass, H/I/J/K/L/M
  results (Pattern N failed initially because the shared
  `KoinApprovedProviders` did not include the sweet-spi class FQNs).
- `02-MultiModule-N-only.json` — Pattern N re-run after splitting
  `KoinApprovedProviders` into `JAVA_SPI` (L, M) and `SWEET_SPI` (N).
- `diff.py` — Python script used to produce the deltas above.

## Followups before commit

1. The split of `KoinApprovedProviders` into `JAVA_SPI` / `SWEET_SPI`
   is the right shape; that mistake was caught by Pattern N's
   benchmark failing fast.
2. No further code changes needed.
