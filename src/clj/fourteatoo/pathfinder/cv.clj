(ns fourteatoo.pathfinder.cv
  (:require [cheshire.core :as json]
            [org.httpkit.client :as http]
            [clojure.string :as str]
            [diehard.core :as dh]
            [fourteatoo.pathfinder.config :as c]
            [mount.core :as mount]))


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

(def groq-api-url "https://api.groq.com/openai/v1/chat/completions")

(dh/defratelimiter groq-rl {:rate 1})

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
        payload {:model "llama-3.3-70b-versatile"
                 ;; Low temperature is critical to prevent hallucinations
                 :temperature 0.1 
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

(comment
  (def my-cv
    {:name "Alex Developer"
     :summary "Backend developer focused on functional programming and distributed databases."
     :skills ["Clojure" "Java" "DuckDB" "PostgreSQL" "Linux" "Docker" "Git"]
     :experience
     [{:company "Data Corp"
       :role "Senior Software Engineer"
       :years "2021 - Present"
       :bullets ["Built high-performance Clojure microservices handling 10M daily events."
                 "Optimized PostgreSQL queries reducing latency by 40%."
                 "Mentored junior developers and set up Docker CI/CD pipelines."]}
      {:company "WBS School"
       :role "Student"
       :years "2026"
       :bullets ["Learned Python basics"
                 "Refreshed statistcs"
                 "Dusted some ML"
                 "used some Generative AI"]}]})

  (def target-job
    {:description
     "Our Technology Solutions Group is a dynamic, fast-paced environment, with exciting changes on the horizon under new senior leadership. We are looking for you to build out scalable applications to support our Compliance, Finance, Data Governance, Operational Risk, and Human Resources teams. As a UI/UX Developer, you will be responsible for designing, developing Python based data access layers and rest APIs. for our software applications. You will work closely with cross-functional teams to understand requirements and deliver high-quality, responsive, scalable and flexible data access layer, rest api and other python based applications. We want you to see this challenge as a unique and valuable opportunity, so if this sounds interesting, then PGIM could be the place for you.\nYour Impact\nAPI development using RESTful or GraphQL standards.\nDevelop Data Interfaces using Python, Data Frames, Pandas etc.\nIntegration with back-end technologies and frameworks, such as Node.js, .NET, or Java.\nBuild Data Access Layer and metadata driven data query tool.\nWrite clean, reusable, and well-documented code that follows best practices and coding standards.\nCollaborate with back-end developers to integrate UI components with server-side systems and APIs. Ensure efficient data retrieval and synchronization, handle data validation and error handling, and optimize API performance.\nAlign with the Data Engineering Lead, Product Owner and Scrum Master in assessing business needs and transforming them into scalable applications.\nWork in an iterative/Agile environment as a good team player.\nAbility to manage multiple tasks and projects simultaneously.\nResearch emerging technologies and develop POC’s\nWork with the latest CI/CD DevOps deployment model\nRequired Skills\nYour Required Skills:\nBachelor's degree in Computer Science, Software Engineering, or a related field.\n5&plus; years hands on experience in Python development using NumPy, Pandas, Data frame etc.\nProven experience of API development using RESTful or GraphQL standards.\nExperience with back-end technologies and frameworks, such as Node.js, .NET, or Java, and integrating UI components with server-side systems.\nExperience with BitBucket, Jenkins, Gradle, Git. Cloud experience working with AWS S3/EC2/SQS\nDirect experience supporting front office end-users and sound understanding of capital markets within Fixed Income.\nExperience in and front-end frameworks such as Angular, React.\nKnowledge of Jira, Confluence, SAFe development methodology & DevOps.\nFamiliarity with security concepts authentication, authorization and SSL\nExcellent analytical and problem-solving skills with the ability to think quickly and offer alternatives both independently and within teams.\nProven ability to work quickly in a dynamic environment.\nExperience providing ongoing support for a wide-range of technology solutions\nExperience developing solutions in BI tools such as Tableau or Power BI is a plus\nAll qualified applicants will receive consideration for employment without regard to race, color, religion, sex, sexual orientation, gender, identity, national origin, disability, or protected veteran status.\nShow more\nShow less",
     :job-id 4690783672050812578,
     :job-title "Python API Developer Data Engineer #: 23-04526",
     :longitude 11.0775,
     :match-dist 0.874238,
     :skills
     "Python, DataFrames, Pandas, NumPy, RESTful, GraphQL, Node.js, .NET, Java, BitBucket, Jenkins, Gradle, Git, AWS S3, EC2, SQS, Angular, React, Jira, Confluence, SAFe, DevOps, Authentication, Authorization, SSL, Tableau, PowerBI",
     :job-link
     "https://www.linkedin.com/jobs/view/python-api-developer-data-engineer-%23-23-04526-at-hiretalent-diversity-staffing-recruiting-firm-3764247356",
     :latitude 49.4539,
     :job-level "Mid senior",
     :job-type "Onsite",
     :location "Nuremberg",
     :country "Germany",
     :company "HireTalent - Diversity Staffing & Recruiting Firm"}

    #_{:job/title "Senior Clojure & Data Engineer"
     :job/company "TechStart Inc"
     :job/description "Looking for a Clojure expert to optimize query engines, handle analytical databases (DuckDB/PostgreSQL), and build resilient backend infrastructure."})

  (generate-tailored-cv my-cv target-job))
