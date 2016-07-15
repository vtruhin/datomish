;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns datomish.db
  #?(:cljs
     (:require-macros
      [datomish.pair-chan :refer [go-pair <?]]
      [cljs.core.async.macros :refer [go]]))
  (:require
   [honeysql.core :as sql]
   [datomish.util :as util #?(:cljs :refer-macros :clj :refer) [raise cond-let]]
   [datomish.sqlite :as s]
   [datomish.exec :as de]
   [datomish.query :as dq]
   [datomish.transforms :as dt]
   [datomish.sqlite-schema :as sqlite-schema]
   #?@(:clj [[datomish.pair-chan :refer [go-pair <?]]
             [clojure.core.async :as a :refer [go <! >!]]])
   #?@(:cljs [[datomish.pair-chan]
              [cljs.core.async :as a :refer [<! >!]]])))

;; TODO: split connection and DB, in preparation for a DB-as-values world.
(defprotocol IDB
  (idents
    [db]
    "Return map {ident -> entid} if known idents.  See http://docs.datomic.com/identity.html#idents.")

  (close
    [db]
    "Close this database. Returns a pair channel of [nil error]."))

(defrecord DB [sqlite-connection idents max-tx]
  IDB
  (idents [db] @(:idents db))

  (close [db] (s/close (.-sqlite-connection db))))

(defn db? [x]
  (and (satisfies? IDB x)))

(defn <q
  "Execute the provided query on the provided DB.
   Returns a transduced pair-chan of [[results] err]."
  [db find]
  {:pre [(db? db)]}
  (let [attribute-transform (fn [a] (get (idents db) a a))
        constant-transform dt/constant-transform-default
        initial-context (dq/make-context attribute-transform constant-transform)
        context (dq/find->into-context initial-context (dq/parse find))
        row-pair-transducer (dq/row-pair-transducer context (dq/sql-projection context))
        chan (a/chan 50 row-pair-transducer)]

    (s/<?all-rows (:sqlite-connection db) (dq/context->sql-string context) chan)
    ;; TODO: extract this reducing function lifted to the Maybe monad.
    (let [g (fn [f [rv re] [v e]]
              (if re
                [nil re]
                (if e
                  [nil e]
                  [(f rv v) nil])))]
      (go-pair
        (<? (a/reduce (partial g conj) [[] nil] chan))))))

;; TODO: implement support for DB parts?
(def tx0 0x2000000)

(defn <with-sqlite-connection [sqlite-connection]
  (go-pair
    (when-not (= sqlite-schema/current-version (<? (sqlite-schema/<ensure-current-version sqlite-connection)))
      (raise "Could not ensure current SQLite schema version."))
    (map->DB {:sqlite-connection sqlite-connection
              :idents            (atom {:db/txInstant 100 :x 101 :y 102}) ;; TODO: pre-populate idents and SQLite tables?
              :current-tx        (atom (dec tx0))}))) ;; TODO: get rid of dec.

;; TODO: consider CLJS interop.
(defn- tx-id? [e]
  (= e :db/current-tx))

;; TODO: write tx-meta to transaction.
(defrecord TxReport [tx-data tempids tx-meta])

;; TODO: persist max-tx and max-eid in SQLite.
(defn <allocate-tx [db]
  (go-pair
    (swap! (:current-tx db) inc)))

;; TODO: add fancy destructuring.
;; TODO: handle reading.
(deftype Datom [e a v tx added])

(defn datom? [x] (instance? Datom x))

#?(:clj
   (defmethod print-method Datom [^Datom d, ^java.io.Writer w]
     (.write w (str "#datomish/Datom "))
     (binding [*out* w]
       (pr [(.-e d) (.-a d) (.-v d) (.-tx d) (.-added d)]))))

(defn- validate-eid [eid at]
  (when-not (number? eid)
    (raise "Bad entity id " eid " at " at ", expected number"
           {:error :transact/syntax, :entity-id eid, :context at})))

(defn- validate-attr [attr at]
  (when-not (number? attr)
    (raise "Bad entity attribute " attr " at " at ", expected number"
           {:error :transact/syntax, :attribute attr, :context at})))

