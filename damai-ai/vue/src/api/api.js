const BASE_URL = (import.meta.env.VITE_DAMAI_AI_BASE_URL || '/damai-ai-dev').replace(/\/$/, '')
const DAMAI_PRO_LOGIN_URL = import.meta.env.VITE_DAMAI_PRO_LOGIN_URL || 'http://127.0.0.1:5173/login'
const TIMEOUT = 30000

class APIError extends Error {
  constructor(message, status, payload = null) {
    super(message)
    this.status = status
    this.payload = payload
    this.name = 'APIError'
  }
}

function getCookie(name) {
  const cookie = document.cookie
    .split('; ')
    .find(row => row.startsWith(`${name}=`))
  return cookie ? decodeURIComponent(cookie.split('=')[1]) : ''
}

export function getAuthState() {
  const token = getCookie('Admin-Token')
  const userId = getCookie('userId')
  return {
    token,
    userId,
    isAuthenticated: Boolean(token)
  }
}

export function ensureAuthenticated() {
  const auth = getAuthState()
  if (!auth.isAuthenticated) {
    window.location.href = DAMAI_PRO_LOGIN_URL
    return false
  }
  return true
}

function authHeaders() {
  const { token, userId } = getAuthState()
  return {
    token: token || '',
    'x-user-id': userId || ''
  }
}

async function fetchWithTimeout(url, options = {}) {
  const controller = new AbortController()
  const timeoutId = setTimeout(() => controller.abort(), TIMEOUT)

  try {
    const response = await fetch(url, {
      ...options,
      credentials: 'same-origin',
      headers: {
        ...authHeaders(),
        ...(options.headers || {})
      },
      signal: controller.signal
    })

    if (!response.ok) {
      if (response.status === 401) {
        ensureAuthenticated()
      }
      let message = `HTTP error! status: ${response.status}`
      let payload = null
      try {
        payload = await response.clone().json()
        message = payload?.message || payload?.data?.message || payload?.error || message
      } catch (error) {
        const text = await response.clone().text()
        if (text) {
          message = text
        }
      }
      throw new APIError(message, response.status, payload)
    }

    return response
  } finally {
    clearTimeout(timeoutId)
  }
}

export function createAbsoluteUrl(rawUrl) {
  return /^https?:\/\//.test(rawUrl)
    ? new URL(rawUrl)
    : new URL(rawUrl, window.location.origin)
}

function buildUrl(path, params = {}) {
  const rawUrl = `${BASE_URL}${path}`
  const url = createAbsoluteUrl(rawUrl)
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null) {
      url.searchParams.append(key, value)
    }
  })
  return url
}

export function createSseIterator(reader) {
  return {
    async *[Symbol.asyncIterator]() {
      const decoder = new TextDecoder('utf-8')
      let buffer = ''

      while (true) {
        const { value, done } = await reader.read()
        if (done) {
          if (buffer.trim()) {
            yield parseSseChunk(buffer)
          }
          break
        }

        buffer += decoder.decode(value, { stream: true })
        const chunks = buffer.split('\n\n')
        buffer = chunks.pop() || ''

        for (const chunk of chunks) {
          const parsed = parseSseChunk(chunk)
          if (parsed) {
            yield parsed
          }
        }
      }
    }
  }
}

export function parseSseChunk(chunk) {
  if (!chunk || !chunk.trim()) {
    return null
  }
  const lines = chunk.split('\n')
  let event = 'message'
  let id = ''
  const dataLines = []
  for (const line of lines) {
    if (line.startsWith(':')) {
      continue
    } else if (line.startsWith('id:')) {
      id = line.slice(3).trim()
    } else if (line.startsWith('event:')) {
      event = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trim())
    }
  }
  if (!id && dataLines.length === 0 && event === 'message') {
    return null
  }
  const rawData = dataLines.join('\n')
  let data = rawData
  try {
    data = rawData ? JSON.parse(rawData) : null
  } catch (error) {
    data = rawData
  }
  return { id, event, data }
}

async function streamRequest(path, params = {}) {
  const response = await fetchWithTimeout(buildUrl(path, params))
  return createSseIterator(response.body.getReader())
}

async function requestJson(path, options = {}) {
  const response = await fetchWithTimeout(buildUrl(path), {
    method: options.method || 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(options.headers || {})
    },
    body: options.body ? JSON.stringify(options.body) : undefined
  })
  return response.json()
}

