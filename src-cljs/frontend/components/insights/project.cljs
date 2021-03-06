(ns frontend.components.insights.project
  (:require [frontend.components.common :as common]
            [frontend.components.insights :as insights]
            [frontend.config :as config]
            [frontend.datetime :as datetime]
            [frontend.models.project :as project-model]
            [frontend.routes :as routes]
            [frontend.state :as state]
            [frontend.async :refer [raise!]]
            [frontend.api :as api]
            [om.core :as om :include-macros true]
            [schema.core :as s :include-macros true]
            [cljs-time.core :as time]
            [cljs-time.format :as time-format]
            [devcards.core :as dc :refer-macros [defcard]]
            cljsjs.c3)
  (:require-macros [frontend.utils :refer [html defrender]]))

(def build-time-bar-chart-plot-info
  {:top 30
   :right 10
   :bottom 10
   :left 30
   :max-bars 100
   :positive-y% 0.6
   :show-bar-title? false
   :left-legend-items [{:classname "success"
                        :text "Passed"}
                       {:classname "failed"
                        :text "Failed"}
                       {:classname "canceled"
                        :text "Canceled"}]
   :right-legend-items [{:classname "queue"
                         :text "Queue time"}]
   :legend-info {:top 22
                 :square-size 10
                 :item-width 80
                 :item-height 14   ; assume font is 14px
                 :spacing 4}})


(defn build-time-line-chart [timing-data owner]
  (let [median-build-time-millis (map :median_build_time_millis timing-data)
        dates (map (comp time-format/parse :date) timing-data)
        columns [(cons "date" dates)
                 (cons "Median Build Time" median-build-time-millis)]]
   (reify
     om/IDidUpdate
     (did-update [_ _ _]
       (let [chart (om/get-state owner :chart)]
         (.load chart (clj->js {:x "date"
                                :columns columns}))))
     om/IDidMount
     (did-mount [_]
       (let [el (om/get-node owner)
             max-y (apply max median-build-time-millis)
             max-y-mins (-> max-y
                            (/ 60000)
                            (js/Math.ceil))
             max-y-mins-millis (* 60000 max-y-mins)
             max-y-ticks 6
             ;; 1 tick for each minute in max-y, maximum of max-y-ticks
             y-ticks (min max-y-mins max-y-ticks)
             y-axis-values (map #(-> %
                                     (/ y-ticks)
                                     (* max-y-mins-millis))
                                ;; a range that ends with y-ticks and
                                ;; doesn't begin with 0
                                (-> y-ticks inc range rest))]
         (om/set-state! owner :chart
                        (js/c3.generate (clj->js {:bindto el
                                                  :padding {:top 10
                                                            :right 20}
                                                  :data {:x "date"
                                                         :columns columns}
                                                  :legend {:hide true}
                                                  :grid {:y {:show true}}
                                                  :axis {:x {:padding {:left "0"}
                                                             :type "timeseries"
                                                             :tick {:format "%m/%d"}
                                                             :fit "true"}
                                                         :y {:min 0
                                                             :tick {:format datetime/millis-to-nice-duration
                                                                    :values y-axis-values}
                                                             :padding {:bottom 0}}}
                                                  :tooltip {:format {:value datetime/millis-to-nice-duration}}})))))
     om/IRender
     (render [_]
       (html
        [:div])))))


