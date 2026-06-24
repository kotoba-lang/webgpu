;; Real-binary round-trip gate for the kami.* format hiccups. Each compiler emits source; the real
;; vendor tool then parses/assembles/simulates it — proof the EDN→source output is not just
;; golden-string-equal but actually ACCEPTED by the ecosystem. A tool that isn't installed is skipped
;; (the gate stays green in CI) but activates automatically once present. Run via `bb gate`.
(require '[babashka.process :refer [shell]]
         '[babashka.fs :as fs]
         '[clojure.string :as str]
         '[kami.scad :as scad]
         '[kami.spirv :as spirv]
         '[kami.kicad :as kicad]
         '[kami.spice :as spice]
         '[kami.usd :as usd])

(defn- have? [tool] (some? (fs/which tool)))

(def ^:private tmp (str (fs/create-temp-dir {:prefix "kami-gate-"})))
(defn- path [f] (str tmp "/" f))

;; Each gate: {:name :tool :hint :run}. :run writes its source + invokes the tool, returns true on
;; accept; it only runs when :tool is present. shell throws on non-zero exit → caught as a failure.
(def gates
  [{:name "scad → openscad" :tool "openscad" :hint "brew install openscad"
    :run (fn []
           (spit (path "part.scad")
                 (scad/scad [:def :wall 2]
                            [:module :washer [:od :id :h]
                             [:difference [:cylinder {:h :h :d :od :$fn 64}]
                              [:translate [0 0 -1] [:cylinder {:h [:+ :h 2] :d :id :$fn 64}]]]]
                            [:for [:i [:range 0 3]]
                             [:translate [[:* :i 25] 0 0] [:washer 20 8 3]]]))
           (shell {:out :string :err :string} "openscad" "-o" (path "part.csg") (path "part.scad"))
           true)}

   {:name "spirv → spirv-as" :tool "spirv-as" :hint "brew install spirv-tools"
    :run (fn []
           (spit (path "shader.spvasm")
                 (spirv/asm '[[:OpCapability Shader]
                              [:OpMemoryModel Logical GLSL450]
                              [:OpEntryPoint Fragment :main "main" :color]
                              [:OpExecutionMode :main OriginUpperLeft]
                              [:void :OpTypeVoid] [:fnty :OpTypeFunction :void]
                              [:float :OpTypeFloat 32] [:v4float :OpTypeVector :float 4]
                              [:ptr :OpTypePointer Output :v4float]
                              [:color :OpVariable :ptr Output]
                              [:c1 :OpConstant :float 1]
                              [:white :OpConstantComposite :v4float :c1 :c1 :c1 :c1]
                              [:main :OpFunction :void None :fnty] [:label :OpLabel]
                              [:OpStore :color :white] [:OpReturn] [:OpFunctionEnd]]))
           (shell "spirv-as" (path "shader.spvasm") "-o" (path "shader.spv"))
           (when (have? "spirv-val") (shell "spirv-val" (path "shader.spv")))   ;; validate if available
           true)}

   {:name "usd → usdcat" :tool "usdcat" :hint "pip install usd-core"
    :run (fn []
           (spit (path "scene.usda")
                 (usd/usda {:defaultPrim "hello" :upAxis :Y}
                           [:def "Xform" :hello {:kind "component"}
                            [:attr "float3" "xformOp:translate" [0 1 0]]
                            [:attr "uniform token[]" :xformOpOrder [:array :xformOp:translate]]
                            [:def "Sphere" :world
                             [:attr "double" :radius 2]
                             [:attr "color3f[]" "primvars:displayColor" [[1 0 0]]]]]))
           (shell {:out :string :err :string} "usdcat" (path "scene.usda"))   ;; full parse + round-trip
           true)}

   {:name "spice → ngspice" :tool "ngspice" :hint "brew install ngspice"
    :run (fn []
           (spit (path "rc.cir")
                 (spice/netlist "RC low-pass"
                                [:v 1 :in :gnd [:dc 5]]
                                [:r 1 :in :out "1k"]
                                [:c 1 :out :gnd "100n"]
                                [:op]
                                [:end]))
           (shell {:out :string :err :string} "ngspice" "-b" (path "rc.cir"))
           true)}

   ;; kicad-cli has no fragment validator (it wants a complete board), so even when installed we only
   ;; emit + sanity-check balanced parens rather than claim a real round-trip.
   {:name "kicad → balanced-sexpr" :tool nil :hint "(no fragment validator in kicad-cli)"
    :run (fn []
           (let [src (kicad/kicad [:kicad-pcb [:version 20221018]
                                   [:footprint "R_0805" [:layer "F.Cu"] [:at 10 20 0]
                                    [:pad "1" :smd :roundrect [:at -1 0] [:size 1 1.25]
                                     [:layers "F.Cu"]]]])]
             (spit (path "board.kicad_pcb") src)
             (= (count (re-seq #"\(" src)) (count (re-seq #"\)" src)))))}])

(println "── kami.* real-binary format gate ──")
(let [results (for [{:keys [name tool hint run]} gates]
                (cond
                  (and tool (not (have? tool)))
                  (do (println (format "  ⏭  %-26s skip — %s not installed (%s)" name tool hint))
                      :skip)
                  :else
                  (try (if (run)
                         (do (println (format "  ✓  %-26s accepted by %s" name (or tool "checker"))) :ok)
                         (do (println (format "  ✗  %-26s checker returned false" name)) :fail))
                       (catch Exception e
                         (println (format "  ✗  %-26s %s" name (str/trim (or (ex-message e) "error"))))
                         :fail))))
      results (doall results)
      fails   (count (filter #{:fail} results))]
  (println (format "\n%d ok · %d skipped · %d failed"
                   (count (filter #{:ok} results)) (count (filter #{:skip} results)) fails))
  (fs/delete-tree tmp)
  (when (pos? fails) (throw (ex-info "format gate failed" {:fails fails}))))
