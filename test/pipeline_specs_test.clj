(ns pipeline-specs-test
  "Gate: fixtures/pipeline_specs.edn must stay in sync with kami.pipelines."
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.string :as str]
            [babashka.process :as p]))

(deftest generated-edn-in-sync-with-source
  (let [{:keys [exit out]} (p/sh "bb" "scripts/gen_pipeline_specs.clj" "--strict")]
    (println (str/trim out))
    (is (zero? exit) "fixtures/pipeline_specs.edn differs from kami.pipelines; run `bb gen-pipeline-specs --write`")))

(let [{:keys [fail error]} (run-tests 'pipeline-specs-test)]
  (when (pos? (+ fail error)) (throw (ex-info "pipeline_specs fixture drift" {:fail fail :error error}))))
