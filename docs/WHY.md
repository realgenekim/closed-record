# Why We're Using ClosedRecord in bd-viewer

## The Problem: Silent Nil Values from Typos

When working with maps in Clojure, a typo in a key name silently returns `nil`:

```clojure
(def issue {:title "Bug fix" :description "Fix the bug" :priority 0})

;; Oops! Typo in key name
(:desription issue)  ;=> nil  😱 (Should be :description)

;; This bug propagates through your code silently!
(if-let [desc (:desription issue)]  ; Always false due to typo
  (display-desc desc)
  (display-error "No description"))  ; Wrong branch taken!
```

**This is especially dangerous when:**
- Working with LLMs that may hallucinate key names
- Refactoring and renaming keys
- Accessing deeply nested data structures
- Multiple developers on a project with inconsistent naming

## The Solution: ClosedRecord

ClosedRecord is a **map wrapper that validates key access** against a schema. Invalid keys **fail loudly** instead of silently returning nil:

```clojure
(require '[slack-archive.util.closed-record :refer [closed-record]])

(def issue (closed-record {:title "Bug fix" :description "Fix the bug" :priority 0}))

;; Valid access works normally
(:title issue)  ;=> "Bug fix" ✅
(:description issue)  ;=> "Fix the bug" ✅

;; Typo throws an exception immediately!
(:desription issue)
;=> ExceptionInfo: INVALID KEY ACCESS: :desription
;   (valid keys: [:description :priority :title])
```

## Key Features for bd-viewer

### 1. Early Error Detection
Catch typos at runtime **immediately** instead of chasing nil values through your code.

### 2. Explicit Schema
Define exactly what keys are valid:

```clojure
;; From bd list --json output
(def issue-data {:id "bd-viewer-1"
                 :title "..."
                 :description "..."
                 :status "open"
                 :priority 0})

;; Wrap with ClosedRecord
(def issue (closed-record issue-data))

;; Now this fails loudly:
(:titel issue)  ;=> THROWS! (typo caught)
```

### 3. Integration with clojure.spec

Define your schema once using spec, get automatic validation:

```clojure
(require '[clojure.spec.alpha :as s])

(s/def ::id string?)
(s/def ::title string?)
(s/def ::description string?)
(s/def ::status #{"open" "closed" "in-progress"})
(s/def ::priority (s/int-in 0 5))
(s/def ::issue-type #{"bug" "feature" "task" "epic" "chore"})
(s/def ::labels (s/coll-of string?))
(s/def ::created_at string?)
(s/def ::updated_at string?)

(s/def ::issue
  (s/keys :req-un [::id ::title ::status ::priority ::issue-type ::created_at ::updated_at]
          :opt-un [::description ::labels]))

;; Create ClosedRecord from spec
(def issue (closed-record issue-data {:spec ::issue}))

;; Schema is derived from spec automatically!
(:status issue)   ;=> "open" ✅
(:staus issue)    ;=> THROWS! (typo in status)
```

### 4. Recursive Protection

Protect nested data structures too:

```clojure
(s/def ::user (s/keys :req-un [::user-id ::name]))
(s/def ::message (s/keys :req-un [::text ::user]))

(def msg-data {:text "Hello"
               :user {:user-id "U123" :name "Alice"}})

;; Recursive wrapping
(def msg (closed-record msg-data {:spec ::message :recursive true}))

;; Both top-level AND nested maps are protected!
(-> msg :user :name)   ;=> "Alice" ✅
(-> msg :user :nam)    ;=> THROWS! (typo in nested field caught!)
```

### 5. Safe Updates

Can't accidentally add invalid keys:

```clojure
(assoc issue :status "closed")      ;=> ✅ Valid key
(assoc issue :stat "closed")        ;=> THROWS! Invalid key
(assoc issue :new-field "value")    ;=> THROWS! Not in schema
```

## Why This is Perfect for bd-viewer

### 1. Beads Issue Schema is Stable
The `bd list --json` output has a well-defined structure we can validate against.

### 2. Swing UI Has Many Field Accesses
We'll be reading issue fields constantly to populate UI components. Typos would cause silent bugs.

### 3. State Management Safety
Our app state atom will contain many issues. ClosedRecord ensures we never access invalid keys:

