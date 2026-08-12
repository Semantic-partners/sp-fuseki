#!/usr/bin/env bb
;; Regenerate .github/workflows/build.yml from ci/sp_fuseki/workflows.clj.
;;
;;   bb ci/generate.clj           write it
;;   bb ci/generate.clj --check   fail if the committed file is stale
;;
;; The pre-commit hook runs the first form and stages the result. --check is the
;; backstop for anyone who hasn't installed the hook (including fork PRs, which
;; is most contributors now the README invites them).

(require '[babashka.fs :as fs]
         '[sp-fuseki.workflows :as wf])

(def target ".github/workflows/build.yml")

(let [want (wf/->yaml wf/build-workflow)
      check? (contains? (set *command-line-args*) "--check")
      have (when (fs/exists? target) (slurp target))]
  (cond
    (and check? (= want have))
    (println "up to date:" target)

    check?
    (binding [*out* *err*]
      (println "STALE:" target "does not match ci/sp_fuseki/workflows.clj")
      (println "Run: bb ci/generate.clj  (and commit the result)")
      (System/exit 1))

    (= want have)
    (println "unchanged:" target)

    :else
    (do (spit target want)
        (println "wrote" target))))
