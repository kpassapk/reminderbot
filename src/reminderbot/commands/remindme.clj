;; Copyright © 2023 Greg Haskins.  All rights reserved

(ns reminderbot.commands.remindme
  (:require
   [clojure.core.protocols :as p]
   [java-time.api :as jt]
   [reminderbot.commands.core :as core]
   [reminderbot.parse :as parse]
   [reminderbot.slack-api :as slack]
   [taoensso.timbre :as log]
   [temporal.activity :refer [defactivity] :as a]
   [temporal.client.core :as temporal]
   [temporal.side-effect :as s]
   [temporal.signals :refer [>!] :as sig]
   [temporal.workflow :refer [defworkflow] :as w])
  (:import
   (java.time Duration)))

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
  [{:keys [deadline] :as args}]
  (let [state (atom {:canceled false})]
    ;; when any signal is received, unpause
    (sig/register-signal-handler! (fn [signal-name args]
                                    (swap! state assoc :canceled true)
                                    
                                    ))

    (w/await (duration-from-now deadline :seconds) (fn [] (= (:canceled @state) true)))

    (if  (:canceled state))
    @(a/invoke send-reminder args)))

(defn launch-workflow
  [{:keys [wf-client workflow-queuename] :as ctx} workflow args]
  (let [w (temporal/create-workflow wf-client workflow {:task-queue workflow-queuename})]
    (def ctx* ctx)
    (temporal/start w args)))

(defworkflow send-signal
  [{:keys [workflow-id]}]
  (>! workflow-id ::signal {}))

(comment
  (def foo (atom {:paused false}))
  (swap! foo assoc :paused true)

  (launch-workflow ctx* send-signal {:workflow-id "4df30a81-0342-4743-8534-18fe0e004883"})
  )

(defmethod core/dispatch "/remindme"
  [ctx {{:keys [text] :as payload} :payload}]
  (log/trace "remindme:" payload)
  (try
    (let [{:keys [phrase deadline]} (parse/remindme text)
          ex (launch-workflow ctx schedule-reminder (assoc payload :phrase phrase :deadline deadline))]
      (log/info (str "Created workflow ID " (.getWorkflowId ex)))
      {:text (str "Ok, I'll remind you to " phrase " at " deadline)})
    (catch Exception e
      {:text "Hmm, I don't know what you mean.  Try '/remindme take out the trash in 20 minutes`"})))
