(ns net.thegeez.reactive.core
  (:refer-clojure :exclude [map empty concat filter distinct take take-while])
  (:require [clojure.core :as clj])
  (:import [java.util.concurrent LinkedBlockingQueue]
           [java.util.concurrent.atomic AtomicReference AtomicBoolean AtomicLong]
           [java.util.concurrent.locks ReentrantLock]))

(set! *warn-on-reflection* true)

(defprotocol IObservable
  (subscribe* [this observer]))

(defprotocol IObserver
  (on-next [this value])
  (on-error [this error])
  (on-completed [this]))

(defprotocol ISubscription
  (unsubscribe [this]))

(defn empty-subscription []
  (reify ISubscription
         (unsubscribe [this])))

(deftype WrapSubscription [^AtomicReference wrappee]
  ISubscription
  (unsubscribe [this]
               (prn "Wrap subscription unsubscribe" (.get wrappee))
               (when-let [action (.getAndSet wrappee nil)]
                 (unsubscribe action))))

(defn setable-subscription []
  (WrapSubscription. (AtomicReference. nil)))

(defn set-subscription! [subscription-wrap subscription]
  (.compareAndSet ^AtomicReference (.wrappee ^WrapSubscription subscription-wrap) nil subscription))

(defn create-subscription [on-unsubscribe]
  (WrapSubscription. (AtomicReference. (reify ISubscription
                                              (unsubscribe [this]
                                                           (on-unsubscribe))))))

