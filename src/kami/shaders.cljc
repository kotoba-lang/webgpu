(ns kami.shaders
  "Game shaders as data — the kami.wgsl EDN AST for the lit instanced renderer's fragment lighting.
   The lighting model (hemisphere ambient, Lambert, Blinn-Phong spec, Fresnel rim, PCF shadow,
   Reinhard tonemap, gamma) is authored as data, so it reads and forks like the scene, and ONE
   source generates the WGSL the web (kami.webgpu) runs — and, in time, the native kami-webgpu-rs
   path (parity by source). The struct/bindings/shadow/vertex preamble stays a template in
   kami.webgpu; this is the fragment a designer actually tunes. `.cljc` → browser + bb/JVM."
  (:require [kami.wgsl :as w]))

;; lighting inputs come from the G uniform (g.light_a..d pack the tunables) and the VO varying `i`.
(def lit-fs-body
  [[:let :N [:normalize :i.n]]
   [:let :L [:normalize [:- :g.sun-dir.xyz]]]
   [:let :eye [:vec3 :g.sun-dir.w :g.sun-col.w :g.sky.w]]
   [:let :V [:normalize [:- :eye :i.wpos]]]
   [:let :H [:normalize [:+ :L :V]]]
   [:let :ndl [:max [:dot :N :L] 0.0]]
   [:let :metallic [:clamp :i.mat.x 0.0 1.0]]
   [:let :rough [:clamp :i.mat.y 0.04 1.0]]
   [:let :emissive :i.mat.z]
   [:let :amb [:mix :g.light-a.rgb [:* :g.sky.rgb :g.light-a.w] [:+ [:* :N.y 0.5] 0.5]]]
   [:let :shininess [:mix :g.light-c.x :g.light-c.y [:- 1.0 :rough]]]
   [:let :specStr [:mix :g.light-b.x :g.light-b.y :metallic]]
   [:let :specTint [:mix [:vec3 1.0] :i.col :metallic]]
   [:let :spec [:* [:pow [:max [:dot :N :H] 0.0] :shininess] :specStr]]
   [:let :rim [:* [:pow [:- 1.0 [:max [:dot :N :V] 0.0]] :g.light-b.w] :g.light-b.z]]
   [:let :sh [:shadow :i.wpos :ndl]]
   [:var :c [:+ [:* :i.col [:+ :amb [:* :ndl :g.sun-col.rgb :g.light-c.z [:- 1.0 [:* :metallic :g.light-c.w]] :sh]]]
                [:* :specTint :g.sun-col.rgb :spec :sh]
                [:* :g.sky.rgb :rim]
                [:* :i.col :emissive]]]
   [:set :c [:/ :c [:+ :c [:vec3 1.0]]]]            ;; Reinhard tonemap
   [:set :c [:pow :c [:vec3 [:/ 1.0 :g.light-d.x]]]] ;; gamma
   [:return [:vec4 :c 1.0]]])

(defn lit-fs
  "The lit instanced renderer's fragment shader, generated from data."
  []
  (apply w/func :fs {:stage :fragment :params [[:i :VO]] :ret [:loc 0 [:vec4 :f32]]} lit-fs-body))

;; ── the full shader as data: uniforms, shadow-map bindings, PCF shadow fn, varyings, vertex ──────

(def G-fields
  [[:vp :mat4] [:sun-dir :vec4] [:sun-col :vec4] [:sky :vec4] [:light-vp :mat4]
   [:light-a :vec4] [:light-b :vec4] [:light-c :vec4] [:light-d :vec4]])

;; 3×3 PCF percentage-closer shadow lookup (clamps outside the light frustum to lit).
(def shadow-fn-body
  [[:let :lc [:* :g.light-vp [:vec4 :wpos 1.0]]]
   [:let :ndc [:/ :lc.xyz :lc.w]]
   [:let :uv [:vec2 [:+ [:* :ndc.x 0.5] 0.5] [:- 0.5 [:* :ndc.y 0.5]]]]
   [:if [:|| [:< :uv.x 0.0] [:> :uv.x 1.0] [:< :uv.y 0.0] [:> :uv.y 1.0] [:> :ndc.z 1.0]]
    [[:return 1.0]]]
   [:let :bias [:max [:* :g.light-d.y [:- 1.0 :ndl]] :g.light-d.z]]
   [:let :texel :g.light-d.w]
   [:var :lit 0.0]
   [:for [:var :dx [:i -1]] [:<= :dx [:i 1]] [:++ :dx]
    [:for [:var :dy [:i -1]] [:<= :dy [:i 1]] [:++ :dy]
     [:+= :lit [:textureSampleCompareLevel :shadowMap :shadowSamp
                [:+ :uv [:* [:vec2 [:f32 :dx] [:f32 :dy]] :texel]]
                [:- :ndc.z :bias]]]]]
   [:return [:/ :lit 9.0]]])

(def VO-fields
  [[:clip [:vec4 :f32] {:builtin :position}] [:n [:vec3 :f32] {:location 0}]
   [:col [:vec3 :f32] {:location 1}] [:wpos [:vec3 :f32] {:location 2}] [:mat [:vec3 :f32] {:location 3}]])

;; per-instance model matrix (m0..m3) + color + material → clip-space + world varyings.
(def vs-body
  [[:let :model [:mat4 :m0 :m1 :m2 :m3]]
   [:let :world [:* :model [:vec4 :pos 1.0]]]
   [:decl :o :VO]
   [:set :o.clip [:* :g.vp :world]]
   [:set :o.n [:normalize [:. [:* :model [:vec4 :normal 0.0]] :xyz]]]
   [:set :o.col :color.rgb]
   [:set :o.wpos :world.xyz]
   [:set :o.mat :material.xyz]
   [:return :o]])

(defn lit-shader
  "The complete lit instanced renderer WGSL — generated entirely from data (struct/bindings/shadow/
   vertex/fragment). One source; the web (kami.webgpu) and, in time, native run the same shader."
  []
  (w/shader
   (w/struct* :G G-fields)
   (w/binding* {:group 0 :binding 0 :space :uniform} :g :G)
   (w/binding* {:group 0 :binding 1} :shadowMap "texture_depth_2d")
   (w/binding* {:group 0 :binding 2} :shadowSamp "sampler_comparison")
   (apply w/func :shadow {:params [[:wpos [:vec3 :f32]] [:ndl :f32]] :ret :f32} shadow-fn-body)
   (w/struct* :VO VO-fields)
   (apply w/func :vs {:stage :vertex
                      :params [[:pos [:vec3 :f32] {:location 0}] [:normal [:vec3 :f32] {:location 1}]
                               [:m0 [:vec4 :f32] {:location 2}] [:m1 [:vec4 :f32] {:location 3}]
                               [:m2 [:vec4 :f32] {:location 4}] [:m3 [:vec4 :f32] {:location 5}]
                               [:color [:vec4 :f32] {:location 6}] [:material [:vec4 :f32] {:location 7}]]
                      :ret :VO} vs-body)
   (lit-fs)))
