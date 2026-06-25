(ns kami.pipelines
  "Render pipelines as data — the native open-world pipelines (kami-render/scene_pipelines.rs) as one
   shared EDN table, the same way the web side describes its frame in kami.webgpu/default-graph. Today
   the native pipelines are ~500 LOC of hand-written wgpu::RenderPipelineDescriptor boilerplate that
   varies in only a few fields; this table is the single source for those fields and a parity gate
   (test/pipelines_test) holds scene_pipelines.rs to it, so native + EDN can't silently diverge. The
   next step (Tier B) is generating the Rust from this table; the gate makes that safe. `.cljc`.

   Fields per pipeline: :shader (the scene_*.wgsl), :cull (:back/:front/:none), :depth-write (bool),
   :depth-compare (:less/:less-equal/…). Blend + vertex layouts are the remaining fields to fold in."
  (:require [clojure.string :as str]))

(def native-pipelines
  "The 8 open-world render pipelines as data — mirror of scene_pipelines.rs's varying fields."
  {:terrain    {:shader "scene_terrain"    :cull :back :depth-write true  :depth-compare :less}
   :sky        {:shader "scene_sky"        :cull :none :depth-write false :depth-compare :less-equal}
   :vegetation {:shader "scene_vegetation" :cull :none :depth-write true  :depth-compare :less}
   :character  {:shader "scene_character"  :cull :back :depth-write true  :depth-compare :less}
   :water      {:shader "scene_water"      :cull :none :depth-write false :depth-compare :less}
   :voxel      {:shader "scene_voxel"      :cull :back :depth-write true  :depth-compare :less}
   :particle   {:shader "scene_particle"  :cull :none :depth-write false :depth-compare :less}
   :atlas      {:shader "scene_atlas"     :cull :none :depth-write false :depth-compare :less}})

;; ── parse the hand-written Rust into the same shape, to gate drift ────────────────────────────────
(defn- kw-cull [s] (case s "Back" :back "Front" :front :none))
(defn- kw-cmp  [s] (case s "Less" :less "LessEqual" :less-equal "Greater" :greater
                           "GreaterEqual" :greater-equal "Equal" :equal "Always" :always (keyword (str/lower-case s))))

(defn parse-rust
  "Extract each native pipeline's varying fields from scene_pipelines.rs, keyed by shader. Slices the
   source into per-pipeline blocks at each shader include_str!, then takes each block's first cull/
   depth fields — so missing/inline fields align to the right pipeline (a global zip mis-aligns)."
  [src]
  (let [blocks (rest (str/split src #"include_str!\(\"shaders/"))]
    (into {}
      (for [b blocks
            :let [sh (second (re-find #"^(scene_\w+)\.wgsl" b))]
            :when sh]
        [(keyword (str/replace sh "scene_" ""))
         {:shader sh
          :cull (if-let [m (re-find #"cull_mode:\s*Some\(wgpu::Face::(\w+)\)" b)] (kw-cull (second m)) :none)
          :depth-write (= "true" (second (re-find #"depth_write_enabled:\s*(true|false)" b)))
          :depth-compare (kw-cmp (second (re-find #"depth_compare:\s*wgpu::CompareFunction::(\w+)" b)))}]))))
