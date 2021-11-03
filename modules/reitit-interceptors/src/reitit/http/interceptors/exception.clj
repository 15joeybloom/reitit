(ns reitit.http.interceptors.exception
  (:require [reitit.coercion :as coercion]
            [reitit.ring :as ring]
            [clojure.spec.alpha :as s]
            [clojure.string :as str])
  (:import (java.time Instant)
           (java.io PrintWriter Writer)))

(s/def ::handlers (s/map-of any? fn?))
(s/def ::spec (s/keys :opt-un [::handlers]))

;;
;; helpers
;;

(defn- super-classes [^Class k]
  (loop [sk (.getSuperclass k), ks []]
    (if-not (= sk Object)
      (recur (.getSuperclass sk) (conj ks sk))
      ks)))

(defn- call-error-handler [handlers error request]
  (let [type (:type (ex-data error))
        ex-class (class error)
        error-handler (or (get handlers type)
                          (get handlers ex-class)
                          (some
                            (partial get handlers)
                            (descendants type))
                          (some
                            (partial get handlers)
                            (super-classes ex-class))
                          (get handlers ::default))]
    (if-let [wrap (get handlers ::wrap)]
      (wrap error-handler error request)
      (error-handler error request))))

(defn print! [^Writer writer & more]
  (.write writer (str (str/join " " more) "\n")))

;;
;; handlers
;;

(defn default-handler
  "Default safe handler for any exception."
  [^Exception e _]
  {:status 500
   :body {:type "exception"
          :class (.getName (.getClass e))}})

(defn create-coercion-handler
  "Creates a coercion exception handler."
  [status]
  (fn [e _]
    {:status status
     :body (coercion/encode-error (ex-data e))}))

(defn http-response-handler
  "Reads response from Exception ex-data :response"
  [e _]
  (-> e ex-data :response))

(defn request-parsing-handler [e _]
  {:status 400
   :headers {"Content-Type" "text/plain"}
   :body (str "Malformed " (-> e ex-data :format pr-str) " request.")})

(defn wrap-log-to-console [handler ^Throwable e {:keys [uri request-method] :as req}]
  (print! *out* (Instant/now) request-method (pr-str uri) "=>" (.getMessage e))
  (.printStackTrace e (PrintWriter. ^Writer *out*))
  (handler e req))

;;
;; public api
;;

(def default-handlers
  {::default default-handler
   ::ring/response http-response-handler
   :muuntaja/decode request-parsing-handler
   ::coercion/request-coercion (create-coercion-handler 400)
   ::coercion/response-coercion (create-coercion-handler 500)})

(defn exception-interceptor
  "Creates an Interceptor that catches all exceptions. Takes a map
  of `identifier => exception request => response` that is used to select
  the exception handler for the thrown/raised exception identifier. Exception
  identifier is either a `Keyword` or a Exception Class.

  The following handlers special handlers are available:

  | key                    | description
  |------------------------|-------------
  | `::exception/default`  | a default exception handler if nothing else matched (default [[default-handler]]).
  | `::exception/wrap`     | a 3-arity handler to wrap the actual handler `handler exception request => response`

  The handler is selected from the options map by exception identifier
  in the following lookup order:

  1) `:type` of exception ex-data
  2) Class of exception
  3) `:type` ancestors of exception ex-data
  4) Super Classes of exception
  5) The ::default handler

  Example:

      (require '[reitit.ring.interceptors.exception :as exception])

      ;; type hierarchy
      (derive ::error ::exception)
      (derive ::failure ::exception)
      (derive ::horror ::exception)

      (defn handler [message exception request]
        {:status 500
         :body {:message message
                :exception (str exception)
                :uri (:uri request)}})

      (exception/exception-interceptor
        (merge
          exception/default-handlers
          {;; ex-data with :type ::error
           ::error (partial handler \"error\")

           ;; ex-data with ::exception or ::failure
           ::exception (partial handler \"exception\")

           ;; SQLException and all it's child classes
           java.sql.SQLException (partial handler \"sql-exception\")

           ;; override the default handler
           ::exception/default (partial handler \"default\")

           ;; print stack-traces for all exceptions
           ::exception/wrap (fn [handler e request]
                              (.printStackTrace e)
                              (handler e request))}))"
  ([]
   (exception-interceptor default-handlers))
  ([handlers]
   {:name ::exception
    :spec ::spec
    :error (fn [ctx]
             (let [error (:error ctx)
                   request (:request ctx)
                   response (call-error-handler handlers error request)]
               (if (instance? Exception response)
                 (-> ctx (assoc :error response) (dissoc :response))
                 (-> ctx (assoc :response response) (dissoc :error)))))}))
