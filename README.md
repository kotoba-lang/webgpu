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

- **cross-platform** — the same EDN is read by Clojure, ClojureScript, Rust (`edn`),
  and JS. One description; the web executes it via CLJS→WebGPU, a native host via
  Rust→wgpu.
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

## Status

Renders instanced, lit cuboids with a follow/overview camera (proven live in Chrome
via WebGPU). Roadmap: EDN-authored passes / pipelines / materials / WGSL, shadows,
PBR, and a native Rust executor over the same EDN.
