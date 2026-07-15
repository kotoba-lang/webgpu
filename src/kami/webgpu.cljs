(ns kami.webgpu
  "kami-webgpu — a WebGPU renderer driven entirely from CLJ/EDN (hiccup for WebGPU).

   ClojureScript calls the browser WebGPU JS API via w3.webgpu (org-w3-webgpu, the raw
   1:1 spec binding — ADR-2607051400) — no Rust, no wasm, no string marshaling. TWO
   kinds of data drive it:
     • the render GRAPH (shaders, targets, samplers, pipelines, an ordered :passes
       array) — EDN describing HOW a frame is drawn; built once in `init!`. Override it
       with (init! canvas {:graph ...}); reorder/add passes by editing data.
     • the render-IR (globals + instances) — EDN describing WHAT is in the frame; fed to
       `draw!` each requestAnimationFrame.
   The heavy rasterization is the GPU's; CLJS only records light per-frame commands. This
   is the web execution of the same EDN a native Rust/wgpu executor interprets (ADR-0001).
   The render-IR shape + pure constructors live in kami.webgpu.ir (.cljc, cross-platform)."
  (:require [clojure.walk :as walk]
            [kami.webgpu.ir :as ir]
            [kami.webgpu.quality :as quality]
            [kotoba.webgl :as webgl]
            [kotoba.render.environment :as render-environment]
            [kotoba.render.mesh :as render-mesh]
            [kotoba.render.texture :as render-texture]
            [kotoba.shaders :as shaders]
            [w3.webgpu :as w3]))

;; --- column-major mat4 (WebGPU/wgpu convention) ------------------------------

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
(defn- m4-mulN [& ms] (reduce m4-mul ms))

