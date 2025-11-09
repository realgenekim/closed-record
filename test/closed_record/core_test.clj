(ns closed-record.core-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [closed-record.core :as cr]))

(def sample-user
  {:user-id "U123"
   :name "Alice"
   :email "alice@example.com"})

(deftest closed-record-creation-test
  (testing "Create ClosedRecord with default schema"
    (let [record (cr/closed-record sample-user)]
      (is (cr/closed-record? record))
      (is (= #{:user-id :name :email} (cr/valid-keys record)))))

  (testing "Create ClosedRecord with explicit schema"
    (let [record (cr/closed-record {:id 1}
                                   {:schema #{:id :name :email}})]
      (is (= #{:id :name :email} (cr/valid-keys record)))))

  (testing "Create ClosedRecord - default is strict (throws)"
    (let [record (cr/closed-record sample-user)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"INVALID KEY ACCESS"
                            (:invalid-key record)))))

  (testing "Create ClosedRecord - lenient mode (returns sentinel)"
    (let [record (cr/closed-record sample-user
                                   {:throw-on-invalid-read false})]
      (is (= :INVALID-KEY (:invalid-key record))))))

(deftest valid-key-access-test
  (let [record (cr/closed-record sample-user)]

    (testing "Access with keyword lookup"
      (is (= "Alice" (:name record)))
      (is (= "U123" (:user-id record)))
      (is (= "alice@example.com" (:email record))))

    (testing "Access with get"
      (is (= "Alice" (get record :name)))
      (is (= "U123" (get record :user-id)))
      (is (= "alice@example.com" (get record :email))))

    (testing "Access with function invocation"
      (is (= "Alice" (record :name)))
      (is (= "U123" (record :user-id))))

    (testing "Access with threading"
      (is (= "Alice" (-> record :name)))
      (is (= "U123" (-> record :user-id))))

    (testing "Access with default value"
      (is (= "default" (get record :missing "default"))))))

(deftest invalid-key-access-test
  (testing "Invalid key THROWS in strict mode (default)"
    (let [record (cr/closed-record sample-user)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"INVALID KEY ACCESS"
                            (:invalid-key record)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"INVALID KEY ACCESS"
                            (get record :missing)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"INVALID KEY ACCESS"
                            (record :nonexistent)))))

  (testing "Invalid key returns :INVALID-KEY in lenient mode (opt-in)"
    (let [record (cr/closed-record sample-user
                                   {:throw-on-invalid-read false})]
      (is (= :INVALID-KEY (:invalid-key record)))
      (is (= :INVALID-KEY (get record :missing)))
      (is (= :INVALID-KEY (record :nonexistent))))))

(deftest valid-key-update-test
  (let [record (cr/closed-record sample-user)]

    (testing "Update existing key with assoc"
      (let [updated (assoc record :name "Bob")]
        (is (cr/closed-record? updated))
        (is (= "Bob" (:name updated)))
        (is (= "U123" (:user-id updated)))))

    (testing "Update multiple keys"
      (let [updated (-> record
                        (assoc :name "Bob")
                        (assoc :email "bob@example.com"))]
        (is (= "Bob" (:name updated)))
        (is (= "bob@example.com" (:email updated)))))

    (testing "Update with threading"
      (let [name (-> record
                     (assoc :name "Charlie")
                     :name)]
        (is (= "Charlie" name))))))

(deftest invalid-key-update-test
  (testing "Assoc with invalid key throws exception"
    (let [record (cr/closed-record sample-user)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"INVALID KEY ASSOC"
                            (assoc record :invalid-key "value")))

      (let [ex (try
                 (assoc record :bad-key "value")
                 (catch clojure.lang.ExceptionInfo e e))]
        (is (= :invalid-key-assoc (:type (ex-data ex))))
        (is (= :bad-key (:key (ex-data ex))))
        (is (contains? (ex-data ex) :schema))))))

(deftest map-operations-test
  (let [record (cr/closed-record sample-user)]

    (testing "keys returns valid keys"
      (is (= #{:user-id :name :email}
             (set (keys record)))))

    (testing "vals returns values"
      (is (= #{"U123" "Alice" "alice@example.com"}
             (set (vals record)))))

    (testing "count returns number of entries"
      (is (= 3 (count record))))

    (testing "seq returns sequence of entries"
      (is (= 3 (count (seq record))))
      (is (every? map-entry? (seq record))))

    (testing "contains? works"
      (is (contains? record :name))
      (is (not (contains? record :invalid-key))))

    (testing "find works"
      (is (= [:name "Alice"] (find record :name)))
      (is (nil? (find record :invalid-key))))))

(deftest dissoc-test
  (testing "Dissoc valid key"
    (let [record (cr/closed-record sample-user)
          updated (dissoc record :email)]
      (is (cr/closed-record? updated))
      (is (nil? (:email updated)))
      (is (= "Alice" (:name updated)))))

  (testing "Dissoc invalid key is idempotent"
    (let [record (cr/closed-record sample-user)
          updated (dissoc record :invalid-key)]
      (is (cr/closed-record? updated))
      (is (= record updated)))))

(deftest equality-test
  (testing "ClosedRecords with same data are equal"
    (let [r1 (cr/closed-record sample-user)
          r2 (cr/closed-record sample-user)]
      (is (= r1 r2))))

  (testing "ClosedRecord equals plain map with same data"
    (let [record (cr/closed-record sample-user)]
      ;; ClosedRecord to map comparison works
      (is (= record sample-user))
      ;; Map to ClosedRecord comparison requires conversion
      (is (= sample-user (cr/to-map record)))))

  (testing "ClosedRecords with different data are not equal"
    (let [r1 (cr/closed-record sample-user)
          r2 (assoc r1 :name "Bob")]
      (is (not= r1 r2)))))

(deftest helper-functions-test
  (testing "valid-keys returns schema"
    (let [record (cr/closed-record sample-user)]
      (is (= #{:user-id :name :email} (cr/valid-keys record)))))

  (testing "underlying-map returns plain map"
    (let [record (cr/closed-record sample-user)]
      (is (= sample-user (cr/underlying-map record)))
      (is (map? (cr/underlying-map record)))
      (is (not (cr/closed-record? (cr/underlying-map record))))))

  (testing "to-map is alias for underlying-map"
    (let [record (cr/closed-record sample-user)]
      (is (= (cr/to-map record) (cr/underlying-map record)))))

  (testing "with-schema updates schema"
    (let [record (cr/closed-record {:id 1})
          updated (cr/with-schema record #{:id :name :email})]
      (is (= #{:id :name :email} (cr/valid-keys updated)))
      ;; Can now assoc keys in new schema
      (is (= "Alice" (:name (assoc updated :name "Alice"))))))

  (testing "add-valid-keys adds keys to schema"
    (let [record (cr/closed-record {:id 1})
          updated (cr/add-valid-keys record :name :email)]
      (is (= #{:id :name :email} (cr/valid-keys updated)))
      ;; Can now assoc new keys
      (is (= "Alice" (:name (assoc updated :name "Alice")))))))

(deftest edge-cases-test
  (testing "Empty map"
    (let [record (cr/closed-record {} {:throw-on-invalid-read false})]
      (is (= 0 (count record)))
      (is (= #{} (cr/valid-keys record)))
      (is (= :INVALID-KEY (:any-key record)))))

  (testing "Map with nil values"
    (let [record (cr/closed-record {:id 1 :name nil})]
      (is (nil? (:name record)))
      (is (= 1 (:id record)))))

  (testing "Map with keyword and string keys"
    ;; ClosedRecord schema is derived from keys
    (let [record (cr/closed-record {:id 1 "name" "Alice"})]
      (is (= 1 (:id record)))
      (is (= "Alice" (get record "name")))))

  (testing "Explicit schema allows keys not in initial data"
    (let [record (cr/closed-record {:id 1}
                                   {:schema #{:id :name :email}})]
      ;; Can assoc keys in schema but not in initial data
      (let [updated (assoc record :name "Alice")]
        (is (= "Alice" (:name updated))))))

  (testing "Schema prevents adding keys not in schema"
    (let [record (cr/closed-record {:id 1})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"INVALID KEY ASSOC"
                            (assoc record :new-key "value"))))))

(deftest common-llm-typos-test
  (testing "Common LLM typos are caught - strict mode (default)"
    (let [msg {:message-key "msg_123"
               :ts "123.456"
               :user-id "U123"
               :channel-id "C456"
               :text "Hello"}
          record (cr/closed-record msg)]
      ;; All typos throw
      (is (thrown? Exception (:mesage-key record))) ;; missing 's'
      (is (thrown? Exception (:message_key record))) ;; underscore
      (is (thrown? Exception (:messageKey record))) ;; camelCase
      (is (thrown? Exception (:user_id record))) ;; underscore
      (is (thrown? Exception (:userId record))) ;; camelCase
      (is (thrown? Exception (:channelId record))))) ;; camelCase

  (testing "Common typos return :INVALID-KEY in lenient mode (opt-in)"
    (let [msg {:message-key "msg_123"
               :ts "123.456"
               :user-id "U123"
               :channel-id "C456"
               :text "Hello"}
          record (cr/closed-record msg {:throw-on-invalid-read false})]
      ;; All typos return :INVALID-KEY
      (is (= :INVALID-KEY (:mesage-key record)))
      (is (= :INVALID-KEY (:message_key record)))
      (is (= :INVALID-KEY (:messageKey record)))
      (is (= :INVALID-KEY (:user_id record)))
      (is (= :INVALID-KEY (:userId record)))
      (is (= :INVALID-KEY (:channelId record))))))

(deftest integration-example-test
  (testing "Real-world usage example - strict mode (default)"
    ;; Simulate fetching a message from DB
    (let [raw-message {:message-key "msg_123"
                       :ts "1234567890.123456"
                       :user-id "U123"
                       :channel-id "C456"
                       :text "Hello world"}
          ;; Wrap in ClosedRecord (strict mode - default)
          msg (cr/closed-record raw-message)]

      ;; Valid operations work
      (is (= "msg_123" (:message-key msg)))
      (is (= "Hello world" (:text msg)))

      ;; Transformations work
      (let [updated (-> msg
                        (assoc :text "Updated text")
                        (assoc :ts "9999999999.999999"))]
        (is (= "Updated text" (:text updated)))
        (is (= "9999999999.999999" (:ts updated))))

      ;; Invalid key access throws
      (is (thrown? Exception (:message_id msg))) ;; typo
      (is (thrown? Exception (:timestamp msg))))) ;; wrong key name

  (testing "Real-world usage example - lenient mode (opt-in)"
    (let [raw-message {:message-key "msg_123"
                       :ts "1234567890.123456"
                       :user-id "U123"
                       :channel-id "C456"
                       :text "Hello world"}
          msg (cr/closed-record raw-message {:throw-on-invalid-read false})]

      ;; Valid operations work
      (is (= "msg_123" (:message-key msg)))
      (is (= "Hello world" (:text msg)))

      ;; Invalid key access returns sentinel
      (is (= :INVALID-KEY (:message_id msg)))
      (is (= :INVALID-KEY (:timestamp msg))))))

(deftest threading-macro-test
  (testing "Works with -> threading"
    (let [record (cr/closed-record {:user-id "U123"})]
      (is (= "U123"
             (-> record
                 :user-id)))))

  (testing "Works with some->"
    (let [record (cr/closed-record {:user-id "U123"} {:throw-on-invalid-read false})]
      ;; some-> stops at first nil/:INVALID-KEY
      (is (= :INVALID-KEY
             (some-> record
                     :invalid-key)))))

  (testing "Works with ->> threading"
    (let [record (cr/closed-record {:user-id "U123"})]
      (is (= "U123"
             (->> record
                  :user-id))))))

(deftest collection-operations-test
  (testing "Works with map function"
    (let [records [(cr/closed-record {:id 1})
                   (cr/closed-record {:id 2})
                   (cr/closed-record {:id 3})]]
      (is (= [1 2 3] (map :id records)))))

  (testing "Works with filter"
    (let [records [(cr/closed-record {:id 1 :active true})
                   (cr/closed-record {:id 2 :active false})
                   (cr/closed-record {:id 3 :active true})]]
      (is (= 2 (count (filter :active records))))))

  (testing "Works with reduce"
    (let [records [(cr/closed-record {:value 1})
                   (cr/closed-record {:value 2})
                   (cr/closed-record {:value 3})]]
      (is (= 6 (reduce (fn [sum rec] (+ sum (:value rec))) 0 records)))))

  (testing "Works with group-by"
    (let [records [(cr/closed-record {:type "A" :id 1})
                   (cr/closed-record {:type "B" :id 2})
                   (cr/closed-record {:type "A" :id 3})]]
      (is (= {"A" 2 "B" 1}
             (-> (group-by :type records)
                 (update-vals count)))))))

(deftest merge-and-select-keys-test
  (testing "merge with ClosedRecord"
    (let [r1 (cr/closed-record {:id 1 :name "Alice"})
          r2 {:name "Bob" :email "bob@example.com"}]
      ;; Merge returns ClosedRecord
      (let [merged (merge r1 r2)]
        (is (cr/closed-record? merged))
        (is (= "Bob" (:name merged)))
        (is (= 1 (:id merged))))))

  (testing "select-keys with ClosedRecord"
    (let [record (cr/closed-record {:id 1 :name "Alice" :email "alice@example.com"})]
      (is (= {:id 1 :name "Alice"}
             (select-keys record [:id :name])))))

  (testing "update with ClosedRecord"
    (let [record (cr/closed-record {:count 10})]
      (let [updated (update record :count inc)]
        (is (cr/closed-record? updated))
        (is (= 11 (:count updated)))))))

(deftest conj-test
  (testing "conj with map entry"
    (let [record (cr/closed-record {:id 1})
          updated (conj record [:id 2])]
      (is (cr/closed-record? updated))
      (is (= 2 (:id updated)))))

  (testing "conj with map"
    (let [record (cr/closed-record {:id 1 :name "Alice"})
          updated (conj record {:name "Bob"})]
      (is (cr/closed-record? updated))
      (is (= "Bob" (:name updated))))))

(deftest print-representation-test
  (testing "toString shows ClosedRecord prefix"
    (let [record (cr/closed-record {:id 1 :name "Alice"})]
      (is (re-find #"ClosedRecord" (str record)))))

  (testing "pr-str works"
    (let [record (cr/closed-record {:id 1})]
      ;; Should contain the data
      (is (re-find #"id" (pr-str record))))))

(deftest nested-closed-records-test
  (testing "ClosedRecord with nested ClosedRecord - lenient mode"
    (let [inner (cr/closed-record {:id "U123" :name "Alice"} {:throw-on-invalid-read false})
          outer (cr/closed-record {:user inner :message "Hello"} {:throw-on-invalid-read false})]

      ;; Can access nested ClosedRecord
      (is (cr/closed-record? (:user outer)))
      (is (= "Alice" (-> outer :user :name)))

      ;; Invalid key on inner returns sentinel
      (is (= :INVALID-KEY (-> outer :user :invalid-key)))))

  (testing "ClosedRecord with nested plain map"
    (let [record (cr/closed-record {:user {:id "U123" :name "Alice"}
                                    :message "Hello"})]
      ;; Nested map is plain (not wrapped)
      (is (map? (:user record)))
      (is (not (cr/closed-record? (:user record))))
      ;; Can still access nested fields
      (is (= "Alice" (-> record :user :name))))))

(deftest get-in-and-update-in-test
  (testing "get-in with ClosedRecord"
    (let [record (cr/closed-record {:user {:name "Alice" :age 30}
                                    :message "Hello"})]
      (is (= "Alice" (get-in record [:user :name])))
      (is (= 30 (get-in record [:user :age])))))

  (testing "update-in with ClosedRecord"
    (let [record (cr/closed-record {:user {:name "Alice" :age 30}
                                    :message "Hello"})
          updated (update-in record [:user :age] inc)]
      (is (cr/closed-record? updated))
      (is (= 31 (get-in updated [:user :age]))))))

(deftest assoc-in-test
  (testing "assoc-in with ClosedRecord"
    (let [record (cr/closed-record {:user {:name "Alice"}
                                    :message "Hello"})
          updated (assoc-in record [:user :name] "Bob")]
      (is (cr/closed-record? updated))
      (is (= "Bob" (get-in updated [:user :name]))))))

(deftest destructuring-test
  (testing "Destructuring in let"
    (let [record (cr/closed-record {:id 1 :name "Alice" :email "alice@example.com"})
          {:keys [id name email]} record]
      (is (= 1 id))
      (is (= "Alice" name))
      (is (= "alice@example.com" email))))

  (testing "Destructuring with :as"
    (let [record (cr/closed-record {:id 1 :name "Alice"})
          {:keys [id name] :as whole} record]
      (is (= 1 id))
      (is (= "Alice" name))
      (is (cr/closed-record? whole))))

  (testing "Destructuring in function args"
    (let [process-record (fn [{:keys [id name]}]
                           (str id "-" name))
          record (cr/closed-record {:id 1 :name "Alice"})]
      (is (= "1-Alice" (process-record record))))))

(deftest performance-test
  (testing "ClosedRecord handles large maps"
    (let [large-map (into {} (map (fn [i] [(keyword (str "key-" i)) i]) (range 1000)))
          record (cr/closed-record large-map {:throw-on-invalid-read false})]
      (is (= 1000 (count record)))
      (is (= 500 (:key-500 record)))
      (is (= :INVALID-KEY (:key-1001 record)))))

  (testing "Repeated access is efficient"
    (let [record (cr/closed-record {:id 1 :name "Alice"})]
      ;; Should not degrade with repeated access
      (dotimes [_ 1000]
        (is (= "Alice" (:name record)))))))

(deftest sentinel-value-test
  (testing "Can detect sentinel value in lenient mode"
    (let [record (cr/closed-record {:id 1} {:throw-on-invalid-read false})]
      (is (= cr/INVALID-KEY (:invalid-key record)))
      (is (= :INVALID-KEY (:invalid-key record)))))

  (testing "Sentinel is not nil in lenient mode"
    (let [record (cr/closed-record {:id 1 :value nil} {:throw-on-invalid-read false})]
      (is (nil? (:value record))) ;; valid key with nil value
      (is (not (nil? (:invalid-key record)))) ;; invalid key returns sentinel
      (is (= :INVALID-KEY (:invalid-key record))))))

(deftest error-message-quality-test
  (testing "Error messages include valid keys"
    (let [record (cr/closed-record {:id 1 :name "Alice" :email "alice@example.com"})]
      ;; Capture error via try-catch
      (try
        (assoc record :invalid-key "value")
        (catch Exception e
          (let [msg (.getMessage e)]
            (is (re-find #"INVALID KEY ASSOC" msg))
            (is (re-find #"invalid-key" msg)))))))

  (testing "Exception data includes schema"
    (let [record (cr/closed-record {:id 1 :name "Alice"})]
      (try
        (assoc record :bad-key "x")
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :bad-key (:key data)))
            (is (= :invalid-key-assoc (:type data)))
            (is (set? (:schema data)))
            (is (contains? (:schema data) :id))
            (is (contains? (:schema data) :name))))))))

;; ============================================================================
;; Schema Export Tests (for clj-kondo integration)
;; ============================================================================

(deftest schema-registry-test
  (testing "Register schema adds to registry"
    (cr/clear-schema-registry!)
    (let [record (cr/closed-record {:id 1 :name "Alice"})]
      (cr/register-schema! :test/user record)
      (is (contains? (cr/list-registered-schemas) :test/user))
      (is (= #{:id :name} (get (cr/list-registered-schemas) :test/user)))))

  (testing "Multiple schemas can be registered"
    (cr/clear-schema-registry!)
    (let [user (cr/closed-record {:id 1 :name "Alice"})
          msg (cr/closed-record {:text "Hello" :ts "123"})]
      (cr/register-schema! :test/user user)
      (cr/register-schema! :test/message msg)
      (is (= 2 (count (cr/list-registered-schemas))))
      (is (= #{:id :name} (get (cr/list-registered-schemas) :test/user)))
      (is (= #{:text :ts} (get (cr/list-registered-schemas) :test/message)))))

  (testing "Clear registry empties all schemas"
    (cr/clear-schema-registry!)
    (cr/register-schema! :test/user (cr/closed-record {:id 1}))
    (is (= 1 (count (cr/list-registered-schemas))))
    (cr/clear-schema-registry!)
    (is (= 0 (count (cr/list-registered-schemas))))))

(deftest export-schemas-test
  (testing "Export creates file with schemas"
    (cr/clear-schema-registry!)
    (let [user (cr/closed-record {:id 1 :name "Alice"})
          msg (cr/closed-record {:text "Hello" :ts "123"})]
      (cr/register-schema! :test/user user)
      (cr/register-schema! :test/message msg)
      (cr/export-schemas-for-clj-kondo!)

      ;; File should exist
      (is (.exists (io/file ".clj-kondo/closed-record-schemas.edn")))

      ;; File should contain schemas
      (let [exported (edn/read-string (slurp ".clj-kondo/closed-record-schemas.edn"))]
        (is (map? exported))
        (is (contains? exported :test/user))
        (is (contains? exported :test/message))
        (is (= #{:id :name} (:test/user exported)))
        (is (= #{:text :ts} (:test/message exported))))))

  (testing "Export returns schema map"
    (cr/clear-schema-registry!)
    (cr/register-schema! :test/user (cr/closed-record {:id 1}))
    (let [result (cr/export-schemas-for-clj-kondo!)]
      (is (map? result))
      (is (contains? result :test/user)))))

(deftest schema-diff-test
  (testing "Schema diff detects new runtime types"
    (cr/clear-schema-registry!)
    ;; Export empty schemas
    (cr/export-schemas-for-clj-kondo!)

    ;; Register new schema
    (cr/register-schema! :test/new-type (cr/closed-record {:id 1}))

    (let [diff (cr/schema-diff)]
      (is (contains? (:only-runtime diff) :test/new-type))
      (is (empty? (:only-lint diff)))
      (is (empty? (:schema-diff diff)))))

  (testing "Schema diff detects schema changes"
    (cr/clear-schema-registry!)
    ;; Register and export schema with :id
    (cr/register-schema! :test/user (cr/closed-record {:id 1}))
    (cr/export-schemas-for-clj-kondo!)

    ;; Change schema to add :name
    (cr/clear-schema-registry!)
    (cr/register-schema! :test/user (cr/closed-record {:id 1 :name "Alice"}))

    (let [diff (cr/schema-diff)]
      (is (empty? (:only-runtime diff)))
      (is (empty? (:only-lint diff)))
      (is (contains? (:schema-diff diff) :test/user))
      (is (contains? (get-in diff [:schema-diff :test/user :runtime]) :name)))))

(deftest print-schema-diff-test
  (testing "Print schema diff with no differences"
    (cr/clear-schema-registry!)
    (cr/register-schema! :test/user (cr/closed-record {:id 1}))
    (cr/export-schemas-for-clj-kondo!)

    ;; Should print "in sync"
    (with-out-str (cr/print-schema-diff)))

  (testing "Print schema diff with differences"
    (cr/clear-schema-registry!)
    (cr/register-schema! :test/user (cr/closed-record {:id 1}))
    (cr/export-schemas-for-clj-kondo!)

    (cr/clear-schema-registry!)
    (cr/register-schema! :test/user (cr/closed-record {:id 1 :name "Alice"}))

    ;; Should print differences
    (let [output (with-out-str (cr/print-schema-diff))]
      (is (re-find #"Schema differences" output)))))

(deftest integration-with-closed-record-test
  (testing "Complete workflow: register -> export -> validate"
    (cr/clear-schema-registry!)

    ;; Step 1: Create ClosedRecords with schemas
    (let [users [(cr/closed-record {:id 1 :name "Alice"})
                 (cr/closed-record {:id 2 :name "Bob"})]
          messages [(cr/closed-record {:text "Hello" :ts "123"})
                    (cr/closed-record {:text "World" :ts "456"})]]

      ;; Step 2: Register schemas
      (cr/register-schema! :test/user (first users))
      (cr/register-schema! :test/message (first messages))

      ;; Step 3: Export for clj-kondo
      (cr/export-schemas-for-clj-kondo!)

      ;; Step 4: Verify schemas were exported
      (is (.exists (io/file ".clj-kondo/closed-record-schemas.edn")))

      ;; Step 5: Verify schemas are correct
      (let [exported (edn/read-string (slurp ".clj-kondo/closed-record-schemas.edn"))]
        (is (= #{:id :name} (:test/user exported)))
        (is (= #{:text :ts} (:test/message exported)))))))

;; ============================================================================
;; clojure.spec Support Tests
;; ============================================================================

;; Define test specs
(s/def ::user-id string?)
(s/def ::username string?)
(s/def ::email (s/nilable string?))
(s/def ::age int?)

(s/def ::test-user
  (s/keys :req-un [::user-id ::username]
          :opt-un [::email ::age]))

(s/def ::message-key string?)
(s/def ::text string?)
(s/def ::ts string?)

(s/def ::test-message
  (s/keys :req-un [::message-key ::text ::ts]))

;; Namespaced keys spec
(s/def :person/id string?)
(s/def :person/name string?)
(s/def :person/address (s/nilable string?))

(s/def ::test-person
  (s/keys :req [:person/id :person/name]
          :opt [:person/address]))

(deftest spec-basic-support-test
  (testing "Create ClosedRecord with spec - unqualified required keys"
    (let [data {:user-id "U123" :username "alice"}
          record (cr/closed-record data {:spec ::test-user})]
      (is (cr/closed-record? record))
      (is (= #{:user-id :username :email :age} (cr/valid-keys record)))
      (is (= "U123" (:user-id record)))
      (is (= "alice" (:username record)))))

  (testing "Create ClosedRecord with spec - includes optional keys"
    (let [data {:user-id "U123" :username "alice" :email "alice@example.com"}
          record (cr/closed-record data {:spec ::test-user})]
      (is (= #{:user-id :username :email :age} (cr/valid-keys record)))
      (is (= "alice@example.com" (:email record)))))

  (testing "Spec catches LLM typos"
    (let [data {:user-id "U123" :username "alice"}
          ;; Enable strict mode to throw on invalid access
          record (cr/closed-record data {:spec ::test-user
                                         :throw-on-invalid-read true})]
      ;; Valid access
      (is (= "U123" (:user-id record)))

      ;; LLM typos - should throw
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"INVALID KEY ACCESS: :user_id"
                            (:user_id record)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"INVALID KEY ACCESS: :userId"
                            (:userId record)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"INVALID KEY ACCESS: :user-name"
                            (:user-name record))))))

(deftest spec-namespaced-keys-test
  (testing "Create ClosedRecord with namespaced keys"
    (let [data {:person/id "P123" :person/name "Bob"}
          record (cr/closed-record data {:spec ::test-person})]
      (is (= #{:person/id :person/name :person/address} (cr/valid-keys record)))
      (is (= "P123" (:person/id record)))
      (is (= "Bob" (:person/name record)))))

  (testing "Namespaced keys catch typos"
    (let [data {:person/id "P123" :person/name "Bob"}
          record (cr/closed-record data {:spec ::test-person
                                         :throw-on-invalid-read true})]
      ;; Valid
      (is (= "P123" (:person/id record)))

      ;; Invalid - wrong namespace
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"INVALID KEY ACCESS"
                            (:id record)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"INVALID KEY ACCESS"
                            (:user/id record))))))

(deftest spec-from-metadata-test
  (testing "Read spec from data's metadata"
    (let [data (with-meta {:user-id "U123" :username "alice"}
                 {:spec ::test-user})
          record (cr/closed-record data)]
      (is (= #{:user-id :username :email :age} (cr/valid-keys record)))
      (is (= "U123" (:user-id record)))))

  (testing "Spec from metadata catches typos"
    (let [data (with-meta {:user-id "U123" :username "alice"}
                 {:spec ::test-user})
          record (cr/closed-record data {:throw-on-invalid-read true})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"INVALID KEY ACCESS: :user_id"
                            (:user_id record))))))

(deftest spec-precedence-test
  (testing "Explicit :schema wins over :spec"
    (let [record (cr/closed-record {:user-id "U123"}
                                   {:schema #{:user-id}
                                    :spec ::test-user
                                    :throw-on-invalid-read true})]
      ;; Explicit schema wins - only :user-id allowed
      (is (= #{:user-id} (cr/valid-keys record)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"INVALID KEY ACCESS: :username"
                            (:username record)))))

  (testing ":spec in options wins over metadata"
    (let [data (with-meta {:text "Hello"}
                 {:spec ::test-user})
          record (cr/closed-record data {:spec ::test-message
                                         :throw-on-invalid-read true})]
      ;; Options spec wins
      (is (= #{:message-key :text :ts} (cr/valid-keys record)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"INVALID KEY ACCESS: :user-id"
                            (:user-id record))))))

(deftest spec-real-world-example-test
  (testing "Message schema from spec"
    (let [msg {:message-key "msg_123"
               :text "Hello world"
               :ts "1234567890.123"}
          ;; Enable strict mode to throw on invalid access
          record (cr/closed-record msg {:spec ::test-message
                                        :throw-on-invalid-read true})]
      ;; Valid access
      (is (= "msg_123" (:message-key record)))
      (is (= "Hello world" (:text record)))
      (is (= "1234567890.123" (:ts record)))

      ;; LLM typos caught
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"INVALID KEY ACCESS: :mesage-key"
                            (:mesage-key record)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"INVALID KEY ACCESS: :message_key"
                            (:message_key record)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"INVALID KEY ACCESS: :messageKey"
                            (:messageKey record))))))

(deftest spec-integration-with-validation-test
  (testing "Use spec for validation AND ClosedRecord for access protection"
    ;; Simulate: spec validates, ClosedRecord protects
    (let [raw-data {:user-id "U123" :username "alice" :age 30}]
      ;; Step 1: Validate with spec
      (is (s/valid? ::test-user raw-data))

      ;; Step 2: Wrap in ClosedRecord with strict mode
      (let [record (cr/closed-record raw-data {:spec ::test-user
                                               :throw-on-invalid-read true})]
        ;; Valid access works
        (is (= "U123" (:user-id record)))
        (is (= 30 (:age record)))

        ;; LLM typos caught by ClosedRecord
        (is (thrown? clojure.lang.ExceptionInfo
                     (:user_id record)))))))

(deftest spec-assoc-operations-test
  (testing "Assoc with spec-derived schema"
    (let [record (cr/closed-record {:user-id "U123"}
                                   {:spec ::test-user})]
      ;; Can assoc valid keys from spec
      (let [updated (assoc record :username "alice")]
        (is (= "alice" (:username updated))))

      (let [updated2 (assoc record :email "alice@example.com")]
        (is (= "alice@example.com" (:email updated2))))

      ;; Cannot assoc invalid keys
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"INVALID KEY ASSOC: :invalid-key"
                            (assoc record :invalid-key "value"))))))

;; ============================================================================
;; Nested Spec Support Tests (Real-world scenario from schema.clj)
;; ============================================================================

;; Real specs from schema.clj
(s/def ::user-slack-id string?)
(s/def ::name string?)
(s/def ::real-name string?)
(s/def ::image-48 string?)

(s/def ::user-entity-nested
  (s/keys :opt-un [::user-slack-id ::name ::real-name ::image-48]))

(s/def ::message-key string?)
(s/def ::text string?)
(s/def ::ts string?)
(s/def ::user-obj ::user-entity-nested)

(s/def ::message-with-user-obj
  (s/keys :req-un [::message-key ::text ::ts]
          :opt-un [::user-obj]))

(deftest nested-spec-problem-test
  (testing "PROBLEM: Nested user-obj is NOT wrapped (current behavior)"
    (let [msg-data {:message-key "C123--1234567890.123"
                    :text "Hello world"
                    :ts "1234567890.123"
                    :user-obj {:user-slack-id "U123"
                               :name "alice"
                               :real-name "Alice Smith"
                               :image-48 "https://example.com/alice.png"}}
          msg-record (cr/closed-record msg-data {:spec ::message-with-user-obj})]

      ;; Top-level access works
      (is (= "Hello world" (:text msg-record)))
      (is (= "C123--1234567890.123" (:message-key msg-record)))

      ;; Can access nested user-obj
      (is (map? (:user-obj msg-record)))

      ;; But nested user-obj is NOT a ClosedRecord - it's a plain map!
      (is (not (cr/closed-record? (:user-obj msg-record))))

      ;; LLM typos in nested fields are NOT caught!
      (let [user-obj (:user-obj msg-record)]
        ;; Valid access works
        (is (= "alice" (:name user-obj)))

        ;; LLM typo - returns nil (NOT caught!) ❌
        (is (nil? (:nam user-obj))) ; Should throw but doesn't!
        (is (nil? (:user_slack_id user-obj))) ; Should throw but doesn't!
        (is (nil? (:realName user-obj))))))) ; Should throw but doesn't!

(deftest nested-spec-solution-test
  (testing "SOLUTION: Recursive wrapping catches nested typos"
    ;; This test shows recursive wrapping working correctly

    (let [msg-data {:message-key "C123--1234567890.123"
                    :text "Hello world"
                    :ts "1234567890.123"
                    :user-obj {:user-slack-id "U123"
                               :name "alice"
                               :real-name "Alice Smith"
                               :image-48 "https://example.com/alice.png"}}

          msg-record (cr/closed-record msg-data {:spec ::message-with-user-obj
                                                 :recursive true})]

      ;; Top-level access works
      (is (= "Hello world" (:text msg-record)))

      ;; Nested user-obj is ALSO a ClosedRecord
      (is (cr/closed-record? (:user-obj msg-record)))

      ;; Valid nested access works
      (let [user-obj (:user-obj msg-record)]
        (is (= "alice" (:name user-obj)))
        (is (= "U123" (:user-slack-id user-obj)))

        ;; LLM typos in nested fields ARE caught! ✅
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"INVALID KEY ACCESS: :nam"
                              (:nam user-obj)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"INVALID KEY ACCESS: :user_slack_id"
                              (:user_slack_id user-obj)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"INVALID KEY ACCESS: :realName"
                              (:realName user-obj)))))))

(deftest nested-reactions-test
  (testing "Nested collections in specs"
    ;; Define reaction spec
    (s/def ::emoji-name string?)
    (s/def ::reaction-users (s/coll-of string? :kind vector?))
    (s/def ::reaction-count int?)
    (s/def ::reaction (s/keys :req-un [::emoji-name ::reaction-users ::reaction-count]))
    (s/def ::reactions (s/coll-of ::reaction :kind vector?))

    (s/def ::message-with-reactions
      (s/keys :req-un [::message-key ::text ::ts]
              :opt-un [::reactions]))

    (let [msg-data {:message-key "C123--123"
                    :text "Great idea!"
                    :ts "123"
                    :reactions [{:emoji-name "wave"
                                 :reaction-users ["U1" "U2"]
                                 :reaction-count 2}
                                {:emoji-name "thumbsup"
                                 :reaction-users ["U3"]
                                 :reaction-count 1}]}

          msg-record (cr/closed-record msg-data {:spec ::message-with-reactions
                                                 :recursive true})]

      ;; Access reactions collection
      (is (vector? (:reactions msg-record)))
      (is (= 2 (count (:reactions msg-record))))

      ;; Each reaction should be a ClosedRecord
      (let [first-reaction (first (:reactions msg-record))]
        (is (cr/closed-record? first-reaction))

        ;; Valid access
        (is (= "wave" (:emoji-name first-reaction)))

        ;; LLM typos caught
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"INVALID KEY ACCESS: :emojiName"
                              (:emojiName first-reaction)))))))

(deftest real-world-llm-bugs-caught-test
  (testing "Bug 1: Reaction keys mismatch (emoji-name vs name, reaction-count vs count)"
    ;; Real bug: LLM destructured using {:keys [emoji-name reaction-count]}
    ;; but actual data had {:name "wave" :count 2}
    ;; Result: All reactions silently disappeared from UI

    (s/def ::emoji-name string?)
    (s/def ::reaction-users (s/coll-of string? :kind vector?))
    (s/def ::reaction-count int?)
    (s/def ::reaction-correct (s/keys :req-un [::emoji-name ::reaction-users ::reaction-count]))
    (s/def ::reactions-correct (s/coll-of ::reaction-correct :kind vector?))
    (s/def ::msg-with-reactions (s/keys :req-un [::message-key ::text ::reactions-correct]))

    (let [msg-data {:message-key "C123--456"
                    :text "Great idea!"
                    :reactions-correct [{:emoji-name "wave"
                                         :reaction-users ["U1" "U2"]
                                         :reaction-count 2}]}
          msg (cr/closed-record msg-data {:spec ::msg-with-reactions :recursive true})
          reaction (first (:reactions-correct msg))]

      ;; ✅ Correct key works
      (is (= "wave" (:emoji-name reaction)))
      (is (= 2 (:reaction-count reaction)))

      ;; ❌ LLM typo "name" instead of "emoji-name" THROWS immediately
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"INVALID KEY ACCESS: :name"
                            (:name reaction)))

      ;; ❌ LLM typo "count" instead of "reaction-count" THROWS immediately
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"INVALID KEY ACCESS: :count"
                            (:count reaction)))))

  (testing "Bug 2: Reply users key format (reply-users vs reply_users)"
    ;; Real bug: Code looked for :reply-users but data had :reply_users
    ;; Result: "1 reply from 0 users" (impossible state)

    (s/def ::reply-count int?)
    (s/def ::reply-users (s/coll-of string? :kind vector?))
    (s/def ::thread-msg (s/keys :req-un [::message-key ::text]
                                :opt-un [::reply-count ::reply-users]))

    (let [msg-data {:message-key "C123--789"
                    :text "Thread parent"
                    :reply-count 1
                    :reply-users ["U123"]}
          msg (cr/closed-record msg-data {:spec ::thread-msg})]

      ;; ✅ Correct hyphenated key works
      (is (= ["U123"] (:reply-users msg)))

      ;; ❌ LLM typo with underscore THROWS immediately
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"INVALID KEY ACCESS: :reply_users"
                            (:reply_users msg)))))

  (testing "Bug 3: Date vs day field confusion (date vs day)"
    ;; Real bug: (map :date messages) produced (nil nil nil...)
    ;; Result: (frequencies (map :date msgs)) => {nil 703}
    ;; User profile showed zero message counts

    (s/def ::day string?) ; ISO date format "2024-11-04"
    (s/def ::msg-with-day (s/keys :req-un [::message-key ::text ::day]))

    (let [msg-data {:message-key "C123--999"
                    :text "Hello"
                    :day "2024-11-04"}
          msg (cr/closed-record msg-data {:spec ::msg-with-day})]

      ;; ✅ Correct "day" key works
      (is (= "2024-11-04" (:day msg)))

      ;; ❌ LLM typo "date" instead of "day" THROWS immediately
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"INVALID KEY ACCESS: :date"
                            (:date msg)))

      ;; This prevents silent bugs like:
      ;; (frequencies (map :date messages)) => {nil 703} ❌
      ;; NOW becomes immediate exception at map time! ✅
      (is (thrown? clojure.lang.ExceptionInfo
                   (doall (map :date [msg])))))))
