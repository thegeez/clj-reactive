# clj-reactive

Abandoned project to experiment doing Reactive Extension in Clojure.

Clojure interface may be useful when used with RxJava as language-adaptor.

# Notes

Rx for Clojure

var arg function signatures

(clj/concat [1 2 3] [2 3 4])
vs
(rx/concat [[1 2 3] [2 3 4]])

varg arg is an implicit list, but rx/concat works on an observable.
You can add an observable to the concat observable after you have
defined concat.
for use in threading this methods also have this signature:
(f o obs) which does an (cons o obs) to call (f o+obs) 

Namespace
merge -> weave
others with prefix

o/obv works as clojure.core/seq, returns observable whenever input can
be turned into one, also when input is already an observable.
## License

Copyright © 2013 Gijs Stuurman

Distributed under the Eclipse Public License, the same as Clojure.
