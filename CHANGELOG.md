# Changelog

kami-webgpu — declarative WebGPU + UI/input/audio/state from EDN (hiccup for the GPU).

## Unreleased

### Dedup pass across kotoba-lang/{sprite-gpu,gpu,webgl} (2026-07-09)

Three standalone repos (`kotoba-lang/sprite-gpu`, `kotoba-lang/gpu`, `kotoba-lang/webgl`) each
carried a duplicate of a namespace that also lives here (`kami.sprite-gpu`, `kami.gpu`,
`kami.webgl`), left over from the abandoned "clj-wgsl Phase-4" split-migration + independent
"restore" commits (2026-07-02). All three pairs were diffed in detail rather than assumed:

- **`sprite-gpu`** — `kami.sprite-gpu` (here) wins. It has the `:rect` half-extent bug fix (PR #10,
  above) that the standalone repo's copy never received; the standalone repo's own git history has
  no commits beyond CI/lint housekeeping since its restore. `kotoba-lang/sprite-gpu`'s
  `kotoba.sprite-gpu` is now a thin re-export of this namespace.
- **`gpu`** — `kami.gpu` (here) wins, though the diff is docstring-only (no functional bug on
  either side — every `def`/`defn` body was byte-identical). `kami.gpu` reflects the more current
  "capability-gated render-graph contract" doc pass; the standalone repo received no comparable
  work. `kotoba-lang/gpu`'s `kotoba.gpu` is now a thin re-export of this namespace.
- **`webgl` — the SURPRISING one: the standalone repo was more current here, not this repo.**
  `kotoba-lang/webgl`'s `kotoba.webgl` was already `.cljc` with a `#?(:clj ...)` fallback branch
  (JVM-safe capability queries + explicit "browser-only executor" errors instead of blowing up on
  `js/navigator`), backed by its own JVM test (`webgl-test`). `kami.webgl` here was plain `.cljs`
  with none of that — a real feature gap, not just docs. So instead of repointing consumers away
  from the standalone repo's content, **that `.cljc` structure was ported INTO this repo**:
  `src/kami/webgl.cljs` → `src/kami/webgl.cljc` (adds the `:clj` branch, verbatim-ported from
  `kotoba.webgl`'s), `src/kami/webgl/glsl.cljs` → `src/kami/webgl/glsl.cljc` (pure data, no
  reader-conditional needed — its content was already byte-identical between the two repos), and a
  new `test/webgl_test.clj` (ported from the standalone repo's) wired into `bb test`, giving
  `kami.webgl` JVM coverage it never had. This also matches this repo's own stated design
  philosophy ("A `.cljc`-first library", per `deps.edn`'s header comment). `kotoba-lang/webgl`'s
  `kotoba.webgl` is now, in turn, a thin re-export of this (now at-parity) namespace.

Consumers: `network-isekai`, `net-babiniku`, and everything else already depending on this repo are
unaffected (same public API, same behaviour, `kami.webgl` gained functionality rather than losing
any). `kotoba-lang/scene2d` and `kotoba-lang/webgl` (the repo, for its own internal use before this
change) have been repointed from the standalone `sprite-gpu`/`gpu` repos to depend on this repo
directly. See each affected repo's own README/CHANGELOG for its side of this.

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
