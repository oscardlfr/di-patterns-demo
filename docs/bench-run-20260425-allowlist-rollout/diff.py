"""Diff median timeNs between baseline and post-allowlist benchmark runs.

Usage:
  python diff.py BASELINE.json [BASELINE2.json ...] -- POST.json [POST2.json ...]

Prints one row per benchmark name shared between sets:
  name | baseline_us | post_us | delta_us | delta_pct
"""
import json
import sys
from pathlib import Path


def load(paths):
    out = {}
    for p in paths:
        data = json.load(open(p))
        for b in data.get("benchmarks", []):
            name = b["name"]
            median_ns = b["metrics"]["timeNs"]["median"]
            out[name] = median_ns
    return out


def main():
    args = sys.argv[1:]
    if "--" not in args:
        print(__doc__)
        sys.exit(2)
    sep = args.index("--")
    baseline = load(args[:sep])
    post = load(args[sep + 1:])

    common = sorted(set(baseline) & set(post))
    print(f"{'benchmark':<48} {'baseline_us':>12} {'post_us':>12} {'delta_us':>10} {'delta_pct':>10}")
    print("-" * 96)
    for name in common:
        b = baseline[name] / 1000
        p = post[name] / 1000
        d = p - b
        pct = (d / b) * 100 if b else 0
        print(f"{name:<48} {b:>12.2f} {p:>12.2f} {d:>+10.2f} {pct:>+9.2f}%")

    only_baseline = set(baseline) - set(post)
    only_post = set(post) - set(baseline)
    if only_baseline:
        print(f"\n[only in baseline] {sorted(only_baseline)}")
    if only_post:
        print(f"[only in post]     {sorted(only_post)}")


if __name__ == "__main__":
    main()
