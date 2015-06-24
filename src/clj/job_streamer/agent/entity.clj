(ns job-streamer.agent.entity
  (:use [clojure.walk :only [keywordize-keys stringify-keys]])
  (:require [clojure.data.xml :refer [element emit-str] :as xml])
  (:import [javax.xml.stream XMLStreamWriter XMLOutputFactory]))

(defn properties->xml [properties]
  (element :properties {}
           (map #(element :property {:name (-> % first name)
                                     :value (second %)}) properties)))

(declare make-component)

(defprotocol XMLSerializable (to-xml [this]))

(defrecord Next [on to]
  XMLSerializable
  (to-xml [this]
    (element :next {:on (:on this)
                    :to (:to this)})))

(defn transitions->xml [transitions]
  (map (fn [transition]
         (to-xml (cond
                   (:next/on transition) (->Next (:next/on transition) (:next/to transition)))) )
       transitions))

(defrecord Split [id next components]
  XMLSerializable
  (to-xml [this]
    (element :split (merge {:id (:id this)}
                           (->> (select-keys this [:next])
                                (filter #(second %))
                                (into {})))
             (map to-xml components))))

(defn make-split [{:keys [split/name split/next split/components]
                   :or {split/components []}}]
  (->Split name next (map make-component components)))

(defrecord Flow [id next components]
  XMLSerializable
  (to-xml [this]
    (element :flow (merge {:id (:id this)}
                          (->> (select-keys this [:next])
                               (filter #(second %))
                               (into {})))
             (map to-xml components))))

(defn make-flow [{:keys [flow/name flow/next flow/components]
                  :or {flow/components []}}]
  (->Flow name next (map make-component components)))

(defrecord Batchlet [ref]
  XMLSerializable
  (to-xml [this]
    (element :batchlet (select-keys this [:ref]))))

(defn make-batchlet [{:keys [batchlet/ref]}]
  (->Batchlet ref))

(defrecord Chunk [reader processor writer checkpoint-policy commit-interval buffer-reads chunk-size skip-limit retry-limit]
  XMLSerializable
  (to-xml [this]
    (element :chunk (->> (select-keys this [:item-count])
                         (filter #(second %))
                         (into {}))
             (when reader
               (element :reader    {:ref (:ref reader)}))
             (when processor
               (element :processor {:ref (:ref processor)}))
             (when writer
               (element :writer    {:ref (:ref writer)})))))

(defn make-chunk [{:keys [chunk/reader chunk/processor chunk/writer chunk/checkpoint-policy chunk/commit-interval
                          chunk/buffer-reads chunk/chunk-size chunk/skip-limit chunk/retry-limit]}]
  (->Chunk reader processor writer checkpoint-policy commit-interval buffer-reads chunk-size skip-limit retry-limit))

(defrecord Step [id start-limit allow-start-if-complete next
                 chunk batchlet properties transitions]
  XMLSerializable
  (to-xml [this]
    (element :step (merge
                    {:id (:id this)
                     :allow-start-if-complete (boolean (:allow-start-if-complete? this))}
                    (->> (select-keys this [:start-limit :next])
                         (filter #(second %))
                         (into {}))) 
             (when-let [properties (some-> (:properties this))]
               (properties->xml properties))
             (when-let [chunk (some-> (:chunk this) make-chunk)]
               (to-xml chunk))
             (when-let [batchlet (some-> (:batchlet this) make-batchlet)]
               (to-xml batchlet))
             (when-let [transitions (some-> (:transitions this))]
               (transitions->xml transitions))
             (element :listeners {}
                      (element :listener {:ref "net.unit8.job_streamer.agent.listener.StepProgressListener"})))))

(defn make-step [{:keys [step/name step/start-limit step/allow-start-if-complete step/next
                         step/chunk step/batchlet step/properties step/transitions]}]
  (->Step name start-limit allow-start-if-complete next chunk batchlet properties transitions))

(defn make-component [component]
  (cond
    (:step/name component) (make-step component)
    (:flow/name component) (make-flow component)
    (:split/name component) (make-split component)
    ; (:decision/name component) (make-decision component) TODO will support
    ))

(defrecord Job [id restartable components properties]
  XMLSerializable
  (to-xml [this]
    (element :job (select-keys this [:id :restartable])
             (properties->xml properties)
             (element :listeners {}
                      (element :listener {:ref "net.unit8.job_streamer.agent.listener.JobProgressListener"}))
             (map to-xml components))))

(defn make-job [job]
  (let [{:keys [job/name job/restartable? job/components job/properties]
         :or {job/restartable? true, job/components [], job/properties {}}} job]
    (->Job name restartable? (map make-component components) properties)))

