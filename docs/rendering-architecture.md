# kami rendering — one EDN, every GPU backend

The renderer is **data**. A frame is an EDN render-IR (`{:globals :instances}`) drawn through an EDN
**render graph** (capability-gated passes) with **EDN shaders** (kami.wgsl). The same source drives
WebGPU (web + native), WebGL2 (web fallback), Metal/Vulkan/DX12 (native via wgpu+naga), and PS5/Switch
(vendor WGSL transpile). **Canvas2D is not a target** — its immediate-mode model diverges from the GPU
pipeline; 2D is GPU instanced quads.

3D instances express dimensions as `:size [width height depth]`. The legacy two-value
form `[width height]` normalizes to `[width height width]`, preserving the original square
x/z footprint. WebGPU packing and WebGL2 model matrices consume this same rule. Independent
depth uses the existing model matrix z-scale and does not change the 28-float instance stride.

## Layers (all `.cljc`/`.clj`, browser + JVM/bb)

| ns | role |
|---|---|
| `kami.wgsl` | shader as data — EDN AST → WGSL (struct/binding/func/expr/stmt, compute/storage). |
| `kami.shaders` / `kami.render-shaders` | the lit/shadow + 16 open-world shaders, authored in `kami.wgsl` EDN. |
| `kami.gpu` | capability-gated render graph. A pass declares `:requires` (e.g. `[:compute]`); a backend advertises `:caps`; `resolve-graph` drops what a tier can't run (compute on WebGL2) and reports it. |
| `kami.sprite-gpu` | 2D as GPU instanced quads — sprite primitives (circle/ellipse/rect/arc) → quad instances + a 2D-SDF shader. Replaces Canvas2D. |
| `kami.webgpu` | the WebGPU runtime (CLJS → browser WebGPU API). |
| `kami.webgl` | the WebGL2 runtime (fallback). `pick-backend` selects WebGPU when `navigator.gpu` exists, else WebGL2. 2D sprite pass + 3D lit/shadow pass (depth-FBO shadow map). |
| `kami.playwright` | browser tests in CLJ — `eval-page` drives a headless WebGL2 Chromium and returns EDN. |

## Shader language: one EDN → WGSL + GLSL

`bb gen-glsl` lowers each EDN shader to WGSL (kami.wgsl) and lets **naga** (wgpu's frontend) transpile
WGSL → **GLSL ES 3.00** (WebGL2) — naga handles type inference, std140 layout, `@location→in/out`,
`@builtin→gl_*`, `textureSample→texture`. So one source feeds WGSL (WebGPU/Metal/console) **and** GLSL
(WebGL2). Compute shaders don't cross to WebGL2 (no compute there) — gated by `kami.gpu :requires`.

## Single source, web ↔ native

`bb gen-wgsl` writes the canonical WGSL to the native crates (`kami-webgpu-rs`, `kami-render`), which
`include_str!` it; `bb wgsl-parity` gates that the committed `.wgsl` stays token-equivalent to the EDN.
The lit shader's `light_a..d` tunables + the 16 open-world shaders are all single-sourced this way.

## Verification (run in CI)

- `bb test` — the EDN/shader/GPU-IR gates (geometry golden, wgsl, render-shader token-equivalence,
  capability resolution, sprite-gpu).
- `naga` — every generated WGSL/GLSL is validated by wgpu's own frontend.
- `bb webgl-test` — the generated GLSL **links** in a real headless WebGL2 browser (playwright-clj).
- `bb render-test` — the GPU-2D sprite-SDF pass **draws** (pixel readback: a red disc + a green block).
- `bb wgsl-parity` / `bb wit-check` — single-source drift gates. All run on every push/PR (GitHub Actions).

## Status

Implemented + verified: the EDN render-IR, capability-gated graph, shader-as-data (WGSL + GLSL),
GPU-2D quads, both runtimes (WebGPU + WebGL2), `pick-backend` dispatch (wired into isekai `start!`).
Remaining: connecting the WebGL2 fallback's full render loop in isekai (the renderer, GLSL, and
pixel-verification are all in place); native render pipelines as data (web's `default-graph` exists,
native is still hardcoded Rust).
# Render-style boundary

`kami.webgpu.render-style` defines the pure EDN `:kotoba.render/style-v1`
boundary shared with scenes and native renderers. A scene authors it at
`:render/style`; a consumer may propagate it to `[:globals :render-style]`.

This repository currently lowers that envelope only in `kami.webgpu.mesh`, the
skinned/morph mesh path. It does **not** wire the style profile into the static
instanced `kami.webgpu` render graph. The mesh backend executes PBR and toon-PBR
shading, including per-material shade/rim/quantized highlights. It has no
outline or color-grading pass. Consequently `:outline :screen-space` fails
closed at mesh-uniform lowering instead of producing an unannounced fidelity
downgrade. `:inverted-hull` is reserved by the contract and rejected during
profile validation.
