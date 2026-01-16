# konserve-rocksdb

A [RocksDB](https://rocksdb.org/) backend for [konserve](https://github.com/replikativ/konserve), using [clj-rocksdb](https://github.com/kotyo/clj-rocksdb).

## Usage

Add to your dependencies:

[![Clojars Project](http://clojars.org/org.replikativ/konserve-rocksdb/latest-version.svg)](http://clojars.org/org.replikativ/konserve-rocksdb)

## Configuration

``` clojure
(require '[konserve-rocksdb.core]  ;; Registers the :rocksdb backend
         '[konserve.core :as k])

(def config
  {:backend :rocksdb
   :path "./tmp/rocksdb/konserve"
   :id #uuid "550e8400-e29b-41d4-a716-446655440000"
   ;; Optional:
   :map-size (* 1024 1024 1024)  ;; Default: 1GB
   :flags 0})

(def store (k/create-store config {:sync? true}))
```

For API usage (assoc-in, get-in, delete-store, etc.), see the [konserve documentation](https://github.com/replikativ/konserve).

## Implementation Details

### Multi-key Operations

This backend supports multi-key operations (`multi-assoc`, `multi-get`, `multi-dissoc`).

**No hard item limits** - operations scale with your RocksDB instance capacity.

| Operation | RocksDB API | Atomicity |
|-----------|------------|-----------|
| `multi-assoc` | WriteBatch | Atomic |
| `multi-get` | multiGetAsList | Efficient bulk read |
| `multi-dissoc` | WriteBatch | Atomic |

### Storage Format

Data is stored in two keys per entry (`key` for value, `key.meta` for header/metadata). This enables efficient metadata-only reads for garbage collection while using `multiGetAsList` to fetch both in a single call for full reads.

## License

Copyright © 2021-2026 Christian Weilbach, Judith Massa

Licensed under Eclipse Public License (see [LICENSE](LICENSE)).
