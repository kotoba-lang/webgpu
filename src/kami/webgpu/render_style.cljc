(ns kami.webgpu.render-style
  "Pure, serializable render-style profiles shared by CLJ/CLJS and native consumers.

   `:render-style` is the stable boundary.  The photoreal profile deliberately maps
   to the historical continuous mesh shader; stylized parameters are inert unless
   `:render-style :stylized` is selected.  Profiles are ordinary EDN, so games may
   store them with material data without depending on a GPU runtime.")

(def supported-render-styles #{:photoreal :stylized})
(def style-contract :kotoba.render/style-v1)

(def default-scene-style
  {:contract style-contract
   :profile :photoreal
   :shading {:model :pbr}
   :outline {:mode :none :width-px 0.0 :color [0.0 0.0 0.0]
             :depth-threshold 0.1 :normal-threshold 0.2}
   :color-grading {:saturation 1.0 :contrast 1.0 :exposure 0.0}})

(def default-photoreal-profile
  {:render-style :photoreal})

(def default-stylized-profile
  {:render-style :stylized
   :shade-color [0.35 0.35 0.38]
   :toon-threshold 0.4
   :toon-smooth 0.08
   :rim-color [1.0 1.0 1.0]
   :rim-intensity 0.25
   :rim-power 3.0
   :rim-lift 0.0
   :specular-bands 3
   :specular-threshold 0.45
   :specular-smooth 0.04
   :highlight-intensity 0.35})

(def default-render-profile default-photoreal-profile)

(declare finite-number? unit-number? color3?)

(defn scene-style->profile
  "Lower the canonical scene/render-IR style-v1 envelope to material shader data.

   Scene authoring location is `:render/style`; executors propagate it unchanged to
   `[:globals :render-style]`. Outline and grading remain in the envelope for their
   dedicated passes, while this mesh lowering consumes the toon-PBR fields."
  [scene-style]
  (let [{:keys [contract profile shading outline color-grading]} scene-style]
    (when-not (= style-contract contract)
      (throw (ex-info "unsupported render-style contract"
                      {:contract contract :supported style-contract})))
    (when-not (contains? supported-render-styles profile)
      (throw (ex-info "unsupported render-style profile"
                      {:profile profile :supported supported-render-styles})))
    (when-not (contains? (if (= profile :stylized) #{:toon-pbr} #{:pbr}) (:model shading))
      (throw (ex-info "shading model does not match render profile"
                      {:profile profile :model (:model shading)})))
    (when (and outline (not (contains? #{:none :screen-space} (:mode outline))))
      (throw (ex-info "unsupported outline mode (inverted-hull is reserved)"
                      {:mode (:mode outline) :supported #{:none :screen-space}})))
    (when (and outline
               (or (not (and (finite-number? (:width-px outline))
                             (<= 0.0 (:width-px outline))))
                   (not (color3? (:color outline)))
                   (not (unit-number? (:depth-threshold outline)))
                   (not (unit-number? (:normal-threshold outline)))))
      (throw (ex-info "invalid outline parameters" {:outline outline})))
    (when (and color-grading
               (or (not (and (finite-number? (:saturation color-grading))
                             (pos? (:saturation color-grading))))
                   (not (and (finite-number? (:contrast color-grading))
                             (pos? (:contrast color-grading))))
                   (not (finite-number? (:exposure color-grading)))))
      (throw (ex-info "invalid color-grading parameters" {:color-grading color-grading})))
    (cond-> {:render-style profile}
      (contains? shading :bands) (assoc :specular-bands (:bands shading))
      (contains? shading :threshold) (assoc :toon-threshold (:threshold shading)
                                            :specular-threshold (:threshold shading))
      (contains? shading :smoothness) (assoc :toon-smooth (:smoothness shading)
                                             :specular-smooth (:smoothness shading)))))

(defn- finite-number? [x]
  (and (number? x)
       #?(:clj (Double/isFinite (double x))
          :cljs (js/Number.isFinite x))))

(defn- unit-number? [x]
  (and (finite-number? x) (<= 0.0 x 1.0)))

(defn- color3? [x]
  (and (sequential? x) (= 3 (count x)) (every? unit-number? x)))

(defn profile-errors
  "Return deterministic validation errors for a complete or partial profile."
  [m]
  (let [style (or (:render-style m) :photoreal)
        ranged {:toon-threshold unit-number? :toon-smooth unit-number?
                :rim-intensity unit-number? :rim-lift unit-number?
                :specular-threshold unit-number? :specular-smooth unit-number?
                :highlight-intensity unit-number?}
        positive [:rim-power]
        errors (cond-> []
                 (not (map? m))
                 (conj {:path [] :error :profile-must-be-map})

                 (not (contains? supported-render-styles style))
                 (conj {:path [:render-style] :error :unsupported-render-style
                        :value style :supported supported-render-styles})

                 (and (contains? m :shade-color) (not (color3? (:shade-color m))))
                 (conj {:path [:shade-color] :error :expected-unit-rgb})

                 (and (contains? m :rim-color) (not (color3? (:rim-color m))))
                 (conj {:path [:rim-color] :error :expected-unit-rgb}))]
    (-> errors
        (into (keep (fn [[k pred]]
                      (when (and (contains? m k) (not (pred (get m k))))
                        {:path [k] :error :expected-unit-number}))
                    ranged))
        (into (keep (fn [k]
                      (when (and (contains? m k)
                                 (not (and (finite-number? (get m k)) (pos? (get m k)))))
                        {:path [k] :error :expected-positive-number}))
                    positive))
        (cond-> (and (contains? m :specular-bands)
                     (not (and (integer? (:specular-bands m))
                               (<= 1 (:specular-bands m) 16))))
          (conj {:path [:specular-bands] :error :expected-band-count-1-to-16})))))

(defn render-profile
  "Resolve a partial EDN profile over style defaults, or throw on invalid data.

   Nil preserves the historical continuous-lighting contract."
  [m]
  (let [m (or m {})
        m (if (contains? m :contract)
            (merge (scene-style->profile m)
                   (apply dissoc m [:contract :profile :shading :outline :color-grading]))
            m)
        errors (profile-errors m)]
    (when (seq errors)
      (throw (ex-info "invalid render-style profile" {:errors errors :profile m})))
    (merge (case (or (:render-style m) :photoreal)
             :stylized default-stylized-profile
             default-photoreal-profile)
           m)))

(defn shader-uniforms
  "Lower profile EDN to the mesh shader's portable uniform data contract."
  [m]
  (let [p (render-profile m)]
    (if (= :stylized (:render-style p))
      {:shade-kind 1
       :toon-threshold (:toon-threshold p)
       :toon-smooth (:toon-smooth p)
       :shade-color (:shade-color p)
       :rim-color (:rim-color p)
       :rim-intensity (:rim-intensity p)
       :rim-power (:rim-power p)
       :rim-lift (:rim-lift p)
       :specular-bands (:specular-bands p)
       :specular-threshold (:specular-threshold p)
       :specular-smooth (:specular-smooth p)
       :highlight-intensity (:highlight-intensity p)}
      {:shade-kind 0})))
