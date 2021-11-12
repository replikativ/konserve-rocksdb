(ns konserve-rocksdb.core
  "Address globally aggregated immutable key-value stores(s)."
  (:require [konserve.impl.default :refer [connect-default-store]]
            [konserve.impl.storage-layout :refer [PBackingStore PBackingBlob PBackingLock -delete-store]]
            [konserve.compressor :refer [null-compressor]]
            [konserve.encryptor :refer [null-encryptor]]
            [konserve.utils :refer [async+sync *default-sync-translation*]]
            [superv.async :refer [go-try- <?-]]
            [clojure.core.async :refer [go <!! chan close! put!]]
            [clj-rocksdb :as rocksdb                        ;:refer [DB]
             ]
            [taoensso.nippy :as nippy]
            [taoensso.timbre :refer [warn]])
  (:import (java.io ByteArrayInputStream)
           #_(clj-rocksdb DB)
           (clj_rocksdb DB)))

(set! *warn-on-reflection* 1)

(def rocks-db-config
  {:key-encoder nippy/freeze
   :key-decoder nippy/thaw
   :val-encoder nippy/freeze
   :val-decoder nippy/thaw})

(extend-protocol PBackingLock
  Boolean
  (-release [_ env]
    (if (:sync? env) nil (go-try- nil))))

(defrecord RocksDBKV [db key data]
  PBackingBlob
  (-sync [_ env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try- (let [{:keys [header meta value]} @data]
                           (when (and header meta value)
                             (rocksdb/put db key @data))))))
  (-close [_ env]
    (if (:sync? env) (reset! data {}) (go-try- (reset! data {}))))
  (-get-lock [_ env]
    (if (:sync? env) true (go-try- true)))                       ;; May not return nil, otherwise eternal retries
  (-read-header [_ env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try- (:header (rocksdb/get db key)))))
  (-read-meta [_ _meta-size env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try- (:meta (rocksdb/get db key)))))
  (-read-value [_ _meta-size env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try- (:value (rocksdb/get db key)))))
  (-read-binary [_ _meta-size locked-cb env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try- (locked-cb {:input-stream (ByteArrayInputStream. (:value (rocksdb/get db key)))
                                     :size         nil}))))
  (-write-header [_ header env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try-
                 (swap! data assoc :header header))))
  (-write-meta [_ meta env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try-
                 (swap! data assoc :meta meta))))
  (-write-value [_ value _meta-size env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try-
                 (swap! data assoc :value value))))
  (-write-binary [_ _meta-size blob env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try-
                 (swap! data assoc :value blob)))))

(defrecord RocksDB [path db]
  PBackingStore
  (-create-blob [_ store-key env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try- (RocksDBKV. @db store-key (atom {})))))
  (-delete-blob [_ store-key env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try- (rocksdb/delete @db store-key))))
  (-blob-exists? [_ store-key env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try- (rocksdb/get @db store-key))))
  (-copy [_ from to env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try- (rocksdb/put @db to (rocksdb/get @db from)))))
  (-atomic-move [_ from to env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try- (rocksdb/batch @db {:put [to (rocksdb/get @db from)] :delete from}))))
  (-migratable [_ _key _store-key env]
    (if (:sync? env) nil (go-try- nil)))
  (-migrate [_backing _migration-key _key-vec _serializer _read-handlers _write-handlers env]
    (if (:sync? env) nil (go-try- nil)))
  (-create-store [_ env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try- (reset! db (rocksdb/create-db path rocks-db-config)))))
  (-sync-store [_ env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try- (rocksdb/sync @db))))
  (-delete-store [_ env]
    (println (type @db) @db)
    (async+sync (:sync? env) *default-sync-translation*
                (go-try- (rocksdb/destroy-db path))))
  (-keys [_ env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try- (map first (rocksdb/iterator @db))))))

(defn connect-rocksdb-store [path  & {:keys [opts]}]
  (let [complete-opts (merge {:sync? true} opts)
        backing (RocksDB. path (atom nil))
        config {:path               path
                :opts               complete-opts
                :config             {:sync-blob? true
                                     :in-place? true
                                     :lock-blob? true}
                :default-serializer :FressianSerializer
                :compressor         null-compressor
                :encryptor          null-encryptor
                :buffer-size        (* 1024 1024)}]
    (connect-default-store backing config)))

(defn delete-rocksdb-store [path & {:keys [opts]}]
  (let [complete-opts (merge {:sync? true} opts)
        backing (RocksDB. path (atom nil))]
    (-delete-store backing complete-opts)))

(defn close-rocksdb [store]
  (.close ^DB (-> store :backing :db deref)))

(comment

  (def path "/tmp/rocks")

  (require '[konserve.core :as k])
  (import  '[java.io File])

  (delete-rocksdb-store path :opts {:sync? true})

  (def store (connect-rocksdb-store path :opts {:sync? true}))

  (time (k/assoc-in store ["foo"] {:foo "baz"} {:sync? true}))
  (k/get-in store ["foo"] nil {:sync? true})
  (k/exists? store "foo" {:sync? true})

  (time (k/assoc-in store [:bar] 42 {:sync? true}))
  (k/update-in store [:bar] inc {:sync? true})
  (k/get-in store [:bar] nil {:sync? true})
  (k/dissoc store :bar {:sync? true})

  (k/append store :error-log {:type :horrible} {:sync? true})
  (k/log store :error-log {:sync? true})

  (k/keys store {:sync? true})

  (k/bassoc store :binary-bar (byte-array (range 10)) {:sync? true})
  (k/bget store :binary-bar (fn [{:keys [input-stream]}]
                              (map byte (slurp input-stream)))
          {:sync? true}))

#_(comment

    (require '[konserve.core :as k])
    (require '[clojure.core.async :refer [<!!]])

    (<!! (delete-rocksdb-store db-spec :opts {:sync? false}))

    (def store (<!! (connect-jdbc-store db-spec :opts {:sync? false})))

    (time (<!! (k/assoc-in store ["foo" :bar] {:foo "baz"} {:sync? false})))
    (<!! (k/get-in store ["foo"] nil {:sync? false}))
    (<!! (k/exists? store "foo" {:sync? false}))

    (time (<!! (k/assoc-in store [:bar] 42 {:sync? false})))
    (<!! (k/update-in store [:bar] inc {:sync? false}))
    (<!! (k/get-in store [:bar] nil {:sync? false}))
    (<!! (k/dissoc store :bar {:sync? false}))

    (<!! (k/append store :error-log {:type :horrible} {:sync? false}))
    (<!! (k/log store :error-log {:sync? false}))

    (<!! (k/keys store {:sync? false}))

    (<!! (k/bassoc store :binary-bar (byte-array (range 10)) {:sync? false}))
    (<!! (k/bget store :binary-bar (fn [{:keys [input-stream]}]
                                     (map byte (slurp input-stream)))
                 {:sync? false})))