export const assistantAPI = {
  async getCapabilities() {
    const response = await fetchWithTimeout(buildUrl('/assistant/capabilities'))
    return response.json()
  },

  async createRun(payload) {
    return requestJson('/assistant/runs', {
      method: 'POST',
      body: payload
    })
  },

  async sendMessage(message, chatId, clientContext = {}) {
    const result = await this.createRun({
      chatId,
      message,
      clientContext
    })
    return this.streamRun(result?.data?.eventStreamPath)
  },

  async streamRun(eventStreamPath) {
    if (!eventStreamPath) {
      throw new APIError('Missing event stream path', 500)
    }
    const response = await fetchWithTimeout(buildUrl(eventStreamPath))
    return createSseIterator(response.body.getReader())
  },

  async getRun(runId) {
    const response = await fetchWithTimeout(buildUrl(`/assistant/runs/${runId}`))
    return response.json()
  },

  async getRunGraph(runId) {
    const response = await fetchWithTimeout(buildUrl(`/assistant/runs/${runId}/graph`))
    return response.json()
  },

  async resumeRun(runId) {
    const response = await fetchWithTimeout(buildUrl(`/assistant/runs/${runId}/resume`), {
      method: 'POST'
    })
    return response.json()
  },

  async replayRun(runId) {
    const response = await fetchWithTimeout(buildUrl(`/assistant/runs/${runId}/replay`), {
      method: 'POST'
    })
    return response.json()
  },

  async listConversations() {
    const response = await fetchWithTimeout(buildUrl('/assistant/conversations'))
    return response.json()
  },

  async listMessages(chatId) {
    const response = await fetchWithTimeout(buildUrl(`/assistant/conversations/${chatId}/messages`))
    return response.json()
  },

  async approveAction(runId, actionId) {
    const response = await fetchWithTimeout(buildUrl(`/assistant/runs/${runId}/actions/${actionId}/approve`), {
      method: 'POST'
    })
    return response.json()
  },

  async rejectAction(runId, actionId) {
    const response = await fetchWithTimeout(buildUrl(`/assistant/runs/${runId}/actions/${actionId}/reject`), {
      method: 'POST'
    })
    return response.json()
  },

  async reindexKnowledge() {
    const response = await fetchWithTimeout(buildUrl('/assistant/admin/knowledge/reindex'), {
      method: 'POST'
    })
    return response.json()
  },

  async listSkills() {
    const response = await fetchWithTimeout(buildUrl('/assistant/skills'))
    return response.json()
  },

  async getSkill(skillId) {
    const response = await fetchWithTimeout(buildUrl(`/assistant/skills/${skillId}`))
    return response.json()
  },

  async updateSkill(skillId, payload) {
    const response = await fetchWithTimeout(buildUrl(`/assistant/admin/skills/${skillId}`), {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(payload)
    })
    return response.json()
  },

  async createSkillEvalRun(skillId) {
    const response = await fetchWithTimeout(buildUrl(`/assistant/admin/skills/${skillId}/eval-runs`), {
      method: 'POST'
    })
    return response.json()
  },

  async runEvalSuite(suite, payload = {}) {
    return requestJson(`/assistant/evals/${suite}/run`, {
      method: 'POST',
      body: payload
    })
  },

  async getEvalRun(suite, evalRunId) {
    const response = await fetchWithTimeout(buildUrl(`/assistant/evals/${suite}/runs/${evalRunId}`))
    return response.json()
  },

  async getQualityGate() {
    const response = await fetchWithTimeout(buildUrl('/assistant/admin/quality-gates/latest'))
    return response.json()
  },

  async getMcpGovernance() {
    const response = await fetchWithTimeout(buildUrl('/assistant/admin/mcp/governance'))
    return response.json()
  },

  async readMcpResource(payload = {}) {
    return requestJson('/assistant/admin/mcp/resources/read', {
      method: 'POST',
      body: payload
    })
  },

  async renderMcpPrompt(payload = {}) {
    return requestJson('/assistant/admin/mcp/prompts/render', {
      method: 'POST',
      body: payload
    })
  }
}

export const customerServiceAPI = {
  async getStarterPrompts() {
    const response = await fetchWithTimeout(buildUrl('/assistant/customer-service/starter-prompts'))
    return response.json()
  },

  async quickAnswer(payload = {}) {
    return requestJson('/assistant/customer-service/quick-answer', {
      method: 'POST',
      body: payload
    })
  },

  async createWorkItem(payload = {}) {
    return requestJson('/assistant/customer-service/handoff', {
      method: 'POST',
      body: payload
    })
  },

  async getWorkItem(workItemId) {
    const response = await fetchWithTimeout(buildUrl(`/assistant/customer-service/work-items/${workItemId}`))
    return response.json()
  },

  async getDashboard() {
    const response = await fetchWithTimeout(buildUrl('/assistant/admin/customer-service/dashboard'))
    return response.json()
  },

  async getTopQuestions() {
    const response = await fetchWithTimeout(buildUrl('/assistant/admin/customer-service/top-questions'))
    return response.json()
  },

  async getUnresolvedCases() {
    const response = await fetchWithTimeout(buildUrl('/assistant/admin/customer-service/unresolved-cases'))
    return response.json()
  },

  async getWorkItems(params = {}) {
    const response = await fetchWithTimeout(buildUrl('/assistant/admin/customer-service/work-items', params))
    return response.json()
  }
}

