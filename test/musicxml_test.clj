(ns musicxml-test
  "Golden tests for kami.musicxml — the MusicXML (score) hiccup, built on kami.xml. They pin the
   builders (note/rest/attributes/measure → the right element tree) and the score-partwise document
   wrapper (part-list/part/measure). xmllint validates the same output in `bb gate`."
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.string :as str]
            [kami.musicxml :as m]))

(deftest builders
  (is (= [:note [:pitch [:step "C"] [:octave 4]] [:duration 1] [:type "quarter"]]
         (m/note :C 4 1 :quarter)))
  (is (= [:note [:pitch [:step "G"] [:octave 5]] [:duration 2]] (m/note :G 5 2)) "type optional")
  (is (= [:note [:rest] [:duration 4] [:type "whole"]] (m/rest* 4 :whole)))
  (is (= [:attributes [:divisions 1] [:key [:fifths 0]]
          [:time [:beats 4] [:beat-type 4]] [:clef [:sign "G"] [:line 2]]]
         (m/attributes {:divisions 1 :fifths 0 :beats 4 :beat-type 4 :clef [:G 2]})))
  (is (= [:measure {:number 1} [:note [:pitch [:step "C"] [:octave 4]] [:duration 1]]]
         (m/measure 1 (m/note :C 4 1)))))

(deftest a-score-document
  (let [src (m/score-partwise {:part-name "Tune"}
              (m/measure 1
                (m/attributes {:divisions 1 :beats 4 :beat-type 4 :clef [:G 2]})
                (m/note :C 4 1 :quarter)
                (m/rest* 2 :half)))]
    (is (str/starts-with? src "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<score-partwise version=\"4.0\">"))
    (is (str/includes? src "<score-part id=\"P1\">"))
    (is (str/includes? src "<part-name>"))
    (is (str/includes? src "<measure number=\"1\">"))
    (is (str/includes? src "<step>"))
    (is (str/includes? src "<rest />") "rest is an empty element")
    (is (str/ends-with? src "</score-partwise>"))))

(let [{:keys [fail error]} (run-tests 'musicxml-test)]
  (when (pos? (+ fail error))
    (throw (ex-info "musicxml tests failed" {:fail fail :error error}))))
