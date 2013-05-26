(ns net.thegeez.reactive.test.marbles
  (:use clojure.test)
  (:require [net.thegeez.reactive.core :as o]
            [net.thegeez.reactive.test.marble :refer [marble myor]])
  (:import [net.thegeez.reactive.core OnNextNotification OnErrorNotification OnCompletedNotification]))

(deftest marble-test-empty
  (is (marble [:src []
               :expr :src 
               :out []])))

(deftest marble-test-error
    (is (marble [:src [X]
                 :expr :src 
                 :out [X]])))

(deftest marble-test-completed
    (is (marble [:src [|]
                 :expr :src 
                 :out [|]])))

(deftest flip-example
  (is (marble [:src [ 1  2  3 4 5 6 | ]
               :expr (o/map :src (fn [value]
                                  (if (= value 4)
                                       (throw (Exception. "DemoFail on 4"))
                                       (* -1 value))))
               :out [-1 -2 -3 X]])))

(deftest empty-test
    (is (marble [:expr (o/empty)
                 :out [|]])))

(deftest just-test
    (is (marble [:expr (o/just 1)
                 :out [ 1 |]])))

(deftest filter-test
    (is (marble [:src [1 2 3 4 5 6 |]
                 :expr (o/filter :src #{1 4})
                 :out [1 - - 4 - - |]])))

(deftest filter-empty
      (is (marble [:src [|]
                   :expr (o/filter :src #{1 4})
                   :out [|]])))

(deftest map-test
  (is (marble [:src [ 1 2 3 4 5 6 |]
               :expr (o/map :src inc)
               :out [ 2 3 4 5 6 7 |]])))

(deftest map-many-test
  (is (marble [:src [ 1 - 2 - 3 |]
               :expr (o/map-many :src (fn [v]
                                        (o/obv [v v])))
               :out [ 1 1 2 2 3 3 |]])))

;; ;; todo define eq for Notifactions (make them records?)
;; #_(deftest materialize-test
;;   (is (marble [:src [ 1 2 |]
;;                :expr (o/materialize :src)
;;                :out [ (OnNextNotification. 1) (OnNextNotification. 2) (OnCompletedNotification.) |]])))

(deftest weave-test
  (is (marble [:in1 [1 - 3 - X]
               :in2 [- 2 - 4 - 6 |]
               :expr (o/weave [:in1 :in2])
               :out [1 2 3 4 X]])))

(deftest weave-test-2
  (is (marble [:in1 [1 - - 4 X]
               :in2 [- 2 3 - - 6 |]
               :expr (o/weave [:in1 :in2])
               :out [1 2 3 4 X]])))

(deftest weave-test-3
  (is (marble [:in1 [1 - - 4 X]
               :in2 [- - 3 - - 6 |]
               :in3 [- 2 - |]
               :expr (o/weave :in1 [:in2 :in3])
               :out [1 2 3 4 X]])))

(deftest concat-test
  (is (marble [:in1 [1 2 3 |]
               :in2 [- - 4 5 6 |]
               :expr (o/concat [:in1 :in2])
               :out [1 2 3 5 6 |]])))

(deftest concat-overlap
  (is (marble [:in1 [1 2 3 |]
               :in2 [- - 4 5 6 |]
               :expr (o/concat [:in1 :in2])
               :out [1 2 3 5 6 |]])))

(deftest concat-overlap-2
  (is (marble [:in1 [- - 1 2 3 |]
               :in2 [- - - - 4 5 6 |]
               :in3 [7 8 - - - - - 9 |]
               :expr (o/concat :in1 [:in2 :in3])
               :out [- - 1 2 3 5 6 9 |]])))

;; #_(deftest merge-delay-error
;;   (is (marble [:in1 [1 - 3 - X]
;;                :in2 [- 2 - 4 - 6 |]
;;                :expr (Observable/mergeDelayError [:in1 :in2])
;;                :out [1 2 3 4 - 6 X]])))

;; #_(deftest on-error-resume-next
;;   (is (marble [:in1 [1 2 3 X]
;;                :in2 [- - - 4 5 |]
;;                :expr (Observable/onErrorResumeNext :in1 :in2)
;;                :out [1 2 3 4 5 |]])))

;; #_(deftest on-error-resume-next-overlap
;;   (is (marble [:in1 [1 2 3 X]
;;                :in2 [- - 4 5 6 |]
;;                :expr (Observable/onErrorResumeNext :in1 :in2)
;;                :out [1 2 3 5 6 |]])))

;; #_(deftest on-error-resume-next-fn
;;   (is (marble [:src [1 2 3 X]
;;                :expr (Observable/onErrorResumeNext :src (fn [error]
;;                                                           (Observable/from [5 6])))
;;                :out [1 2 3 5 6 |]])))

