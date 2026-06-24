(ns kami.render-shaders
  "kami-render's native open-world shaders, as data (kami.wgsl EDN AST). These ship in the native
   Rust renderer (kami-render, used by the kami-app-{game} open-world games); EDN-ifying them makes
   the shaders forkable data and lets `bb gen-wgsl` + a parity gate keep the committed .wgsl files
   token-equivalent to this source. Ported incrementally, simplest first; each is gated against the
   shipping shader (token-equivalent ⇒ same program ⇒ renders identically). `.cljc`."
  (:require [kami.wgsl :as w]))

;; ── scene_character — procedural humanoid: per-vertex colour, model transform, sun + fog ──────────
(def character-U
  [[:viewProj :mat4x4f] [:model :mat4x4f]
   [:camPos :vec3f] [:_p0 :f32]
   [:sunDir :vec3f] [:_p1 :f32]
   [:sunColor :vec3f] [:fogDensity :f32]
   [:fogColor :vec3f] [:_p2 :f32]])

(defn scene-character []
  (w/shader
   (w/struct* :U character-U)
   (w/binding* {:group 0 :binding 0 :space :uniform} :u :U)
   (w/struct* :VIn [[:pos :vec3f {:location 0}] [:normal :vec3f {:location 1}] [:color :vec3f {:location 2}]])
   (w/struct* :VO [[:cp :vec4f {:builtin :position}] [:wp :vec3f {:location 0}]
                   [:n :vec3f {:location 1}] [:col :vec3f {:location 2}]])
   (apply w/func :vs {:stage :vertex :params [[:v :VIn]] :ret :VO}
          [[:decl :o :VO]
           [:let :wp [:. [:* :u.model [:vec4f :v.pos 1.0]] :xyz]]
           [:set :o.cp [:* :u.viewProj [:vec4f :wp 1.0]]]
           [:set :o.wp :wp]
           [:set :o.n [:normalize [:. [:* :u.model [:vec4f :v.normal 0.0]] :xyz]]]
           [:set :o.col :v.color]
           [:return :o]])
   (apply w/func :fs {:stage :fragment :params [[:v :VO]] :ret [:loc 0 :vec4f]}
          [[:let :NdotL [:max [:dot :v.n :u.sunDir] 0.0]]
           [:let :ambient [:vec3f 0.45 0.46 "0.50"]]   ;; "0.50" verbatim to match the shipped literal
           [:var :col [:* :v.col [:+ :ambient [:* :u.sunColor :NdotL 0.5]]]]
           [:let :d [:length [:- :v.wp :u.camPos]]]
           [:let :f [:- 1.0 [:exp [:* [:- :d] :u.fogDensity 2.0]]]]
           [:set :col [:mix :col :u.fogColor [:clamp :f 0.0 0.9]]]
           [:return [:vec4f :col 1.0]]])))

;; ── scene_voxel — blocky chunks, half-Lambert + fog ──────────────────────────────────────────────
(defn scene-voxel []
  (w/shader
   (w/struct* :U [[:view-proj [:mat4 :f32]] [:cam-pos [:vec3 :f32]] [:_p0 :f32]
                  [:sun-dir [:vec3 :f32]] [:_p1 :f32] [:sun-color [:vec3 :f32]] [:fog-density :f32]
                  [:fog-color [:vec3 :f32]] [:_p2 :f32]])
   (w/binding* {:group 0 :binding 0 :space :uniform} :u :U)
   (w/struct* :VsIn [[:pos [:vec3 :f32] {:location 0}] [:norm [:vec3 :f32] {:location 1}] [:col [:vec3 :f32] {:location 2}]])
   (w/struct* :VsOut [[:clip [:vec4 :f32] {:builtin :position}] [:world [:vec3 :f32] {:location 0}]
                      [:norm [:vec3 :f32] {:location 1}] [:col [:vec3 :f32] {:location 2}]])
   (w/func :vs {:stage :vertex :params [[:i :VsIn]] :ret :VsOut}
           [:decl :o :VsOut]
           [:set :o.clip [:* :u.view-proj [:vec4 :i.pos "1.0"]]]
           [:set :o.world :i.pos] [:set :o.norm :i.norm] [:set :o.col :i.col]
           [:return :o])
   (w/func :fs {:stage :fragment :params [[:i :VsOut]] :ret [:loc 0 [:vec4 :f32]]}
           [:let :n [:normalize :i.norm]]
           [:let :sun [:normalize :u.sun-dir]]
           [:let :ndotl [:max [:+ [:* [:dot :n :sun] "0.5"] "0.5"] "0.0"]]
           [:let :ambient "0.35"]
           [:let :diffuse [:+ [:* :ndotl [:- "1.0" :ambient]] :ambient]]
           [:let :lit [:* :i.col :diffuse :u.sun-color]]
           [:let :dist [:length [:- :u.cam-pos :i.world]]]
           [:let :fog-t [:- "1.0" [:exp [:* [:- :dist] :u.fog-density]]]]
           [:let :color [:mix :lit :u.fog-color [:clamp :fog-t "0.0" "0.6"]]]
           [:return [:vec4 :color "1.0"]])))

