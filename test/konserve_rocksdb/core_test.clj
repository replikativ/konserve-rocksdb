(ns konserve-rocksdb.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [konserve.core :as k]
            [clojure.core.async :refer [<!!]]
            [konserve.compliance-test :refer [compliance-test
                                              conditional-write-compliance-test]]
            [konserve-rocksdb.core :refer [connect-rocksdb-store
                                           delete-rocksdb-store
                                           release-rocksdb]]))

(deftest rocksdb-compliance-sync-test
  (let [path "/tmp/rocks-db-sync-test"
        _ (delete-rocksdb-store path :opts {:sync? true})
        store  (connect-rocksdb-store path :opts {:sync? true})]
    (testing "Compliance test with synchronous store"
      (compliance-test store))
    (release-rocksdb store)
    (delete-rocksdb-store path :opts {:sync? true})))

(deftest rocksdb-compliance-async-test
  (let [path2 "/tmp/rocks-db-async-test"
        _ (<!! (delete-rocksdb-store path2 :opts {:sync? false}))
        store (<!! (connect-rocksdb-store path2 :opts {:sync? false}))]
    (testing "Compliance test with asynchronous store"
      (compliance-test store))
    (release-rocksdb store)
    (<!! (delete-rocksdb-store path2 :opts {:sync? false}))))

(deftest config-reaches-connect-default-store
  (testing "this built a LITERAL config map and destructured only `:opts`, so
            everything else a caller passed was discarded -- a request for a
            different serializer produced Fressian and said nothing. It also
            shipped `:compressor null-compressor` and `:encryptor
            null-encryptor`, which `connect-default-store` never reads.

            Asserted through a real round trip, because the serializer that
            matters is the one that actually wrote the bytes."
    (let [mk (fn [d & args]
               (try (delete-rocksdb-store d) (catch Exception _ nil))
               (let [s (apply connect-rocksdb-store d args)
                     v {:a (vec (range 50)) :b "x"}]
                 (k/assoc s "c" v {:sync? true})
                 [(:default-serializer s) (= v (k/get s "c" nil {:sync? true}))]))]
      (testing "the default is unchanged"
        (is (= [:FressianSerializer true] (mk "/tmp/krdb-test-default"))))
      (testing "the old top-level spelling reaches the store"
        (is (= [:BoringSerializer true]
               (mk "/tmp/krdb-test-old" :default-serializer :BoringSerializer))))
      (testing "and so does :config :encoding"
        (is (= [:BoringSerializer true]
               (mk "/tmp/krdb-test-enc"
                   :config {:encoding {:serializer :BoringSerializer}})))))))

(deftest rocksdb-conditional-write-test
  (testing "the `:expected-revision` contract.

            The comparison happens inside an optimistic transaction, and both the
            metadata entry and the value entry are written inside it — this backend
            stores a blob as two RocksDB keys, so a fenced write has to cover both
            or it could leave the metadata ahead of the value."
    (let [path "/tmp/rocks-db-cas-test"
          _ (delete-rocksdb-store path :opts {:sync? true})
          store (connect-rocksdb-store path :opts {:sync? true})]
      (try
        (is (= :process (k/conditional-write-domain store))
            "RocksDB holds an exclusive OS lock on the directory, so no other process can reach it")
        (conditional-write-compliance-test store)
        (finally
          (release-rocksdb store)
          (delete-rocksdb-store path :opts {:sync? true}))))))

(deftest rocksdb-concurrent-fenced-counter-test
  (testing "concurrent increments converge and none is lost.

            READ WHAT THIS DOES AND DOES NOT SHOW. The threads share one store
            instance, because that is the only shape reachable here: RocksDB takes
            an exclusive OS lock on the directory, so a second process cannot open
            it and `connect-rocksdb-store` cannot be called twice.

            konserve's `go-locked` therefore serialises these threads per key, and
            that — not the storage-level fence — is what makes them converge.
            Verified: with `compare-and-put!` replaced by two plain puts, this test
            stays green. It is a test of the whole stack behaving correctly under
            threads, not evidence that the fence works.

            Driving it the other way, with independent lock registries over one
            backing, does fail (55 of 60) — but that state cannot be produced
            through the public API, and it fails for a reason documented at the
            read cache in core.clj rather than because the fence is wrong.

            What the fence buys here is written in that comment too: the metadata
            entry and the value entry land in ONE transaction, where they used to
            be two puts that could tear."
    (let [path "/tmp/rocks-db-conc-test"
          _ (delete-rocksdb-store path :opts {:sync? true})
          store (connect-rocksdb-store path :opts {:sync? true})
          threads 4 per-thread 15
          expected (* threads per-thread)
          conflicts (atom 0)
          unexpected (atom [])]
      (try
        (k/assoc-in store [:counter] 0 {:sync? true})
        (let [fs (doall
                  (for [_ (range threads)]
                    (let [store store]
                     (future
                      (dotimes [_ per-thread]
                        (loop [tries 0]
                          (let [rev (k/revision store :counter {:sync? true})
                                r (try (k/update-in store [:counter] (fnil inc 0)
                                                    {:sync? true :expected-revision rev})
                                       ::ok
                                       (catch Exception e (or (:type (ex-data e)) ::other)))]
                            (cond
                              (= ::ok r) :done
                              (= :konserve/revision-mismatch r)
                              (do (swap! conflicts inc)
                                  (if (< tries 1000)
                                    (recur (inc tries))
                                    (swap! unexpected conj :retries-exhausted)))
                              :else (swap! unexpected conj r)))))))))]
          (doseq [f fs] @f))
        (is (empty? @unexpected) (str "unexpected failures: " (pr-str @unexpected)))
        (is (= expected (k/get-in store [:counter] nil {:sync? true}))
            "every increment must survive")
        (finally
          (release-rocksdb store)
          (delete-rocksdb-store path :opts {:sync? true}))))))
