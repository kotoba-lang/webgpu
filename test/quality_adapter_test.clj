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

(let [{:keys [fail error]} (run-tests 'quality-adapter-test)]
  (when (pos? (+ fail error))
    (throw (ex-info "quality adapter tests failed" {:fail fail :error error}))))
