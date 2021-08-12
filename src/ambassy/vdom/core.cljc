(ns ambassy.vdom.core
  (:refer-clojure :exclude [comp])
  (:require
    [clojure.core :as cc]
    [clojure.set :as set]
    [ambassy.client.util :as u]))

;; Things to do:
;; [x] Add support for the on-xxx.
;; [x] Add helper functions to write the vdom-diff structures.
;; [x] Add functions to compose vdom-diff together.
;; [ ] Implement the move of the children.
;;     Make sure it is easy enough to combine multiple vdom-diffs together.
;; [x] Test apply-vdom using vdom-diff.
;; [ ] The app-element should be able to have multiple children.
;; [ ] Review API structure: Do we still need an empty text node by default?
;; [ ] Check how Phoenix Live View deals with controlled text inputs, w.r.t. async and lag.


(comment
  ;; When we want to clear everything on the page, the root element should be the empty string.
  ;;
  ;; A vdom-diff (a.k.a. "vdom", which is shorter) structure can be either:
  ;; - a string: replaces existing node with a text node.
  ;; - a hashmap containing :tag, and optionally :attrs, :listeners and :children.
  ;; - a hashmap without :tag, and optionally :remove-attrs, :add-attrs,
  ;;   :remove-listeners, :add-listeners, :children-diff and :children-moves.

  ;; Examples:

  ;; When it is a string
  "Hello, world"

  ;; When it contains :tag
  {:tag "div"
   :attrs {"xxx" "value"}
   :listeners {"click" (fn [event] ,,,)}
   :children [vdom0 vdom1 ,,,]}

  ;; When it does not contain :tag
  {;; The keys in :remove-attrs and :add-attrs should be mutually exclusive.
   :remove-attrs #{"yyy"}
   :add-attrs {"xxx" "new-value"}

   ;; The keys are not necessarily mutually exclusive,
   ;; previous listeners have to be removed explicitly before new ones are added.
   :remove-listeners #{"focus"}
   :add-listeners {"click" (fn [event] ,,,)}

   ;; Re-use the vector format (index-op) from Diffuse.
   :children-diff [[:no-op size0]
                   [:update [vdom-diff0 vdom-diff1 ,,,]]
                   [:remove size1]
                   [:insert [vdom0 vdom1 ,,,]]
                   [:take size2 id0]
                   [:update-take [vdom-diff2 vdom-diff3 ,,,] id0]
                   [:put id0]
                   ,,,]}

  ,)


#?(:cljs
   (defn- add-event-listener [^js element event-type event-handler]
     (let [listeners (or (-> element .-event-listeners) {})]
       (set! (-> element .-event-listeners)
             (update listeners event-type (fnil conj #{}) event-handler))
       (-> element (.addEventListener event-type event-handler)))))

#?(:cljs
   (defn- remove-event-listeners [^js element event-type]
     (let [listeners (or (-> element .-event-listeners) {})]
       (doseq [event-handler (get listeners event-type)]
         (-> element (.removeEventListener event-type event-handler)))
       (set! (-> element .-event-listeners)
             (dissoc listeners event-type)))))

#?(:cljs
   (defn- create-dom [vdom]
     (if (string? vdom)
       ;; Text node
       (-> js/document (.createTextNode vdom))

       ;; Non-text node
       (let [{:keys [tag attrs listeners children]} vdom
             node (-> js/document (.createElement tag))]
         ;; Set the attributes
         (doseq [[k v] attrs]
           (-> node (.setAttribute k v)))

         ;; Set the listeners
         (doseq [[event-type event-handler] listeners]
           (add-event-listener node event-type event-handler))

         ;; Set the children
         (doseq [child children]
           (-> node (.appendChild (create-dom child))))

         ;; Return the node
         node))))

#?(:cljs
   (defn- extract-take-id->arg1 [children-diff]
     (into {}
           (keep (fn [[op-type size-or-vdom-diffs id]]
                   (when (or (= op-type :take)
                             (= op-type :update-take))
                     [id size-or-vdom-diffs])))
           children-diff)))

