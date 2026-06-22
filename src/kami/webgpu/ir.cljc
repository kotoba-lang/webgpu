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
                  :eye    [x y z]                 ;; camera position (optional —
                  :target [x y z]}                ;;   else an overview is derived)
      :instances [{:pos   [x y z]                 ;; world position (ground at y)
                   :color [r g b]                 ;; albedo
                   :size  [w h]                   ;; footprint w (x,z) × height h
                   :yaw   theta                    ;; rotation about Y (radians)
                   :metallic m :roughness r :emissive e}]} ;; PBR material (optional)

   A material is just the PBR fields — author a palette as data and merge it into
   instances (or store it as Datomic datoms and query/as-of/fork it):

      {:metallic 0.0 :roughness 0.65 :emissive 0.0}

   Everything is data: build it with assoc/update/merge, store it in Datomic, send
   it over the wire, fork it. Future keys (:passes, :pipelines, :materials, WGSL as
   EDN) extend the same map without changing the contract — the executor reads what
   it understands and ignores the rest.")

(defn material
  "A PBR material — pure data. metallic 0=dielectric…1=metal; roughness 0=mirror…1=matte;
   emissive ≥0 = self-glow (× albedo). Store these as datoms and query/as-of/fork them."
  [& {:keys [metallic roughness emissive] :or {metallic 0.0 roughness 0.65 emissive 0.0}}]
  {:metallic metallic :roughness roughness :emissive emissive})

(defn instance
  "An instanced cuboid. Pure data. Merge a `material` map in for PBR."
  [pos color size & {:keys [yaw metallic roughness emissive] :or {yaw 0}}]
  (cond-> {:pos pos :color color :size size :yaw yaw}
    metallic  (assoc :metallic metallic)
    roughness (assoc :roughness roughness)
    emissive  (assoc :emissive emissive)))

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

(defn valid?
  "A cheap structural check — enough to catch obvious authoring mistakes."
  [ir]
  (and (map? ir)
       (map? (:globals ir))
       (sequential? (:instances ir))
       (every? (fn [i] (and (vector? (:pos i)) (vector? (:color i)) (vector? (:size i))))
               (:instances ir))))
