(ns ssao-graph-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]))

(def source (slurp "src/kami/webgpu.cljs"))

(deftest data-driven-ssao-is-preserved-in-both-post-tiers
  (is (str/includes? source ":ssao (shaders/ssao-shader)"))
  (is (str/includes? source ":ssao {:color \"r8unorm\" :scale 0.5}"))
  (is (str/includes? source "{:texture :screen-depth} :ssao-uniform"))
  (is (str/includes? source "(w3/texture-usage :texture-binding)"))
  (is (= 2 (count (re-seq #"\{:pipeline :ssao :color :ssao" source)))
      "cinematic and saturation tiers both retain contact AO")
  (is (str/includes? source ":sample-count (:sample-count ssao) :deterministic? true"))
  (is (str/includes? source "defn adaptive-ssao-graph"))
  (is (str/includes? source ":resolution-scale scale"))
  (is (str/includes? source ":size 48"))
  (is (str/includes? source ":kotoba.webgpu/ssao-evidence-v1")))

(let [{:keys [fail error]} (run-tests 'ssao-graph-test)]
  (when (pos? (+ fail error))
    (throw (ex-info "SSAO graph gate failed" {:fail fail :error error}))))
