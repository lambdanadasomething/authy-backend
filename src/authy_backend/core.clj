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
            [com.walmartlabs.dyn-edn :as dyn-edn]
            [clojure.edn :as edn]
            [ring.middleware.cors :refer [wrap-cors]]
            [one-time.core :as ot]
            [one-time.uri  :as oturi]))

;; Section - HOTP/TOTP

(def secret-key (ot/generate-secret-key))

(ot/get-totp-token secret-key)

(def current-token (ot/get-totp-token secret-key))
(ot/is-valid-totp-token? current-token secret-key)
;(Thread/sleep 30000)

(oturi/totp-uri {:label "authy-dev" :user "user@email.com" :secret secret-key})


;; End section - HOTP/TOTP

(defn test-fn [x]
  x)

;(def conf (load-config))

;conf
;*data-readers*

;; What a hack... Ya I'm nuts

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

(def node (crux/start-node (get-in allconfs [:infra :crux])))

;; Section - DB

; Trans fn
(comment
  (crux/submit-tx node 
                [[:crux.tx/put 
                  {:crux.db/id :basic/merge
                   ;; note that the function body is quoted.
                   :crux.db/fn '(fn [ctx eid m]
                                  (let [db (crux.api/db ctx)
                                        entity (crux.api/entity db eid)]
                                    [[:crux.tx/put (merge entity m)]]))}]]))
;;Example use
;;(crux/submit-tx node [[:crux.tx/fn :basic/merge "testing_68241" {:meta true :name "Hey man this"}]])

(defn create-user! [node user opt]
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

;(create-user! node {:id "mary" :email "foo@bar.com" :ident-type :id :password "superhero123"} {})
;(create-user! node {:id "whatever" :email "null@dev.local" :ident-type :email :password "fj2uEVk"} {})
;(create-user! node {:id "lester" :email "simpsonfamily@test.local" :ident-type :id :password "kWxO372Nm"} {})
;(create-user! node {:id "lester2" :email "simpsonfamily2@test.local" :ident-type :id :password "kWxO372Nm"} {})
;(create-user! node {:id "notfound404" :email "notexists@test.local" :ident-type :id :password "kWxO372Nm"} {})

(comment
  (crux/q (crux/db node) '{:find [(eql/project ?e [*])]
                           :where [[?e :crux.db/id _]]}))

(defn find-user [node type id]
  (crux/entity (crux/db node) {:db-type :user, :ident-type type, :id id}))

(defn set-mfa [node type id mfa]
  (crux/submit-tx node [[:crux.tx/fn :basic/merge {:db-type :user, :ident-type type, :id id}
                         {:user.credential/mfa mfa}]]))

;; mfa temp schema:
;; 
;; {:enabled? true
;;  :type :goog-authenticator
;;  :secret-key "HS72CK19LDO"}
;; {:enabled? false}

(defn has-mfa? [mfa]
  (and (not (nil? mfa))
       (:enabled? mfa)))

;; (has-mfa? nil) => false
;; (has-mfa? {:enabled? false}) => false
;; (has-mfa? {:enabled? true}) => true


; Currently hardcode googel authenticator as mfa type (so no side effect needed yet)
(defn naive-login [node type id password]
  (let [{password-hash :user.credential/password
         mfa :user.credential/mfa
         :as user} (find-user node type id)]
    (cond 
      (nil? user) {:success? false :reason :user-not-found}
      (not (:valid (hashers/verify password password-hash))) {:success? false :reason :incorrect-password}
      (not (has-mfa? mfa)) {:success? true :need-mfa? false}
      true {:success? true :need-mfa? true :mfa (:type mfa)})))

;(.close node)

;; End section - DB

;; Test reitit

(defn html-ok [body]
  (-> body
      (response/response)
      (response/content-type "text/html")))

(defn edn-ok [body]
  (-> body
      (prn-str)
      (response/response)
      (response/content-type "application/edn")))

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

(defn check-user [req]
  (let [userid (get (:query-params req) "userid")
        available? (nil? (find-user node :id userid))]
    (edn-ok {:available? available?})))


(defn test-path [req]
  (let [test-id (:path-params (:reitit.core/match req))]
    (html-ok (str "My path: " test-id))))

(defn cors-tmp [handler]
  (wrap-cors handler
   :access-control-allow-origin [#"http://172.30.0.22:8280/"]
   :access-control-allow-methods [:get :put :post :delete]))

(defn wrap-cors-header
  "Allow requests from all origins"
  [handler]
  (fn [request]
    (let [response (handler request)]
      (update-in response
                 [:headers "Access-Control-Allow-Origin"]
                 (fn [_] "*")))))

(def router
  (ring/router
   [
    ["/diagnostic" {:middleware [wrap-myprint]
                    :get diagnostic}]
    ["/session" {:get get-user
                :post set-user}]
    ["/test/:test-id" test-path]
    ["/authy"
     ["/check-user-availability" {:middleware [cors-tmp wrap-cors-header]
                                  :get check-user}]]]))


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


