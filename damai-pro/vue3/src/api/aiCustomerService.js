import { getToken, getUserIdKey } from '@/utils/auth'

const BASE_URL = (import.meta.env.VITE_DAMAI_AI_BASE_URL || '/damai-ai-dev').replace(/\/$/, '')
const TIMEOUT = 30000

function buildUrl(path) {
  if (/^https?:\/\//.test(BASE_URL)) {
    return `${BASE_URL}${path}`
  }
  return `${BASE_URL}${path}`
}

async function requestJson(path, options = {}) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), TIMEOUT)
  try {
    const response = await fetch(buildUrl(path), {
      ...options,
      credentials: 'same-origin',
      headers: {
        'Content-Type': 'application/json;charset=utf-8',
        token: getToken() || '',
        'x-user-id': getUserIdKey() || '',
        ...(options.headers || {})
      },
      body: options.body ? JSON.stringify(options.body) : undefined,
      signal: controller.signal
    })
    const payload = await response.json().catch(() => ({}))
    if (!response.ok || (payload.code != null && payload.code !== 0)) {
      throw new Error(payload.message || payload.error || `请求失败: ${response.status}`)
    }
    return payload
  } finally {
    clearTimeout(timer)
  }
}

export async function getStarterPrompts() {
  return requestJson('/assistant/customer-service/starter-prompts')
}

export async function quickAnswer(payload) {
  return requestJson('/assistant/customer-service/quick-answer', {
    method: 'POST',
    body: payload
  })
}

export async function createRun(payload) {
  return requestJson('/assistant/runs', {
    method: 'POST',
    body: payload
  })
}

export async function getRun(runId) {
  return requestJson(`/assistant/runs/${runId}`)
}

export async function approveAction(runId, actionId) {
  return requestJson(`/assistant/runs/${runId}/actions/${actionId}/approve`, {
    method: 'POST',
    body: {}
  })
}

export async function rejectAction(runId, actionId) {
  return requestJson(`/assistant/runs/${runId}/actions/${actionId}/reject`, {
    method: 'POST',
    body: {}
  })
}

export async function createWorkItem(payload) {
  return requestJson('/assistant/customer-service/handoff', {
    method: 'POST',
    body: payload
  })
}

export async function submitFeedback(payload) {
  return requestJson('/assistant/customer-service/feedback', {
    method: 'POST',
    body: payload
  })
}

export async function streamRun(eventStreamPath, onEvent) {
  if (!eventStreamPath) {
    return
  }
  const response = await fetch(buildUrl(eventStreamPath), {
    credentials: 'same-origin',
    headers: {
      token: getToken() || '',
      'x-user-id': getUserIdKey() || ''
    }
  })
  if (!response.ok || !response.body) {
    throw new Error(`事件流连接失败: ${response.status}`)
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  while (true) {
    const { value, done } = await reader.read()
    if (done) {
      break
    }
    buffer += decoder.decode(value, { stream: true })
    const chunks = buffer.split('\n\n')
    buffer = chunks.pop() || ''
    for (const chunk of chunks) {
      const event = parseSseChunk(chunk)
      if (event) {
        await onEvent(event)
      }
    }
  }
}

function parseSseChunk(chunk) {
  const lines = chunk.split('\n')
  let event = 'message'
  let id = ''
  const dataLines = []
  lines.forEach((line) => {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
    } else if (line.startsWith('id:')) {
      id = line.slice(3).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trim())
    }
  })
  if (!dataLines.length) {
    return null
  }
  let data = dataLines.join('\n')
  try {
    data = JSON.parse(data)
  } catch (error) {
    data = { text: data }
  }
  return { event, id, data }
}
