(ns konserve-rocksdb.core
  "Address globally aggregated immutable key-value stores(s)."
  (:require [konserve.impl.defaults :refer [connect-default-store]]
            [konserve.protocols :as protocols]
            [konserve.impl.storage-layout :refer [PBackingStore PBackingBlob PBackingLock
                                                  PMultiWriteBackingStore PMultiReadBackingStore
                                                  -delete-store]]
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
   :val-decoder nippy/thaw
   ;; Opens an OptimisticTransactionDB, which is what `compare-and-put!` needs to
   ;; fence a write. It costs nothing otherwise — an OptimisticTransactionDB IS a
   ;; RocksDB, so every other operation is unchanged.
   :transactional? true
   :create-if-missing? true})

(extend-protocol PBackingLock
  Boolean
  (-release [_ env]
    (if (:sync? env) nil (go-try- nil))))

(defrecord RocksDBKV [store db key data]
  PBackingBlob
  (-sync [_ env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try-
                 (let [{:keys [header meta value]} @data
                       expected-revision (:expected-revision env)
                       meta-key (str key ".meta")
                       new-meta (dissoc @data :value)]
                   (when (and header meta value)
                     (if expected-revision
                       ;; FENCED. konserve has already compared the revision it read
                       ;; against the caller's; the transaction closes the window
                       ;; BETWEEN that read and this write, which is the half no
                       ;; counter can do. Both together are the compare-and-set.
                       ;;
                       ;; The comparison is on the META entry, because that is where
                       ;; the revision lives — and both entries are written inside
                       ;; the one transaction that carries it, so a fenced write
                       ;; cannot leave the metadata ahead of the value.
                       ;;
                       ;; What was read is remembered by `-read-header`, since
                       ;; `-sync` runs on a DIFFERENT blob record than the read did.
                       ;; No entry means no read happened, which for a fenced write
                       ;; is create-if-absent.
                       ;;
                       ;; THIS ASSUMES ONE IN-FLIGHT FENCED WRITE PER KEY PER
                       ;; BACKING, and that holds because konserve's `go-locked`
                       ;; serialises per key on a store instance and RocksDB refuses
                       ;; a second open of the same directory, so there is exactly
                       ;; one such instance. Break either and the entry is wrong
                       ;; rather than missing: a second reader would overwrite it,
                       ;; and the first writer would then fence against a revision
                       ;; it never read. Measured, by forcing that state with
                       ;; independent lock registries over one backing: 55 of 60
                       ;; increments survived. The assumption is doing real work, so
                       ;; it is written down rather than left implicit.
                       (let [cache (:read-cache store)
                             expected (get @cache meta-key rocksdb/absent)]
                         (try
                           (when-not (rocksdb/compare-and-put!
                                      db meta-key expected
                                      {meta-key new-meta key (:value @data)})
                             (throw (ex-info (str "Conditional write rejected: the stored metadata is "
                                                  "not the one this write was derived from.")
                                             {:type :konserve/revision-mismatch
                                              :key key
                                              :expected expected-revision})))
                           (finally
                             ;; Whatever happened, this read is spent.
                             (swap! cache dissoc meta-key))))
                       (do
                         (rocksdb/put db meta-key new-meta)
                         (rocksdb/put db key (:value @data)))))))))
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
                             ;; Remember it for a fenced `-sync`, and only for one.
                             ;; The read preceding a conditional write carries
                             ;; `:expected-revision` in its env, so we can tell —
                             ;; caching on every read would hold the metadata of
                             ;; every key a store ever touched.
                             (when (:expected-revision env)
                               (swap! (:read-cache store) assoc (str key ".meta") meta))
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

