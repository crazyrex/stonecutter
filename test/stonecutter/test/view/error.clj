(ns stonecutter.test.view.error
  (:require [midje.sweet :refer :all]
            [net.cgrand.enlive-html :as html]  
            [stonecutter.view.error :as e]))

(fact "csrf error has correct page-intro translation key"
      (let [page (e/csrf-error)]
        (-> (html/select page [:.clj--error-info]) first :attrs :data-l8n) => "content:error-403/page-intro"))
