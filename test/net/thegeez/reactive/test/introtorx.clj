(ns net.thegeez.reactive.test.introtorx
  (:use clojure.test)
  (:require [net.thegeez.reactive.core :as o]))

(deftest begin
  (prn "test begin")
  (let [my-console-observer (reify o/IObserver
                                   (o/on-next [this value]
                                            (println "Received value: " value))
                                   (o/on-error [this error]
                                             (println "Received eror: " error))
                                   (o/on-completed [this]
                                                 (println "Received completed")))
        my-sequence-of-numbers (reify o/IObservable
                                      (o/subscribe* [this observer]
                                                 (doto observer
                                                   (o/on-next 1)
                                                   (o/on-next 2)
                                                   (o/on-next 3)
                                                   (o/on-completed))

                                                 (o/empty-subscription)))]
    (is (= (with-out-str
             (o/subscribe my-sequence-of-numbers my-console-observer))
           "Received value:  1\nReceived value:  2\nReceived value:  3\nReceived completed\n"))))

(deftest subject-test
  (prn "test subject-test")
  (let [s (o/subject)
        sseq (o/observable-seq s)]
    (prn "before on-next")
    (doto s
      (o/on-next 42)
      (o/on-completed))
    (prn "before")
    (prn "Subject seq" sseq)))

;; var values = new Subject<int>();
;; try
;; {
;; values.Subscribe(value => Console.WriteLine("1st subscription received {0}", value));
;; }
;; catch (Exception ex)
;; {
;; Console.WriteLine("Won't catch anything here!");
;; }
;; values.OnNext(0);
;; //Exception will be thrown here causing the app to fail.
;; values.OnError(new Exception("Dummy exception"));
(deftest on-error-subject
  (prn "test on-error-subject")
  (let [s (o/subject)]
    (o/subscribe s (fn [v]
                     (prn "1st subscription received " v)))
    (o/on-next s 0)
    (is (thrown-with-msg? Exception #"Dummy exception"
          (o/on-error s (Exception. "Dummy exception")))))
  (let [s (o/subject)]
    (try
      (o/subscribe s
                   (fn [v]
                     (prn "subscription received " v))
                   (fn [error]
                     (prn "subscr received error" error)))
      (catch Exception e
        ;; not reached
        ))
    (o/on-next s 0)
    (o/on-error s (Exception. "Dummy exception"))))

;; var values = new Subject<int>();
;; var firstSubscription = values.Subscribe(value =>
;; Console.WriteLine("1st subscription received {0}", value));
;; var secondSubscription = values.Subscribe(value =>
;; Console.WriteLine("2nd subscription received {0}", value));
;; values.OnNext(0);
;; values.OnNext(1);
;; values.OnNext(2);
;; values.OnNext(3);
;; firstSubscription.Dispose();
;; Console.WriteLine("Disposed of 1st subscription");
;; values.OnNext(4);
;; values.OnNext(5);

(deftest subject-unsubscribe
  (prn "test subject-unsubscribe")
  (let [s (o/subject)
        sub1 (o/subscribe s (partial prn "1st sub received"))
        sub2 (o/subscribe s (partial prn "2nd sub received"))]
    (doto s
      (o/on-next 0)
      (o/on-next 1)
      (o/on-next 2)
      (o/on-next 3))
    (o/unsubscribe sub1)
    (prn "unsubscribe sub1")
    (doto s
      (o/on-next 4)
      (o/on-next 5))))

(deftest nothing-after-on-complete
  (prn "test nothing-after-on-complete")
  (is (= "" (with-out-str
              (let [s (o/subject)]
                (o/subscribe s (partial prn "sub"))
                (o/on-completed s)
                (o/on-next s 2))))))