;; Kaizen (net-babiniku co-scientist round 67): `zero-to-one?` (default true, so every
;; existing WebGPU call site is unaffected) selects the clip-space depth convention --
;; WebGPU/D3D want z/w in [0,1] (the original, only-ever-tested behavior here); native
;; WebGL wants the classic OpenGL [-1,1] NDC range. `draw-webgl!` below was calling this
;; SAME fn (0..1 output) and feeding it straight into `gl_Position`, which WebGL's fixed-
;; function clip/viewport/depth-buffer stages always interpret as [-1,1] -- squeezing
;; every object into the back half of the depth range and depth-buffer/near-far behavior
;; that's subtly wrong even where it doesn't outright clip. Found investigating why the
;; WebGL2 fallback's placeholder box render loop (round 66, US-129) put zero pixels on
;; screen. o[10]/o[14] are the only two coefficients that differ between the conventions;
;; everything else (FOV, aspect, o[11]'s perspective-divide setup) is convention-neutral.
(defn- perspective
  ([fovy aspect near far] (perspective fovy aspect near far true))
  ([fovy aspect near far zero-to-one?]
   (let [f (/ 1.0 (js/Math.tan (/ fovy 2.0))) nf (/ 1.0 (- near far)) o (m4)]
     (aset o 0 (/ f aspect)) (aset o 5 f)
     (if zero-to-one?
       (do (aset o 10 (* far nf)) (aset o 14 (* far near nf)))
       (do (aset o 10 (* (+ far near) nf)) (aset o 14 (* 2.0 (* far near) nf))))
     (aset o 11 -1.0) o)))

(defn- ortho [l r b t near far]                ;; RH, depth 0..1 (wgpu) — for the sun
  (let [o (m4)]
    (aset o 0 (/ 2.0 (- r l))) (aset o 5 (/ 2.0 (- t b))) (aset o 10 (/ 1.0 (- near far)))
    (aset o 12 (- (/ (+ r l) (- r l)))) (aset o 13 (- (/ (+ t b) (- t b))))
    (aset o 14 (/ near (- near far))) (aset o 15 1.0) o))

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

(defn- model-mat [[x y z] yaw w h]
  ;; translate(pos + up*h/2) * rotateY(yaw) * scale(w,h,w)
  (let [c (js/Math.cos yaw) s (js/Math.sin yaw)
        t (doto (m4) (aset 0 1)(aset 5 1)(aset 10 1)(aset 15 1)
                     (aset 12 x)(aset 13 (+ y (* h 0.5)))(aset 14 z))
        r (doto (m4) (aset 0 c)(aset 2 (- s))(aset 5 1)(aset 8 s)(aset 10 c)(aset 15 1))
        sc (doto (m4) (aset 0 w)(aset 5 h)(aset 10 w)(aset 15 1))]
    (m4-mulN t r sc)))

;; --- mesh buffers from the shared geometry source ----------------------------
;; The geometry itself is generated by kami.webgpu.geometry (.cljc) — the ONE cross-platform
;; source the native renderer also matches — and only interleaved into GPU buffers here.

(defn- mesh->buffers
  "Interleave position, normal, UV and common-repo generated tangent into the
   PBR vertex format (48 bytes) plus a uint16 index buffer."
  [{:keys [positions normals uvs indices]}]
  (let [flat-pos (vec (mapcat identity positions))
        flat-normal (vec (mapcat identity normals))
        flat-uv (vec (mapcat identity uvs))
        tangents (render-mesh/compute-tangents flat-pos flat-normal flat-uv indices)]
    [(js/Float32Array.
      (clj->js (render-mesh/interleave-with-tangents flat-pos flat-normal flat-uv tangents)))
     (js/Uint16Array. (clj->js (vec indices)))]))

;; The :geo mesh kinds are DATA now (kami.webgpu.ir/default-geometry); the executor bakes each
;; spec with ir/mesh-from-spec at init!. Unit meshes sized to the [w h] footprint the model
;; matrix scales (±0.5 like the cube). Pass {:geometry {…}} to init! to add/override a kind.

;; --- shaders (WGSL is data: referenced by the render graph) -------------------
;; eye (camera world pos) is packed into the spare .w of sun_dir/sun_col/sky. Lighting:
;; hemisphere ambient + Lambert sun + Blinn-Phong specular + Fresnel rim + shadow-map PCF,
;; Reinhard tonemap + gamma. PBR material (metallic/roughness/emissive) is per-instance EDN.
;; the ENTIRE shader is generated from data (kami.shaders/lit-shader via kami.wgsl): struct G,
;; uniform + shadow-map bindings, the PCF shadow fn, the VO varyings, the vertex and the fragment.
;; A bb token-equivalence gate (test/shader_test.clj) pins the generated WGSL to the on-screen-
;; verified original — identical token stream; only redundant grouping parens differ (always valid).
(def SHADER (shaders/cascaded-textured-hdr-shader))

;; depth-only shadow pass: render instances from the sun's POV into the shadow map.
;; the depth-only shadow pass — also generated from data (kami.shaders/shadow-shader).
(def SHADOW-WGSL (shaders/cascaded-shadow-shader 0))

;; --- the render graph, as EDN data -------------------------------------------

(def ^:private HDR-FORMAT
  ;; Packed 32-bit floating-point HDR keeps the linear light range required by
  ;; bloom/ACES while halving render-target bandwidth versus rgba16float. The
  ;; post stack does not use alpha, so the missing alpha channel is intentional.
  "rg11b10ufloat")

(def ^:private HDR-RENDERABLE-FEATURE "rg11b10ufloat-renderable")

(defn- resolve-hdr-graph [graph packed-hdr?]
  (if packed-hdr?
    graph
    (walk/postwalk-replace {HDR-FORMAT "rgba16float"} graph)))

(def default-graph
  "The frame, described as data. :passes is an ordered array — the shadow pass renders
   depth into the :shadow target, then the main pass draws to the screen sampling it.
   Reorder/add passes, swap shaders, or retarget by editing this map (or pass {:graph ...}
   to init!). Each pipeline's :binds wires its group-0 resources by name."
  {:shaders   {:lit SHADER :lit-direct (shaders/cascaded-textured-lit-shader)
               :bloom (shaders/bloom-shader)
               :composite (shaders/hdr-composite-shader)
               :depth0 (shaders/cascaded-shadow-shader 0)
               :depth1 (shaders/cascaded-shadow-shader 1)
               :depth2 (shaders/cascaded-shadow-shader 2)
               :depth3 (shaders/cascaded-shadow-shader 3)}
   :targets   {:shadow {:depth "depth32float" :size [2048 2048 4] :layers 4}
               ;; Internal dynamic-resolution tier: shade the 3D scene at half
               ;; linear resolution, extract bloom at quarter resolution, then
               ;; composite/ACES into the full-resolution swapchain.
               :hdr {:color HDR-FORMAT :scale 0.5}
               :bloom {:color HDR-FORMAT :scale 0.25}}
   :samplers  {:comparison {:compare "less-equal" :magFilter "linear" :minFilter "linear"}
               :linear {:magFilter "linear" :minFilter "linear"}
               :material {:magFilter "linear" :minFilter "linear" :mipmapFilter "linear"
                          :maxAnisotropy 8
                          :addressModeU "repeat" :addressModeV "repeat"}}
   :pipelines {:shadow0 {:shader :depth0 :cull "back"
                         :depth {:format "depth32float" :write true :compare "less"}
                         :binds [:uniform]}
               :shadow1 {:shader :depth1 :cull "back"
                         :depth {:format "depth32float" :write true :compare "less"}
                         :binds [:uniform]}
               :shadow2 {:shader :depth2 :cull "back"
                         :depth {:format "depth32float" :write true :compare "less"}
                         :binds [:uniform]}
               :shadow3 {:shader :depth3 :cull "back"
                         :depth {:format "depth32float" :write true :compare "less"}
                         :binds [:uniform]}
               :main   {:shader :lit :cull "back" :color HDR-FORMAT
                        :depth {:format "depth24plus" :write true :compare "less-equal"}
                        :binds [:uniform {:texture :shadow} {:sampler :comparison}
                                {:texture :albedo} {:texture :normal}
                                {:texture :metallic-roughness} {:sampler :material}
                                {:texture :irradiance} {:texture :prefiltered-specular}
                                {:texture :brdf-lut}]}
               :main-direct {:shader :lit-direct :cull "back" :color :screen
                             :depth {:format "depth24plus" :write true :compare "less-equal"}
                             :binds [:uniform {:texture :shadow} {:sampler :comparison}
                                     {:texture :albedo} {:texture :normal}
                                     {:texture :metallic-roughness} {:sampler :material}
                                     {:texture :irradiance} {:texture :prefiltered-specular}
                                     {:texture :brdf-lut}]}
               :bloom {:shader :bloom :fullscreen true :color HDR-FORMAT
                       :binds [{:texture :hdr} {:sampler :linear}]}
               :composite {:shader :composite :fullscreen true :color :screen
                           :binds [{:texture :hdr} {:texture :bloom} {:sampler :linear}]}}
   :passes    [{:pipeline :shadow0 :depth :shadow :cascade 0 :clear-depth 1.0}
               {:pipeline :shadow1 :depth :shadow :cascade 1 :clear-depth 1.0}
               {:pipeline :shadow2 :depth :shadow :cascade 2 :clear-depth 1.0}
               {:pipeline :shadow3 :depth :shadow :cascade 3 :clear-depth 1.0}
               {:pipeline :main :color :hdr :depth :screen-depth :clear :sky}
               {:pipeline :bloom :color :bloom :clear [0 0 0]}
               {:pipeline :composite :color :screen :clear [0 0 0]}]
   ;; Keep cinematic post-processing for ordinary scenes. At saturation,
   ;; preserve PBR + cascaded shadows + ACES in one direct pass and shed only
   ;; bloom/intermediate-target work that would otherwise back-pressure the GPU.
   :adaptive-post {:max-instances 256
                   :passes [{:pipeline :shadow0 :depth :shadow :cascade 0 :clear-depth 1.0}
                            {:pipeline :main-direct :color :screen :depth :direct-depth :clear :sky}]}})

;; ADR-2607100100 M6: this used to be a hard cap — draw! silently `take`-ing
;; the first MAX-INST instances and dropping the rest, a correctness gap for
;; genuinely large (city-scale) scenes, not a perf one (that's M4). It's now
;; only the STARTING allocation size for the GPU instance buffer; draw! grows
;; it (doubling) via [[ensure-inst-buffer!]] when a scene needs more. Kept at
;; the same value so typical scenes (<16384 instances) still pay zero extra
;; buffer churn versus before this fix.
(def ^:private INITIAL-INST-CAPACITY 16384)
(def ^:private INST-STRIDE 96)   ;; bytes/instance: model(16)+color(4)+material(4) floats

(defn- mk-inst-buffer [device capacity]
  (w3/create-buffer! device #js {:size (* capacity INST-STRIDE)
                                 :usage (bit-or (w3/buffer-usage :vertex) (w3/buffer-usage :copy-dst))}))

(defn- ensure-inst-buffer!
  "Grow `inst-buffer` (an atom of {:buf :capacity}) to hold at least `needed`
  instances, doubling from its current capacity (amortized-growth, same idea
  as a dynamic array) rather than resizing to the exact count every time an
  edge case adds one more instance. Destroys the old GPU buffer before
  replacing it. Returns {:buf <current-or-new buffer> :grew? bool} — callers
  use `grew?` to force a re-upload even on an otherwise-cached instance list,
  since a freshly (re)created buffer's contents are undefined until written."
  [inst-buffer device needed]
  (let [{:keys [buf capacity]} @inst-buffer]
    (if (<= needed capacity)
      {:buf buf :grew? false}
      (let [capacity' (loop [c (max capacity 1)] (if (>= c needed) c (recur (* c 2))))
            buf' (mk-inst-buffer device capacity')]
        (w3/destroy-buffer! buf)
        (reset! inst-buffer {:buf buf' :capacity capacity'})
        {:buf buf' :grew? true}))))

(defn- vattr [fmt off loc] #js {:format fmt :offset off :shaderLocation loc})
(defn- vlayout []   ;; mesh(pos+normal+uv+tangent, stride 48) + instance(..., stride 96)
  #js [#js {:arrayStride 48
            :attributes #js [(vattr "float32x3" 0 0) (vattr "float32x3" 12 1)
                             (vattr "float32x2" 24 8) (vattr "float32x4" 32 9)]}
       #js {:arrayStride 96 :stepMode "instance"
            :attributes #js [(vattr "float32x4" 0 2) (vattr "float32x4" 16 3) (vattr "float32x4" 32 4)
                             (vattr "float32x4" 48 5) (vattr "float32x4" 64 6) (vattr "float32x4" 80 7)]}])

(defn- build-pipeline [device fmt shaders {:keys [shader cull depth color fullscreen]}]
  (let [mod (w3/create-shader-module! device #js {:code (get shaders shader)})
        desc #js {:layout "auto"
                  :vertex #js {:module mod :entryPoint "vs"
                               :buffers (if fullscreen #js [] (vlayout))}
                  :primitive #js {:cullMode (if fullscreen "none" (or cull "back"))}}]
    (when depth
      (set! (.-depthStencil desc)
            #js {:format (:format depth) :depthWriteEnabled (boolean (:write depth))
                 :depthCompare (:compare depth)}))
    (when color   ;; no :color → depth-only pipeline (shadow pass)
      (set! (.-fragment desc) #js {:module mod :entryPoint "fs"
                                   :targets #js [#js {:format (if (= color :screen) fmt color)}]}))
    (w3/create-render-pipeline! device desc)))

(defn- build-bind   ;; wire group-0 entries from the pipeline's :binds vector (EDN)
  [device pipe gbuf targets samplers binds]
  (w3/create-bind-group! device
    #js {:layout (w3/get-bind-group-layout pipe 0)
         :entries (into-array
                    (map-indexed
                      (fn [i b]
                        (cond
                          (= b :uniform) #js {:binding i :resource #js {:buffer gbuf}}
                          (:texture b)   #js {:binding i :resource (get-in targets [(:texture b) :view])}
                          (:sampler b)   #js {:binding i :resource (get samplers (:sampler b))}))
                      binds))}))

(defn- upload-rgba8-texture-array!
  "Upload one PBR channel across all material layers with complete mip chains."
  [device queue descriptors]
  (let [{array-width :width array-height :height array-space :color-space}
        (first descriptors)
        _ (doseq [{:keys [schema width height color-space] :as descriptor} descriptors]
            (when-not (and (= schema :kotoba.render/texture-rgba8-v1)
                           (= array-width width) (= array-height height)
                           (= array-space color-space))
              (throw (ex-info "incompatible PBR texture-array layer"
                              {:descriptor descriptor}))))
        layer-count (count descriptors)
        format (if (= array-space :srgb) "rgba8unorm-srgb" "rgba8unorm")
        mip-level-count (render-texture/mip-level-count array-width array-height)
        tex (w3/create-texture!
             device #js {:size #js [array-width array-height layer-count]
                         :mipLevelCount mip-level-count
                         :format format
                         :usage (bit-or (w3/texture-usage :texture-binding)
                                        (w3/texture-usage :copy-dst))})]
    (doseq [[layer descriptor] (map-indexed vector descriptors)
            {:keys [level width height data]}
            (into [{:level 0 :width array-width :height array-height :data (:data descriptor)}]
                  (render-texture/generate-mipmaps-cpu
                   (:data descriptor) array-width array-height mip-level-count))]
      (w3/write-texture! queue #js {:texture tex :mipLevel level
                                    :origin #js [0 0 layer]}
                         (js/Uint8Array. (clj->js data))
                         #js {:offset 0 :bytesPerRow (* width 4) :rowsPerImage height}
                         #js [width height 1]))
    {:tex tex :view (w3/create-view tex #js {:dimension "2d-array"
                                             :arrayLayerCount layer-count})
     :format format :width array-width :height array-height :layer-count layer-count
     :mip-level-count mip-level-count}))

(defn- upload-rgba8-cube!
  [device queue {:keys [schema color-space levels]}]
  (when-not (= schema :kotoba.render/cube-rgba8-v1)
    (throw (ex-info "unsupported environment cube descriptor" {:schema schema})))
  (let [base-size (:size (first levels))
        format (if (= color-space :srgb) "rgba8unorm-srgb" "rgba8unorm")
        tex (w3/create-texture!
             device #js {:size #js [base-size base-size 6]
                         :mipLevelCount (count levels)
                         :format format
                         :usage (bit-or (w3/texture-usage :texture-binding)
                                        (w3/texture-usage :copy-dst))})]
    (doseq [[level-index {:keys [size faces]}] (map-indexed vector levels)
            [face-index face] (map-indexed vector render-environment/cube-faces)]
      (w3/write-texture! queue #js {:texture tex :mipLevel level-index
                                    :origin #js [0 0 face-index]}
                         (js/Uint8Array. (clj->js (get faces face)))
                         #js {:offset 0 :bytesPerRow (* size 4) :rowsPerImage size}
                         #js [size size 1]))
    {:tex tex :view (w3/create-view tex #js {:dimension "cube"})
     :format format :size base-size :mip-level-count (count levels)}))

(defn- upload-rgba8-2d!
  [device queue {:keys [schema width height data color-space]}]
  (when-not (= schema :kotoba.render/texture-rgba8-v1)
    (throw (ex-info "unsupported 2D texture descriptor" {:schema schema})))
  (let [format (if (= color-space :srgb) "rgba8unorm-srgb" "rgba8unorm")
        tex (w3/create-texture!
             device #js {:size #js [width height 1] :format format
                         :usage (bit-or (w3/texture-usage :texture-binding)
                                        (w3/texture-usage :copy-dst))})]
    (w3/write-texture! queue #js {:texture tex}
                       (js/Uint8Array. (clj->js data))
                       #js {:offset 0 :bytesPerRow (* width 4) :rowsPerImage height}
                       #js [width height 1])
    {:tex tex :view (w3/create-view tex) :format format
     :width width :height height}))

(defn- init-webgl-fallback! [canvas opts]
  (if-let [viewport (webgl/init-mesh-viewport! canvas)]
    (let [geom-specs (merge ir/default-geometry (:geometry opts))
          geos (reduce-kv (fn [result kind spec]
                            (assoc result kind (webgl/upload-mesh! viewport (ir/mesh-from-spec spec))))
                          {} geom-specs)]
      (js/Promise.resolve (assoc viewport :geos geos :instance-cache (atom nil))))
    (js/Promise.reject (js/Error. "Neither WebGPU nor WebGL2 is available"))))

(defn init!
  "Set up WebGPU on the canvas once from the render graph. Returns a Promise of a context.
   opts (optional): {:graph <render-graph EDN>
                     :quality-plan <:kotoba.render/quality-v1 EDN>
                     :material-textures {:albedo/:normal/:metallic-roughness
                                         <:kotoba.render/texture-rgba8-v1>}
                     :material-texture-sets [<material texture set> ...]
                     :environment <:kotoba.render/pbr-environment-v1>
                     :adapter-options <GPURequestAdapterOptions JS object>
                     :geometry <{:geo-kw {:type … params}} EDN>} —
   default to default-graph / ir/default-geometry (a {:geometry …} override is merged over it)."
  ([canvas] (init! canvas nil))
  ([canvas opts]
   (if-not (w3/supported?)
     (init-webgl-fallback! canvas opts)
     (-> (w3/request-adapter! (:adapter-options opts))
         (.then (fn [adapter]
                  (if-not adapter
                    (js/Promise.reject (js/Error. "No WebGPU adapter available"))
                    (let [packed-hdr? (.has (.-features adapter) HDR-RENDERABLE-FEATURE)
                          descriptor (when packed-hdr?
                                       #js {:requiredFeatures #js [HDR-RENDERABLE-FEATURE]})]
                      (-> (w3/request-device! adapter descriptor)
                          (.then (fn [device]
                                   {:device device :packed-hdr? packed-hdr?})))))))
         (.then
           (fn [{:keys [device packed-hdr?]}]
             (let [gpu-errors (atom [])
                   _ (set! (.-onuncapturederror device)
                           (fn [event]
                             (swap! gpu-errors conj
                                    (str (some-> event .-error .-message)))))
                   graph-base (resolve-hdr-graph (or (:graph opts) default-graph)
                                                 packed-hdr?)
                   quality-resolution (when-let [plan (:quality-plan opts)]
                                        (quality/resolve-plan graph-base plan))
                   graph (or (:graph quality-resolution) graph-base)
                   w (max 1 (.-clientWidth canvas)) h (max 1 (.-clientHeight canvas))
                   _ (set! (.-width canvas) w)
                   _ (set! (.-height canvas) h)
                   ctx (w3/get-context canvas)
                   fmt (w3/preferred-canvas-format)
                   q (.-queue device)
                   ;; writeBuffer REQUIRES COPY_DST or it silently no-ops → zero buffers.
                   mkbuf (fn [data usage]
                           (let [b (w3/create-buffer! device #js {:size (.-byteLength data)
                                                                  :usage (bit-or usage (w3/buffer-usage :copy-dst))})]
                             (w3/write-buffer! q b data) b))
                   ;; one vertex+index buffer per geometry kind, baked from EDN specs
                   ;; (ir/default-geometry + any {:geometry …} override); :geo picks a kind.
                   geom-specs (merge ir/default-geometry (:geometry opts))
                   geos (reduce-kv (fn [acc k spec]
                                     (let [[v i] (mesh->buffers (ir/mesh-from-spec spec))]
                                       (assoc acc k {:vbuf (mkbuf v (w3/buffer-usage :vertex))
                                                     :ibuf (mkbuf i (w3/buffer-usage :index))
                                                     :idx-count (.-length i)})))
                                   {} geom-specs)
                   box (:box geos)
                   inst-buffer (atom {:buf (mk-inst-buffer device INITIAL-INST-CAPACITY) :capacity INITIAL-INST-CAPACITY})
                   gbuf (w3/create-buffer! device #js {:size 448 :usage (bit-or (w3/buffer-usage :uniform) (w3/buffer-usage :copy-dst))})
                   ;; samplers from EDN
                   samplers (reduce-kv (fn [m k s] (assoc m k (w3/create-sampler! device (clj->js s)))) {} (:samplers graph))
                   ;; offscreen targets from EDN (RENDER_ATTACHMENT + sampleable) + implicit screen-depth
                   targets (reduce-kv
                             (fn [m k {:keys [depth color size layers scale]}]
                               (let [[sw sh size-layers] (or size [w h])
                                     tw (max 1 (int (* sw (or scale 1.0))))
                                     th (max 1 (int (* sh (or scale 1.0))))
                                     layers (or layers size-layers 1)
                                     f (or depth color)
                                     tex (w3/create-texture! device #js {:size #js [tw th layers] :format f
                                                                         :usage (bit-or (w3/texture-usage :render-attachment) (w3/texture-usage :texture-binding))})
                                     view (if (> layers 1)
                                            (w3/create-view tex #js {:dimension "2d-array" :baseArrayLayer 0
                                                                     :arrayLayerCount layers})
                                            (w3/create-view tex))
                                     layer-views (when (> layers 1)
                                                   (mapv #(w3/create-view tex #js {:dimension "2d"
                                                                                  :baseArrayLayer %
                                                                                  :arrayLayerCount 1})
                                                         (range layers)))]
                                 (assoc m k {:tex tex :view view :layer-views layer-views
                                             :width tw :height th :layers layers :format f})))
                             {} (:targets graph))
                   ;; The depth attachment paired with the main HDR pass must
                   ;; exactly match its scaled color attachment dimensions.
                   main-target (get targets :hdr)
                   depth-w (or (:width main-target) w)
                   depth-h (or (:height main-target) h)
                   sdepth (w3/create-texture! device #js {:size #js [depth-w depth-h] :format "depth24plus" :usage (w3/texture-usage :render-attachment)})
                   targets (assoc targets :screen-depth {:view (w3/create-view sdepth)
                                                         :width depth-w :height depth-h
                                                         :format "depth24plus"})
                   direct-depth (w3/create-texture! device #js {:size #js [w h] :format "depth24plus" :usage (w3/texture-usage :render-attachment)})
                   targets (assoc targets :direct-depth {:view (w3/create-view direct-depth)
                                                         :width w :height h :format "depth24plus"})
                   texture-library (render-texture/pbr-texture-library
                                    (or (:material-texture-sets opts)
                                        (:material-textures opts)))
                   material-resources
                   (into {}
                         (for [kind [:albedo :normal :metallic-roughness]]
                           [kind (upload-rgba8-texture-array!
                                  device q (mapv kind texture-library))]))
                   environment (render-environment/pbr-environment
                                (or (:environment opts)
                                    render-environment/neutral-pbr-environment))
                   environment-resources
                   {:irradiance (upload-rgba8-cube! device q (:irradiance environment))
                    :prefiltered-specular
                    (upload-rgba8-cube! device q (:prefiltered-specular environment))
                    :brdf-lut (upload-rgba8-2d! device q (:brdf-lut environment))}
                   targets (merge targets material-resources environment-resources)
                   ;; pipelines + bind groups, from EDN
                   pipelines (reduce-kv
                               (fn [m k pd]
                                 (let [pipe (build-pipeline device fmt (:shaders graph) pd)]
                                   (assoc m k {:pipe pipe
                                               :fullscreen (:fullscreen pd)
                                               :bind (build-bind device pipe gbuf targets samplers (:binds pd))})))
                               {} (:pipelines graph))]
               (w3/configure-context! ctx #js {:device device :format fmt :alphaMode "opaque"})
               {:backend :webgpu :device device :queue q :ctx ctx :fmt fmt :w w :h h
                :gpu-errors gpu-errors
                :adapter-options (:adapter-options opts)
                :hdr-format (if packed-hdr? HDR-FORMAT "rgba16float")
                :packed-hdr-feature? packed-hdr?
                :vbuf (:vbuf box) :ibuf (:ibuf box) :inst-buffer inst-buffer :gbuf gbuf :idx-count (:idx-count box)
                :geos geos
                :targets targets :pipelines pipelines :graph graph
                :material-textures (into {}
                                         (map (fn [[kind resource]]
                                                [kind (select-keys resource [:format :width :height
                                                                             :mip-level-count
                                                                             :layer-count])]))
                                         material-resources)
                :environment (into {}
                                   (map (fn [[kind resource]]
                                          [kind (select-keys resource
                                                             [:format :size :width :height
                                                              :mip-level-count])]))
                                   environment-resources)
                :quality-resolution quality-resolution
                :density-evidence (atom nil)
                :post-evidence (atom nil)
                :lod-state (atom {}) :lod-evidence (atom nil)
                ;; ADR-2607100100 M4 investigation: a static scene's :instances is the
                ;; SAME value (by reference) across draw! calls in normal use (compose
                ;; the render-IR once, call draw! every rAF) — caching the sort/group/
                ;; marshal-to-Float32Array/GPU-upload work keyed on that reference turns
                ;; a static scene's per-frame cost from O(instance count) back to O(1).
                ;; See [[compute-instance-data]]/[[draw!]].
                :instance-cache (atom nil)})))
         (.catch (fn [error]
                   (js/console.error "WebGPU initialization failed; using WebGL2" error)
                   (-> (init-webgl-fallback! canvas opts)
                       (.then #(assoc % :webgpu-init-error (str error))))))))))

(defn- arr3 [m k d] (or (get m k) d))

(defn- pack-instance!
  "Write one instance directly into the shared Float32Array. This is the
  closed-form T*Ry*S matrix used by model-mat, avoiding three temporary
  matrices, two matrix multiplies, and two clj->js arrays per instance."
  [idata i {:keys [pos color size yaw metallic roughness emissive textured? texture-layer]}]
  (let [[x y z] pos
        [w h] (or size [1 1])
        yaw (or yaw 0)
        c (js/Math.cos yaw)
        s (js/Math.sin yaw)
        base (* i 24)]
    ;; column-major translate(pos + up*h/2) * rotateY(yaw) * scale(w,h,w)
    (aset idata base (* c w))
    (aset idata (+ base 1) 0)
    (aset idata (+ base 2) (* (- s) w))
    (aset idata (+ base 3) 0)
    (aset idata (+ base 4) 0)
    (aset idata (+ base 5) h)
    (aset idata (+ base 6) 0)
    (aset idata (+ base 7) 0)
    (aset idata (+ base 8) (* s w))
    (aset idata (+ base 9) 0)
    (aset idata (+ base 10) (* c w))
    (aset idata (+ base 11) 0)
    (aset idata (+ base 12) x)
    (aset idata (+ base 13) (+ y (* h 0.5)))
    (aset idata (+ base 14) z)
    (aset idata (+ base 15) 1)
    (aset idata (+ base 16) (color 0))
    (aset idata (+ base 17) (color 1))
    (aset idata (+ base 18) (color 2))
    (aset idata (+ base 19) 1)
    (aset idata (+ base 20) (or metallic 0.0))
    (aset idata (+ base 21) (or roughness 0.65))
    (aset idata (+ base 22) (or emissive 0.0))
    (aset idata (+ base 23) (cond
                              (some? texture-layer) (double (inc texture-layer))
                              textured? 1.0
                              :else 0.0))))

(defn- compute-instance-data
  "Pure: bucket instances by geometry kind (so each kind's instances are
  contiguous → one instanced draw per kind via a firstInstance offset —
  depth is handled by the z-buffer, so draw order is free), group them,
  compute the XZ centroid (the default-camera fallback point), and
  marshal the packed per-instance Float32Array (model matrix + color +
  material — everything [[draw!]] needs that depends ONLY on
  `raw-instances`, nothing on camera/lighting/sky). Factored out so
  `draw!` can cache it across frames when the instance list hasn't
  changed — see the `:instance-cache` note on [[init!]]. No count limit here
  — [[ensure-inst-buffer!]] grows the GPU buffer to fit however many
  `raw-instances` actually has (ADR-2607100100 M6; this used to silently
  `take MAX-INST` and drop the rest)."
  [raw-instances]
  (let [buckets (js/Map.)
        ;; Bucket and accumulate the camera centroid in one O(n) source pass.
        ;; JS arrays are intentional here: transient mutable buckets avoid a
        ;; persistent-vector allocation for every entity in a dynamic frame.
        cz (reduce (fn [[sx sz] {:keys [pos] :as instance}]
                     (let [geo (or (:geo instance) :box)
                           bucket (or (.get buckets geo)
                                      (let [fresh #js []]
                                        (.set buckets geo fresh)
                                        fresh))]
                       (.push bucket instance)
                       [(+ sx (pos 0)) (+ sz (pos 2))]))
                   [0 0] raw-instances)
        geo-keys (js/Array.from (.keys buckets))
        _ (.sort geo-keys (fn [a b] (.localeCompare (name a) (name b))))
        insts #js []
        geo-groups
        (loop [i 0 acc []]
          (if (= i (.-length geo-keys))
            acc
            (let [geo (aget geo-keys i)
                  bucket (.get buckets geo)
                  at (.-length insts)
                  c (.-length bucket)]
              (dotimes [j c] (.push insts (aget bucket j)))
              (recur (inc i) (conj acc [geo c at])))))
        n (max 1 (count insts))
        [cxx czz] [(/ (cz 0) n) (/ (cz 1) n)]
        idata (js/Float32Array. (* (count insts) 24))]   ;; model(16)+color(4)+material(4)
    (dotimes [i (count insts)]
      (pack-instance! idata i (nth insts i)))
    {:raw-instances raw-instances :insts insts :geo-groups geo-groups :cxx cxx :czz czz :idata idata}))

(defn- draw-webgpu!
  "Draw one frame from a render-IR map: {:globals {:sky {:horizon :sun-dir :sun} :eye
   :target} :instances [{:pos :color :size :yaw :metallic :roughness :emissive}]}. Runs
   the graph's :passes in order. Synchronous; no wasm.

   Caches the sort/group/marshal-to-buffer work in `instance-cache` (see
   [[init!]]) keyed on `(:instances ir)` BY REFERENCE (`identical?`, not
   `=` — cheap, and correct for the common case of composing a render-IR
   once and calling `draw!` every rAF with that same value; a caller that
   rebuilds an equal-but-not-identical instances vector every frame just
   won't hit the cache, not a correctness issue). Only re-uploads the GPU
   instance buffer when the cache actually misses — ADR-2607100100's M4
   investigation measured a static scene's per-frame cost scaling with
   instance count purely from this redundant re-sort/re-marshal/re-upload
   (~36ms/frame at 20k instances), not from any GPU-side rendering limit.

   The GPU instance buffer itself grows (doubling) to fit however many
   instances the scene actually has — see [[ensure-inst-buffer!]] — rather
   than silently dropping any past a fixed cap (ADR-2607100100 M6)."
  [{:keys [device queue ctx w h vbuf ibuf inst-buffer gbuf idx-count targets pipelines graph
           geos instance-cache quality-resolution density-evidence lod-state lod-evidence post-evidence]} ir]
  (let [raw-instances (:instances ir)
        lod (get-in quality-resolution [:effective :lod])
        authored-eye (get-in ir [:globals :eye])
        fov (or (get-in ir [:globals :fov]) 60)
        lod-result (if lod
                     (quality/apply-lod raw-instances authored-eye
                                        (/ (* fov js/Math.PI) 180.0) h
                                        (:bias lod) @lod-state)
                     {:instances raw-instances :state @lod-state
                      :switched-count 0 :levels {}})
        _ (reset! lod-state (:state lod-result))
        _ (some-> lod-evidence
                  (reset! (select-keys lod-result [:switched-count :levels])))
        lod-instances (:instances lod-result)
        density (if lod
                  (quality/density-plan lod-instances authored-eye lod)
                  {:instances lod-instances :input-count (count lod-instances)
                   :visible-count (count lod-instances) :culled-count 0
                   :budget-applied? false})
        selected-instances (:instances density)
        density-key [authored-eye fov h lod (:state lod-result)]
        _ (some-> density-evidence (reset! (dissoc density :instances)))
        cached (some-> instance-cache deref)
        cache-hit? (and cached
                        (identical? raw-instances (:source-instances cached))
                        (= density-key (:density-key cached)))
        {:keys [insts geo-groups cxx czz idata]}
        (if cache-hit?
          cached
          (let [fresh (assoc (compute-instance-data selected-instances)
                             :source-instances raw-instances
                             :density-key density-key)]
            (some-> instance-cache (reset! fresh))
            fresh))
        g (:globals ir)
        sky (:sky g)
        horizon (arr3 sky :horizon [0.7 0.8 0.9])
        sun-dir (arr3 sky :sun-dir [-0.4 -0.85 -0.35])
        sun (arr3 sky :sun [1 0.96 0.85])
        eye (arr3 g :eye [(+ cxx 60) 80 (+ czz 60)])
        target (arr3 g :target [cxx 0 czz])
        ;; projection as data: FOV (deg) + near/far planes from globals (defaults = the old look)
        pnear (or (:near g) 0.5) pfar (or (:far g) 4000)
        vp (m4-mul (perspective (/ (* fov js/Math.PI) 180.0) (/ w (max 1 h)) pnear pfar)
                   (look-at (vec eye) (vec target) [0 1 0]))
        adaptive-post (:adaptive-post graph)
        post-degraded? (and adaptive-post
                            (> (count insts) (:max-instances adaptive-post)))
        sl (let [l (js/Math.hypot (sun-dir 0) (sun-dir 1) (sun-dir 2)) l (if (< l 1e-6) 1.0 l)]
             [(/ (sun-dir 0) l) (/ (sun-dir 1) l) (/ (sun-dir 2) l)])
        ;; Cascaded sun frusta follow the camera. Each slice uses a conservative
        ;; bounding sphere and its own light matrix; the shader selects by distance.
        shd (ir/shadow (:shadow g))
        authored-splits (get-in quality-resolution [:effective :shadow :splits])
        base-splits (if (seq authored-splits) authored-splits [48.0 128.0 320.0 800.0])
        splits (if post-degraded?
                 [pfar pfar pfar pfar]
                 (loop [xs (vec (take 4 base-splits))]
                   (if (= 4 (count xs)) xs
                     (recur (conj xs (or (peek xs) 800.0))))))
        view-dir (v-norm (v-sub target eye))
        aspect (/ w (max 1 h))
        tan-half (js/Math.tan (/ (* fov js/Math.PI) 360.0))
        light-vps
        (mapv (fn [cascade-far]
                (let [center-distance (* cascade-far 0.55)
                      center [(+ (eye 0) (* (view-dir 0) center-distance))
                              (+ (eye 1) (* (view-dir 1) center-distance))
                              (+ (eye 2) (* (view-dir 2) center-distance))]
                      radius (max 12.0 (* cascade-far tan-half
                                          (js/Math.sqrt (+ 1.0 (* aspect aspect)))))
                      light-distance (+ (:distance shd) (* 2.0 radius))
                      light-eye [(- (center 0) (* (sl 0) light-distance))
                                 (- (center 1) (* (sl 1) light-distance))
                                 (- (center 2) (* (sl 2) light-distance))]]
                  (m4-mul (ortho (- radius) radius (- radius) radius
                                 1.0 (+ light-distance (* 2.0 radius)))
                          (look-at light-eye center [0 1 0]))))
              splits)
        ;; lighting-model coefficients as data: merge the frame's :lighting over the defaults
        ;; (omitting it reproduces the original baked-in constants exactly — parity, no change).
        lt (ir/lighting (:lighting g))
        amb (arr3 lt :ambient [0.20 0.22 0.26])
        gf (js/Float32Array. 112)]
    (.set gf vp 0)
    (.set gf (clj->js [(sun-dir 0) (sun-dir 1) (sun-dir 2) (nth eye 0)]) 16)
    (.set gf (clj->js [(sun 0) (sun 1) (sun 2) (nth eye 1)]) 20)
    (.set gf (clj->js [(horizon 0) (horizon 1) (horizon 2) (nth eye 2)]) 24)
    (doseq [[index light-vp] (map-indexed vector light-vps)]
      (.set gf light-vp (+ 28 (* index 16))))
    (.set gf (clj->js splits) 92)
    ;; light_a = [ambient.rgb, ambient-sky] · light_b = [spec-min spec-max rim rim-power]
    ;; light_c = [shininess-min shininess-max sun-diffuse metallic-diffuse-cut]
    (.set gf (clj->js [(amb 0) (amb 1) (amb 2) (:ambient-sky lt)]) 96)
    (.set gf (clj->js [(:spec-min lt) (:spec-max lt) (:rim lt) (:rim-power lt)]) 100)
    (.set gf (clj->js [(:shininess-min lt) (:shininess-max lt) (:sun-diffuse lt) (:metallic-diffuse-cut lt)]) 104)
    ;; light_d = [gamma, shadow-bias-slope, shadow-bias-min, shadow-texel]
    (.set gf (clj->js [(:gamma lt) (:shadow-bias-slope lt) (:shadow-bias-min lt)
                       (/ 1.0 (or (first (get-in graph [:targets :shadow :size])) 2048.0))]) 108)
    (w3/write-buffer! queue gbuf 0 gf)
    ;; grow the GPU instance buffer first if this frame's instance count
    ;; exceeds its current capacity (ADR-2607100100 M6) — then only
    ;; re-upload on a real cache miss OR a just-grown (contents-undefined)
    ;; buffer. See the :instance-cache note on init! and the draw! docstring.
    (let [{:keys [buf grew?]} (ensure-inst-buffer! inst-buffer device (count insts))]
      (when (or (not cache-hit?) grew?) (w3/write-buffer! queue buf 0 idata))
    (let [enc (w3/create-command-encoder! device)
          ninst (count insts)
          screen-view (w3/create-view (w3/current-texture ctx))
          vw (fn [k layer]
               (if (= k :screen) screen-view
                 (if (some? layer)
                   (get-in targets [k :layer-views layer])
                   (get-in targets [k :view]))))
          frame-passes (if post-degraded? (:passes adaptive-post) (:passes graph))
          _ (some-> post-evidence
                    (reset! {:tier (if post-degraded? :direct-aces :hdr-bloom)
                             :shadow-cascades (if post-degraded? 1 4)
                             :instances ninst
                             :threshold (:max-instances adaptive-post)}))
          draw-geom (fn [p pipe bnd]
                      (when (pos? ninst)
                        (w3/set-pipeline! p pipe) (w3/set-bind-group! p 0 bnd)
                        (w3/set-vertex-buffer! p 1 buf)
                        ;; one instanced draw per geometry kind, offset into the instance buffer
                        (doseq [[gk gcount gfirst] geo-groups]
                          (let [{gv :vbuf gi :ibuf gn :idx-count} (get geos gk (:box geos))]
                            (w3/set-vertex-buffer! p 0 gv)
                            (w3/set-index-buffer! p gi "uint16")
                            (w3/draw-indexed! p gn gcount 0 0 gfirst)))))]
      ;; run the graph's passes in order (EDN-driven)
      (doseq [{:keys [pipeline color depth clear clear-depth cascade]} frame-passes]
        (let [{:keys [pipe bind fullscreen]} (get pipelines pipeline)
              catts (if color
                      (let [c (if (= clear :sky) horizon (or clear [0 0 0]))]
                        #js [#js {:view (vw color nil) :loadOp "clear" :storeOp "store"
                                  :clearValue #js {:r (c 0) :g (c 1) :b (c 2) :a 1}}])
                      #js [])
              pass-desc #js {:colorAttachments catts}
              _ (when depth
                  (set! (.-depthStencilAttachment pass-desc)
                        #js {:view (vw depth cascade) :depthLoadOp "clear"
                             :depthStoreOp "store" :depthClearValue (or clear-depth 1.0)}))
              rp (w3/begin-render-pass! enc pass-desc)]
          (if fullscreen
            (do (w3/set-pipeline! rp pipe)
                (w3/set-bind-group! rp 0 bind)
                (w3/draw! rp 3))
            (draw-geom rp pipe bind))
          (w3/end-pass! rp)))
      (w3/submit! queue [(w3/finish! enc)])))))

(defn post-evidence
  "Last adaptive post-processing decision for profiling/Studio diagnostics."
  [ctx]
  (some-> ctx :post-evidence deref))

(defn material-texture-evidence
  "Uploaded PBR texture formats and dimensions (never includes pixel data)."
  [ctx]
  (:material-textures ctx))

(defn backend-evidence
  "Renderer backend and any WebGPU initialization failure. Capture gates use
   this to prevent a visually plausible WebGL fallback from claiming WebGPU."
  [ctx]
  {:backend (:backend ctx)
   :hdr-format (:hdr-format ctx)
   :packed-hdr-feature? (:packed-hdr-feature? ctx)
   :force-fallback-adapter (boolean (some-> ctx :adapter-options
                                            (aget "forceFallbackAdapter")))
   :gpu-errors (if-let [errors (:gpu-errors ctx)] @errors [])
   :webgpu-init-error (:webgpu-init-error ctx)})

(defn settle!
  "Promise resolved when all commands submitted before this call finish. Normal
   render loops do not wait; deterministic capture uses this as a screenshot
   barrier, which is essential for software WebGPU queues."
  [ctx]
  (if-let [queue (:queue ctx)]
    (.onSubmittedWorkDone queue)
    (js/Promise.resolve nil)))

(defn environment-evidence
  "Uploaded split-sum IBL resource metadata (never pixel data)."
  [ctx]
  (:environment ctx))

(defn- draw-webgl! [{:keys [geos width height instance-cache] :as viewport} render-ir]
  (let [instances (:instances render-ir)
        cached @instance-cache
        cache-hit? (and cached (identical? instances (:instances cached))
                        (= (:globals render-ir) (:globals cached)))
        computed (if cache-hit?
                   cached
                   (let [{:keys [cxx czz]} (compute-instance-data instances)
                         globals (:globals render-ir)
                         eye (arr3 globals :eye [(+ cxx 60) 80 (+ czz 60)])
                         target (arr3 globals :target [cxx 0 czz])
                         fov (or (:fov globals) 60) near (or (:near globals) 0.5) far (or (:far globals) 4000)
                         vp (m4-mul (perspective (/ (* fov js/Math.PI) 180.0) (/ width (max 1 height)) near far false)
                                    (look-at (vec eye) (vec target) [0 1 0]))
                         draws (mapv (fn [{:keys [pos color size yaw geo]}]
                                       {:buffers (get geos (or geo :box) (:box geos))
                                        :mvp (m4-mul vp (model-mat pos (or yaw 0) ((or size [1 1]) 0) ((or size [1 1]) 1)))
                                        :color (or color [0.7 0.75 0.82])}) instances)]
                     {:instances instances :globals (:globals render-ir) :draws draws}))
        _ (when-not cache-hit? (reset! instance-cache computed))
        draws (:draws computed)]
    (webgl/render-mesh-scene! viewport draws)))

(defn draw!
  "Draw Render-IR through the backend selected by init!: WebGPU first,
  WebGL2 fallback. Callers keep one backend-neutral contract."
  [ctx render-ir]
  (if (= :webgl2 (:backend ctx)) (draw-webgl! ctx render-ir) (draw-webgpu! ctx render-ir)))

(defn inst-buffer-capacity
  "How many instances the GPU instance buffer currently has room for
  (ADR-2607100100 M6) — an observability hook, not just for tests: lets a
  caller confirm the selected visible set fits the uploaded buffer. The
  quality-plan density evidence distinguishes intentional budget culling from
  capacity loss."
  [ctx]
  (:capacity @(:inst-buffer ctx)))

(defn density-evidence
  "Last WebGPU frame's explicit density-budget result, without instance data."
  [ctx]
  (some-> (:density-evidence ctx) deref))

(defn lod-evidence
  "Last WebGPU frame's selected geometry counts and hysteresis switches."
  [ctx]
  (some-> (:lod-evidence ctx) deref))
