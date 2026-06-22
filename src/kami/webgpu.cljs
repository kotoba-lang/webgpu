(ns kami.webgpu
  "kami-webgpu — a WebGPU renderer driven entirely from CLJ/EDN (hiccup for WebGPU).

   ClojureScript calls the browser WebGPU JS API directly — no Rust, no wasm, no string
   marshaling. The render-IR is a plain CLJS map: globals (camera/sky/sun) + a flat
   instance list. `init!` sets up the device/pipeline once; `draw!` records a frame from
   the EDN each requestAnimationFrame. The heavy rasterization is the GPU's; CLJS only
   records light per-frame commands. This is the web execution of the same EDN render
   spec a native Rust/wgpu executor interprets (ADR-0001). The render-IR shape + pure
   data constructors live in kami.webgpu.ir (.cljc, cross-platform).")

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

;; eye (camera world pos) is packed into the spare .w of sun_dir/sun_col/sky so the
;; uniform stays 112 bytes. Lighting: hemisphere ambient + Lambert sun + Blinn-Phong
;; specular + a Fresnel rim, Reinhard tonemap + gamma. Material params (metallic/rough)
;; will move into the EDN render-IR as the next layer (passes/materials as datoms).
(def ^:private SHADER "
struct G { vp: mat4x4<f32>, sun_dir: vec4<f32>, sun_col: vec4<f32>, sky: vec4<f32> };
@group(0) @binding(0) var<uniform> g: G;
struct VO { @builtin(position) clip: vec4<f32>, @location(0) n: vec3<f32>, @location(1) col: vec3<f32>, @location(2) wpos: vec3<f32>, @location(3) mat: vec3<f32> };
@vertex
fn vs(@location(0) pos: vec3<f32>, @location(1) normal: vec3<f32>,
      @location(2) m0: vec4<f32>, @location(3) m1: vec4<f32>, @location(4) m2: vec4<f32>, @location(5) m3: vec4<f32>,
      @location(6) color: vec4<f32>, @location(7) material: vec4<f32>) -> VO {
  let model = mat4x4<f32>(m0, m1, m2, m3);
  let world = model * vec4<f32>(pos, 1.0);
  var o: VO; o.clip = g.vp * world;
  o.n = normalize((model * vec4<f32>(normal, 0.0)).xyz); o.col = color.rgb; o.wpos = world.xyz;
  o.mat = material.xyz; return o;          // metallic, roughness, emissive (from EDN)
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
  // hemisphere ambient: sky above, a cool ground bounce below
  let amb = mix(vec3<f32>(0.20,0.22,0.26), g.sky.rgb*0.65, N.y*0.5+0.5);
  // Blinn-Phong specular: roughness→sharpness, metallic→strength + albedo-tinted
  let shininess = mix(4.0, 256.0, 1.0 - rough);
  let specStr   = mix(0.25, 0.9, metallic);
  let specTint  = mix(vec3<f32>(1.0), i.col, metallic);
  let spec = pow(max(dot(N, H), 0.0), shininess) * specStr;
  let rim  = pow(1.0 - max(dot(N, V), 0.0), 3.0) * 0.25;
  var c = i.col * (amb + ndl * g.sun_col.rgb * 0.9 * (1.0 - metallic*0.7))
        + specTint * g.sun_col.rgb * spec
        + g.sky.rgb * rim
        + i.col * emissive;               // EDN-authored glow
  c = c / (c + vec3<f32>(1.0));                 // Reinhard tonemap
  c = pow(c, vec3<f32>(1.0/2.2));               // gamma
  return vec4<f32>(c, 1.0);
}")

(def ^:private MAX-INST 16384)

(defn- vattr [fmt off loc] #js {:format fmt :offset off :shaderLocation loc})

(defn init!
  "Set up WebGPU on the canvas once. Returns a Promise of a render context.
   opts (optional): {:wgsl <shader string>} — the WGSL is data; override it from the
   EDN render graph (defaults to the built-in PBR shader)."
  ([canvas] (init! canvas nil))
  ([canvas opts]
  (let [gpu (.-gpu js/navigator)]
    (if-not gpu
      (js/Promise.reject "WebGPU not available (use a recent Chrome/Edge)")
      (-> (.requestAdapter gpu)
          (.then (fn [adapter] (.requestDevice adapter)))
          (.then
            (fn [device]
              (let [w (max 1 (.-clientWidth canvas)) h (max 1 (.-clientHeight canvas))
                    _ (set! (.-width canvas) w)
                    _ (set! (.-height canvas) h)
                    ctx (.getContext canvas "webgpu")
                    fmt (.getPreferredCanvasFormat gpu)
                    q (.-queue device)
                    U js/GPUBufferUsage
                    [verts idx] (cube)
                    ;; writeBuffer REQUIRES COPY_DST — without it the write silently
                    ;; fails (validation error stays in the device scope, not thrown)
                    ;; and the buffer stays zero-filled → degenerate geometry → nothing
                    ;; renders. This was the "clear works, no boxes" bug.
                    mkbuf (fn [data usage]
                            (let [b (.createBuffer device #js {:size (.-byteLength data)
                                                               :usage (bit-or usage (.-COPY_DST U))})]
                              (.writeBuffer q b 0 data) b))
                    vbuf (mkbuf verts (.-VERTEX U))
                    ibuf (mkbuf idx (.-INDEX U))
                    inst (.createBuffer device #js {:size (* MAX-INST 96) :usage (bit-or (.-VERTEX U) (.-COPY_DST U))})
                    gbuf (.createBuffer device #js {:size 112 :usage (bit-or (.-UNIFORM U) (.-COPY_DST U))})
                    shader (.createShaderModule device #js {:code (or (:wgsl opts) SHADER)})
                    pipeline (.createRenderPipeline device
                               #js {:layout "auto"
                                    :vertex #js {:module shader :entryPoint "vs"
                                                 :buffers #js [#js {:arrayStride 24
                                                                    :attributes #js [(vattr "float32x3" 0 0) (vattr "float32x3" 12 1)]}
                                                               ;; instance: model(64) + color(16) + material(16) = 96 bytes
                                                               #js {:arrayStride 96 :stepMode "instance"
                                                                    :attributes #js [(vattr "float32x4" 0 2) (vattr "float32x4" 16 3)
                                                                                     (vattr "float32x4" 32 4) (vattr "float32x4" 48 5)
                                                                                     (vattr "float32x4" 64 6) (vattr "float32x4" 80 7)]}]}
                                    :fragment #js {:module shader :entryPoint "fs" :targets #js [#js {:format fmt}]}
                                    :primitive #js {:cullMode "back"}
                                    :depthStencil #js {:format "depth24plus" :depthWriteEnabled true :depthCompare "less-equal"}})
                    bind (.createBindGroup device #js {:layout (.getBindGroupLayout pipeline 0)
                                                       :entries #js [#js {:binding 0 :resource #js {:buffer gbuf}}]})
                    depth (.createTexture device #js {:size #js [w h] :format "depth24plus" :usage (.-RENDER_ATTACHMENT js/GPUTextureUsage)})]
                (.configure ctx #js {:device device :format fmt :alphaMode "opaque"})
                {:device device :queue q :ctx ctx :pipeline pipeline :bind bind
                 :vbuf vbuf :ibuf ibuf :inst inst :gbuf gbuf :idx-count (.-length idx)
                 :depth (.createView depth) :w w :h h }))))))))

(defn- arr3 [m k d] (or (get m k) d))

(defn draw!
  "Draw one frame from a render-IR CLJS map: {:globals {:sky {:horizon :sun-dir :sun}
   :eye :target} :instances [{:pos :color :size :yaw}]}. Synchronous; no wasm."
  [{:keys [device queue ctx pipeline bind vbuf ibuf inst gbuf idx-count depth w h]} ir]
  (let [g (:globals ir)
        sky (:sky g)
        horizon (arr3 sky :horizon [0.7 0.8 0.9])
        sun-dir (arr3 sky :sun-dir [-0.4 -0.85 -0.35])
        sun (arr3 sky :sun [1 0.96 0.85])
        insts (vec (take MAX-INST (:instances ir)))
        ;; camera: explicit eye/target, else overview of the instance centroid
        cz (reduce (fn [a {:keys [pos]}] [(+ (a 0) (pos 0)) (+ (a 1) (pos 2))]) [0 0] insts)
        n (max 1 (count insts))
        [cxx czz] [(/ (cz 0) n) (/ (cz 1) n)]
        eye (arr3 g :eye [(+ cxx 60) 80 (+ czz 60)])
        target (arr3 g :target [cxx 0 czz])
        vp (m4-mul (perspective (/ (* 60 js/Math.PI) 180.0) (/ w (max 1 h)) 0.5 4000.0)
                   (look-at (vec eye) (vec target) [0 1 0]))
        ;; globals uniform: vp(16) + sun_dir(4) + sun_col(4) + sky(4)
        gf (js/Float32Array. 28)]
    (.set gf vp 0)
    ;; .w of each carries the camera eye (x,y,z) for view-dependent lighting
    (.set gf (clj->js [(sun-dir 0) (sun-dir 1) (sun-dir 2) (nth eye 0)]) 16)
    (.set gf (clj->js [(sun 0) (sun 1) (sun 2) (nth eye 1)]) 20)
    (.set gf (clj->js [(horizon 0) (horizon 1) (horizon 2) (nth eye 2)]) 24)
    (.writeBuffer queue gbuf 0 gf)
    ;; instance buffer: model(16) + color(4) + material(4) = 24 floats. The material
    ;; (metallic, roughness, emissive) is EDN data on each instance, defaulting to a
    ;; matte dielectric — author it per-material in the render graph.
    (let [idata (js/Float32Array. (* (count insts) 24))]
      (dotimes [i (count insts)]
        (let [{:keys [pos color size yaw metallic roughness emissive]} (nth insts i)
              m (model-mat pos (or yaw 0) ((or size [1 1]) 0) ((or size [1 1]) 1))
              base (* i 24)]
          (.set idata m base)
          (.set idata (clj->js [(color 0) (color 1) (color 2) 1]) (+ base 16))
          (.set idata (clj->js [(or metallic 0.0) (or roughness 0.65) (or emissive 0.0) 0]) (+ base 20))))
      (.writeBuffer queue inst 0 idata)
      (let [enc (.createCommandEncoder device)
            view (.createView (.getCurrentTexture ctx))
            pass (.beginRenderPass enc
                   #js {:colorAttachments #js [#js {:view view :loadOp "clear" :storeOp "store"
                                                    :clearValue #js {:r (horizon 0) :g (horizon 1) :b (horizon 2) :a 1}}]
                        :depthStencilAttachment #js {:view depth :depthLoadOp "clear" :depthStoreOp "store" :depthClearValue 1.0}})]
        (when (pos? (count insts))
          (.setPipeline pass pipeline)
          (.setBindGroup pass 0 bind)
          (.setVertexBuffer pass 0 vbuf)
          (.setVertexBuffer pass 1 inst)
          (.setIndexBuffer pass ibuf "uint16")
          (.drawIndexed pass idx-count (count insts)))
        (.end pass)
        (.submit queue #js [(.finish enc)])))))
