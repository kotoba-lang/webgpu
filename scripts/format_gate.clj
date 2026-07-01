;; Real-binary round-trip gate for the kami.* format hiccups. Each compiler emits source; the real
;; vendor tool then parses/assembles/simulates it — proof the EDN→source output is not just
;; golden-string-equal but actually ACCEPTED by the ecosystem. A tool that isn't installed is skipped
;; (the gate stays green in CI) but activates automatically once present. Run via `bb gate`.
(require '[babashka.process :refer [shell]]
         '[babashka.fs :as fs]
         '[clojure.string :as str]
         '[kami.scad :as scad]
         '[kami.wgsl :as wgsl]
         '[kami.spirv :as spirv]
         '[kami.kicad :as kicad]
         '[kami.spice :as spice]
         '[kami.usd :as usd]
         '[kami.otio :as otio]
         '[kami.gltf :as gltf]
         '[kami.materialx :as mtlx]
         '[kami.graphml :as graphml]
         '[kami.atom :as atom-feed]
         '[kami.mathml :as mathml]
         '[kami.musicxml :as musicxml]
         '[kami.ocio :as ocio]
         '[kami.dot :as dot]
         '[kami.proto :as proto]
         '[kami.capnp :as capnp]
         '[kami.graphql :as gql]
         '[kami.json :as kjson]
         '[kami.geojson :as geojson]
         '[kami.re :as kre]
         '[kami.toml :as ktoml]
         '[kami.cue :as kcue]
         '[kami.css :as kcss]
         '[kami.html :as khtml]
         '[kami.sql :as ksql]
         '[cheshire.core :as cheshire]
         '[clj-yaml.core :as yamlc])

(defn- have? [tool] (some? (fs/which tool)))

(def ^:private tmp (str (fs/create-temp-dir {:prefix "kami-gate-"})))
(defn- path [f] (str tmp "/" f))

;; Each gate: {:name :tool :hint :run}. :run writes its source + invokes the tool, returns true on
;; accept; it only runs when :tool is present. shell throws on non-zero exit → caught as a failure.
(def gates
  [{:name "scad → openscad" :tool "openscad" :hint "brew install openscad"
    :run (fn []
           (spit (path "part.scad")
                 (scad/scad [:def :wall 2]
                            [:module :washer [:od :id :h]
                             [:difference [:cylinder {:h :h :d :od :$fn 64}]
                              [:translate [0 0 -1] [:cylinder {:h [:+ :h 2] :d :id :$fn 64}]]]]
                            [:for [:i [:range 0 3]]
                             [:translate [[:* :i 25] 0 0] [:washer 20 8 3]]]))
           (shell {:out :string :err :string} "openscad" "-o" (path "part.csg") (path "part.scad"))
           true)}

   {:name "wgsl → naga" :tool "naga" :hint "install naga-cli if available"
    :run (fn []
           (spit (path "shader.wgsl")
                 (wgsl/shader
                   (wgsl/func :fs {:stage :fragment :ret [:loc 0 [:vec4 :f32]]}
                              [:let :c [:vec3 1.0 0.0 0.0]]
                              [:return [:vec4 [:* :c 2.0] 1.0]])))
           (shell {:out :string :err :string} "naga" (path "shader.wgsl"))   ;; wgpu's translator: parse + validate
           true)}

   {:name "spirv → spirv-as" :tool "spirv-as" :hint "brew install spirv-tools"
    :run (fn []
           (spit (path "shader.spvasm")
                 (spirv/asm '[[:OpCapability Shader]
                              [:OpMemoryModel Logical GLSL450]
                              [:OpEntryPoint Fragment :main "main" :color]
                              [:OpExecutionMode :main OriginUpperLeft]
                              [:void :OpTypeVoid] [:fnty :OpTypeFunction :void]
                              [:float :OpTypeFloat 32] [:v4float :OpTypeVector :float 4]
                              [:ptr :OpTypePointer Output :v4float]
                              [:color :OpVariable :ptr Output]
                              [:c1 :OpConstant :float 1]
                              [:white :OpConstantComposite :v4float :c1 :c1 :c1 :c1]
                              [:main :OpFunction :void None :fnty] [:label :OpLabel]
                              [:OpStore :color :white] [:OpReturn] [:OpFunctionEnd]]))
           (shell "spirv-as" (path "shader.spvasm") "-o" (path "shader.spv"))
           (when (have? "spirv-val") (shell "spirv-val" (path "shader.spv")))   ;; validate if available
           true)}

   {:name "usd → usdchecker" :tool "usdchecker" :hint "pip install usd-core"
    :run (fn []
           (spit (path "scene.usda")
                 (usd/usda {:defaultPrim "hello" :upAxis :Y :metersPerUnit 0.01}
                           [:def "Xform" :hello {:kind "component"}
                            [:attr "float3" "xformOp:translate" [0 1 0]]
                            [:attr "uniform token[]" :xformOpOrder [:array :xformOp:translate]]
                            [:def "Material" :mat]
                            [:def "Sphere" :world {:apiSchemas [:array "MaterialBindingAPI"]}
                             [:attr "double" :radius 2]
                             [:attr "color3f[]" "primvars:displayColor" [[1 0 0]]]
                             [:rel :material:binding [:path "/hello/mat"]]]]))
           (shell {:out :string :err :string} "usdcat" (path "scene.usda"))       ;; parse + round-trip
           (shell {:out :string :err :string} "usdchecker" (path "scene.usda"))   ;; full schema compliance
           true)}

   {:name "re → java.util.regex" :tool nil :hint "(clj-native: emitted regex must compile + match)"
    :run (fn []
           (let [form [:seq [:+ :digit] "." [:rep :digit 2 2]]   ;; → \d+\.\d{2,2}
                 pat  (kre/re form)]                              ;; compiles via java.util.regex.Pattern
             (spit (path "pat.re") (kre/rx form))
             (and (some? (re-matches pat "3.14")) (nil? (re-matches pat "3.1")))))}

   {:name "geojson → cheshire" :tool nil :hint "(clj-native JSON round-trip — no external tool)"
    :run (fn []
           (let [doc (geojson/feature-collection
                       (geojson/feature (geojson/point 102.0 0.5) {:name "A"})
                       (geojson/feature (geojson/line-string [[102 0] [103 1]]) {}))
                 src (geojson/geojson doc)]
             (spit (path "map.geojson") src)
             (let [p (cheshire/parse-string src true)]
               (and (= "FeatureCollection" (:type p))
                    (= 2 (count (:features p)))
                    (= "Point" (get-in p [:features 0 :geometry :type]))))))}

   {:name "json → cheshire" :tool nil :hint "(clj-native JSON round-trip — no external tool)"
    :run (fn []
           (let [v {:s "tab\there, quote\" back\\slash, CR\r LF\n"
                    :nested {:n nil :t true :arr [1 2.5 "x"]}}
                 src (kjson/json v)]
             (spit (path "doc.json") src)
             (= (cheshire/parse-string src true) v)))}   ;; real JSON parser must read back exactly

   {:name "ocio → clj-yaml" :tool nil :hint "(clj-native YAML round-trip — no Python)"
    :run (fn []
           (let [src (ocio/config {:version 2 :roles {:default "raw" :scene_linear "ACEScg"}
                                   :displays [(ocio/display "sRGB" (ocio/view "ACES" "ACEScg"))]
                                   :active_displays ["sRGB"]
                                   :colorspaces [(ocio/colorspace {:name "raw" :family "raw" :isdata true})
                                                 (ocio/colorspace {:name "ACEScg" :family "ACES"})]})]
             (spit (path "config.ocio") src)
             (let [parsed (yamlc/parse-string (str/replace src #"!<[A-Za-z0-9_]+>" ""))]
               (and (= "ACEScg" (get-in parsed [:roles :scene_linear]))
                    (= 2 (count (:colorspaces parsed)))))))}

   {:name "otio → otiocat" :tool "otiocat" :hint "pipx install opentimelineio"
    :run (fn []
           (spit (path "cut.otio")
                 (otio/otio (otio/timeline "cut"
                              (otio/track "V1" :video
                                (otio/clip "s1" {:from 0 :dur 48 :rate 24} (otio/external "s1.mov"))
                                (otio/gap {:dur 12 :rate 24})
                                (otio/clip "s2" {:from 0 :dur 24 :rate 24} (otio/external "s2.mov"))))))
           (shell {:out :string :err :string} "otiocat" (path "cut.otio"))   ;; parse + round-trip
           true)}

   {:name "gltf → gltf-validator" :tool "node" :hint "node (+ npm i -g gltf-validator for full check)"
    :run (fn []
           (spit (path "scene.gltf")
                 (gltf/gltf {:generator "kami"}
                            {:scene 0 :scenes [{:nodes [0]}]
                             :nodes [(gltf/node {:name "root" :translation [0 1 0]})]
                             :materials [(gltf/material "red" [1 0 0 1])]}))
           (shell {:out :string :err :string} "node" "scripts/gltf_validate.js" (path "scene.gltf"))
           true)}

   {:name "graphql → buildSchema" :tool "node" :hint "node (+ npm i -g graphql for full parse)"
    :run (fn []
           (spit (path "schema.graphql")
                 (gql/graphql
                   [:type :Query [:field :me :User] [:field :users [:list! :User!]]]
                   [:type :User {:implements [:Node]}
                    [:field :id :ID!] [:field :name :String!] [:field :role :Role]]
                   [:interface :Node [:field :id :ID!]]
                   [:enum :Role :ADMIN :USER]))
           (shell {:out :string :err :string} "node" "scripts/graphql_validate.js" (path "schema.graphql"))
           true)}

   {:name "capnp → capnp compile" :tool "capnp" :hint "brew install capnp"
    :run (fn []
           (spit (path "schema.capnp")
                 (capnp/capnp "0xdbb9ad1f14bf0b36"
                              [:struct :Person
                               [:field :name 0 :Text] [:field :id 1 :UInt32]
                               [:field :phones 2 [:List :PhoneNumber]]
                               [:struct :PhoneNumber [:field :number 0 :Text]
                                [:enum :Type [:mobile 0] [:home 1]]]]
                              [:interface :Greeter [:method :sayHello 0 {:request :Text} {:reply :Text}]]))
           (shell {:out :string :err :string} "capnp" "compile" "-ocapnp" (path "schema.capnp"))   ;; real parse + validate
           true)}

   {:name "proto → protoc" :tool "protoc" :hint "brew install protobuf"
    :run (fn []
           (spit (path "example.proto")
                 (proto/proto {:package "example"}
                              [:message :Person
                               [:field :string :name 1] [:field :int32 :id 2]
                               [:repeated :string :emails 3]
                               [:enum :Phone [:MOBILE 0] [:HOME 1]]]
                              [:service :Greeter [:rpc :SayHello :Person :Person]]))
           (shell {:out :string :err :string}
                  "protoc" (str "--proto_path=" tmp) (str "--descriptor_set_out=" (path "out.pb"))
                  (path "example.proto"))   ;; real protoc compile → descriptor set
           true)}

   {:name "cue → cue vet" :tool "cue" :hint "brew install cue"
    :run (fn []
           (spit (path "config.cue")
                 (kcue/cue {:host "localhost" :port 8080 :tags ["a" "b"]
                            :server {:timeout 30 :tls true}
                            :#Person {:name :string :age :int}}))
           (shell {:out :string :err :string} "cue" "vet" (path "config.cue"))   ;; real CUE parse + validate
           true)}

   {:name "sql → sqlite3" :tool "sqlite3" :hint "(ships with macOS)"
    :run (fn []
           (spit (path "schema.sql")
                 (ksql/sql [:create-table :users
                            [:col :id :integer {:primary-key true}]
                            [:col :name [:varchar 255] {:not-null true}]
                            [:col :email :text {:unique true}]
                            [:col :created :timestamp {:default :current-timestamp}]]
                           [:create-index :ix_users_email :users [:email]]
                           [:insert :users [:id :name] [1 "O'Brien"]]))
           (shell {:out :string :err :string} "sqlite3" ":memory:" (str ".read " (path "schema.sql")))   ;; really run the DDL
           true)}

   {:name "toml → taplo" :tool "taplo" :hint "cargo install taplo-cli"
    :run (fn []
           (spit (path "config.toml")
                 (ktoml/toml {:title "kami config"
                              :package {:name "kami" :version "0.1.0" :keywords ["edn" "hiccup"] :edition 2021}
                              :dependencies {:serde {:version "1.0" :features ["derive"]}}
                              :bin [{:name "app" :path "src/main.rs"} {:name "tool"}]}))
           (shell {:out :string :err :string} "taplo" "check" (path "config.toml"))   ;; real TOML parse
           true)}

   {:name "dot → graphviz" :tool "dot" :hint "brew install graphviz"
    :run (fn []
           (spit (path "g.dot")
                 (dot/dot :digraph :G
                          [:graph-attr {:rankdir "LR"}]
                          [:node {:shape :box}]
                          [:n :a {:label "Start"}]
                          [:n :b {:shape :circle}]
                          [:-> :a :b {:label "go"}]
                          [:-> :b :c]))
           (shell {:out :string :err :string} "dot" "-Tsvg" (path "g.dot") "-o" (path "g.svg"))
           true)}

   {:name "html → xmllint" :tool "xmllint" :hint "(ships with macOS / libxml2)"
    :run (fn []
           (spit (path "p.html")
                 (khtml/html5
                   [:html {:lang "en"}
                    [:head [:meta {:charset "utf-8"}] [:title "Demo"]]
                    [:body [:h1 {:class "t"} "Hi"]
                     [:p "Some " [:strong "bold"] " text."]
                     [:input {:type "checkbox" :checked true}] [:br]
                     [:img {:src "a.png" :alt "a"}]]]))
           (shell {:out :string :err :string} "xmllint" "--html" "--noout" (path "p.html"))   ;; real HTML parse
           true)}

   {:name "css → esbuild" :tool "esbuild" :hint "npm i -g esbuild"
    :run (fn []
           (spit (path "style.css")
                 (kcss/css {:rules {".card" {:border-radius 12 :padding [12 22] :color :white :background "#1a1a1a"}
                                    ".card:hover" {:opacity 0.8 :transform "scale(1.02)"}}
                            :keyframes {:fade [[0 {:opacity 0}] [100 {:opacity 1}]]}}))
           (shell {:out :string :err :string} "esbuild" (path "style.css") "--minify")   ;; real CSS parse
           true)}

   {:name "musicxml → xmllint" :tool "xmllint" :hint "(ships with macOS / libxml2)"
    :run (fn []
           (spit (path "s.musicxml")
                 (musicxml/score-partwise {:part-name "Tune"}
                                          (musicxml/measure 1
                                            (musicxml/attributes {:divisions 1 :beats 4 :beat-type 4 :clef [:G 2]})
                                            (musicxml/note :C 4 1 :quarter)
                                            (musicxml/rest* 2 :half))))
           (shell {:out :string :err :string} "xmllint" "--noout" (path "s.musicxml"))   ;; well-formedness
           true)}

   {:name "atom → xmllint" :tool "xmllint" :hint "(ships with macOS / libxml2)"
    :run (fn []
           (spit (path "f.atom")
                 (atom-feed/feed
                   {:title "Blog" :id "urn:feed" :updated "2026-06-25T00:00:00Z" :link "https://x/" :author "Jun"}
                   (atom-feed/entry {:title "First" :id "urn:1" :updated "2026-06-25T00:00:00Z"
                                     :link "https://x/1" :summary "Hi"})))
           (shell {:out :string :err :string} "xmllint" "--noout" (path "f.atom"))   ;; well-formedness
           true)}

   {:name "mathml → xmllint" :tool "xmllint" :hint "(ships with macOS / libxml2)"
    :run (fn []
           (spit (path "m.mml")
                 (mathml/mathml {:display :block}
                                (mathml/row [:mi "x"] [:mo "="]
                                            (mathml/frac (mathml/row [:mo "-"] [:mi "b"])
                                                         (mathml/row [:mn 2] [:mi "a"])))))
           (shell {:out :string :err :string} "xmllint" "--noout" (path "m.mml"))   ;; well-formedness
           true)}

   {:name "graphml → xmllint" :tool "xmllint" :hint "(ships with macOS / libxml2)"
    :run (fn []
           (spit (path "g.graphml")
                 (graphml/graphml {:id "G" :edgedefault :directed}
                                  [:key {:id "d0" :for :node :attr.name "label" :attr.type :string}]
                                  [:node :a [:data "d0" "Start"]]
                                  [:node :b]
                                  [:edge :a :b]))
           (shell {:out :string :err :string} "xmllint" "--noout" (path "g.graphml"))   ;; well-formedness
           true)}

   {:name "materialx → xmllint" :tool "xmllint" :hint "(ships with macOS / libxml2)"
    :run (fn []
           (spit (path "mat.mtlx")
                 (mtlx/materialx {:version "1.38"}
                                 [:nodegraph {:name "NG_red"}
                                  [:constant {:name "c1" :type "color3"}
                                   [:input {:name "value" :type "color3" :value (mtlx/value [1 0 0])}]]
                                  [:output {:name "out" :type "color3" :nodename "c1"}]]))
           (shell {:out :string :err :string} "xmllint" "--noout" (path "mat.mtlx"))   ;; well-formedness
           true)}

   {:name "spice → ngspice" :tool "ngspice" :hint "brew install ngspice"
    :run (fn []
           (spit (path "rc.cir")
                 (spice/netlist "RC low-pass"
                                [:v 1 :in :gnd [:dc 5]]
                                [:r 1 :in :out "1k"]
                                [:c 1 :out :gnd "100n"]
                                [:op]
                                [:end]))
           (shell {:out :string :err :string} "ngspice" "-b" (path "rc.cir"))
           true)}

   ;; kicad-cli has no fragment validator (it wants a complete board), so even when installed we only
   ;; emit + sanity-check balanced parens rather than claim a real round-trip.
   {:name "kicad → balanced-sexpr" :tool nil :hint "(no fragment validator in kicad-cli)"
    :run (fn []
           (let [src (kicad/kicad [:kicad-pcb [:version 20221018]
                                   [:footprint "R_0805" [:layer "F.Cu"] [:at 10 20 0]
                                    [:pad "1" :smd :roundrect [:at -1 0] [:size 1 1.25]
                                     [:layers "F.Cu"]]]])]
             (spit (path "board.kicad_pcb") src)
             (= (count (re-seq #"\(" src)) (count (re-seq #"\)" src)))))}])

(println "── kami.* real-binary format gate ──")
(let [results (for [{:keys [name tool hint run]} gates]
                (cond
                  (and tool (not (have? tool)))
                  (do (println (format "  ⏭  %-26s skip — %s not installed (%s)" name tool hint))
                      :skip)
                  :else
                  (try (if (run)
                         (do (println (format "  ✓  %-26s accepted by %s" name (or tool "checker"))) :ok)
                         (do (println (format "  ✗  %-26s checker returned false" name)) :fail))
                       (catch Exception e
                         (println (format "  ✗  %-26s %s" name (str/trim (or (ex-message e) "error"))))
                         :fail))))
      results (doall results)
      fails   (count (filter #{:fail} results))]
  (println (format "\n%d ok · %d skipped · %d failed"
                   (count (filter #{:ok} results)) (count (filter #{:skip} results)) fails))
  (fs/delete-tree tmp)
  (when (pos? fails) (throw (ex-info "format gate failed" {:fails fails}))))
