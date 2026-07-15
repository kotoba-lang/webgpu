(ns kami.webgpu.quality
  "Adapter from `:kotoba.render/quality-v1` plans to the capabilities currently
   implemented by the browser WebGPU executor. Pure data; no JS API calls.")

(def capabilities
  {:backend :webgpu
   :pbr #{:base-color :metallic :roughness :emissive}
   :shadow {:directional true :max-cascades 1 :max-resolution 4096 :pcf-radius 1}
   ;; ACES is currently fused into the lit fragment shader. Multi-pass effects
   ;; remain absent until the HDR intermediate frame graph lands.
   :post-process #{:tone-map}
   :lod #{:consumer-culling :instance-budget :triangle-budget}})

(defn quality-plan? [plan]
  (= :kotoba.render/quality-v1 (:schema plan)))

(defn- distance-squared [eye {:keys [pos]}]
  (if (and eye pos)
    (reduce + (map (fn [a b] (let [d (- a b)] (* d d))) eye pos))
    0.0))

(defn density-plan
  "Apply the effective instance/triangle budget. The common within-budget path
   preserves the original collection without sorting. Over budget, retain hero
   importance first, then nearest camera distance, then source order."
  [instances eye {:keys [max-visible-instances max-visible-triangles]}]
  (let [instance-limit (or max-visible-instances #?(:clj Long/MAX_VALUE :cljs js/Number.MAX_SAFE_INTEGER))
        triangle-limit (or max-visible-triangles #?(:clj Long/MAX_VALUE :cljs js/Number.MAX_SAFE_INTEGER))
        triangle-count (reduce + (map #(or (:triangles %) 12) instances))]
    (if (and (<= (count instances) instance-limit)
             (<= triangle-count triangle-limit))
      {:instances instances :input-count (count instances) :visible-count (count instances)
       :triangle-count triangle-count :culled-count 0 :budget-applied? false}
      (let [ranked (sort-by (juxt (comp - #(or % 1.0) :importance)
                                  #(distance-squared eye %)
                                  :source-index)
                            (map-indexed #(assoc %2 :source-index %1) instances))
            result (reduce (fn [{:keys [instances triangles] :as acc} item]
                             (let [n (or (:triangles item) 12)]
                               (if (and (< (count instances) instance-limit)
                                        (<= (+ triangles n) triangle-limit))
                                 {:instances (conj instances (dissoc item :source-index))
                                  :triangles (+ triangles n)}
                                 acc)))
                           {:instances [] :triangles 0} ranked)]
        {:instances (:instances result)
         :input-count (count instances)
         :visible-count (count (:instances result))
         :triangle-count (:triangles result)
         :culled-count (- (count instances) (count (:instances result)))
         :budget-applied? true}))))

(defn resolve-plan
  "Resolve a shared render-quality plan without silently claiming unsupported
   effects. Returns the effective graph plus explicit `:degraded` evidence."
  [graph plan]
  (when-not (quality-plan? plan)
    (throw (ex-info "unsupported render-quality schema" {:schema (:schema plan)})))
  (let [requested-shadow (:shadow plan)
        shadow-enabled? (:enabled? requested-shadow)
        requested-cascades (or (:cascades requested-shadow) 0)
        resolution (min (or (:resolution requested-shadow) 2048)
                        (get-in capabilities [:shadow :max-resolution]))
        post-kinds (mapv :kind (get-in plan [:post-process :passes]))
        supported-post (vec (filter (:post-process capabilities) post-kinds))
        unsupported-post (vec (remove (:post-process capabilities) post-kinds))
        graph' (if shadow-enabled?
                 (assoc-in graph [:targets :shadow :size] [resolution resolution])
                 graph)
        degraded (cond-> []
                   (> requested-cascades 1)
                   (conj {:feature :shadow-cascades :requested requested-cascades :effective 1})
                   (seq unsupported-post)
                   (conj {:feature :post-process :unsupported unsupported-post :effective []})
                   (not shadow-enabled?)
                   (conj {:feature :shadow-disable :requested :off
                          :effective :compatibility-shadow
                          :reason :lit-pipeline-currently-requires-shadow-binding}))]
    {:graph graph'
     :quality-plan plan
     :effective {:shadow {:enabled? true :cascades 1 :resolution resolution}
                 :post-process supported-post
                 :lod (:lod plan)}
     :degraded degraded
     :capabilities capabilities}))
