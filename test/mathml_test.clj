(ns mathml-test
  "Golden tests for kami.mathml — the MathML (equations) hiccup, built on kami.xml. They pin the
   convenience builders (row/frac/sup/sub/sqrt → the right elements), the <math> namespace wrapper +
   display attribute, and token elements (mi/mn/mo). xmllint validates the same output in `bb gate`."
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.string :as str]
            [kami.mathml :as m]))

(deftest builders
  (is (= [:mrow [:mi "x"] [:mo "="]] (m/row [:mi "x"] [:mo "="])))
  (is (= [:mfrac [:mn 1] [:mn 2]]    (m/frac [:mn 1] [:mn 2])))
  (is (= [:msup [:mi "b"] [:mn 2]]   (m/sup [:mi "b"] [:mn 2])))
  (is (= [:msub [:mi "a"] [:mn 0]]   (m/sub [:mi "a"] [:mn 0])))
  (is (= [:msqrt [:mi "x"]]          (m/sqrt [:mi "x"]))))

(deftest a-formula
  (let [src (m/mathml {:display :block}
              (m/row [:mi "x"] [:mo "="]
                (m/frac (m/row [:mo "-"] [:mi "b"]) (m/row [:mn 2] [:mi "a"]))))]
    (is (str/starts-with? src "<math xmlns=\"http://www.w3.org/1998/Math/MathML\" display=\"block\">"))
    (is (str/includes? src "<mrow>"))
    (is (str/includes? src "<mfrac>"))
    (is (str/includes? src "<mi>"))
    (is (str/includes? src "<mo>"))
    (is (str/ends-with? src "</math>"))))

(deftest no-display-attr
  (is (str/starts-with? (m/mathml [:mn 1]) "<math xmlns=\"http://www.w3.org/1998/Math/MathML\">")
      "display omitted when not given"))

(let [{:keys [fail error]} (run-tests 'mathml-test)]
  (when (pos? (+ fail error))
    (throw (ex-info "mathml tests failed" {:fail fail :error error}))))
