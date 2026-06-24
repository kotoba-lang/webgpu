(ns kami.wgsl
  "WGSL as data — 'hiccup for shaders'. An EDN AST compiles to a WGSL source string, so a game's
   lighting / material / post-fx is authored and forked like the rest of the scene, and ONE shader
   source feeds both the web (kami.webgpu) and native (kami-webgpu-rs) executors — parity by source,
   not a hand-mirrored copy. `.cljc`: the same compiler runs on shadow-cljs (browser) and bb/JVM.

   Expressions (vectors are calls/operators; keywords/symbols are identifiers; numbers are f32):
     [:* a b]            → (a * b)        ;; +,-,*,/,<,>,<=,>=,==,&&,|| variadic; [:- a] is unary
     [:dot a b]          → dot(a, b)      ;; any other head is a function call (kebab→snake)
     [:vec4 r g b 1.0]   → vec4<f32>(r, g, b, 1.0)   ;; vec2/3/4, mat3/4 constructors
     [:i -1]             → -1             ;; raw integer literal (loop bounds, indices)
     [:. expr :xyz]      → (expr).xyz     ;; field / swizzle on a sub-expression
     :i.n  :g.sun-dir.xyz → i.n   g.sun_dir.xyz       ;; field paths + swizzles; kebab→snake
   Statements:
     [:let n e] [:var n e] [:var n type e] [:decl n type] [:set lhs e] [:+= lhs e] [:++ n]
     [:return e]  [:if cond [then…] [else…]]  [:for init cond step stmt…]
   Top level:
     (func :fs {:stage :fragment :params [[:i :VO]] :ret [:loc 0 [:vec4 :f32]]} stmt…)
     (struct* :VO [[:clip [:vec4 :f32] {:builtin :position}] [:n [:vec3 :f32] {:location 0}] …])
     (binding* {:group 0 :binding 0 :space :uniform} :g :G)   (shader item…)"
  (:require [clojure.string :as str]))

(defn- ident [s] (str/replace (name s) "-" "_"))

(defn- num [n]
  ;; WGSL f32 literals need a decimal point; CLJS can't tell 0 from 0.0, so float everything that
  ;; looks integral (use [:i n] for the rare integer literal — loop bounds, indices).
  (let [s (str n)]
    (if (or (str/includes? s ".") (str/includes? s "e")) s (str s ".0"))))

(def ^:private binops
  {:+ "+" :- "-" :* "*" :/ "/" :% "%"
   :< "<" :> ">" :<= "<=" :>= ">=" :== "==" :!= "!=" :&& "&&" :|| "||"})

(def ^:private ctors {:vec2 "vec2<f32>" :vec3 "vec3<f32>" :vec4 "vec4<f32>"
                      :mat3 "mat3x3<f32>" :mat4 "mat4x4<f32>"})

(defn- type-str [t]
  (cond (string? t) t                                    ;; exotic types (texture_depth_2d…) pass through
        (vector? t) (or (ctors (first t)) (ident (first t)))   ;; [:vec4 :f32] → vec4<f32>
        :else       (or (ctors t) (ident t))))                 ;; :mat4 → mat4x4<f32>, :G → G

(declare expr)
(defn- arglist [xs] (str/join ", " (map expr xs)))

(defn expr
  "Compile an EDN expression to a WGSL expression string."
  [e]
  (cond
    (number? e)  (num e)
    (string? e)  e                         ;; raw WGSL passthrough (escape hatch)
    (or (keyword? e) (symbol? e)) (ident e) ;; var / field path / swizzle
    (vector? e)
    (let [[op & xs] e]
      (cond
        (= :i op)   (str (first xs))                              ;; raw integer literal
        (= :. op)   (str "(" (expr (first xs)) ")." (ident (second xs)))  ;; field/swizzle on expr
        (binops op) (if (= 1 (count xs))
                      (str "(" (binops op) (expr (first xs)) ")")           ;; unary (e.g. -x)
                      (str "(" (str/join (str " " (binops op) " ") (map expr xs)) ")"))
        (ctors op)  (str (ctors op) "(" (arglist xs) ")")
        :else       (str (ident op) "(" (arglist xs) ")")))                 ;; function call
    :else (str e)))

(declare stmt)
(defn- block [stmts] (str/join "\n" (map #(str "  " (str/replace (stmt %) "\n" "\n  ")) stmts)))
(defn- for-step [s]
  (let [[op x] s] (case op :++ (str (ident x) "++") :-- (str (ident x) "--")
                           (str/replace (stmt s) #";$" ""))))

(defn stmt
  "Compile an EDN statement to a WGSL statement string."
  [s]
  (let [[op & xs] s]
    (case op
      :let    (str "let " (ident (first xs)) " = " (expr (second xs)) ";")
      :var    (if (= 3 (count xs))                       ;; [:var name type expr] — annotated
                (str "var " (ident (first xs)) ": " (type-str (second xs)) " = " (expr (nth xs 2)) ";")
                (str "var " (ident (first xs)) " = " (expr (second xs)) ";"))   ;; [:var name expr] — inferred
      :decl   (str "var " (ident (first xs)) ": " (type-str (second xs)) ";")   ;; declaration only
      :set    (str (ident (first xs)) " = " (expr (second xs)) ";")
      :+=     (str (ident (first xs)) " += " (expr (second xs)) ";")
      :-=     (str (ident (first xs)) " -= " (expr (second xs)) ";")
      :++     (str (ident (first xs)) "++;")
      :--     (str (ident (first xs)) "--;")
      :return (str "return " (expr (first xs)) ";")
      :if     (str "if (" (expr (first xs)) ") {\n" (block (second xs)) "\n}"
                   (when (> (count xs) 2) (str " else {\n" (block (nth xs 2)) "\n}")))
      :for    (let [[init cnd step & body] xs]
                (str "for (" (str/replace (stmt init) #";$" "") "; " (expr cnd) "; " (for-step step) ") {\n"
                     (block body) "\n}"))
      (str (expr s) ";"))))   ;; a bare expression statement

(defn- attr-str [a]
  (cond (nil? a)       ""
        (:builtin a)   (str "@builtin(" (ident (:builtin a)) ") ")
        (:location a)  (str "@location(" (:location a) ") ")
        :else          ""))

(defn- param-str [[n t a]] (str (attr-str a) (ident n) ": " (type-str t)))

(defn func
  "Compile a function form to a WGSL function declaration.
   opts: {:stage :vertex|:fragment? :params [[name type attr?] …] :ret type-or-[:loc n type]}."
  [name {:keys [stage params ret]} & body]
  (let [ret* (cond
               (nil? ret) nil
               (and (vector? ret) (= :loc (first ret)))
               (str "@location(" (second ret) ") " (type-str (nth ret 2)))
               :else (type-str ret))]
    (str (when stage (str "@" (ident stage) "\n"))
         "fn " (ident name) "(" (str/join ", " (map param-str params)) ")"
         (when ret* (str " -> " ret*)) " {\n" (block body) "\n}")))

(defn struct*
  "[:struct] form: name + fields [[field type attr?] …] → a WGSL struct declaration."
  [name fields]
  (str "struct " (ident name) " { " (str/join ", " (map param-str fields)) " };"))

(defn binding*
  "A @group/@binding resource var. opts {:group :binding :space?(:uniform/:storage)}."
  [{:keys [group binding space]} name type]
  (str "@group(" group ") @binding(" binding ") var"
       (when space (str "<" (ident space) ">")) " " (ident name) ": " (type-str type) ";"))

(defn shader
  "Assemble top-level items (struct*/binding*/func strings) into one WGSL source string."
  [& items]
  (str/join "\n" items))
