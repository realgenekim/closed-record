(ns closed-record.recursive-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [closed-record.core :as cr]))

;; ============================================================================
;; Spec-Free Recursive Wrapping Tests
;; ============================================================================

(deftest spec-free-recursive-basic-test
  (testing "Basic spec-free recursive wrapping"
    (let [cr (cr/closed-record {:a {:b 1}} {:recursive true})]
      ;; Outer is ClosedRecord
      (is (cr/closed-record? cr))
      ;; Nested map is also ClosedRecord
      (is (cr/closed-record? (:a cr)))
      ;; Valid access works
      (is (= 1 (:b (:a cr))))
      ;; Typo throws on nested
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"INVALID KEY ACCESS: :c"
                            (:c (:a cr))))))

  (testing "Typo on outer level also throws"
    (let [cr (cr/closed-record {:a {:b 1}} {:recursive true})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"INVALID KEY ACCESS: :x"
                            (:x cr))))))

(deftest spec-free-recursive-deep-nesting-test
  (testing "Three levels of nesting — all wrapped"
    (let [cr (cr/closed-record {:x {:y {:z 42}}} {:recursive true})]
      (is (cr/closed-record? cr))
      (is (cr/closed-record? (:x cr)))
      (is (cr/closed-record? (:y (:x cr))))
      (is (= 42 (:z (:y (:x cr)))))
      ;; Typo at deepest level
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"INVALID KEY ACCESS: :zz"
                            (:zz (:y (:x cr)))))))

  (testing "Threading through deep nesting"
    (let [cr (cr/closed-record {:x {:y {:z 42}}} {:recursive true})]
      (is (= 42 (-> cr :x :y :z)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (-> cr :x :y :typo))))))

(deftest spec-free-recursive-mixed-values-test
  (testing "Only maps get wrapped — strings, numbers, nil, keywords pass through"
    (let [cr (cr/closed-record {:name "Alice"
                                :age 30
                                :active true
                                :role nil
                                :tag :admin
                                :nested {:id 1}}
                               {:recursive true})]
      (is (= "Alice" (:name cr)))
      (is (= 30 (:age cr)))
      (is (= true (:active cr)))
      (is (nil? (:role cr)))
      (is (= :admin (:tag cr)))
      (is (cr/closed-record? (:nested cr)))
      (is (= 1 (:id (:nested cr)))))))

(deftest spec-free-recursive-vectors-of-maps-test
  (testing "Vectors of maps — each element wrapped"
    (let [cr (cr/closed-record {:items [{:id 1 :name "A"}
                                        {:id 2 :name "B"}]}
                               {:recursive true})]
      (is (= 2 (count (:items cr))))
      (is (cr/closed-record? (first (:items cr))))
      (is (cr/closed-record? (second (:items cr))))
      (is (= 1 (:id (first (:items cr)))))
      (is (= "B" (:name (second (:items cr)))))
      ;; Typo on element throws
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"INVALID KEY ACCESS: :nme"
                            (:nme (first (:items cr)))))))

  (testing "Vectors of non-maps pass through unchanged"
    (let [cr (cr/closed-record {:tags ["a" "b" "c"]
                                :nums [1 2 3]}
                               {:recursive true})]
      (is (= ["a" "b" "c"] (:tags cr)))
      (is (= [1 2 3] (:nums cr)))))

  (testing "Empty vectors pass through"
    (let [cr (cr/closed-record {:items []} {:recursive true})]
      (is (= [] (:items cr))))))

(deftest spec-free-recursive-assoc-in-test
  (testing "assoc-in preserves outer ClosedRecord"
    (let [cr (cr/closed-record {:a {:b 1}} {:recursive true})
          updated (assoc-in cr [:a :b] 2)]
      (is (cr/closed-record? updated))
      (is (= 2 (get-in updated [:a :b])))))

  (testing "update-in preserves outer ClosedRecord"
    (let [cr (cr/closed-record {:a {:b 1}} {:recursive true})
          updated (update-in cr [:a :b] inc)]
      (is (cr/closed-record? updated))
      (is (= 2 (get-in updated [:a :b]))))))

