(ns render-shader-test
  "Gate the kami-render shaders ported to kami.render-shaders against the shipping native .wgsl. Each
   ported shader must be token-equivalent to the committed kami-render shader it mirrors (same program
   ⇒ renders identically), so the EDN can become the single source. Reads the originals from the
   co-located kami-engine checkout (skips if absent, same policy as the geometry goldens)."
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [kami.render-shaders :as rs]))

(def shaders-dir "../kami-engine/kami-render/src/shaders")

;; token stream: drop WGSL line comments, whitespace, grouping/call parens, and trailing commas
;; (all semantically insignificant) — what's left must match exactly.
(defn- canon [s]
  (-> s (str/replace #"//[^\n]*" "") (str/replace #"[\s()]" "") (str/replace #",}" "}")))

(defn- gate [file generated]
  (let [f (io/file shaders-dir file)]
    (if-not (.exists f)
      (println (str "  skip: " file " (kami-engine not co-located)"))
      (is (= (canon (slurp f)) (canon generated))
          (str file " — kami.render-shaders must be token-equivalent to the shipping WGSL")))))

(deftest scene-shaders-match-shipping
  (gate "scene_character.wgsl" (rs/scene-character))
  (gate "scene_voxel.wgsl"     (rs/scene-voxel))
  (gate "scene_particle.wgsl"  (rs/scene-particle))
  (gate "scene_terrain.wgsl"   (rs/scene-terrain))
  (gate "scene_water.wgsl"     (rs/scene-water))
  (gate "scene_sky.wgsl"       (rs/scene-sky)))

(let [{:keys [fail error]} (run-tests 'render-shader-test)]
  (when (pos? (+ fail error))
    (throw (ex-info "render-shader gate failed" {:fail fail :error error}))))
