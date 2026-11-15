# Benchmarks

Appended by `scripts/run-tests.sh --benchmark`: 1920x1200, render distance 12, vsync off, no FPS limit,
10 s warm-up and 30 s measurement per scene. Newest run last.

## 2026-11-15 04:10 · opengl

Device: Apple M1 Max. Commit: 558a9ab + local changes. Output: `run/test-output/m05-benchmark/opengl`.

| Scene | Avg FPS | 1% low FPS | Avg frame (ms) | p99 frame (ms) |
|---|---:|---:|---:|---:|
| plains_day | 290.79 | 144.91 | 3.44 | 6.52 |
| ocean | 229.62 | 124.03 | 4.36 | 7.34 |
| night_torches | 302.45 | 137.18 | 3.31 | 6.56 |
| cave | 454.26 | 140.66 | 2.20 | 6.71 |
| nether | 195.73 | 113.69 | 5.11 | 8.51 |
| entities | 273.21 | 121.30 | 3.66 | 7.98 |
| gui_inventory | 248.21 | 114.92 | 4.03 | 8.32 |
| entities_fx | 263.93 | 116.99 | 3.79 | 7.98 |

## 2026-11-15 04:20 · vulkan

Device: Apple M1 Max. Commit: 558a9ab + local changes. Output: `run/test-output/m05-benchmark/vulkan`.

| Scene | Avg FPS | 1% low FPS | Avg frame (ms) | p99 frame (ms) |
|---|---:|---:|---:|---:|
| plains_day | 191.39 | 53.73 | 5.22 | 13.09 |
| ocean | 190.65 | 54.27 | 5.25 | 12.32 |
| night_torches | 190.42 | 52.36 | 5.25 | 15.89 |
| cave | 191.52 | 50.95 | 5.22 | 16.41 |
| nether | 189.81 | 55.04 | 5.27 | 13.80 |
| entities | 191.37 | 54.31 | 5.23 | 13.82 |
| gui_inventory | 191.25 | 59.51 | 5.23 | 11.12 |
| entities_fx | 191.05 | 55.46 | 5.23 | 14.88 |
