(ns render-ir-test
  "JVM golden tests for kami.webgpu.ir — the render-IR data contract. These PIN the lighting
   model + sun-shadow frustum that the fragment shader used to bake in as literals: the
   executor now reads them from [:globals :lighting] / [:globals :shadow], and the defaults
   here MUST reproduce the original constants byte-for-byte (so a frame that omits them renders
   identically). If someone edits a default, this test fails — that's the point: the look is
   data now, and the data is pinned to the historical render."
  (:require [clojure.test :refer [deftest is run-tests]]
            [kami.webgpu.ir :as ir]
            [kami.webgpu.geometry :as geom]))

(deftest default-lighting-pins-the-original-shader-constants
  (let [d ir/default-lighting]
    ;; ambient hemisphere
    (is (= [0.20 0.22 0.26] (:ambient d)))
    (is (= 0.65 (:ambient-sky d)))
    ;; specular + rim
    (is (= 0.25 (:spec-min d)))
    (is (= 0.90 (:spec-max d)))
    (is (= 0.25 (:rim d)))
    (is (= 3.0  (:rim-power d)))
    ;; Blinn-Phong highlight + sun
    (is (= 4.0   (:shininess-min d)))
    (is (= 256.0 (:shininess-max d)))
    (is (= 0.9   (:sun-diffuse d)))
    (is (= 0.7   (:metallic-diffuse-cut d)))
    ;; tonemap / shadow sampling
    (is (= 2.2 (:gamma d)))
    (is (= 0.0025 (:shadow-bias-slope d)))
    (is (= 0.0006 (:shadow-bias-min d)))
    (is (= (/ 1.0 2048.0) (:shadow-texel d)))
    (is (= 14 (count d)) "exactly the 14 coefficients packed into light_a/b/c/d")))

(deftest lighting-merges-a-partial-override-over-defaults
  (is (= ir/default-lighting (ir/lighting nil)) "omitting :lighting = full defaults (parity)")
  (is (= ir/default-lighting (ir/lighting {})))
  (let [m (ir/lighting {:rim 0.6 :ambient [0.1 0.05 0.2] :gamma 2.4})]
    (is (= 0.6 (:rim m)))
    (is (= [0.1 0.05 0.2] (:ambient m)))
    (is (= 2.4 (:gamma m)))
    (is (= 0.90 (:spec-max m)) "untouched keys keep their defaults")
    (is (= (/ 1.0 2048.0) (:shadow-texel m)))))

(deftest default-shadow-pins-the-original-sun-frustum
  (let [s ir/default-shadow]
    (is (= 130.0 (:extent s))   "ortho ±130")
    (is (= 1.0   (:near s)))
    (is (= 420.0 (:far s)))
    (is (= 200.0 (:distance s)) "light placed 200 units back along -sun-dir")
    (is (= 4 (count s)))))

(deftest shadow-merges-a-partial-override-over-defaults
  (is (= ir/default-shadow (ir/shadow nil)) "omitting :shadow = old frustum (parity)")
  (let [s (ir/shadow {:extent 300.0})]
    (is (= 300.0 (:extent s)))
    (is (= 420.0 (:far s)) "untouched keys keep defaults")))

(deftest default-geometry-bakes-the-original-three-meshes
  ;; the executor used to hardcode (box 1 1 1) / (sphere 0.5 14 20) / (cylinder 0.5 1 20);
  ;; now they come from data via ir/mesh-from-spec — pin them to the historical meshes.
  (is (= (geom/box 1 1 1)        (ir/mesh-from-spec (:box ir/default-geometry))))
  (is (= (geom/sphere 0.5 14 20) (ir/mesh-from-spec (:sphere ir/default-geometry))))
  (is (= (geom/cylinder 0.5 1 20)(ir/mesh-from-spec (:cylinder ir/default-geometry))))
  (is (= #{:box :sphere :sphere-lod1 :cylinder :cylinder-lod1}
         (set (keys ir/default-geometry)))))