;; var disposable = Disposable.Create(() => Console.WriteLine("Being disposed."));
;; Console.WriteLine("Calling dispose...");
;; disposable.Dispose();
;; Console.WriteLine("Calling again...");
;; disposable.Dispose();
(deftest unsubscribe-twice
  (prn "test unsubscribe-twice")
  (is (= "Calling unsubscribe...\nBeing disposed\nCalling again\n"
         (with-out-str
           (let [sub (o/create-subscription (fn [] (println "Being disposed")))]
             (println "Calling unsubscribe...")
             (o/unsubscribe sub)
             (println "Calling again")
             (o/unsubscribe sub))))))

(deftest observable-seq-test
  (prn "test observable-seq-test")
  (is (= [1 2 3]
           (seq (o/observable-seq (o/obv [1 2 3])))
           (iterator-seq (.iterator [1 2 3]))))
  (is (= nil
           (seq (o/observable-seq (o/obv [])))
           (iterator-seq (.iterator [])))))

(deftest just-test
  (prn "test just-test")
  (let [o (o/just 4)]
    (is (= (o/observable-seq o) [4]))
    (is (= (o/observable-seq o) [4]))))

(deftest empty-test
  (prn "test empty-test")
  (let [o (o/empty)]
    (is (= (o/observable-seq o) []))
    (is (= (o/observable-seq o) []))))


(deftest subscription-cancel
  (prn "test subscription-cancel")
  (let [o (o/create* (fn [observer]
                       (let [running (atom true)]
                         (-> (Thread. (fn []
                                        (let [i (atom 0)]
                                          (while @running
                                            (o/on-next observer @i)
                                            (swap! i inc)
                                            (Thread/sleep 100)))))
                             .start)
                         (o/create-subscription (fn []
                                                  (reset! running false))))
                       ))
        seen (atom nil)
        sub (o/subscribe o (fn [v]
                             (println "seen "v)
                             (reset! seen v)))
        latch (java.util.concurrent.CountDownLatch. 1)]
    (add-watch seen :unsub (fn [_ _ _ new]
                             (when (= new 4)
                               (o/unsubscribe sub)
                               (.countDown latch))))
    (.await latch)))

(deftest range-test
  (prn "test range-test")
  (is (= (range 10)
         (o/observable-seq (o/obv (range 10))))))

