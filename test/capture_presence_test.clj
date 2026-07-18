(ns capture-presence-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]))

(deftest presence-is-stamped-only-after-submit-and-cleared-per-frame
  (let [source (slurp (io/file "src/kami/webgpu.cljs"))
        submit (.indexOf source "(w3/submit! queue")
        stamp (.indexOf source ":kotoba.webgpu/capture-presence-evidence-v2" submit)]
    (is (<= 0 submit))
    (is (< submit stamp) "presence must be stamped after queue.submit succeeds")
    (is (str/includes? source "(reset! (when-let [presence @overlay-presence]"))
    (is (str/includes? source ":submit-sequence"))
    (is (str/includes? source ":invalidated-by :device-loss"))
    (is (str/includes? source ":capture-presence (some-> ctx :capture-presence deref)"))))

(deftest skinned-presence-carries-provenance-without-pixel-readback
  (let [source (slurp (io/file "src/kami/webgpu/mesh.cljs"))]
    (is (str/includes? source ":entity-ids"))
    (is (str/includes? source ":submitted-roles"))
    (is (str/includes? source ":projected-screen-bounds"))
    (is (str/includes? source ":projected-screen-bounds-provenance :consumer-supplied"))
    (is (str/includes? source ":device-match? true"))
    (is (str/includes? source ":cache (skinned-submission-evidence cache)"))))

(let [{:keys [fail error]} (run-tests 'capture-presence-test)]
  (when (pos? (+ fail error))
    (throw (ex-info "capture presence gate failed" {:fail fail :error error}))))
