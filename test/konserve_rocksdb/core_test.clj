(ns konserve-rocksdb.core-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
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
