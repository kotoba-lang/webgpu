(ns atmosphere-graph-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]))

(def source (slurp "src/kami/webgpu.cljs"))

(deftest hdr-and-adaptive-graphs-preserve-atmosphere
  (is (str/includes? source ":atmosphere (shaders/atmosphere-cloud-shader)"))
  (is (str/includes? source "{:pipeline :atmosphere :color :hdr"))
  (is (str/includes? source "{:pipeline :atmosphere-direct :color :screen"))
  (is (str/includes? source ":loadOp (if load? \"load\" \"clear\")"))
  (is (str/includes? source ":uniform-floats 32")))

(let [{:keys [fail error]} (run-tests 'atmosphere-graph-test)]
  (when (pos? (+ fail error))
    (throw (ex-info "atmosphere graph gate failed" {:fail fail :error error}))))
