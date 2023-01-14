;; Copyright © 2023 Greg Haskins.  All rights reserved

(ns reminderbot.commands.remindme
  (:require [taoensso.timbre :as log]
            [java-time.api :as jt]
            [temporal.client.core :as temporal]
            [temporal.workflow :refer [defworkflow] :as w]
            [temporal.activity :refer [defactivity] :as a]
            [temporal.side-effect :as s]
            [reminderbot.commands.core :as core]
            [reminderbot.parse :as parse]
            [reminderbot.slack-api :as slack]))

(defactivity send-reminder
  [{:keys [bot-token] :as ctx} {:keys [user_id phrase] :as params}]
  (log/trace "sending:" params)
  @(slack/invoke bot-token "chat.postMessage" {:channel user_id :text (str "Psst.  I was told to remind you to " phrase)})
  :ok)

(defn duration-from-now
  [deadline units]
  (-> (jt/time-between (s/now) deadline units)
      (jt/duration units)))

(defworkflow schedule-reminder
  [ctx {{:keys [deadline] :as args} :args}]
  (w/sleep (duration-from-now deadline :seconds))
  @(a/invoke send-reminder args))

(defn launch-workflow
  [{:keys [wf-client workflow-queuename] :as ctx} workflow args]
  (let [w (temporal/create-workflow wf-client workflow {:task-queue workflow-queuename})]
    (temporal/start w args)))

(defn markdown-response [text]
  {:blocks [{:type "section"
             :text {:type "mrkdwn"
                    :text text}}]})

(def units->jt
  {:seconds jt/seconds
   :minutes jt/minutes
   :hours   jt/hours
   :days    jt/days})

(defmethod core/dispatch "/remindme"
  [ctx {{:keys [text] :as payload} :payload}]
  (log/trace "remindme:" payload)
  (try
    (let [[_ phrase duration [units]] (parse/remindme text)
          duration (parse-long duration)
          deadline (jt/plus (jt/instant) ((get units->jt units) duration))]
      (launch-workflow ctx schedule-reminder (assoc payload :phrase phrase :deadline deadline))
      (markdown-response (str "Ok, I'll remind you at " deadline)))
    (catch Exception e
      (markdown-response "Hmm, I don't know what you mean.  Try '/remindme \"take out the trash\" in 20 minutes`"))))
