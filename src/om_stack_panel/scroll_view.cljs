(ns om-stack-panel.scroll-view
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer put! close!]]
            [om.core :as om]
            [om.dom :as dom])
  (:require-macros [cljs.core.async.macros :as am :refer [go go-loop alt!]]))

(def default-vp-style
  {:overflow-y "scroll"
   :position "absolute"
   :top 0
   :left 0
   :width "100%"})

(defn scroll-view [data owner opts]
  (reify
    om/IWillMount
    (will-mount [c]
      (let [scroll-ch (chan)
            parent-ch (:comm opts)]
        (om/set-state! owner :scroll-ch scroll-ch)
        (go (while true
              (let [val (<! scroll-ch)]
                (put! parent-ch [:scrolled-to val]))))))
    om/IRender
    (render [_]
      (let [{:keys
             [vp-class   vp-oversized-child
              vp-height  user-vp-style
              render-fn  items]} data
            vp-style (clj->js (merge default-vp-style
                                     {:height vp-height
                                      :background-color "red"}
                                     user-vp-style))
            ;; Child of div with overflow-y: scroll
            vp-oversized-child-height 10000
            scroll-ch (om/get-state owner :scroll-ch)]
        (dom/div nil
                 (dom/div #js {:className (str "scroll-panel-view " vp-class)
                               :style vp-style
                               :onScroll #(put! scroll-ch (-> (om/get-node owner)
                                                              (.-children)
                                                              (aget 0)
                                                              (.-scrollTop)))}
                          (apply dom/div
                                 (concat [#js{:className "vp-oversized-child"
                                              :style #js {:height vp-oversized-child-height}}]
                                         ;; Render all of the
                                         ;; appropriate panel-entries
                                         ;; with items into
                                         ;; vp-oversized-child
                                         (map render-fn items)))))))))

