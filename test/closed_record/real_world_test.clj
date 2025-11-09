(ns slack-archive.util.real-world-closed-record-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [closed-record.core :as cr]))

(s/def ::emoji-name string?)
(s/def ::reaction-users (s/coll-of string? :kind vector?))
(s/def ::reaction-count int?)
(s/def ::reaction-correct (s/keys :req-un [::emoji-name ::reaction-users ::reaction-count]))
(s/def ::reactions-correct (s/coll-of ::reaction-correct :kind vector?))
(s/def ::msg-with-reactions (s/keys :req-un [::message-key ::text ::reactions-correct]))

(deftest real-world-llm-bugs-caught-test
  (testing "Bug 1: Reaction keys mismatch (emoji-name vs name, reaction-count vs count)"
    ;; Real bug: LLM destructured using {:keys [emoji-name reaction-count]}
    ;; but actual data had {:name "wave" :count 2}
    ;; Result: All reactions silently disappeared from UI

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

(deftest relax-constructor-constraints-test
  (testing "relax-constructor-constraints? true allows extra keys in constructor"
    ;; Use case: We may receive data with extra keys we don't control (e.g., from Slack API)
    ;; Without :relax-constructor-constraints?, spec is too tightly coupled to external data
    ;; With it enabled, we accept any keys but still enforce strict read access

    (let [msg-data {:message-key "C123--456"
                    :text "Great!"
                    :reactions-correct [{:emoji-name "wave"
                                         :reaction-users ["U1" "U2"]
                                         :reaction-count 2}]
                    :unknown-key 123 ; Extra key not in spec
                    :another-extra "value"} ; Another extra key
          msg (cr/closed-record msg-data {:spec ::msg-with-reactions
                                          :recursive true
                                          :relax-constructor-constraints? true})]

      ;; ✅ Closed record created successfully despite extra keys
      (is (= "C123--456" (:message-key msg)))
      (is (= "Great!" (:text msg)))

      ;; ✅ Nested records also work
      (let [reaction (first (:reactions-correct msg))]
        (is (= "wave" (:emoji-name reaction)))
        (is (= 2 (:reaction-count reaction))))

      ;; ❌ But strict read access still enforced - typos still caught!
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"INVALID KEY ACCESS: :unknown-key"
                            (:unknown-key msg)))))

  (testing "Without relax-constructor-constraints?, extra keys cause failure"
    ;; Default behavior: strict spec validation on construction

    (let [msg-data {:message-key "C123--456"
                    :text "Great!"
                    :reactions-correct [{:emoji-name "wave"
                                         :reaction-users ["U1" "U2"]
                                         :reaction-count 2}]
                    :unknown-key 123}] ; Extra key not in spec

      ;; ❌ Construction fails due to extra key
      (is (thrown? clojure.lang.ExceptionInfo
                   (cr/closed-record msg-data {:spec ::msg-with-reactions
                                               :recursive true}))))))

(comment
  ;; Bug 1: REPL test - Reaction key mismatch
  (def msg (cr/closed-record {:message-key "C123--456"
                              :text "Great!"
                              :reactions-correct [{:emoji-name "wave"
                                                   :reaction-users ["U1" "U2"]
                                                   :reaction-count 2}]
                              :unknown-key 123}
                             {:spec ::msg-with-reactions :recursive true}))
  (def reaction (first (:reactions-correct msg)))
  (:emoji-name reaction) ; => "wave" ✅
  (try (:name reaction) (catch Exception e (ex-message e))) ; => "INVALID KEY ACCESS: :name" ❌

  ;; Addition 1: slightly less restrictive
  (def msg (cr/closed-record {:message-key "C123--456"
                              :text "Great!"
                              :reactions-correct [{:emoji-name "wave"
                                                   :reaction-users ["U1" "U2"]
                                                   :reaction-count 2}]
                              :unknown-key 123}
                             {:spec ::msg-with-reactions :recursive true
                              :relax-constructor-constraints? true}))

  (-> msg :xyz)

  ;; Bug 2: REPL test - Reply users underscore vs hyphen
  (def thread-msg (cr/closed-record {:message-key "C123--789"
                                     :text "Thread parent"
                                     :reply-count 1
                                     :reply-users ["U123"]}
                                    {:spec ::thread-msg}))
  (:reply-users thread-msg) ; => ["U123"] ✅
  (try (:reply_users thread-msg) (catch Exception e (ex-message e))) ; => "INVALID KEY ACCESS: :reply_users" ❌

  ;; Bug 3: REPL test - Date vs day field confusion
  (def day-msg (cr/closed-record {:message-key "C123--999"
                                  :text "Hello"
                                  :day "2024-11-04"}
                                 {:spec ::msg-with-day}))
  (:day day-msg) ; => "2024-11-04" ✅
  (try (:date day-msg) (catch Exception e (ex-message e)))) ; => "INVALID KEY ACCESS: :date" ❌

