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
#   scripts/run-tests.sh --shaderpack test    # Metal with a shader pack (output and baseline folder metal-<pack>)
#   scripts/run-tests.sh --upscale temporal --render-scale 67   # Metal with MetalFX (folder metal-temporal67)
#   scripts/run-tests.sh --sodium             # run with Sodium installed (output folder <backend>-sodium)
#   scripts/run-tests.sh --iris <pack.zip>    # the original pack on Iris + Sodium + OpenGL, as a reference
#                                             # (pack from shaderpacks/_original/, output folder iris-<pack>)
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
sodium=false
iris=""
shaderpack=""
upscale=""
render_scale=75
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
        --sodium) sodium=true ;;
        --iris) iris="${2:?--iris needs the name of a pack zip in shaderpacks/_original}"; backend=opengl; shift ;;
        --upscale) upscale="${2:?--upscale needs a value}"; backend=metal; shift ;;
        --render-scale) render_scale="${2:?--render-scale needs a value}"; shift ;;
        --shaderpack) shaderpack="${2:?--shaderpack needs a value}"; backend=metal; shift ;;
        -h|--help) sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
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

if [ -n "$upscale" ]; then
    case "$upscale" in
        spatial|temporal) ;;
        *) echo "Unknown --upscale '$upscale' (expected spatial or temporal)" >&2; exit 2 ;;
    esac
    case "$render_scale" in
        ''|*[!0-9]*) echo "--render-scale needs a whole number from 50 to 100" >&2; exit 2 ;;
    esac
    if [ "$render_scale" -lt 50 ] || [ "$render_scale" -gt 100 ] || { [ "$upscale" = spatial ] && [ "$render_scale" -eq 100 ]; }; then
        echo "--render-scale must be 50 to 100 (below 100 for spatial)" >&2
        exit 2
    fi
    if [ "$backend" != metal ] || $sodium || $save_baseline; then
        echo "--upscale needs the Metal backend and cannot be combined with --sodium or --save-baseline" >&2
        exit 2
    fi
    # The result depends on frame timing, so there is no pixel-exact reference to compare with.
    compare=false
fi

# The project builds with Temurin 25 only.
if JAVA_25="$(/usr/libexec/java_home -v 25 2>/dev/null)"; then
    export JAVA_HOME="$JAVA_25"
fi

variant="$backend"
$sodium && variant="$backend-sodium"
[ -n "$shaderpack" ] && variant="$backend-$shaderpack"
[ -n "$upscale" ] && variant="$variant-$upscale$render_scale"
[ -n "$iris" ] && variant="iris-${iris%.zip}"
out="$PWD/run/test-output/$stamp/$variant"
# Sodium draws terrain and clouds its own way, so runs with it have their own reference set.
baseline="$PWD/run/test-baseline/opengl"
$sodium && baseline="$PWD/run/test-baseline/opengl-sodium"
# A shader pack changes the picture on purpose; it is compared with its own earlier result.
[ -n "$shaderpack" ] && baseline="$PWD/run/test-baseline/$variant"
# An upscaled picture is close to the full-size one, but not within the pixel tolerance.
[ -n "$upscale" ] && baseline="$PWD/run/test-baseline/$variant"
# A reference run of the original pack: its animations cannot be held, so it is looked at, not compared.
[ -n "$iris" ] && compare=false
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
$sodium && gradle_args+=("-Pmetalcraft.sodium=true")
[ -n "$iris" ] && gradle_args+=("-Pmetalcraft.iris=true" "-Pmetalcraft.test.irisPack=$iris")
[ -n "$shaderpack" ] && gradle_args+=("-Pmetalcraft.shaderpack=$shaderpack")
[ -n "$upscale" ] && gradle_args+=("-Pmetalcraft.upscale=$upscale" "-Pmetalcraft.renderScale=$render_scale")


echo "==> Running scenes on $variant (output: $out)"
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
        printf '\n## %s · %s\n\n' "$(date '+%Y-%m-%d %H:%M')" "$variant"
        printf 'Device: %s. Commit: %s. Output: `run/test-output/%s/%s`.\n\n' "$device" "$commit" "$stamp" "$variant"
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
    rm -rf "$PWD/run/test-baseline/$variant"
    mkdir -p "$PWD/run/test-baseline/$variant"
    cp "$out"/*.png "$out/metrics.json" "$PWD/run/test-baseline/$variant/"
    echo "==> Saved as baseline: run/test-baseline/$variant"
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
