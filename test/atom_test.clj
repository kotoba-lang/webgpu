(ns atom-test
  "Golden tests for kami.atom — the Atom syndication (RFC 4287) hiccup, built on kami.xml. They pin the
   entry/feed builders (property map → child elements, link → <link href/>, author → <author><name>),
   the <feed xmlns> + <?xml?> wrapper, and absent-key omission. xmllint validates the output in `bb gate`."
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.string :as str]
            [kami.atom :as atom]))

(deftest entry-builder
  (is (= [:entry [:title "P"] [:link {:href "https://x/1"}] [:id "urn:1"] [:updated "T"]]
         (atom/entry {:title "P" :id "urn:1" :updated "T" :link "https://x/1"}))
      "link → href attr; props become ordered child els")
  (is (= [:entry [:title "P"] [:author [:name "Jun"]]]
         (atom/entry {:title "P" :author "Jun"})) "author → author/name")
  (is (= [:entry [:title "P"]] (atom/entry {:title "P"})) "absent keys omitted"))

(deftest a-feed
  (let [src (atom/feed
              {:title "Blog" :id "urn:feed" :updated "2026-06-25T00:00:00Z" :link "https://x/" :author "Jun"}
              (atom/entry {:title "First" :id "urn:1" :updated "2026-06-25T00:00:00Z"
                           :link "https://x/1" :summary "Hi"})
              (atom/entry {:title "Second" :id "urn:2" :updated "2026-06-25T01:00:00Z"}))]
    (is (str/starts-with? src "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<feed xmlns=\"http://www.w3.org/2005/Atom\">"))
    (is (str/includes? src "<link href=\"https://x/\" />") "feed link self-closes with href")
    (is (str/includes? src "<author>\n    <name>"))
    (is (= 2 (count (re-seq #"<entry>" src))) "two entries")
    (is (str/ends-with? src "</feed>"))))

(let [{:keys [fail error]} (run-tests 'atom-test)]
  (when (pos? (+ fail error))
    (throw (ex-info "atom tests failed" {:fail fail :error error}))))
