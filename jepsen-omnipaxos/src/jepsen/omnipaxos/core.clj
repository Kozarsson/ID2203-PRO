(ns jepsen.omnipaxos.core
  "Jepsen test suite for OmniPaxos KV store.

  Run with:
    lein run -- --nodes n1,n2,n3 --time-limit 60
      --server-bin /path/to/server --shim-bin /path/to/api-shim"
  (:require [clojure.tools.logging :refer [info]]
            [jepsen [checker :as checker]
                    [cli :as cli]
                    [generator :as gen]
                    [history :as h]
                    [tests :as tests]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.omnipaxos.client :refer [omnipaxos-client]]
            [jepsen.omnipaxos.db :refer [omnipaxos-db]]
            [jepsen.omnipaxos.nemesis :refer [omnipaxos-nemesis nemesis-generator
                                              majority-down-nemesis-composed
                                              majority-down-generator
                                              total-isolation-nemesis
                                              total-isolation-generator]]
            [knossos.model :as model]))

; Use a small fixed key space so reads and writes contend on the same keys,
; giving Knossos enough overlap to detect linearizability violations.
(def key-count 1)

(defn w [_ _]
  {:type :invoke, :f :write, :key (str "k" (rand-int key-count)), :value (rand-int 100)})

(defn r [_ _]
  {:type :invoke, :f :read, :key (str "k" (rand-int key-count))})

(defn omnipaxos-test
  [opts]
  (merge tests/noop-test
         {:name      "omnipaxos-kv"
          :nodes     (:nodes opts)
          :db        (omnipaxos-db (:server-bin opts) (:shim-bin opts))
          :client    (omnipaxos-client)
          :nemesis   (omnipaxos-nemesis)
          :checker   (checker/compose
                       {:linearizable (checker/linearizable
                                        {:model (model/register)})
                        :timeline     (timeline/html)})
          :generator (gen/phases
                       (->> (gen/mix [r w])
                            (gen/stagger 1/10)
                            (gen/clients)
                            (gen/nemesis (nemesis-generator))
                            (gen/time-limit (:time-limit opts 60)))
                       ; Stop any lingering faults before final check
                       (gen/nemesis {:type :info :f :stop-partition})
                       (gen/log "Test complete, waiting before final check")
                       (gen/sleep 5))}))

(defn no-ok-during-majority-down-checker
  "Verifies that no write operation returned :ok while the majority was down.
   Finds the time window between :majority-down and :majority-up nemesis ops,
   then checks that no client write invoke/ok pair falls entirely within it."
  []
  (reify checker/Checker
    (check [_ test history opts]
      (let [; Find the start and end of the majority-down window
            down-time  (->> history
                            (h/filter-f :majority-down)
                            (filter #(= :info (:type %)))
                            first
                            :time)
            up-time    (->> history
                            (h/filter-f :majority-up)
                            (filter #(= :info (:type %)))
                            first
                            :time)
            ; Find write completions that were :ok inside that window
            violations (when (and down-time up-time)
                         (->> history
                              (h/filter-f :write)
                              (filter #(= :ok (:type %)))
                              (filter #(and (< down-time (:time %))
                                            (< (:time %) up-time)))
                              seq))]
        (if violations
          {:valid?      false
           :error       "Writes acknowledged :ok while majority was down — safety violation"
           :violations  violations}
          {:valid? true})))))

(defn majority-down-test
  "Tests that OmniPaxos refuses to commit when only a minority of nodes is up.
   Kills all but one node for 30 s, fires writes at the surviving node, then
   recovers and checks linearizability across the whole history."
  [opts]
  (merge tests/noop-test
         {:name      "omnipaxos-majority-down"
          :nodes     (:nodes opts)
          :db        (omnipaxos-db (:server-bin opts) (:shim-bin opts))
          :client    (omnipaxos-client)
          :nemesis   (majority-down-nemesis-composed)
          :checker   (checker/compose
                       {:no-ok-during-outage (no-ok-during-majority-down-checker)
                        :linearizable        (checker/linearizable
                                               {:model (model/register)})
                        :timeline            (timeline/html)})
          :generator (gen/phases
                       ; Establish some initial state before the outage
                       (->> (gen/mix [r w])
                            (gen/stagger 1/10)
                            (gen/clients)
                            (gen/time-limit 10))
                       ; Bring majority down and keep firing writes
                       (gen/nemesis (majority-down-generator))
                       (->> (gen/mix [r w])
                            (gen/stagger 1/10)
                            (gen/clients)
                            (gen/time-limit 40))
                       ; Recover and run for 3 minutes to confirm the cluster
                       ; resumed normal operation
                       (gen/nemesis {:type :info :f :majority-up})
                       (gen/log "Majority recovered, running 3-minute verification")
                       (gen/sleep 10)
                       (->> (gen/mix [r w])
                            (gen/stagger 1/10)
                            (gen/clients)
                            (gen/time-limit 180)))}))

(defn total-isolation-test
  "Tests that OmniPaxos correctly pauses and then resumes after all three nodes
   are isolated from each other via iptables for 30s. Since connections are not
   killed (only packets dropped), the cluster should recover and resume normal
   operation once the partition heals. Checks linearizability across the full
   history."
  [opts]
  (merge tests/noop-test
         {:name      "omnipaxos-total-isolation"
          :nodes     (:nodes opts)
          :db        (omnipaxos-db (:server-bin opts) (:shim-bin opts))
          :client    (omnipaxos-client)
          :nemesis   (total-isolation-nemesis)
          :checker   (checker/compose
                       {:linearizable (checker/linearizable
                                        {:model (model/register)})
                        :timeline     (timeline/html)})
          :generator (gen/phases
                       ; Establish some initial state before the isolation
                       (->> (gen/mix [r w])
                            (gen/stagger 1/10)
                            (gen/clients)
                            (gen/time-limit 10))
                       ; Isolate all nodes while firing ops (expect timeouts)
                       (gen/nemesis (total-isolation-generator))
                       (->> (gen/mix [r w])
                            (gen/stagger 1/10)
                            (gen/clients)
                            (gen/time-limit 40))
                       ; Ensure partition is healed before final check
                       (gen/nemesis {:type :info :f :heal-all})
                       (gen/log "Partition healed, running 60s verification")
                       (gen/sleep 5)
                       (->> (gen/mix [r w])
                            (gen/stagger 1/10)
                            (gen/clients)
                            (gen/time-limit 60)))}))

(def cli-opts
  "Additional CLI options beyond Jepsen defaults."
  [[nil "--key-count NUM" "Number of keys to operate on"
    :default  3
    :parse-fn parse-long
    :validate [pos? "Must be positive"]]
   [nil "--server-bin PATH" "Local path to the pre-built OmniPaxos server binary"
    :default "server"]
   [nil "--shim-bin PATH" "Local path to the pre-built api-shim binary"
    :default "api-shim"]])

(defn -main
  [& args]
  (let [[subcmd & rest-args] args]
    (condp = subcmd
      "majority-down"
      (cli/run! (cli/single-test-cmd {:test-fn  majority-down-test
                                      :opt-spec cli-opts})
                (cons "test" rest-args))
      "total-isolation"
      (cli/run! (cli/single-test-cmd {:test-fn  total-isolation-test
                                      :opt-spec cli-opts})
                (cons "test" rest-args))
      (cli/run! (cli/single-test-cmd {:test-fn  omnipaxos-test
                                      :opt-spec cli-opts})
                args))))