```clojure
;; In db.clj
(defonce *app-state
  (atom {:issues []          ; Vector of ClosedRecord issues
         :selected-issue nil
         :filter-text ""}))

;; In effects/swing.clj
(defn update-detail-panel! [issue]
  (when issue
    (.setText title-label (:title issue))       ; Safe!
    (.setText desc-area (:description issue))   ; Safe!
    (.setText status-label (:status issue))))   ; Safe!

;; If we typo:
(.setText status-label (:staus issue))  ; THROWS immediately!
```

### 4. LLM-Assisted Development
When Claude Code generates code, it might hallucinate key names. ClosedRecord catches these errors immediately during testing.

### 5. First Time Using In Anger
This is your first real-world use of ClosedRecord! It's the perfect project:
- Well-defined external data format (bd CLI JSON)
- Lots of map access patterns (UI rendering)
- State management with atoms
- Active development with rapid iteration

## How We'll Use It

### Setup (via Symbolic Link)

```bash
# From bd-viewer directory
cd src/bd_viewer
mkdir -p util
cd util
ln -s ../../../slack-retriever/src/slack_archive/util/closed_record.clj closed_record.clj
```

Or copy the file if you prefer independence:

```bash
cp ../slack-retriever/src/slack_archive/util/closed_record.clj \
   src/bd_viewer/util/closed_record.clj
```

### Define Schema (in db.clj)

```clojure
(ns bd-viewer.db
  (:require [clojure.spec.alpha :as s]
            [bd-viewer.util.closed-record :refer [closed-record]]))

;; Spec for beads issue (from bd list --json)
(s/def ::id string?)
(s/def ::title string?)
(s/def ::description string?)
(s/def ::status #{"open" "closed" "in-progress"})
(s/def ::priority (s/int-in 0 5))
(s/def ::issue-type #{"bug" "feature" "task" "epic" "chore"})
(s/def ::labels (s/coll-of string?))
(s/def ::created-at string?)
(s/def ::updated-at string?)
(s/def ::assignee (s/nilable string?))

(s/def ::issue
  (s/keys :req-un [::id ::title ::status ::priority ::issue-type ::created-at ::updated-at]
          :opt-un [::description ::labels ::assignee]))

;; Wrap issues when loading
(defn load-issues-from-bd []
  "Load issues from bd CLI and wrap in ClosedRecord"
  (let [raw-issues (-> (shell/sh "bd" "list" "--json")
                       :out
                       (json/parse-string true))]
    (mapv #(closed-record % {:spec ::issue}) raw-issues)))
```

### Access Safely Everywhere

```clojure
;; In events.clj
(defmethod handle-event ::issue-selected [event]
  (let [issue-id (:issue-id event)
        issue (first (filter #(= (:id %) issue-id)  ; Safe!
                           (:issues @db/*app-state)))]
    (swap! db/*app-state assoc :selected-issue issue)))

;; In effects/swing.clj
(defn populate-detail-panel! [panel issue]
  (SwingUtilities/invokeLater
    (fn []
      (.setText (:title-label panel) (:title issue))           ; Safe!
      (.setText (:desc-area panel) (:description issue ""))    ; Safe!
      (.setText (:status-label panel) (:status issue))         ; Safe!
      (.setText (:priority-label panel) (str (:priority issue)))  ; Safe!
      (.setText (:type-label panel) (:issue-type issue))       ; Safe!
      (.setText (:labels-label panel)
                (str/join ", " (:labels issue []))))))         ; Safe!
```

## Benefits Summary

1. **No Silent Failures**: Typos throw exceptions immediately
2. **Better Error Messages**: "INVALID KEY ACCESS: :staus (valid keys: [:status ...])"
3. **Self-Documenting**: Schema shows exactly what keys are valid
4. **Refactoring Safety**: Rename a key, find all usages via exceptions
5. **LLM Safety**: Catch hallucinated key names during testing
6. **Development Speed**: Find bugs faster, iterate with confidence

## The Trade-off

**Cost**: Slightly more verbose setup (define specs, wrap data)

**Benefit**: Catch an entire class of bugs (typo-induced nil values) at the source

For bd-viewer, this is **100% worth it** because:
- We have a stable schema (bd JSON output)
- We'll access these fields hundreds of times across UI code
- First-time real usage is a great learning opportunity
- Silent nil bugs in UI code are especially painful to debug

## Next Steps

1. Create symbolic link to closed_record.clj
2. Define specs in db.clj
3. Wrap all issues in ClosedRecord when loading
4. Enjoy compile-time-like safety in a dynamic language!
