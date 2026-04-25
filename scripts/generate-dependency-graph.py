#!/usr/bin/env python3
"""
Dependency-graph generator for FeatureProvider modules.

Scans every `features/feature-*-impl/src/main/kotlin/**/*Provider.kt`,
extracts:

  - the provider class name (e.g. `AuthProvider`)
  - its `flavor` (DAGGER / PURE / KI / KOIN / SWEET_SPI / ...)
  - its declared `services: Set<Class<*>>`
  - every `resolver.get(SomeApi::class.java)` call inside `build()`

Emits two artefacts under `docs/generated/`:

  - `dependency-graph.md`   — human-readable, intended for Confluence
  - `dependency-graph.dot`  — machine-readable, render with Graphviz

The script intentionally uses regex over Kotlin sources rather than a
full AST parser. The pattern of FeatureProvider implementations in this
project is constrained enough that regex is reliable; the reward is
zero build dependencies and instant execution.

Run:
    python3 scripts/generate-dependency-graph.py

CI usage:
    Run the script and `git diff --exit-code docs/generated/` to fail
    when a provider added a new `resolver.get(...)` without updating
    the committed graph.
"""

from __future__ import annotations

import re
import sys
from collections import defaultdict
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
FEATURES_ROOT = PROJECT_ROOT / "features"
OUTPUT_DIR = PROJECT_ROOT / "docs" / "generated"

# Regex patterns ------------------------------------------------------------

CLASS_RE = re.compile(
    r"class\s+(\w*Provider)\s*(?:\([^)]*\))?\s*:\s*FeatureProvider\s*\(\s*\)",
    re.MULTILINE,
)
FLAVOR_RE = re.compile(r"override\s+val\s+flavor\s*[:=]\s*(?:Flavor\.)?(\w+)")
# services may be on a single line with setOf(...) or split across lines.
SERVICES_RE = re.compile(
    r"override\s+val\s+services[^=]*=\s*setOf\(\s*([^)]*)\s*\)",
    re.DOTALL,
)
RESOLVER_GET_RE = re.compile(
    r"resolver\.get\s*\(\s*([\w.]+)::class\.java\s*\)"
)
PERSISTENT_RE = re.compile(r"override\s+val\s+persistent\s*[:=][^=]*=\s*true")


# Helpers -------------------------------------------------------------------


def feature_module_of(path: Path) -> str:
    """Return the Gradle path of the feature-impl module containing `path`."""
    rel = path.relative_to(PROJECT_ROOT)
    parts = rel.parts
    # parts == ("features", "feature-X-impl", "src", "main", ...)
    if len(parts) >= 2 and parts[0] == "features":
        return f":{parts[0]}:{parts[1]}"
    return "<unknown>"


def parse_class_token(token: str) -> str:
    """Strip a `Foo::class.java` token down to `Foo`."""
    return token.strip().rstrip(",").split(".")[-1]


# Main ----------------------------------------------------------------------


def scan() -> dict[str, list[dict]]:
    """Group providers by their feature-impl Gradle path."""
    grouped: dict[str, list[dict]] = defaultdict(list)
    for kt in FEATURES_ROOT.rglob("*Provider.kt"):
        if "/build/" in kt.as_posix():
            continue
        text = kt.read_text(encoding="utf-8")

        m_class = CLASS_RE.search(text)
        if not m_class:
            continue
        class_name = m_class.group(1)

        m_flavor = FLAVOR_RE.search(text)
        flavor = m_flavor.group(1) if m_flavor else "UNKNOWN"

        m_services = SERVICES_RE.search(text)
        services: list[str] = []
        if m_services:
            for tok in m_services.group(1).split(","):
                tok = tok.strip()
                if not tok:
                    continue
                # `Foo::class.java` -> `Foo`
                cleaned = re.sub(r"::class\.java", "", tok)
                services.append(parse_class_token(cleaned))

        # Only count resolver.get inside build() — we approximate by
        # restricting to text after the first `override fun build(`.
        deps: list[str] = []
        build_idx = text.find("override fun build(")
        if build_idx >= 0:
            for m in RESOLVER_GET_RE.finditer(text, build_idx):
                deps.append(parse_class_token(m.group(1)))

        persistent = bool(PERSISTENT_RE.search(text))

        grouped[feature_module_of(kt)].append({
            "class": class_name,
            "flavor": flavor,
            "services": services,
            "deps": deps,
            "persistent": persistent,
            "file": str(kt.relative_to(PROJECT_ROOT)).replace("\\", "/"),
        })
    return grouped


def emit_markdown(grouped: dict[str, list[dict]]) -> str:
    lines = [
        "# SDK Feature Dependency Graph",
        "",
        "> Auto-generated from `features/*/src/main/kotlin/**/*Provider.kt`.",
        "> Do not edit by hand. Regenerate with",
        "> `python3 scripts/generate-dependency-graph.py`.",
        "",
        "Each provider lists the API services it publishes and the",
        "services it requests from the `Resolver` while building.",
        "",
    ]
    for module in sorted(grouped):
        lines.append(f"## `{module}`")
        lines.append("")
        for p in sorted(grouped[module], key=lambda x: x["class"]):
            persistent = " · **persistent**" if p["persistent"] else ""
            lines.append(f"### `{p['class']}` ({p['flavor']}){persistent}")
            lines.append("")
            lines.append(f"`{p['file']}`")
            lines.append("")
            services = ", ".join(f"`{s}`" for s in p["services"]) or "_(none declared)_"
            lines.append(f"- **publishes:** {services}")
            if p["deps"]:
                deps = ", ".join(f"`{d}`" for d in p["deps"])
                lines.append(f"- **depends on:** {deps}")
            else:
                lines.append("- **depends on:** _(no resolver.get() calls — root)_")
            lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def emit_dot(grouped: dict[str, list[dict]]) -> str:
    lines = [
        "// Auto-generated. Render with: dot -Tpng dependency-graph.dot -o graph.png",
        "digraph SdkFeatureGraph {",
        "  rankdir=LR;",
        "  node [shape=box, fontname=\"Helvetica\"];",
        "",
    ]
    # Each feature has multiple flavor variants (DAGGER/PURE/KI/...) that
    # produce the same edges. Deduplicate so the DOT is readable.
    edges: set[tuple[str, str]] = set()
    for providers in grouped.values():
        for p in providers:
            for service in p["services"]:
                for dep in p["deps"]:
                    if service != dep:  # skip self-loops (synthetic-like)
                        edges.add((service, dep))
    for service, dep in sorted(edges):
        lines.append(f"  \"{service}\" -> \"{dep}\";")
    lines.append("}")
    return "\n".join(lines) + "\n"


def main() -> int:
    grouped = scan()
    if not grouped:
        print("No FeatureProvider classes found.", file=sys.stderr)
        return 1

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    md_path = OUTPUT_DIR / "dependency-graph.md"
    dot_path = OUTPUT_DIR / "dependency-graph.dot"
    md_path.write_text(emit_markdown(grouped), encoding="utf-8")
    dot_path.write_text(emit_dot(grouped), encoding="utf-8")

    total_providers = sum(len(v) for v in grouped.values())
    total_modules = len(grouped)
    print(
        f"Generated {md_path.relative_to(PROJECT_ROOT)} and "
        f"{dot_path.relative_to(PROJECT_ROOT)} "
        f"({total_providers} provider(s) across {total_modules} module(s))."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
