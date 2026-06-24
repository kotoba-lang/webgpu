(ns mermaid-test
  "Golden tests for kami.mermaid — the Mermaid flowchart hiccup. They pin node shapes, plain/labeled
   edges, dotted/thick arrows, subgraph nesting, and a whole flowchart with a header direction."
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.string :as str]
            [kami.mermaid :as m]))

(deftest statements
  (is (= "A[Start]"        (m/stmt [:node :A {:label "Start"}])) "square default")
  (is (= "B{Go?}"          (m/stmt [:node :B {:label "Go?" :shape :diamond}])) "diamond")
  (is (= "E((End))"        (m/stmt [:node :E {:label "End" :shape :circle}])) "circle")
  (is (= "A"               (m/stmt [:node :A])) "bare id reference")
  (is (= "A --> B"         (m/stmt [:--> :A :B])))
  (is (= "B -->|yes| C"    (m/stmt [:--> :B :C {:label "yes"}])) "labeled edge")
  (is (= "A -.-> B"        (m/stmt [:-.-> :A :B])) "dotted")
  (is (= "A ==> B"         (m/stmt [:==> :A :B])) "thick")
  (is (= "subgraph grp\n  A --> B\nend" (m/stmt [:subgraph :grp [:--> :A :B]])) "subgraph nesting"))

(deftest label-escaping
  (is (= "a[\"a]b (x)\"]" (m/stmt [:node :a {:label "a]b (x)"}])) "delimiter chars → quoted label")
  (is (= "c[\"say #quot;hi#quot;\"]" (m/stmt [:node :c {:label "say \"hi\""}])) "internal quote → #quot;")
  (is (= "B[Decision]" (m/stmt [:node :B {:label "Decision"}])) "plain label stays bare")
  (is (= "A -->|\"a|b\"| B" (m/stmt [:--> :A :B {:label "a|b"}])) "pipe in edge label → quoted"))

(deftest a-flowchart
  (let [src (m/flowchart :LR
              [:node :A {:label "Start"}]
              [:node :B {:label "Decision" :shape :diamond}]
              [:--> :A :B]
              [:--> :B :C {:label "yes"}]
              [:--> :B :D {:label "no"}]
              [:node :C {:label "Do" :shape :round}]
              [:--> :C :E])]
    (is (str/starts-with? src "flowchart LR\n  A[Start]"))
    (is (str/includes? src "  B{Decision}"))
    (is (str/includes? src "  B -->|yes| C"))
    (is (str/includes? src "  C(Do)"))
    (is (str/ends-with? src "  C --> E"))))

(let [{:keys [fail error]} (run-tests 'mermaid-test)]
  (when (pos? (+ fail error))
    (throw (ex-info "mermaid tests failed" {:fail fail :error error}))))
