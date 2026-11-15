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

## 2026-11-15 14:32 · opengl

Device: Apple M1 Max. Commit: 94f2ada + local changes. Output: `run/test-output/m2-benchmark/opengl`.

| Scene | Avg FPS | 1% low FPS | Avg frame (ms) | p99 frame (ms) |
|---|---:|---:|---:|---:|
| plains_day | 174.18 | 71.97 | 5.74 | 12.32 |
| ocean | 138.67 | 63.45 | 7.21 | 13.61 |
| night_torches | 168.80 | 65.03 | 5.92 | 13.45 |
| cave | 231.61 | 70.08 | 4.32 | 12.52 |
| nether | 134.29 | 57.22 | 7.45 | 14.18 |
| entities | 165.74 | 65.90 | 6.03 | 13.05 |
| gui_inventory | 145.63 | 70.46 | 6.87 | 13.32 |
| entities_fx | 148.79 | 65.21 | 6.72 | 13.59 |

## 2026-11-15 14:40 · metal

Device: Apple M1 Max. Commit: 94f2ada + local changes. Output: `run/test-output/m2-benchmark/metal`.

| Scene | Avg FPS | 1% low FPS | Avg frame (ms) | p99 frame (ms) |
|---|---:|---:|---:|---:|
| plains_day | 963.81 | 416.28 | 1.04 | 2.06 |
| ocean | 755.04 | 370.33 | 1.32 | 2.41 |
| night_torches | 963.99 | 386.01 | 1.04 | 2.16 |
| cave | 1218.23 | 354.81 | 0.82 | 1.97 |
| nether | 747.33 | 341.30 | 1.34 | 2.52 |
| entities | 689.81 | 299.87 | 1.45 | 3.07 |
| gui_inventory | 680.20 | 300.23 | 1.47 | 3.07 |
| entities_fx | 670.45 | 291.65 | 1.49 | 3.13 |

## 2026-11-15 22:01 · metal-complementary

Device: Apple M1 Max. Commit: 956f29c + local changes. Output: `run/test-output/20261115-215924/metal-complementary`.

| Scene | Avg FPS | 1% low FPS | Avg frame (ms) | p99 frame (ms) |
|---|---:|---:|---:|---:|
| plains_day | 58.29 | 45.37 | 17.15 | 21.36 |
| nether | 89.23 | 80.17 | 11.21 | 12.11 |

## 2026-11-15 22:03 · metal-complementary

Device: Apple M1 Max. Commit: 956f29c + local changes. Output: `run/test-output/20261115-220156/metal-complementary`.

| Scene | Avg FPS | 1% low FPS | Avg frame (ms) | p99 frame (ms) |
|---|---:|---:|---:|---:|
| plains_day | 58.08 | 45.91 | 17.22 | 21.26 |

## 2026-11-15 22:04 · metal-complementary

Device: Apple M1 Max. Commit: 956f29c + local changes. Output: `run/test-output/20261115-220341/metal-complementary`.

| Scene | Avg FPS | 1% low FPS | Avg frame (ms) | p99 frame (ms) |
|---|---:|---:|---:|---:|
| plains_day | 58.15 | 45.61 | 17.20 | 21.09 |

## 2026-11-15 22:06 · metal-complementary

Device: Apple M1 Max. Commit: 956f29c + local changes. Output: `run/test-output/20261115-220538/metal-complementary`.

| Scene | Avg FPS | 1% low FPS | Avg frame (ms) | p99 frame (ms) |
|---|---:|---:|---:|---:|
| plains_day | 58.03 | 44.58 | 17.23 | 21.38 |

## 2026-11-15 22:08 · metal-complementary

Device: Apple M1 Max. Commit: 956f29c + local changes. Output: `run/test-output/20261115-220710/metal-complementary`.

| Scene | Avg FPS | 1% low FPS | Avg frame (ms) | p99 frame (ms) |
|---|---:|---:|---:|---:|
| plains_day | 58.05 | 46.49 | 17.23 | 21.07 |
