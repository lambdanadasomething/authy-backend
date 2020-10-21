(ns authy-backend.rabbit
  (:require [langohr.core      :as rmq]
            [langohr.channel   :as lch]
            [langohr.queue     :as lq]
            [langohr.consumers :as lc]
            [langohr.basic     :as lb]
            [taoensso.nippy :as nippy]))

(def rabbitmq-conn-conf
  {:host "localhost", :port 5672, :username "authy-agent", :vhost "/authy", :password "<redacted>"})

(def rab (rmq/connect rabbitmq-conn-conf))
(def ch (lch/open rab))

(lb/publish ch "work.main" "verify-email" 
            niptest {:content-type "binary/nippy"})

(lb/publish ch "work.main" "verify-email"
            "Hello" {:content-type "text/plain"})

(defn publish-verify-email-task [data]
  (lb/publish ch "work.main" "verify-email"
              (nippy/freeze data) {:content-type "binary/nippy"}))

(defn message-handler
  [ch {:keys [content-type delivery-tag] :as meta} ^bytes payload]
  (println (format "[consumer] Received a message: %s, delivery tag: %d, content type: %s"
                   (pr-str (nippy/thaw payload)) delivery-tag content-type)))

(defn test-email-handler
  [ch {:keys [content-type delivery-tag] :as meta} ^bytes payload]
  (let [{:keys [email token]} (nippy/thaw payload)]
    (println "email" email)
    (println "token" token)))

(println "email" "foo")

(def verify-email-consumer
  (lc/subscribe ch "verify-email"
                (lc/ack-unless-exception test-email-handler)
                {:auto-ack false}))


(def niptest (nippy/freeze {:foo 123 
                            :bar ["123" "hi" true 42] 
                            :test {:1 "Yes" :2 "No"}}))
(pr-str (nippy/thaw niptest))

(publish-verify-email-task {:email "foo@bar.com" :token "EJDU28CCUEJ992LLOWSP"})

(lb/cancel ch "amq.ctag-G54G1eLwDb_VAUVIq2_AiQ")
(lb/cancel ch verify-email-consumer)

(rmq/close ch)
(rmq/close rab)

