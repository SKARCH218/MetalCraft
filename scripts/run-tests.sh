#!/bin/bash
# Automated test harness entry point (CLAUDE.md section 6-1).
#
# Starts Minecraft, visits every test scene, saves screenshots and frame-time metrics, then quits.
#
#   scripts/run-tests.sh                      # OpenGL, quick metrics
#   scripts/run-tests.sh --backend vulkan     # same scenes on another renderer (opengl | vulkan | metal)
#   scripts/run-tests.sh --benchmark          # 10 s warm-up + 30 s measurement per scene
#   scripts/run-tests.sh --scenes cave,nether # only some scenes
#   scripts/run-tests.sh --save-baseline      # keep this run as the reference set for its backend
#   scripts/run-tests.sh --stamp NAME         # reuse an output folder name, to group several backends
#   scripts/run-tests.sh --fresh-worlds       # regenerate the cached test world (baselines must be re-saved)
#
# Output: run/test-output/<date-time>/<backend>/{<scene>.png, metrics.json, log.txt, game-output.log}
# The normal test world is generated once and kept in run/test-worlds, because terrain generation is not
# repeatable from run to run even with a fixed seed.
# Unless --no-compare is given, the screenshots are compared with run/test-baseline/opengl when that exists
# and the result lands in .../<backend>/compare/. The Mac must be logged in with the display on.

set -uo pipefail
cd "$(dirname "$0")/.."

backend=opengl
benchmark=false
scenes=""
save_baseline=false
compare=true
fresh_worlds=false
stamp="$(date +%Y%m%d-%H%M%S)"

while [ $# -gt 0 ]; do
    case "$1" in
        --backend) backend="${2:?--backend needs a value}"; shift ;;
        --scenes) scenes="${2:?--scenes needs a value}"; shift ;;
        --stamp) stamp="${2:?--stamp needs a value}"; shift ;;
        --benchmark) benchmark=true ;;
        --save-baseline) save_baseline=true ;;
        --no-compare) compare=false ;;
        --fresh-worlds) fresh_worlds=true ;;
        -h|--help) sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
    shift
done

case "$backend" in
    opengl|vulkan|metal) ;;
    *) echo "Unknown backend '$backend' (expected opengl, vulkan or metal)" >&2; exit 2 ;;
esac

if $save_baseline && [ -n "$scenes" ]; then
    echo "--save-baseline needs a full run; it cannot be combined with --scenes" >&2
    exit 2
fi

# The project builds with Temurin 25 only.
if JAVA_25="$(/usr/libexec/java_home -v 25 2>/dev/null)"; then
    export JAVA_HOME="$JAVA_25"
fi

out="$PWD/run/test-output/$stamp/$backend"
baseline="$PWD/run/test-baseline/opengl"
# A reused --stamp must not leave screenshots of an earlier run behind.
rm -rf "$out"
mkdir -p "$out"

worlds="$PWD/run/test-worlds"
if $fresh_worlds && [ -d "$worlds" ]; then
    rm -rf "$worlds"
    echo "==> Removed the cached test world. Screenshots will no longer match old baselines: use --save-baseline."
fi

gradle_args=(runClientGameTest "-Pmetalcraft.backend=$backend" "-Pmetalcraft.test.outputDir=$out" "-Pmetalcraft.test.benchmark=$benchmark" "-Pmetalcraft.test.worldCache=$worlds")
[ -n "$scenes" ] && gradle_args+=("-Pmetalcraft.test.scenes=$scenes")

echo "==> Running scenes on $backend (output: $out)"
# caffeinate keeps the display awake; a sleeping display stops the game window from rendering.
caffeinate -dims ./gradlew "${gradle_args[@]}" > "$out/game-output.log" 2>&1
status=$?

# log.txt: warnings, errors, stack traces and Metal validation output, without colour codes.
sed -E $'s/\x1b\\[[0-9;]*m//g' "$out/game-output.log" \
    | grep -E '/(WARN|ERROR|FATAL)\]|Exception|^[[:space:]]+at |Caused by|-\[MTL|MTLDebug|Metal API Validation|validation|FAILED|AssertionError' \
    > "$out/log.txt" || true

if [ $status -ne 0 ]; then
    echo "==> FAILED: the game exited with an error (gradle status $status). Last lines of the game output:" >&2
    tail -n 30 "$out/game-output.log" >&2
    exit 1
fi
if ! grep -q '"completed": true' "$out/metrics.json" 2>/dev/null; then
    echo "==> FAILED: $out/metrics.json is missing or incomplete" >&2
    exit 1
fi

echo "==> Screenshots: $(ls "$out"/*.png 2>/dev/null | wc -l | tr -d ' ')   log.txt lines: $(wc -l < "$out/log.txt" | tr -d ' ')"
grep -E '"(name|avgFps|low1PercentFps|p99FrameTimeMs)"' "$out/metrics.json" | paste - - - - | sed -E 's/[",]//g; s/[[:space:]]+/ /g'

if $benchmark && [ -f "$out/benchmark.md" ]; then
    commit="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
    [ -n "$(git status --porcelain 2>/dev/null)" ] && commit="$commit + local changes"
    device="$(sed -n 's/.*"device": "\(.*\)",/\1/p' "$out/metrics.json")"
    mkdir -p docs
    [ -f docs/benchmarks.md ] || printf '# Benchmarks\n\nAppended by `scripts/run-tests.sh --benchmark`: 1920x1200, render distance 12, vsync off, no FPS limit,\n10 s warm-up and 30 s measurement per scene. Newest run last.\n' > docs/benchmarks.md
    {
        printf '\n## %s · %s\n\n' "$(date '+%Y-%m-%d %H:%M')" "$backend"
        printf 'Device: %s. Commit: %s. Output: `run/test-output/%s/%s`.\n\n' "$device" "$commit" "$stamp" "$backend"
        cat "$out/benchmark.md"
    } >> docs/benchmarks.md
    echo "==> Benchmark table appended to docs/benchmarks.md"
fi

if $save_baseline; then
    # The run that generates the world sees chunks a reload finishes slightly differently at the edges.
    if grep -q '"normalWorldFromCache": false' "$out/metrics.json"; then
        echo "==> Baseline NOT saved: this run generated the test world. Run again with --save-baseline." >&2
        exit 1
    fi
    rm -rf "$PWD/run/test-baseline/$backend"
    mkdir -p "$PWD/run/test-baseline/$backend"
    cp "$out"/*.png "$out/metrics.json" "$PWD/run/test-baseline/$backend/"
    echo "==> Saved as baseline: run/test-baseline/$backend"
fi

if $compare && ! $save_baseline && [ -d "$baseline" ]; then
    tool="$PWD/build/tools/compare-screenshots"
    if [ ! -x "$tool" ] || [ scripts/compare-screenshots.swift -nt "$tool" ]; then
        mkdir -p "$(dirname "$tool")"
        swiftc -O -o "$tool" scripts/compare-screenshots.swift || exit 1
    fi
    echo "==> Comparing with baseline $baseline"
    "$tool" "$baseline" "$out" "$out/compare"
    case $? in
        0) echo "==> All compared scenes match the baseline" ;;
        1) echo "==> Some scenes differ. Open the diff images in $out/compare" >&2; exit 3 ;;
        *) echo "==> FAILED: the comparison tool could not finish" >&2; exit 1 ;;
    esac
fi
echo "==> Done: $out"
