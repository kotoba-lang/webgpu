
> **Authority (ADR-2607102200 addendum 8–9):** this repo is the **browser WebGPU executor** (`kami.webgpu*`) plus compute-golden harness (`kami.cartpole-math`). Game browser surface → `kotoba-lang/host`. Pure retired-rs domain → `kotoba-lang/webgpu-rs`. Domain IR packages are siblings (gpu/webgl/dance/…).
> **ADR-2607102200 addendum 5:** render-domain modules live in sibling packages
> (`wgsl`, `sprite-gpu`, `sky`, `shaders`, `render-shaders`, `scene2d`, `sprite2d`).
> This repo is the WebGPU/WebGL *executor* + harness; `kami.*` namespaces still
> resolve via those deps.

# kami-webgpu

## Layer boundary

`kotoba-lang/render` authors backend-neutral quality plans;
`kotoba-lang/webgpu` resolves and executes KAMI render graphs; and
`kotoba-lang/org-w3-webgpu` is only the raw W3C WebGPU JavaScript binding.
See ADR 0002. `kami.webgpu.quality/resolve-plan` reports every unsupported
effect instead of silently treating a requested quality tier as rendered.

> 2026-07-02: [kotoba-lang/webgpu-rs](https://github.com/kotoba-lang/webgpu-rs)
> はこのリポジトリに統合された（owner 指示）。`kotoba.webgpu-rs.*` — CPU 側
> EDN render-IR ドメインロジック（render-IR parsing / scene.edn bridge /
> deterministic procedural demo scene / mat4 / rng / geometry）— は
> `src/kotoba/webgpu_rs*` に namespace そのままで住む。旧リポは archive。
>
> 2026-07-05 (ADR-2607051500): `90-docs/migration/kami-webgpu-dsl-runtime-split.edn`
> 台帳の cleanup が未実行のまま残っていた 38 個の無関係フォーマット DSL
> （materialx/dxf/verilog/scad/graphql/dance/atom/css/…、各々 kotoba-lang 直下に
> 独立 repo が既に存在）を削除。実際にレンダリングコードが依存していたのは
> `kami.wgsl`→`kami.expr` の 1 本だけで、これは標準 `expr` repo への
> `:local/root` 依存（`kotoba.expr`）へ置き換えた。あわせて、その孤児 DSL 群
> だけを実バイナリ検証していた `scripts/format_gate.clj`（+ その専用
> validator `gltf_validate.js`/`graphql_validate.js`）も削除（`gate`/`verify`
> bb task から除去）。`gen_glsl.clj`/`gen_wgsl.clj`/`gen_pipeline_specs.clj`/
> `fixtures/glsl`/`fixtures/pipeline_specs.edn` は `kami.wgsl`/`kami.shaders`/
> `kami.pipelines`（= このrepo自身のドメイン）に実際に使われているため維持。
> executor 本体は `org-w3-webgpu` 分離（Phase 2、`kami/webgpu.cljs` は
> `kami.webgpu`のまま — 台帳が期待していた `kotoba.webgpu` へのリネームは、
> 台帳の日付(2026-07-01)より後に起きた webgpu-rs 統合(2026-07-02)で実質的に
> 上書きされたため、今回は適用しなかった）。

**Declarative WebGPU from EDN — hiccup for the GPU.**

ClojureScript drives the browser WebGPU API directly from a plain EDN render-IR.
No Rust, no wasm, no per-frame string marshaling — you hand it data, it draws.

```clojure
(require '[kami.webgpu :as gpu]
         '[kami.webgpu.ir :as ir])

;; render-IR is pure EDN data (the "hiccup")
(def frame
  (ir/render-ir
    (ir/sky [0.74 0.84 0.95] [-0.4 -0.85 -0.35] [1.0 0.96 0.85])
    [(ir/instance [0 0 0] [0.62 0.60 0.66] [2 5])      ;; a building
     (ir/instance [4 0 3] [0.28 0.55 0.30] [1.1 2.6])] ;; a tree
    [45 55 45] [0 0 0]))                                ;; eye → target

;; executor: init once, draw each frame (browser)
(-> (gpu/init! (js/document.getElementById "game"))
    (.then (fn [ctx]
             (gpu/draw! ctx frame))))
```

## Why EDN, not CLJ

The render-IR is **data, not code** — exactly like hiccup's `[:div …]`. That makes it:

- **cross-platform** — the same EDN is read by Clojure, ClojureScript, and JS.
  One description; the web executes it via CLJS→WebGPU, and native adapters can
  consume the EDN contract from their own repositories.
- **Datomic-native** — store the render graph as datoms; `as-of`, query, and fork
  all work on data.
- **serializable / forkable** — save, send, clone a scene with no eval.
- **composable** — build and transform it with `assoc`/`update`/`merge`.

## Layout

| File | Platform | Role |
|---|---|---|
| `src/kami/webgpu/ir.cljc` | CLJ + CLJS | render-IR shape + pure constructors (the data layer) |
| `src/kami/webgpu.cljs`    | browser    | the WebGPU executor — `init!` / `draw!` |

The heavy rasterization is the GPU's; CLJS only records light per-frame commands.

## Use it

`deps.edn`:

```clojure
{:deps {com-junkawasaki/kami-webgpu {:local/root "../path/to/kami-webgpu"}}}
;; or a git dep — then add `kami.webgpu` to your shadow-cljs source path.
```

Requires `:simple` (not `:advanced`) shadow-cljs optimizations, or `^js` externs —
the WebGPU JS method names must not be renamed.

## Authoring the look (data-driven globals)

The shader's look used to be baked-in constants; it is now **data** under the frame's
`:globals`. Every key is optional — omit it and the executor merges `kami.webgpu.ir`'s
defaults, which reproduce the original render byte-for-byte (pinned by `test/render_ir_test.clj`).

| `[:globals …]` key | what it controls | merged over |
|---|---|---|
| `:lighting` | ambient (`:ambient` `:ambient-sky`), specular (`:spec-min/-max`), Fresnel rim (`:rim` `:rim-power`), Blinn-Phong (`:shininess-min/-max`), `:sun-diffuse`, `:metallic-diffuse-cut`, `:gamma`, shadow PCF (`:shadow-bias-slope/-min/-texel`) | `ir/default-lighting` |
| `:shadow` | the sun's ortho frustum: `:extent` (half-width), `:near`, `:far`, `:distance` (back along −sun-dir) | `ir/default-shadow` |
| `:fov` `:near` `:far` | the perspective camera (degrees / planes) | `60 / 0.5 / 4000` |
| *(init! opt)* `:geometry` | the `:geo` mesh kinds — `{:kw {:type :box/:sphere/:cylinder/:plane …params}}` baked by `ir/mesh-from-spec` | `ir/default-geometry` |

Shared procedural libraries may register a pre-baked portable mesh as
`{:type :mesh :mesh {:positions [[x y z] ...] :normals [...] :indices [...]}}`.
The IR validates its vertex/index shape before WebGPU or WebGL upload; metadata
such as `:bounds` can remain beside the mesh for culling and quality evidence.

Heightfield patches can be registered directly without backend-specific code:

```clojure
(gpu/init! canvas
 {:geometry {:island-high {:type :terrain :patch [0 0] :size 64
                           :base-segments 32 :detail :high
                           :amplitude 9 :seed 2654435769 :skirt-depth 3}}})
```

Both WebGPU and WebGL call the same `ir/mesh-from-spec` terrain baker.

Terrain-following roads use the same backend-neutral dispatch. `:part` selects
the independently materialed asphalt surface, soil shoulder or lane marking:

```clojure
{:type :road-ribbon :part :surface :detail :high
 :path [[0 0] [24 0] [36 12]] :width 8 :shoulder 1.5
 :camber 0.12 :shoulder-drop 0.08 :base-subdivisions 8 :miter-limit 1.75
 :terrain {:size 64 :base-segments 32 :amplitude 8 :seed 7}}
```

`:part :marking` forwards the shared render contract's deterministic dash,
phase, offset, clearance and per-LOD budget options without executor-specific
geometry logic.

```clojure
;; a warmer dusk look + a wider shadow frustum + a custom mesh kind — all data:
(gpu/init! canvas {:geometry (assoc ir/default-geometry :slab {:type :box :size [3 0.3 3]})})
(gpu/draw! ctx
  (-> (wir/render-ir sky instances eye target)
      (assoc-in [:globals :lighting] {:ambient [0.20 0.12 0.10] :rim 0.4 :gamma 2.4})
      (assoc-in [:globals :shadow]   {:extent 300.0})
      (assoc-in [:globals :fov] 70)))
```

In network-isekai a game authors the same data in `scene.edn` as `:render/lighting`,
`:render/shadow`, `:render/camera`; `isekai.render-ir/scene->globals` threads it through.

Instance dimensions use `:size [width height depth]`, so a road, wall, or vehicle can
have an independent z footprint. Existing `:size [width height]` data remains compatible:
the renderer interprets it as `[width height width]`. Both WebGPU and the WebGL2 fallback
apply the same normalization. Depth occupies the model matrix's existing z-scale slot;
the 28-float/112-byte instance ABI is unchanged.

## Submission packets and skinned overlay caching

`kami.webgpu.submission/build-submission-packets` groups exact compatible
pipeline/mesh/LOD/material-resource state, packs the 32-float instance ABI and
retains source indices, entity IDs and semantic counts. Packet and LOD budgets
report overflow without silently dropping instances.

For character overlays, create one cache with
`kami.webgpu.mesh/create-skinned-submission-cache`, then call
`encode-skinned-overlay-cached!`. Draws require `:entity-id`. The cached path
uploads one current joint palette per entity per frame, while reusing texture
views, bind groups and 60-float globals arrays across frames. It never caches
changing matrices. `skinned-submission-evidence` reports palette uploads,
upload reduction, bind-group creation/reuse and provenance preservation; these
are structural counts, not synthetic timing claims.

Long-lived scenes must call `evict-skinned-entity!` when a transient character
leaves. `reset-skinned-submission-cache!` clears all resident entities while
retaining device ownership; `destroy-skinned-submission-cache!` releases all
owned joint buffers and permanently invalidates that cache. Device identity is
validated on every prepare: replacement clears old resources and fails closed
instead of allowing bind groups from an old device to survive. Lifecycle counts
are included in `skinned-submission-evidence`.

Capture gates can read `:capture-presence` from `backend-evidence`. The renderer
publishes schema `:kotoba.webgpu/capture-presence-evidence-v2` only after the
frame command buffer is accepted by `queue.submit`; a later frame without an
overlay clears it, and device loss invalidates it. Skinned overlays report the
encoded entity IDs, semantic `:role` values, draw count, device/cache state and
any `:projected-screen-bounds` supplied by the consumer. Bounds are explicitly
tagged `:consumer-supplied`: the renderer does not claim projection or perform
pixel readback. Deterministic capture still owns simulation freeze in the host;
`kami.webgpu/settle!` only provides the submitted-work completion barrier.

## Status

Renders instanced, lit geometry with a follow/overview camera, proven live in
Chrome through both WebGPU-first and forced WebGL2 fallback. Static WebGL2
render-IR now retains the computed matrix/draw batch so the fallback does not
rebuild 20,000 transforms each frame. The modeler E2E verifies 20,000 sphere
instances / 11.2 million resident triangles in one draw on both backends.
Shadow-mapped, PBR, with the **lighting model, sun frustum, camera, and
geometry library all data-driven** (see "Authoring the look"). Roadmap:
EDN-authored post-FX passes, pipelines, and WGSL.
