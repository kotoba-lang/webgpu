# Testing

The `.cljc` domain interpreters (`kami.fsm`, `kami.physics`, `kami.netsync`, `kami.level`,
`kami.webgpu.ir`) are the **same source** that runs on the browser and JVM
surfaces. Repository-local tests focus on the CLJ/EDN contract:

| Surface | How it runs the interpreters | Test command |
|---|---|---|
| **web** | CLJS → WebGPU / DOM (`kami.webgpu`, `kami.ui`, …) | in-browser (isekai.network) |
| **JVM** | babashka loads the `.cljc` directly | `bb test` |

## Commands

```bash
bb test       # JVM: the .cljc interpreters (8 tests / 42 assertions — examples + properties)
bb verify     # repository-local surfaces: bb test + real-binary format gates
```

CI (`.github/workflows/test.yml`) runs `bb test` on every push — the fast,
GPU-free correctness gate. Native adapters should consume this repo's EDN
contracts from their own repositories.

## What's asserted

- **fsm** `advance` — transitions fire on matching events; identity otherwise
- **physics** `collides?` (symmetric) + `separate` (overlap → deltas; non-colliding → none)
- **netsync** `snapshot` (synced fields only, idempotent) + `interp` (lerp/snap, t-endpoints)
- **level** `zone-radius` (monotonic, floors at `:min-radius`) + `in-zone?` + `spawn-points`
- **camera** `rig->camera` (distance/azimuth/height → eye/target)
- **ir** `render-ir` / `valid?`