(defmacro with-lock [lock & body]
  `(try
     (.lock ~lock)
     ~@body
     (finally
      (.unlock ~lock))))

;; behaving observer, will pass calls to the wrapped observer
;; following the Rx contract on-next*(on-error|on-completed), with all
;; the calls synchronized, blocking on concurrent attempts
(deftype BehavingWrapObserver [^WrapSubscription subscription wrappee ^ReentrantLock lock ^AtomicBoolean done]
  IObserver
  (on-next [this value]
           (with-lock lock
             (when-not (.get done)
               (on-next wrappee value))))
  (on-error [this error]
            (with-lock lock
             (when (.compareAndSet done false true)
               (on-error wrappee error)
               (unsubscribe subscription))))
  (on-completed [this]
            (with-lock lock
             (when (.compareAndSet done false true)
               (on-completed wrappee)
               (prn "clean up subscription")
               (prn (.get ^AtomicReference (.wrappee subscription)))
               (unsubscribe subscription)))))

(defn behaving-wrap-observer [subscription wrappee]
  (BehavingWrapObserver. subscription wrappee (ReentrantLock.) (AtomicBoolean. false)))

(defn subscribe
  ([observable fn-or-observer]
     (if (satisfies? IObserver fn-or-observer)
       (subscribe* observable fn-or-observer)
       (subscribe observable fn-or-observer (fn [error] (throw error)) (constantly nil))))
  ([observable on-next-fn on-error-fn]
     (subscribe observable on-next-fn on-error-fn (constantly nil)))
  ([observable on-next-fn on-error-fn on-completed-fn]
     (subscribe* observable
                 (reify IObserver
                        (on-next [this v]
                                 (on-next-fn v))
                        (on-error [this error]
                                  (on-error-fn error))
                        (on-completed [this]
                                      (on-completed-fn))))))

(defn create [on-subscribe]
  (let [subscription (setable-subscription)]
    (reify IObservable
           (subscribe* [this observer]
                       (try
                         (set-subscription! subscription
                                            (on-subscribe (behaving-wrap-observer
                                                           subscription
                                                           observer)))
                         (catch Exception e
                           (on-error observer e)))
                       subscription))))

(defn create* [on-subscribe]
  (reify IObservable
         (subscribe* [this observer]
                     (try
                       (on-subscribe observer)
                       (catch Exception e
                         (on-error observer e)
                         (empty-subscription))))))

(deftype OnNextNotification [value])
(deftype OnErrorNotification [error])
(deftype OnCompletedNotification [])

(defn materialize [observable]
  (create* (fn [observer]
             (prn "sub materialize")
             (subscribe observable
                        (reify IObserver
                               (on-next [this value]
                                        (on-next observer (OnNextNotification. value)))
                               (on-error [this error]
                                         (on-next observer (OnErrorNotification. error)))
                               (on-completed [this]
                                             (on-next observer (OnCompletedNotification.))))))))

(defn observable-seq
  "Like lazy-seq"
  [observable]
  (let [queue (LinkedBlockingQueue.)]
    (prn "seq sub materialize")
    (subscribe (materialize observable)
               (reify IObserver
                      (on-next [this value]
                               (prn "on-next into queue" value)
                               (.put queue value))
                      (on-error [this error])
                      (on-completed [this])))
    (prn "ret lazy seq")
    (lazy-seq ((fn loop []
                 (let [notification (.take queue)]
                   (prn "Notification: " notification (instance? OnNextNotification notification))
                   (condp instance? notification
                     OnNextNotification
                     (cons (.value ^OnNextNotification notification)
                           (loop))
                     OnErrorNotification
                     (throw (.error ^OnErrorNotification notification))
                     OnCompletedNotification
                     nil)))))))

(defn subject []
  (let [observers (atom #{})
        _ (add-watch observers :subject (fn [_ _ _ new]
                                          (prn "Observers for subject now:")
                                          (prn new)))
        subscription (setable-subscription)
        on-subscribe (fn [observer]
                       (set-subscription! subscription
                                          (create-subscription (fn []
                                                                 (swap! observers disj observer))))
                       (swap! observers conj observer)
                       subscription)
        behaving-observer (behaving-wrap-observer
                           subscription
                           (reify IObserver
                                  (on-next [this value]
                                           (prn "on next subject" value (.hashCode this))
                                           (doseq [observer @observers]
                                             (on-next observer value)))
                                  (on-error [this error]
                                            (doseq [observer @observers]
                                              (on-error observer error)))
                                  (on-completed [this]
                                                (doseq [observer @observers]
                                                  (on-completed observer)))))]
    (reify
     IObservable
     (subscribe* [this observer]
                ;; (prn "on-subscribe through subject")
                (on-subscribe observer))
      IObserver
      (on-next [this value]
               (on-next behaving-observer value))
      (on-error [this error]
                (on-error behaving-observer error))
      (on-completed [this]
                    (on-completed behaving-observer)))))


(defn obv
  "Like seq, but for IObservable"
  [o]
  (if (satisfies? IObservable o)
    o
    (create* (fn [observer]
             (doseq [v o]
               (on-next observer v))
             (on-completed observer)
             (empty-subscription)))))

(defn cons
  "Like cons, but for IObservable"
  [o obs]
  (let [obs (obv obs)]
    (create* (fn [observer]
               (on-next observer o)
               (subscribe obs
                          observer)))))

(defn just [v]
  (obv [v]))

(defn empty []
  (obv []))

(defn never []
  (create* (fn [observer]
             (empty-subscription))))

(defn throw-observable [error]
  (create* (fn [observer]
             (on-error observer error)
             (empty-subscription))))

(comment
  "needs schedulers"
  (defn generate
   ([seed iterate]
      (generate seed iterate (constantly false) identity))
   ([seed iterate finish]
      (generate seed iterate finish identity))
   ([seed iterate finish result]
      (create* (fn [observer]
                 (let [running (atom true)]
                   (loop [state seed]
                     (if (finish state)
                       (on-completed observer)
                       (do
                         (on-next observer (result state))
                         (recur (iterate state)))))
                   (empty-subscription)))))))

(defn concat
  ([obs-obs]
     (create* (fn [observer]
                (let [obs-obs (obv obs-obs)
                      lock (ReentrantLock.)
                      stopped (AtomicBoolean. false)
                      has-child (AtomicBoolean. false)
                      children (atom clojure.lang.PersistentQueue/EMPTY)
                      child-sub (setable-subscription)
                      maybe-cycle (fn maybe-cycle []
                                    (when-not (.get has-child)
                                      (when-let [next-sub (peek @children)]
                                        (if (= next-sub ::end)
                                          (on-completed observer)
                                          (when (.compareAndSet has-child false true)
                                            (set-subscription! child-sub (subscribe next-sub
                                                                                         (fn [value]
                                                                                           (on-next observer value))
                                                                                         (fn [error]
                                                                                           (on-error observer error))
                                                                                         (fn []
                                                                                           (prn "child done")
                                                                                           (with-lock lock
                                                                                             (prn "clean up child")
                                                                                             (unsubscribe child-sub)
                                                                                             (swap! children pop)
                                                                                             (.set has-child false)
                                                                                             (maybe-cycle))))))))))
                      parent-sub (subscribe obs-obs
                                            (fn [child]
                                              (prn "parent add")
                                              (with-lock lock
                                                (when-not (.get stopped)
                                                  (prn "parent add in")
                                                  (swap! children conj (obv child))
                                                  (maybe-cycle))))
                                            (fn [error]
                                              (on-error observer error))
                                            (fn []
                                              (with-lock lock
                                                (when-not (.get stopped)
                                                  (swap! children conj ::end)
                                                  (maybe-cycle)))))]
                  (create-subscription (fn []
                                         (with-lock lock
                                           (when (.compareAndSet stopped false true)
                                             (unsubscribe parent-sub)
                                             (unsubscribe child-sub)))))))))
  ([o obs]
     (concat (cons o obs))))

(defn interval-temp [secs]
  ;; same as Observable.Interval(TimeSpan.FromSeconds(1))
  (create* (fn [observer]
             (let [index (AtomicLong. 0)
                   t (Thread. (fn []
                                (try
                                  (while (not (.isInterrupted (.currentThread Thread)))
                                    (Thread/sleep (* secs 1000))
                                    (on-next observer (.getAndIncrement index)))
                                  (catch Exception e
                                    nil))))]
               (.start t)
               (create-subscription (fn []
                                      (.interrupt t)
                                      (on-completed observer)))))))

(defn interval-temp [secs]
  (create* (fn [observer]
             (let [index (AtomicLong. 0)
                   t (Thread. (fn []
                                (try
                                  (while true
                                    (Thread/sleep (* secs 1000))
                                    (on-next observer (.getAndIncrement index)))
                                  (catch Exception e
                                    nil))))]
               (.start t)
               (create-subscription (fn []
                                      (.interrupt t)))))))

(defn weave
  "Same as observable/merge"
  ([obs-obs]
     (let [obs-obs (obv obs-obs)]
       (create* (fn [observer]
                  (let [s (subject)
                        out-subscription (subscribe s observer)
                        stopped (AtomicBoolean. false)
                        lock (ReentrantLock.)
                        parent-sub (setable-subscription)
                        subscriptions (atom #{parent-sub})
                        maybe-cycle (fn []
                                      (when (empty? @subscriptions)
                                        (on-completed observer)))]
                    (with-lock lock
                      (set-subscription! parent-sub
                                        (subscribe obs-obs
                                                   (fn [obs]
                                                     (prn "on-next parent weave")
                                                     (with-lock lock
                                                       (when-not (.get stopped)
                                                         (prn "new weave obs" obs)
                                                         (let [child-sub (setable-subscription)]
                                                           (swap! subscriptions conj child-sub)
                                                           (set-subscription! child-sub
                                                                              (subscribe obs
                                                                                         (fn [value]
                                                                                           (prn "weave child forward" value)
                                                                                           (on-next s value))
                                                                                         (fn [error]
                                                                                           (on-error s error))
                                                                                         (fn []
                                                                                           (prn "weave child complete")
                                                                                           (with-lock lock
                                                                                             (when-not (.get stopped)
                                                                                               (prn "weave child complete run")
                                                                                               (swap! subscriptions disj child-sub)
                                                                                               (maybe-cycle)))))))
                                                         (maybe-cycle))))
                                                   (fn [error]
                                                     (on-error s error))
                                                   (fn []
                                                     ;; do nothing, the
                                                     ;; observers produced
                                                     ;; can still produce items
                                                     (prn "obs-obs complete")
                                                     (with-lock lock
                                                       (when-not (.get stopped)
                                                         (prn "obs-obs complete run")
                                                         (swap! subscriptions disj parent-sub)
                                                         (maybe-cycle)))))))
                    (create-subscription (fn []
                                           (prn "unsub weave")
                                           (with-lock lock
                                             (when (.compareAndSet stopped false true)
                                               (prn "doing unsub weave")
                                               (unsubscribe out-subscription)
                                               (doseq [cs @subscriptions]
                                                 (unsubscribe cs)))))))))))
  ([o obs]
     (weave (cons o obs))))

(defn map [o f]
  (create* (fn [observer]
             (let [subscription (setable-subscription)]
               (set-subscription!
                subscription
                (subscribe o
                           (fn [value]
                             (try
                               (let [mapped-value (f value)]
                                 (on-next observer mapped-value))
                               (catch Exception e
                                 (unsubscribe subscription)
                                 (on-error observer e))))
                           (partial on-error observer)
                           (partial on-completed observer)))
               subscription))))

(defn do-obv
  "like observable/do"
  [o f]
  (map o (fn [i] (f i) i)))

(defn map-many
  ([obs-obs f]
     (weave (map obs-obs f)))
  ([o obs-obs f]
     (map-many (cons o obs-obs) f)))

(defn filter [obs pred]
  (map-many obs (fn [item]
                  (if (pred item)
                    (just item)
                    (empty)))))

(defn distinct [obs]
  (create* (fn [observer]
             (let [seen (atom #{})]
               (subscribe obs
                          (fn [v]
                            (when-not (@seen v)
                              (swap! seen conj v)
                              (on-next observer v)))
                          (partial on-error observer)
                          (partial on-completed observer))))))

(defn distinct-until-changed [obs]
  (create* (fn [observer]
             (let [seen (atom ::none)]
               (subscribe obs
                          (fn [v]
                            (when-not (= @seen v)
                              (reset! seen v)
                              (on-next observer v)))
                          (partial on-error observer)
                          (partial on-completed observer))))))

(defn take-while-index [obs pred]
  (create* (fn [observer]
             (let [counter (AtomicLong. 0)
                   subscription (setable-subscription)]
               (set-subscription!
                subscription
                (subscribe obs
                           (fn [v]
                             (prn "twi on-next" v)
                             (try
                               (if (pred v (.getAndIncrement counter))
                                 (on-next observer v)
                                 (do
                                   (prn "unsubscribe" subscription)
                                   (unsubscribe subscription)
                                   (on-completed observer)))
                               (catch Exception e
                                 (prn "exception????" e)
                                 (unsubscribe subscription)
                                 (on-error observer e))))
                           (partial on-error observer)
                           (partial on-completed observer)))
               subscription))))

(defn take-while [obs pred]
  (take-while-index obs (fn [v _] (pred v))))

(defn take [obs n]
  (take-while-index obs (fn [v idx] (< idx n))))


(defn skip-while-index [obs pred]
  (create* (fn [observer]
             (let [counter (AtomicLong. 0)
                   subscription (setable-subscription)]
               (set-subscription!
                subscription
                (subscribe obs
                           (fn [v]
                             (prn "twi on-next" v)
                             (try
                               (when-not (pred v (.getAndIncrement counter))
                                 (on-next observer v))
                               (catch Exception e
                                 (prn "exception????" e)
                                 (unsubscribe subscription)
                                 (on-error observer e))))
                           (partial on-error observer)
                           (partial on-completed observer)))
               subscription))))

(defn skip-while [obs pred]
  (skip-while-index obs (fn [v _] (pred v))))

(defn skip [obs n]
  (skip-while-index obs (fn [v idx] (< idx n))))

(defn zip
  "Like observable/zip. Note that zip completes when all the source observables are completed, see http://social.msdn.microsoft.com/forums/en-us/rx/thread/4CF07C50-9633-449A-9AC0-71BC55720A4E"
  ([obs-obs]
     (let [;; nr of sources is fixed upon creation
           obs-obs (observable-seq (obv obs-obs))]
       (create* (fn [observer]
                  (let [stopped (AtomicBoolean. false)
                        lock (ReentrantLock.)
                        children (atom {})]
                    (doseq [obs obs-obs]
                      (let [child-subscription (setable-subscription)]
                        (swap! children assoc obs {:buffer clojure.lang.PersistentQueue/EMPTY
                                                   :subscription child-subscription
                                                   :completed false})
                        (set-subscription!
                         child-subscription
                         (subscribe obs
                                    (fn [value]
                                      (with-lock lock
                                        (when-not (.get stopped)
                                          (swap! children update-in [obs :buffer] conj value)
                                          (let [buffers (clj/map :buffer (vals @children))]
                                            (when (every? peek buffers)
                                              (let [;; make out have to same
                                                    ;; order as the sources
                                                    out (mapv (comp peek :buffer @children) obs-obs)]
                                                (on-next observer out)
                                                (swap! children (fn [obs+buffers-sub]
                                                                  (zipmap (keys obs+buffers-sub)
                                                                          (clj/map
                                                                           (fn [buffers-sub]
                                                                             (update-in buffers-sub [:buffer] pop))
                                                                           (vals obs+buffers-sub)))))))))))
                                    (fn [error]
                                      (with-lock lock
                                        (when (.compareAndSet stopped false true)
                                          (on-error observer error)
                                          (doseq [child (clj/map :subscription (vals @children))]
                                            (unsubscribe child))
                                          (reset! children {}))))
                                    (fn []
                                      (with-lock lock
                                        (when-not (.get stopped)
                                          (swap! children assoc-in [obs :completed] true)
                                          (when (every? :completed (vals @children))
                                            (when (.compareAndSet stopped false true)
                                              (on-completed observer)
                                              (doseq [child (clj/map :subscription (vals @children))]
                                                (unsubscribe child))
                                              (reset! children {}))))))))))
                    (create-subscription (fn []
                                           (with-lock lock
                                             (when (.compareAndSet stopped false true)
                                               (doseq [child (clj/map :subscription (vals @children))]
                                                 (unsubscribe child))
                                               (reset! children {}))))))))))
  ([o obs]
     (zip (cons o obs))))