;; ── scene_particle — billboard quads, soft round alpha ───────────────────────────────────────────
(defn scene-particle []
  (w/shader
   (w/struct* :U [[:view-proj :mat4] [:cam-right :vec3] [:_p0 :f32] [:cam-up :vec3] [:_p1 :f32]])
   (w/binding* {:group 0 :binding 0 :space :uniform} :u :U)
   (w/struct* :VsIn [[:quad :vec2 {:location 0}] [:ipos :vec3 {:location 1}] [:icol :vec3 {:location 2}]
                     [:isize :f32 {:location 3}] [:iage :f32 {:location 4}] [:ilife :f32 {:location 5}]])
   (w/struct* :VsOut [[:clip :vec4 {:builtin :position}] [:color :vec3 {:location 0}]
                      [:alpha :f32 {:location 1}] [:uv :vec2 {:location 2}]])
   (w/func :vs {:stage :vertex :params [[:i :VsIn]] :ret :VsOut}
           [:let :world [:+ :i.ipos [:* :u.cam-right [:* :i.quad.x :i.isize]] [:* :u.cam-up [:* :i.quad.y :i.isize]]]]
           [:decl :o :VsOut]
           [:set :o.clip [:* :u.view-proj [:vec4 :world "1.0"]]]
           [:set :o.color :i.icol]
           [:set :o.alpha [:clamp [:- "1.0" [:/ :i.iage [:max :i.ilife "0.001"]]] "0.0" "1.0"]]
           [:set :o.uv :i.quad]
           [:return :o])
   (w/func :fs {:stage :fragment :params [[:i :VsOut]] :ret [:loc 0 :vec4]}
           [:let :d [:* [:length :i.uv] "2.0"]]
           [:let :edge [:- "1.0" [:smoothstep "0.7" "1.0" :d]]]
           [:let :a [:* :i.alpha :edge]]
           [:if [:<= :a "0.0"] ["discard"]]
           [:return [:vec4 :i.color :a]])))

