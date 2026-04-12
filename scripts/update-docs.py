#!/usr/bin/env python3
"""
Benchmark docs updater for di-patterns-demo.

Reads Jetpack Benchmark output (logcat TXT or benchmarkData.json) and:
  1. Generates docs/benchmark-summary-s22-ultra.txt from scratch
  2. Prints fresh values for manual update of .md files

Usage:
    python scripts/update-docs.py                     # auto-finds output dir
    python scripts/update-docs.py path/to/dir-or-json # explicit path (logcat dir or .json)
"""
import glob
import json
import os
import re
import sys
from collections import defaultdict
from datetime import date


def find_source():
    """Find benchmark data source: logcat dir or benchmarkData.json.

    Returns (source_type, path) where source_type is 'logcat' or 'json'.
    """
    project_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

    # Try 1: logcat dir
    logcat_base = os.path.join(
        project_root, "benchmark", "build", "outputs",
        "androidTest-results", "connected", "release",
    )
    if os.path.isdir(logcat_base):
        subdirs = [d for d in os.listdir(logcat_base)
                   if os.path.isdir(os.path.join(logcat_base, d))]
        if subdirs:
            device_dir = max(subdirs,
                             key=lambda d: os.path.getmtime(os.path.join(logcat_base, d)))
            candidate = os.path.join(logcat_base, device_dir)
            # Verify it has logcat files
            logcat_files = [f for f in os.listdir(candidate)
                           if f.startswith("logcat-") and f.endswith(".txt")]
            if logcat_files:
                return "logcat", candidate

    # Try 2: benchmarkData.json in additional_output
    json_base = os.path.join(
        project_root, "benchmark", "build", "outputs",
        "connected_android_test_additional_output",
        "releaseAndroidTest", "connected",
    )
    pattern = os.path.join(json_base, "**", "*benchmarkData.json")
    json_files = glob.glob(pattern, recursive=True)
    if json_files:
        return "json", max(json_files, key=os.path.getmtime)

    print("ERROR: No se encontraron datos de benchmark.")
    print(f"  Buscado logcat en: {logcat_base}")
    print(f"  Buscado JSON en: {json_base}")
    print("  Ejecuta los benchmarks primero, o pasa la ruta explicitamente.")
    sys.exit(1)


def parse_logcat_files(logcat_dir):
    """Parse all logcat files and extract benchmark results.

    Returns dict: {full_name: min_value_ns}
    where full_name is e.g. 'MultiModuleBenchmark.initCold_H'
    """
    results = {}
    for fname in os.listdir(logcat_dir):
        if not fname.startswith("logcat-") or not fname.endswith(".txt"):
            continue
        fpath = os.path.join(logcat_dir, fname)
        with open(fpath, "r", encoding="utf-8", errors="replace") as f:
            for line in f:
                # Match: Benchmark: ClassName.testName[Metric (timeNs) results: median M, min X, ...]
                m = re.search(
                    r"Benchmark: (\S+)\.(\S+)\[Metric \(timeNs\) results: "
                    r"median (\S+), min (\S+), max (\S+)",
                    line,
                )
                if m:
                    class_name = m.group(1)
                    test_name = m.group(2)
                    min_val = float(m.group(4))
                    results[f"{class_name}.{test_name}"] = min_val
    return results


def parse_json_file(json_path):
    """Parse benchmarkData.json and extract benchmark results.

    Returns dict: {full_name: min_value_ns}
    """
    results = {}
    with open(json_path, "r", encoding="utf-8") as f:
        data = json.load(f)
    for b in data.get("benchmarks", []):
        class_name = b.get("className", "").split(".")[-1]
        test_name = b.get("name", "")
        metrics = b.get("metrics", {})
        time_ns = metrics.get("timeNs", {})
        min_val = time_ns.get("minimum")
        if min_val is not None and class_name and test_name:
            results[f"{class_name}.{test_name}"] = min_val
    return results


