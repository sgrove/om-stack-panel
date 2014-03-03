(ns om-stack-panel.core
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [om-stack-panel.place-holder :as ph]
            [om-stack-panel.scroll-view :as sv]
            [om-stack-panel.fixed-height-stack-panel :as fixed-sp]
            [om-stack-panel.stack-panel :as sp]))

(enable-console-print!)

(def app-state (atom {:text "Hello world!"
                      :items (map ph/rand-place-holder-setting (range 1 10501))}))

(def implementation
  ;:scroll-view
  ;:stack-panel
  :fixed-stack-panel
  )

(om/root
  (fn [app owner]
    (dom/div nil
     (dom/h1 nil "StackPanel Playground")
     (cond
      (= implementation :scroll-view) (dom/div nil
                                               (dom/h2 nil "Scroll-View (no virtualization)")
                                               (dom/div #js {:style #js {:position "absolute"
                                                                         :top 50
                                                                         :width "100%"
                                                                         :height 640}}
                                                        (om/build sv/scroll-view
                                                                  (om/graft {:scroll-position   0
                                                                             :vp-class           "paginated-activities"
                                                                             :vp-height          640
                                                                             :vp-oversized-child 10000
                                                                             :render-fn         #(om/build ph/place-holder %)
                                                                             :items              (:items app)}
                                                                            app))))
      (= implementation :fixed-stack-panel) (dom/div nil
                                                     (dom/h2 nil "FixedStackPanel")
                                                     (dom/div #js {:style #js {:position "absolute"
                                                                               :top 50
                                                                               :width "100%"
                                                                               :height 640}}
                                                              (om/build fixed-sp/fixed-stack-panel
                                                                        (om/graft {:scroll-position 0
                                                                                   :vp-class        "paginated-activities"
                                                                                   :item-com        ph/place-holder
                                                                                   :items           (:items app)
                                                                                   :item-options    {}}
                                                                                  app)
                                                                        {:opts {;; Initial guess for spacing to minimize pop on
                                                                                ;; render
                                                                                :item-height-best-guess 65
                                                                                ;; Try 1/2/3 for for overdraw in both
                                                                                ;; direction. Too big will cause large
                                                                                ;; pauses at the edges. < 1 will show
                                                                                ;; a smalller window, but has a lot of
                                                                                ;; rendering glitches
                                                                                :overdraw-factor 4
                                                                                ;; TODO: Handle resize
                                                                                :vp-height 640}})))
      :else (dom/div nil
                     (dom/h2 nil "StackPanel")
                     (dom/div #js {:style #js {:position "absolute"
                                               :top 50
                                               :width "100%"
                                               :height 640}}
                              (om/build sp/stack-panel
                                        (om/graft {:scroll-position 0
                                                   :vp-class        "paginated-activities"
                                                   :item-com        ph/place-holder
                                                   :items           (:items app)
                                                   :item-options    {}}
                                                  app)
                                        {:opts {;; Initial guess for spacing to minimize pop on
                                                ;; render
                                                :item-height-best-guess 65
                                                ;; Try 1/2/3 for for overdraw in both
                                                ;; direction. Too big will cause large
                                                ;; pauses at the edges. < 1 will show
                                                ;; a smalller window, but has a lot of
                                                ;; rendering glitches
                                                :overdraw-factor 1.5
                                                ;; TODO: Handle resize
                                                :vp-height 640}}))))))
  app-state
  {:target (. js/document (getElementById "app"))})
