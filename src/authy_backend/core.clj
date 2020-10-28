(ns authy-backend.core
  (:require [clojure.java.io :as io]
            [crux.api :as crux]
            [buddy.hashers :as hashers]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.util.response :as response]
            [ring.middleware.params :as param]
            [reitit.core :as r]
            [reitit.ring :as ring]
            ;[compojure.core :refer :all]
            ;[compojure.route :as route]
            [ring.middleware.session :as ring-session]
            [ring.redis.session :refer [redis-store]]
            [cprop.core :refer [load-config]]
            [com.walmartlabs.dyn-edn :as dyn-edn]))




(defn test-fn [x]
  x)

;(def conf (load-config))

;conf
;*data-readers*

;; What a hack... Ya I'm nuts

(comment
  (def initial-conf
  (binding [*data-readers* (merge *data-readers* (dyn-edn/readers {}) {'dyn/prop #'authy-backend.core/test-fn})]
    (load-config)))

(binding [*data-readers* (merge *data-readers* (dyn-edn/readers initial-conf))]
  (load-config)))

(defn load-config-resolve-ref [secret-file-path]
  (let [initial-conf (binding 
                      [*data-readers* (merge *data-readers*
                                             (dyn-edn/readers {})
                                             {'dyn/prop #'authy-backend.core/test-fn})]
                       (load-config :resource secret-file-path))]
    (binding [*data-readers* (merge *data-readers* (dyn-edn/readers initial-conf))]
      (load-config))))

(def allconfs (load-config-resolve-ref "secrets.dev.edn"))

(def rconn (get-in allconfs [:infra :redis]))

;(def node (crux/start-node (get-in allconfs [:infra :crux])))

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
                                                             :prefix "authy-test1"})})))
)

;; Test reitit

(defn html-ok [body]
  (-> body
      (response/response)
      (response/content-type "text/html")))

(defn home [req]
  (html-ok "<h1>Hello World!</h1>"))

(defn diagnostic [req]
  (html-ok (str (format "<ul><li>Session Key: %s</li><li>Session Data: %s</li></ul>"
                        (:session/key req "None") (pr-str (:session req)))
                "<p>Diagnostic printed.</p>")))

(defn get-user [{{:keys [user-id]} :session}]
  (html-ok (format "<span>%s</span><form action='/session' method='POST'><label for='user'>User id: </label><input type='text' id='user' name='user'><input type='submit' value='Submit!'></form>"
                   (str "The current user is " user-id))))

(defn set-user [req]
  (let [user (get (:form-params req) "user" "Guest")]
    (-> (response/response "OK")
        (assoc :session {:user-id user}))))

(defn wrap-myprint [handler]
  (fn [req]
    (println req)
    (handler req)))

(comment
  (def router
  (r/router
   ["/" {:get home}
    ["/diagnostic" {:get diagnostic}]
    ["/session"
     ["/set" {:post 1}]
     ["/get" {:get 1}]]])))

(defn test-path [req]
  (let [test-id (:path-params (:reitit.core/match req))]
    (html-ok (str "My path: " test-id))))

(def router
  (ring/router
   [
    ["/diagnostic" {:middleware [wrap-myprint]
                    :get diagnostic}]
    ["/session" {:get get-user
                :post set-user}]
    ["/test/:test-id" test-path]]))


(def my-app
  (ring/ring-handler
   router
   (ring/routes
    (ring/redirect-trailing-slash-handler)
    (ring/create-default-handler
     {:not-found (constantly {:status 404, :body "Not found"})
      :method-not-allowed (constantly {:status 405, :body "Method not allowed"})
      :not-acceptable (constantly {:status 406, :body "Opps"})}))
   {:middleware 
    [[param/wrap-params]
     [ring-session/wrap-session {:store (redis-store rconn {:expire-secs (* 60 10)
                                                            :reset-on-read true
                                                            :prefix "authy-test1"})}]]}))


(def server (run-jetty #'my-app (get-in allconfs [:infra :web-server])))
(defn restart [s]
  (do
    (.stop s)
    (.start s)))

(restart server)
(.stop server)

;(io/file "/tmp/crux-rocksdb")
;(def node (crux/start-node myconf))
(def node {})

(def testhash (hashers/derive "secretpassword" {:alg :bcrypt+sha512}))

(def weakhash (hashers/derive "secretpassword" {:alg :bcrypt+sha512 :iterations 5 }))

(hashers/verify "hello" weakhash)
(hashers/verify "secretpassword" weakhash)
(hashers/verify "secretpassword" testhash)


(defn create-user! [user opt]
  (let [{:keys [id email ident-type password]} user
        uid (case ident-type
              :id    id
              :email email
              email)
        crux-id {:db-type :user, :ident-type ident-type, :id uid}]
    (crux/submit-tx 
     node 
     [[:crux.tx/match crux-id nil]
      [:crux.tx/put {:crux.db/id crux-id
                     :user/id id
                     :user/email email
                     :user/lifecycle-state :state/init
                     :user.credential/password (hashers/derive password 
                                                               {:alg :bcrypt+sha512})}]])))

;(create-user! {:id "mary" :email "foo@bar.com" :ident-type :id :password "superhero123"} {})
;(create-user! {:id "whatever" :email "null@dev.local" :ident-type :email :password "fj2uEVk"} {})

(comment
  (crux/q (crux/db node) '{:find [(eql/project ?e [*])]
                         :where [[?e :crux.db/id _]]})
)

(defn find-user [node type id]
  (crux/entity (crux/db node) {:db-type :user, :ident-type type, :id id}))

;(.close node)
