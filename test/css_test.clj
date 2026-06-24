(ns css-test
  "Dedicated tests for kami.css — the styles hiccup (previously only touched via dsl_test). They pin the
   value rules: kebab property keys, numbers auto-`px` (except the unitless set and zero), vectors
   space-joined (padding [12 22] → 12px 22px), keyword → bare name, string passthrough, @keyframes, and
   inline style cssText. esbuild parses the same output for real in `bb gate`."
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.string :as str]
            [kami.css :as css]))

(deftest value-rules
  (is (= ".a { border-radius: 12px; }" (css/rule ".a" {:border-radius 12})) "number → px")
  (is (= ".a { opacity: 0.8; }"        (css/rule ".a" {:opacity 0.8}))      "unitless prop, no px")
  (is (= ".a { margin: 0; }"           (css/rule ".a" {:margin 0}))         "zero stays unitless")
  (is (= ".a { padding: 12px 22px; }"  (css/rule ".a" {:padding [12 22]}))  "vector → space-joined")
  (is (= ".a { color: white; }"        (css/rule ".a" {:color :white}))     "keyword → bare name")
  (is (= ".a { background: #1a1a1a; }" (css/rule ".a" {:background "#1a1a1a"})) "string passthrough")
  (is (= "color: red; font-weight: 700;" (css/style {:color :red :font-weight 700})) "inline cssText, unitless weight"))

(deftest keyframes-and-stylesheet
  (is (= "@keyframes fade { 0% { opacity: 0; } 100% { opacity: 1; } }"
         (css/kf :fade [[0 {:opacity 0}] [100 {:opacity 1}]])))
  (let [sheet (css/css {:rules {".card" {:border-radius 8 :color :white}}
                        :keyframes {:fade [[0 {:opacity 0}] [100 {:opacity 1}]]}})]
    (is (str/includes? sheet ".card { border-radius: 8px; color: white; }"))
    (is (str/includes? sheet "@keyframes fade {"))))

(let [{:keys [fail error]} (run-tests 'css-test)]
  (when (pos? (+ fail error))
    (throw (ex-info "css tests failed" {:fail fail :error error}))))
