#!/usr/bin/env python3
"""
Benchmark Summary Generator for di-patterns-demo.

Reads Jetpack Benchmark JSON output and produces a human-readable
comparison table grouped by operation type.

Usage:
    python scripts/benchmark-summary.py [path-to-benchmarkData.json]

If no path given, searches the default output directory.
"""
import json
import sys
import os
import glob
from collections import defaultdict

def find_benchmark_json():
    """Find the most recent benchmarkData.json in the default output dir."""
    base = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "benchmark", "build", "outputs",
        "connected_android_test_additional_output",
        "releaseAndroidTest", "connected",
    )
    pattern = os.path.join(base, "**", "*benchmarkData.json")
    files = glob.glob(pattern, recursive=True)
    if not files:
        print(f"ERROR: No benchmarkData.json found in {base}")
        sys.exit(1)
    return max(files, key=os.path.getmtime)


def format_time(ns):
    """Format nanoseconds to human-readable string."""
    if ns >= 1_000_000:
        return f"{ns / 1_000_000:.1f} ms"
    elif ns >= 1_000:
        return f"{ns / 1_000:.1f} us"
    else:
        return f"{ns:.1f} ns"


def classify_benchmark(name):
    """Extract (operation, pattern) from benchmark name."""
    name = name.replace("EMULATOR_", "")

    # Multi-module patterns (suffix order: _E2 before _E to avoid partial match)
    for suffix, label in [("_E2", "MM-E2"), ("_D", "MM-D"), ("_G", "MM-G"),
                          ("_H", "MM-H"), ("_I", "MM-I"), ("_J", "MM-J"),
                          ("_K", "MM-K")]:
        if name.endswith(suffix):
            op = name[: -len(suffix)]
            return op, label

    # Scale benchmarks (ScaleBenchmark class — no per-pattern suffix)
    for prefix in ["resolver_", "registry_"]:
        if name.startswith(prefix):
            engine = "Resolver (H/I/J)" if prefix == "resolver_" else "Registry (E2)"
            return name, engine

    # Monolithic patterns
    pattern_map = {
        "daggerA": "Dagger-A",
        "daggerB": "Dagger-B",
        "daggerC": "Dagger-C",
        "daggerD": "Dagger-D",
        "daggerE2": "Dagger-E2",
        "daggerE": "Dagger-E",
        "daggerF": "Dagger-F",
        "koin": "Koin",
        "hybrid": "Hybrid",
    }

    for key, label in pattern_map.items():
        if key in name:
            op = name
            for k in pattern_map:
                op = op.replace(f"_{k}", "").replace(k, "")
            op = op.strip("_")
            return op, label

    return name, "Unknown"


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else find_benchmark_json()
    print(f"Reading: {path}\n")

    with open(path) as f:
        data = json.load(f)

    benchmarks = data.get("benchmarks", [])
    print(f"Total benchmarks: {len(benchmarks)}\n")

    # Group by operation
    ops = defaultdict(dict)
    for b in benchmarks:
        name = b.get("name", "")
        median = b.get("metrics", {}).get("timeNs", {}).get("median")
        if median is None:
            continue
        op, pattern = classify_benchmark(name)
        ops[op][pattern] = median

    # Define display order for operations
    op_groups = {
        "Init Cold (full graph)": ["initCold", "initCold_monolithic", "baseline__initCold"],
        "Resolve First": ["resolveFirst", "resolveFirst_viaBridge"],
        "Resolve Cached": ["resolveCached_viaBridge", "resolveCached_singleService", "resolveAll_direct", "resolveAll_viaRegistry", "resolveAll_viaAutoRegistry"],
        "Lazy Init - No Deps (Analytics)": ["lazyInit_noDeps", "lazyInit_noDeps_noDeps"],
        "Lazy Init - Cascade (Sync)": ["lazyInit_cascade", "lazyInit_cascade_cascade"],
        "Cross-Feature Op (Sync)": ["crossFeatureOp", "crossFeatureOp_sync", "baseline__crossFeatureOp"],
        "Stress: Init/Shutdown Cycle": ["stress_initShutdown"],
        "Stress: Concurrent Resolution": ["stress_concurrent"],
        "Stress: Resolve All (cached)": ["stress_resolveAll"],
        "Stress: Selective Init": ["stress_selective"],
        "Stress: Re-Init": ["stress_reInit"],
        "Stress: Incremental Build": ["stress_incremental"],
        "E2E App Startup": ["e2eStartup"],
    }

    # Scale benchmarks get their own section (different patterns: engine, not SDK variant)
    scale_groups = {
        "Scale: Linear Graph": ["linear"],
        "Scale: Tree Graph": ["tree"],
        "Scale: Diamond Graph": ["diamond"],
        "Scale: Full Graph (tree)": ["fullGraph_tree"],
        "Scale: Selective (tree)": ["selective_tree"],
    }

    # Collect all patterns seen
    all_patterns = set()
    for patterns in ops.values():
        all_patterns.update(patterns.keys())

    # Order patterns: monolithic first, then multi-module, then scale engines
    mono_order = ["Dagger-A", "Dagger-B", "Dagger-C", "Dagger-D", "Dagger-E", "Dagger-E2", "Dagger-F", "Koin", "Hybrid"]
    mm_order = ["MM-D", "MM-E", "MM-E2", "MM-G", "MM-H", "MM-I", "MM-J", "MM-K"]
    scale_order = ["Resolver (H/I/J)", "Registry (E2)"]
    pattern_order = [p for p in mono_order + mm_order + scale_order if p in all_patterns]

    print("=" * 100)
    print("BENCHMARK RESULTS SUMMARY")
    print("=" * 100)

    for group_name, op_keys in op_groups.items():
        # Find matching operations
        matching = {}
        for op, patterns in ops.items():
            for key in op_keys:
                if key in op:
                    matching[op] = patterns
                    break

        if not matching:
            continue

        print(f"\n{'-' * 100}")
        print(f"  {group_name}")
        print(f"{'-' * 100}")

        # Merge all patterns from matching ops
        merged = {}
        for patterns in matching.values():
            merged.update(patterns)

        # Print table
        results = [(p, merged[p]) for p in pattern_order if p in merged]
        if not results:
            continue

        # Find best and worst
        times = [t for _, t in results]
        best = min(times)
        worst = max(times)

        col_w = 14
        header = f"  {'Pattern':<{col_w}} {'Median':>12}  {'vs Best':>10}  "
        print(header)
        print(f"  {'-' * (col_w + 28)}")

        for pattern, time in sorted(results, key=lambda x: x[1]):
            ratio = time / best if best > 0 else 0
            marker = " << BEST" if time == best else (" << WORST" if time == worst else "")
            ratio_str = f"{ratio:.1f}x" if ratio > 1.05 else "  =="
            print(f"  {pattern:<{col_w}} {format_time(time):>12}  {ratio_str:>10}{marker}")

    # Scale benchmarks section (Resolver vs Registry at different sizes)
    scale_ops = {op: patterns for op, patterns in ops.items()
                 if any(op.startswith(p) for p in ["resolver_", "registry_"])}
    if scale_ops:
        print(f"\n{'=' * 100}")
        print("SCALE BENCHMARKS (synthetic features)")
        print("=" * 100)

        for group_name, keywords in scale_groups.items():
            matching = {op: patterns for op, patterns in scale_ops.items()
                        if any(k in op for k in keywords)}
            if not matching:
                continue

            print(f"\n{'-' * 100}")
            print(f"  {group_name}")
            print(f"{'-' * 100}")

            col_w = 40
            header = f"  {'Test':<{col_w}} {'Engine':<20} {'Median':>12}"
            print(header)
            print(f"  {'-' * (col_w + 36)}")

            for op in sorted(matching.keys()):
                for engine, time in sorted(matching[op].items()):
                    print(f"  {op:<{col_w}} {engine:<20} {format_time(time):>12}")

    # Print unmatched operations
    matched_ops = set()
    for op_keys in op_groups.values():
        for op in ops:
            for key in op_keys:
                if key in op:
                    matched_ops.add(op)
    matched_ops.update(scale_ops.keys())

    unmatched = {op: patterns for op, patterns in ops.items() if op not in matched_ops}
    if unmatched:
        print(f"\n{'-' * 100}")
        print(f"  Other Tests")
        print(f"{'-' * 100}")
        for op, patterns in sorted(unmatched.items()):
            for pattern, time in sorted(patterns.items(), key=lambda x: x[1]):
                print(f"  {op} [{pattern}]: {format_time(time)}")

    # Conclusions
    print(f"\n{'=' * 100}")
    print("CONCLUSIONS")
    print("=" * 100)

    # Find multi-module results
    mm_init = {p: t for op, patterns in ops.items() for p, t in patterns.items()
               if "initCold" in op and p.startswith("MM-")}
    mm_resolve = {p: t for op, patterns in ops.items() for p, t in patterns.items()
                  if "resolveFirst" in op and p.startswith("MM-")}

    if mm_init:
        print("\nMulti-module init cold ranking:")
        for p, t in sorted(mm_init.items(), key=lambda x: x[1]):
            print(f"  {p}: {format_time(t)}")

    if mm_resolve:
        print("\nMulti-module resolve first ranking:")
        for p, t in sorted(mm_resolve.items(), key=lambda x: x[1]):
            print(f"  {p}: {format_time(t)}")

    # Key insights
    mono_d_init = next((t for op, patterns in ops.items() for p, t in patterns.items()
                        if "initCold" in op and p == "Dagger-D"), None)
    mm_d_init = mm_init.get("MM-D")
    mm_g_init = mm_init.get("MM-G")

    if mono_d_init and mm_d_init:
        print(f"\nMulti-module overhead vs monolithic D:")
        print(f"  MM-D: {mm_d_init/mono_d_init:.1f}x monolithic D ({format_time(mm_d_init)} vs {format_time(mono_d_init)})")
    if mm_g_init and mm_d_init:
        print(f"\nG vs D (multi-module):")
        ratio = mm_g_init / mm_d_init
        if ratio < 1.05:
            print(f"  Identical performance ({format_time(mm_g_init)} vs {format_time(mm_d_init)})")
        else:
            print(f"  G is {ratio:.1f}x D ({format_time(mm_g_init)} vs {format_time(mm_d_init)})")
        print(f"  G advantage: DaggerXxxComponent stays internal (better encapsulation)")

    mm_h_init = mm_init.get("MM-H")
    if mm_h_init and mm_g_init:
        print(f"\nH vs G (multi-module):")
        ratio = mm_h_init / mm_g_init
        print(f"  H is {ratio:.1f}x G ({format_time(mm_h_init)} vs {format_time(mm_g_init)})")
        print(f"  H advantage: zero central editing (each feature self-registers)")

    # I vs H (Pure Resolver — zero DI framework)
    mm_i_init = mm_init.get("MM-I")
    if mm_i_init and mm_h_init:
        print(f"\nI vs H (multi-module):")
        ratio = mm_i_init / mm_h_init
        if ratio < 1.05 and ratio > 0.95:
            print(f"  Identical performance ({format_time(mm_i_init)} vs {format_time(mm_h_init)})")
        elif ratio < 1:
            print(f"  I is {1/ratio:.1f}x FASTER than H ({format_time(mm_i_init)} vs {format_time(mm_h_init)})")
        else:
            print(f"  I is {ratio:.1f}x H ({format_time(mm_i_init)} vs {format_time(mm_h_init)})")
        print(f"  I advantage: zero DI framework dependency (no Dagger, no KSP)")

    # J vs H (kotlin-inject)
    mm_j_init = mm_init.get("MM-J")
    if mm_j_init and mm_h_init:
        print(f"\nJ vs H (multi-module):")
        ratio = mm_j_init / mm_h_init
        if ratio < 1.05 and ratio > 0.95:
            print(f"  Identical performance ({format_time(mm_j_init)} vs {format_time(mm_h_init)})")
        elif ratio < 1:
            print(f"  J is {1/ratio:.1f}x FASTER than H ({format_time(mm_j_init)} vs {format_time(mm_h_init)})")
        else:
            print(f"  J is {ratio:.1f}x H ({format_time(mm_j_init)} vs {format_time(mm_h_init)})")
        print(f"  J advantage: modern KSP tooling, less boilerplate than Dagger")

    print()


if __name__ == "__main__":
    main()