;; ── scene_terrain — splatmap blend (4 layers), Lambert + fog ─────────────────────────────────────
(defn scene-terrain []
  (w/shader
   (w/struct* :U [[:viewProj :mat4x4f] [:camPos :vec3f] [:_p0 :f32] [:sunDir :vec3f] [:_p1 :f32]
                  [:sunColor :vec3f] [:fogDensity :f32] [:fogColor :vec3f] [:_p2 :f32]
                  [:baseCol "array<vec4f, 4>"] [:tipCol "array<vec4f, 4>"]])
   (w/binding* {:group 0 :binding 0 :space :uniform} :u :U)
   (w/struct* :VIn [[:pos :vec3f {:location 0}] [:normal :vec3f {:location 1}]
                    [:uv :vec2f {:location 2}] [:splat :vec4f {:location 3}]])
   (w/struct* :VOut [[:clipPos :vec4f {:builtin :position}] [:wp :vec3f {:location 0}]
                     [:n :vec3f {:location 1}] [:sp :vec4f {:location 2}]])
   (w/func :vs {:stage :vertex :params [[:v :VIn]] :ret :VOut}
           [:decl :o :VOut]
           [:set :o.clipPos [:* :u.viewProj [:vec4f :v.pos "1.0"]]]
           [:set :o.wp :v.pos] [:set :o.n [:normalize :v.normal]] [:set :o.sp :v.splat]
           [:return :o])
   (w/func :fs {:stage :fragment :params [[:v :VOut]] :ret [:loc 0 :vec4f]}
           [:let :slope [:- "1.0" :v.n.y]]
           [:var :col [:vec3f "0.0"]]
           [:for [:var :i [:i 0]] [:< :i [:i 4]] [:set :i [:+ :i [:i 1]]]
            [:let :r [:clamp [:+ [:* :slope "0.6"] "0.2"] "0.0" "1.0"]]
            [:+= :col [:* [:mix "(u.baseCol[i]).xyz" "(u.tipCol[i]).xyz" :r] "v.sp[i]"]]]
           [:let :NdotL [:max [:dot :v.n :u.sunDir] "0.0"]]
           [:let :ambient [:vec3f "0.40" "0.41" "0.44"]]
           [:set :col [:* :col [:+ :ambient [:* :u.sunColor :NdotL "0.45"]]]]
           [:let :d [:length [:- :v.wp :u.camPos]]]
           [:let :fog [:- "1.0" [:exp [:* [:- :d] :u.fogDensity "2.0"]]]]
           [:set :col [:mix :col :u.fogColor [:clamp :fog "0.0" "0.92"]]]
           [:return [:vec4f :col "1.0"]])))

;; ── scene_water — Gerstner-ish wave + fresnel + spec + fog ───────────────────────────────────────
(defn scene-water []
  (w/shader
   (w/struct* :U [[:view-proj [:mat4 :f32]] [:cam-pos [:vec3 :f32]] [:time :f32] [:sun-dir [:vec3 :f32]]
                  [:water-y :f32] [:fog-color [:vec3 :f32]] [:-p0 :f32] [:base-col [:vec3 :f32]] [:-p1 :f32]])
   (w/binding* {:group 0 :binding 0 :space :uniform} :u :U)
   (w/struct* :VsIn [[:pos [:vec3 :f32] {:location 0}] [:uv [:vec2 :f32] {:location 1}]])
   (w/struct* :VsOut [[:clip [:vec4 :f32] {:builtin :position}] [:world [:vec3 :f32] {:location 0}] [:uv [:vec2 :f32] {:location 1}]])
   (w/func :vs {:stage :vertex :params [[:i :VsIn]] :ret :VsOut}
           [:decl :o :VsOut]
           [:let :k1 0.05] [:let :k2 0.07] [:let :a 0.15]
           [:let :dy [:* :a [:+ [:sin [:+ [:* :i.pos.x :k1] [:* :u.time 1.3]]] [:sin [:+ [:* :i.pos.z :k2] [:* :u.time 0.9]]]]]]
           [:let :world [:vec3 :i.pos.x [:+ :u.water-y :dy] :i.pos.z]]
           [:set :o.clip [:* :u.view-proj [:vec4 :world 1.0]]]
           [:set :o.world :world] [:set :o.uv :i.uv]
           [:return :o])
   (w/func :fs {:stage :fragment :params [[:i :VsOut]] :ret [:loc 0 [:vec4 :f32]]}
           [:let :view [:normalize [:- :u.cam-pos :i.world]]]
           [:let :up [:vec3 0.0 1.0 0.0]]
           [:let :ndotv [:clamp [:dot :up :view] 0.0 1.0]]
           [:let :fresnel [:pow [:- 1.0 :ndotv] 4.0]]
           [:let :half [:normalize [:+ :u.sun-dir :view]]]
           [:let :spec [:pow [:max [:dot :up :half] 0.0] 64.0]]
           [:let :sun-bright [:max :u.sun-dir.y 0.0]]
           [:let :dist [:length [:- :u.cam-pos :i.world]]]
           [:let :fog-t [:- 1.0 [:exp [:* [:- :dist] 0.0015]]]]
           [:let :base [:mix :u.base-col :u.fog-color [:* :fog-t 0.6]]]
           [:let :reflective [:mix :base :u.fog-color :fresnel]]
           [:let :lit [:+ :reflective [:* [:* [:vec3 1.0 0.95 0.85] :spec] :sun-bright]]]
           [:return [:vec4 :lit 0.85]])))

