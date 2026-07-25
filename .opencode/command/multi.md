---
description: Run a query across multiple sub-agents in parallel and synthesize the results.
---

1. Identify all `@agent` mentions in the user prompt.
2. Trigger each mentioned sub-agent concurrently with the user query.
3. Once all sub-agents complete their analysis, combine their points into a structured comparison matrix highlighting where they agree and disagree.