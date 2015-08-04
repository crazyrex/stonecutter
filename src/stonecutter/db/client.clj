(ns stonecutter.db.client
  (:require [clauth.client :as cl-client]
            [schema.core :as schema]
            [clojure.string :as s]
            [stonecutter.db.storage :as storage]))

(def not-blank? (complement s/blank?))

(def Client
  "A schema for a client entry"
  {:name          (schema/both schema/Str (schema/pred not-blank?))
   :client-id     (schema/both schema/Str (schema/pred not-blank?))
   :client-secret (schema/both schema/Str (schema/pred not-blank?))
   :url           (schema/both schema/Str (schema/pred not-blank?))})

(defn validate-url-format [client-entry]
  (let [url (:url client-entry)]
    (if (re-find #"https?://" url)
      client-entry
      (throw Exception "missing resource prefix e.g. https://"))))

(defn validate-client-entry [client-entry]
  (-> (schema/validate Client client-entry)
      validate-url-format))

(defn delete-clients! []
  (cl-client/reset-client-store! @storage/client-store))

(defn retrieve-client [client-id]
  (dissoc (cl-client/fetch-client @storage/client-store client-id) :client-secret))

(defn unique-client-id? [client-id]
  (nil? (retrieve-client client-id)))

(defn store-clients-from-map [client-credentials-map]
  (let [client-credentials-seq (seq client-credentials-map)]
    (doseq [client-entry client-credentials-seq]
      (validate-client-entry client-entry)
      (let [name (:name client-entry)
            client-id (:client-id client-entry)
            client-secret (:client-secret client-entry)
            url (:url client-entry)]
        (when (unique-client-id? client-id)
          (cl-client/store-client @storage/client-store {:name          name
                                                        :client-id     client-id
                                                        :client-secret client-secret
                                                        :url           url}))))))
