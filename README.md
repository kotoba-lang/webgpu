# kami-webgpu

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

## Status

Renders instanced, lit cuboids with a follow/overview camera (proven live in Chrome
via WebGPU). Shadow-mapped, PBR, with the **lighting model, sun frustum, camera, and
geometry library all data-driven** (see "Authoring the look"). Roadmap:
EDN-authored post-FX passes, pipelines, and WGSL.