(deftest to-map-recursive-test
  (testing "Deeply unwraps all levels to plain maps"
    (let [cr (cr/closed-record {:a {:b {:c 1}}} {:recursive true})
          plain (cr/to-map-recursive cr)]
      (is (not (cr/closed-record? plain)))
      (is (map? plain))
      (is (not (cr/closed-record? (:a plain))))
      (is (map? (:a plain)))
      (is (not (cr/closed-record? (:b (:a plain)))))
      (is (map? (:b (:a plain))))
      (is (= 1 (get-in plain [:a :b :c])))))

  (testing "Unwraps vectors of ClosedRecords"
    (let [cr (cr/closed-record {:items [{:id 1} {:id 2}]} {:recursive true})
          plain (cr/to-map-recursive cr)]
      (is (not (cr/closed-record? plain)))
      (is (= 2 (count (:items plain))))
      (is (not (cr/closed-record? (first (:items plain)))))
      (is (= {:id 1} (first (:items plain))))
      (is (= {:id 2} (second (:items plain))))))

  (testing "Idempotent on plain maps"
    (let [m {:a {:b 1}}]
      (is (= m (cr/to-map-recursive m)))))

  (testing "Handles non-map values"
    (is (= "hello" (cr/to-map-recursive "hello")))
    (is (= 42 (cr/to-map-recursive 42)))
    (is (nil? (cr/to-map-recursive nil)))))

(deftest spec-free-recursive-round-trip-test
  (testing "Round-trip: wrap → to-map-recursive → compare with original"
    (let [original {:name "Alice"
                    :address {:street "123 Main" :city "NYC"}
                    :items [{:id 1 :label "A"} {:id 2 :label "B"}]
                    :count 42}
          cr (cr/closed-record original {:recursive true})
          unwrapped (cr/to-map-recursive cr)]
      (is (= original unwrapped))))

  (testing "Round-trip with deep nesting"
    (let [original {:a {:b {:c {:d 1}}}}
          cr (cr/closed-record original {:recursive true})
          unwrapped (cr/to-map-recursive cr)]
      (is (= original unwrapped)))))

;; Specs for the regression test
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

(deftest spec-free-recursive-no-regression-test
  (testing "Spec-based recursive still works (no regression)"
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
      ;; Nested user-obj is also a ClosedRecord
      (is (cr/closed-record? (:user-obj msg-record)))
      ;; Valid nested access works
      (is (= "alice" (:name (:user-obj msg-record))))
      ;; Typo caught
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"INVALID KEY ACCESS: :nam"
                            (:nam (:user-obj msg-record)))))))

(deftest spec-free-recursive-app-state-shape-test
  (testing "Real-world app-state shape with recursive wrapping"
    (let [app-state {:chat {:messages [{:role "user" :content "Hello"}
                                       {:role "assistant" :content "Hi!"}]
                            :model "claude-3"}
                     :ui {:sidebar-open true
                          :theme "dark"}
                     :examples [{:title "Example 1" :body "Content 1"}
                                {:title "Example 2" :body "Content 2"}]}
          cr (cr/closed-record app-state {:recursive true})]
      ;; Top level
      (is (cr/closed-record? cr))
      (is (cr/closed-record? (:chat cr)))
      (is (cr/closed-record? (:ui cr)))
      ;; Nested values
      (is (= "claude-3" (:model (:chat cr))))
      (is (= true (:sidebar-open (:ui cr))))
      ;; Messages are wrapped
      (is (cr/closed-record? (first (:messages (:chat cr)))))
      (is (= "user" (:role (first (:messages (:chat cr))))))
      ;; Examples are wrapped
      (is (cr/closed-record? (first (:examples cr))))
      (is (= "Example 1" (:title (first (:examples cr)))))
      ;; Typos caught at every level
      (is (thrown? clojure.lang.ExceptionInfo (:cht cr)))
      (is (thrown? clojure.lang.ExceptionInfo (:modl (:chat cr))))
      (is (thrown? clojure.lang.ExceptionInfo (:sidebar_open (:ui cr))))
      (is (thrown? clojure.lang.ExceptionInfo (:titl (first (:examples cr))))))))
