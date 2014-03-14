(ns thi.ng.luxor.io
  (:require
   [thi.ng.luxor.config :as conf]
   [thi.ng.luxor.compiler :refer [luxvalues-typed]]
   [thi.ng.luxor.version :refer [version]]
   [clojure.java.io :as io])
  (:import
   [java.io File ByteArrayOutputStream OutputStream]
   [java.util.zip ZipOutputStream ZipEntry]
   [java.util Date]))

(defprotocol PAsByteArray
  (as-byte-array [_]))

(extend-protocol PAsByteArray
  String
  (as-byte-array [_] (.getBytes _ "UTF-8"))

  ByteArrayOutputStream
  (as-byte-array [_] (.toByteArray _)))

(defn file-mesh-stream
  [id path]
  (println "writing:" path)
  (io/output-stream path))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; serialization

(defn- lx-header
  [path & comments]
  (format "# %s\n# generated %s by luxor v%s\n%s\n"
          path (.toString (Date.)) version
          (if (seq comments)
            (reduce #(str % "# " %2 "\n") "#\n# Comments:\n" comments)
            "")))

(defn- path-filename
  [path] (.getName (File. ^String path)))

(defn- include-file*
  [path]
  (format "Include \"%s\"\n\n" path))

(defn- include-partials*
  [partials]
  (apply str (map include-file* partials)))

(defn- inject-scene-partial
  [partial path include?]
  (when partial
    (if include? (include-file* path) partial)))

(defn- serialize-lxs
  [scene base-path separate-files?]
  (let [base-name (path-filename base-path)
        lxs-path (str base-path ".lxs")
        lxs (str
             (apply lx-header lxs-path (:comments scene))
             (include-partials* (get-in scene [:includes :headers]))
             (:renderer scene)
             (:accel scene)
             (:sampler scene)
             (:integrator scene)
             (:volume-integrator scene)
             (:filter scene)
             (:film scene)
             (:camera scene)
             "WorldBegin\n\n"
             (include-partials* (get-in scene [:includes :partials]))
             (inject-scene-partial (:volumes scene) (str base-name ".lxv") separate-files?)
             (inject-scene-partial (:materials scene) (str base-name ".lxm") separate-files?)
             (inject-scene-partial (:geometry scene) (str base-name ".lxo") separate-files?)
             (:lights scene)
             "\nWorldEnd\n")]
    {:path lxs-path :body lxs}))

(defn- serialize-scene-component
  [group path]
  (when group
    {:path path :body (str (lx-header path) group)}))

(defn serialize-scene
  ([scene base-path]
     (serialize-scene scene base-path true))
  ([scene base-path separate?]
     (let [scene* (reduce
                   (fn [s [k type]]
                     (if-let [ents (k scene)]
                       (assoc s k (luxvalues-typed scene type ents))
                       s))
                   (select-keys scene [:comments :includes])
                   {:renderer :renderer
                    :accel :accelerator
                    :sampler :sampler
                    :integrator :integrator
                    :volume-integrator :volume-integrator
                    :filter :filter
                    :film :film
                    :camera :camera
                    :lights :light
                    :materials :material
                    :volumes :volume
                    :geometry :shape})
           serialized {:lxs (serialize-lxs scene* base-path separate?)}
           serialized (if-let [collector (get-in scene [:__config :mesh-collector])]
                        (merge serialized (collector scene))
                        serialized)]
       (if separate?
         (merge
          serialized
          {:lxm (serialize-scene-component (:materials scene*) (str base-path ".lxm"))
           :lxo (serialize-scene-component (:geometry scene*) (str base-path ".lxo"))
           :lxv (serialize-scene-component (:volumes scene*) (str base-path ".lxv"))})
         serialized))))

(defn export-scene
  ([serialized-map]
     (export-scene
      serialized-map
      (fn [_ path body]
        (println "exporting:" path)
        (with-open [out (io/output-stream path)]
          (let [buf ^bytes (as-byte-array body)]
            (.write out buf 0 (alength buf)))))))
  ([serialized-map f]
     (doseq [[id {:keys [path body]}] serialized-map]
       (when (and path body)
         (f id path body)))
     serialized-map))

(defn export-archived-scene
  [serialized-map out]
  (with-open [out (io/output-stream out), zip (ZipOutputStream. out)]
    (.setMethod zip ZipOutputStream/DEFLATED)
    (doseq [[id {:keys [path body]}] serialized-map]
      (when (and path body)
        (let [buf ^bytes (as-byte-array body)]
          (doto zip
            (.putNextEntry (ZipEntry. ^String path))
            (.write buf 0 (alength buf)))))))
  serialized-map)
