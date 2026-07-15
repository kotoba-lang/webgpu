(ns kami.webgpu.ir
  "render-IR — the EDN data the renderer consumes (hiccup for WebGPU).

   This namespace is .cljc: it holds the *shape* of a frame and pure constructors
   for building it. It contains NO platform code — it works identically in Clojure,
   ClojureScript, and (read as plain EDN) Rust / Datomic. The browser executor that
   turns this data into WebGPU draw calls lives in `kami.webgpu` (.cljs).

   A render-IR is a plain map:

     {:globals   {:sky    {:horizon [r g b]      ;; clear / ambient sky colour
                           :sun-dir [x y z]       ;; directional light (world space)
                           :sun     [r g b]}      ;; sun colour
                  :lighting {…}                   ;; lighting-model coefficients (optional —
                  :eye    [x y z]                 ;;   `default-lighting` below fills the rest)
                  :target [x y z]}                ;;   else an overview is derived)
      :instances [{:pos   [x y z]                 ;; world position (ground at y)
                   :color [r g b]                 ;; albedo
                   :size  [w h d]                 ;; width × height × depth
                                                  ;; legacy [w h] means depth=w
                   :yaw   theta                    ;; rotation about Y (radians)
                   :metallic m :roughness r :emissive e
                   :textured? true}]} ;; sample bound albedo/normal/MR textures

   A material is just the PBR fields — author a palette as data and merge it into
   instances (or store it as Datomic datoms and query/as-of/fork it):

      {:metallic 0.0 :roughness 0.65 :emissive 0.0}

   Everything is data: build it with assoc/update/merge, store it in Datomic, send
   it over the wire, fork it. Future keys (:passes, :pipelines, :materials, WGSL as
   EDN) extend the same map without changing the contract — the executor reads what
   it understands and ignores the rest."
  (:require [kami.webgpu.geometry :as geom]
            [kotoba.render.building :as building]
            [kotoba.render.terrain :as terrain]
            [kotoba.render.terrain-biome :as terrain-biome]
            [kotoba.render.road :as road]))

(defn material
  "A PBR material — pure data. metallic 0=dielectric…1=metal; roughness 0=mirror…1=matte;
   emissive ≥0 = self-glow (× albedo). Store these as datoms and query/as-of/fork them."
  [& {:keys [metallic roughness emissive textured?]
      :or {metallic 0.0 roughness 0.65 emissive 0.0 textured? false}}]
  {:metallic metallic :roughness roughness :emissive emissive :textured? textured?})

;; --- lighting model: the shader's look, as data -------------------------------
;; The fragment shader used to bake these coefficients in as literals; now they are EDN under
;; the frame's [:globals :lighting]. `default-lighting` reproduces the original constants
;; EXACTLY, so a frame that omits :lighting renders identically — authoring it is opt-in.
;; A game overrides any subset in its scene.edn :render/lighting (e.g. a warmer ambient, a
;; punchier rim) and the executor merges it over these defaults. Store it as datoms, fork it.

(def default-lighting
  {:ambient              [0.20 0.22 0.26] ;; hemisphere ground/ambient colour (down-facing)
   :ambient-sky          0.65             ;; how much sky colour bleeds into the up-facing ambient
   :spec-min             0.25             ;; specular strength — dielectric
   :spec-max             0.90             ;; specular strength — metal
   :rim                  0.25             ;; Fresnel rim-light strength
   :rim-power            3.0              ;; rim falloff exponent
   :shininess-min        4.0              ;; Blinn-Phong exponent — rough surface
   :shininess-max        256.0            ;; Blinn-Phong exponent — smooth surface
   :sun-diffuse          0.9              ;; direct-sun diffuse scale
   :metallic-diffuse-cut 0.7              ;; how strongly metal suppresses diffuse
   :gamma                2.2              ;; output gamma (encoding exponent)
   :shadow-bias-slope    0.0025           ;; shadow depth-bias, scaled by (1 - N·L)
   :shadow-bias-min      0.0006           ;; shadow depth-bias floor
   :shadow-texel         (/ 1.0 2048.0)}) ;; 1 / shadow-map size (match :targets :shadow :size)

(defn lighting
  "A lighting map merged over the defaults — pass a partial override, get a complete map."
  [m] (merge default-lighting m))

