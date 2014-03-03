(ns om-stack-panel.fixed-height-stack-panel
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer put! close!]]
            [om.core :as om]
            [om.dom :as dom]
            [om-stack-panel.scroll-view :as scroll-view])
  (:require-macros [cljs.core.async.macros :as am :refer [go go-loop alt!]]))

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

(defn scroll-threshold? [vp-top top-threshold bottom-threshold vp-height percent-trigger & [absolute-bottom]]
  "If the (< (- VP-TOP TOP-THRESHOLD) PERCENT-TRIGGER) of VP-HEIGHT,
   trigger a rerender. Same for VP-BOTTOM (calculated from the last rendered element)."
  (let [vp-bottom  (+ vp-height vp-top)
        px-trigger (* percent-trigger vp-height)]
    (or (and (< 0 top-threshold)
             (< (- vp-top top-threshold) px-trigger))
        (< (- bottom-threshold vp-bottom) px-trigger))))

;; Currently invoked in main_area.cljs
(defn fixed-stack-panel [panel-data owner opts]
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
            ;; Visible center of the element
            el-center (+ vp-scroll-offset (/ vp-height 2))
            ;; Determine virtual render bounds
            offset-threshold (* vp-height overdraw-factor)
            top-threshold    (- el-center offset-threshold)
            bottom-threshold (+ el-center offset-threshold)

            top-el-idx (js/Math.floor (/ top-threshold item-height))
            btm-el-idx (js/Math.floor (/ bottom-threshold item-height))

            top-el-off top-threshold
            btm-el-off bottom-threshold
            M (- btm-el-idx top-el-idx)
            N top-el-idx ;; Draw half above, and half below
            comm (om/get-state owner :panel-entry-comm)
            ;; Render panel-entry, which renders item for us
            render-item (fn [[idx com-data]]
                          (dom/div
                           #js {:key idx
                                :style #js {:position "absolute"
                                            :left 0
                                            :top (* idx item-height)
                                            :width "100%"
                                            :font-decoration "bold"}}
                           (om/build panel-entry
                                     {:item item-com
                                      :item-data com-data
                                      :item-options item-options}
                                     {:opts {:index idx
                                             :comm comm}})))
            idx-data-offset (map vector (range) items)]
        ;; Store the boundaries of what we'll rendered, iff
        ;; they're different from what's already stored (to
        ;; prevent continual rerendering)
        (when (or (not= (om/get-state owner :overdraw-top) (- top-threshold item-height))
                  (not= (om/get-state owner :overdraw-bottom) (+ bottom-threshold item-height)))
          (print "(not= " (om/get-state owner :overdraw-top) " " (- top-threshold item-height) ")")
          (print "(not= " (om/get-state owner :overdraw-bottom) " " (- bottom-threshold item-height) ")")
          (print "Rendering boundaries changed, updating")
          (om/set-state! owner :overdraw-top (- top-threshold item-height))
          (om/set-state! owner :overdraw-bottom (+ bottom-threshold item-height)))
        (om/build scroll-view/scroll-view (om/graft {:vp-class vp-class
                                          :vp-height vp-height
                                          :user-vp-style user-vp-style
                                          :render-fn render-item
                                          :items (->> idx-data-offset
                                                      (drop N)
                                                      (take M))}
                                         panel-data)
                  {:opts {:comm (om/get-state owner :pc-comm)}})))))
