(ns toml-test
  "Golden tests for kami.toml — the TOML hiccup. They pin scalar/array/inline-table values, [table]
   sections, dotted nested tables, array-of-tables [[name]], and the bare-keys-before-subtables rule.
   taplo validates the same output for real in `bb gate`."
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.string :as str]
            [kami.toml :as t]))

(deftest scalars-and-tables
  (is (= "title = \"x\"\n"              (t/toml {:title "x"})) "string scalar")
  (is (= "n = 42\nb = true\n"           (t/toml {:n 42 :b true})) "number + bool")
  (is (= "xs = [1, 2, 3]\n"             (t/toml {:xs [1 2 3]})) "inline array")
  (is (= "[a]\nk = \"v\"\n"             (t/toml {:a {:k "v"}})) "nested map → [table], no leading blank line"))

(deftest a-config
  (let [src (t/toml {:title "cfg"
                     :package {:name "kami" :version "0.1.0" :keywords ["edn" "hiccup"]}
                     :dependencies {:serde {:version "1.0" :features ["derive"]}}
                     :bin [{:name "app" :path "src/main.rs"} {:name "tool"}]})]
    (is (str/starts-with? src "title = \"cfg\"\n\n[package]"))
    (is (str/includes? src "name = \"kami\"\nversion = \"0.1.0\"\nkeywords = [\"edn\", \"hiccup\"]"))
    (is (str/includes? src "[dependencies.serde]\nversion = \"1.0\"\nfeatures = [\"derive\"]"))
    (is (str/includes? src "[[bin]]\nname = \"app\"\npath = \"src/main.rs\""))
    (is (str/includes? src "[[bin]]\nname = \"tool\""))
    (is (str/ends-with? src "\n"))))

(let [{:keys [fail error]} (run-tests 'toml-test)]
  (when (pos? (+ fail error))
    (throw (ex-info "toml tests failed" {:fail fail :error error}))))
