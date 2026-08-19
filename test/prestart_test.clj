(ns prestart-test
  "The pre-start hook's decisions — what runs, in what order, and what refuses to
  boot. No Docker: this is the pure half, and test/smoke.clj asserts the other one
  (that a hook actually runs, and that a failing one stops the boot).

  These tests are documentation for the rule the feature exists to enforce: a file
  in the hook directory is an instruction, so anything we cannot carry out is
  fatal. Every :error case below is a case that a 'warn and skip' implementation
  would have booted through."
  (:require [clojure.test :refer [deftest testing is]]
            [sp-fuseki.prestart :as prestart]))

(defn- entry
  ([name] (entry name {}))
  ([name opts] (merge {:name name :dir? false :executable? true} opts)))

(defn- plan [opts]
  (prestart/plan (merge {:path "/fuseki/pre-start.d" :explicit? false
                         :exists? true :dir? true :entries []}
                        opts)))

;; ---------------------------------------------------------------------------
;; Absence — the distinction the whole config-authority argument turns on
;; ---------------------------------------------------------------------------

(deftest the-default-directory-being-absent-is-silence
  ;; The overwhelmingly common case: nobody mounted hooks. A line about it on
  ;; every boot would train people to skim the log they are meant to read.
  (is (= {:skip :absent} (plan {:exists? false}))))

(deftest a-path-someone-asked-for-and-that-is-missing-is-fatal
  ;; Same rule as FUSEKI_CONFIG and FUSEKI_ADMIN_PASSWORD_FILE: absence of a
  ;; default means "carry on", absence of an explicit instruction does not.
  (let [{:keys [error]} (plan {:exists? false :explicit? true :path "/hooks"})]
    (is (some? error) "an explicitly set FUSEKI_PRESTART that is missing must stop the boot")
    (is (re-find #"/hooks" error) "and name the path it was given")))

(deftest a-file-where-a-directory-belongs-says-so
  (let [{:keys [error]} (plan {:dir? false :path "/fuseki/pre-start.d"})]
    (is (re-find #"file, not a directory" error))
    (is (re-find #"10-whatever\.sh" error)
        "and shows the shape that would have worked, rather than only what is wrong")))

;; ---------------------------------------------------------------------------
;; What runs
;; ---------------------------------------------------------------------------

(deftest hooks-run-in-filename-order
  ;; The 10-/20-/30- convention is the only ordering people expect, and a
  ;; directory listing does not promise it.
  (is (= ["10-first.sh" "20-second.sh" "99-last.sh"]
         (:run (plan {:entries [(entry "99-last.sh") (entry "10-first.sh")
                                (entry "20-second.sh")]})))))

(deftest an-empty-directory-is-reported-only-when-it-was-asked-for
  ;; The image pre-creates the default directory, so "present and empty" is the
  ;; state of every container that uses no hooks — a line there would be noise on
  ;; every boot. An empty directory someone explicitly pointed FUSEKI_PRESTART at
  ;; is different: in practice it means the mount landed somewhere else, and that
  ;; is worth saying. Neither is a reason to refuse to boot.
  (is (= :absent (:skip (plan {:entries []})))
      "the default directory, empty: silence")
  (is (= :empty (:skip (plan {:entries [] :explicit? true})))
      "a directory someone named, empty: one line"))

(deftest directories-and-dotfiles-are-ignored-not-fatal
  ;; Kubernetes builds ConfigMap and Secret volumes out of `..data` symlinks and
  ;; timestamped staging directories. Treating those as broken hooks would make
  ;; the feature unusable in the place people most want to mount things from.
  (let [{:keys [run ignored]}
        (plan {:entries [(entry "10-real.sh")
                         (entry "..data" {:dir? true})
                         (entry "..2026_08_19_10_00_00.123456" {:dir? true})
                         (entry ".hidden.sh" {:executable? false})
                         (entry "subdir" {:dir? true})]})]
    (is (= ["10-real.sh"] run))
    (is (= ["..2026_08_19_10_00_00.123456" "..data" ".hidden.sh" "subdir"] (sort ignored))
        "ignored, and named — so a hook that silently did not run is still visible in the log")))

;; ---------------------------------------------------------------------------
;; What refuses
;; ---------------------------------------------------------------------------

(deftest a-hook-without-its-executable-bit-is-fatal
  ;; The likeliest failure in practice: the bit is lost over a bind mount, a
  ;; checkout on a filesystem without it, or a COPY from Windows. Warn-and-skip
  ;; here would produce exactly the outcome this feature was built to end — a
  ;; healthy container whose hook never ran.
  (let [{:keys [error run]} (plan {:entries [(entry "10-seed.sh" {:executable? false})]})]
    (is (nil? run) "nothing runs")
    (is (re-find #"not executable" error))
    (is (re-find #"chmod \+x" error) "and says the fix")
    (is (re-find #"/fuseki/pre-start\.d/10-seed\.sh" error)
        "naming the full path, because the operator is looking at their host, not ours")))

(deftest one-unrunnable-hook-stops-all-of-them
  ;; Not "run what we can". Hooks are ordered because they depend on each other;
  ;; running 10 and 30 while skipping 20 is a state nobody designed.
  (let [{:keys [error run]} (plan {:entries [(entry "10-ok.sh")
                                             (entry "20-broken.sh" {:executable? false})
                                             (entry "30-ok.sh")]})]
    (is (nil? run))
    (is (re-find #"20-broken\.sh" error))))

(deftest every-unrunnable-hook-is-named-at-once
  ;; One boot, one list. Naming the first only turns a two-file mistake into two
  ;; edit-rebuild-run cycles.
  (let [{:keys [error]} (plan {:entries [(entry "10-a.sh" {:executable? false})
                                         (entry "20-b.sh" {:executable? false})]})]
    (is (re-find #"10-a\.sh" error))
    (is (re-find #"20-b\.sh" error))))

(deftest the-errors-are-instructions-not-diagnoses
  ;; A house rule worth a test: every refusal above tells you what to do, not
  ;; just what is wrong. This one fails if someone adds a bare "invalid" message.
  (doseq [[label opts] [["missing explicit path" {:exists? false :explicit? true}]
                        ["file not directory"    {:dir? false}]
                        ["not executable"        {:entries [(entry "x.sh" {:executable? false})]}]]]
    (testing label
      (let [{:keys [error]} (plan opts)]
        (is (re-find #"(?i)mount|chmod|unset|instead" error)
            (str label ": the message must contain the fix, not only the fault"))))))
