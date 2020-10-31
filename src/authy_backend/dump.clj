(ns authy-backend.dump)

;; Dumping ground for obsolete code (?)

(comment
  (def initial-conf
    (binding [*data-readers* (merge *data-readers* (dyn-edn/readers {}) {'dyn/prop #'authy-backend.core/test-fn})]
      (load-config)))

  (binding [*data-readers* (merge *data-readers* (dyn-edn/readers initial-conf))]
    (load-config)))

(comment
  (defroutes my-routes
    (GET "/" [] "<h1>Hello World Bye</h1>")
    (GET "/diagnostic" req
      (do
        (println req)
        (str (format "<ul><li>Session Key: %s</li><li>Session Data: %s</li></ul>"
                     (:session/key req "None") (pr-str (:session req)))
             "<p>Diagnostic printed.</p>")))
    (GET "/user" {{:keys [user-id]} :session}
      (str "The current user is " user-id))
    (GET "/test" [] {:session {:user-id "Sam"}
                     :status 200
                     :headers {"Content-Type" "text/html"}
                     :body "OK"})
    (route/not-found "<h1>Page not found</h1>")))

(comment
  (def my-app
    (-> my-routes
        (ring-session/wrap-session {:store (redis-store rconn {:expire-secs (* 60 10)
                                                               :reset-on-read true
                                                               :prefix "authy-test1"})}))))

(comment
  (def router
    (r/router
     ["/" {:get home}
      ["/diagnostic" {:get diagnostic}]
      ["/session"
       ["/set" {:post 1}]
       ["/get" {:get 1}]]])))

(crux/submit-tx node
                [[:crux.tx/put
                  {:crux.db/id "testing_123"
                   :name "Hey man"
                   :linked-docs ["testing_41" "testing_56"]}]])

(crux/submit-tx node
                [[:crux.tx/put
                  {:crux.db/id "testing_41"
                   :subitem "Test A"}]
                 [:crux.tx/put
                  {:crux.db/id "testing_56"
                   :subitem "Test B"}]])

(crux/q (crux/db node)
        '{:find [?n ?s]
          :where [[?e :crux.db/id "testing_123"]
                  [?e :name ?n]
                  [?e :linked-docs ?el]
                  [?el :subitem ?s]]})

(crux/q (crux/db node)
        '{:find [(eql/project ?e [:name {:linked-docs [:subitem]}])]
          :where [[?e :crux.db/id "testing_123"]]})

(crux/submit-tx node
                [[:crux.tx/put
                  {:crux.db/id "testing_68241"
                   :name "Hey man"
                   :linked-docs [{:crux.db/id "testing_81732"
                                  :subitem "Oh well"}
                                 {:crux.db/id "testing_90375"
                                  :subitem "What the?"}]}]])
