(ns atmosphere-graph-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]))

(def source (slurp "src/kami/webgpu.cljs"))

(deftest hdr-and-adaptive-graphs-preserve-atmosphere
  (is (str/includes? source ":atmosphere (shaders/atmosphere-cloud-shader)"))
  (is (= 2 (count (re-seq #"\{:pipeline :atmosphere :color :hdr" source)))
      "cinematic and adaptive SSAO paths both retain the HDR atmosphere")
  (is (str/includes? source ":loadOp (if load? \"load\" \"clear\")"))
  (is (str/includes? source ":uniform-floats 32")))

(let [{:keys [fail error]} (run-tests 'atmosphere-graph-test)]
  (when (pos? (+ fail error))
    (throw (ex-info "atmosphere graph gate failed" {:fail fail :error error}))))
