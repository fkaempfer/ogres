(ns ogres.app.events
  (:require [datascript.core :as ds :refer [squuid]]
            [clojure.set :refer [union]]
            [clojure.string :refer [trim]]
            [ogres.app.geom :refer [bounding-box normalize within?]]
            [ogres.app.util :refer [comp-fn with-ns]]))

(def ^:private suffix-max-xf
  (map (fn [[label tokens]] [label (apply max (map :initiative/suffix tokens))])))

(def ^:private zoom-scales
  [0.15 0.30 0.50 0.75 0.90 1 1.25 1.50 2 3 4])

(defn ^:private find-next
  "Finds the element in the given collection which passes the given predicate
   and returns the element that appears after it. Returns nil if no element
   passes the predicate or if the element found is the last in the collection."
  [pred xs]
  (first (next (drop-while (complement pred) xs))))

(defn ^:private indexed
  "Returns a transducer which decorates each element with a decreasing
   negative index suitable for use as temporary ids in a DataScript
   transaction. Optionally receives an offset integer to begin counting and
   a step integer to create space between indexes."
  ([]
   (indexed 1 1))
  ([offset]
   (indexed offset 1))
  ([offset step]
   (map-indexed (fn [idx val] [(-> (* idx step) (+ offset) (* -1)) val]))))

(defn ^:private suffix-token-key
  "Returns a grouping key for the given token that will match other similarly
   identifiable tokens."
  [token]
  (let [{label :token/label
         {checksum :image/checksum} :token/image} token]
    [label checksum]))

(defn ^:private suffixes
  "Returns a map of `{entity key => suffix}` for the given token entities.
   Each suffix represents a unique and stable identity for a token within
   the group of tokens by which it shares a label. Suffixes are intended to
   help decorate tokens that may otherwise be difficult to distinguish
   when they share the same image and label."
  [tokens]
  (let [groups (group-by suffix-token-key tokens)
        offset (into {} suffix-max-xf groups)]
    (loop [tokens tokens index {} result {}]
      (if (seq tokens)
        (let [token (first tokens)
              group (suffix-token-key token)]
          (if (or (= (count (groups group)) 1)
                  (:initiative/suffix token)
                  (contains? (:token/flags token) :player))
            (recur (rest tokens) index result)
            (recur (rest tokens)
                   (update index group inc)
                   (assoc result (:db/key token) (+ (offset group) (index group) 1)))))
        result))))

(defn ^:private round
  ([x]   (round x 1))
  ([x n] (* (js/Math.round (/ x n)) n)))

(defn ^:private to-precision [n p]
  (js/Number (.toFixed (js/Number.parseFloat n) p)))

(defn ^:private constrain [n min max]
  (clojure.core/max (clojure.core/min n max) min))

