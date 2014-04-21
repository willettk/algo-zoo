(ns zoo-algos.mongo
  (:require [monger.core :refer [set-db! connect! get-db]]
            [monger.collection :refer [find-maps find-map-by-id update-by-id] :as m]
            [monger.conversion :refer [from-db-object to-object-id]]
            [zoo-algos.graph :refer [TaskGraph]]
            [clojure.edn :as edn]))

(defn user-col
  [project]
  (str project "_users"))

(defn subject-col
  [project]
  (str project "_subjects"))

(defn classification-col
  [project]
  (str project "_classifications"))

(defn annotation-filter
  [a]
  (cond
    (keyword? a) (fn [[k v]] (= a k))
    (map? a) (fn [p] (= a p))))

(defn lazy-id-list
  [col]
  (let [cursor (m/find col)
        seq-fn (fn seq-fn [cursor]
                 (lazy-seq (cons (:_id (from-db-object (.next cursor) true))
                                 (if (.hasNext cursor)
                                   (seq-fn cursor)
                                   nil))))]
    (seq-fn cursor)))

(defn set-answer
  [delta-obj]
  (update-in delta-obj [:answer] #(if (nil? %) -1 1)))

(defn get-task
  [project annotation id] 
  (let [{:keys [p]} (find-map-by-id (subject-col project) (to-object-id id))
        delta (->> (find-maps (classification-col project) {:subject_ids (to-object-id id)})
                   (filter :user_id)
                   (map #(hash-map :p (:p (find-map-by-id (user-col project) (:user_id %)))
                                   :id (:user_id %)
                                   :answer (first (filter (annotation-filter annotation) 
                                                          (:annotations %)))))
                   (map set-answer))]
    {:p (or p 0) :delta delta}))

(defn get-user
  [project annotation id]
  (let [{:keys [p]} (find-map-by-id (user-col project) (to-object-id id))
        answers (find-maps (classification-col project) {:user_id (to-object-id id)})
        delta (map #(hash-map :p (->> (:subject_ids %)
                                      first
                                      (find-map-by-id (subject-col project)) 
                                      :p)
                              :id (first (:subject_ids %))
                              :answer (first (filter (annotation-filter annotation)
                                                     (:annotations %))))
                   answers)
        delta (map set-answer delta)]
    {:p (or p 0) :delta delta}))

(defn set-p
  [col id new-p]
  (update-by-id col id {:p new-p}))

(defrecord MongoGraph [project annotation]
  TaskGraph
  (get [this type id]
    (cond
      (and (= :task type) (= :all id)) (lazy-id-list (subject-col project)) 
      (and (= :user type) (= :all id)) (lazy-id-list (user-col project)) 
      (= :task type) (get-task project annotation id)
      (= :user type) (get-user project annotation id)))
  (update-p [this [type id] f]
    (let [new-p (apply f (get this type id))]
      (cond
        (= :task type) (set-p (subject-col project) id new-p)
        (= :user type) (set-p (user-col project) id new-p)))))

(defn -main
  [& [project annotation m-host m-port]]
  (if m-host
    (connect! {:host m-host :port m-port})
    (connect!))
  (set-db! (get-db project))
  (MongoGraph. project (edn/read-string annotation)))
