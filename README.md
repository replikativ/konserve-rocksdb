# konserve-rocksdb

A [RocksDB](https://github.com/kotyo/clj-rocksdb) backend for [konserve](https://github.com/replikativ/konserve).

## Usage

<!--- Add to your leiningen dependencies:
[[http://clojars.org/io.replikativ/konserve-rocksdb]][[http://clojars.org/io.replikativ/konserve-rocksdb/latest-version.svg]]]]
-->

### Synchronous Execution

``` clojure
(require '[konserve-jdbc.core :refer [connect-rocksdb-store]]
         '[konserve.core :as k])

(def path "./tmp/rocksdb/konserve")
   
(def store (connect-jdbc-store db-spec :opts {:sync? true}))

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
               
```

### Asynchronous Execution

``` clojure
(ns test-db
  (require '[konserve-jdbc.core :refer [connect-rocksdb-store]]
           '[clojure.core.async :refer [<!]]
           '[konserve.core :as k])

(def path "./tmp/rocksdb/konserve")
   
(def store (<! (connect-jdbc-store db-path :opts {:sync? false})))

(<! (k/assoc-in store ["foo" :bar] {:foo "baz"}))
(<! (k/get-in store ["foo"]))
(<! (k/exists? store "foo"))

(<! (k/assoc-in store [:bar] 42))
(<! (k/update-in store [:bar] inc))
(<! (k/get-in store [:bar]))
(<! (k/dissoc store :bar))

(<! (k/append store :error-log {:type :horrible}))
(<! (k/log store :error-log))

(<! (k/bassoc store :binbar (byte-array (range 10)) {:sync? false}))
(<! (k/bget store :binbar (fn [{:keys [input-stream]}]
                            (map byte (slurp input-stream)))
            {:sync? false}))
```


## Commercial support

We are happy to provide commercial support with
[lambdaforge](https://lambdaforge.io). If you are interested in a particular
feature, please let us know.

## License

Copyright © 2021 Judith Massa

Licensed under Eclipse Public License (see [LICENSE](LICENSE)).