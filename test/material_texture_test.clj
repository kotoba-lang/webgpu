(ns material-texture-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [kotoba.render.texture :as texture]))

(deftest common-mip-chain-contract
  (let [base (vec (mapcat identity [[255 0 0 255] [0 255 0 255]
                                    [0 0 255 255] [255 255 255 255]]))
        levels (texture/generate-mipmaps-cpu base 2 2
                                             (texture/mip-level-count 2 2))]
    (is (= 2 (texture/mip-level-count 2 2)))
    (is (= [{:level 1 :width 1 :height 1 :data [127 127 127 255]}]
           levels))))

(deftest webgpu-host-uploads-and-samples-mips
  (let [source (slurp (io/file "src/kami/webgpu.cljs"))]
    (is (str/includes? source ":mipLevelCount mip-level-count"))
    (is (str/includes? source ":mipLevel level"))
    (is (str/includes? source ":dimension \"2d-array\""))
    (is (str/includes? source ":origin #js [0 0 layer]"))
    (is (str/includes? source "(double (inc texture-layer))"))
    (is (str/includes? source "(def ^:private INST-FLOATS 28)"))
    (is (str/includes? source "(def ^:private INST-STRIDE (* 4 INST-FLOATS))"))
    (is (str/includes? source "(vattr \"float32x4\" 96 10)"))
    (is (str/includes? source "(render-instance/normalize-uv-transform uv-transform)"))
    (is (str/includes? source ":dimension \"cube\""))
    (is (str/includes? source "render-environment/neutral-pbr-environment"))
    (is (str/includes? source "(w3/request-adapter! (:adapter-options opts))"))
    (is (str/includes? source ".onSubmittedWorkDone"))
    (is (str/includes? source ".-onuncapturederror"))
    (is (str/includes? source ":prefiltered-specular"))
    (is (str/includes? source ":brdf-lut"))
    (is (str/includes? source ":maxAnisotropy 8"))))

(let [{:keys [fail error]} (run-tests 'material-texture-test)]
  (when (pos? (+ fail error))
    (throw (ex-info "material texture gate failed" {:fail fail :error error}))))