;; --- sun shadow frustum: the directional light's orthographic camera, as data ----
;; draw! used to bake the shadow frustum in (ortho ±130, near 1, far 420, light 200 units
;; back along the sun). Now it's [:globals :shadow]; defaults reproduce the old frustum, so
;; omitting it changes nothing. Widen :extent for a bigger world, raise :distance to keep
;; tall geometry inside the light's depth range. (:shadow-texel — the PCF tap size — lives in
;; the lighting map and should track the :targets :shadow :size in the render graph.)

(def default-shadow
  {:extent   130.0   ;; half-width of the ortho light frustum (world units)
   :near     1.0     ;; light near plane
   :far      420.0   ;; light far plane
   :distance 200.0}) ;; how far back along -sun-dir the light is placed

(defn shadow
  "A shadow-frustum map merged over the defaults — partial override → complete map."
  [m] (merge default-shadow m))

;; --- geometry library: the :geo mesh kinds, as data ------------------------------
;; The executor used to hardcode three meshes (box/sphere/cylinder at fixed tessellation).
;; Now each `:geo` kind is a {:type … params} spec, baked into a mesh by `mesh-from-spec`
;; via kami.webgpu.geometry (the shared cross-platform mesh source). `default-geometry`
;; reproduces the original three EXACTLY; pass {:geometry {…}} to init! to add a kind (e.g.
;; :plane) or retessellate one — an instance then references the kind by its `:geo` keyword.

(def default-geometry
  {:box      {:type :box      :size [1 1 1]}
   :sphere   {:type :sphere   :r 0.5 :rings 14 :sectors 20}
   :sphere-lod1 {:type :sphere :r 0.5 :rings 6 :sectors 10}
   :cylinder {:type :cylinder :r 0.5 :h 1 :sectors 20}
   :cylinder-lod1 {:type :cylinder :r 0.5 :h 1 :sectors 8}
   :stepped-tower {:type :building :variant :stepped-tower :detail :high :seed 17}
   :stepped-tower-lod1 {:type :building :variant :stepped-tower :detail :medium :seed 17}
   :stepped-tower-lod2 {:type :building :variant :stepped-tower :detail :low :seed 17}
   :industrial-block {:type :building :variant :industrial-block :detail :high :seed 29}
   :industrial-block-lod1 {:type :building :variant :industrial-block :detail :medium :seed 29}
   :industrial-block-lod2 {:type :building :variant :industrial-block :detail :low :seed 29}})

(defn- building-geometry [{:keys [variant detail seed]}]
  (let [[positions normals uvs indices]
        (building/building-mesh {:variant variant :width 1.0 :depth 1.0
                                 :height 1.0 :seed (or seed 0)}
                                (or detail :high))]
    {:positions (mapv vec (partition 3 positions))
     :normals (mapv vec (partition 3 normals))
     :uvs (mapv vec (partition 2 uvs))
     :indices indices}))

