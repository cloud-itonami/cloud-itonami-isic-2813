(ns pressureequip.registry-tsukuru-test
  "Direct unit coverage of `pressureequip.registry`'s tsukuru-
  discovery-bridge predicates (`valid-isic-code?`/
  `contains-order-fields?`/`order-contamination-keys`) -- pure, zero-
  I/O functions, no `pressureequip.tsukuru-bridge`/`kotoba.lang.
  atproto-client` involved at all, so this runs under the plain
  `clojure -M:test`."
  (:require [clojure.test :refer [deftest is testing]]
            [pressureequip.registry :as registry]))

(deftest valid-isic-code-accepts-numeric-and-section-letter-forms
  (testing "bare 2-4 digit division/class codes (this actor's own isic-2813 repo-naming convention)"
    (is (true? (registry/valid-isic-code? "28")))
    (is (true? (registry/valid-isic-code? "282")))
    (is (true? (registry/valid-isic-code? "2822"))))
  (testing "section-letter + 2-digit division (tsukuru's own lex/factory.edn example, C26)"
    (is (true? (registry/valid-isic-code? "C26")))
    (is (true? (registry/valid-isic-code? "c26")) "case-insensitive on the letter")))

(deftest valid-isic-code-rejects-malformed-input
  (is (false? (registry/valid-isic-code? nil)))
  (is (false? (registry/valid-isic-code? "")))
  (is (false? (registry/valid-isic-code? "not-an-isic-code!!")))
  (is (false? (registry/valid-isic-code? "12345")) "5+ digits is not a valid section/class code")
  (is (false? (registry/valid-isic-code? 2822)) "must be a string, never a bare number")
  (is (false? (registry/valid-isic-code? :2822)) "must be a string, never a keyword"))

(deftest contains-order-fields-detects-every-contamination-key-keyword-and-string-forms
  (doseq [k registry/order-contamination-keys]
    (testing (str "detects " (pr-str k) " at the top level")
      (is (true? (registry/contains-order-fields? {k "some-value"}))))))

(deftest contains-order-fields-detects-nested-contamination
  (testing "nested inside a map value (keyword-keyed, matching what pressureequip.tsukuru-bridge actually returns)"
    (is (true? (registry/contains-order-fields? {:candidates [{:factoryDid "did:web:x" :orderId "o-1"}]}))))
  (testing "nested inside a vector of maps"
    (is (true? (registry/contains-order-fields?
                [{:factoryDid "did:web:x"} {:factoryDid "did:web:y" :buyerDid "did:web:z"}]))))
  (testing "also detects the string-keyed form (defense-in-depth for any pre-parse/raw-JSON caller)"
    (is (true? (registry/contains-order-fields? {:candidates [{"factoryDid" "did:web:x" "orderId" "o-1"}]})))))

(deftest contains-order-fields-is-false-for-clean-discovery-shapes
  (is (false? (registry/contains-order-fields?
              {:op :discover/tsukuru-factory-candidates :subject "tsukuru-query:2822:cnc-machining"
               :isic-code "2822" :capability "cnc-machining"
               :candidates [{:factoryDid "did:web:factory-1.example.com"
                             :displayName "Example Machine Works"
                             :country "JP" :isic "2822"
                             :capabilities ["cnc-machining" "welding"]
                             :fulfillmentModes ["mto"]
                             :laborProvenance "iso-45001-audited"}]})))
  (is (false? (registry/contains-order-fields? nil)))
  (is (false? (registry/contains-order-fields? "plain-string")))
  (is (false? (registry/contains-order-fields? []))))
