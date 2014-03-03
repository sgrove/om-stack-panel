(ns om-stack-panel.stack-panel
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer put! close!]]
            [om.core :as om]
            [om.dom :as dom]
            [om-stack-panel.scroll-view :as scroll-view])
  (:require-macros [cljs.core.async.macros :as am :refer [go go-loop alt!]]))

(def default-vp-style
  {:overflow-y "scroll"
   :position "absolute"
   :top 0
   :left 0
   :width "100%"})

(defn panel-entry [entry-data owner opts]
  (reify
    om/IDidMount
    (did-mount [c]
      (let [node (om/get-node owner)
            {:keys [comm index]} opts
            height (.. (.getBoundingClientRect node) -height)]
        ;; Use this to report back to StackPanel the rendered-height
        ;; of the component (for varying-height)
        (put! comm [index height])))
    om/IRender
    (render [_]
      (om/build (:item entry-data) (:item-data entry-data) {:opts (:item-options entry-data)}))))

(defn recalc-offsets [all-heights]
  (reduce (fn [heights [new-height _]]
            (let [last-offset (peek (peek heights))
                  last-height (first (last heights))
                  curr-offset (+ last-offset last-height)]
              (if (empty? heights)
                [[new-height 0]]
                (conj heights [new-height curr-offset])))) [] all-heights))

(defn scroll-threshold? [vp-top top-threshold bottom-threshold vp-height percent-trigger & [absolute-bottom]]
  "If the (< (- VP-TOP TOP-THRESHOLD) PERCENT-TRIGGER) of VP-HEIGHT,
   trigger a rerender. Same for VP-BOTTOM (calculated from the last rendered element)."
  (let [vp-bottom  (+ vp-height vp-top)
        px-trigger (* percent-trigger vp-height)]
    (or (and (< 0 top-threshold)
             (< (- vp-top top-threshold) px-trigger))
        (< (- bottom-threshold vp-bottom) px-trigger))))

;; Currently invoked in main_area.cljs
(defn stack-panel [panel-data owner opts]
  (reify
    om/IWillMount
    (will-mount [_]
      ;; Create a channel where the item will notify us of its actual height on render
      ;; Initialize a vector with heights/offsets calculated from user-supplied best-guess.
      ;;   -> Store it in local state
      ;;   -> On notification of render and new height, recalc heights/offsets
      (let [;; Channel for children to notify when they've rendered
            ;; (so we can recalc their y-offset with their actual
            ;; height)
            panel-ch          (chan)
            ;; Parent<->child channel between StackPanel and ScrollView
            pc-ch             (chan)
            height-best-guess (:item-height-best-guess opts)
            entry-count       (count (:items panel-data))]
        (om/set-state! owner :pc-comm pc-ch)
        (om/set-state! owner :panel-entry-comm panel-ch)
        (om/set-state! owner :panel-entry-heights (recalc-offsets (vec (repeat entry-count [height-best-guess 0]))))
        ;; Recalc heights/offsets on render for variable-heights
        (go (while true
              (let [[index height] (<! panel-ch)]
                (let [heights (om/get-state owner :panel-entry-heights)]
                  (om/set-state! owner :panel-entry-heights (recalc-offsets (assoc-in heights [index 0] height)))))))
        ;; Catch scroll events in child scroll-view, update scroll-position locally
        (go (while true
              (let [[msg val] (<! pc-ch)]
                (cond
                 (= msg :scrolled-to) (let [;; Viewport dimensions
                                            ;; (moving window over rendered-view) 
                                            vp-top           val
                                            vp-height        (:vp-height opts)
                                            ;; Rendered-view dimensions
                                            top-threshold    (om/get-state owner :overdraw-top)
                                            bottom-threshold (om/get-state owner :overdraw-bottom)
                                            ;; How close to the edge
                                            ;; of the rendered-view
                                            ;; can the viewport come
                                            ;; before triggering more
                                            ;; renders (and dropping old renders)
                                            percent-trigger  (or (:percent-trigger opts) 0.25)
                                            trigger?         (scroll-threshold? vp-top top-threshold bottom-threshold vp-height percent-trigger)]
                                        (when trigger?
                                          (print "Rerender triggered")
                                          (om/set-state! owner :scroll-position vp-top)))
                 :else (print "Unrecognized message in VirtualStackPanel: " (pr-str msg))))))))
    om/IRender
    (render [this]
      (let [{:keys [item-com items item-options
                    vp-class user-vp-style]} panel-data
            vp-height (:vp-height opts)
            ;; Bubbled up scroll-view
            vp-scroll-offset (or (om/get-state owner :scroll-position) 0)
            item-height      (:item-height-best-guess opts)
            overdraw-factor  (or (:overdraw-factor opts) 2)
            ;; Current estimates on height/offset
            ;; [[item-height item-offset] [... ...]]
            heights (om/get-state owner :panel-entry-heights)
            ;; Visible center of the element
            el-center (+ vp-scroll-offset (/ vp-height 2))
            ;; Determine virtual render bounds
            offset-threshold (* vp-height overdraw-factor)
            top-threshold    (- el-center offset-threshold)
            bottom-threshold (+ el-center offset-threshold)
            [top-el-idx top-el-off] (or (last (keep-indexed (fn [i [h o]] (when (< o top-threshold) [i o])) heights))
                                        [0 0])
            [btm-el-idx btm-el-ht btm-el-off] (last (keep-indexed (fn [i [h o]] (when (< o bottom-threshold) [i h o])) heights))
            M (- btm-el-idx top-el-idx)
            N top-el-idx ;; Draw half above, and half below
            comm (om/get-state owner :panel-entry-comm)
            ;; Render panel-entry, which renders item for us
            render-item (fn [[idx com-data [height offset]]]
                          (dom/div
                           #js {:key offset
                                :style #js {:position "absolute"
                                            :left 0
                                            :top offset
                                            :width "100%"
                                            :font-decoration "bold"}}
                           (om/build panel-entry
                                     {:item item-com
                                      :item-data com-data
                                      :item-options item-options}
                                     {:opts {:index idx
                                             :comm comm}})))
            idx-data-offset (map vector (range) items heights)]
        ;; Store the boundaries of what we'll rendered, iff
        ;; they're different from what's already stored (to
        ;; prevent continual rerendering)
        (when (or (not= (om/get-state owner :overdraw-top) top-el-off)
                  (not= (om/get-state owner :overdraw-bottom) (+ btm-el-ht btm-el-off)))
          (print "Rendering boundaries changed, updating")
          (om/set-state! owner :overdraw-top top-el-off)
          (om/set-state! owner :overdraw-bottom (+ btm-el-ht btm-el-off)))
        (om/build scroll-view/scroll-view (om/graft {:vp-class vp-class
                                          :vp-height vp-height
                                          :user-vp-style user-vp-style
                                          :render-fn render-item
                                          :items (->> idx-data-offset
                                                      (drop N)
                                                      (take M))}
                                         panel-data)
                  {:opts {:comm (om/get-state owner :pc-comm)}})))))
