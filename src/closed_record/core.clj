(ns closed-record.core
  "ClosedRecord - A map wrapper that validates key access against a schema.

  **Prevents silent nil bugs from typos in map keys. Essential for any Clojure
  app with map-heavy data, especially when working with LLMs that may hallucinate
  key names.

  Example:
    (def issue {:title \"Bug fix\" :description \"Fix the bug\" :priority 0})
    (def cr (closed-record issue))

    ;; Valid access works normally
    (:title cr)                    ;=> \"Bug fix\"
    (get cr :description)          ;=> \"Fix the bug\"
    (assoc cr :priority 1)         ;=> updated ClosedRecord

    ;; Invalid access fails loudly
    (:titel cr)                    ;=> throws ex-info (typo caught!)
    (assoc cr :priorit 1)          ;=> throws ex-info

  Options:
    :schema                - Set of allowed keys (default: keys from data)
    :throw-on-invalid-read - Throw on invalid reads (default: true)
    :throw-on-invalid-write - Throw on invalid writes (default: true)
    :spec                  - Derive schema from clojure.spec
    :recursive             - Recursively wrap nested maps (default: false)"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint]
            [clojure.set]
            [clojure.spec.alpha :as s]))

;; Sentinel value for invalid key access
(def INVALID-KEY :INVALID-KEY)

;; Spec support functions

(defn- extract-keys-from-spec
  "Extract the set of valid keys from a clojure.spec.

  Supports s/keys forms with:
  - :req-un (required unqualified keys)
  - :opt-un (optional unqualified keys)
  - :req (required namespaced keys)
  - :opt (optional namespaced keys)

  Examples:
    (s/def ::user (s/keys :req-un [::user-id ::username]
                          :opt-un [::email]))
    (extract-keys-from-spec ::user)
    ;=> #{:user-id :username :email}

    (s/def ::person (s/keys :req [:person/id :person/name]))
    (extract-keys-from-spec ::person)
    ;=> #{:person/id :person/name}"
  [spec-kw]
  (when spec-kw
    (try
      (when-let [spec-form (s/form spec-kw)]
        (cond
          ;; Handle (s/keys :req-un [...] :opt-un [...] :req [...] :opt [...])
          (and (seq? spec-form)
               (= 'clojure.spec.alpha/keys (first spec-form)))
          (let [args (apply hash-map (rest spec-form))
                ;; Unqualified keys - remove namespace
                req-un (map #(keyword (name %)) (get args :req-un []))
                opt-un (map #(keyword (name %)) (get args :opt-un []))
                ;; Namespaced keys - keep as-is
                req (get args :req [])
                opt (get args :opt [])]
            (set (concat req-un opt-un req opt)))

          ;; Unsupported spec form
          :else
          nil))
      (catch Exception e
        nil))))

;; Recursive nested spec support functions

(defn- extract-nested-specs
  "Extract nested spec keywords from a parent spec.

  Returns map of key-name -> spec-keyword for nested specs.

  Example:
    (s/def ::user-entity (s/keys :opt-un [::user-id ::name]))
    (s/def ::message (s/keys :req-un [::message-key ::user-obj]))

    (extract-nested-specs ::message)
    ;=> {:user-obj ::user-entity}"
  [spec-kw]
  (when spec-kw
    (try
      (when-let [spec-form (s/form spec-kw)]
        (when (and (seq? spec-form)
                   (= 'clojure.spec.alpha/keys (first spec-form)))
          (let [args (apply hash-map (rest spec-form))
                all-key-specs (concat (get args :req-un [])
                                      (get args :opt-un [])
                                      (get args :req [])
                                      (get args :opt []))]
            ;; For each key spec, check if it has a registered spec
            (->> all-key-specs
                 (keep (fn [key-spec]
                         (let [key-name (keyword (name key-spec))]
                           ;; Try to get the spec for this key
                           (when-let [nested-spec (s/get-spec key-spec)]
                             ;; Return [key-name spec-keyword]
                             [key-name key-spec]))))
                 (into {})))))
      (catch Exception e
        {}))))

(declare closed-record) ; Forward declaration for recursion

(defn- wrap-nested-value
  "Recursively wrap a value based on its type and nested spec.

  - Map: Wrap as ClosedRecord with nested spec
  - Vector/List of maps: Wrap each map as ClosedRecord
  - Nil or other values: Return as-is"
  [value nested-spec-kw opts]
  (cond
    ;; Nil - return as-is
    (nil? value)
    nil

    ;; Single map - wrap it recursively
    (map? value)
    (closed-record value (assoc opts :spec nested-spec-kw))

    ;; Collection of maps - wrap each element
    (and (coll? value)
         (not (empty? value))
         (every? map? value))
    (into (empty value) ; Preserve collection type (vector/list)
          (map #(closed-record % (assoc opts :spec nested-spec-kw)))
          value)

    ;; Other values (strings, numbers, etc.) - return as-is
    :else
    value))

(defn- apply-recursive-wrapping
  "Apply recursive wrapping to nested maps in data.

  For each key in nested-specs, wrap the corresponding value
  as a ClosedRecord (if it's a map or collection of maps)."
  [data nested-specs opts]
  (if (empty? nested-specs)
    data
    (reduce-kv
     (fn [acc key nested-spec-kw]
       (if-let [value (get acc key)]
         (assoc acc key (wrap-nested-value value nested-spec-kw opts))
         acc))
     data
     nested-specs)))

(deftype ClosedRecord [data schema config]
  ;; ILookup: Handles (get cr :key) and (:key cr) syntax
  clojure.lang.ILookup
  (valAt [this k]
    ;; Called when no default is provided
    (if (contains? schema k)
      (get data k nil)
      (let [msg (format "INVALID KEY ACCESS: %s (valid keys: %s)" k (vec (sort schema)))]
        (println "**** " msg " ****")
        (if (:throw-on-invalid-read config)
          (throw (ex-info msg {:key k
                               :schema schema
                               :type :invalid-key-access}))
          INVALID-KEY))))
  (valAt [this k not-found]
    ;; Called when a default value is provided - honor the default for invalid keys
    (if (contains? schema k)
      (get data k not-found)
      ;; When a default is explicitly provided, return it (don't throw)
      ;; This matches standard Clojure map behavior: (get {} :missing "default") => "default"
      not-found))

  ;; IFn: Handles (cr :key) syntax
  clojure.lang.IFn
  (invoke [this k]
    (.valAt this k))
  (invoke [this k not-found]
    (.valAt this k not-found))

  ;; Associative: Handles assoc, dissoc, contains?, etc.
  clojure.lang.Associative
  (assoc [this k v]
    (if (contains? schema k)
      (ClosedRecord. (assoc data k v) schema config)
      (let [msg (format "INVALID KEY ASSOC: %s (valid keys: %s)" k (vec (sort schema)))]
        (println "**** " msg " ****")
        (if (:throw-on-invalid-write config)
          (throw (ex-info msg {:key k
                               :schema schema
                               :type :invalid-key-assoc}))
          INVALID-KEY))))
  (containsKey [this k]
    (contains? schema k))
  (entryAt [this k]
    (when (contains? schema k)
      (find data k)))

  ;; IPersistentMap: Handles dissoc
  clojure.lang.IPersistentMap
  (assocEx [this k v]
    ;; assocEx throws if key already exists
    (if (contains? schema k)
      (if (contains? data k)
        (throw (ex-info "Key already present" {:key k}))
        (.assoc this k v))
      (throw (ex-info "Key not in schema" {:key k :schema schema}))))
  (without [this k]
    (if (contains? schema k)
      (ClosedRecord. (dissoc data k) schema config)
      ;; Dissoc of non-existent key is idempotent in Clojure
      this))

  ;; Seqable: Handles seq, keys, vals
  clojure.lang.Seqable
  (seq [this]
    (seq data))

  ;; Counted: Handles count
  clojure.lang.Counted
  (count [this]
    (count data))

  ;; IPersistentCollection: Handles cons, empty, equiv
  clojure.lang.IPersistentCollection
  (cons [this o]
    (cond
      ;; Handle MapEntry (from (first {:a 1}))
      (map-entry? o)
      (.assoc this (key o) (val o))

      ;; Handle vector pair like [:key val]
      (and (vector? o) (= 2 (count o)))
      (.assoc this (nth o 0) (nth o 1))

      ;; Handle map (used by merge) - filter to only valid keys
      (map? o)
      (reduce (fn [acc [k v]]
                ;; Only assoc if key is in schema (silently skip invalid keys)
                (if (contains? schema k)
                  (.assoc acc k v)
                  acc))
              this
              o)

      ;; Invalid input
      :else
      (throw (ex-info "cons expects MapEntry, [key val] vector, or map"
                      {:input o :type (type o)}))))
  (empty [this]
    (ClosedRecord. {} schema config))
  (equiv [this other]
    (if (instance? ClosedRecord other)
      (and (= data (.-data ^ClosedRecord other))
           (= schema (.-schema ^ClosedRecord other)))
      ;; Compare with regular map
      (= data other)))

  ;; Iterable: Required for Java interop
  java.lang.Iterable
  (iterator [this]
    (.iterator ^Iterable (seq data)))

  ;; Object: toString, equals, hashCode for REPL display and equality
  Object
  (toString [this]
    (str "#ClosedRecord" (pr-str data)))
  (equals [this other]
    ;; Java equals for bidirectional equality with maps
    (if (instance? ClosedRecord other)
      (and (= data (.-data ^ClosedRecord other))
           (= schema (.-schema ^ClosedRecord other)))
      ;; Allow equality with plain maps (compare data only)
      (and (map? other)
           (= data other))))
  (hashCode [this]
    ;; Must match hashCode of underlying map for proper equality
    (.hashCode ^Object data)))

;; Constructor functions

(defn closed-record
  "Create a ClosedRecord that validates key access against schema.

  **ESSENTIAL for swing-fx apps!** Without ClosedRecord, typos in map keys
  silently return nil, causing bugs that propagate through your UI code.
  ClosedRecord catches these errors immediately at the source.

  Options:
  - :schema                         - Set of allowed keys (explicit)
  - :spec                           - clojure.spec keyword to derive schema from
  - :recursive                      - Recursively wrap nested maps (default: false)
  - :throw-on-invalid-read          - Throw exception on invalid key access (default: true)
  - :throw-on-invalid-write         - Throw exception on invalid key assoc (default: true)
  - :relax-constructor-constraints? - Allow extra keys in data beyond spec (default: false)
                                      Use when receiving data with keys you don't control
                                      (e.g., external API responses).

  Schema precedence (highest to lowest):
  1. Explicit :schema in options
  2. :spec in options (extracts keys from spec)
  3. :spec in data's metadata
  4. Derive from data's keys

  DEFAULTS:
  - Invalid reads: Throw exception (strict by default)
  - Invalid writes: Throw exception (strict by default)
  - Constructor constraints: Strict (no extra keys allowed)

  This design catches typos and invalid key access immediately, preventing bugs
  from propagating through your code.

  Examples:
    ;; Strict mode (default) - throws on invalid reads
    (closed-record {:id 1 :name \"Alice\"})

    ;; Explicit schema (allows keys not present in initial data)
    (closed-record {:id 1} {:schema #{:id :name :email}})

    ;; With clojure.spec - extracts keys automatically
    (s/def ::user (s/keys :req-un [::user-id ::username]))
    (closed-record {:user-id \"U123\"} {:spec ::user})

    ;; Spec in metadata (reads automatically)
    (def data ^{:spec ::user} {:user-id \"U123\"})
    (closed-record data)  ; Uses ::user spec from metadata

    ;; Recursive wrapping - protects nested maps too!
    (s/def ::user (s/keys :opt-un [::user-id ::name]))
    (s/def ::message (s/keys :req-un [::text ::user]))
    (def msg (closed-record msg-data {:spec ::message :recursive true}))
    ;; Now nested :user is ALSO a ClosedRecord
    (-> msg :user :name)        ;=> \"alice\" ✅
    (-> msg :user :nam)         ;=> THROWS! ✅ (typo caught in nested field!)

    ;; Accept external data with extra keys (relaxed constructor)
    ;; Use case: API returns extra fields we don't need in our spec
    (closed-record {:id 1 :name \"Alice\" :api-version \"2.0\"}
                   {:spec ::user :relax-constructor-constraints? true})"
  ([data] (closed-record data nil))
  ([data opts]
   (let [spec-kw (or (:spec opts) (-> data meta :spec))

         ;; Schema precedence: explicit > spec option > metadata spec > derive
         schema (or
                 ;; 1. Explicit schema
                 (:schema opts)

                 ;; 2. Spec in options
                 (when spec-kw
                   (extract-keys-from-spec spec-kw))

                 ;; 3. Spec in metadata (already checked in spec-kw)
                 nil

                 ;; 4. Derive from data
                 (set (keys data)))

         ;; Validate: if spec is provided and relax-constructor-constraints? is false,
         ;; ensure data doesn't have extra keys beyond the spec
         _ (when (and spec-kw
                      (not (:relax-constructor-constraints? opts)))
             (let [data-keys (set (keys data))
                   extra-keys (clojure.set/difference data-keys schema)]
               (when (seq extra-keys)
                 (throw (ex-info (format "Data contains keys not in spec: %s (allowed: %s)"
                                         (vec (sort extra-keys))
                                         (vec (sort schema)))
                                 {:extra-keys extra-keys
                                  :schema schema
                                  :spec spec-kw
                                  :type :invalid-constructor-keys})))))

         ;; If :relax-constructor-constraints? is true, filter data to only include valid keys
         ;; This allows external data to have extra keys without failing construction
         filtered-data (if (:relax-constructor-constraints? opts)
                         (select-keys data schema)
                         data)

         ;; Extract nested specs if recursive mode enabled
         nested-specs (when (and (:recursive opts) spec-kw)
                        (extract-nested-specs spec-kw))

         ;; Apply recursive wrapping to nested maps
         wrapped-data (if (and (:recursive opts) nested-specs (not (empty? nested-specs)))
                        (apply-recursive-wrapping filtered-data nested-specs opts)
                        filtered-data)

         config {:throw-on-invalid-read (if (contains? opts :throw-on-invalid-read)
                                          (:throw-on-invalid-read opts)
                                          true) ; Default: true (throw)
                 :throw-on-invalid-write (if (contains? opts :throw-on-invalid-write)
                                           (:throw-on-invalid-write opts)
                                           true)}] ; Default: true (throw)
     (ClosedRecord. wrapped-data schema config))))

;; Helper functions

(defn closed-record?
  "Returns true if x is a ClosedRecord."
  [x]
  (instance? ClosedRecord x))

(defn valid-keys
  "Returns the set of valid keys for this ClosedRecord."
  [^ClosedRecord cr]
  (.-schema cr))

(defn underlying-map
  "Returns the underlying map data from a ClosedRecord.
  Useful for interop with functions that expect plain maps."
  [^ClosedRecord cr]
  (.-data cr))

(defn to-map
  "Converts a ClosedRecord back to a plain Clojure map.
  If given a plain map, returns it unchanged (idempotent).

  This allows using to-map defensively without checking:
  (cr/to-map x)  ; Works whether x is ClosedRecord or plain map"
  [x]
  (if (instance? ClosedRecord x)
    (.-data ^ClosedRecord x)
    x))

(defn with-schema
  "Returns a new ClosedRecord with an updated schema.
  Useful for adding additional valid keys after construction."
  [^ClosedRecord cr new-schema]
  (ClosedRecord. (.-data cr) new-schema (.-config cr)))

(defn add-valid-keys
  "Returns a new ClosedRecord with additional valid keys added to schema."
  [^ClosedRecord cr & ks]
  (with-schema cr (into (.-schema cr) ks)))

(comment
  ;; Basic usage - STRICT MODE (default)
  (def user {:user-id "U123" :name "Alice" :email "alice@example.com"})
  (def cr (closed-record user))

  ;; Valid access works normally
  (:name cr) ; => "Alice"
  (get cr :user-id) ; => "U123"
  (cr :email) ; => "alice@example.com"
  (-> cr :name) ; => "Alice"

  ;; Invalid access THROWS (catches typos!)
  (try
    (:nam cr) ; Typo!
    (catch Exception e
      (ex-message e))) ; => "INVALID KEY ACCESS: :nam (valid keys: [:email :name :user-id])"

  ;; Valid update works
  (def cr2 (assoc cr :name "Bob"))
  (:name cr2) ; => "Bob"

  ;; Invalid update THROWS
  (try
    (assoc cr :invalid-key "value")
    (catch Exception e
      (ex-data e))) ; => {:key :invalid-key, :schema #{...}}

  ;; Works with threading
  (-> cr
      (assoc :name "Charlie")
      :name) ; => "Charlie"

  ;; Explicit schema (allows future keys)
  (def cr3 (closed-record {:id 1}
                          {:schema #{:id :name :email}}))
  (assoc cr3 :name "Alice") ; => works! :name in schema

  ;; Helper functions
  (valid-keys cr) ; => #{:user-id :name :email}
  (to-map cr) ; => {:user-id "U123", :name "Alice", ...}
  (closed-record? cr) ; => true

  ;; Add valid keys after construction
  (def cr4 (add-valid-keys cr :phone :address))
  (assoc cr4 :phone "555-1234")) ; => works!
