(ns kami.webgpu
  "kami-webgpu — a WebGPU renderer driven entirely from CLJ/EDN (hiccup for WebGPU).

   ClojureScript calls the browser WebGPU JS API directly — no Rust, no wasm, no string
   marshaling. TWO kinds of data drive it:
     • the render GRAPH (shaders, targets, samplers, pipelines, an ordered :passes
       array) — EDN describing HOW a frame is drawn; built once in `init!`. Override it
       with (init! canvas {:graph ...}); reorder/add passes by editing data.
     • the render-IR (globals + instances) — EDN describing WHAT is in the frame; fed to
       `draw!` each requestAnimationFrame.
   The heavy rasterization is the GPU's; CLJS only records light per-frame commands. This
   is the web execution of the same EDN a native Rust/wgpu executor interprets (ADR-0001).
   The render-IR shape + pure constructors live in kami.webgpu.ir (.cljc, cross-platform).")

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

(defn- perspective [fovy aspect near far]
  (let [f (/ 1.0 (js/Math.tan (/ fovy 2.0))) nf (/ 1.0 (- near far)) o (m4)]
    (aset o 0 (/ f aspect)) (aset o 5 f)
    (aset o 10 (* far nf)) (aset o 11 -1.0) (aset o 14 (* far near nf)) o))

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

;; --- cube mesh (pos + normal), 24 verts / 36 indices -------------------------

(def ^:private faces
  [[[0 0 1]  [[-0.5 -0.5 0.5][0.5 -0.5 0.5][0.5 0.5 0.5][-0.5 0.5 0.5]]]
   [[0 0 -1] [[0.5 -0.5 -0.5][-0.5 -0.5 -0.5][-0.5 0.5 -0.5][0.5 0.5 -0.5]]]
   [[1 0 0]  [[0.5 -0.5 0.5][0.5 -0.5 -0.5][0.5 0.5 -0.5][0.5 0.5 0.5]]]
   [[-1 0 0] [[-0.5 -0.5 -0.5][-0.5 -0.5 0.5][-0.5 0.5 0.5][-0.5 0.5 -0.5]]]
   [[0 1 0]  [[-0.5 0.5 0.5][0.5 0.5 0.5][0.5 0.5 -0.5][-0.5 0.5 -0.5]]]
   [[0 -1 0] [[-0.5 -0.5 -0.5][0.5 -0.5 -0.5][0.5 -0.5 0.5][-0.5 -0.5 0.5]]]])

(defn- cube []
  (loop [fs faces, base 0, v [], idx []]
    (if (empty? fs)
      [(js/Float32Array. (clj->js v)) (js/Uint16Array. (clj->js idx))]
      (let [[n quad] (first fs)
            v' (reduce (fn [acc p] (-> acc (into p) (into n))) v quad)
            idx' (into idx (map #(+ base %) [0 1 2 0 2 3]))]
        (recur (rest fs) (+ base 4) v' idx')))))

;; --- shaders (WGSL is data: referenced by the render graph) -------------------
;; eye (camera world pos) is packed into the spare .w of sun_dir/sun_col/sky. Lighting:
;; hemisphere ambient + Lambert sun + Blinn-Phong specular + Fresnel rim + shadow-map PCF,
;; Reinhard tonemap + gamma. PBR material (metallic/roughness/emissive) is per-instance EDN.
(def SHADER "
struct G { vp: mat4x4<f32>, sun_dir: vec4<f32>, sun_col: vec4<f32>, sky: vec4<f32>, light_vp: mat4x4<f32> };
@group(0) @binding(0) var<uniform> g: G;
@group(0) @binding(1) var shadowMap: texture_depth_2d;
@group(0) @binding(2) var shadowSamp: sampler_comparison;
fn shadow(wpos: vec3<f32>, ndl: f32) -> f32 {
  let lc = g.light_vp * vec4<f32>(wpos, 1.0);
  let ndc = lc.xyz / lc.w;
  let uv = vec2<f32>(ndc.x*0.5+0.5, 0.5-ndc.y*0.5);
  if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0 || ndc.z > 1.0) { return 1.0; }
  let bias = max(0.0025*(1.0-ndl), 0.0006);
  let texel = 1.0/2048.0;
  var lit = 0.0;
  for (var dx = -1; dx <= 1; dx++) {
    for (var dy = -1; dy <= 1; dy++) {
      lit += textureSampleCompareLevel(shadowMap, shadowSamp, uv + vec2<f32>(f32(dx),f32(dy))*texel, ndc.z - bias);
    }
  }
  return lit/9.0;
}
struct VO { @builtin(position) clip: vec4<f32>, @location(0) n: vec3<f32>, @location(1) col: vec3<f32>, @location(2) wpos: vec3<f32>, @location(3) mat: vec3<f32> };
@vertex
fn vs(@location(0) pos: vec3<f32>, @location(1) normal: vec3<f32>,
      @location(2) m0: vec4<f32>, @location(3) m1: vec4<f32>, @location(4) m2: vec4<f32>, @location(5) m3: vec4<f32>,
      @location(6) color: vec4<f32>, @location(7) material: vec4<f32>) -> VO {
  let model = mat4x4<f32>(m0, m1, m2, m3);
  let world = model * vec4<f32>(pos, 1.0);
  var o: VO; o.clip = g.vp * world;
  o.n = normalize((model * vec4<f32>(normal, 0.0)).xyz); o.col = color.rgb; o.wpos = world.xyz;
  o.mat = material.xyz; return o;
}
@fragment
fn fs(i: VO) -> @location(0) vec4<f32> {
  let N = normalize(i.n);
  let L = normalize(-g.sun_dir.xyz);
  let eye = vec3<f32>(g.sun_dir.w, g.sun_col.w, g.sky.w);
  let V = normalize(eye - i.wpos);
  let H = normalize(L + V);
  let ndl = max(dot(N, L), 0.0);
  let metallic  = clamp(i.mat.x, 0.0, 1.0);
  let rough     = clamp(i.mat.y, 0.04, 1.0);
  let emissive  = i.mat.z;
  let amb = mix(vec3<f32>(0.20,0.22,0.26), g.sky.rgb*0.65, N.y*0.5+0.5);
  let shininess = mix(4.0, 256.0, 1.0 - rough);
  let specStr   = mix(0.25, 0.9, metallic);
  let specTint  = mix(vec3<f32>(1.0), i.col, metallic);
  let spec = pow(max(dot(N, H), 0.0), shininess) * specStr;
  let rim  = pow(1.0 - max(dot(N, V), 0.0), 3.0) * 0.25;
  let sh = shadow(i.wpos, ndl);
  var c = i.col * (amb + ndl * g.sun_col.rgb * 0.9 * (1.0 - metallic*0.7) * sh)
        + specTint * g.sun_col.rgb * spec * sh
        + g.sky.rgb * rim
        + i.col * emissive;
  c = c / (c + vec3<f32>(1.0));
  c = pow(c, vec3<f32>(1.0/2.2));
  return vec4<f32>(c, 1.0);
}")

;; depth-only shadow pass: render instances from the sun's POV into the shadow map.
(def SHADOW-WGSL "
struct G { vp: mat4x4<f32>, sun_dir: vec4<f32>, sun_col: vec4<f32>, sky: vec4<f32>, light_vp: mat4x4<f32> };
@group(0) @binding(0) var<uniform> g: G;
@vertex
fn vs(@location(0) pos: vec3<f32>, @location(1) normal: vec3<f32>,
      @location(2) m0: vec4<f32>, @location(3) m1: vec4<f32>, @location(4) m2: vec4<f32>, @location(5) m3: vec4<f32>,
      @location(6) color: vec4<f32>, @location(7) material: vec4<f32>) -> @builtin(position) vec4<f32> {
  let model = mat4x4<f32>(m0, m1, m2, m3);
  return g.light_vp * model * vec4<f32>(pos, 1.0);
}")

;; --- the render graph, as EDN data -------------------------------------------

(def default-graph
  "The frame, described as data. :passes is an ordered array — the shadow pass renders
   depth into the :shadow target, then the main pass draws to the screen sampling it.
   Reorder/add passes, swap shaders, or retarget by editing this map (or pass {:graph ...}
   to init!). Each pipeline's :binds wires its group-0 resources by name."
  {:shaders   {:lit SHADER :depth SHADOW-WGSL}
   :targets   {:shadow {:depth "depth32float" :size [2048 2048]}}
   :samplers  {:comparison {:compare "less-equal" :magFilter "linear" :minFilter "linear"}}
   :pipelines {:shadow {:shader :depth :cull "back"
                        :depth {:format "depth32float" :write true :compare "less"}
                        :binds [:uniform]}
               :main   {:shader :lit :cull "back" :color :screen
                        :depth {:format "depth24plus" :write true :compare "less-equal"}
                        :binds [:uniform {:texture :shadow} {:sampler :comparison}]}}
   :passes    [{:pipeline :shadow :depth :shadow      :clear-depth 1.0}
               {:pipeline :main   :color :screen :depth :screen-depth :clear :sky}]})

(def ^:private MAX-INST 16384)

(defn- vattr [fmt off loc] #js {:format fmt :offset off :shaderLocation loc})
(defn- vlayout []   ;; cube(pos+normal, stride 24) + instance(model+color+material, stride 96)
  #js [#js {:arrayStride 24 :attributes #js [(vattr "float32x3" 0 0) (vattr "float32x3" 12 1)]}
       #js {:arrayStride 96 :stepMode "instance"
            :attributes #js [(vattr "float32x4" 0 2) (vattr "float32x4" 16 3) (vattr "float32x4" 32 4)
                             (vattr "float32x4" 48 5) (vattr "float32x4" 64 6) (vattr "float32x4" 80 7)]}])

(defn- build-pipeline [device fmt shaders {:keys [shader cull depth color]}]
  (let [mod (.createShaderModule device #js {:code (get shaders shader)})
        desc #js {:layout "auto"
                  :vertex #js {:module mod :entryPoint "vs" :buffers (vlayout)}
                  :primitive #js {:cullMode (or cull "back")}
                  :depthStencil #js {:format (:format depth) :depthWriteEnabled (boolean (:write depth)) :depthCompare (:compare depth)}}]
    (when color   ;; no :color → depth-only pipeline (shadow pass)
      (set! (.-fragment desc) #js {:module mod :entryPoint "fs"
                                   :targets #js [#js {:format (if (= color :screen) fmt color)}]}))
    (.createRenderPipeline device desc)))

(defn- build-bind   ;; wire group-0 entries from the pipeline's :binds vector (EDN)
  [device pipe gbuf targets samplers binds]
  (.createBindGroup device
    #js {:layout (.getBindGroupLayout pipe 0)
         :entries (into-array
                    (map-indexed
                      (fn [i b]
                        (cond
                          (= b :uniform) #js {:binding i :resource #js {:buffer gbuf}}
                          (:texture b)   #js {:binding i :resource (get-in targets [(:texture b) :view])}
                          (:sampler b)   #js {:binding i :resource (get samplers (:sampler b))}))
                      binds))}))

(defn init!
  "Set up WebGPU on the canvas once from the render graph. Returns a Promise of a context.
   opts (optional): {:graph <render-graph EDN>} — defaults to default-graph."
  ([canvas] (init! canvas nil))
  ([canvas opts]
   (let [gpu (.-gpu js/navigator)]
     (if-not gpu
       (js/Promise.reject "WebGPU not available (use a recent Chrome/Edge)")
       (-> (.requestAdapter gpu)
           (.then (fn [adapter] (.requestDevice adapter)))
           (.then
             (fn [device]
               (let [graph (or (:graph opts) default-graph)
                     w (max 1 (.-clientWidth canvas)) h (max 1 (.-clientHeight canvas))
                     _ (set! (.-width canvas) w)
                     _ (set! (.-height canvas) h)
                     ctx (.getContext canvas "webgpu")
                     fmt (.getPreferredCanvasFormat gpu)
                     q (.-queue device)
                     U js/GPUBufferUsage
                     TU js/GPUTextureUsage
                     [verts idx] (cube)
                     ;; writeBuffer REQUIRES COPY_DST or it silently no-ops → zero buffers.
                     mkbuf (fn [data usage]
                             (let [b (.createBuffer device #js {:size (.-byteLength data)
                                                                :usage (bit-or usage (.-COPY_DST U))})]
                               (.writeBuffer q b 0 data) b))
                     vbuf (mkbuf verts (.-VERTEX U))
                     ibuf (mkbuf idx (.-INDEX U))
                     inst (.createBuffer device #js {:size (* MAX-INST 96) :usage (bit-or (.-VERTEX U) (.-COPY_DST U))})
                     gbuf (.createBuffer device #js {:size 176 :usage (bit-or (.-UNIFORM U) (.-COPY_DST U))})
                     ;; samplers from EDN
                     samplers (reduce-kv (fn [m k s] (assoc m k (.createSampler device (clj->js s)))) {} (:samplers graph))
                     ;; offscreen targets from EDN (RENDER_ATTACHMENT + sampleable) + implicit screen-depth
                     targets (reduce-kv
                               (fn [m k {:keys [depth color size]}]
                                 (let [[tw th] (or size [w h])
                                       f (or depth color)
                                       tex (.createTexture device #js {:size #js [tw th] :format f
                                                                       :usage (bit-or (.-RENDER_ATTACHMENT TU) (.-TEXTURE_BINDING TU))})]
                                   (assoc m k {:tex tex :view (.createView tex) :format f})))
                               {} (:targets graph))
                     sdepth (.createTexture device #js {:size #js [w h] :format "depth24plus" :usage (.-RENDER_ATTACHMENT TU)})
                     targets (assoc targets :screen-depth {:view (.createView sdepth) :format "depth24plus"})
                     ;; pipelines + bind groups, from EDN
                     pipelines (reduce-kv
                                 (fn [m k pd]
                                   (let [pipe (build-pipeline device fmt (:shaders graph) pd)]
                                     (assoc m k {:pipe pipe :bind (build-bind device pipe gbuf targets samplers (:binds pd))})))
                                 {} (:pipelines graph))]
                 (.configure ctx #js {:device device :format fmt :alphaMode "opaque"})
                 {:device device :queue q :ctx ctx :fmt fmt :w w :h h
                  :vbuf vbuf :ibuf ibuf :inst inst :gbuf gbuf :idx-count (.-length idx)
                  :targets targets :pipelines pipelines :graph graph}))))))))

(defn- arr3 [m k d] (or (get m k) d))

(defn draw!
  "Draw one frame from a render-IR map: {:globals {:sky {:horizon :sun-dir :sun} :eye
   :target} :instances [{:pos :color :size :yaw :metallic :roughness :emissive}]}. Runs
   the graph's :passes in order. Synchronous; no wasm."
  [{:keys [device queue ctx w h vbuf ibuf inst gbuf idx-count targets pipelines graph]} ir]
  (let [g (:globals ir)
        sky (:sky g)
        horizon (arr3 sky :horizon [0.7 0.8 0.9])
        sun-dir (arr3 sky :sun-dir [-0.4 -0.85 -0.35])
        sun (arr3 sky :sun [1 0.96 0.85])
        insts (vec (take MAX-INST (:instances ir)))
        cz (reduce (fn [a {:keys [pos]}] [(+ (a 0) (pos 0)) (+ (a 1) (pos 2))]) [0 0] insts)
        n (max 1 (count insts))
        [cxx czz] [(/ (cz 0) n) (/ (cz 1) n)]
        eye (arr3 g :eye [(+ cxx 60) 80 (+ czz 60)])
        target (arr3 g :target [cxx 0 czz])
        vp (m4-mul (perspective (/ (* 60 js/Math.PI) 180.0) (/ w (max 1 h)) 0.5 4000.0)
                   (look-at (vec eye) (vec target) [0 1 0]))
        sl (let [l (js/Math.hypot (sun-dir 0) (sun-dir 1) (sun-dir 2)) l (if (< l 1e-6) 1.0 l)]
             [(/ (sun-dir 0) l) (/ (sun-dir 1) l) (/ (sun-dir 2) l)])
        ltgt [cxx 0 czz]
        leye [(- (ltgt 0) (* (sl 0) 200)) (- (ltgt 1) (* (sl 1) 200)) (- (ltgt 2) (* (sl 2) 200))]
        light-vp (m4-mul (ortho -130 130 -130 130 1.0 420.0) (look-at leye ltgt [0 1 0]))
        gf (js/Float32Array. 44)]   ;; vp(16) sun_dir(4) sun_col(4) sky(4) light_vp(16)
    (.set gf vp 0)
    (.set gf (clj->js [(sun-dir 0) (sun-dir 1) (sun-dir 2) (nth eye 0)]) 16)
    (.set gf (clj->js [(sun 0) (sun 1) (sun 2) (nth eye 1)]) 20)
    (.set gf (clj->js [(horizon 0) (horizon 1) (horizon 2) (nth eye 2)]) 24)
    (.set gf light-vp 28)
    (.writeBuffer queue gbuf 0 gf)
    (let [idata (js/Float32Array. (* (count insts) 24))]   ;; model(16)+color(4)+material(4)
      (dotimes [i (count insts)]
        (let [{:keys [pos color size yaw metallic roughness emissive]} (nth insts i)
              m (model-mat pos (or yaw 0) ((or size [1 1]) 0) ((or size [1 1]) 1))
              base (* i 24)]
          (.set idata m base)
          (.set idata (clj->js [(color 0) (color 1) (color 2) 1]) (+ base 16))
          (.set idata (clj->js [(or metallic 0.0) (or roughness 0.65) (or emissive 0.0) 0]) (+ base 20))))
      (.writeBuffer queue inst 0 idata)
      (let [enc (.createCommandEncoder device)
            ninst (count insts)
            screen-view (.createView (.getCurrentTexture ctx))
            vw (fn [k] (if (= k :screen) screen-view (get-in targets [k :view])))
            draw-geom (fn [p pipe bnd]
                        (when (pos? ninst)
                          (.setPipeline p pipe) (.setBindGroup p 0 bnd)
                          (.setVertexBuffer p 0 vbuf) (.setVertexBuffer p 1 inst)
                          (.setIndexBuffer p ibuf "uint16") (.drawIndexed p idx-count ninst)))]
        ;; run the graph's passes in order (EDN-driven)
        (doseq [{:keys [pipeline color depth clear clear-depth]} (:passes graph)]
          (let [{:keys [pipe bind]} (get pipelines pipeline)
                catts (if color
                        (let [c (if (= clear :sky) horizon (or clear [0 0 0]))]
                          #js [#js {:view (vw color) :loadOp "clear" :storeOp "store"
                                    :clearValue #js {:r (c 0) :g (c 1) :b (c 2) :a 1}}])
                        #js [])
                rp (.beginRenderPass enc
                     #js {:colorAttachments catts
                          :depthStencilAttachment #js {:view (vw depth) :depthLoadOp "clear"
                                                       :depthStoreOp "store" :depthClearValue (or clear-depth 1.0)}})]
            (draw-geom rp pipe bind)
            (.end rp)))
        (.submit queue #js [(.finish enc)])))))