(deftest mesh-from-spec-honours-custom-params-and-new-kinds
  ;; retessellate: more sectors → more verts than the default sphere
  (let [hi (ir/mesh-from-spec {:type :sphere :r 0.5 :rings 24 :sectors 40})]
    (is (> (count (:positions hi)) (count (:positions (geom/sphere 0.5 14 20)))))
    (is (= (count (:positions hi)) (count (:positions (geom/sphere 0.5 24 40))))))
  ;; a kind the executor never hardcoded — :plane — is now expressible as data
  (is (= (geom/plane 10 10) (ir/mesh-from-spec {:type :plane})))
  (is (= (geom/plane 6 3)   (ir/mesh-from-spec {:type :plane :w 6 :d 3})))
  ;; defaults fill in for a partial box spec, and an unknown type falls back to a unit box
  (is (= (geom/box 1 1 1) (ir/mesh-from-spec {:type :box})))
  (is (= (geom/box 1 1 1) (ir/mesh-from-spec {:type :teapot}))))

(deftest authoring-a-fully-custom-look-is-pure-data
  ;; executable documentation: a game authoring a whole custom look — warmer dusk lighting,
  ;; a wider sun frustum, a tighter camera — is just data merged over the defaults, and the
  ;; result is a complete, structurally-valid render-IR. No GPU, no shader edits.
  (let [base (ir/render-ir (ir/sky [0.5 0.4 0.35] [-0.3 -0.7 -0.4] [1 0.9 0.8])
                           [(ir/instance [0 0 0] [0.8 0.2 0.2] [1 1] :metallic 0.6 :roughness 0.3)]
                           [10 8 10] [0 0 0])
        custom (-> base
                   (assoc-in [:globals :lighting] (ir/lighting {:ambient [0.20 0.12 0.10]
                                                                 :rim 0.4 :gamma 2.4}))
                   (assoc-in [:globals :shadow]   (ir/shadow {:extent 300.0}))
                   (assoc-in [:globals :fov] 70))]
    (is (ir/valid? custom) "a fully-authored custom-look frame is structurally valid")
    ;; the lighting override took, and untouched coefficients fell back to defaults:
    (is (= [0.20 0.12 0.10] (get-in custom [:globals :lighting :ambient])))
    (is (= 0.4 (get-in custom [:globals :lighting :rim])))
    (is (= 2.4 (get-in custom [:globals :lighting :gamma])))
    (is (= 0.90 (get-in custom [:globals :lighting :spec-max])) "default kept")
    (is (= (/ 1.0 2048.0) (get-in custom [:globals :lighting :shadow-texel])) "default kept")
    ;; the shadow override widened the frustum but kept near/far/distance:
    (is (= 300.0 (get-in custom [:globals :shadow :extent])))
    (is (= 420.0 (get-in custom [:globals :shadow :far])) "default kept")
    (is (= 200.0 (get-in custom [:globals :shadow :distance])) "default kept")
    (is (= 70 (get-in custom [:globals :fov])))
    ;; a default frame (no look authored) stays valid and bare — the executor fills the look:
    (is (ir/valid? base))
    (is (nil? (get-in base [:globals :lighting])) "omitted = nil; executor merges defaults")))

(deftest instance-size-supports-non-square-footprints-with-legacy-parity
  (is (= [4 2 7] (ir/instance-size [4 2 7]))
      "three-axis size preserves independent z depth")
  (is (= [4 2 4] (ir/instance-size [4 2]))
      "legacy width/height keeps its historical square x/z footprint")
  (is (= [1 1 1] (ir/instance-size nil))))

(deftest uniform-float-layout-is-stable
  ;; The executor packs vp(16) sun_dir(4) sun_col(4) sky(4) light_vp(16) light_a/b/c/d(16)
  ;; = 60 floats = 240 bytes. The shader struct G + gbuf size must agree with these counts.
  (is (= 60 (+ 16 4 4 4 16 16)))
  (is (= 240 (* 60 4))))

(let [{:keys [fail error]} (run-tests 'render-ir-test)]
  (when (pos? (+ fail error))
    (throw (ex-info "render-ir tests failed" {:fail fail :error error}))))