#?(:cljs
   ;; The parent is responsible for replacing the previous dom node
   ;; by the new one if they are different nodes.
   (defn- apply-vdom* [^js dom vdom]
     (cond
       (nil? vdom)
       dom

       (or (string? vdom)
           (contains? vdom :tag))
       (create-dom vdom)

       :else
       (let [^js dom-child-nodes (-> dom .-childNodes)
             {:keys [remove-attrs add-attrs
                     remove-listeners add-listeners
                     children-diff]} vdom]

         (doseq [attr remove-attrs]
           (-> dom (.removeAttribute attr)))

         (doseq [[k v] add-attrs]
           (-> dom (.setAttribute k v)))

         (doseq [event-type remove-listeners]
           (remove-event-listeners dom event-type))

         (doseq [[event-type event-handler] add-listeners]
           (add-listeners dom event-type event-handler))

         (let [take-id->arg1 (extract-take-id->arg1 children-diff)
               take-id->dom-nodes (-> (reduce (fn [[m index] [op arg1 arg2]]
                                                (case op
                                                  (:no-op :remove) [m (+ index arg1)]
                                                  (:update :insert) [m (+ index (count arg1))]
                                                  :take [(assoc m arg2 (mapv (fn [index]
                                                                               (-> dom-child-nodes (.item index)))
                                                                             (range index (+ index arg1))))
                                                         (+ index arg2)]
                                                  :update-take (do
                                                                 ;; Does the update of the element
                                                                 (dotimes [i (count arg1)]
                                                                   (let [^js child-element     (-> dom-child-nodes (.item (+ index i)))
                                                                         ^js new-child-element (apply-vdom* child-element (nth arg1 i))]
                                                                     (-> child-element (.replaceWith new-child-element))))

                                                                 ;; TODO: simplify the code?
                                                                 [(assoc m arg2 (mapv (fn [index]
                                                                                        (-> dom-child-nodes (.item index)))
                                                                                      (range index (+ index (count arg1)))))
                                                                  (+ index arg2)])
                                                  :put [m (+ index (let [take-arg1 (take-id->arg1 arg1)]
                                                                     (if (vector? take-arg1)
                                                                       (count take-arg1)
                                                                       take-arg1)))]))
                                              [{} 0]
                                              children-diff)
                                      first)]
           (loop [operations (seq children-diff)
                  index 0]
             (when operations
               (let [[op arg1 arg2] (first operations)
                     next-operations (next operations)]
                 (case op
                   :no-op (recur next-operations (+ index arg1))
                   :update (do
                             (dotimes [i (count arg1)]
                               (let [^js child-element     (-> dom-child-nodes (.item (+ index i)))
                                     ^js new-child-element (apply-vdom* child-element (nth arg1 i))]
                                 (-> child-element (.replaceWith new-child-element))))
                             (recur next-operations (+ index (count arg1))))
                   :remove (do
                             (dotimes [_ arg1]
                               (-> dom (.removeChild (-> dom-child-nodes (.item index)))))
                             (recur next-operations index))
                   :insert (do
                             (if (< index (-> dom-child-nodes .-length))
                               (let [node-after (-> dom-child-nodes (.item index))]
                                 (doseq [child-vdom arg1]
                                   (-> dom (.insertBefore (create-dom child-vdom) node-after))))
                               (doseq [child-vdom arg1]
                                 (-> dom (.appendChild (create-dom child-vdom)))))
                             (recur next-operations (+ index (count arg1))))
                   :take (recur next-operations (+ index arg1))
                   :update-take (recur next-operations (+ index (count arg1)))
                   :put (let [^js node-to (-> dom-child-nodes (.item index))
                              take-arg1 (take-id->arg1 arg1)
                              size (if (vector? take-arg1)
                                     (count take-arg1)
                                     take-arg1)
                              nodes-from (take-id->dom-nodes arg1)]
                          (-> node-to .-before (.apply node-to (into-array nodes-from)))
                          (recur next-operations (+ index size))))))))

         dom))))

#?(:cljs
   (defn apply-vdom [^js app-element vdom]
     ;; Ensures that we have a "root" node under app-element.
     (when (zero? (-> app-element .-childNodes .-length))
       (-> app-element (.appendChild (create-dom ""))))

     ;; Apply the vdom
     (let [current-dom-root (-> app-element .-firstChild)
           new-dom-root (apply-vdom* current-dom-root vdom)]
       (-> current-dom-root (.replaceWith new-dom-root)))))



