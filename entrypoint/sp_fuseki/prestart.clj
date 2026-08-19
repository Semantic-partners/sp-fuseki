(ns sp-fuseki.prestart
  "Pure planning for the pre-start hook: given what is in the hook directory,
  decide what runs, in what order, and what is refused outright.

  Pure for the same reason render.clj is — the decisions are the part worth
  testing, and they are testable without Docker (see test/prestart_test.clj).
  entrypoint.clj does the running.

  The rule this encodes: a file placed in the hook directory is an INSTRUCTION.
  Anything we cannot carry out is fatal, never skipped. Skipping is how you get a
  container that boots happily without the migration, the seeding or the lock
  check its operator believed had run — the same class of silent lie the image
  refuses in config, sourced from a directory instead of a file."
  (:require [clojure.string :as str]))

(defn- hidden?
  "Dotfiles are not hooks. Kubernetes ConfigMap and Secret volumes are built from
  `..data` symlinks and `..2026_08_19_10_00_00.123456` staging directories, and an
  editor leaves `.swp` files behind. Refusing to boot over those would make the
  feature unusable in exactly the place people mount things from."
  [name]
  (str/starts-with? name "."))

(defn plan
  "What to do with the hook directory, as data.

  `opts`:
    :path       the directory we looked in
    :explicit?  did an operator name it (FUSEKI_PRESTART), or is it the default
    :exists?    does the path exist at all
    :dir?       is it a directory
    :entries    seq of {:name, :dir?, :executable?}, one per child

  Returns one of:
    {:skip :absent}                     nothing to do, say nothing
    {:skip :empty, :path p}             directory present, no hooks in it
    {:run [\"10-a.sh\" ...], :ignored [names]}
    {:error \"...\"}                      refuse to boot, with the reason

  The default path missing is silence: an image that logged about an absent
  optional directory on every boot would train people to skip the log. A path
  someone EXPLICITLY set and that is missing is an instruction we could not
  honour, and that is fatal — the same distinction FUSEKI_CONFIG and
  FUSEKI_ADMIN_PASSWORD_FILE already make."
  [{:keys [path explicit? exists? dir? entries]}]
  (cond
    (and (not exists?) explicit?)
    {:error (str "FUSEKI_PRESTART set to " path " but nothing is there. "
                 "Mount the directory, or unset the variable.")}

    (not exists?)
    {:skip :absent}

    (not dir?)
    {:error (str path " is a file, not a directory. The hook path is a DIRECTORY "
                 "of scripts run in filename order — mount yours at "
                 path "/10-whatever.sh instead.")}

    :else
    (let [{hidden true visible false} (group-by (comp boolean hidden? :name) entries)
          dirs     (filter :dir? visible)
          files    (remove :dir? visible)
          unrunnable (remove :executable? files)]
      (cond
        (seq unrunnable)
        ;; Not a warning-and-skip. A script mounted without its executable bit is
        ;; the single most likely way to get a hook that "ran" and did nothing —
        ;; the bit is easy to lose over a bind mount, a git checkout on a
        ;; filesystem without it, or a COPY from a Windows host.
        {:error (str "not executable: "
                     (str/join ", " (sort (map #(str path "/" (:name %)) unrunnable)))
                     " — chmod +x, or move the file out of " path
                     ". A hook that cannot run is not a hook that is skipped.")}

        (empty? files)
        ;; The image pre-creates the default directory (so mounting a single hook
        ;; file into it cannot leave a root-owned parent), which makes "present and
        ;; empty" the ordinary state of every container that uses no hooks. Silence
        ;; there. An empty directory someone POINTED AT is worth a line, because in
        ;; practice it means the mount landed somewhere else.
        {:skip (if explicit? :empty :absent) :path path}

        :else
        ;; Filename order, so 10-/20-/30- means what everyone expects it to.
        {:run     (sort (map :name files))
         :ignored (sort (map :name (concat dirs hidden)))}))))
