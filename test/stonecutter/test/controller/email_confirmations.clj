(ns stonecutter.test.controller.email-confirmations
  (:require [midje.sweet :refer :all]
            [clauth.token :as cl-token]
            [net.cgrand.enlive-html :as html]
            [stonecutter.test.test-helpers :as th]
            [stonecutter.email :as email]
            [stonecutter.routes :as routes]
            [stonecutter.controller.email-confirmations :as ec]
            [stonecutter.db.user :as user]
            [stonecutter.db.confirmation :as conf]
            [stonecutter.db.mongo :as m]
            [stonecutter.test.email :as test-email]
            [stonecutter.session :as session]))

(defn with-signed-in-user [ring-map token-store user]
  (let [access-token (cl-token/create-token token-store nil user)]
    (-> ring-map
        (session/set-access-token (:token access-token))
        (session/set-user-login (:login user)))))

(def email "dummy@email.com")
(def confirmation-id "RANDOM-ID-12345")
(def password "password123")

(defn test-email-renderer [email-data]
  {:subject "confirmation"
   :body    email-data})

(def confirm-email-path
  (routes/path :confirm-email-with-id
               :confirmation-id confirmation-id))

(def confirm-email-request
  (th/create-request :get confirm-email-path {:confirmation-id confirmation-id}))

(facts "about confirm-email-with-id"
       (fact "if the confirmation UUID in the URL matches that of the signed in user's user record confirm the account and redirect to show profile with confirmation flash message"
             (let [user-store (m/create-memory-store)
                   token-store (m/create-memory-store)
                   confirmation-store (m/create-memory-store)
                   user (th/store-user! user-store email "password")
                   confirmation (conf/store! confirmation-store email confirmation-id)
                   request (-> confirm-email-request
                               (with-signed-in-user token-store user))
                   response (ec/confirm-email-with-id user-store confirmation-store request)]
               response => (th/check-redirects-to (routes/path :show-profile))
               (:flash response) => :email-confirmed
               (user/retrieve-user user-store (:login user)) =not=> (contains {:confirmation-id anything})
               (user/retrieve-user user-store (:login user)) => (contains {:confirmed? true})))

       (fact "when confirmation UUID in the URL does not match that of the signed in user's user record, signs the user out and redirects to confirmation endpoint with the original confirmation UUID from the query string"
             (let [user-store (m/create-memory-store)
                   token-store (m/create-memory-store)
                   confirmation-store (m/create-memory-store)
                   signed-in-user (th/store-user! user-store "signed-in@email.com" "password")
                   confirming-user (th/store-user! user-store "confirming@email.com" "password")
                   confirmation (conf/store! confirmation-store "confirming@email.com" confirmation-id)
                   request (-> confirm-email-request
                               (with-signed-in-user token-store signed-in-user))
                   response (ec/confirm-email-with-id user-store confirmation-store request)]
               response =not=> (th/check-signed-in request signed-in-user)
               response => (th/check-redirects-to confirm-email-path)))

       (fact "when user is not signed in, redirects to index page with the confirmation endpoint (including confirmation UUID URL) as the successful sign-in redirect target"
             (let [user-store (m/create-memory-store)
                   confirmation-store (m/create-memory-store)
                   confirming-user (th/store-user! user-store email "password")
                   confirmation (conf/store! confirmation-store email confirmation-id)
                   response (ec/confirm-email-with-id user-store confirmation-store confirm-email-request)]
               response => (th/check-redirects-to (routes/path :confirmation-sign-in-form
                                                               :confirmation-id confirmation-id))))

       (fact "when email confirmation is complete confirmation-id is revoked"
             (let [user-store (m/create-memory-store)
                   token-store (m/create-memory-store)
                   confirmation-store (m/create-memory-store)
                   user (th/store-user! user-store email "password")
                   confirmation (conf/store! confirmation-store email confirmation-id)
                   request (-> confirm-email-request
                               (with-signed-in-user token-store user))]
               (ec/confirm-email-with-id user-store confirmation-store request)
               (conf/fetch confirmation-store confirmation-id) => nil))

       (fact "when the confirmation id does not exist in the db it returns nil (404)"
             (let [user-store (m/create-memory-store)
                   token-store (m/create-memory-store)
                   confirmation-store (m/create-memory-store)
                   user (th/store-user! user-store email "password")
                   request (-> confirm-email-request
                               (with-signed-in-user token-store user))]
               (ec/confirm-email-with-id user-store confirmation-store request) => nil))

       (fact "if the confirmation id exists but the user does not then the confirmation id should be revoked and the error-account-nonexistent page is rendered"
             (let [user-store (m/create-memory-store)
                   confirmation-store (m/create-memory-store)
                   confirmation (conf/store! confirmation-store email confirmation-id)
                   response (ec/confirm-email-with-id user-store confirmation-store confirm-email-request)]
               (conf/fetch confirmation-store confirmation-id) => nil
               response => (th/check-renders-page [:.func--error-account-nonexistent-page]))))

