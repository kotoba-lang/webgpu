(ns graphql-test
  "Golden tests for kami.graphql — the GraphQL SDL hiccup. They pin fields (plain / args / list & non-
   null type tokens), object types with implements, interface/input/enum/union/scalar/schema defs, and
   a whole document. (Validated for real by graphql buildSchema in `bb gate` when the npm pkg exists.)"
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.string :as str]
            [kami.graphql :as g]))

(deftest definitions
  (is (= "id: ID!"                 (#'g/field [:field :id :ID!])))
  (is (= "user(id: ID!): User"     (#'g/field [:field :user {:id :ID!} :User])) "args map")
  (is (= "tags: [String!]!"        (#'g/field [:field :tags [:list! :String!]])) "non-null list of non-null")
  (is (= "enum Role {\n  ADMIN\n  USER\n}" (g/item [:enum :Role :ADMIN :USER])))
  (is (= "union Result = User | Post"      (g/item [:union :Result :User :Post])))
  (is (= "scalar DateTime"                 (g/item [:scalar :DateTime])))
  (is (= "type User implements Node {\n  id: ID!\n}"
         (g/item [:type :User {:implements [:Node]} [:field :id :ID!]]))))

(deftest a-schema-document
  (let [src (g/graphql
              [:schema {:query :Query}]
              [:type :Query [:field :user {:id :ID!} :User] [:field :users [:list! :User!]]]
              [:type :User {:implements [:Node]}
               [:field :id :ID!] [:field :name :String!] [:field :role :Role]]
              [:interface :Node [:field :id :ID!]]
              [:enum :Role :ADMIN :USER]
              [:scalar :DateTime])]
    (is (str/starts-with? src "schema {\n  query: Query\n}"))
    (is (str/includes? src "type Query {\n  user(id: ID!): User\n  users: [User!]!\n}"))
    (is (str/includes? src "type User implements Node {"))
    (is (str/includes? src "interface Node {\n  id: ID!\n}"))
    (is (str/ends-with? src "scalar DateTime"))))

(let [{:keys [fail error]} (run-tests 'graphql-test)]
  (when (pos? (+ fail error))
    (throw (ex-info "graphql tests failed" {:fail fail :error error}))))