;; ── scene_sky — procedural sky gradient + fbm cloud raymarch ─────────────────────────────────────
(defn scene-sky []
  (w/shader
   (w/struct* :U [[:invVP :mat4x4f] [:camPos :vec3f] [:_p0 :f32] [:sunDir :vec3f] [:_p1 :f32]
                  [:fogColor :vec3f] [:overcast :f32] [:scrollX :f32] [:scrollZ :f32] [:altitude :f32] [:_p2 :f32]])
   (w/binding* {:group 0 :binding 0 :space :uniform} :u :U)
   (w/struct* :VO [[:pos :vec4f {:builtin :position}] [:ndc :vec2f {:location 0}]])
   (w/func :vs {:stage :vertex :params [[:vi :u32 {:builtin :vertex-index}]] :ret :VO}
           [:let :x [:- [:f32 "((vi & 1u) << 2u)"] 1.0]]
           [:let :y [:- [:f32 "((vi & 2u) << 1u)"] 1.0]]
           [:decl :o :VO]
           [:set :o.pos [:vec4f :x :y 1.0 1.0]]
           [:set :o.ndc [:vec2f :x :y]]
           [:return :o])
   (w/func :h2 {:params [[:p :vec2f]] :ret :f32}
           [:return [:fract [:* [:sin [:dot :p [:vec2f "127.1" "311.7"]]] "43758.5453"]]])
   (w/func :n2 {:params [[:p :vec2f]] :ret :f32}
           [:let :i [:floor :p]] [:let :f [:fract :p]]
           [:let :u [:* :f :f [:- 3.0 [:* 2.0 :f]]]]
           [:return [:mix [:mix [:h2 :i] [:h2 [:+ :i [:vec2f [:i 1] [:i 0]]]] :u.x]
                     [:mix [:h2 [:+ :i [:vec2f [:i 0] [:i 1]]]] [:h2 [:+ :i [:vec2f [:i 1] [:i 1]]]] :u.x] :u.y]])
   (w/func :fbm {:params [[:p :vec2f]] :ret :f32}
           [:var :v 0.0] [:var :a "0.5"] [:var :f 1.0]
           [:for [:var :i [:i 0]] [:< :i [:i 5]] [:set :i [:+ :i [:i 1]]]
            [:+= :v [:* :a [:n2 [:* :p :f]]]] "a *= 0.5" "f *= 2.0"]
           [:return :v])
   (w/func :fs {:stage :fragment :params [[:v :VO]] :ret [:loc 0 :vec4f]}
           [:let :wp4 [:* :u.invVP [:vec4f :v.ndc.x :v.ndc.y 1.0 1.0]]]
           [:let :vd [:normalize [:- [:/ :wp4.xyz :wp4.w] :u.camPos]]]
           [:let :elev [:max :vd.y 0.0]]
           [:let :horizon [:vec3f "0.72" "0.73" "0.75"]]
           [:let :zenith [:vec3f "0.60" "0.63" "0.67"]]
           [:var :sky [:mix :horizon :zenith [:pow :elev "0.7"]]]
           [:if [:> :vd.y "0.005"]
            [[:let :t [:/ [:- :u.altitude :u.camPos.y] :vd.y]]
             [:if [:&& [:> :t 0.0] [:< :t 6000.0]]
              [[:let :hit [:+ :u.camPos [:* :vd :t]]]
               [:let :cuv [:+ [:* :hit.xz "0.0015"] [:* [:vec2f :u.scrollX :u.scrollZ] "0.001"]]]
               [:let :cn [:+ [:* [:fbm :cuv] "0.7"] [:* [:fbm [:+ [:* :cuv "2.3"] [:vec2f "3.1"]]] "0.3"]]]
               [:let :thr [:- 1.0 :u.overcast]]
               [:let :mask [:smoothstep [:- :thr "0.05"] [:+ :thr "0.2"] :cn]]
               [:let :ccol [:mix [:vec3f "0.40" "0.42" "0.46"] [:vec3f "0.82" "0.82" "0.83"] :cn]]
               [:let :fade [:smoothstep 5500.0 400.0 :t]]
               [:set :sky [:mix :sky :ccol [:* :mask :fade]]]]]]]
           [:return [:vec4f :sky 1.0]])))