(defrecord RocksDBStore [path db read-cache]
  ;; RocksDB evaluates the comparison — `compare-and-put!` runs it inside an
  ;; optimistic transaction whose commit fails if anyone wrote the compared key in
  ;; between. konserve adds no mechanism of its own: no sidecar, no lock.
  protocols/PSelfConditionalWrite

  protocols/PConditionalWrite
  ;; `:process`, and no further. RocksDB takes an exclusive OS lock on the database
  ;; directory, so a second process cannot open it at all — the transaction is
  ;; atomic against every thread that can reach this database, and there is nobody
  ;; else by construction.
  (-conditional-write-domain [_] :process)

  PBackingStore
  (-create-blob [this store-key env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try- (RocksDBKV. this @db store-key (atom {})))))
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
  (-multi-read-blobs [this store-keys env]
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
                                   (let [blob (RocksDBKV. this @db store-key
                                                          (atom (assoc meta-data :value value)))]
                                     (assoc acc store-key blob))
                                   acc)))
                             {}
                             store-keys)))))))

(defn connect-rocksdb-store
  "Connect a konserve store backed by RocksDB.

  Everything except `:opts` and `:config` is forwarded to
  `connect-default-store`, so `:default-serializer`, the handler maps and
  `:buffer-size` all work. They did not before: this built a LITERAL config
  map and destructured only `:opts`, so a caller asking for a different
  serializer got Fressian and was told nothing.

  It also passed `:compressor null-compressor` and `:encryptor
  null-encryptor`, which `connect-default-store` never reads -- it takes both
  from `(get-in config [:compressor :type])`. Dead keys that made a top-level
  spelling look supported; removed."
  [path & {:keys [opts config] :as params}]
  (let [complete-opts (merge {:sync? true} opts)
        backing (RocksDBStore. path (atom nil) (atom {}))
        store-config (merge {:default-serializer :FressianSerializer
                             :buffer-size        (* 1024 1024)}
                            (dissoc params :opts :config)
                            {:path   path
                             :opts   complete-opts
                             :config (merge {:sync-blob? true
                                             :in-place? true
                                             :lock-blob? true}
                                            config)})]
    (connect-default-store backing store-config)))

(defn delete-rocksdb-store [path & {:keys [opts]}]
  (let [complete-opts (merge {:sync? true} opts)
        backing (RocksDBStore. path (atom nil) (atom {}))]
    (-delete-store backing complete-opts)))

(defn release-rocksdb [store]
  (when-let [^Closeable db (some-> store :backing :db deref)]
    (.close db)))

;; =============================================================================
;; Multimethod Registration for konserve.store dispatch
;; =============================================================================

(defmethod store/-connect-store :rocksdb
  [{:keys [path] :as config} opts]
  (async+sync (:sync? opts) *default-sync-translation*
              (go-try-
               ;; Check if store exists
               (when-not (.exists (clojure.java.io/file path))
                 (throw (ex-info (str "RocksDB store does not exist at path: " path)
                                 {:path path :config config})))
               (connect-rocksdb-store path))))

(defmethod store/-create-store :rocksdb
  [{:keys [path] :as config} opts]
  (async+sync (:sync? opts) *default-sync-translation*
              (go-try-
               ;; Check if store already exists
               (when (.exists (clojure.java.io/file path))
                 (throw (ex-info (str "RocksDB store already exists at path: " path)
                                 {:path path :config config})))
               (connect-rocksdb-store path))))

(defmethod store/-store-exists? :rocksdb
  [{:keys [path] :as config} opts]
  (async+sync (:sync? opts) *default-sync-translation*
              (go-try-
               ;; RocksDB store exists if the directory exists
               (.exists (clojure.java.io/file path)))))

(defmethod store/-delete-store :rocksdb
  [{:keys [path] :as config} opts]
  (async+sync (:sync? opts) *default-sync-translation*
              (go-try-
               (delete-rocksdb-store path))))

(defmethod store/-release-store :rocksdb
  [_config store opts]
  ;; Release and return proper async type
  (release-rocksdb store)
  (if (:sync? opts) nil (go-try- nil)))