;; (deftest generate-test
;;   (is (= (range 10)
;;          (o/observable-seq (o/generate 0 inc #{10} identity))))
;;   (is (= []
;;            (o/observable-seq (o/generate 0 inc (constantly true))))))

(deftest map-test
  (prn "test map-test")
  (is (= [0 2 4 6 8]
           (-> (o/obv (range 5))
               (o/map #(* % 2))
               o/observable-seq))))

(deftest knock-subject
  (prn "test knock-subject")
  (let [s (o/subject)
        sub1 (-> (-> (o/interval-temp 1)
                     (o/map (fn [i] (str "KNOCK stream ONE" i))))
                 (o/subscribe s))
        sub2 (-> (-> (o/interval-temp 2)
                     (o/map (fn [i] (str "KNOCK stream TWO" i))))
                 (o/subscribe s))
        ]
    (o/subscribe s (fn [v] (prn "final val: " v)))
    (Thread/sleep 8000)
    (o/unsubscribe sub1)
    (Thread/sleep 8000)
    (o/unsubscribe sub2)))


(deftest weave-test
  (prn "test weave-test")
  (let [o1 (-> (o/interval-temp 1)
               (o/map (fn [i] (str "weave-test stream ONE" i))))
        o2 (-> (o/interval-temp 2)
               (o/map (fn [i] (str "weave-test stream TWO" i))))
        sub (-> (o/weave [o1 o2])
                (o/subscribe 
                 (fn [x]
                   (when (= x "stream ONE0")
                     (println "slow onNext processing start")
                     (Thread/sleep (* 3 1000))
                 (println "slow onNext processing done"))
                   (println "onNext:" x))
             (fn [e] (println "onError:" e))
             (fn [] (println "onCompleted:"))))]
    (Thread/sleep 8000)
    (prn "unsubscribe weave-test")
    (o/unsubscribe sub)
    (Thread/sleep 8000)))

(deftest weave-test2
  (prn "weave-test2")
  (let [o1 (-> (o/interval-temp 1)
               (o/map (fn [i] (str "TEST2stream ONE" i))))
        o2 (-> (o/interval-temp 2)
               (o/map (fn [i] (str "TEST2stream TWO" i))))
        sub (-> (o/weave [(o/obv (range 10)) (o/obv (range 20 30))])
                (o/subscribe 
                 (fn [x]
                   (when (= x "TEST2stream ONE0")
                     (println "TEST2slow onNext processing start")
                     (Thread/sleep (* 3 1000))
                 (println "TEST2slow onNext processing done"))
                   (println "TEST2onNext" x))
             (fn [e] (println "TEST2onError" e))
             (fn [] (println "TEST2onCompleted"))))]
    (Thread/sleep 8000)
    (prn "TEST2unsubscribe weave-test")
    (o/unsubscribe sub)
    (Thread/sleep 8000)))

(deftest weave-test3
  (prn "test weave-test3")
  (let [obs (o/subject)
        s1 (o/subject)
        stwo (o/subject)
        out (o/subject)
        w (o/weave obs)
        sub (o/subscribe w out)
        outs (o/observable-seq out)]
    (prn "start")
    (o/on-next obs s1)
     (o/on-next s1 1)
     (o/on-next s1 2)

    
    (o/on-next obs stwo)
      (o/on-next stwo 20)
     (o/on-next s1 3)
     (o/on-completed s1)
      (o/on-next stwo 21)
    (o/on-completed obs)
      (o/on-next stwo 22)
      (o/on-next stwo 23)
      (o/on-completed stwo)
    
    (is (= [1 2 20 3 21 22 23]
             outs))
))

(deftest subject-test-sub
  (prn "test subject-test-sub")
  (let [s (o/subject)
        sub (o/subscribe s
                       (fn [value] (prn "on-next" value))
                       (fn [error] (prn "on-error" error))
                       (fn [] (prn "on-completed")))]
    (o/unsubscribe sub)))

(deftest merge-sync
  (prn "test merge-sync")
  (println "Starting example")
  (let [o1 (-> (o/interval-temp 1)
               (o/map (fn [i] (str "stream ONE" i))))
        o2 (-> (o/interval-temp 2)
               (o/map (fn [i] (str "stream TWO" i))))
        sub (-> (o/weave [o1 o2])
                (o/subscribe 
                 (fn [x]
                   (when (= x "stream ONE0")
                     (println "slow onNext processing start")
                     (Thread/sleep (* 3 1000))
                     (println "slow onNext processing done"))
                   (println "onNext" x))
                 (fn [e] (println "onError" e))
                 (fn [] (println "onCompleted"))))]
    (Thread/sleep 10000)
    (o/unsubscribe sub)))

(deftest concat-test
  (prn "test concat-test")
  (let [s1 (o/subject)
        stwo (o/subject)
        c (o/concat [s1 stwo])
        _ (prn "step1")
        cout (o/observable-seq c)]
    (prn "step2")
    (o/on-next s1 1)
    (o/on-next s1 2)
    (o/on-next stwo 20)
    (o/on-next s1 3)
    (o/on-completed s1)
    (o/on-next stwo 21)
    (o/on-next stwo 22)
    (o/on-completed stwo)
    (prn "compare")
    (is (= [1 2 3 21 22]
             cout))))

(deftest concat-test2
  (prn "test concat-test2")
  (let [obs (o/subject)
        s1 (o/subject)
        stwo (o/subject)
        c (o/concat obs)
        _ (prn "step1!")
        cout (o/observable-seq c)]
    (prn "start")
    (o/on-next obs s1)
    (o/on-next s1 1)
    (o/on-next s1 2)
    (o/on-next obs stwo)
    (o/on-next stwo 20)
    (o/on-next s1 3)
    (o/on-completed s1)
    (o/on-next stwo 21)
    (o/on-completed obs)
    (o/on-next stwo 22)
    (o/on-completed stwo)
    (prn "done")
    (is (= [1 2 3 21 22]
         cout))))

(deftest concat-reg
  (prn "test concat-reg")
  (let [c (o/concat [1 2 3] [[4 5 6] [7 8 9]])]
    (is (= [1 2 3 4 5 6 7 8 9]
             (o/observable-seq c)))))

(deftest concat-test3
  (prn "test concat-test3")
  (let [obs (o/subject)
        s1 (o/subject)
        stwo (o/subject)
        c (o/concat obs)
        _ (prn "step1!")
        cout (o/observable-seq c)]
    (prn "start")
    (o/on-next obs s1)
    (o/on-next s1 1)
    (o/on-next s1 2)
    (o/on-next s1 3)
    (o/on-completed s1)
    
    (o/on-next obs stwo)
    (o/on-next stwo 20)
    (o/on-next stwo 21)
    (o/on-completed obs)
    (o/on-next stwo 22)
    (o/on-completed stwo)
    (prn "done")
    (is (= [1 2 3 20 21 22]
             cout))))

(deftest concat-test4
  (prn "test concat-test4")
  (let [obs (o/subject)
        s1 (o/subject)
        stwo (o/subject)
        c (o/concat obs)
        _ (prn "step1!")
        cs (o/subject)
        sub (o/subscribe c cs)
        cout (o/observable-seq cs)
        ]
    (prn "start")
    (o/on-next obs s1)
     (o/on-next s1 1)
     (o/on-next s1 2)
     (o/on-next s1 3)
     (o/on-completed s1)
    (prn "add second obs")
    (o/on-next obs stwo)
      (o/on-next stwo 20)
      (o/on-next stwo 21)
    (o/on-completed obs)
    ;;    (o/unsubscribe sub)
      (o/on-next stwo 22)
      (o/on-completed stwo)
    (prn "done")
    (is (= [1 2 3 20 21 22]
         cout))))

(deftest filter-test
  (prn "test filter-test")
  (let [odd (-> (o/obv (range 10))
                (o/filter even?))]
    (is (= [0 2 4 6 8]
             (o/observable-seq odd)))))

(deftest distinct
  (prn "test distinct")
  (let [s (o/subject)
        d (o/distinct s)
        d-out (o/observable-seq d)]
    (o/on-next s 1)
    (o/on-next s 2)
    (o/on-next s 3)
    (o/on-next s 1)
    (o/on-next s 1)
    (o/on-next s 4)
    (o/on-completed s)
    (is (= [1 2 3 4]
             d-out))))

(deftest distinct-until-changed-test
  (prn "test distinct-until-changed-test")
  (let [s (o/subject)
        d (o/distinct-until-changed s)
        d-out (o/observable-seq d)]
    (o/on-next s 1)
    (o/on-next s 2)
    (o/on-next s 3)
    (o/on-next s 1)
    (o/on-next s 1)
    (o/on-next s 4)
    (o/on-completed s)
    (is (= [1 2 3 1 4]
             d-out))))

(deftest take-test
  (prn "test take-test")
  (let [o (-> (o/obv (range 10))
              (o/take 3))]
    (is (= (range 3)
           (o/observable-seq o)))))

(deftest take-test2
  (prn "test take-test2")
  (let [o (-> (o/interval-temp 1)
              (o/take 3))
        sub (o/subscribe o
                         (partial prn "on-next")
                         (partial prn "on-error")
                         (partial prn "on-completed"))]
    (Thread/sleep 5000)))

(deftest skip-test
  (prn "test skip-test")
  (let [o (-> (o/obv (range 10))
              (o/skip 3)
              (o/take 3))]
    (is (= (range 3 6)
           (o/observable-seq o)))))

(deftest skip-test2
  (prn "test skip-test2")
  (let [o (-> (o/obv (range 10))
              (o/skip-while (fn [v] (< v 3)))
              (o/take 3))]
    (is (= (range 3 6)
           (o/observable-seq o)))))