;; ****************************************************************

(declare comp)


(defn- extract-take-id->state [children-diff]
  (into {}
        (keep (fn [[op-type size-or-vdom-diffs id]]
                (when (or (= op-type :take)
                          (= op-type :update-take))
                   [id (-> {:take-index 0
                            :put-index 0}
                           (assoc (if (vector? size-or-vdom-diffs)
                                    :updates
                                    :size)
                                  size-or-vdom-diffs))])))
        children-diff))


;; Copied from the Diffuse library
(defn- index-op-size
  "Returns the size of the operation in number of DOM elements."
  [take-id->state [op arg1 _]]
  (case op
    (:no-op :remove :take)
    arg1

    (:update :insert :update-take)
    (count arg1)

    :put
    (let [take-state (take-id->state arg1)]
      (or (:size take-state)
          (count (:updates take-state))))))


;; Copied from the Diffuse library
(defn- index-op-split
  "Splits an operation into 2 pieces so that the size of the first piece is the given size,
   and then return a vector containing those 2 pieces."
  [take-id->state [op arg1 arg2] size]
  (case op
    (:no-op :remove)
    [[op size]
     [op (- arg1 size)]]

    (:update :insert)
    [[op (subvec arg1 0 size)]
     [op (subvec arg1 size)]]

    ;; FIXME: splitting the :take implies trouble for the :put and the take-id.
    ;; In which situations do we need to split the :take and :put ?
    :take
    [[op size arg2]
     [op (- arg1 size) arg2]]

    ;; FIXME: splitting the :take implies trouble for the :put and the take-id.
    ;; In which situations do we need to split the :take and :put ?
    :update-take
    [[op (subvec arg1 0 size) arg2]
     [op (subvec arg1 size) arg2]]

    ;; FIXME: splitting the :take implies trouble for the :put and the take-id.
    ;; In which situations do we need to split the :take and :put ?
    :put
    [[op arg1 size]
     [op arg1 (- (let [take-state (take-id->state arg1)]
                   (or (:size take-state)
                       (count (:updates take-state))))
                 size)]]))


;; Copied from the Diffuse library
(defn- head-split
  "Transform 2 sequences of index operations so that their first elements
   have the same size."
  [new-take-id->state new-iops
   base-take-id->state base-iops]
  (let [new-iop (first new-iops)
        base-iop (first base-iops)
        new-size (index-op-size new-take-id->state new-iop)
        base-size (index-op-size base-take-id->state base-iop)]
    (cond
      (= new-size base-size)
      [base-size new-iops base-iops]

      (< new-size base-size)
      (let [[base-head base-tail] (index-op-split base-take-id->state base-iop new-size)]
        [new-size new-iops (list* base-head base-tail (rest base-iops))])

      (> new-size base-size)
      (let [[new-head new-tail] (index-op-split new-take-id->state new-iop base-size)]
        [base-size (list* new-head new-tail (rest new-iops)) base-iops]))))

(defn- ensure-take-state-has-updates [state]
  (cond-> state
    (not (contains? state :updates))
    (assoc :updates (vec (repeat (:size state) nil)))))

#_(ensure-take-state-has-updates {:size 3})