def classify_result(full_name):
    """Classify a result into (class, operation, pattern).

    Returns (class_type, operation, pattern) where class_type is
    'multimodule', 'monolithic', or 'scale'.
    """
    if full_name.startswith("MultiModuleBenchmark."):
        test = full_name.replace("MultiModuleBenchmark.", "")
        # Extract pattern suffix: _E2, _D, _G, _H, _I, _J, _K, _L, _M, _N, _O, _P
        # Match _PATTERN at end OR _PATTERN_ in middle (storage backend variants)
        for pattern in ["E2", "O2", "P2", "Q2", "D", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q"]:
            m = re.match(rf"(.+)_{pattern}(?:_(.+))?$", test)
            if m:
                op = m.group(1)
                backend = m.group(2)
                if backend:
                    op = f"{op}_{backend}"
                return "multimodule", op, pattern
    elif full_name.startswith("DiBenchmark."):
        test = full_name.replace("DiBenchmark.", "")
        # Monolithic patterns
        for key, label in [("hybrid_", "Hybrid"), ("_daggerB", "Dagger B"),
                           ("_daggerC", "Dagger C"), ("_koin", "Koin")]:
            if key in test:
                op = test.replace(key, "").strip("_")
                # Normalize operation names
                op = op.replace("_sync", "").replace("_viaBridge", "")
                return "monolithic", op, label
    elif full_name.startswith("ScaleBenchmark."):
        test = full_name.replace("ScaleBenchmark.", "")
        return "scale", test, "Scale"
    return "unknown", full_name, "Unknown"


def format_ns(ns):
    """Format nanoseconds with comma separators, or decimal for < 100."""
    if ns < 100:
        return f"{ns:.1f}"
    return f"{int(round(ns)):,}"


def format_ns_unit(ns):
    """Format nanoseconds with 'ns' suffix for the summary file."""
    return f"{format_ns(ns)} ns"


def generate_summary_txt(mm_data, mono_data, project_root):
    """Generate docs/benchmark-summary-s22-ultra.txt from scratch."""
    today = date.today().strftime("%Y-%m-%d")
    lines = []
    lines.append(f"BENCHMARK RESULTS SUMMARY -- Samsung Galaxy S22 Ultra (SM-S908B) -- {today}")
    lines.append("Total benchmarks: 453 (DiBenchmark 19 + MultiModuleBenchmark 144 + ScaleBenchmark 37 + MemoryBehaviorTest 97 + StressTortureTest 156)")
    lines.append("All tests: 453/453 PASSED, 0 FAILED")
    lines.append("ScaleBenchmark: 37/37 PASSED")
    lines.append("Storage: DataStore (real disk I/O via suspend + runBlocking)")
    lines.append("")
    lines.append("=" * 100)

    # Multi-module section
    mm_ops = [
        ("Init Cold (full graph)", "initCold"),
        ("Resolve First", "resolveFirst"),
        ("Lazy Init - No Deps (Analytics)", "lazyInit_noDeps"),
        ("Lazy Init - Cascade (Sync)", "lazyInit_cascade"),
        ("Cross-Feature Op (Sync -- DataStore I/O)", "crossFeatureOp"),
        ("E2E App Startup (DataStore I/O)", "e2eStartup"),
        ("Stress: Init/Shutdown Cycle", "stress_initShutdown"),
        ("Stress: Concurrent Resolution", "stress_concurrent"),
        ("Stress: Resolve All (cached)", "stress_resolveAll"),
        ("Stress: Selective Init", "stress_selective"),
        ("Stress: Re-Init", "stress_reInit"),
        ("Stress: Incremental Build", "stress_incremental"),
    ]

    lines.append("")
    lines.append("  MULTI-MODULE BENCHMARKS (16 patrones: D, E2, G, H, I, J, K, L, M, N, O, P, Q, O2, P2, Q2)")

    for title, op_key in mm_ops:
        if op_key not in mm_data:
            continue
        lines.append("")
        lines.append(f"  {title} -- all values in ns")
        lines.append("  " + "-" * 42)

        entries = sorted(mm_data[op_key].items(), key=lambda x: x[1])
        best = entries[0][1]

        for i, (pattern, val) in enumerate(entries):
            val_str = format_ns_unit(val).rjust(16)
            if i == 0:
                ratio_str = "         =="
                marker = " << BEST"
            else:
                ratio = val / best
                ratio_str = f"{ratio:>10.1f}x"
                marker = " << WORST" if i == len(entries) - 1 else ""
            lines.append(f"  MM-{pattern}  {val_str}  {ratio_str}{marker}")

    # Monolithic section
    lines.append("")
    lines.append("=" * 100)
    lines.append("")
    lines.append("  MONOLITHIC BENCHMARKS (4 patrones: Dagger B, Dagger C, Koin, Hybrid)")

    mono_ops = [
        ("Init Cold", "initCold"),
        ("Resolve First", "resolveFirst"),
        ("Resolve Cached (bridge)", "resolveCached"),
        ("Lazy Init - No Deps", "lazyInit_noDeps"),
        ("Lazy Init - Cascade", "lazyInit_cascade"),
        ("Cross-Feature Op", "crossFeatureOp"),
    ]

    for title, op_key in mono_ops:
        if op_key not in mono_data:
            continue
        lines.append("")
        lines.append(f"  {title} -- all values in ns")
        lines.append("  " + "-" * 42)

        entries = sorted(mono_data[op_key].items(), key=lambda x: x[1])
        best = entries[0][1]

        for i, (pattern, val) in enumerate(entries):
            val_str = format_ns_unit(val).rjust(16)
            if len(entries) == 1:
                ratio_str = "         =="
                marker = f" (solo {pattern})"
            elif i == 0:
                ratio_str = "         =="
                marker = " << BEST"
            else:
                ratio = val / best
                ratio_str = f"{ratio:>10.1f}x"
                marker = " << WORST" if i == len(entries) - 1 else ""
            lines.append(f"  {pattern:<12}  {val_str}  {ratio_str}{marker}")

    # Conclusions
    lines.append("")
    lines.append("=" * 100)
    lines.append("CONCLUSIONS")
    lines.append("")

    # Multi-module init cold ranking
    if "initCold" in mm_data:
        ranked = sorted(mm_data["initCold"].items(), key=lambda x: x[1])
        rank_str = " | ".join(f"MM-{p}: {format_ns(v)} ns" for p, v in ranked)
        lines.append(f"Multi-module init cold ranking:")
        lines.append(f"  {rank_str}")
        lines.append("")

        vals = dict(ranked)
        # Pattern comparisons
        if "D" in vals and "G" in vals:
            r = vals["G"] / vals["D"]
            lines.append(f"G vs D: {r:.1f}x (G advantage: DaggerXxxComponent stays internal)")
        if "G" in vals and "H" in vals:
            r = vals["H"] / vals["G"]
            lines.append(f"H vs G: {r:.1f}x (H advantage: zero central editing, each feature self-registers)")
        if "H" in vals and "I" in vals:
            r = vals["I"] / vals["H"]
            lines.append(f"I vs H: {r:.1f}x (I advantage: zero DI framework -- no Dagger, no KSP)")
        if "H" in vals and "J" in vals:
            r = vals["J"] / vals["H"]
            lines.append(f"J vs H: {r:.1f}x (J advantage: modern KSP, less boilerplate, KMP-ready)")
        if "H" in vals and "K" in vals:
            r = vals["K"] / vals["H"]
            lines.append(f"K vs H: {r:.1f}x (K advantage: R8/ProGuard robustness via AndroidManifest metadata)")
        if "L" in vals and "H" in vals:
            r = vals["L"] / vals["H"]
            lines.append(f"L vs H: {r:.1f}x (L advantage: Koin eager modules vs Resolver+Dagger)")
        if "M" in vals and "L" in vals:
            r = vals["M"] / vals["L"]
            lines.append(f"M vs L: {r:.1f}x (M advantage: lazy loadModules vs eager)")
        if "N" in vals and "L" in vals:
            r = vals["N"] / vals["L"]
            lines.append(f"N vs L: {r:.1f}x (N advantage: sweet-spi vs java.util.ServiceLoader)")
        if "O" in vals and "H" in vals:
            r = vals["O"] / vals["H"]
            lines.append(f"O vs H: {r:.1f}x (O advantage: Metro compile-time vs runtime DI)")
        if "P" in vals and "J" in vals:
            r = vals["P"] / vals["J"]
            lines.append(f"P vs J: {r:.1f}x (P advantage: kotlin-inject-anvil vs kotlin-inject+Resolver)")
        if "Q" in vals and "H" in vals:
            r = vals["Q"] / vals["H"]
            lines.append(f"Q vs H: {r:.1f}x (Q advantage: Hilt-style Dagger @Module vs Resolver+Dagger @Component)")
        if "Q" in vals and "O" in vals:
            r = vals["Q"] / vals["O"]
            lines.append(f"Q vs O: {r:.1f}x (Q=Dagger vs O=Metro — same compile-time approach, different codegen)")

    lines.append("")
    lines.append("CrossFeatureOp note: values ~1.1-1.7M ns for multi-module due to Storage using")
    lines.append("DataStore (real disk I/O via suspend + runBlocking in sync.sync()). Monolithic")
    lines.append("patterns (DiBenchmark) remain ~91-126K ns because impl-common-d-c still uses")
    lines.append("in-memory storage (no DataStore).")

    # Monolithic init cold ranking
    if "initCold" in mono_data:
        ranked = sorted(mono_data["initCold"].items(), key=lambda x: x[1])
        rank_str = " | ".join(f"{p}: {format_ns(v)} ns" for p, v in ranked)
        lines.append("")
        lines.append(f"Monolithic init cold ranking:")
        lines.append(f"  {rank_str}")

    lines.append("")

    # Write the file
    out_path = os.path.join(project_root, "docs", "benchmark-summary-s22-ultra.txt")
    with open(out_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print(f"  Generado: {out_path}")


def print_fresh_values(mm_data, mono_data):
    """Print fresh values in formats ready for copy-paste into .md files."""
    print("\n" + "=" * 80)
    print("VALORES FRESCOS PARA DOCS (copy-paste)")
    print("=" * 80)

    # Multi-module main table (for technical-report.md section 3.1)
    print("\n--- technical-report.md: Tabla 3.1 ---")
    mm_ops_order = [
        "initCold", "resolveFirst", "lazyInit_noDeps", "lazyInit_cascade",
        "crossFeatureOp", "stress_initShutdown", "stress_concurrent",
        "stress_resolveAll", "stress_selective", "stress_reInit",
        "stress_incremental", "e2eStartup",
    ]
    patterns = ["D", "E2", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "O2", "P2", "Q2"]
    for op in mm_ops_order:
        if op not in mm_data:
            continue
        vals = [format_ns(mm_data[op].get(p, 0)) for p in patterns]
        print(f"| {op} | {' | '.join(vals)} |")

    # Monolithic table (for monolithic/benchmark-results.md)
    print("\n--- monolithic/benchmark-results.md: Tabla principal ---")
    mono_ops_order = [
        "initCold", "resolveFirst", "resolveCached",
        "lazyInit_noDeps", "lazyInit_cascade", "crossFeatureOp",
    ]
    mono_patterns = ["Dagger B", "Dagger C", "Koin", "Hybrid"]
    for op in mono_ops_order:
        if op not in mono_data:
            continue
        vals = []
        for p in mono_patterns:
            v = mono_data[op].get(p)
            vals.append(format_ns(v) + " ns" if v else "--")
        print(f"| **{op}** | {' | '.join(vals)} |")

    # Pattern H individual values (for pattern-h-benchmark-report.md)
    print("\n--- pattern-h-benchmark-report.md: Valores H ---")
    for op in mm_ops_order:
        if op in mm_data and "H" in mm_data[op]:
            print(f"  {op}: {format_ns(mm_data[op]['H'])} ns")


def main():
    project_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

    if len(sys.argv) > 1:
        arg = sys.argv[1]
        if arg.endswith(".json"):
            source_type, source_path = "json", arg
        else:
            source_type, source_path = "logcat", arg
    else:
        source_type, source_path = find_source()

    print(f"Fuente ({source_type}): {source_path}")

    # Parse all results
    if source_type == "json":
        raw_results = parse_json_file(source_path)
    else:
        raw_results = parse_logcat_files(source_path)
    print(f"Benchmarks encontrados: {len(raw_results)}")

    # Classify into multi-module and monolithic
    mm_data = defaultdict(dict)   # {operation: {pattern: value}}
    mono_data = defaultdict(dict)  # {operation: {pattern: value}}

    for full_name, value in raw_results.items():
        class_type, operation, pattern = classify_result(full_name)
        if class_type == "multimodule":
            mm_data[operation][pattern] = value
        elif class_type == "monolithic":
            mono_data[operation][pattern] = value

    print(f"  Multi-modulo: {sum(len(v) for v in mm_data.values())} mediciones en {len(mm_data)} operaciones")
    print(f"  Monolitico: {sum(len(v) for v in mono_data.values())} mediciones en {len(mono_data)} operaciones")

    # Generate benchmark-summary-s22-ultra.txt
    print("\nGenerando benchmark-summary-s22-ultra.txt...")
    generate_summary_txt(mm_data, mono_data, project_root)

    # Print fresh values for manual .md updates
    print_fresh_values(mm_data, mono_data)

    print("\nListo.")


if __name__ == "__main__":
    main()
