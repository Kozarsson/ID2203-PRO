(ns jepsen.omnipaxos.nemesis
  "Nemesis for OmniPaxos: network partitions and node crashes."
  (:require [clojure.tools.logging :refer [info]]
            [jepsen [control :as c]
                    [generator :as gen]
                    [nemesis :as nemesis]]
            [jepsen.control.util :as cu]
            [jepsen.omnipaxos.db :as db]))

(defn crash-nemesis
  "Kills server + api-shim on a random node, then restarts them on recover.
   Tracks the crashed node in an atom so recover knows where to go."
  []
  (let [crashed (atom nil)]
    (reify nemesis/Nemesis
      (setup! [this test] this)

      (invoke! [this test op]
        (case (:f op)
          :crash-node
          (let [node (rand-nth (:nodes test))]
            (info "nemesis crashing node" node)
            (c/on-nodes test [node]
              (fn [_ _]
                (cu/stop-daemon! db/shim-bin   db/shim-pid)
                (cu/stop-daemon! db/server-bin db/server-pid)))
            (reset! crashed node)
            (assoc op :type :info :value node))

          :recover-node
          (if-let [node @crashed]
            (do
              (info "nemesis recovering node" node)
              (c/on-nodes test [node]
                (fn [_ _]
                  (db/start-server!)
                  (Thread/sleep 3000)
                  (db/start-shim!)
                  (db/await-shim! node)))
              (reset! crashed nil)
              (assoc op :type :info :value node))
            (assoc op :type :info :value :nothing-to-recover))))

      (teardown! [this test]))))

(defn majority-down-nemesis
  "Kills a majority of nodes (all but one), then restarts them on recover.
   With quorum size 2 out of 3, the surviving minority node cannot commit
   new values — any :ok responses would be a safety violation."
  []
  (let [downed (atom [])]
    (reify nemesis/Nemesis
      (setup! [this test] this)

      (invoke! [this test op]
        (case (:f op)
          :majority-down
          (let [; Keep only the first node alive; kill the rest
                victims (rest (:nodes test))]
            (info "nemesis downing majority" victims)
            (c/on-nodes test victims
              (fn [_ _]
                (cu/stop-daemon! db/shim-bin   db/shim-pid)
                (cu/stop-daemon! db/server-bin db/server-pid)))
            (reset! downed victims)
            (assoc op :type :info :value victims))

          :majority-up
          (let [nodes @downed]
            (info "nemesis recovering majority" nodes)
            (c/on-nodes test nodes
              (fn [_ node]
                (db/start-server!)
                (Thread/sleep 3000)
                (db/start-shim!)
                (db/await-shim! node)))
            (reset! downed [])
            (assoc op :type :info :value nodes))))

      (teardown! [this test]))))

(defn omnipaxos-nemesis
  "Combined nemesis: random-halves partition + single-node crash/restart."
  []
  (nemesis/compose
    {{:start-partition :start
      :stop-partition  :stop} (nemesis/partition-random-halves)
     #{:crash-node :recover-node} (crash-nemesis)}))

(defn majority-down-nemesis-composed
  "Nemesis for the majority-down test: only majority kill/recover."
  []
  (nemesis/compose
    {#{:majority-down :majority-up} (majority-down-nemesis)}))

(defn nemesis-generator
  "Cycles through: partition → heal → crash → recover."
  []
  (gen/cycle
    [(gen/sleep 10)
     {:type :info :f :start-partition}
     (gen/sleep 15)
     {:type :info :f :stop-partition}
     (gen/sleep 5)
     {:type :info :f :crash-node}
     (gen/sleep 10)
     {:type :info :f :recover-node}
     (gen/sleep 5)]))

(defn majority-down-generator
  "Brings majority down, holds for 30s, then recovers."
  []
  (gen/phases
    (gen/sleep 5)
    {:type :info :f :majority-down}
    (gen/sleep 30)
    {:type :info :f :majority-up}
    (gen/sleep 10)))

(defn total-isolation-nemesis
  "Blocks all inter-node traffic with iptables so no quorum is possible.
   Uses iptables (not process kills) so connections resume when healed."
  []
  (reify nemesis/Nemesis
    (setup! [this test] this)

    (invoke! [this test op]
      (case (:f op)
        :isolate-all
        (do (c/on-nodes test
              (fn [test node]
                (doseq [peer (remove #{node} (:nodes test))]
                  (c/exec :iptables :-I :INPUT  :-s peer :-j :DROP)
                  (c/exec :iptables :-I :OUTPUT :-d peer :-j :DROP))))
            (assoc op :type :info :value :isolated))

        :heal-all
        (do (c/on-nodes test
              (fn [_ _]
                (c/exec :iptables :-F :INPUT)
                (c/exec :iptables :-F :OUTPUT)))
            (assoc op :type :info :value :healed))))

    (teardown! [this test]
      (c/on-nodes test
        (fn [_ _]
          (c/exec :iptables :-F :INPUT)
          (c/exec :iptables :-F :OUTPUT))))))


(defn total-isolation-generator
  "Isolates all nodes from each other for 30s, then heals."
  []
  (gen/phases
    (gen/sleep 5)
    {:type :info :f :isolate-all}
    (gen/sleep 30)
    {:type :info :f :heal-all}
    (gen/sleep 5)))