(defn- validate-val [v at]
  (when (nil? v)
    (raise "Cannot store nil as a value at " at
           {:error :transact/syntax, :value v, :context at})))

;; TODO: implement schemas.
(defn multival? [db attr] false)

;; TODO: implement schemas.
(defn ref? [db attr] false)

(defn <entid [db eid]
  {:pre [(db? db)]}
  (go-pair
    (cond
      (number? eid)
      eid

      (keyword? eid)
      ;; Turn ident into entid if possible.
      (get (idents db) eid eid)

      (sequential? eid)
      (raise "Lookup ref for entity id not yet supported, got " eid
             {:error :entity-id/syntax
              :entity-id eid})

      :else
      (raise "Expected number or lookup ref for entity id, got " eid
             {:error :entity-id/syntax
              :entity-id eid}))))

(defn <entid-strict [db eid]
  {:pre [(db? db)]}
  (go-pair
    (or (<? (<entid db eid))
        (raise "Nothing found for entity id " eid
               {:error :entity-id/missing
                :entity-id eid}))))

(defn <entid-some [db eid]
  {:pre [(db? db)]}
  (go-pair
    (when eid
      (<? (<entid-strict db eid)))))

;; TODO: handle _?
(defn search->sql-clause [pattern]
  (merge
    {:select [:*] ;; e :a :v :tx] ;; TODO: generalize columns.
     :from [:datoms]}
    (if-not (empty? pattern)
      {:where (cons :and (map #(vector := %1 %2) [:e :a :v :tx] pattern))} ;; TODO: use schema to v.
      {})))

(defn <search [db pattern]
  {:pre [(db? db)]}
  (go-pair
    ;; TODO: find a better expression of this pattern.
    (let [rows (<? (->>
                     (search->sql-clause pattern)
                     (sql/format)
                     (s/all-rows (:sqlite-connection db))))]
      (mapv #(Datom. (:e %) (:a %) (:v %) (:tx %) true) rows))))

(defn- <transact-report [db report datom]
  {:pre [(db? db)]}
  (go-pair
    (let [exec (partial s/execute! (:sqlite-connection db))
          [e a v tx added] [(.-e datom) (.-a datom) (.-v datom) (.-tx datom) (.-added datom)]] ;; TODO: destructuring.
      (validate-eid e [e a v tx added]) ;; TODO: track original vs. transformed?
      ;; Append to transaction log.
      (<? (exec
            ["INSERT INTO transactions VALUES (?, ?, ?, ?, ?)" e a v tx added]))
      ;; Update materialized datom view.
      (if (.-added datom)
        (<? (exec
              ;; TODO: use schema to insert correct indexing flags.
              ["INSERT INTO datoms VALUES (?, ?, ?, ?, 0, 0)" e a v tx]))
        (<? (exec
              ;; TODO: verify this is correct.
              ["DELETE FROM datoms WHERE (e = ? AND a = ? AND v = ?)" e a v])))
      (-> report
          (update-in [:tx-data] conj datom)))))

(defn- <transact-add [db report [_ e a v tx :as entity]]
  {:pre [(db? db)]}
  (go-pair
    (validate-attr a entity)
    (validate-val  v entity)
    (let [tx    (or tx (:current-tx report))
          e     (<? (<entid-strict db e))
          v     (if (ref? db a) (<? (<entid-strict db v)) v)
          datom (Datom. e a v tx true)]
      (if (multival? db a)
        ;; TODO: consider adding a UNIQUE CONSTRAINT and using INSERT OR IGNORE.
        (if (empty? (<? (<search db [e a v])))
          (<? (<transact-report db report datom))
          report)
        (if-let [^Datom old-datom (first (<? (<search db [e a])))]
          (if (= (.-v old-datom) v)
            report
            (let [ra (<? (<transact-report db report (Datom. e a (.-v old-datom) tx false)))
                  rb (<? (<transact-report db ra datom))]
              rb)) ;; TODO: express this better.
          (<? (<transact-report db report datom)))))))

(defn- <transact-retract [db report [_ e a v _ :as entity]] ;; TODO: think about retracting with tx.
  {:pre [(db? db)]}
  (go-pair
    (let [tx (:current-tx report)]
      (if-let [e (<? (<entid db e))]
        (let [v (if (ref? db a) (<? (<entid-strict db v)) v)]
          (validate-attr a entity)
          (validate-val v entity)
          (if-let [old-datom (first (<? (<search db [e a v])))]
            (<? (<transact-report db report (Datom. e a v tx false)))
            report))
        report))))
(defn- #?@(:clj  [^Boolean neg-number?]
           :cljs [^boolean neg-number?])
  [x]
  (and (number? x) (neg? x)))

(defn <transact-tx-data
  [db now initial-report initial-es]
  {:pre [(db? db)]}
  (go-pair
    (when-not (or (nil? initial-es)
                  (sequential? initial-es))
      (raise "Bad transaction data " initial-es ", expected sequential collection"
             {:error :transact/syntax, :tx-data initial-es}))
    (loop [report initial-report
           es     initial-es]
      (let [[entity & entities] es
            current-tx (:current-tx report)]
        (cond
          (nil? entity)
          ;; We're done!  Add transaction datom to the report.
          (do
            ;; TODO: don't special case :db/txInstant attribute.
            (<? (<transact-report db report (Datom. current-tx (get (idents db) :db/txInstant) now current-tx true)))
            (-> report
                (assoc-in [:tempids :db/current-tx] current-tx)))

          (map? entity)
          (raise "Map entities are not yet supported, got " entity
                 {:error :transact/syntax
                  :op    entity })

          (sequential? entity)
          (let [[op e a v] entity]
            (cond
              (keyword? a)
              (if-let [entid (get (idents db) a)]
                (recur report (cons [op e entid v] entities))
                (raise "No entid found for ident " a
                       {:error :transact/syntax
                        :op entity}))

              (= op :db.fn/call)
              (raise "DataScript's transactor functions are not yet supported, got " entity
                     {:error :transact/syntax
                      :op    entity })

              (= op :db.fn/cas)
              (raise "Datomic's compare-and-swap is not yet supported, got " entity
                     {:error :transact/syntax
                      :op    entity })

              (tx-id? e)
              (recur report (cons [op current-tx a v] entities))

              (and (ref? db a) (tx-id? v))
              (recur report (cons [op e a current-tx] entities))

              (neg-number? e)
              (if (not= op :db/add)
                (raise "Negative entity ids are resolved for :db/add only"
                       {:error :transact/syntax
                        :op    entity })
                (raise "Negative entity ids are not yet supported, got " entity
                       {:error :transact/syntax
                        :op    entity }))

              (and (ref? db a) (neg-number? v))
              (raise "Negative entity ids are not yet supported, got " entity
                     {:error :transact/syntax
                      :op    entity })

              (= op :db/add)
              (recur (<? (<transact-add db report entity)) entities)

              (= op :db/retract)
              (recur (<? (<transact-retract db report entity)) entities)

              (= op :db.fn/retractAttribute)
              (raise "DataScript's :db.fn/retractAttribute shortcut is not yet supported, got " entity
                     {:error :transact/syntax
                      :op    entity })

              (= op :db.fn/retractEntity)
              (raise "Datomic's :db.fn/retractEntity shortcut is not yet supported, got " entity
                     {:error :transact/syntax
                      :op    entity })

              :else
              (raise "Unknown operation at " entity ", expected :db/add, :db/retract, :db.fn/call, :db.fn/retractAttribute or :db.fn/retractEntity"
                     {:error :transact/syntax, :operation op, :tx-data entity})))

          (datom? entity)
          (raise "Datom entities are not yet supported, got " entity
                 {:error :transact/syntax
                  :op    entity })

          :else
          (raise "Bad entity type at " entity ", expected map or vector"
                 {:error :transact/syntax, :tx-data entity})
          )))))

(defn <transact!
  ([db tx-data]
   (<transact! db tx-data nil 0xdeadbeef)) ;; TODO: timestamp!
  ([db tx-data tx-meta now]
   {:pre [(db? db)]}
   (go-pair
     (let [current-tx (<? (<allocate-tx db))]
       (<? (<transact-tx-data db now
                              (map->TxReport
                                {:current-tx current-tx
                                 :tx-data    []
                                 :tempids    {}
                                 :tx-meta    tx-meta}) tx-data))))))
