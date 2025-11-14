# ClosedRecord: Strict Map Wrapper for Safe Clojure

A Clojure data structure that catches invalid map key access immediately - designed to prevent typos, detect LLM hallucinations, and eliminate silent nil bugs.  I'm using this in all my projects, and am finding all sorts of issues every couple of days that normally would have caused hours of chasing down questions that usually involve asking "where did this nil come from?"

---

## ⚠️ Philosophical Note

**This idea is antithetical to Rich Hickey and the Clojure ethos.**

As Rich Hickey said in his "Spec-ulation" 2016 Clojure/conj talk: "Spec is about what you can do right now. It's not about what you can't do."

When I mentioned this approach of solving the the problem in the #ai-assisted-coding channel, Alex Miller (Clojure team) response was: *"This is the worst thread I've ever read ever :)"* 😂

But hey, if you can [implement monads and optionals](https://github.com/funcool/cats) in Clojure, I can do this, too.

Goals:

1. **Fail fast** - Detect invalid keys at the point of access
2. **Fail loud** - Show errors that both humans and LLMs can see
3. **Fail informatively** - Show what was tried and what's valid

In many ways, this exercise is a testament to the power of the Clojure language.  I'm amazed that it can enable and accommodate this type of use case—even when it goes against the grain of open maps and dynamic typing.  Of particular awe was how easy it was to implement the map protocols (`ILookup`, `IFn`, `Associative`, `IPersistentMap`) via `deftype`, creating a custom type that behaves like a map but with validation.  (See Acknowledgements at the end of this file.)

Just as the fantastic [guardrails](https://github.com/fulcrologic/guardrails) does, you trade in some of Clojure's flexibility for additinal runtime safety. This is especially helpful when typos cause silent nil bugs, or when working with LLMs, where it hallucinates `:user_id` when you meant `:user-id`.


---

## The Problem

Typos in map keys silently return `nil`, causing bugs that propagate through your code:

```clojure
(def user {:name "Alice" :email "alice@example.com"})

;; Typo - silently returns nil
(:nam user)  ;=> nil (missing 'e' - silent failure!)

;; Hours later: "Why is this nil??"
```

**This is especially dangerous when:**
- **Working with LLMs** that may hallucinate key names (`:user_id` vs `:user-id`)
- **Refactoring** and renaming keys across large codebases
- **Accessing nested data** where typos propagate silently
- **Multiple developers** with inconsistent naming conventions
- **UI code** where silent nils cause blank displays (hard to debug)

## The Solution

`ClosedRecord` wraps a map and validates all key access against a schema. Invalid keys fail **immediately** and **loudly**:

```clojure
(require '[closed-record.core :refer [closed-record]])

(def user (closed-record {:name "Alice" :email "alice@example.com"}))

(:name user)   ;=> "Alice" ✅
(:nam user)    ;=> THROWS! "INVALID KEY ACCESS: :nam (valid keys: [:email :name])"
```

## Design Philosophy

### The Schema Investment Paradox

**When you invest significant energy in defining your domain and developing schemas, you actually don't want freedom anymore. You want to lock things down and ensure you only get legitimate outcomes.**

Think of it as two phases of development:

**Phase 1: Exploration** (Open Maps Phase)
- You're discovering your domain
- You don't know what keys you need yet
- Flexibility is essential - open maps are perfect
- Decoupling is good because the domain is still evolving

**Phase 2: Hardening** (Closed Maps Phase) ← **You are here**
- You've defined your canonical schema
- You know EXACTLY what keys should exist
- The exploration phase is over
- Now you want **tight coupling** between objects and system
- You want runtime enforcement of what you've carefully designed -- in fact, heck, if there were a way to get compile-time checking, I'd use it!

**The insight**: When you know everything about your domain model, you don't want such loose coupling. Loose coupling is valuable, lowers the cost of exploration, and increases option value, which is so valuable when things are uncertain. But once you've invested in defining schemas, specs, and transformations, this can become a liability, because it allows violations of what you've carefully specified.

ClosedRecord is for **Phase 2**: When you've done the hard work of schema design and now want to enforce it strictly. It's not for exploration; it's for production-hardening code where schema violations should be caught immediately.

**In other words**: Open maps are great for discovery. Closed maps are great for preventing bugs in well-defined domains. Use the right tool for the right phase.

### Postel's Law in Reverse

Traditional software follows **Postel's Law**: "Be liberal in what you accept, be conservative in what you send."

But when precision matters, **liberal acceptance causes pain**. ClosedRecord flips this:

- **Conservative in acceptance**: Only valid keys allowed (catch typos/hallucinations)
- **Liberal in communication**: LOUD errors so you can't miss them



## Installation

```clojure
;; deps.edn
{:deps {closed-record/closed-record {:git/url "https://github.com/realgenekim/closed-record"}}}
```

## Quick Start

```clojure
(require '[closed-record.core :refer [closed-record]]
         '[clojure.spec.alpha :as s])

;; Basic usage - schema derived from data
(def user (closed-record {:name "Alice" :email "alice@example.com"}))

(:name user)   ;=> "Alice" ✅
(:nam user)    ;=> THROWS! (typo caught!)

;; With clojure.spec - automatic schema extraction
(s/def ::id string?)
(s/def ::name string?)
(s/def ::email string?)
(s/def ::user (s/keys :req-un [::id ::name ::email]))

(def user (closed-record {:id "1" :name "Alice" :email "alice@example.com"}
                         {:spec ::user}))

;; Recursive wrapping - protect nested maps too!
(s/def ::address (s/keys :req-un [::street ::city]))
(s/def ::person (s/keys :req-un [::name ::address]))

(def person (closed-record {:name "Alice"
                            :address {:street "123 Main" :city "NYC"}}
                           {:spec ::person :recursive true}))

(-> person :address :city)    ;=> "NYC" ✅
(-> person :address :cty)     ;=> THROWS! (typo in nested map caught!)
```

## Key Design Decisions

### 1. Invalid Reads: THROW by Default

```clojure
(:invalid-key cr)
;; **** INVALID KEY ACCESS: :invalid-key (valid keys: [...]) ****
;; Throws ex-info
```

**Why throw?** You want to catch the error immediately, not propagate it through your code.

### 2. Invalid Writes: ALWAYS Throw

```clojure
(assoc cr :bad-key "value")
;; **** INVALID KEY ASSOC: :bad-key (valid keys: [...]) ****
;; Throws ex-info
```

**Why?** You NEVER want to add invalid keys to your data structure. This is a programming error.

### 3. Works With Your Existing Code

ClosedRecord implements all Clojure protocols, so it works everywhere maps work:

```clojure
;; All these work!
(:key cr)              ; keyword lookup
(get cr :key)          ; get function
(cr :key)              ; function invocation
(-> cr :key)           ; threading
(assoc cr :key val)    ; returns new ClosedRecord
(keys cr)              ; standard map operations
(count cr)
(seq cr)
(dissoc cr :key)
(merge cr other-map)
```

### 4. Flexible Schema

```clojure
;; Schema derived from example data
(closed-record {:id 1 :name "Alice"})
;; Schema: #{:id :name}

;; Explicit schema (allows future keys not in initial data)
(closed-record {:id 1} {:schema #{:id :name :email}})
;; Can later: (assoc cr :email "alice@example.com")

;; With clojure.spec
(closed-record data {:spec ::user})

;; Add keys to schema later
(add-valid-keys cr :phone :address)
```

## Usage Examples

### Catch LLM Hallucinations

```clojure
(def msg {:message-key "msg_123"
          :user-id "U123"
          :text "Hello"})
(def cr (closed-record msg))

;; Correct access
(:message-key cr)  ;=> "msg_123"

;; Common LLM hallucinations - ALL CAUGHT!
(:mesage-key cr)    ;=> THROWS! (missing 's')
(:message_key cr)   ;=> THROWS! (underscore vs dash)
(:messageKey cr)    ;=> THROWS! (camelCase)
(:user_id cr)       ;=> THROWS! (underscore vs dash)
```

### Wrap Database Results

```clojure
(require '[myapp.db :as db])

;; Wrap results in ClosedRecord
(def users (map #(closed-record % {:spec ::user}) (db/get-users)))

;; Typos fail LOUDLY
(-> (first users) :nam)     ;=> THROWS! (typo caught!)
(-> (first users) :name)    ;=> "Alice" (works)
```

### Integration in Your Pipeline

```clojure
(defn process-message! [raw-msg]
  ;; Wrap in ClosedRecord at pipeline entry
  (let [msg (closed-record raw-msg {:spec ::message})]
    ;; All downstream code catches typos automatically
    (-> msg
        :text                    ; typo = immediate exception
        (analyze-sentiment!)
        (store-result!))))
```

### Database Boundaries + Transformations

**Real-world pattern from server2 project:** Wrap at database boundaries for typo protection, but convert to plain maps when transformations need to add fields.

```clojure
(require '[closed-record.core :as cr])

;; 1. Wrap database results at the boundary
(defn get-starred-posts [database opts]
  (let [raw-posts (mongodb/get-posts ...)]
    (->> raw-posts
         (map cr/closed-record)  ; Typo protection!
         vec)))

;; 2. Reading database results is now typo-safe
(let [posts (get-starred-posts db {})]
  (:post-id (first posts))   ;=> Works!
  (:pst-id (first posts)))   ;=> THROWS! Typo caught!

;; 3. When transformations need to add fields, use to-map (idempotent!)
(defn extract-media-url [post]
  (let [;; to-map is idempotent - works on both ClosedRecords and plain maps
        plain-post (cr/to-map post)
        ;; Now transformation can add :data, :preview, etc.
        transformed (xform/transform plain-post)]
    transformed))
```

**Why this works:**
- ✅ ClosedRecord catches typos when reading DB results
- ✅ `to-map` is idempotent - no need for defensive checks
- ✅ Transformation code can add new fields freely
- ✅ Best of both worlds - safety + flexibility

**Key insight:** Use ClosedRecord for *reading* data at boundaries. Use `to-map` before *transforming* data (adding fields, reshaping, etc). Since `to-map` is idempotent, you can safely call it without checking whether you have a ClosedRecord or plain map.

### Swing/UI Applications

Perfect for preventing silent nil bugs in UI code:

```clojure
(defn update-detail-panel! [issue]
  (.setText title-label (:title issue))      ; Safe!
  (.setText status-label (:status issue)))   ; Typo caught immediately!

;; Plain map: Shows blank status (hard to debug)
;; ClosedRecord: THROWS with helpful error message!
```

## API Reference

### `closed-record`

Create a ClosedRecord that validates key access.

```clojure
(closed-record data)
(closed-record data opts)
```

**Options:**
- `:schema` - Explicit set of allowed keys
- `:spec` - clojure.spec keyword to derive schema from
- `:recursive` - Recursively wrap nested maps (default: false)
- `:throw-on-invalid-read` - Throw on invalid reads (default: true)
- `:throw-on-invalid-write` - Throw on invalid writes (default: true)
- `:relax-constructor-constraints?` - Allow extra keys in data (default: false)

**Examples:**

```clojure
;; Strict mode (default)
(closed-record {:id 1 :name "Alice"})

;; Explicit schema
(closed-record {:id 1} {:schema #{:id :name :email}})

;; With spec
(closed-record data {:spec ::user})

;; Recursive
(closed-record data {:spec ::person :recursive true})

;; Lenient constructor (for external APIs with extra fields)
(closed-record api-response {:spec ::user :relax-constructor-constraints? true})
```

### Helper Functions

```clojure
(closed-record? x)         ;; Returns true if x is a ClosedRecord
(valid-keys cr)            ;; Returns set of valid keys
(to-map x)                 ;; Convert to plain map (idempotent - works on maps too!)
(underlying-map cr)        ;; Extract underlying map (ClosedRecord only)
(add-valid-keys cr & ks)   ;; Add keys to schema
(with-schema cr schema)    ;; Replace schema
```

## When to Use It

### ✅ Perfect For:

- **Wrapping database query results** - Validate schema at data boundaries
- **Pipeline entry points** - Validate early, fail fast
- **Working with LLMs** - Catch hallucinations immediately
- **Swing/Desktop UI apps** - Prevent silent nil bugs in UI code
- **Debugging typo-heavy sessions** - See errors the moment they happen
- **Onboarding new developers** - Schema violations are obvious
- **Well-defined domains** - When you know EXACTLY what keys should exist

### 🤔 Maybe Not Needed For:

- **Exploratory phase** - When still discovering your domain
- **Internal functions with well-tested schemas** - Overhead may not be worth it
- **Performance-critical hot paths** - Small overhead from protocol dispatch
- **Data already validated by specs** - Redundant validation (but still useful!)
- **Truly dynamic data** - Where keys genuinely vary

## Example: Before and After

### Before (Silent Failure)

```clojure
(def users (db/get-users))

;; Typo - returns nil, bug propagates
(-> (first users) :nam)  ;=> nil (missing 'e')
(-> (first users) :user_id)  ;=> nil (underscore vs dash)

;; Hours later: "Why is this nil??"
```

### After (Immediate Detection)

```clojure
(def users (map #(closed-record % {:spec ::user}) (db/get-users)))

;; Typo - INSTANT feedback
(-> (first users) :nam)
;; **** INVALID KEY ACCESS: :nam (valid keys: [:email :id :name]) ****
;; Throws ex-info

;; Fix the typo immediately!
(-> (first users) :name)  ;=> "Alice" ✅
```

## Real Bugs Caught in Production

**From the server2 project** - These are actual bugs ClosedRecord caught during development:

### Bug #1: Wrong Field Name Assumption

**The Problem:** Test was checking for `:id` field, but MongoDB posts use `:_id`

```clojure
;; WITHOUT ClosedRecord - Silent failure
(deftest test-starred-posts
  (let [posts (get-starred-posts db {})]
    (is (:id (first posts)))))  ;=> nil (test passes but field doesn't exist!)

;; Hours of debugging: "Why is :id always nil even when posts exist?"
```

```clojure
;; WITH ClosedRecord - Immediate discovery
(deftest test-starred-posts
  (let [posts (get-starred-posts db {})]
    (is (:id (first posts)))))

;; **** INVALID KEY ACCESS: :id (valid keys: [:_id :added-timestamp :metadata :post :post-id :post-timestamp :starred]) ****
;; Throws ex-info

;; Immediately see: "Oh! MongoDB uses :_id, not :id"
;; Fix in seconds: (is (:_id (first posts)))
```

**Impact:** Without ClosedRecord, the test would silently return `nil` and you'd spend hours debugging why. With ClosedRecord, you see the valid keys immediately and fix it in seconds.

### Bug #2: Transformation Adding Invalid Fields

**The Problem:** Transformation code tried to `assoc` a `:data` field that wasn't in the database schema

```clojure
;; WITHOUT ClosedRecord - Silent mutation
(defn extract-image-url [post]
  (let [transformed (xform/db>app post)]  ; Silently adds :data field
    (:url transformed)))

;; Works fine, but now your data has mystery fields
;; Later: "Where did :data come from? Is it safe to use?"
```

```clojure
;; WITH ClosedRecord - Caught immediately
(defn extract-image-url [post]
  (let [transformed (xform/db>app post)]
    (:url transformed)))

;; **** INVALID KEY ASSOC: :data (valid keys: [:_id :added-timestamp :metadata :post :post-id :post-timestamp :starred]) ****
;; Throws ex-info

;; Immediately see: "Transformation is adding fields! Need to convert to plain map first"
;; Fix: (xform/db>app (cr/to-map post))
```

**Impact:** Without ClosedRecord, transformations silently add fields and you have unclear data contracts. With ClosedRecord, you're forced to be explicit: "This transformation adds fields, so convert to plain map first."

**The Pattern This Revealed:**
- ✅ Use ClosedRecord when **reading** data (typo protection)
- ✅ Convert to plain map when **transforming** data (flexibility)
- ✅ Best of both worlds!

## Performance Characteristics

**Memory overhead**: Minimal - stores schema set + config map + underlying data

**Access overhead**: One protocol dispatch + set lookup (nanoseconds)

**Creation overhead**: One set creation from keys (negligible)

**Benchmark** (rough):
```clojure
;; Plain map access: ~20ns
(-> plain-map :key)

;; ClosedRecord access: ~50ns (2.5x slower)
(-> closed-record :key)

;; But validation prevents bugs worth 1000x the cost!
```

## Implementation Notes

ClosedRecord is implemented as a `deftype` with full protocol support:

- `ILookup` - Handles `(:key cr)` and `(get cr :key)` syntax
- `IFn` - Handles `(cr :key)` syntax
- `Associative` - Handles `assoc`, `contains?`, `find`
- `IPersistentMap` - Handles `dissoc`
- `Seqable`, `Counted` - Standard collection operations
- `IPersistentCollection` - Handles `cons`, `empty`, `equiv`

All operations that modify the record return a new `ClosedRecord` with the same schema.

## FAQ

**Q: Doesn't this violate Rich Hickey's philosophy of open maps?**

A: Yes, intentionally. Open maps are great for systems where keys are uncertain. When you've invested in defining schemas and know EXACTLY what keys should exist, strictness prevents bugs. Use ClosedRecord when you're in Phase 2 (hardening) not Phase 1 (exploration).

**Q: Why not just use clojure.spec?**

A: Spec validates data shape but doesn't prevent invalid key access during development. ClosedRecord provides **immediate feedback at the REPL** - you see errors the moment you typo a key name. Use both together!

**Q: What about performance?**

A: Small overhead (2-3x slower than plain map access), but the cost of hunting down a nil-related bug is 1000x higher. Use ClosedRecord at boundaries, not in tight loops.

**Q: Can I convert back to a plain map?**

A: Yes! `(to-map cr)` returns the underlying map. Use for interop with libraries that expect plain maps.

**Q: Does it work with nested maps?**

A: Yes, with `:recursive true`:

```clojure
(closed-record data {:spec ::person :recursive true})
;; Now nested maps are also ClosedRecords!
```

**Q: Can I use it with defrecord?**

A: ClosedRecord is an alternative to defrecord with different trade-offs. defrecord provides Java interop and defined fields; ClosedRecord provides runtime validation and LLM/typo safety.

## Related Work

- **defrecord** - Provides compile-time field definition, but allows arbitrary key assoc
- **clojure.spec** - Validates data shape, doesn't prevent invalid key access (use together!)
- **schema (Prismatic)** - Similar to spec, focuses on validation not access control
- **malli** - Modern schema library with closed map support
- **Java classes with getters/setters** - I experimented with generating Java classes with explicit getters and setters for strict field access, but have been frustrated by REPL reload issues when using JVM classes that needed regeneration. ClosedRecord provides similar safety without leaving Clojure-land.

ClosedRecord is **complementary** to these tools - use it when runtime key validation matters.

## Documentation

See [docs/WHY.md](docs/WHY.md) for detailed rationale and examples.

## License

MIT License

## Credits

**Created to solve the chronic problem of typos and LLM key hallucinations.**

**Inspiration and guidance**:
- **Paulo Feodrippe** ([@pfeodrippe](https://github.com/pfeodrippe)) - Pointed toward Potemkin's `def-map-type` as foundation
- **Zach Tellman** ([@ztellman](https://github.com/ztellman)) - Author of [Potemkin](https://github.com/clj-commons/potemkin), which inspired the protocol-based implementation

**Technical foundation**:
- Inspired by [Potemkin's def-map-type](https://github.com/clj-commons/potemkin?tab=readme-ov-file#def-map-type)
- ClosedRecord uses pure `deftype` with protocol implementations

---

**Remember**:
- Fail fast ⚡
- Fail loud 📢
- Fail informatively 📋

Happy coding! 🤖✨
