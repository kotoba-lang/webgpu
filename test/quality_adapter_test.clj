(ns quality-adapter-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [kami.webgpu.quality :as quality]))

(def graph {:targets {:shadow {:depth "depth32float" :size [2048 2048]}}})
(def plan {:schema :kotoba.render/quality-v1
           :shadow {:enabled? true :cascades 4 :resolution 4096}
           :post-process {:passes [{:kind :ssao} {:kind :bloom} {:kind :tone-map}]}
           :lod {:bias 1.5 :max-visible-instances 250000}})

(deftest resolves-shared-plan-honestly
  (let [resolved (quality/resolve-plan graph plan)]
    (is (= [4096 4096] (get-in resolved [:graph :targets :shadow :size])))
    (is (= 1 (get-in resolved [:effective :shadow :cascades])))
    (is (= [:shadow-cascades :post-process]
           (mapv :feature (:degraded resolved))))
    (is (= [:tone-map] (get-in resolved [:effective :post-process])))
    (is (= (:lod plan) (get-in resolved [:effective :lod])))))

(deftest rejects-unversioned-data
  (is (thrown-with-msg? Exception #"unsupported render-quality schema"
                        (quality/resolve-plan graph {:shadow {}}))))

(deftest applies-density-budget-deterministically
  (let [instances [{:id :far :pos [20 0 0] :triangles 12}
                   {:id :hero :pos [30 0 0] :importance 10 :triangles 12}
                   {:id :near :pos [2 0 0] :triangles 12}]
        result (quality/density-plan instances [0 0 0]
                                     {:max-visible-instances 2
                                      :max-visible-triangles 24})]
    (is (= [:hero :near] (mapv :id (:instances result))))
    (is (= 1 (:culled-count result)))
    (is (:budget-applied? result)))
  (let [instances [{:id :a :pos [0 0 0]}]
        result (quality/density-plan instances nil {:max-visible-instances 10})]
    (is (identical? instances (:instances result)))
    (is (false? (:budget-applied? result)))))

(deftest selects-mesh-lod-with-hysteresis
  (let [instances [{:id :ball :geo :sphere :pos [0 0 100] :size [2 2]}]
        low (quality/apply-lod instances [0 0 0] (/ Math/PI 3) 1080 1.0 {})
        held (quality/apply-lod instances [0 0 0] (/ Math/PI 3) 1080 4.0 (:state low))
        high (quality/apply-lod instances [0 0 0] (/ Math/PI 3) 1080 5.0 (:state held))]
    (is (= :sphere-lod1 (get-in low [:instances 0 :geo])))
    (is (= :sphere-lod1 (get-in held [:instances 0 :geo]))
        "15% hysteresis prevents threshold flicker")
    (is (= :sphere (get-in high [:instances 0 :geo])))
    (is (= {:sphere 1} (:levels high)))))

(let [{:keys [fail error]} (run-tests 'quality-adapter-test)]
  (when (pos? (+ fail error))
    (throw (ex-info "quality adapter tests failed" {:fail fail :error error}))))