;; Copied from the Diffuse library
(defn- index-ops-comp
  "Composes 2 sequences of index operations, and return the result.
   Note 2: the result is not guaranteed to be canonical/normalized."
  [new-take-id->state new-iops
   base-take-id->state base-iops]
  (let [take-id-offset (count base-take-id->state)]
    (loop [output-take-id->state (into base-take-id->state
                                       (map (fn [[take-id state]]
                                              [(+ take-id take-id-offset) state]))
                                       new-take-id->state)
           output []
           new-iops new-iops
           base-iops base-iops]
      (cond
        (empty? base-iops) [output-take-id->state (into output new-iops)]
        (empty? new-iops) [output-take-id->state (into output base-iops)]
        :else (let [[op-size split-new-iops split-base-iops] (head-split new-take-id->state new-iops
                                                                         base-take-id->state base-iops)
                    [new-op new-arg1 new-arg2 :as new-iop] (first split-new-iops)
                    [base-op base-arg1 base-arg2 :as base-iop] (first split-base-iops)]
                (if (or (= new-op :insert)
                        (= new-op :put))
                  (recur output-take-id->state
                         (conj output new-iop)
                         (rest split-new-iops)
                         split-base-iops)
                  (case base-op
                    :remove (recur output-take-id->state
                                   (conj output base-iop)
                                   split-new-iops
                                   (rest split-base-iops))
                    :take (recur (update-in output-take-id->state
                                            [base-arg2 :take-index]
                                            + base-arg1)
                                 (conj output base-iop)
                                 split-new-iops
                                 (rest split-base-iops))
                    :update-take (recur (update-in output-take-id->state
                                                   [base-arg2 :take-index]
                                                   + op-size)
                                        (conj output [:take op-size base-arg2])
                                        split-new-iops
                                        (rest split-base-iops))
                    :no-op (recur output-take-id->state
                                  (conj output (if (= new-op :update-take)
                                                 [:take op-size (+ new-arg2 take-id-offset)]
                                                 new-iop))
                                  (rest split-new-iops)
                                  (rest split-base-iops))
                    :update (case new-op
                              :no-op (recur output-take-id->state
                                            (conj output base-iop)
                                            (rest split-new-iops)
                                            (rest split-base-iops))
                              :update (recur output-take-id->state
                                             (conj output [:update (mapv comp new-arg1 base-arg1)])
                                             (rest split-new-iops)
                                             (rest split-base-iops))
                              :remove (recur output-take-id->state
                                             (conj output new-iop)
                                             (rest split-new-iops)
                                             (rest split-base-iops))
                              :take (recur (update output-take-id->state
                                                   (+ new-arg2 take-id-offset)
                                                   (fn [{:keys [take-index] :as state}]
                                                     (-> state
                                                         (update :take-index + op-size)
                                                         ensure-take-state-has-updates
                                                         (update :updates u/replace-subvec take-index base-arg1))))
                                           (conj output new-iops)
                                           (rest split-new-iops)
                                           (rest split-base-iops))
                              :update-take (recur (update output-take-id->state
                                                          (+ new-arg2 take-id-offset)
                                                          (fn [{:keys [take-index] :as state}]
                                                            (-> state
                                                                (update :take-index + op-size)
                                                                (update :updates u/replace-subvec take-index (mapv comp new-arg1 base-arg1)))))
                                                  (conj output [:take op-size (+ new-arg2 take-id-offset)])
                                                  (rest split-new-iops)
                                                  (rest split-base-iops)))
                    :insert (case new-op
                              :no-op (recur output-take-id->state
                                            (conj output base-iop)
                                            (rest split-new-iops)
                                            (rest split-base-iops))
                              :update (recur output-take-id->state
                                             (conj output [:insert (mapv comp new-arg1 base-arg1)])
                                             (rest split-new-iops)
                                             (rest split-base-iops))
                              :remove (recur output-take-id->state
                                             output
                                             (rest split-new-iops)
                                             (rest split-base-iops))
                              ;; FIXME: :insert then :take -> replace each :put with the insert.
                              ;; PROBLEM: we ommit :take from the output, how will we know that the :put
                              ;;          should be turned into an :insert ?
                              :take (recur (update output-take-id->state
                                                   (+ new-arg2 take-id-offset)
                                                   (fn [{:keys [take-index] :as state}]
                                                     (-> state
                                                         (update :take-index + op-size)
                                                         ;; FIXME: correct it to become an :insert
                                                         #_#_
                                                         ensure-take-state-has-updates
                                                         (update :updates u/replace-subvec take-index base-arg1))))
                                           output
                                           (rest split-new-iops)
                                           (rest split-base-iops))
                              ;; Moved updated insert
                              :update-take (recur (update output-take-id->state
                                                          (+ new-arg2 take-id-offset)
                                                          (fn [{:keys [take-index] :as state}]
                                                            (-> state
                                                                (update :take-index + op-size)
                                                                ;; FIXME: correct it to become an :insert
                                                                #_#_
                                                                ensure-take-state-has-updates
                                                                (update :updates u/replace-subvec take-index (mapv comp new-arg1 base-arg1)))))
                                                  output
                                                  (rest split-new-iops)
                                                  (rest split-base-iops)))
                    ;; TODO: ... finish this part
                    :put (case new-op
                           :no-op (recur output-take-id->state
                                         (conj output base-iop)
                                         (rest split-new-iops)
                                         (rest split-base-iops))
                           ;; TODO: apply the update on the source of the :take
                           :update (recur (update output-take-id->state
                                                  base-arg1
                                                  (fn [{:keys [put-index] :as state}]
                                                    (-> state
                                                        (update :put-index + op-size)
                                                        ensure-take-state-has-updates
                                                        (update :updates (fn [updates]
                                                                           (u/replace-subvec updates
                                                                                             put-index
                                                                                             ;; FIXME: incorrect - go to sleep !
                                                                                             (mapv comp new-arg1 base-arg1)))))))
                                          ;;(conj output [:insert (mapv comp new-arg1 base-arg1)])
                                          (conj output base-iop)
                                          (rest split-new-iops)
                                          (rest split-base-iops))
                           ;; TODO: remove the :take and the :put, insert the :remove in the output.
                           :remove (recur output-take-id->state
                                          output
                                          (rest split-new-iops)
                                          (rest split-base-iops))
                           ;; TODO: remove the base :put and the new :take, make the new put use the base's take-id.
                           :take (recur (update output-take-id->state
                                                (+ new-arg2 take-id-offset)
                                                (fn [{:keys [take-index] :as state}]
                                                  (-> state
                                                      (update :take-index + op-size)
                                                      ;; FIXME: correct it to become an :insert
                                                      #_#_
                                                      ensure-take-state-has-updates
                                                      (update :updates u/replace-subvec take-index base-arg1))))
                                        output
                                        (rest split-new-iops)
                                        (rest split-base-iops))
                           ;; TODO: update the base :take, remove the base :put and the new :update-take, make new put use the base's take-id.
                           ;; Moved updated insert
                           :update-take (recur (update output-take-id->state
                                                       (+ new-arg2 take-id-offset)
                                                       (fn [{:keys [take-index] :as state}]
                                                         (-> state
                                                             (update :take-index + op-size)
                                                             ;; FIXME: correct it to become an :insert
                                                             #_#_
                                                             ensure-take-state-has-updates
                                                             (update :updates u/replace-subvec take-index (mapv comp new-arg1 base-arg1)))))
                                               output
                                               (rest split-new-iops)
                                               (rest split-base-iops))))))))))


