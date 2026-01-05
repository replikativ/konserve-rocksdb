(ns konserve-rocksdb.core
  "Address globally aggregated immutable key-value stores(s)."
  (:require [konserve.impl.defaults :refer [connect-default-store]]
            [konserve.impl.storage-layout :refer [PBackingStore PBackingBlob PBackingLock
                                                  PMultiWriteBackingStore PMultiReadBackingStore
                                                  -delete-store]]
            [konserve.compressor :refer [null-compressor]]
            [konserve.encryptor :refer [null-encryptor]]
            [konserve.utils :refer [async+sync *default-sync-translation*]]
            [konserve.store :as store]
            [superv.async :refer [go-try-]]
            [clj-rocksdb :as rocksdb]
            [taoensso.nippy :as nippy]
            [clojure.string :as str])
  (:import (java.io ByteArrayInputStream Closeable)))

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
                             (rocksdb/put db (str key ".meta") (dissoc @data :value))
                             (rocksdb/put db key (:value @data)))))))
  (-close [_ env]
    (if (:sync? env) (reset! data {}) (go-try- (reset! data {}))))
  (-get-lock [_ env]
    (if (:sync? env) true (go-try- true)))                       ;; May not return nil, otherwise eternal retries
  (-read-header [_ env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try- (if-let [header (:header @data)]
                           header
                           (let [meta (rocksdb/get db (str key ".meta"))]
                             (swap! data merge meta)
                             (:header meta))))))
  (-read-meta [_ _meta-size env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try- (if-let [header (:meta @data)]
                           header
                           (let [meta (rocksdb/get db (str key ".meta"))]
                             (swap! data merge meta)
                             (:meta meta))))))
  (-read-value [_ _meta-size env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try- (or (:value @data)
                             (let [value (rocksdb/get db key)]
                               (swap! data assoc :value value)
                               value)))))
  (-read-binary [_ _meta-size locked-cb env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try- (let [value (or (:value @data)
                                         (let [value (rocksdb/get db key)]
                                           (swap! data assoc :value value)
                                           value))]
                           (locked-cb {:input-stream (ByteArrayInputStream. value)
                                       :size         nil})))))
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

(defrecord RocksDBStore [path db]
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
  (-handle-foreign-key [_ _migration-key _serializer _read-handlers _write-handlers env]
    (if (:sync? env) nil (go-try- nil)))
  (-create-store [_ env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try- (reset! db (rocksdb/create-db path rocks-db-config)))))
  (-sync-store [_ env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try- (rocksdb/sync @db))))
  (-delete-store [_ env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try- (rocksdb/destroy-db path))))
  (-keys [_ env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try- (->> (rocksdb/iterator @db)
                              (map first)
                              (filter #(not (str/ends-with? % ".meta")))))))

  PMultiWriteBackingStore
  (-multi-write-blobs [_ store-key-values env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try-
                 (if (empty? store-key-values)
                   {}
                   ;; Build batch with all puts: both key (value) and key.meta (header+meta)
                   (let [puts (mapcat (fn [[store-key {:keys [header meta value]}]]
                                        ;; Put value at key, put header+meta at key.meta
                                        [store-key value
                                         (str store-key ".meta") {:header header :meta meta}])
                                      store-key-values)]
                     (rocksdb/batch @db {:put puts})
                     ;; Return success map
                     (into {} (map (fn [[store-key _]] [store-key true]) store-key-values)))))))

  (-multi-delete-blobs [_ store-keys env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try-
                 (if (empty? store-keys)
                   {}
                   ;; Check which keys exist, then delete both key and key.meta
                   (let [existing-keys (into #{}
                                             (filter #(rocksdb/get @db %))
                                             store-keys)
                         deletes (mapcat (fn [store-key]
                                           [store-key (str store-key ".meta")])
                                         existing-keys)]
                     (when (seq deletes)
                       (rocksdb/batch @db {:delete deletes}))
                     ;; Return map showing which keys existed
                     (into {} (map (fn [k] [k (contains? existing-keys k)]) store-keys)))))))

  PMultiReadBackingStore
  (-multi-read-blobs [_ store-keys env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try-
                 (if (empty? store-keys)
                   {}
                   ;; Fetch all keys and meta keys in one multi-get call
                   (let [all-keys (mapcat (fn [k] [k (str k ".meta")]) store-keys)
                         results (rocksdb/multi-get @db all-keys)]
                     ;; Build sparse map of store-key -> RocksDBKV with pre-populated data
                     (reduce (fn [acc store-key]
                               (let [value (get results store-key)
                                     meta-data (get results (str store-key ".meta"))]
                                 (if (and value meta-data)
                                   ;; Create blob with pre-populated data atom
                                   (let [blob (RocksDBKV. @db store-key
                                                          (atom (assoc meta-data :value value)))]
                                     (assoc acc store-key blob))
                                   acc)))
                             {}
                             store-keys)))))))

(defn connect-rocksdb-store [path & {:keys [opts]}]
  (let [complete-opts (merge {:sync? true} opts)
        backing (RocksDBStore. path (atom nil))
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
        backing (RocksDBStore. path (atom nil))]
    (-delete-store backing complete-opts)))

(defn release-rocksdb [store]
  (when-let [^Closeable db (some-> store :backing :db deref)]
    (.close db)))

;; =============================================================================
;; Multimethod Registration for konserve.store dispatch
;; =============================================================================

(defmethod store/connect-store :rocksdb
  [{:keys [path] :as config} opts]
  (async+sync (:sync? opts) *default-sync-translation*
              (go-try-
               ;; Check if store exists
               (when-not (.exists (clojure.java.io/file path))
                 (throw (ex-info (str "RocksDB store does not exist at path: " path)
                                 {:path path :config config})))
               (connect-rocksdb-store path :opts opts))))

(defmethod store/create-store :rocksdb
  [{:keys [path] :as config} opts]
  (async+sync (:sync? opts) *default-sync-translation*
              (go-try-
               ;; Check if store already exists
               (when (.exists (clojure.java.io/file path))
                 (throw (ex-info (str "RocksDB store already exists at path: " path)
                                 {:path path :config config})))
               (connect-rocksdb-store path :opts opts))))

(defmethod store/store-exists? :rocksdb
  [{:keys [path] :as config} opts]
  (async+sync (:sync? opts) *default-sync-translation*
              (go-try-
               ;; RocksDB store exists if the directory exists
               (.exists (clojure.java.io/file path)))))

(defmethod store/delete-store :rocksdb
  [{:keys [path] :as config} opts]
  (async+sync (:sync? opts) *default-sync-translation*
              (go-try-
               (delete-rocksdb-store path :opts opts))))

(defmethod store/release-store :rocksdb
  [_config store _opts]
  (release-rocksdb store))
