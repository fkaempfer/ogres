(ns ogre.tools.render.layout
  (:require ogre.tools.form.core
            [ogre.tools.render :refer [css use-query]]
            [ogre.tools.render.canvas :refer [canvas]]
            [ogre.tools.render.command :refer [command]]
            [ogre.tools.render.panel :refer [container]]
            [ogre.tools.render.tokens :refer [tokens]]
            [ogre.tools.render.workspaces :refer [workspaces]]))

(def find-spec
  [:viewer/loaded? :viewer/host? :viewer/shortcuts? :viewer/tooltips?])

(defn layout []
  (let [[{:viewer/keys [loaded? host? shortcuts? tooltips?]}] (use-query {:pull find-spec})
        classes
        {:global--host       host?
         :global--guest      (not host?)
         :global--shortcuts  shortcuts?
         :global--tooltips   tooltips?}]
    (if loaded?
      (if host?
        [:div.layout {:class (css classes)}
         [:div.layout-workspaces [workspaces]]
         [:div.layout-canvas [canvas]]
         [:div.layout-command [command]]
         [:div.layout-tokens [tokens]]
         [:div.layout-panel [container]]]
        [:div.layout {:class (css classes)}
         [:div.layout-canvas [canvas]]]))))