(s/defn build-status-bar-chart-hovercard [build :- insights/BarChartableBuild]
  (html
   [:div {:data-component `build-status-bar-chart-hovercard}

   [:div.insights-metadata
    [:div.metadata-row
     [:div.metadata-item
      [:i.material-icons "radio_button_checked"]
      (:outcome build)]
     [:div.metadata-item.recent-time
      [:i.material-icons "today"]
      (om/build common/updating-duration
               {:start (:start_time build)}
               {:opts {:formatter datetime/time-ago-abbreviated}}) " ago"]
     [:div.metadata-item.recent-time
      [:i.material-icons "timer"]
      (first (datetime/millis-to-float-duration (:build_time_millis build)))]
     [:div.metadata-item.recent-time
      [:i.material-icons "hourglass_empty"]
      (first (datetime/millis-to-float-duration (:queued_time_millis build)))]
     [:div.metadata-item
      [:i.material-icons "storage"]
      (:build_num build)]]]]))

(defn build-status-bar-chart [{:keys [plot-info builds]} owner]
  (reify
    om/IInitState
    (init-state [_]
      {:focused-build nil
       :mouse-location nil})
    om/IRenderState
    (render-state [_ {focused-build :focused-build
                      [x y] :mouse-location}]
      (html
       [:div {:data-component (str `build-status-bar-chart)
              :style {:position "relative"}}
        (om/build insights/build-status-bar-chart {:plot-info plot-info
                                                   :builds builds
                                                   :on-focus-build #(om/set-state! owner :focused-build %)
                                                   :on-mouse-move #(om/set-state! owner :mouse-location %)})
        [:div.hovercard {:style {:position "absolute" :left x :top y}}
         (when focused-build
           (build-status-bar-chart-hovercard (js->clj focused-build :keywordize-keys true)))]]))))

(defrender project-insights [state owner]
  (let [projects (get-in state state/projects-path)
        plans (get-in state state/user-plans-path)
        {:keys [branch] :as navigation-data} (:navigation-data state)
        {:keys [branches parallel] :as project} (some->> projects
                                                         (filter #(= (dissoc (api/project-build-key %) :branch)
                                                                     (dissoc (api/project-build-key navigation-data) :branch)))
                                                         first)
        timing-data (get-in project [:build-timing branch])
        chartable-builds (some->> (get (:recent-builds project) branch)
                                  (filter insights/build-chartable?))
        bar-chart-builds (->> chartable-builds
                              (take (:max-bars build-time-bar-chart-plot-info))
                              (map insights/add-queued-time))]
    (html
     [:div.insights-project
      [:div.insights-metadata-header
       [:div.card.insights-metadata
        [:dl
         [:dt "last build"]
         [:dd (om/build common/updating-duration
                        {:start (->> chartable-builds
                                     (filter :start_time)
                                     first
                                     :start_time)}
                        {:opts {:formatter datetime/as-time-since
                                :formatter-use-start? true}})]]]
       [:div.card.insights-metadata
        [:dl
         [:dt "median build time"]
         [:dd (datetime/as-duration (insights/median (map :build_time_millis bar-chart-builds))) " min"]]]
       [:div.card.insights-metadata
        [:dl
         [:dt "median queue time"]
         [:dd (datetime/as-duration (insights/median (map :queued_time_millis bar-chart-builds))) " min"]]]
       [:div.card.insights-metadata
        [:dl
         [:dt "current parallelism"]
         [:dd parallel
          (when (project-model/can-write-settings? project)
           [:a.btn.btn-xs.btn-default {:href (routes/v1-project-settings-path {:org (:username project)
                                                                               :repo (:reponame project)
                                                                               :_fragment "parallel-builds"})
                                       :on-click #((om/get-shared owner :track-event) {:event-type :parallelism-clicked})}
            [:i.material-icons "tune"]])]]]]
      [:div.card
       [:div.card-header
        [:h2 "Build Status"]]
       [:div.card-body
        (if (nil? chartable-builds)
          [:div.loading-spinner common/spinner]
          (om/build build-status-bar-chart {:plot-info build-time-bar-chart-plot-info
                                            :builds (reverse bar-chart-builds)}))]]
      [:div.card
       [:div.card-header
        [:h2 "Build Performance"]]
       [:div.card-body
        {:class (when (empty? timing-data)
                  "no-chart")}
        (cond (nil? timing-data) [:div.loading-spinner common/spinner]
              (empty? timing-data) [:div.no-builds.no-insights "No builds in the last 90 days."]
              :else
              (om/build build-time-line-chart timing-data))]]])))

