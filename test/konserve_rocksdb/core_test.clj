(ns konserve-rocksdb.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [konserve.core :as k]
            [clojure.core.async :refer [<!!]]
            [konserve.compliance-test :refer [compliance-test]]
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
