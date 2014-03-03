(ns om-stack-panel.place-holder
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]))

(def colors
  ["pink" "green" "yellow" "white"])

(defn place-holder [data owner opts]
  (reify
    om/IRender
    (render [_]
      (dom/div #js {:style
                    #js {:height (:height data)
                         :background-color (:background-color data)
                         :border "1px solid black"
                         :border-radius 4
                         :font-family "sans-serif"
                         :text-align "center"
                         :font-size "-webkit-xxx-large"}}
               (str (:counter data) " -  Place Holder")))))

(defn rand-place-holder-setting [counter]
  {:counter          counter
   :height           65
   :background-color (rand-nth colors)})