(defn- transform-orphan-takes-into-removes
  "Returns the operations where any :take which do not have a matching :put is changed into a :remove.
   ... also return a transformed take-id->size.
   This function should be called before index-ops-canonical."
  [take-id->size iops]
  (let [orphan-take-ids (set/difference (set (keys take-id->size))
                                        ;; all the used take-ids
                                        (->> iops
                                             (keep (fn [[op-type arg1]]
                                                     (when (= op-type :put)
                                                       arg1)))
                                             set))
        transformed-iops (into []
                               (map (fn [[op-type arg1 :as operation]]
                                      (if (and (= op-type :take)
                                               (contains? orphan-take-ids arg1))
                                        [:remove arg1]
                                        operation)))
                               iops)
        transformed-take-id->size (apply dissoc take-id->size orphan-take-ids)]
    [transformed-take-id->size transformed-iops]))

;; Copied from the Diffuse library
(defn- index-ops-canonical
  "Transform a sequence of index operations into its canonical form.
   The goal is to regroup operations with the same type, as well as to order the
   operations whose order can be reversed so that they are always in the same order.
   It's a kind of normalization process."
  [iops]
  (into []
        (cc/comp (partition-by (cc/comp {:no-op :no-op
                                         :update :update
                                         :remove :remsert
                                         :insert :remsert} first))
                 (mapcat (fn [index-ops]
                           (let [op (ffirst index-ops)]
                             (case op
                               :no-op [[op (transduce (map second) + index-ops)]]
                               :update [[op (into [] (mapcat second) index-ops)]]
                               (:remove :insert) (let [{removes :remove
                                                        inserts :insert} (group-by first index-ops)
                                                       remove-count (transduce (map second) + removes)
                                                       insert-elms (into [] (mapcat second) inserts)]
                                                   (cond-> []
                                                     (pos? remove-count) (conj [:remove remove-count])
                                                     (pos? (count insert-elms)) (conj [:insert insert-elms]))))))))
        iops))