;; #_(deftest on-error-resume-next-no-error
;;   (is (marble [:in1 [1 2 3 |]
;;                :in2 [- - 4 5 6 |]
;;                :expr (Observable/onErrorResumeNext :in1 :in2)
;;                :out [1 2 3 |]])))

;; #_(deftest reduce-test
;;   (is (marble [:src [1 2 3 |]
;;                :expr (Observable/reduce :src +)
;;                :out [- - - 6 |]])))

;; #_(deftest reduce-test-seed
;;   (is (marble [:src  [1 2 3 |]
;;                :expr (Observable/reduce :src 3 +)
;;                :out [- - - 9 |]])))

;; #_(deftest scan-test
;;   (is (marble [:src [1 2 3 |]
;;                :expr (Observable/scan :src +)
;;                :out [- 1 3 6 |]])))

;; ;; note the diagram in javadoc is wrong with regard to the seed
;; #_(deftest scan-test-seed
;;   (is (marble [:src [1 2 3 |]
;;                :expr (Observable/scan :src 3 +)
;;                :out [3 4 6 9 |]])))

(deftest skip-test
  (is (marble [:src [1 2 3 4 5 |]
               :expr (o/skip :src 3)
               :out [- - - 4 5 |]])))

(deftest take-test
  (is (marble [:src [1 2 3 4 5 |]
               :expr (o/take :src 2)
               :out [1 2 |]])))

(deftest take-while-test
  (is (marble [:src [1 2 3 4 5 |]
               :expr (o/take-while :src (fn [i] (< i 3)))
               :out [1 2 |]])))

;; toList fails with multiple subscribers and demos bug in rxjava 0.6.1
;; the multiple observers are an implementation detail of marble
;; see https://github.com/Netflix/RxJava/pull/206
;; #_(deftest list-test
;;   (is (marble [:src [1 2 3 4 5 6 |]
;;                :expr (Observable/toList :src)
;;                :out [- - - - - - [1 2 3 4 5 6] | ]])))

(deftest to-observable-test
  (is (marble [:expr (o/obv [1 2 3 4 5 6])
               :out [1 2 3 4 5 6 |]])))

;; ;; fails due to toList fail
;; #_(deftest to-sorted-list
;;   (is (marble [:src [2 5 1 6 3 4 |]
;;                :expr (Observable/toSortedList :src)
;;                :out [- - - - - - [1 2 3 4 5 6] | ]])))

(deftest zip-test
  (is (marble [:in1 [1 - 3 - 5 |]
               :in2 [- 2 - 4 - 6 |]
               :expr (o/zip [:in1 :in2])
               :out [- [1 2] - [3 4] - [5 6] |]])))

(deftest zip-two
   (is (marble [:in1 [1 -     3 -     5 |]
                :in2 [- 2     - 4     - 6 |]
                :expr (o/zip [:in1 :in2])
                :out [- [1 2] - [3 4] - [5 6] |]])))

(deftest zip-three
   (is (marble [:in1 [1 - - 4 - -  7 |]
                :in2 [- 2 - - 5 -  - 8 |]
                :in3 [- - 3 - - 6  - - 9 |]
                :expr (o/zip [:in1 :in2 :in3])
                :out [- - [1 2 3] - - [4 5 6] - - [7 8 9] |]])))

(deftest zip-four
   (is (marble [:in1 [1 - -  5 -  -  9 |]
                :in2 [- 2 -  - 6  -  10 |]
                :in3 [- - 3  7 -  11 |]
                :in4 [- 4 -  - 8  -  12 | ]
                :expr (-> (o/zip :in1 [:in2 :in3 :in4])
                          (o/map (fn [[i1 i2 i3 i4]]
                                 (+ i1 i2 i3 i4))))
                :out [- - 10 - 26 - 42 |]])))

(deftest zip-five
   (is (marble [:in1 [1 2 3 4 5 6 7 |]
                :in2 [- - - - - - 8 9  10 |]
                :expr (-> (o/zip [:in1 :in2])
                          (o/map (fn [[a b]]
                                   (+ a b))))
                :out [- - - - - - 9 11 13 |]])))

(deftest zip-error
   (is (marble [:in1 [1 - -  5 -  -  9 |]
                :in2 [- 2 -  X 6  -  10 |]
                :in3 [- - 3  7 -  11 |]
                :in4 [- 4 -  - 8  -  12 | ]
                :expr (-> (o/zip :in1 [:in2 :in3 :in4])
                          (o/map (fn [[i1 i2 i3 i4]]
                                 (+ i1 i2 i3 i4))))
                :out [- - 10 X]])))