(facts "about confirmation sign in"
       (fact "when password matches login of confirmation id, user is logged in"
             (->> (th/create-request :post (routes/path :confirmation-sign-in) {:confirmation-id confirmation-id :password password})
                  (ec/confirmation-sign-in ...user-store... ...token-store... ...confirmation-store...))
             => (contains {:status  302
                           :headers {"Location" confirm-email-path}
                           :session (contains {:user-login   ...user-login...
                                               :access_token ...token...})})
             (provided
               (user/authenticate-and-retrieve-user ...user-store... email password) => {:login ...user-login...}
               (conf/fetch ...confirmation-store... confirmation-id) => {:login email :confirmation-id confirmation-id}
               (cl-token/create-token ...token-store... nil {:login ...user-login...}) => {:token ...token...}))

       (fact "when credentials are invalid, redirect back to form with invalid error"
             (against-background
               (user/authenticate-and-retrieve-user ...user-store... email "Invalid password") => nil
               (conf/fetch ...confirmation-store... confirmation-id) => {:login email :confirmation-id confirmation-id})
             (let [response (->> (th/create-request :post (routes/path :confirmation-sign-in)
                                                    {:confirmation-id confirmation-id :password "Invalid password"})
                                 (ec/confirmation-sign-in ...user-store... ...token-store... ...confirmation-store...))]
               response => (contains {:status 200})
               response =not=> (contains {:session {:user-login   anything
                                                    :access_token anything}})
               (-> (html/select (html/html-snippet (:body response)) [:.clj--validation-summary__item]) first :attrs :data-l8n)
               => "content:confirmation-sign-in-form/invalid-credentials-validation-message")))

(def confirmation-delete-path
  (routes/path :confirmation-delete
               :confirmation-id confirmation-id))

(def confirmation-delete-request
  (th/create-request :post confirmation-delete-path {:confirmation-id confirmation-id}))

(facts "about confirmation delete"
       (fact "if the confirmation id exists, the account is deleted, the confirmation id is revoked and profile-deleted page is rendered"
             (let [user-store (m/create-memory-store)
                   confirmation-store (m/create-memory-store)
                   user (th/store-user! user-store email "password")
                   confirmation (conf/store! confirmation-store email confirmation-id)
                   response (ec/confirmation-delete user-store confirmation-store confirmation-delete-request)]
               (user/retrieve-user user-store (:login user)) => nil
               (conf/fetch confirmation-store confirmation-id) => nil
               response => (every-checker
                             (th/check-redirects-to "/profile-deleted")
                             (contains {:session nil}))))

       (fact "if the confirmation id does not exist in the db it returns nil (404)"
             (let [user-store (m/create-memory-store)
                   confirmation-store (m/create-memory-store)
                   response (ec/confirmation-delete user-store confirmation-store confirmation-delete-request)]
               response => nil))

       (fact "if the confirmation id exists but the user does not then the confirmation id should be revoked and the error-account-nonexistent page is rendered"
             (let [user-store (m/create-memory-store)
                   confirmation-store (m/create-memory-store)
                   confirmation (conf/store! confirmation-store email confirmation-id)
                   response (ec/confirmation-delete user-store confirmation-store confirmation-delete-request)]
               (conf/fetch confirmation-store confirmation-id) => nil
               response => (th/check-renders-page [:.func--error-account-nonexistent-page]))))

(def show-confirmation-delete-path
  (routes/path :show-confirmation-delete
               :confirmation-id confirmation-id))

(def show-confirmation-delete-request
  (th/create-request :get show-confirmation-delete-path {:confirmation-id confirmation-id}))

(facts "about show confirmation delete"
       (fact "if the confirmation id exists and the user exists the confirmation-delete page is rendered"
             (let [user-store (m/create-memory-store)
                   confirmation-store (m/create-memory-store)
                   user (th/store-user! user-store email "password")
                   confirmation (conf/store! confirmation-store email confirmation-id)
                   response (ec/show-confirmation-delete user-store confirmation-store show-confirmation-delete-request)]
               response => (th/check-renders-page [:.func--delete-account-page])))

       (fact "if the confirmation id does not exist in the db it returns nil (404)"
             (let [user-store (m/create-memory-store)
                   confirmation-store (m/create-memory-store)
                   response (ec/show-confirmation-delete user-store confirmation-store show-confirmation-delete-request)]
               response => nil))

       (fact "if the confirmation id exists but the user does not then the confirmation id should be revoked and the error-account-nonexistent page is rendered"
             (let [user-store (m/create-memory-store)
                   confirmation-store (m/create-memory-store)
                   confirmation (conf/store! confirmation-store email confirmation-id)
                   response (ec/show-confirmation-delete user-store confirmation-store show-confirmation-delete-request)]
               (conf/fetch confirmation-store confirmation-id) => nil
               response => (th/check-renders-page [:.func--error-account-nonexistent-page]))))
