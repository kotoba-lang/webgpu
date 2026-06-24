(ns scad-test
  "Golden tests for kami.scad — the OpenSCAD hiccup. They pin that an EDN CSG tree compiles to the
   .scad a part expects: primitives (positional / sized / named args), transforms wrapping children,
   boolean operators, kebab→snake op names, `$fn` passthrough, and a small parametric module. The
   compiler being .cljc, this same source previews in the browser and feeds headless `openscad`."
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.string :as str]
            [kami.scad :as s]))

(deftest primitives
  (is (= "cube(10);"                      (s/node [:cube 10])))
  (is (= "cube([10, 20, 30]);"            (s/node [:cube [10 20 30]])))
  (is (= "cube(size=[2, 3, 4], center=true);" (s/node [:cube {:size [2 3 4] :center true}])))
  (is (= "sphere(r=5, $fn=64);"           (s/node [:sphere {:r 5 :$fn 64}])) "$fn key passes through")
  (is (= "cylinder(h=10, r1=5, r2=0);"    (s/node [:cylinder {:h 10 :r1 5 :r2 0}])))
  (is (= "text(\"Hi\", size=8);"          (s/node [:text "Hi" {:size 8}])) "positional + named")
  (is (= "polygon([[0, 0], [10, 0], [5, 8]]);" (s/node [:polygon [[0 0] [10 0] [5 8]]])))
  (is (= "text(\"say \\\"hi\\\"\");" (s/node [:text "say \"hi\""])) "internal quotes escaped, not broken"))

(deftest for-comprehension
  (is (= "for (i = [0:5]) {\n  translate([(i * 10), 0, 0]) {\n    sphere(2);\n  }\n}"
         (s/node [:for [:i [:range 0 5]]
                  [:translate [[:* :i 10] 0 0] [:sphere 2]]]))
      "range iterable + expression coordinate")
  (is (= "for (a = [0:0.5:5]) {\n  cube(1);\n}"
         (s/node [:for [:a [:range 0 0.5 5]] [:cube 1]])) "stepped range")
  (is (= "for (n = [1, 2, 3]) {\n  cube(n);\n}"
         (s/node [:for [:n [1 2 3]] [:cube :n]])) "value-list iterable"))

(deftest expressions-in-args
  (is (= "cylinder(h=(h + 2), r=r);" (s/node [:cylinder {:h [:+ :h 2] :r :r}]))
      "arithmetic in a named arg (shared kami.expr), not a point")
  (is (= "translate([(i * step), 0, 0]) {\n  sphere(1);\n}"
         (s/node [:translate [[:* :i :step] 0 0] [:sphere 1]]))
      "expression as a coordinate component inside a point"))

(deftest transforms-wrap-children
  (is (= "translate([10, 0, 0]) {\n  cube(5);\n}"
         (s/node [:translate [10 0 0] [:cube 5]])))
  (is (= "linear_extrude(height=4) {\n  circle(3);\n}"
         (s/node [:linear-extrude {:height 4} [:circle 3]])) "kebab→snake op name")
  (is (= "color(\"red\") {\n  sphere(2);\n}"
         (s/node [:color "red" [:sphere 2]]))))

(deftest boolean-operators
  (is (= "difference() {\n  cube(10);\n  translate([2, 2, 2]) {\n    sphere(6);\n  }\n}"
         (s/node [:difference [:cube 10] [:translate [2 2 2] [:sphere 6]]]))
      "nested children indent")
  (is (= "union() {\n  cube(1);\n  sphere(1);\n}"
         (s/node [:union [:cube 1] [:sphere 1]]))))

(deftest a-parametric-part-compiles
  (let [src (s/scad [:def :wall 2]
                    [:module :pillar [:h :r]
                     [:cylinder {:h :h :r :r :$fn 48}]]
                    [:difference
                     [:pillar 20 4]
                     [:translate [0 0 -1] [:pillar 22 2]]])]
    (is (str/includes? src "wall = 2;"))
    (is (str/includes? src "module pillar(h, r) {"))
    (is (str/includes? src "cylinder(h=h, r=r, $fn=48);"))
    (is (str/includes? src "difference() {"))
    (is (str/includes? src "pillar(20, 4);")  "user module instantiated by bare call")
    (is (= src (s/scad [[:def :wall 2]
                        [:module :pillar [:h :r]
                         [:cylinder {:h :h :r :r :$fn 48}]]
                        [:difference
                         [:pillar 20 4]
                         [:translate [0 0 -1] [:pillar 22 2]]]]))
        "varargs and single-seq forms agree — deterministic")))

(let [{:keys [fail error]} (run-tests 'scad-test)]
  (when (pos? (+ fail error))
    (throw (ex-info "scad tests failed" {:fail fail :error error}))))
