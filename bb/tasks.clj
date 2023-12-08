(ns tasks
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p :refer [shell]]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(defn sh [& args]
  (let [[config & _] args
        cmds (if (map? config) (rest args) args)]
    (println "$" (str/join " " cmds))
    (apply shell args)))

(defn -update-project-file [proj-edn k f args]
  (loop [visited '()
         remaining proj-edn]
    (if-let [current (first remaining)]
      (if (= current k)
        (concat (reverse visited)
                (list current (apply f (second remaining) args))
                (nthrest remaining 2))
        (recur (conj visited current) (rest remaining)))
      (concat proj-edn (list k (apply f nil args))))))

(defn update-project-file [dir k f & args]
  (let [file (fs/file (fs/path dir "project.clj"))]
    (-> (slurp file)
        (edn/read-string)
        (-update-project-file k f args)
        (pr-str)
        (->> (spit file)))))

(defn add-codox-dependency [proj-dir dep]
  (update-project-file proj-dir :profiles
                       update-in [:codox :dependencies]
                       (fnil conj []) dep))

(defn configure-codox [proj-dir config]
  (update-project-file proj-dir :codox merge config))

(defn generate-docs [proj-dir theme]
  (configure-codox proj-dir {:themes [theme]
                             :output-path (str "codox/" (name theme))})
  (shell {:dir proj-dir} "lein codox"))

(defn push-to-github-pages [repo dir]
  (let [temp-dir (fs/create-temp-dir)]
    (try
      (sh "git clone" repo temp-dir)
      (sh {:dir temp-dir} "git checkout -B gh-pages")
      (doall (map fs/delete-tree (fs/list-dir temp-dir "[!.]*")))
      (fs/copy-tree dir temp-dir)
      (sh {:dir temp-dir} "git add -A")
      (when (zero? (:exit (sh {:dir temp-dir :continue true} "git commit -m 'Auto rebuild codox'")))
        (sh {:dir temp-dir} "git push --set-upstream origin gh-pages -f"))
      (finally
        (fs/delete-tree temp-dir)))))

(defn theme-version []
  (-> (fs/file "project.clj") (slurp) (edn/read-string) (nth 2)))

(defn deploy-demo-site []
  (sh "lein compile")
  (sh "lein install")
  (let [proj-dir (fs/create-temp-dir)]
    (try
      (sh "git clone git@github.com:ring-clojure/ring.git" proj-dir)
      (add-codox-dependency proj-dir `[com.balloneij/familiar-codox-theme ~(theme-version)])
      (generate-docs proj-dir :default)
      (generate-docs proj-dir :familiar)
      (push-to-github-pages "git@github.com:balloneij/familiar-codox-theme.git"
                            (fs/path proj-dir "codox"))
      (finally
        (fs/delete-tree proj-dir)))))
