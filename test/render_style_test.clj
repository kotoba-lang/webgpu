(ns render-style-test
  (:require [clojure.test :refer [deftest is run-tests testing]]
            [kami.webgpu.render-style :as style]))

(deftest omission-is-backwards-compatible
  (is (= {:render-style :photoreal} (style/render-profile nil)))
  (is (= {:shade-kind 0} (style/shader-uniforms nil))))

(deftest stylized-profile-is-complete-and-overridable
  (let [p (style/render-profile {:render-style :stylized
                                 :shade-color [0.12 0.18 0.3]
                                 :rim-intensity 0.7
                                 :specular-bands 5})
        u (style/shader-uniforms p)]
    (is (= :stylized (:render-style p)))
    (is (= [0.12 0.18 0.3] (:shade-color u)))
    (is (= 0.7 (:rim-intensity u)))
    (is (= 5 (:specular-bands u)))
    (is (= 3.0 (:rim-power u)) "unmentioned fields retain library defaults")
    (is (= 1 (:shade-kind u)))))

(deftest canonical-scene-style-lowers-from-render-ir-boundary
  (let [scene {:contract :kotoba.render/style-v1
               :profile :stylized
               :shading {:model :toon-pbr :bands 3 :threshold 0.46 :smoothness 0.06}
               :outline {:mode :screen-space :width-px 1.5 :color [0.02 0.03 0.05]
                         :depth-threshold 0.12 :normal-threshold 0.25}
               :color-grading {:saturation 1.08 :contrast 1.06 :exposure 0.0}}
        p (style/render-profile (assoc scene :rim-intensity 0.72))]
    (is (= :stylized (:render-style p)))
    (is (= 3 (:specular-bands p)))
    (is (= 0.46 (:toon-threshold p)))
    (is (= 0.46 (:specular-threshold p)))
    (is (= 0.06 (:toon-smooth p)))
    (is (= 0.06 (:specular-smooth p)))
    (is (= 0.72 (:rim-intensity p)) "material override survives scene envelope lowering"))
  (is (thrown? clojure.lang.ExceptionInfo
               (style/scene-style->profile {:contract :kotoba.render/style-v2
                                            :profile :stylized
                                            :shading {:model :toon-pbr}})))
  (is (thrown? clojure.lang.ExceptionInfo
               (style/scene-style->profile
                {:contract :kotoba.render/style-v1 :profile :stylized
                 :shading {:model :toon-pbr}
                 :outline {:mode :inverted-hull :width-px 1.0 :color [0 0 0]
                           :depth-threshold 0.1 :normal-threshold 0.2}}))))

(deftest profiles-are-validated-before-gpu-lowering
  (testing "unknown styles cannot silently fall through"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid render-style profile"
                          (style/render-profile {:render-style :cinematic}))))
  (doseq [bad [{:render-style :stylized :shade-color [1 0]}
               {:render-style :stylized :rim-color [1 1 2]}
               {:render-style :stylized :rim-power 0}
               {:render-style :stylized :specular-bands 0}
               {:render-style :stylized :highlight-intensity 1.1}]]
    (is (seq (style/profile-errors bad)) (pr-str bad))))

(let [{:keys [fail error]} (run-tests 'render-style-test)]
  (when (pos? (+ fail error))
    (throw (ex-info "render style gate failed" {:fail fail :error error}))))