(defn ^:private mode-allowed? [mode type]
  (not (and (contains? #{:mask :mask-toggle :mask-remove :grid} mode)
            (not= type :host))))

(defn ^:private trans-xf [x y]
  (comp (partition-all 2) (drop 1) (map (fn [[ax ay]] [(+ ax x) (+ ay y)])) cat))

(defn ^:private initiative-order [a b]
  (let [f (juxt :initiative/roll :db/key)]
    (compare (f b) (f a))))

(defmulti event-tx-fn (fn [_ event] event))

(defmethod event-tx-fn :default [] [])

;; -- Local --
(defmethod
  ^{:doc "Change the rendering status of the given local user. This status
          is used to decide what the top-level rendering mode is in order
          to, for example, render an error page when a WebSocket connection
          has failed."}
  event-tx-fn :local/change-status
  [_ _ status]
  [{:db/ident :local :local/status status}])

(defmethod
  ^{:doc "Changes whether or not toolbar tooltips are displayed when the
          local user hovers over them."}
  event-tx-fn :local/toggle-tooltips
  [_ _ display?]
  [{:db/ident :local :local/tooltips? display?}])

(defmethod
  ^{:doc "Changes the currently expanded panel form to the value given by
          `panel`."}
  event-tx-fn :local/toggle-panel
  [_ _ panel]
  [{:db/ident :local :panel/expanded #{panel}}])

(defmethod
  ^{:doc "Changes the current keyboard modifier for the local user. This
          modifier is currently only used to determine if the 'Shift' key is
          depressed so that users can draw a selection box across the scene,
          selecting more than one token."}
  event-tx-fn :local/modifier-start
  [_ _ modifier]
  [{:db/ident :local :local/modifier modifier}])

(defmethod
  ^{:doc "Releases the given keyboard modifier for the local user."}
  event-tx-fn :local/modifier-release
  []
  [[:db/retract [:db/ident :local] :local/modifier]])

;; -- Camera --
(defn ^:private assoc-camera
  [data & kvs]
  (let [local (ds/entity data [:db/ident :local])]
    [(apply assoc {:db/key (:db/key (:local/camera local))} kvs)]))

(defmethod
  ^{:doc "Changes the public label for the current camera."}
  event-tx-fn :camera/change-label
  [_ _ label]
  [[:db.fn/call assoc-camera :camera/label label]])

(defmethod
  ^{:doc "Removes the public label for the current camera."}
  event-tx-fn :camera/remove-label
  [data]
  (let [local (ds/entity data [:db/ident :local])]
    [[:db/retract [:db/key (:db/key (:local/camera local))] :camera/label]]))

(defmethod
  ^{:doc "Translates the current camera to the point given by `x` and `y`."}
  event-tx-fn :camera/translate
  [_ _ x y]
  [[:db.fn/call assoc-camera :camera/point [(round x) (round y)]]])

(defmethod
  ^{:doc "Changes the camera draw mode to the given value. The draw mode is
          used to decide what behavior clicking and dragging on the scene
          will have, such as drawing a shape or determining the distance
          between two points."}
  event-tx-fn :camera/change-mode
  [data _ mode]
  (let [local (ds/entity data [:db/ident :local])]
    (if (mode-allowed? mode (:local/type local))
      [{:db/key (:db/key (:local/camera local)) :camera/draw-mode mode}]
      [])))

(defmethod
  ^{:doc "Changes the zoom value for the current camera by the given value
          `next` and, optionally, a cursor point given by `x` and `y`.
          This method preserves the point of the cursor on the scene,
          adjusting the camera point to ensure that the user feels as if
          they are zooming in or out from their cursor."}
  event-tx-fn :camera/zoom-change
  ([data event & args]
   (let [local (ds/entity data [:db/ident :local])]
     (case (count args)
       0 [[:db.fn/call event-tx-fn event 1]]
       1 (let [[scale] args
               [_ _ w h] (or (:bounds/self local) [0 0 0 0])]
           [[:db.fn/call event-tx-fn event scale (/ w 2) (/ h 2)]])
       (let [[scale x y] args
             camera  (:local/camera local)
             [cx cy] (or (:camera/point camera) [0 0])
             prev    (or (:camera/scale camera) 1)
             fx      (/ scale prev)
             dx      (/ (- (* x fx) x) scale)
             dy      (/ (- (* y fx) y) scale)]
         [{:db/key (:db/key camera)
           :camera/point [(round (+ cx dx)) (round (+ cy dy))]
           :camera/scale scale}])))))

(defmethod
  ^{:doc "Changes the zoom value for the current camera by offsetting it from
          the given value `delta`. This is useful for zooming with a device
          that uses fine grained updates such as a mousewheel or a trackpad."}
  event-tx-fn :camera/zoom-delta
  [data _ delta x y]
  (let [local  (ds/entity data [:db/ident :local])
        scale  (-> (:camera/scale (:local/camera local)) (or 1) (js/Math.log)
                   (+ delta) (js/Math.exp) (to-precision 2) (constrain 0.15 4))]
    [[:db.fn/call event-tx-fn :camera/zoom-change scale x y]]))

(defmethod
  ^{:doc "Increases the zoom value for the current camera to the next nearest
          zoom level. These fixed zoom levels are determined by an internal
          constant."}
  event-tx-fn :camera/zoom-in
  [data]
  (let [local  (ds/entity data [:db/ident :local])
        camera (:local/camera local)
        prev   (or (:camera/scale camera) 1)
        next   (reduce (fn [n s] (if (> s prev) (reduced s) n)) prev zoom-scales)]
    [[:db.fn/call event-tx-fn :camera/zoom-change next]]))

(defmethod
  ^{:doc "Decreases the zoom value for the current camera to the nearest
          previous zoom level. These fixed zoom levels are determined by an
          internal constant."}
  event-tx-fn :camera/zoom-out
  [data]
  (let [local  (ds/entity data [:db/ident :local])
        camera (:local/camera local)
        prev   (or (:camera/scale camera) 1)
        next   (reduce (fn [n s] (if (< s prev) (reduced s) n)) prev (reverse zoom-scales))]
    [[:db.fn/call event-tx-fn :camera/zoom-change next]]))

(defmethod
  ^{:doc "Resets the zoom value for the given camera to its default setting of
          100%."}
  event-tx-fn :camera/zoom-reset
  []
  [[:db.fn/call event-tx-fn :camera/zoom-change 1]])

;; -- Scenes --
(defmethod
  ^{:doc "Creates a new blank scene and corresponding camera for the local user
          then switches them to it."}
  event-tx-fn :scenes/create
  []
  [{:db/ident :root :root/scenes {:db/id -1 :db/key (squuid)}}
   {:db/id -2 :db/key (squuid) :camera/scene -1}
   {:db/ident :local :local/cameras -2 :local/camera -2}])

(defmethod
  ^{:doc "Switches to the given scene by the given camera identifier."}
  event-tx-fn :scenes/change
  [data _ key]
  (let [camera (ds/entity data [:db/key key])]
    [{:db/ident :local
      :local/camera
      {:db/key key
       :camera/scene
       {:db/key (:db/key (:camera/scene camera))}}}]))

(defmethod
  ^{:doc "Removes the scene and corresponding camera for the local user. Also
          removes all scene cameras for any connected users and switches them
          to whichever scene the host is now on."}
  event-tx-fn :scenes/remove
  [data _ key]
  (let [select-w [:db/key {:camera/scene [:db/key]}]
        select-l [:db/key {:local/cameras select-w :local/camera select-w}]
        select-r [{:root/local select-l} {:root/session [{:session/conns select-l}]}]
        select-o [:db/key {:camera/scene [:db/key {:camera/_scene [:db/key]}]}]

        {{scene :db/key
          remove :camera/_scene} :camera/scene}
        (ds/pull data select-o [:db/key key])

        {{conns   :session/conns} :root/session
         {camera  :local/camera
          cameras :local/cameras} :root/local}
        (ds/pull data select-r [:db/ident :root])]
    (cond
      (= (count cameras) 1)
      (into [[:db/retractEntity [:db/key scene]]
             {:db/id -1
              :db/key (squuid)
              :camera/scene {:db/key (squuid)}}
             {:db/ident :local :local/cameras -1 :local/camera -1}]
            (comp cat cat)
            (list (for [{:keys [db/key]} remove]
                    [[:db/retractEntity [:db/key key]]])
                  (for [[idx conn] (sequence (indexed 3 2) conns)
                        :let [tmp (dec idx)]]
                    [[:db/add idx :db/key (:db/key conn)]
                     [:db/add idx :local/cameras tmp]
                     [:db/add idx :local/camera tmp]
                     [:db/add tmp :db/key (squuid)]
                     [:db/add tmp :camera/scene -2]
                     [:db/add tmp :camera/point [0 0]]
                     [:db/add tmp :camera/scale 1]])))

      (= key (:db/key camera))
      (let [host-cam (first (filter (comp-fn not= :db/key (:db/key camera)) cameras))
            host-scn (:db/key (:camera/scene host-cam))]
        (into [[:db/retractEntity [:db/key scene]]
               {:db/ident :local :local/camera {:db/key (:db/key host-cam)}}]
              (comp cat cat)
              (list (for [{:keys [db/key]} remove]
                      [[:db/retractEntity [:db/key key]]])
                    (for [[idx conn] (sequence (indexed 3 2) conns)
                          :let [tmp (dec idx)
                                cam (->> (:local/cameras conn)
                                         (filter (comp-fn = (comp :db/key :camera/scene) host-scn))
                                         (first))]]
                      [[:db/add idx :db/key (:db/key conn)]
                       [:db/add idx :local/cameras tmp]
                       [:db/add idx :local/camera tmp]
                       [:db/add tmp :db/key (or (:db/key cam) (squuid))]
                       [:db/add tmp :camera/scene [:db/key host-scn]]
                       [:db/add tmp :camera/point [0 0]]
                       [:db/add tmp :camera/scale 1]]))))

      :else
      (into [[:db/retractEntity [:db/key scene]]]
            (for [{:keys [db/key]} remove]
              [:db/retractEntity [:db/key key]])))))

;; -- Scene Images --
(defmethod
  ^{:doc "Creates a new scene image with the given checksum, width, and height.
          Relates this entity to the root scene collection."}
  event-tx-fn :scene-images/create
  [_ _ image-data]
  (let [keys [:name :size :checksum :width :height]]
    [{:db/ident :root :root/scene-images (with-ns (select-keys image-data keys) "image")}]))

(defmethod
  ^{:doc "Removes the scene image by the given identifying checksum."}
  event-tx-fn :scene-images/remove
  [_ _ checksum]
  [[:db/retractEntity [:image/checksum checksum]]])

(defmethod
  ^{:doc "Removes all scene images."}
  event-tx-fn :scene-images/remove-all
  []
  [[:db/retract [:db/ident :root] :root/scene-images]])

;; -- Scene --
(defn ^:private assoc-scene
  [data & kvs]
  (let [local (ds/entity data [:db/ident :local])
        scene (:db/key (:camera/scene (:local/camera local)))]
    [(apply assoc {:db/key scene} kvs)]))

(defmethod
  ^{:doc "Updates the image being used for the current scene by the given
          identifying checksum."}
  event-tx-fn :scene/change-image
  [_ _ checksum]
  [[:db.fn/call assoc-scene :scene/image {:image/checksum checksum}]])

(defmethod
  ^{:doc "Updates the grid size for the current scene."}
  event-tx-fn :scene/change-grid-size
  [_ _ size]
  [[:db.fn/call assoc-scene :scene/grid-size size]])

(defmethod
  ^{:doc "Applies both a grid origin and tile size to the current scene."}
  event-tx-fn :scene/apply-grid-options
  [data _ origin size]
  (let [local (ds/entity data [:db/ident :local])]
    [{:db/key (:db/key (:local/camera local))
      :camera/draw-mode :select
      :camera/scene
      {:db/key (:db/key (:camera/scene (:local/camera local)))
       :scene/grid-size size
       :scene/grid-origin origin}}]))

(defmethod
  ^{:doc "Resets the grid origin to (0, 0)."}
  event-tx-fn :scene/reset-grid-origin
  [data]
  (let [local  (ds/entity data [:db/ident :local])
        scene  (:db/key (:camera/scene (:local/camera local)))]
    [[:db.fn/call assoc-camera :camera/draw-mode :select]
     [:db/retract [:db/key scene] :scene/grid-origin]]))

(defmethod
  ^{:doc "Retracts the grid size for the current scene, allowing queries to
          revert to their defaults."}
  event-tx-fn :scene/retract-grid-size
  [data]
  (let [local (ds/entity data [:db/ident :local])
        scene (:db/key (:camera/scene (:local/camera local)))]
    [[:db/retract [:db/key scene] :scene/grid-size]]))

(defmethod
  ^{:doc "Updates whether or not the grid is drawn onto the current scene."}
  event-tx-fn :scene/toggle-show-grid
  [_ _ value]
  [[:db.fn/call assoc-scene :scene/show-grid value]])

(defmethod
  ^{:doc "Updates whether or not dark mode is enabled on the current scene."}
  event-tx-fn :scene/toggle-dark-mode
  [_ _ enabled]
  [[:db.fn/call assoc-scene :scene/dark-mode enabled]])

(defmethod
  ^{:doc "Updates the lighting option used for the current scene."}
  event-tx-fn :scene/change-lighting
  [_ _ value]
  [[:db.fn/call assoc-scene :scene/lighting value]])

(defmethod
  ^{:doc "Updates the time of day option used for the current scene."}
  event-tx-fn :scene/change-time-of-day
  [_ _ value]
  [[:db.fn/call assoc-scene :scene/timeofday value]])

(defmethod event-tx-fn :element/update
  [_ _ keys attr value]
  (for [[id key] (sequence (indexed) keys)]
    (assoc {:db/id id :db/key key} attr value)))

(defmethod event-tx-fn :element/select
  [data _ key replace?]
  (let [local  (ds/entity data [:db/ident :local])
        camera (:local/camera local)
        entity (ds/entity data [:db/key key])]
    [(if replace?
       [:db/retract [:db/key (:db/key camera)] :camera/selected])
     (if (and (not replace?) (:camera/_selected entity))
       [:db/retract [:db/key (:db/key camera)] :camera/selected [:db/key key]]
       {:db/key (:db/key camera) :camera/selected {:db/key key}})]))

(defmethod event-tx-fn :element/remove
  [_ _ keys]
  (for [key keys]
    [:db/retractEntity [:db/key key]]))

(defmethod event-tx-fn :token/create
  [_ _ x y checksum]
  [{:db/id -1
    :db/key (squuid)
    :token/point [(round x) (round y)]
    :token/image {:image/checksum checksum}}
   [:db.fn/call assoc-camera :camera/selected -1 :draw-mode :select]
   [:db.fn/call assoc-scene :scene/tokens -1]])

(defmethod event-tx-fn :token/remove
  [data _ keys]
  (let [local (ds/entity data [:db/ident :local])
        scene (:camera/scene (:local/camera local))
        keys (set keys)
        curr (->> (:initiative/turn scene) :db/key)
        tkns (->> (:scene/initiative scene) (sort initiative-order) (map :db/key))
        tkfn (complement (partial contains? (disj keys curr)))
        next (->> (filter tkfn tkns) (find-next (partial = curr)))
        data {:db/key (:db/key scene) :initiative/turn {:db/key (or next (first tkns))}}]
    (cond-> (for [key keys] [:db/retractEntity [:db/key key]])
      (contains? keys curr) (conj data))))

(defmethod event-tx-fn :token/translate
  [_ _ token x y]
  [{:db/key token :token/point [(round x) (round y)]}])

(defmethod event-tx-fn :token/change-flag
  [data _ keys flag add?]
  (let [idents (map (fn [key] [:db/key key]) keys)
        tokens (ds/pull-many data [:db/key :token/flags] idents)]
    (for [[id {:keys [db/key token/flags] :or {flags #{}}}] (sequence (indexed) tokens)]
      {:db/id id :db/key key :token/flags ((if add? conj disj) flags flag)})))

(defmethod event-tx-fn :token/translate-all
  [data _ keys x y]
  (let [idents (map (fn [key] [:db/key key]) keys)
        tokens (ds/pull-many data [:db/key :token/point] idents)]
    (for [[id {key :db/key [tx ty] :token/point}] (sequence (indexed) tokens)]
      {:db/id id :db/key key :token/point [(round (+ x tx)) (round (+ y ty))]})))

(defmethod event-tx-fn :token/change-label
  [_ _ keys value]
  (for [key keys]
    {:db/key key :token/label (trim value)}))

(defmethod event-tx-fn :token/change-size
  [_ _ keys radius]
  (for [key keys]
    {:db/key key :token/size radius}))

(defmethod event-tx-fn :token/change-light
  [_ _ keys radius]
  (for [key keys]
    {:db/key key :token/light radius}))

(defmethod event-tx-fn :token/change-aura
  [_ _ keys radius]
  (for [key keys]
    {:db/key key :aura/radius radius}))

(defmethod event-tx-fn :shape/create
  [_ _ kind vecs]
  [{:db/id -1 :db/key (squuid) :shape/kind kind :shape/vecs vecs}
   [:db.fn/call assoc-camera :camera/draw-mode :select :camera/selected -1]
   [:db.fn/call assoc-scene :scene/shapes -1]])

(defmethod event-tx-fn :shape/remove
  [_ _ keys]
  (for [key keys]
    [:db/retractEntity [:db/key key]]))

(defmethod event-tx-fn :shape/translate
  [data _ key x y]
  (let [result (ds/pull data [:shape/vecs] [:db/key key])
        {[ax ay] :shape/vecs
         vecs    :shape/vecs} result
        x (round x)
        y (round y)]
    [{:db/key key :shape/vecs (into [x y] (trans-xf (- x ax) (- y ay)) vecs)}]))

(defmethod event-tx-fn :share/initiate [] [])

(defmethod event-tx-fn :share/toggle
  [data _ open?]
  (let [local (ds/entity data [:db/ident :local])]
    [{:db/ident          :local
      :local/sharing?    open?
      :local/paused?     false
      :local/privileged? (and (= (:local/type local) :host) open?)}]))

(defmethod event-tx-fn :share/switch
  [data]
  (let [local (ds/entity data [:db/ident :local])]
    [{:db/ident :local :local/paused? (not (:local/paused? local))}]))

(defmethod event-tx-fn :bounds/change
  [data _ w-type bounds]
  (let [local (ds/entity data [:db/ident :local])]
    [[:db/add -1 :db/ident :local]
     (if (= w-type (:local/type local))
       [:db/add -1 :bounds/self bounds])
     [:db/add -1 (keyword :bounds w-type) bounds]]))

(defmethod event-tx-fn :selection/from-rect
  [data _ vecs]
  (let [local  (ds/entity data [:db/ident :local])
        bounds (normalize vecs)]
    [{:db/key (:db/key (:local/camera local))
      :camera/draw-mode :select
      :camera/selected
      (for [[idx token] (sequence (indexed 2) (:scene/tokens (:camera/scene (:local/camera local))))
            :let  [{[x y] :token/point flags :token/flags key :db/key} token]
            :when (and (within? x y bounds)
                       (or (= (:local/type local) :host)
                           (not (flags :hidden))))]
        {:db/id idx :db/key key})}]))

(defmethod event-tx-fn :selection/clear
  [data]
  (let [local (ds/entity data [:db/ident :local])]
    [[:db/retract [:db/key (:db/key (:local/camera local))] :camera/selected]]))

(defmethod event-tx-fn :selection/remove
  [data]
  (let [local (ds/entity data [:db/ident :local])
        type  (->> (:camera/selected (:local/camera local))
                   (group-by (fn [x] (cond (:scene/_tokens x) :token (:scene/_shapes x) :shape)))
                   (first))]
    (case (key type)
      :token [[:db.fn/call event-tx-fn :token/remove (map :db/key (val type))]]
      :shape [[:db.fn/call event-tx-fn :shape/remove (map :db/key (val type))]])))

(defmethod event-tx-fn :initiative/toggle
  [data _ keys adding?]
  (let [local  (ds/entity data [:db/ident :local])
        scene  (:db/key (:camera/scene (:local/camera local)))
        tokens (map (fn [key] [:db/key key]) keys)
        select [{:token/image [:image/checksum]}
                [:token/flags :default #{}]
                :db/key
                :token/label
                :initiative/suffix]
        result (ds/pull data [{:scene/initiative select}] [:db/key scene])
        change (into #{} (ds/pull-many data select tokens))
        exists (into #{} (:scene/initiative result))]
    (if adding?
      [{:db/key scene
        :scene/initiative
        (let [merge (union exists change)
              sffxs (suffixes merge)]
          (for [[idx token] (sequence (indexed 2) merge) :let [key (:db/key token)]]
            (if-let [suffix (sffxs key)]
              {:db/id idx :db/key key :initiative/suffix suffix}
              {:db/id idx :db/key key})))}]
      (apply concat
             [[:db/add -1 :db/key scene]]
             (for [[idx {key :db/key}] (sequence (indexed 2) change)]
               [[:db/add idx :db/key key]
                [:db/retract [:db/key key] :initiative/suffix]
                [:db/retract [:db/key key] :initiative/roll]
                [:db/retract [:db/key key] :initiative/health]
                [:db/retract [:db/key scene] :scene/initiative idx]])))))

(defmethod event-tx-fn :initiative/next
  [data]
  (let [local (ds/entity data [:db/ident :local])
        scene (:camera/scene (:local/camera local))
        {curr :initiative/turn
         trns :initiative/turns
         rnds :initiative/rounds
         tkns :scene/initiative} scene
        tkns (->> tkns (sort initiative-order) (map :db/key))]
    (if (nil? rnds)
      [{:db/key (:db/key scene)
        :initiative/turn {:db/key (first tkns)}
        :initiative/turns 0
        :initiative/rounds 1}]
      (if-let [next (find-next (partial = (:db/key curr)) tkns)]
        [{:db/key (:db/key scene)
          :initiative/turn {:db/key next}
          :initiative/turns (inc trns)}]
        [{:db/key (:db/key scene)
          :initiative/turn {:db/key (first tkns)}
          :initiative/turns (inc trns)
          :initiative/rounds (inc rnds)}]))))

(defmethod event-tx-fn :initiative/change-roll
  [_ _ key roll]
  (let [parsed (.parseFloat js/window roll)]
    (cond
      (or (nil? roll) (= roll ""))
      [[:db/add -1 :db/key key]
       [:db/retract [:db/key key] :initiative/roll]]

      (.isNaN js/Number parsed)
      []

      :else
      [{:db/id -1 :db/key key :initiative/roll parsed}])))

(defmethod event-tx-fn :initiative/roll-all
  [data]
  (let [local  (ds/entity data [:db/ident :local])
        scene  (:camera/scene (:local/camera local))
        tokens (:scene/initiative scene)]
    (for [[idx token] (sequence (indexed) tokens)
          :let  [{:keys [db/key token/flags initiative/roll]} token]
          :when (and (nil? roll) (not (contains? flags :player)))]
      {:db/id idx :db/key key :initiative/roll (inc (rand-int 20))})))

(defmethod event-tx-fn :initiative/reset
  [data]
  (let [local (ds/entity data [:db/ident :local])
        scene (:camera/scene (:local/camera local))]
    (->> (for [[idx token] (sequence (indexed 2) (:scene/initiative scene))
               :let [{key :db/key} token]]
           [[:db/add idx :db/key key]
            [:db/retract [:db/key key] :initiative/roll]])
         (into [[:db/add -1 :db/key (:db/key scene)]
                [:db/retract [:db/key (:db/key scene)] :initiative/turn]
                [:db/retract [:db/key (:db/key scene)] :initiative/turns]
                [:db/retract [:db/key (:db/key scene)] :initiative/rounds]] cat))))

(defmethod event-tx-fn :initiative/change-health
  [data _ key f value]
  (let [parsed (.parseFloat js/window value)]
    (if (.isNaN js/Number parsed) []
        (let [{:keys [initiative/health]} (ds/entity data [:db/key key])]
          [{:db/id -1 :db/key key :initiative/health (f health parsed)}]))))

(defmethod event-tx-fn :initiative/leave
  [data]
  (let [local (ds/entity data [:db/ident :local])
        scene (:camera/scene (:local/camera local))]
    (apply concat
           [[:db/add -1 :db/key (:db/key scene)]
            [:db/retract [:db/key (:db/key scene)] :scene/initiative]
            [:db/retract [:db/key (:db/key scene)] :initiative/turn]
            [:db/retract [:db/key (:db/key scene)] :initiative/turns]
            [:db/retract [:db/key (:db/key scene)] :initiative/rounds]]
           (for [[idx {key :db/key}] (sequence (indexed 2) (:scene/initiative scene))]
             [[:db/add idx :db/key key]
              [:db/retract [:db/key key] :initiative/roll]
              [:db/retract [:db/key key] :initiative/health]
              [:db/retract [:db/key key] :initiative/suffix]]))))

(defmethod event-tx-fn :tokens/create
  [_ _ image-data scope]
  (let [keys [:name :size :checksum :width :height]
        data (-> image-data (select-keys keys) (assoc :scope scope))]
    [{:db/ident :root :root/token-images (with-ns data "image")}]))

(defmethod
  ^{:doc "Change the scope of the token image by the given checksum to the
          given scope, typically `:public` or `:private`."}
  event-tx-fn :tokens/change-scope
  [_ _ checksum scope]
  [[:db/add -1 :image/checksum checksum]
   [:db/add -1 :image/scope scope]])

(defmethod event-tx-fn :tokens/remove
  [_ _ checksum]
  [[:db/retractEntity [:image/checksum checksum]]])

(defmethod event-tx-fn :tokens/remove-all
  []
  [[:db/retract [:db/ident :root] :root/token-images]])

;; --- Masks ---
(defmethod
  ^{:doc "Sets the current scene to be entirely masked by default. This is
          useful when the scene image is composed of many rooms and mostly
          dead space between them, such as a dungeon, and it is more efficient
          to 'carve out' the scene instead of filling it in."}
  event-tx-fn :mask/fill
  []
  [[:db.fn/call assoc-scene :mask/filled? true]])

(defmethod
  ^{:doc "Sets the current scene to not be entirely masked by default. This
          is the default behavior."}
  event-tx-fn :mask/clear
  []
  [[:db.fn/call assoc-scene :mask/filled? false]])

(defmethod
  ^{:doc "Creates a new mask object for the current scene, accepting its
          current state (hide or reveal) and its polygon points as a flat
          vector of x, y pairs."}
  event-tx-fn :mask/create
  [_ _ state vecs]
  [[:db.fn/call assoc-scene :scene/masks
    {:db/key (squuid) :mask/enabled? state :mask/vecs vecs}]])

(defmethod
  ^{:doc "Toggles the state of the given mask to be either hiding or revealing
          its contents."}
  event-tx-fn :mask/toggle
  [_ _ mask state]
  [{:db/key mask :mask/enabled? state}])

(defmethod
  ^{:doc "Removes the given mask object."}
  event-tx-fn :mask/remove
  [_ _ mask]
  [[:db/retractEntity [:db/key mask]]])

(defmethod event-tx-fn :session/request
  []
  [{:db/ident :root :root/session
    {:db/ident :session :session/host
     {:db/ident :local :session/state :connecting}}}])

(defmethod event-tx-fn :session/join
  []
  [{:db/ident :local :session/state :connecting}])

(defmethod event-tx-fn :session/close
  []
  [{:db/ident :local :session/state :disconnected}
   [:db/retract [:db/ident :session] :session/host]
   [:db/retract [:db/ident :session] :session/conns]])

(defmethod event-tx-fn :session/disconnected
  []
  [{:db/ident :local :session/state :disconnected}
   [:db/retract [:db/ident :session] :session/host]
   [:db/retract [:db/ident :session] :session/conns]])

(defmethod event-tx-fn :session/toggle-share-cursors
  [_ _ enabled]
  [{:db/ident :session :session/share-cursors enabled}])

(defmethod event-tx-fn :session/toggle-share-my-cursor
  [_ _ enabled]
  [{:db/ident :local :local/share-cursor enabled}])

(defmethod event-tx-fn :session/focus
  [data]
  (let [select-w [:camera/scene [:camera/point :default [0 0]] [:camera/scale :default 1]]
        select-l [:db/key [:bounds/self :default [0 0 0 0]] {:local/cameras [:camera/scene] :local/camera select-w}]
        select-s [{:session/host select-l} {:session/conns select-l}]
        result   (ds/pull data select-s [:db/ident :session])
        {{[_ _ hw hh] :bounds/self
          {[hx hy] :camera/point} :local/camera
          host :local/camera} :session/host
         conns :session/conns} result
        scale (:camera/scale host)
        mx (+ (/ hw scale 2) hx)
        my (+ (/ hh scale 2) hy)]
    (->> (for [[next conn] (sequence (indexed 1 2) conns)
               :let [prev (dec next)
                     exst (->> (:local/cameras conn)
                               (filter (fn [conn]
                                         (= (:db/key (:camera/scene conn))
                                            (:db/key (:camera/scene host)))))
                               (first)
                               (:db/key))
                     [_ _ cw ch] (:bounds/self conn)
                     cx (- mx (/ cw scale 2))
                     cy (- my (/ ch scale 2))]]
           [[:db/add next :db/key (:db/key conn)]
            [:db/add next :local/camera prev]
            [:db/add next :local/cameras prev]
            [:db/add prev :db/key        (or exst (squuid))]
            [:db/add prev :camera/point  [cx cy]]
            [:db/add prev :camera/scale  scale]
            [:db/add prev :camera/scene (:db/id (:camera/scene host))]])
         (into [] cat))))

;; -- Clipboard --
(defmethod
  ^{:doc "Copy the currently selected tokens to the clipboard. Optionally
          removes them from the current scene if cut? is passed as true.
          The clipboard contains a template for the token data, and not
          references to the tokens themselves since those references
          don't exist after they are pruned from the scene. Only some token
          data is copied; transient state like that related to initiative is
          not preserved."}
  event-tx-fn :clipboard/copy
  ([_ event]
   [[:db.fn/call event-tx-fn event false]])
  ([data _ cut?]
   (let [attrs  [:token/label :token/flags :token/light :token/size :aura/radius :token/image :token/point]
         select [{:local/camera [{:camera/selected (into attrs [:db/key :scene/_tokens {:token/image [:image/checksum]}])}]}]
         result (ds/pull data select [:db/ident :local])
         tokens (filter (comp-fn contains? identity :scene/_tokens) (:camera/selected (:local/camera result)))
         copies (into [] (map (comp-fn select-keys identity attrs)) tokens)]
     (cond-> []
       (seq tokens)
       (into [{:db/ident :local :local/clipboard copies}])
       (and (seq tokens) cut?)
       (into (for [{key :db/key} tokens]
               [:db/retractEntity [:db/key key]]))))))

(def ^:private clipboard-paste-select
  [{:root/local
    [[:local/clipboard :default []]
     [:bounds/self :default [0 0 0 0]]
     {:local/camera
      [:db/key
       [:camera/scale :default 1]
       [:camera/point :default [0 0]]
       {:camera/scene [:db/key]}]}]}
   {:root/token-images [:image/checksum]}])

(defmethod
  ^{:doc "Creates tokens on the current scene from the data stored in the local
          user's clipboard. Attempts to preserve the relative position of
          the tokens when they were copied but in the center of the user's
          viewport. Clipboard data is not pruned after pasting."}
  event-tx-fn :clipboard/paste
  [data]
  (let [result (ds/pull data clipboard-paste-select [:db/ident :root])
        {{clipboard :local/clipboard
          [_ _ sw sh] :bounds/self
          {camera-key :db/key
           scale :camera/scale
           [cx cy] :camera/point
           {scene-key :db/key} :camera/scene} :local/camera} :root/local
         images :root/token-images} result
        hashes (into #{} (map :image/checksum) images)
        [ax ay bx by] (apply bounding-box (map :token/point clipboard))
        sx (+ (/ sw scale 2) cx)
        sy (+ (/ sh scale 2) cy)
        ox (/ (- ax bx) 2)
        oy (/ (- ay by) 2)]
    (->> (for [[temp token] (sequence (indexed) clipboard)
               :let [[tx ty] (:token/point token)
                     hash    (:image/checksum (:token/image token))
                     data    (merge token {:db/id       temp
                                           :db/key      (squuid)
                                           :token/image [:image/checksum (or (hashes hash) "default")]
                                           :token/point [(+ sx tx ox (- ax)) (+ sy ty oy (- ay))]})]]
           [{:db/key camera-key :camera/selected temp}
            {:db/key scene-key :scene/tokens data}])
         (into [] cat))))

;; -- Shortcuts --
(defmethod
  ^{:doc "Handles the 'Escape' keyboard shortcut, clearing any token
          selections and changing the mode to `select`."}
  event-tx-fn :shortcut/escape
  [data]
  (let [local (ds/entity data [:db/ident :local])
        key   (:db/key (:local/camera local))]
    [{:db/key key :camera/draw-mode :select}
     [:db/retract [:db/key key] :camera/selected]]))