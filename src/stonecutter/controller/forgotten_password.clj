(ns stonecutter.controller.forgotten-password
  (:require [clauth.store :as cl-store]
            [clojure.string :as string]
            [ring.util.response :as response]
            [stonecutter.validation :as v]
            [stonecutter.view.forgotten-password :as forgotten-password-view]
            [stonecutter.helper :as sh]
            [stonecutter.email :as email]
            [stonecutter.util.uuid :as uuid]
            [stonecutter.db.user :as user]))

(defn show-forgotten-password-form [request]
  (sh/enlive-response (forgotten-password-view/forgotten-password-form request) (:context request)))

(defn forgotten-password-form-post [email-sender user-store forgotten-password-store request]
  (let [params (:params request)
        email-address (string/lower-case (:email params))
        err (v/validate-forgotten-password params)
        app-name (get-in request [:context :config-m :app-name]) ;; FIXME JOHN 7/8 make functions for retrieving stuff from context
        base-url (get-in request [:context :config-m :base-url])
        request-with-validation-errors (assoc-in request [:context :errors] err)]
    (if (empty? err)
      (do
        (when (user/retrieve-user user-store email-address)
          (let [forgotten-password-id (uuid/uuid)]
            (cl-store/store! forgotten-password-store :forgotten-password-id {:forgotten-password-id forgotten-password-id :login email-address})
            (email/send! email-sender :forgotten-password email-address {:app-name app-name :base-url base-url :forgotten-password-id forgotten-password-id})))
        (response/response "email sent"))
      (show-forgotten-password-form request-with-validation-errors))))