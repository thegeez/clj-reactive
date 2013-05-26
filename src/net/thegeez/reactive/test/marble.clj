(ns net.thegeez.reactive.test.marble
  (:require [net.thegeez.reactive.core :as o]
            [clojure.walk :as walk]
            [clojure.set :as set]
            [clojure.test :as test]
            [clojure.string :as string]
            [clojure.tools.logging :as log])
  (:import [net.thegeez.reactive.core OnNextNotification OnErrorNotification OnCompletedNotification]
           [java.util.concurrent LinkedBlockingQueue]))

(defn print-observer [name]
  (reify o/IObserver
         (on-next [this value]
                 (log/info name "onNext" value))
         (on-error [this error]
                  (log/info name "onError" error))
         (on-completed [this]
                      (log/info name "onCompleted"))))

(defn eval-rx [& exprs]
  (eval `(do
           (load "net/thegeez/reactive/core")
           ~@exprs)))


(defn hook-up-streams
  "hat tip to contextual-eval from joy of clojure"
  [ins expr]
  (let [ins-ks (zipmap (map first ins)
                       (map (comp symbol name first) ins))
        expr (clojure.walk/postwalk-replace ins-ks expr)]
     (eval-rx
     `(let [~@(let [[ss streams] (reduce (fn [[ss streams] [id s]]
                                           [(conj ss s `(o/subject))
                                            (assoc streams id s)])
                                         [[] {}]
                                         ins-ks) ]
                (into ss ['all streams]))]
        [~'all ~expr]))))

(defmacro myor [l r]
  `(let [ll# ~l]
     (if ll#
       ll#
       ~r)))

(defmacro marble [spec]
  (let [decode {'- :-, 'X :X, '| :|}
        encode (set/map-invert decode)
        spec (mapcat (fn [[id ticks]]
                       [id (if (= id :expr)
                             ticks
                             (walk/postwalk-replace decode ticks))])
                     (partition 2 spec))
        ;; in case of no input streams
        spec (cond->> spec
                      (= (count spec) 4)
                      (into [:dummy-in []]))

        [ins [[_ expr] [_ expected]]] (->> spec
                                           (partition 2)
                                           (partition 3 1)
                                           ((juxt (partial map first)
                                                  (comp rest last))))
        
        ;; pad ins to be as long as out
        expected-count (count expected)
        
        ins (vec (for [[id ticks] ins]
                   [id (vec (take expected-count (concat ticks (repeat :-))))]))

        ins-ks (zipmap (map first ins)
                       (map (comp gensym name first) ins))

        expr (clojure.walk/postwalk-replace ins-ks expr)
        _ (prn "expr is: " expr)
        all (gensym "all")]
    `(let [out-buf# (LinkedBlockingQueue.)
           ~@(let [[ss streams] (reduce (fn [[ss streams] [id s]]
                                          [(conj ss s `(o/subject))
                                            (assoc streams id s)])
                                        [[] {}]
                                         ins-ks) ]
               (into ss [all streams]))
           _# (prn "befoooooooooooooooooooooooooooooooooooooo")
           _# (prn '~expr)
           expr# ~expr
           _# (prn "aaffffffffffffftaaaaaaaaaaaa")
           ]
       (o/subscribe (o/materialize expr#)
                    (fn [item#] (.put out-buf# item#)))
       (o/subscribe expr# (print-observer :out))
       (doseq [[id# s#] ~all]
         (o/subscribe s# (print-observer id#)))
       (let [ticks# (apply map list (map (fn [[id# ticks#]]
                                           (map list (repeat id#) ticks#))
                                         ~ins))
             actual# (reduce
                      (fn [out# ticks#]
                        (doseq [[id# tick#] ticks#
                                :let [stream# (~all id#)]]
                          (condp = tick#
                              :| (o/on-completed stream#)
                              :X (o/on-error stream# (ex-info "MarbleException" {:id id#}))
                              :- nil  ;; noop
                              (o/on-next stream# tick#)))
                        (conj out# (if (.peek out-buf#)
                                    (let [notification# (.take out-buf#)]
                                      (log/info "Notification from buf: " notification#
                                                (when (instance? OnErrorNotification notification#)
                                                  (.error notification#)))
                                      (condp instance? notification#
                                        OnNextNotification (.value notification#)
                                        OnErrorNotification (do
                                                              (prn "EXCEPTION"
                                                                   (.error notification#))
                                                              :X)
                                        OnCompletedNotification :|))
                                    :-)))
                      []
                      ticks#)
             res# {:expected ~expected
                   :actual actual#}]
         (zipmap (keys res#)
                 (walk/postwalk-replace '~encode (vals res#))))
       
       )
))

(defn marble* [spec]
  (let [;; in case of no input streams
        spec (cond->> spec
                      (= (count spec) 4)
                      (into [:dummy-in []]))

        [ins [[_ expr] [_ expected]]] (->> spec
                                           (partition 2)
                                           (partition 3 1)
                                           ((juxt (partial map first)
                                                  (comp rest last))))
        
        ;; pad ins to be as long as out
        expected-count (count expected)
        
        ins (for [[id ticks] ins]
              [id (take expected-count (concat ticks (repeat :-)))])
        
        expected (eval-rx expected)
                
        [streams out] (hook-up-streams ins expr)

        out-buf (LinkedBlockingQueue.)]
    (o/subscribe (o/materialize out)
                #(.put out-buf %))
    (o/subscribe out (print-observer :out))
    (doseq [[id s] streams]
      (o/subscribe s (print-observer id)))

    (let [ticks (apply map list (map (fn [[id ticks]]
                                       (map list (repeat id) ticks))
                                     ins))
          actual (reduce
                  (fn [out tick]
                    (doseq [[id tick] tick
                            :let [stream (streams id)]]
                      (condp = tick
                          :| (o/on-completed stream)
                          :X (o/on-error stream (ex-info "MarbleException" {:id id}))
                          :- nil  ;; noop
                          (o/on-next stream tick)))
                    (conj out (if (.peek out-buf)
                                (let [notification (.take out-buf)]
                                  (log/info "Notification from buf: " notification)
                                  (condp (fn [l r]
                                           (prn "left" l "right" r)
                                           (.contains r l)) (str notification)
                                      "OnNextNotification" (.value notification)
                                      "OnErrorNotification" :X
                                      "OnCompletedNotification" :|))
                                :-)))
                  []
                  ticks)]
      {:expected expected
       :actual actual})))

(defmacro marble-old [spec]
  (let [decode {'- :-, 'X :X, '| :|}
        encode (set/map-invert decode)
        spec (mapcat (fn [[id ticks]]
                       [id (if (= id :expr)
                             ticks
                             (walk/postwalk-replace decode ticks))])
                     (partition 2 spec))]
    `(let [res# (marble* '~spec)]
       (zipmap (keys res#)
               (walk/postwalk-replace '~encode (vals res#))))))


(defmethod test/assert-expr 'marble [msg form]
           (let [newline (with-out-str (newline))
                 spec-str (str "["
                               (apply str (->> (second form)
                                               (partition 2)
                                               (map (partial string/join " "))
                                               (interpose (str newline " "))))
                               "]")]
             `(let [spec# (second '~form)
                    {expected# :expected
                     actual# :actual} ~form]
                (test/do-report {:type (if (= expected# actual#) :pass :fail),
                                 :message (str "Marble diagram test" ~newline ~spec-str),
                                 :expected expected#, :actual actual#})
                actual#)))

