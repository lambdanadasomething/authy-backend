(defproject authy-backend "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [juxt/crux-core "20.09-1.12.1-beta"]
                 [juxt/crux-kafka "20.09-1.12.1-beta"]
                 [juxt/crux-rocksdb "20.09-1.12.1-beta"]
                 [juxt/crux-jdbc "20.09-1.12.1-beta"]
                 [com.opentable.components/otj-pg-embedded "0.13.1"]
                 [com.taoensso/nippy "3.0.0"]
                 [com.novemberain/langohr "5.1.0"]
                 [com.taoensso/carmine "3.0.0"]
                 [buddy "2.0.0"]
                 [buddy/buddy-hashers "1.6.0"]
                 [compojure "1.6.2"]
                 [ring "1.8.2"]
                 [clojusc/ring-redis-session "3.3.0-SNAPSHOT"]
                 [metosin/reitit "0.5.6"]
                 [cprop "0.1.17"]]
  :main ^:skip-aot authy-backend.app
  :target-path "target/%s"
  :profiles {:dev {:jvm-opts ["-Dconf=resources/config.dev.edn"]}
             :uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
