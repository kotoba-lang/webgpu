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
- **Bug fixed: `kami.sprite-gpu/prim->quad`'s `:rect` case doubled the rendered width/height.**
  A prior agent (kotoba-lang/kami-isekai-assets PR #4) found this while validating a Canvas2D
  probe against the GPU path but left the fix to this repo. `:w`/`:h` are FULL width/height in
  the sprite2d vocabulary (`kami.sprite2d.cljs`'s reference `:rect` painter draws
  `fillRect(dx-w/2, dy-h/2, w, h)`), but `prim->quad` was passing them straight into `:size`,
  which the sprite-SDF vertex shader consumes as a HALF-extent (quad corners ±1 scaled by
  `:size`) — a `[:rect {:w 100 :h 50}]` rendered at ~200×100px instead of ~100×50px. Fixed by
  halving `:w`/`:h` in `prim->quad`; `:r`/`:rx`/`:ry` (circle/ellipse/arc) were already radii
  (= half-extents) and needed no change. New pixel-verified regression test
  (`test/playwright_rect_extent_test.clj`, wired into `bb render-test`) renders the probe in a
  real headless WebGL2 browser and measures its on-screen bounding box; confirmed it fails
  against the pre-fix code (measured ~200×100px) and passes against the fix (~100×50px).
  Investigated before fixing: no scene in this repo or in `gftdcojp/network-isekai`'s
  `public/games/*/scene.edn` (26 of 31 use `:rect`) currently renders through this GPU path —
  every one of those scenes uses `:render/sprite2d` (the correct Canvas2D painter); the
  GPU/instanced path (`:render/gpu2d`) exists in `network-isekai`'s `isekai/game.cljc` but no
  scene opts into it. `kotoba-lang/kami-isekai-assets`'s render-adapter does feed this path, but
  its own render_pixel_test.clj only exercises `:ellipse`/`:circle` primitives (the slime
  preset), not `:rect`. So no shipped/tested content depended on the buggy half-extent
  behaviour — the fix is a straight correctness fix, not a breaking behaviour change to any
  live consumer.
