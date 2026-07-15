(ns kami.webgpu.quality
  "Adapter from `:kotoba.render/quality-v1` plans to the capabilities currently
   implemented by the browser WebGPU executor. Pure data; no JS API calls.")

(def capabilities
  {:backend :webgpu
   :pbr #{:base-color :metallic :roughness :emissive}
   :shadow {:directional true :max-cascades 1 :max-resolution 4096 :pcf-radius 1}
   :post-process #{}
   :lod #{:consumer-culling :instance-budget :triangle-budget}})

(defn quality-plan? [plan]
  (= :kotoba.render/quality-v1 (:schema plan)))

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
                 :post-process []
                 :lod (:lod plan)}
     :degraded degraded
     :capabilities capabilities}))
