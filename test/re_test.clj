(ns re-test
  "Golden + validity tests for kami.re — the regex hiccup (previously exercised only indirectly). They
   pin the rx compiler (literals/escaping, shorthand tokens, seq/or, quantifiers, classes, groups) AND
   prove the emitted source is a REAL regex by compiling it with java.util.regex.Pattern and matching —
   a clj-native gate, no external tool."
  (:require [clojure.test :refer [deftest is run-tests]]
            [kami.re :as re]))

(deftest compiles-forms
  (is (= "abc"            (re/rx "abc"))               "literal")
  (is (= "a\\.b"          (re/rx "a.b"))               "metachar in literal escaped")
  (is (= "\\d"            (re/rx :digit))              "shorthand token")
  (is (= "\\d+"           (re/rx [:+ :digit]))         "one-or-more")
  (is (= "(?:cat|dog)"    (re/rx [:or "cat" "dog"]))   "alternation")
  (is (= "\\d{2,4}"       (re/rx [:rep :digit 2 4]))   "bounded repeat")
  (is (= "[ab\\d]"        (re/rx [:class \a \b :digit])) "character class")
  (is (= "[^\\s]"         (re/rx [:not :space]))       "negated class")
  (is (= "(\\w+)"         (re/rx [:group [:+ :word]])) "capture group")
  (is (= "^\\w*$"         (re/rx [:seq :start [:* :word] :end])) "anchors + seq"))

(deftest emitted-regex-is-valid-and-matches
  ;; clj-native validity: the source must compile as a real Pattern and behave.
  (doseq [[form yes no] [[[:seq [:+ :digit] "." [:rep :digit 2 2]] "3.14" "3.1"]
                         [[:or "cat" "dog"] "dog" "cow"]
                         [[:seq :start [:class \a \b] [:* :word] :end] "abc" "xyz"]]]
    (let [pat (re/re form)]       ;; re/re → a platform Pattern
      (is (some? (re-matches pat yes)) (str (pr-str form) " matches " yes))
      (is (nil?  (re-matches pat no))  (str (pr-str form) " rejects " no)))))

(let [{:keys [fail error]} (run-tests 're-test)]
  (when (pos? (+ fail error))
    (throw (ex-info "re tests failed" {:fail fail :error error}))))