export const ragEvalAPI = {
  async getRunReport(evalRunId) {
    const response = await fetchWithTimeout(buildUrl(`/assistant/admin/rag-eval/runs/${evalRunId}/report`))
    return response.json()
  },

  async compareRun(evalRunId, baselineRunId) {
    const response = await fetchWithTimeout(buildUrl(`/assistant/admin/rag-eval/runs/${evalRunId}/compare`, { baselineRunId }))
    return response.json()
  },

  async listBadCases(reviewStatus = 'PENDING', failureType = '') {
    const response = await fetchWithTimeout(buildUrl('/assistant/admin/rag-eval/bad-cases', { reviewStatus, failureType }))
    return response.json()
  },

  async convertBadCase(badCaseId, payload = {}) {
    const response = await fetchWithTimeout(buildUrl(`/assistant/admin/rag-eval/bad-cases/${badCaseId}/convert`), {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(payload)
    })
    return response.json()
  },

  async reviewBadCase(badCaseId, payload = {}) {
    const response = await fetchWithTimeout(buildUrl(`/assistant/admin/rag-eval/bad-cases/${badCaseId}/review`), {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(payload)
    })
    return response.json()
  },

  async createReindexJob(taskType = 'full') {
    const response = await fetchWithTimeout(buildUrl('/assistant/admin/knowledge/reindex-jobs', { taskType }), {
      method: 'POST'
    })
    return response.json()
  },

  async listIngestionTasks() {
    const response = await fetchWithTimeout(buildUrl('/assistant/admin/knowledge/ingestion/tasks'))
    return response.json()
  }
}

export const aiOpsAdminAPI = {
  async listProviders() {
    const response = await fetchWithTimeout(buildUrl('/assistant/admin/ops/providers'))
    return response.json()
  },

  async listRunbooks() {
    const response = await fetchWithTimeout(buildUrl('/assistant/admin/ops/runbooks'))
    return response.json()
  },

  async buildRcaEvidence(payload = {}) {
    const response = await fetchWithTimeout(buildUrl('/assistant/admin/ops/rca-evidence'), {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(payload)
    })
    return response.json()
  }
}

export const promptVersionAPI = {
  async list(promptKey = '') {
    const response = await fetchWithTimeout(buildUrl('/assistant/admin/prompt-versions', { promptKey }))
    return response.json()
  },

  async listReleaseRecords(promptKey = '') {
    const response = await fetchWithTimeout(buildUrl('/assistant/admin/prompt-versions/release-records', { promptKey }))
    return response.json()
  },

  async createDraft(payload) {
    return requestJson('/assistant/admin/prompt-versions', {
      method: 'POST',
      body: payload
    })
  },

  async buildReleasePlan(payload) {
    return requestJson('/assistant/admin/prompt-versions/release-plan', {
      method: 'POST',
      body: payload
    })
  },

  async publish(payload) {
    return requestJson('/assistant/admin/prompt-versions/publish', {
      method: 'POST',
      body: payload
    })
  },

  async promote(payload) {
    return requestJson('/assistant/admin/prompt-versions/promote', {
      method: 'POST',
      body: payload
    })
  },

  async rollback(payload) {
    return requestJson('/assistant/admin/prompt-versions/rollback', {
      method: 'POST',
      body: payload
    })
  },

  async invalidateCache() {
    return requestJson('/assistant/admin/prompt-versions/invalidate-cache', {
      method: 'POST'
    })
  }
}

export const dataOpsAdminAPI = {
  async reloadCatalog() {
    const response = await fetchWithTimeout(buildUrl('/assistant/admin/dataops/catalog/reload'), {
      method: 'POST'
    })
    return response.json()
  },

  async getCatalog() {
    const response = await fetchWithTimeout(buildUrl('/assistant/admin/dataops/catalog'))
    return response.json()
  },

  async rebuildMetrics(payload = {}) {
    const response = await fetchWithTimeout(buildUrl('/assistant/admin/dataops/metrics/rebuild'), {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(payload)
    })
    return response.json()
  }
}
