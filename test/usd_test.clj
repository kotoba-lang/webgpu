(ns usd-test
  "Golden tests for kami.usd — the Pixar USDA hiccup. They pin that EDN maps onto USD's ASCII scene
   surface: the #usda header + layer metadata, def/over specifiers, typed vs. typeless prims, nested
   prim indentation, attribute typing, tuple vs. array-of-tuples vs. scalar-array values, asset/path
   refs, and relationships. usdcat/usdchecker validate the same output for real in `bb gate`."
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.string :as str]
            [kami.usd :as u]))

(deftest values
  (is (= "double radius = 2"        (#'u/attr [:attr "double" :radius 2])))
  (is (= "float3 xformOp:translate = (1, 2, 3)"
         (#'u/attr [:attr "float3" "xformOp:translate" [1 2 3]])) "vector of scalars = tuple")
  (is (= "color3f[] primvars:displayColor = [(1, 0, 0)]"
         (#'u/attr [:attr "color3f[]" "primvars:displayColor" [[1 0 0]]])) "vector of vectors = array of tuples")
  (is (= "int[] indices = [0, 1, 2]" (#'u/attr [:attr "int[]" :indices [:array 0 1 2]])) "scalar array")
  (is (= "asset file = @./tex.png@"  (#'u/attr [:attr "asset" :file [:asset "./tex.png"]])))
  (is (= "rel material:binding = </World/mat>"
         (#'u/rel [:rel "material:binding" [:path "/World/mat"]])))
  (is (= "string note = \"he said \\\"hi\\\"\""
         (#'u/attr [:attr "string" :note "he said \"hi\""])) "internal quotes escaped"))

(deftest a-scene-layer-compiles
  (let [src (u/usda {:defaultPrim "hello" :upAxis :Y}
              [:def "Xform" :hello {:kind "component"}
               [:def "Sphere" :world
                [:attr "double" :radius 2]
                [:attr "color3f[]" "primvars:displayColor" [[1 0 0]]]]])]
    (is (str/starts-with? src "#usda 1.0\n(\n    defaultPrim = \"hello\"\n    upAxis = \"Y\"\n)"))
    (is (str/includes? src "def Xform \"hello\" (\n    kind = \"component\"\n)"))
    (is (str/includes? src "    def Sphere \"world\"\n    {\n        double radius = 2"))
    (is (str/includes? src "        color3f[] primvars:displayColor = [(1, 0, 0)]"))
    (is (= src (u/usda {:defaultPrim "hello" :upAxis :Y}
                 [:def "Xform" :hello {:kind "component"}
                  [:def "Sphere" :world
                   [:attr "double" :radius 2]
                   [:attr "color3f[]" "primvars:displayColor" [[1 0 0]]]]]))
        "deterministic")))

(deftest composition-arcs
  (let [src (u/usda {}
              [:def "Xform" :hero
               {:references [:asset "./base.usd"]
                :apiSchemas [:array "MaterialBindingAPI"]
                :kind "component"}
               [:rel :material:binding [:path "/hero/mat"]]])]
    (is (str/includes? src "prepend references = @./base.usd@") "references emitted with prepend")
    (is (str/includes? src "prepend apiSchemas = [\"MaterialBindingAPI\"]") "apiSchemas list-op")
    (is (str/includes? src "    kind = \"component\"") "plain metadata key unchanged")))

(deftest variant-set
  (let [src (u/prim
              [:def "Xform" :car
               {:variantSets [:array "wear"]}
               [:variant-set "wear"
                {"clean"   [[:def "Sphere" :body [:attr "double" :radius 2]]]
                 "damaged" [[:def "Sphere" :body [:attr "double" :radius 1]]]}]])]
    (is (str/includes? src "prepend variantSets = [\"wear\"]"))
    (is (str/includes? src "variantSet \"wear\" = {"))
    (is (str/includes? src "        \"clean\" {\n            def Sphere \"body\""))
    (is (str/includes? src "        \"damaged\" {\n            def Sphere \"body\""))))

(let [{:keys [fail error]} (run-tests 'usd-test)]
  (when (pos? (+ fail error))
    (throw (ex-info "usd tests failed" {:fail fail :error error}))))