(defn- registered-mesh [{:keys [mesh]}]
  (let [{:keys [positions normals biome-weights biome-layer-indices indices]} mesh
        vertex-count (count positions)]
    (when-not (and (map? mesh)
                   (seq positions)
                   (seq indices)
                   (= vertex-count (count normals))
                   (or (nil? biome-weights) (= vertex-count (count biome-weights)))
                   (or (nil? biome-layer-indices) (= vertex-count (count biome-layer-indices)))
                   (every? #(= 3 (count %)) positions)
                   (every? #(= 3 (count %)) normals)
                   (zero? (mod (count indices) 3))
                   (every? #(< -1 % vertex-count) indices))
      (throw (ex-info "invalid registered geometry mesh"
                      {:vertex-count vertex-count :normal-count (count normals)
                       :index-count (count indices)})))
    (select-keys mesh [:positions :normals :uvs :biome-weights :biome-layer-indices :indices])))

(defn- terrain-geometry [{:keys [detail] :as spec}]
  (let [[positions normals uvs indices]
        (terrain/terrain-mesh
         (select-keys spec [:patch :size :base-segments :amplitude :seed :skirt-depth])
         (or detail :high))
        positions (mapv vec (partition 3 positions))
        normals (mapv vec (partition 3 normals))
        biome (or (:biome spec) terrain-biome/default-biome)
        by-id (into {} (map (juxt :id :texture-layer) (:layers biome)))
        layer-indices (mapv by-id [:grass :soil :rock])]
    {:positions positions
     :normals normals
     :uvs (mapv vec (partition 2 uvs))
     :biome-weights (terrain-biome/mesh-weights
                     biome
                     [(vec (mapcat identity positions)) (vec (mapcat identity normals)) uvs indices])
     :biome-layer-indices (vec (repeat (count positions) layer-indices))
     :indices indices}))

(defn- road-ribbon-geometry [{:keys [detail part] :as spec}]
  (let [road-spec (select-keys spec [:path :width :shoulder :camber :shoulder-drop
                                      :clearance :uv-scale :base-subdivisions
                                      :miter-limit :terrain])
        parts (road/road-mesh-parts road-spec (or detail :high))
        [positions normals uvs indices]
        (or (get parts (or part :surface))
            (throw (ex-info "unsupported road ribbon material part"
                            {:part part :supported (set (keys parts))})))]
    {:positions (mapv vec (partition 3 positions))
     :normals (mapv vec (partition 3 normals))
     :uvs (mapv vec (partition 2 uvs))
     :indices indices}))

(defn mesh-from-spec
  "Bake one geometry spec → a mesh {:positions :normals :indices}. Pure + cross-platform
   (a native executor reimplements this dispatch over the same data). Unknown :type → unit box."
  [{:keys [type size r rings sectors h w d] :as spec}]
  (case type
    :box      (let [s (or size [1 1 1])] (geom/box (nth s 0) (nth s 1) (nth s 2)))
    :sphere   (geom/sphere (or r 0.5) (or rings 14) (or sectors 20))
    :cylinder (geom/cylinder (or r 0.5) (or h 1) (or sectors 20))
    :plane    (geom/plane (or w 10) (or d 10))
    :building (building-geometry spec)
    :mesh     (registered-mesh spec)
    :terrain  (terrain-geometry spec)
    :road-ribbon (road-ribbon-geometry spec)
    (geom/box 1 1 1)))

(defn instance
  "An instanced cuboid. `size` is `[width height depth]`; legacy `[w h]`
   remains supported and means `[w h w]`. Pure data. Merge a `material` map in
   for PBR."
  [pos color size & {:keys [yaw metallic roughness emissive textured?] :or {yaw 0}}]
  (cond-> {:pos pos :color color :size size :yaw yaw}
    metallic  (assoc :metallic metallic)
    roughness (assoc :roughness roughness)
    emissive  (assoc :emissive emissive)
    textured? (assoc :textured? true)))

(defn instance-size
  "Normalize an instance size to `[width height depth]`. The historical two-axis
   form described a square x/z footprint, so `[w h]` expands to `[w h w]`."
  [size]
  (let [[width height depth] (or size [1 1 1])]
    [(or width 1) (or height 1) (or depth width 1)]))

(defn sky
  [horizon sun-dir sun]
  {:horizon horizon :sun-dir sun-dir :sun sun})

(defn render-ir
  "Assemble a frame's render-IR from sky, instances, and an optional camera."
  ([sky-map instances] {:globals {:sky sky-map} :instances (vec instances)})
  ([sky-map instances eye target]
   {:globals {:sky sky-map :eye eye :target target} :instances (vec instances)}))

(defn with-camera
  "Return ir with the camera set to eye→target (3rd-person follow, overview, …)."
  [ir eye target]
  (-> ir (assoc-in [:globals :eye] eye) (assoc-in [:globals :target] target)))

;; --- camera rig: EDN data → eye/target (pure, cross-platform) ----------------
;;   {:distance 64 :height 55 :azimuth 0.785 :look-height 0.0}
;; A rig is data: store it as datoms, fork it, animate :azimuth — the executor just
;; consumes the eye/target it produces.

(def default-rig {:distance 64.0 :height 55.0 :azimuth 0.785 :look-height 0.0})

(defn rig->camera
  "Given a camera-rig map and the follow point [x z] (world), return {:eye :target}.
   eye orbits the target at :distance/:azimuth, raised to :height; target sits at
   :look-height above the follow point."
  [rig [px pz]]
  (let [{:keys [distance height azimuth look-height]} (merge default-rig rig)]
    {:eye    [(+ px (* distance #?(:clj (Math/cos azimuth) :cljs (js/Math.cos azimuth))))
              height
              (+ pz (* distance #?(:clj (Math/sin azimuth) :cljs (js/Math.sin azimuth))))]
     :target [px look-height pz]}))

(defn valid?
  "A cheap structural check — enough to catch obvious authoring mistakes."
  [ir]
  (and (map? ir)
       (map? (:globals ir))
       (sequential? (:instances ir))
       (every? (fn [i] (and (vector? (:pos i)) (vector? (:color i)) (vector? (:size i))))
               (:instances ir))))