(defn comp
  ([vdom] vdom)
  ([vdom2 vdom1]
   (cond
     ;; nil represents a no-change diff
     (nil? vdom1) vdom2
     (nil? vdom2) vdom1

     ;; vdom2 overwrites vdom1
     (contains? vdom2 :tag) vdom2

     ;; vdom2 applies on vdom1's content
     (contains? vdom1 :tag)
     (let [attrs (-> (:attrs vdom1)
                     (reduce dissoc (:remove-attrs vdom2))
                     (into (:add-attrs vdom2)))
           listeners (-> (:listeners vdom1)
                         (reduce dissoc (:remove-listeners vdom2))
                         (into (:add-listeners vdom2)))
           children (loop [children-out []
                           children-in  (:children vdom1)
                           operations   (seq (:children-diff vdom2))]
                      (if operations
                        (let [[op arg] (first operations)
                              next-operations (next operations)]
                          (case op
                            :no-op (recur (into children-out (take arg) children-in)
                                          (subvec children-in arg)
                                          next-operations)
                            :update (recur (into children-out (mapv comp arg children-in))
                                           (subvec children-in (count arg))
                                           next-operations)
                            :remove (recur children-out
                                           (subvec children-in arg)
                                           next-operations)
                            :insert (recur (into children-out arg)
                                           children-in
                                           next-operations)))
                        (into children-out children-in)))]
       (cond-> {:tag (:tag vdom1)}
         (some? attrs) (assoc :attrs attrs)
         (some? listeners) (assoc :listeners listeners)
         (some? children) (assoc :children children)))

     ;; vdom1 and vdom2 are both diffs (i.e. no :tags)
     :else
     (let [{remove-attrs1     :remove-attrs
            add-attrs1        :add-attrs
            remove-listeners1 :remove-listeners
            add-listeners1    :add-listeners
            children-diff1    :children-diff} vdom1
           {remove-attrs2     :remove-attrs
            add-attrs2        :add-attrs
            remove-listeners2 :remove-listeners
            add-listeners2    :add-listeners
            children-diff2    :children-diff} vdom2
           remove-attrs (-> remove-attrs1
                            (set/union remove-attrs2)
                            (as-> xxx (reduce disj xxx (keys add-attrs2))))
           add-attrs (-> add-attrs1
                         (as-> xxx (reduce dissoc xxx remove-attrs2))
                         (into add-attrs2))
           remove-listeners (-> remove-listeners1
                                (set/union remove-listeners2))
           add-listeners (-> add-listeners1
                             (as-> xxx (reduce dissoc xxx remove-listeners2))
                             (into add-listeners2))
           take-id->state1 (extract-take-id->state children-diff1)
           take-id->state2 (extract-take-id->state children-diff2)
           children-diff (-> (index-ops-comp take-id->state2 children-diff2
                                             take-id->state1 children-diff1)
                             second ;; <- TMP
                             index-ops-canonical)]
       (-> {}
           (cond->
             (seq remove-attrs) (assoc :remove-attrs remove-attrs)
             (seq add-attrs) (assoc :add-attrs add-attrs)
             (seq remove-listeners) (assoc :remove-listeners remove-listeners)
             (seq add-listeners) (assoc :add-listeners add-listeners)
             (seq children-diff) (assoc :children-diff children-diff))
           not-empty))))
  ([vdom2 vdom1 & more-vdoms]
   (reduce comp (comp vdom2 vdom1) more-vdoms)))

(defn comp-> [& vdoms]
  (apply comp (reverse vdoms)))


(comment
  (comp-> (h/insert 2 (h/hiccup "xxx"))
          (h/insert 4 (h/hiccup "yyy")))

  (comp-> (h/remove-in [0] 1)
          (h/remove-in [0 1 3] 3)
          (h/insert-in [0 1 3]
                       (h/hiccup [:li "aaa"])
                       (h/hiccup [:li "bbb"]))
          (h/insert-in [0 2]
                       (h/hiccup [:p "xxx"])
                       (h/hiccup [:div "yyy"])))

  (vdom/comp (h/move 2 1 4)
             (h/move 2 1 4))

  ,)