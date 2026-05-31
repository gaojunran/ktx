#!/usr/bin/env bash
# Benchmark ktx vs main-kts using hyperfine.
#
# Outputs benchmarks/results.json (read by docs/.vitepress/theme/benchmarks.data.ts).
# Run via `mise run bench` from the repo root.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KTX="$REPO_ROOT/modules/cli/build/install/ktx/bin/ktx"
RESULTS="$REPO_ROOT/benchmarks/results.json"
SCRATCH=$(mktemp -d)
trap 'rm -rf "$SCRATCH"' EXIT

if [ ! -x "$KTX" ]; then
  echo "ktx CLI not found at $KTX — run 'mise run build' first." >&2
  exit 1
fi

# Find a real `kotlin` (avoid ktx's launcher if a user has aliased it).
# Prefer mise's installed Kotlin distribution so we never resolve to the
# alias even when PATH order is unusual; fall back to `command -v` for
# environments without mise.
KOTLIN=""
for candidate in "$HOME"/.local/share/mise/installs/kotlin/*/kotlinc/bin/kotlin; do
  if [ -x "$candidate" ]; then KOTLIN="$candidate"; break; fi
done
if [ -z "$KOTLIN" ]; then
  # Resolve via PATH but reject anything that looks like a wrapper (script
  # under modules/cli/ — that's our own launcher).
  cand="$(command -v kotlin 2>/dev/null || true)"
  case "$cand" in
    */modules/cli/*) cand="" ;;
  esac
  KOTLIN="$cand"
fi
if [ -z "$KOTLIN" ]; then
  echo "kotlin CLI not found. Install via 'mise install kotlin' or your package manager." >&2
  exit 1
fi

echo "ktx:     $KTX"
echo "kotlin:  $KOTLIN"
echo

# Detect tool versions for the JSON header. Wrapped to survive set -e.
# ktx has no --version flag yet; use the closest git tag as a stable
# release id, or 'dev' on untagged commits / shallow clones.
{
  KTX_VER="$(cd "$REPO_ROOT" && git describe --tags --abbrev=0 2>/dev/null | sed 's/^v//')" || true
  KOTLIN_VER="$("$KOTLIN" -version 2>&1 | awk '/Kotlin version/ {print $3; exit}')" || true
  JAVA_VER="$(java -version 2>&1 | awk -F'"' '/version/ {print $2; exit}')" || true
}
KTX_VER="${KTX_VER:-dev}"
KOTLIN_VER="${KOTLIN_VER:-unknown}"
JAVA_VER="${JAVA_VER:-unknown}"
export KTX_VER KOTLIN_VER JAVA_VER

# Fixtures
HELLO="$REPO_ROOT/benchmarks/hello.main.kts"
WITH_DEPS="$REPO_ROOT/benchmarks/with-deps.main.kts"

# Warm everything once (sink warmup runs)
"$KTX" run "$HELLO" >/dev/null
"$KTX" run "$WITH_DEPS" >/dev/null
"$KOTLIN" "$HELLO" >/dev/null
"$KOTLIN" "$WITH_DEPS" >/dev/null
"$KTX" run --daemon "$HELLO" >/dev/null
"$KTX" run --daemon "$WITH_DEPS" >/dev/null

cd "$SCRATCH"

echo "=== scenario 1: warm + no deps"
hyperfine --warmup 3 --runs 8 --export-json s1.json \
  --command-name "ktx" "$KTX run $HELLO" \
  --command-name "ktx-daemon" "$KTX run --daemon $HELLO" \
  --command-name "main-kts" "$KOTLIN $HELLO" \
  >/dev/null

echo "=== scenario 2: warm + with deps"
hyperfine --warmup 3 --runs 8 --export-json s2.json \
  --command-name "ktx" "$KTX run $WITH_DEPS" \
  --command-name "ktx-daemon" "$KTX run --daemon $WITH_DEPS" \
  --command-name "main-kts" "$KOTLIN $WITH_DEPS" \
  >/dev/null

echo "=== scenario 3: dev loop (fresh source each run)"
GEN="$SCRATCH/gen.sh"
cat > "$GEN" <<EOF
#!/bin/bash
ts=\$(date +%s%N)
cat > $SCRATCH/loop.main.kts <<KOTLIN
data class Point(val x: Int, val y: Int) {
    fun magnitude() = Math.sqrt((x*x + y*y).toDouble())
}
val points = (1..50).map { Point(it, it * 2) }
val total = points.sumOf { it.magnitude() }
println("ts=\$ts total=\\\$total points=\\\${points.size}")
KOTLIN
EOF
chmod +x "$GEN"

hyperfine --warmup 2 --runs 6 --export-json s3.json \
  --prepare "$GEN" \
  --command-name "ktx" "$KTX run $SCRATCH/loop.main.kts" \
  --command-name "ktx-daemon" "$KTX run --daemon $SCRATCH/loop.main.kts" \
  --command-name "main-kts" "$KOTLIN $SCRATCH/loop.main.kts" \
  >/dev/null

echo "=== scenario 4: cold start"
# Each run wipes both the script-compile cache and the daemon socket so
# every measurement pays the full warm-up cost: fork JVM, load Kotlin
# embedded compiler, parse / type-check / codegen the script.
hyperfine --runs 4 --export-json s4.json \
  --prepare "rm -rf $HOME/.cache/ktx/compiled $HOME/.cache/main.kts.compiled.cache; $KTX daemon stop --all 2>/dev/null || true" \
  --command-name "ktx" "$KTX run $HELLO" \
  --command-name "ktx-daemon" "$KTX run --daemon $HELLO" \
  --command-name "main-kts" "$KOTLIN $HELLO" \
  >/dev/null

# Aggregate JSON. We pull mean (in seconds, hyperfine units) and convert to ms.
python3 - "$SCRATCH" "$RESULTS" <<'PY'
import json, sys, datetime, pathlib, os

scratch = pathlib.Path(sys.argv[1])
out = pathlib.Path(sys.argv[2])

def load(s):
    raw = json.loads((scratch / s).read_text())
    return {r["command"]: round(r["mean"] * 1000) for r in raw["results"]}

s1 = load("s1.json")
s2 = load("s2.json")
s3 = load("s3.json")
s4 = load("s4.json")

results = {
    "updated": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "unit": "ms",
    "tools": ["ktx-daemon", "ktx", "main-kts"],
    "versions": {
        "ktx": os.environ.get("KTX_VER", "unknown"),
        "main-kts": "Kotlin " + os.environ.get("KOTLIN_VER", "unknown"),
        "java": os.environ.get("JAVA_VER", "unknown"),
    },
    "rows": [
        {
            "key": "warm-no-deps",
            "label": "Warm — hello.kts (no deps)",
            "values": {
                "ktx-daemon": s1.get("ktx-daemon"),
                "ktx": s1.get("ktx"),
                "main-kts": s1.get("main-kts"),
            },
        },
        {
            "key": "warm-with-deps",
            "label": "Warm — script with @file:DependsOn",
            "values": {
                "ktx-daemon": s2.get("ktx-daemon"),
                "ktx": s2.get("ktx"),
                "main-kts": s2.get("main-kts"),
            },
        },
        {
            "key": "dev-loop",
            "label": "Dev loop — script edited every run",
            "values": {
                "ktx-daemon": s3.get("ktx-daemon"),
                "ktx": s3.get("ktx"),
                "main-kts": s3.get("main-kts"),
            },
        },
        {
            "key": "cold-start",
            "label": "Cold start — caches cleared",
            "values": {
                "ktx-daemon": s4.get("ktx-daemon"),
                "ktx": s4.get("ktx"),
                "main-kts": s4.get("main-kts"),
            },
        },
    ],
}

out.write_text(json.dumps(results, indent=2, ensure_ascii=False) + "\n")
print(f"wrote {out}")
PY
