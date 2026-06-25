(ns pipeline-specs-test
  "Gate: fixtures/pipeline_specs.rs must stay in sync with kami.pipelines (the EDN source). Reuses the
   generator's own --strict check, so editing the EDN without regenerating — or editing the generated
   Rust by hand — fails the build."
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.string :as str]
            [babashka.process :as p]))

(deftest generated-rust-in-sync-with-edn
  (let [{:keys [exit out]} (p/sh "bb" "scripts/gen_pipeline_specs.clj" "--strict")]
    (println (str/trim out))
    (is (zero? exit) "fixtures/pipeline_specs.rs ≠ kami.pipelines — run `bb gen-pipeline-specs --write`")))

(let [{:keys [fail error]} (run-tests 'pipeline-specs-test)]
  (when (pos? (+ fail error)) (throw (ex-info "pipeline_specs codegen drift" {:fail fail :error error}))))
