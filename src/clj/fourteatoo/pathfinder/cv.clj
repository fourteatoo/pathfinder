(ns fourteatoo.pathfinder.cv
  (:require [cheshire.core :as json]
            [org.httpkit.client :as http]
            [clojure.string :as str]
            [diehard.core :as dh]
            [fourteatoo.pathfinder.config :as c]
            [mount.core :as mount]))


;; Alternative in emergency "llama-3.1-8b-instant"
;; The old retired one was "llama-3.3-70b-versatile"
(def groq-default-model "openai/gpt-oss-120b")
(def groq-api-url "https://api.groq.com/openai/v1/chat/completions")
(dh/defratelimiter groq-rl {:rate 1})

(defn api-key []
  (c/conf :groq-api-key))

(defn build-cv-tailoring-prompt
  [cv-edn job-edn]
  (str "You are an expert technical resume editor. Your task is to tailor the provided CV to align as closely as possible with the target job offer.

### STRICT RULES (ZERO HALLUCINATION):
1. **Source of Truth:** You must ONLY use facts, experiences, companies, dates, and technical tools explicitly present in the INPUT CV.
2. **No Fabrications:** DO NOT introduce any tools, technologies, responsibilities, or achievements that are not in the INPUT CV, even if the job offer requires them.
3. **Reframe & Highlight:** You MAY rephrase bullet points, reorder experience, and emphasize skills from the CV that directly match the job offer.
4. **Omit Irrelevant Details:** De-emphasize or drop details from the CV that are irrelevant to the target job offer to keep it concise.
5. **Output Format:** Output the tailored resume directly in clean, well-formatted Markdown. Do not include introductory conversational filler (e.g., 'Here is your tailored CV:').

### INPUT CV (EDN):
" (pr-str cv-edn) "

### TARGET JOB OFFER (EDN):
" (pr-str job-edn) "

### TAILORED CV (MARKDOWN):"))

(defn groq-complete [payload]
  (let [request-opts {:headers {"Authorization" (str "Bearer " (api-key))
                                "Content-Type"  "application/json"}
                      :body (json/generate-string payload)
                      :timeout 15000}
        response (dh/with-rate-limiter groq-rl
                   @(http/post groq-api-url request-opts))]
    (if (= 200 (:status response))
      (-> (json/parse-string (:body response) true)
          (get-in [:choices 0 :message :content]))
      (throw (ex-info "Groq API call failed" 
                      {:response response})))))

(defn generate-tailored-cv
  "Send the CV EDN and Job EDN to Groq and return a tailored Markdown CV."
  [cv-edn job-edn]
  (let [prompt (build-cv-tailoring-prompt cv-edn job-edn)
        payload {:model (or (c/conf :groq :model)
                            groq-default-model)
                 ;; Low temperature is critical to prevent hallucinations
                 :temperature (or (c/conf :groq :temperature) 0.1)
                 ;; :reasoning_format "hidden"
                 :messages [{:role "system"
                             :content "You are a professional, accurate resume editor. You strictly adhere to input facts."}
                            {:role "user"
                             :content prompt}]}
        response (groq-complete payload)]
    ;; Clean up any stray backticks if the model accidentally wraps markdown in code fences
    (-> response
        (str/replace #"^```markdown\n" "")
        (str/replace #"^```\n" "")
        (str/replace #"\n```$" "")
        str/trim)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; 

(comment
  (mount/start)
  (mount/stop)
  (c/conf))


