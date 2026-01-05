# konserve-rocksdb

A [RocksDB](https://rocksdb.org/) backend for [konserve](https://github.com/replikativ/konserve), using [clj-rocksdb](https://github.com/kotyo/clj-rocksdb).

## Usage

Add to your dependencies:

[![Clojars Project](http://clojars.org/io.replikativ/konserve-rocksdb/latest-version.svg)](http://clojars.org/io.replikativ/konserve-rocksdb)

### Synchronous Execution

``` clojure
(require '[konserve-rocksdb.core]  ;; Registers the :rocksdb backend
         '[konserve.core :as k])

(def rocksdb-config
  {:backend :rocksdb
   :path "./tmp/rocksdb/konserve"
   :opts {:sync? true}})

;; Create a new store (same as connect for RocksDB - no creation step)
(def store (k/create-store rocksdb-config))

;; Or connect to existing store
;; (def store (k/connect-store rocksdb-config))

;; Check if store exists
(k/store-exists? rocksdb-config) ;; => true

;; Use the store

(k/assoc-in store ["foo" :bar] {:foo "baz"} {:sync? true})
(k/get-in store ["foo"] nil {:sync? true})
(k/exists? store "foo" {:sync? true})

(k/assoc-in store [:bar] 42 {:sync? true})
(k/update-in store [:bar] inc {:sync? true})
(k/get-in store [:bar] nil {:sync? true})
(k/dissoc store :bar {:sync? true})

(k/append store :error-log {:type :horrible} {:sync? true})
(k/log store :error-log {:sync? true})

(let [ba (byte-array (* 10 1024 1024) (byte 42))]
  (time (k/bassoc store "banana" ba {:sync? true})))

(k/bassoc store :binbar (byte-array (range 10)) {:sync? true})
(k/bget store :binbar (fn [{:keys [input-stream]}]
                               (map byte (slurp input-stream)))
       {:sync? true})

;; Clean up
(k/delete-store rocksdb-config)
```

### Asynchronous Execution

``` clojure
(require '[konserve-rocksdb.core]  ;; Registers the :rocksdb backend
         '[clojure.core.async :refer [<! <!!]]
         '[konserve.core :as k])

(def rocksdb-config
  {:backend :rocksdb
   :path "./tmp/rocksdb/konserve"
   :opts {:sync? false}})

;; Create a new store (async)
(def store (<!! (k/create-store rocksdb-config)))

;; Check if store exists (async)
(<!! (k/store-exists? rocksdb-config)) ;; => true

;; Use the store

(<! (k/assoc-in store ["foo" :bar] {:foo "baz"}))
(<! (k/get-in store ["foo"]))
(<! (k/exists? store "foo"))

(<! (k/assoc-in store [:bar] 42))
(<! (k/update-in store [:bar] inc))
(<! (k/get-in store [:bar]))
(<! (k/dissoc store :bar))

(<! (k/append store :error-log {:type :horrible}))
(<! (k/log store :error-log))

(<! (k/bassoc store :binbar (byte-array (range 10))))
(<! (k/bget store :binbar (fn [{:keys [input-stream]}]
                            (map byte (slurp input-stream)))))

;; Clean up
(<!! (k/delete-store rocksdb-config))
```

## Multi-key Operations

This backend supports multi-key operations (`multi-assoc`, `multi-get`, `multi-dissoc`), allowing you to read, write, or delete multiple keys efficiently.

**No hard item limits** - operations scale with your RocksDB instance capacity.

``` clojure
;; Write multiple keys atomically (uses RocksDB WriteBatch)
(k/multi-assoc store {:user1 {:name "Alice"}
                      :user2 {:name "Bob"}}
               {:sync? true})

;; Read multiple keys in one request (uses RocksDB multiGetAsList)
(k/multi-get store [:user1 :user2 :user3] {:sync? true})
;; => {:user1 {:name "Alice"}, :user2 {:name "Bob"}}
;; Note: Returns sparse map - only found keys are included

;; Delete multiple keys atomically (uses RocksDB WriteBatch)
(k/multi-dissoc store [:user1 :user2] {:sync? true})
;; => {:user1 true, :user2 true}
;; Returns map indicating which keys existed before deletion
```

### Implementation Details

| Operation | RocksDB API | Atomicity |
|-----------|-------------|-----------|
| `multi-assoc` | WriteBatch | Atomic |
| `multi-get` | multiGetAsList | Single call |
| `multi-dissoc` | WriteBatch | Atomic |

Data is stored in two keys per entry (`key` for value, `key.meta` for header/metadata), enabling efficient metadata-only reads for garbage collection while using `multiGetAsList` to fetch both in a single call for full reads.

## License

Copyright © 2021-2025 Judith Massa, Christian Weilbach

Licensed under Eclipse Public License (see [LICENSE](LICENSE)).
