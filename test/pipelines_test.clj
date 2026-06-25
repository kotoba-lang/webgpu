(ns pipelines-test
  "Gate the native render pipelines (kami-render/scene_pipelines.rs) against kami.pipelines — the EDN
   table is the single source for each pipeline's varying fields (shader/cull/depth), so the
   hand-written Rust can't drift from it. Reads the co-located kami-engine checkout (skips if absent)."
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.java.io :as io]
            [kami.pipelines :as pl]))

(def rust "../kami-engine/kami-render/src/scene_pipelines.rs")

(deftest edn-matches-native-pipelines
  (let [f (io/file rust)]
    (if-not (.exists f)
      (println "  skip: scene_pipelines.rs (kami-engine not co-located)")
      (let [parsed (pl/parse-rust (slurp f))]
        (is (= 8 (count parsed)) "all 8 native pipelines parsed")
        (doseq [[id edn] pl/native-pipelines]
          (is (= edn (get parsed id))
              (str id " — kami.pipelines EDN must match scene_pipelines.rs (shader/cull/depth)")))))))

(let [{:keys [fail error]} (run-tests 'pipelines-test)]
  (when (pos? (+ fail error)) (throw (ex-info "pipelines parity failed" {:fail fail :error error}))))
