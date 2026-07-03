(ns kami.webgpu.mesh
  "A second, additive WebGPU executor for arbitrary skinned/morphable triangle
   meshes — new for ADR-2607031200 Phase 2 (com-junkawasaki/root), which asked
   for `kami.webgpu.cljs`'s `draw!` to consume `parse-render-ir`'s `:meshes`
   (with `:skin`/`:joints`/`:morphs`).

   Two real findings changed the plan (both confirmed by reading the actual
   current source, not assumed):

   1. `kotoba.webgpu-rs.render-ir/parse-mesh` (`kotoba-lang/webgpu`) does NOT
      carry vertex geometry — a `:meshes` entry is `{:id :url :pos :rot :scale
      :material :skin :joints :morphs}`, a transform+material+skin *binding*
      whose `:url` a host loader is meant to resolve. No such geometry loader
      exists anywhere in `kotoba-lang` yet.
   2. `kami.webgpu.cljs`'s `draw!` is built entirely around ONE fixed vertex
      model: a small library of *procedural, instanced* primitives (box/
      sphere/... from `kami.webgpu.ir/default-geometry`), one shared
      pos+normal vertex buffer per geometry KIND, drawn many times via a
      per-instance model-matrix buffer. There is no per-mesh arbitrary vertex
      buffer, no skin/morph binding, and no room for one without changing that
      buffer/pipeline model — extending it in place would risk the existing
      golden/visual behaviour ADR-0044's own migration note was careful to
      preserve.

   So instead of editing `kami.webgpu.cljs`, this is a NEW sibling executor,
   `kami.webgpu.mesh`, for the one case that file's model can't express: a
   single arbitrary mesh with real vertex/index data, optional glTF-style
   morph targets (`POSITION` deltas blended by weight), and optional glTF-style
   skinning (4 joint indices + weights per vertex, blended against a joint
   matrix palette). It shares the device/canvas bootstrap style of
   `kami.webgpu/init!` but is a distinct pipeline, distinct vertex layout,
   distinct shader — additive, `kami.webgpu.cljs` is untouched.

   Geometry source: `character-creator.gpu-adapter` (kotoba-lang/
   kami-app-character-creator) decodes real vertex/morph data straight off an
   in-memory `vrm.vrm-types/vrm-document` via `vrm.convert/read-accessor-f32`
   (restored 1:1 from `kami-vrm`, ADR-2607010930) — this namespace only knows
   about plain `{:positions :normals :indices :morph-target-deltas}`/`{:joints
   :weights :joint-count}` maps, not VRM/character-creator specifics.

   Skinning is NOT demoed against a real character-creator avatar: Phase 1's
   generated mesh has no JOINTS_0/WEIGHTS_0 (`character.body`'s mesh
   generators don't attach vertex weights — a real, separate limitation, not
   introduced here). `preview-demo.html` (kami-app-character-creator) instead
   drives this shader's skinning path with a small synthetic 2-bone bending
   quad-strip fixture, and its morph path with the real character-creator head
   mesh — see that file's comments.

   Added later (/loop maturity pass, ADR-2607031200): procedural, computed-
   in-shader PATTERNS as a second, backward-compatible draw-call option
   alongside the original flat `color`. This is explicitly NOT a texture/UV-
   decal system — no image loading, no sampler, no UV vertex attribute (an
   actual bitmap-texture pipeline is a separate, much larger effort). It's a
   `color_b` + `pattern_kind` + `pattern_params` uniform, evaluated per-
   fragment from each vertex's un-morphed, un-skinned LOCAL position
   (`VertexOut.local_pos`, carried through unchanged from the vertex shader's
   `position` input) — local space so a pattern (e.g. a scar's edge fade)
   stays anchored to the mesh's own geometry regardless of pose/animation,
   not the world-space result of skinning. `pattern_kind` 0 (the default,
   byte-for-byte what every pre-existing call site already produces since
   the new uniform fields default to zero) reproduces the exact old flat-
   color behaviour — `t` is always `0.0`, so `mix(color, color_b, 0.0) =
   color`."
  (:require [clojure.string :as str]))

;; --- minimal camera math (duplicated, not shared, from kami.webgpu — kept
;; private there; a handful of pure functions, not worth coupling two
;; independent executors over) --------------------------------------------

(defn- m4 [] (js/Float32Array. 16))
(defn- m4-mul [a b]
  (let [o (m4)]
    (dotimes [c 4]
      (dotimes [r 4]
        (aset o (+ (* c 4) r)
              (+ (* (aget a r)        (aget b (+ (* c 4) 0)))
                 (* (aget a (+ r 4))  (aget b (+ (* c 4) 1)))
                 (* (aget a (+ r 8))  (aget b (+ (* c 4) 2)))
                 (* (aget a (+ r 12)) (aget b (+ (* c 4) 3)))))))
    o))

(defn- perspective [fovy aspect near far]
  (let [f (/ 1.0 (js/Math.tan (/ fovy 2.0))) nf (/ 1.0 (- near far)) o (m4)]
    (aset o 0 (/ f aspect)) (aset o 5 f)
    (aset o 10 (* far nf)) (aset o 11 -1.0) (aset o 14 (* far near nf)) o))

(defn- v-sub [a b] [(- (a 0) (b 0)) (- (a 1) (b 1)) (- (a 2) (b 2))])
(defn- v-cross [a b] [(- (* (a 1) (b 2)) (* (a 2) (b 1)))
                      (- (* (a 2) (b 0)) (* (a 0) (b 2)))
                      (- (* (a 0) (b 1)) (* (a 1) (b 0)))])
(defn- v-norm [a] (let [l (js/Math.hypot (a 0) (a 1) (a 2)) l (if (< l 1e-9) 1.0 l)]
                    [(/ (a 0) l) (/ (a 1) l) (/ (a 2) l)]))
(defn- v-dot [a b] (+ (* (a 0) (b 0)) (* (a 1) (b 1)) (* (a 2) (b 2))))

(defn- look-at [eye center up]
  (let [f (v-norm (v-sub center eye))
        s (v-norm (v-cross f up))
        u (v-cross s f)
        o (m4)]
    (aset o 0 (s 0)) (aset o 4 (s 1)) (aset o 8 (s 2))
    (aset o 1 (u 0)) (aset o 5 (u 1)) (aset o 9 (u 2))
    (aset o 2 (- (f 0))) (aset o 6 (- (f 1))) (aset o 10 (- (f 2)))
    (aset o 12 (- (v-dot s eye))) (aset o 13 (- (v-dot u eye))) (aset o 14 (v-dot f eye))
    (aset o 15 1.0) o))

(defn view-projection
  "eye/target [x y z], aspect ratio -> a column-major mat4 Float32Array (wgpu convention)."
  [eye target aspect]
  (m4-mul (perspective (/ js/Math.PI 3.0) aspect 0.05 100.0) (look-at eye target [0 1 0])))

;; --- the shader: morph blend (glTF POSITION-delta convention) then optional
;; 4-joint linear-blend skinning, in one vertex stage; flat single-color
;; N.L-lit fragment (this is a correctness demo, not a material system) ------

(def SHADER
  "struct Uniforms {
  mvp: mat4x4<f32>,
  color: vec4<f32>,
  color_b: vec4<f32>,
  vertex_count: u32,
  morph_count: u32,
  joint_count: u32,
  pattern_kind: u32,
  pattern_params: vec4<f32>,
};

@group(0) @binding(0) var<uniform> u: Uniforms;
@group(0) @binding(1) var<storage, read> morph_deltas: array<vec4<f32>>;
@group(0) @binding(2) var<storage, read> morph_weights: array<f32>;
@group(0) @binding(3) var<storage, read> joint_matrices: array<mat4x4<f32>>;

struct VertexOut {
  @builtin(position) clip: vec4<f32>,
  @location(0) n: vec3<f32>,
  @location(1) local_pos: vec3<f32>,
};

@vertex
fn vs(
  @location(0) position: vec3<f32>,
  @location(1) normal: vec3<f32>,
  @location(2) joints: vec4<u32>,
  @location(3) weights: vec4<f32>,
  @builtin(vertex_index) vidx: u32
) -> VertexOut {
  var pos = position;
  for (var t: u32 = 0u; t < u.morph_count; t = t + 1u) {
    let w = morph_weights[t];
    if (w != 0.0) {
      let d = morph_deltas[t * u.vertex_count + vidx];
      pos = pos + d.xyz * w;
    }
  }
  var world = vec4<f32>(pos, 1.0);
  if (u.joint_count > 0u) {
    let base = vec4<f32>(pos, 1.0);
    var acc = vec4<f32>(0.0, 0.0, 0.0, 0.0);
    for (var j: u32 = 0u; j < 4u; j = j + 1u) {
      let jm = joint_matrices[joints[j]];
      acc = acc + (jm * base) * weights[j];
    }
    world = acc;
  }
  var out: VertexOut;
  out.clip = u.mvp * world;
  out.n = normal;
  out.local_pos = position;
  return out;
}

// pattern_kind: 0 = flat (color only, color_b unused — the pre-existing
// behaviour, byte-identical). 1 = linear gradient (color->color_b along
// pattern_params.xyz, a local-space axis; .w is the half-range that maps to
// t=0..1). 2 = radial gradient (color at pattern_params.xyz, a local-space
// center point, fading to color_b at pattern_params.w, the radius — the
// scar/tattoo edge-fade case). 3 = stripes (hard color/color_b bands along
// pattern_params.xyz at frequency pattern_params.w).
fn pattern_t(local_pos: vec3<f32>) -> f32 {
  if (u.pattern_kind == 1u) {
    let axis = normalize(u.pattern_params.xyz);
    let range = max(u.pattern_params.w, 1e-4);
    return clamp(dot(local_pos, axis) / range * 0.5 + 0.5, 0.0, 1.0);
  } else if (u.pattern_kind == 2u) {
    let center = u.pattern_params.xyz;
    let radius = max(u.pattern_params.w, 1e-4);
    return clamp(length(local_pos - center) / radius, 0.0, 1.0);
  } else if (u.pattern_kind == 3u) {
    let axis = normalize(u.pattern_params.xyz);
    let freq = u.pattern_params.w;
    return step(0.0, sin(dot(local_pos, axis) * freq));
  }
  return 0.0;
}

@fragment
fn fs(in: VertexOut) -> @location(0) vec4<f32> {
  let light_dir = normalize(vec3<f32>(0.4, 0.8, 0.5));
  let ndl = max(dot(normalize(in.n), light_dir), 0.0);
  let ambient = 0.35;
  let lit = ambient + ndl * (1.0 - ambient);
  let t = pattern_t(in.local_pos);
  let base_color = mix(u.color.rgb, u.color_b.rgb, t);
  return vec4<f32>(base_color * lit, 1.0);
}
")

(def ^:private VERTEX-STRIDE 56) ;; pos(12) + normal(12) + joints(16, uint32x4) + weights(16)

(defn init!
  "Build the skin/morph mesh pipeline once (canvas already configured for
  WebGPU by the caller — reuses the same `device`/`ctx`/`fmt` a `kami.webgpu/
  init!` context already produced, so both executors can draw into the same
  canvas/frame if desired). Returns the pipeline context."
  [device fmt]
  (let [mod (.createShaderModule device #js {:code SHADER})
        pipe (.createRenderPipeline device
               #js {:layout "auto"
                    :vertex #js {:module mod :entryPoint "vs"
                                 :buffers #js [#js {:arrayStride VERTEX-STRIDE
                                                     :attributes
                                                     #js [#js {:format "float32x3" :offset 0 :shaderLocation 0}
                                                          #js {:format "float32x3" :offset 12 :shaderLocation 1}
                                                          #js {:format "uint32x4" :offset 24 :shaderLocation 2}
                                                          #js {:format "float32x4" :offset 40 :shaderLocation 3}]}]}
                    :fragment #js {:module mod :entryPoint "fs" :targets #js [#js {:format fmt}]}
                    :primitive #js {:cullMode "none"}
                    :depthStencil #js {:format "depth24plus" :depthWriteEnabled true :depthCompare "less"}})]
    {:device device :pipe pipe}))

(defn- f32 [xs] (js/Float32Array. (clj->js (vec xs))))

(defn upload-mesh!
  "`{:positions :normals :indices :morph-target-deltas :joints :weights}` (all
  optional except `:positions`/`:normals`/`:indices`; `:joints`/`:weights` are
  per-vertex `[j0 j1 j2 j3]`/`[w0 w1 w2 w3]`, omit both for an unskinned mesh;
  `:morph-target-deltas` is `[[[dx dy dz] ...] ...]`, one seq of per-vertex
  deltas per target, omit for no morphs) -> GPU buffer handles + counts.
  Storage buffers are always allocated (min size, if the mesh has none) since
  WebGPU rejects zero-byte buffers."
  [{:keys [device]} {:keys [positions normals indices morph-target-deltas joints weights]}]
  (let [U js/GPUBufferUsage
        vcount (count positions)
        has-skin? (and (seq joints) (seq weights))
        interleaved (js/Float32Array. (* vcount (/ VERTEX-STRIDE 4)))
        joints-view (js/Uint32Array. (.-buffer interleaved))]
    (dotimes [i vcount]
      (let [base (* i 14) ;; 14 = 56 bytes / 4
            p (nth positions i) n (nth normals i)
            j (if has-skin? (nth joints i) [0 0 0 0])
            w (if has-skin? (nth weights i) [1.0 0.0 0.0 0.0])]
        (.set interleaved (f32 p) base)
        (.set interleaved (f32 n) (+ base 3))
        (.set joints-view (js/Uint32Array. (clj->js (vec j))) (+ base 6))
        (.set interleaved (f32 w) (+ base 10))))
    (let [mkbuf (fn [data usage]
                  (let [b (.createBuffer device #js {:size (.-byteLength data) :usage (bit-or usage (.-COPY_DST U))})]
                    (.writeBuffer (.-queue device) b 0 data) b))
          vbuf (mkbuf interleaved (.-VERTEX U))
          ibuf (mkbuf (js/Uint32Array. (clj->js (vec indices))) (.-INDEX U))
          morph-count (count morph-target-deltas)
          morph-flat (if (pos? morph-count)
                       (f32 (mapcat (fn [target] (mapcat (fn [d] (conj (vec d) 0.0)) target)) morph-target-deltas))
                       (js/Float32Array. 4))
          morph-deltas-buf (mkbuf morph-flat (.-STORAGE U))
          morph-weights-buf (.createBuffer device #js {:size (max 4 (* 4 (max 1 morph-count)))
                                                        :usage (bit-or (.-STORAGE U) (.-COPY_DST U))})
          joint-count (if has-skin? (inc (apply max 0 (mapcat identity joints))) 0)
          joint-matrices-buf (.createBuffer device #js {:size (max 64 (* 64 (max 1 joint-count)))
                                                         :usage (bit-or (.-STORAGE U) (.-COPY_DST U))})
          ;; 128 bytes: mvp(64) + color(16) + color_b(16) + 4x u32 counts/
          ;; pattern_kind(16) + pattern_params(16) — see `draw!`'s `gdata`.
          gbuf (.createBuffer device #js {:size 128 :usage (bit-or (.-UNIFORM U) (.-COPY_DST U))})]
      {:vbuf vbuf :ibuf ibuf :idx-count (count indices) :vertex-count vcount
       :morph-count morph-count :morph-deltas-buf morph-deltas-buf :morph-weights-buf morph-weights-buf
       :joint-count joint-count :joint-matrices-buf joint-matrices-buf
       :gbuf gbuf})))

(defn draw!
  "Draw one mesh into `pass` (an already-begun GPURenderPassEncoder, so the
  caller controls the color/depth attachments and clear — this fn only sets
  pipeline/bind-group/buffers and issues the indexed draw). `mvp`: Float32Array
  (16, column-major). `color`: `[r g b]`. `morph-weights`: seq of f32, length
  `:morph-count` (ignored if 0). `joint-matrices`: seq of 16-float column-major
  mat4s, length `:joint-count` (ignored if 0).

  Optional trailing `pattern` map — `{:color-b [r g b] :kind 0|1|2|3
  :params [x y z w]}` — selects a procedural fragment pattern (see the
  shader's own `pattern_t` doc comment for what each `:kind` means).
  Omitting it (every pre-`/loop`-maturity-pass call site) draws exactly as
  before: `:kind` defaults to `0` (flat `color`, `color_b`/`params` unused)."
  ([ctx pass buffers mvp color morph-weights joint-matrices]
   (draw! ctx pass buffers mvp color morph-weights joint-matrices nil))
  ([{:keys [device pipe]} pass
    {:keys [vbuf ibuf idx-count vertex-count morph-count morph-deltas-buf morph-weights-buf
            joint-count joint-matrices-buf gbuf]}
    mvp color morph-weights joint-matrices
    {:keys [color-b kind params] :or {color-b color kind 0 params [0.0 0.0 0.0 0.0]}}]
   (let [q (.-queue device)
         gdata (js/Float32Array. 32)] ;; mvp(16) + color(4) + color_b(4) + counts+kind(4, u32 view) + params(4)
     (.set gdata mvp 0)
     (.set gdata (f32 (conj (vec color) 1.0)) 16)
     (.set gdata (f32 (conj (vec color-b) 1.0)) 20)
     (let [gview (js/Uint32Array. (.-buffer gdata))]
       (aset gview 24 vertex-count) (aset gview 25 morph-count) (aset gview 26 joint-count) (aset gview 27 kind))
     (.set gdata (f32 params) 28)
     (.writeBuffer q gbuf 0 gdata)
    (when (pos? morph-count)
      (.writeBuffer q morph-weights-buf 0 (f32 (take morph-count (concat morph-weights (repeat 0.0))))))
    (when (pos? joint-count)
      (.writeBuffer q joint-matrices-buf 0 (js/Float32Array. (clj->js (vec (mapcat vec (take joint-count joint-matrices)))))))
    (let [bind (.createBindGroup device
                 #js {:layout (.getBindGroupLayout pipe 0)
                      :entries #js [#js {:binding 0 :resource #js {:buffer gbuf}}
                                    #js {:binding 1 :resource #js {:buffer morph-deltas-buf}}
                                    #js {:binding 2 :resource #js {:buffer morph-weights-buf}}
                                    #js {:binding 3 :resource #js {:buffer joint-matrices-buf}}]})]
      (.setPipeline pass pipe)
      (.setBindGroup pass 0 bind)
      (.setVertexBuffer pass 0 vbuf)
      (.setIndexBuffer pass ibuf "uint32")
      (.drawIndexed pass idx-count)))))