(defrender header [state owner]
  (let [projects (get-in state state/projects-path)
        {selected-branch :branch :as navigation-data} (:navigation-data state)
        {:keys [branches] :as project} (some->> projects
                                                (filter #(and (= (:reponame %) (:repo navigation-data))
                                                              (= (:username %) (:org navigation-data))))
                                                first)
        other-branches (->> branches
                            keys
                            (map name)
                            (remove (partial = selected-branch))
                            sort)]
    (html
     [:.insights-branch-picker
      [:select {:name "insights-branch-picker"
                :required true
                :on-change #(raise! owner [:project-insights-branch-changed {:new-branch (.. % -target -value)}])
                :value ""}
       (cons
        [:option {:value ""
                  :disabled true
                  :hidden true}
         "Change branch"]
        (for [branch-name other-branches]
          [:option {:value branch-name} branch-name]))]])))

(when config/client-dev?
  ;; TODO: Auto-generate build data (perhaps with Schema + test.check).
  (def some-builds
    [{:build_num 136387, :start_time "2016-02-03T18:32:47.650Z", :build_time_millis 1440091, :queued_time_millis 0, :outcome "success"} {:build_num 136402, :start_time "2016-02-03T22:07:24.437Z", :build_time_millis 2512182, :queued_time_millis 0, :outcome "success"} {:build_num 136408, :start_time "2016-02-04T00:10:18.624Z", :build_time_millis 1350720, :queued_time_millis 0, :outcome "success"} {:build_num 136416, :start_time "2016-02-04T01:46:39.151Z", :build_time_millis 2408743, :queued_time_millis 6260, :outcome "success"} {:build_num 136428, :start_time "2016-02-04T07:03:09.156Z", :build_time_millis 782560, :queued_time_millis 0, :outcome "failed"} {:build_num 136430, :start_time "2016-02-04T07:19:58.545Z", :build_time_millis 1345931, :queued_time_millis 0, :outcome "success"} {:build_num 136438, :start_time "2016-02-04T09:05:04.913Z", :build_time_millis 1758123, :queued_time_millis 0, :outcome "success"} {:build_num 136443, :start_time "2016-02-04T10:12:21.666Z", :build_time_millis 1436773, :queued_time_millis 2039, :outcome "success"} {:build_num 136448, :start_time "2016-02-04T10:37:12.887Z", :build_time_millis 2261798, :queued_time_millis 5550, :outcome "success"} {:build_num 136454, :start_time "2016-02-04T11:03:41.223Z", :build_time_millis 1038578, :queued_time_millis 536, :outcome "failed"} {:build_num 136455, :start_time "2016-02-04T11:24:46.375Z", :build_time_millis 2149304, :queued_time_millis 0, :outcome "success"} {:build_num 136459, :start_time "2016-02-04T11:41:05.936Z", :build_time_millis 1456182, :queued_time_millis 0, :outcome "success"} {:build_num 136463, :start_time "2016-02-04T14:03:33.271Z", :build_time_millis 614985, :queued_time_millis 0, :outcome "failed"} {:build_num 136465, :start_time "2016-02-04T14:16:10.803Z", :build_time_millis 701386, :queued_time_millis 388, :outcome "failed"} {:build_num 136466, :start_time "2016-02-04T14:16:56.349Z", :build_time_millis 1327913, :queued_time_millis 0, :outcome "success"} {:build_num 136467, :start_time "2016-02-04T14:23:05.392Z", :build_time_millis 1442550, :queued_time_millis 0, :outcome "success"} {:build_num 136471, :start_time "2016-02-04T14:40:51.772Z", :build_time_millis 2792335, :queued_time_millis 6216, :outcome "success"} {:build_num 136472, :start_time "2016-02-04T15:19:15.650Z", :build_time_millis 1334421, :queued_time_millis 7424, :outcome "success"} {:build_num 136483, :start_time "2016-02-04T17:46:47.650Z", :build_time_millis 2308508, :queued_time_millis 9785, :outcome "success"} {:build_num 136487, :start_time "2016-02-04T19:31:21.414Z", :build_time_millis 2213050, :queued_time_millis 0, :outcome "success"} {:build_num 136489, :start_time "2016-02-04T19:44:00.939Z", :build_time_millis 2278400, :queued_time_millis 15842, :outcome "success"} {:build_num 136492, :start_time "2016-02-04T20:22:38.741Z", :build_time_millis 605135, :queued_time_millis 0, :outcome "failed"} {:build_num 136493, :start_time "2016-02-04T20:46:53.241Z", :build_time_millis 1482786, :queued_time_millis 0, :outcome "success"} {:build_num 136494, :start_time "2016-02-04T20:55:01.408Z", :build_time_millis 1478069, :queued_time_millis 0, :outcome "success"} {:build_num 136500, :start_time "2016-02-04T21:18:44.939Z", :build_time_millis 1366314, :queued_time_millis 0, :outcome "success"} {:build_num 136501, :start_time "2016-02-04T21:22:16.081Z", :build_time_millis 2383971, :queued_time_millis 3143, :outcome "success"} {:build_num 136508, :start_time "2016-02-04T23:01:10.542Z", :build_time_millis 1499119, :queued_time_millis 2453, :outcome "success"} {:build_num 136511, :start_time "2016-02-04T23:13:54.608Z", :build_time_millis 2353609, :queued_time_millis 0, :outcome "success"} {:build_num 136515, :start_time "2016-02-05T00:05:43.286Z", :build_time_millis 1519625, :queued_time_millis 1533, :outcome "success"} {:build_num 136542, :start_time "2016-02-05T11:14:05.785Z", :build_time_millis 821522, :queued_time_millis 94, :outcome "failed"} {:build_num 136544, :start_time "2016-02-05T11:39:37.596Z", :build_time_millis 1449719, :queued_time_millis 342, :outcome "success"} {:build_num 136547, :start_time "2016-02-05T13:15:00.536Z", :build_time_millis 1364707, :queued_time_millis 2607, :outcome "success"} {:build_num 136555, :start_time "2016-02-05T18:25:14.524Z", :build_time_millis 1380441, :queued_time_millis 538, :outcome "success"} {:build_num 136561, :start_time "2016-02-05T19:32:52.989Z", :build_time_millis 2225624, :queued_time_millis 0, :outcome "success"} {:build_num 136568, :start_time "2016-02-05T21:26:49.776Z", :build_time_millis 2274118, :queued_time_millis 0, :outcome "success"} {:build_num 136577, :start_time "2016-02-05T22:44:33.495Z", :build_time_millis 1333993, :queued_time_millis 116, :outcome "success"} {:build_num 136588, :start_time "2016-02-06T00:10:13.644Z", :build_time_millis 1575458, :queued_time_millis 1048, :outcome "failed"} {:build_num 136591, :start_time "2016-02-06T01:01:40.468Z", :build_time_millis 2638465, :queued_time_millis 1876, :outcome "success"} {:build_num 136594, :start_time "2016-02-06T03:30:44.907Z", :build_time_millis 549724, :queued_time_millis 1377, :outcome "failed"} {:build_num 136595, :start_time "2016-02-06T20:37:20.041Z", :build_time_millis 1277695, :queued_time_millis 1991, :outcome "success"} {:build_num 136605, :start_time "2016-02-07T21:38:33.162Z", :build_time_millis 1369645, :queued_time_millis 3028, :outcome "success"} {:build_num 136607, :start_time "2016-02-08T01:06:23.541Z", :build_time_millis 1483677, :queued_time_millis 0, :outcome "success"} {:build_num 136609, :start_time "2016-02-08T07:58:32.090Z", :build_time_millis 2216233, :queued_time_millis 878, :outcome "success"} {:build_num 136613, :start_time "2016-02-08T13:10:01.124Z", :build_time_millis 26495, :queued_time_millis 0, :outcome "canceled"} {:build_num 136615, :start_time "2016-02-08T13:10:31.057Z", :build_time_millis 34021, :queued_time_millis 569, :outcome "canceled"} {:build_num 136616, :start_time "2016-02-08T13:12:10.087Z", :build_time_millis 2238584, :queued_time_millis 0, :outcome "success"} {:build_num 136622, :start_time "2016-02-08T14:56:02.751Z", :build_time_millis 570396, :queued_time_millis 1302, :outcome "failed"} {:build_num 136625, :start_time "2016-02-08T17:27:41.909Z", :build_time_millis 1567314, :queued_time_millis 0, :outcome "success"} {:build_num 136638, :start_time "2016-02-08T18:43:53.544Z", :build_time_millis 2514016, :queued_time_millis 0, :outcome "success"} {:build_num 136646, :start_time "2016-02-08T19:12:27.630Z", :build_time_millis 619856, :queued_time_millis 0, :outcome "failed"} {:build_num 136652, :start_time "2016-02-08T20:26:06.027Z", :build_time_millis 2283665, :queued_time_millis 0, :outcome "success"} {:build_num 136653, :start_time "2016-02-08T20:37:57.118Z", :build_time_millis 2223032, :queued_time_millis 0, :outcome "success"} {:build_num 136658, :start_time "2016-02-08T21:12:37.182Z", :build_time_millis 1555312, :queued_time_millis 4589, :outcome "success"} {:build_num 136659, :start_time "2016-02-08T21:13:47.924Z", :build_time_millis 1586973, :queued_time_millis 5671, :outcome "success"} {:build_num 136668, :start_time "2016-02-08T22:32:07.831Z", :build_time_millis 2411158, :queued_time_millis 2084, :outcome "success"} {:build_num 136673, :start_time "2016-02-09T00:03:33.778Z", :build_time_millis 1429288, :queued_time_millis 0, :outcome "success"} {:build_num 136683, :start_time "2016-02-09T10:12:43.440Z", :build_time_millis 595483, :queued_time_millis 6669, :outcome "failed"} {:build_num 136685, :start_time "2016-02-09T11:54:34.511Z", :build_time_millis 571798, :queued_time_millis 0, :outcome "failed"} {:build_num 136686, :start_time "2016-02-09T11:58:14.115Z", :build_time_millis 767901, :queued_time_millis 0, :outcome "failed"} {:build_num 136691, :start_time "2016-02-09T13:57:13.674Z", :build_time_millis 750969, :queued_time_millis 5182, :outcome "failed"} {:build_num 136710, :start_time "2016-02-09T17:50:53.982Z", :build_time_millis 655806, :queued_time_millis 0, :outcome "failed"} {:build_num 136711, :start_time "2016-02-09T18:00:08.725Z", :build_time_millis 741390, :queued_time_millis 7825, :outcome "failed"} {:build_num 136713, :start_time "2016-02-09T18:20:43.342Z", :build_time_millis 707376, :queued_time_millis 0, :outcome "failed"} {:build_num 136715, :start_time "2016-02-09T19:04:45.744Z", :build_time_millis 588979, :queued_time_millis 0, :outcome "failed"} {:build_num 136719, :start_time "2016-02-09T19:45:45.230Z", :build_time_millis 1528867, :queued_time_millis 0, :outcome "success"} {:build_num 136729, :start_time "2016-02-09T21:13:52.521Z", :build_time_millis 1400824, :queued_time_millis 2144, :outcome "success"} {:build_num 136730, :start_time "2016-02-09T21:14:07.542Z", :build_time_millis 1348176, :queued_time_millis 0, :outcome "success"} {:build_num 136732, :start_time "2016-02-09T21:41:47.164Z", :build_time_millis 2282992, :queued_time_millis 0, :outcome "success"} {:build_num 136735, :start_time "2016-02-09T22:05:06.923Z", :build_time_millis 2410099, :queued_time_millis 1609, :outcome "success"} {:build_num 136736, :start_time "2016-02-09T22:12:47.078Z", :build_time_millis 1393451, :queued_time_millis 3188, :outcome "success"} {:build_num 136747, :start_time "2016-02-10T01:14:36.812Z", :build_time_millis 2593790, :queued_time_millis 5860, :outcome "failed"} {:build_num 136763, :start_time "2016-02-10T06:36:09.889Z", :build_time_millis 2000583, :queued_time_millis 0, :outcome "success"} {:build_num 136766, :start_time "2016-02-10T09:45:26.780Z", :build_time_millis 2304316, :queued_time_millis 0, :outcome "success"} {:build_num 136768, :start_time "2016-02-10T10:28:48.413Z", :build_time_millis 1453009, :queued_time_millis 1367, :outcome "success"} {:build_num 136774, :start_time "2016-02-10T11:57:41.117Z", :build_time_millis 757174, :queued_time_millis 0, :outcome "failed"} {:build_num 136776, :start_time "2016-02-10T14:16:33.297Z", :build_time_millis 2375650, :queued_time_millis 0, :outcome "success"} {:build_num 136780, :start_time "2016-02-10T14:54:55.685Z", :build_time_millis 2245985, :queued_time_millis 0, :outcome "success"} {:build_num 136790, :start_time "2016-02-10T18:20:25.068Z", :build_time_millis 2242146, :queued_time_millis 0, :outcome "success"} {:build_num 136791, :start_time "2016-02-10T18:20:44.328Z", :build_time_millis 542344, :queued_time_millis 0, :outcome "failed"} {:build_num 136795, :start_time "2016-02-10T18:49:24.316Z", :build_time_millis 1552550, :queued_time_millis 7399, :outcome "success"} {:build_num 136799, :start_time "2016-02-10T19:50:38.170Z", :build_time_millis 2349278, :queued_time_millis 0, :outcome "success"} {:build_num 136806, :start_time "2016-02-10T20:26:56.758Z", :build_time_millis 549855, :queued_time_millis 5632, :outcome "failed"} {:build_num 136815, :start_time "2016-02-10T21:50:51.573Z", :build_time_millis 1460915, :queued_time_millis 387, :outcome "success"} {:build_num 136818, :start_time "2016-02-10T21:59:36.952Z", :build_time_millis 572433, :queued_time_millis 2746, :outcome "failed"} {:build_num 136838, :start_time "2016-02-10T23:47:48.011Z", :build_time_millis 1549355, :queued_time_millis 3767, :outcome "success"} {:build_num 136843, :start_time "2016-02-11T00:12:27.757Z", :build_time_millis 840106, :queued_time_millis 0, :outcome "failed"} {:build_num 136852, :start_time "2016-02-11T01:04:28.936Z", :build_time_millis 846650, :queued_time_millis 5603, :outcome "failed"} {:build_num 136855, :start_time "2016-02-11T01:33:05.906Z", :build_time_millis 627238, :queued_time_millis 0, :outcome "failed"} {:build_num 136857, :start_time "2016-02-11T02:37:35.634Z", :build_time_millis 560272, :queued_time_millis 0, :outcome "failed"} {:build_num 136858, :start_time "2016-02-11T02:46:32.517Z", :build_time_millis 753217, :queued_time_millis 0, :outcome "failed"} {:build_num 136859, :start_time "2016-02-11T03:06:52.372Z", :build_time_millis 830567, :queued_time_millis 0, :outcome "failed"} {:build_num 136863, :start_time "2016-02-11T06:50:01.957Z", :build_time_millis 801412, :queued_time_millis 0, :outcome "failed"} {:build_num 136865, :start_time "2016-02-11T07:27:45.797Z", :build_time_millis 746620, :queued_time_millis 11396, :outcome "failed"} {:build_num 136867, :start_time "2016-02-11T07:48:22.626Z", :build_time_millis 1417994, :queued_time_millis 0, :outcome "success"} {:build_num 136870, :start_time "2016-02-11T08:44:40.215Z", :build_time_millis 1374758, :queued_time_millis 0, :outcome "success"} {:build_num 136872, :start_time "2016-02-11T10:56:40.333Z", :build_time_millis 743735, :queued_time_millis 8017, :outcome "failed"} {:build_num 136873, :start_time "2016-02-11T11:16:16.022Z", :build_time_millis 1372018, :queued_time_millis 0, :outcome "success"} {:build_num 136878, :start_time "2016-02-11T12:05:34.787Z", :build_time_millis 1438399, :queued_time_millis 4568, :outcome "success"} {:build_num 136882, :start_time "2016-02-11T13:16:45.071Z", :build_time_millis 1514180, :queued_time_millis 0, :outcome "success"} {:build_num 136883, :start_time "2016-02-11T13:23:13.646Z", :build_time_millis 2253598, :queued_time_millis 1417, :outcome "success"}])

  (defcard build-status-bar-chart
    (om/build build-status-bar-chart {:plot-info build-time-bar-chart-plot-info
                                      :builds some-builds}))

  (defcard build-status-bar-chart-hovercard
    (build-status-bar-chart-hovercard (first some-builds))))
