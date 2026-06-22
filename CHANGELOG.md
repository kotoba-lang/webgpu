# Changelog

kami-webgpu — declarative WebGPU + UI/input/audio/state from EDN (hiccup for the GPU).

## Unreleased

### Renderer (`kami.webgpu`)
- Declarative WebGPU from a pure EDN render-IR — CLJS drives the browser WebGPU API directly
  (no Rust, no wasm, no per-frame string marshaling).
- Render graph as data: shaders, offscreen targets, samplers, pipelines, and an ordered
  `:passes` array are all EDN; the executor interprets them (reorder/add passes by editing data).
- Real shadows: a sun shadow-map pass + PCF sampling, expressed as the first `:passes` entry.
- PBR materials as per-instance EDN (metallic / roughness / emissive).
- Known footgun fixed: GPU buffers written with `writeBuffer` need `COPY_DST`, or the write
  silently no-ops and the buffer reads as zeros (the original "clear works, no geometry" bug).

### Data-driven domains (each EDN, ADR-0040)
- `kami.ui` — HUD as EDN → DOM overlay (`:panel` / `:bar` / `:text` / `:minimap`).
- `kami.input` — key/pointer bindings → axes & actions, as data.
- `kami.audio` — sound cues as EDN recipes, synthesized via Web Audio.
- `kami.fsm` — animation/behaviour state machines as data.
- `kami.physics` — collision layers + matrix as data; `separate` resolves overlaps.
- `kami.netsync` — replication schema (what syncs) as data; `snapshot` / `apply-snapshot` / `interp`.
- `kami.level` — spawns + shrinking zone (storm) + objective as data.
- `kami.webgpu.ir` — render-IR shape + a camera rig (`rig->camera`), cross-platform `.cljc`.

### Cross-platform (ADR-0042)
- The `.cljc` interpreters are the same source the web (CLJS), native (kotoba-clj→WASM, via
  kami-script-runtime), and JVM (babashka) all run. Verified determinism: the native scatter RNG
  matches the web's xorshift sequence — same EDN → same world.

### Tests
- `bb test` — the `.cljc` interpreters on the JVM (examples + properties; 8 tests / 42 assertions).
- `bb verify` / superproject `scripts/verify-clj-everywhere.sh` — all surfaces in one command
  (JVM + native WASM + native renderer).
- CI: GitHub Actions runs `bb test` on every push.
- Bug found via coverage work and fixed upstream: kotoba-clj `into` overflowed when the dst was
  at exact capacity (a vector literal); now returns a correctly-sized new vector.
