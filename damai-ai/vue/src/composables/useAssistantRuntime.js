import { computed, nextTick, ref } from 'vue'
import { assistantAPI } from '../api/api'

function normalizeMessage(message, index) {
  return {
    id: message.id || `${message.role || 'assistant'}-${index}`,
    role: message.role || 'assistant',
    content: message.content || '',
    timestamp: message.createdAt || message.timestamp || new Date().toISOString()
  }
}

function normalizeConversation(chat) {
  return {
    id: chat.chatId,
    title: chat.title || '新的助手会话',
    latestRunId: chat.latestRunId || '',
    routeType: chat.routeType || '',
    workflowStatus: chat.latestStatus || chat.workflowStatus || ''
  }
}

function parseJsonSafely(value, fallback) {
  if (!value) {
    return fallback
  }
  if (typeof value === 'object') {
    return value
  }
  try {
    return JSON.parse(value)
  } catch (error) {
    return fallback
  }
}

function resolveActionFromDetail(detail) {
  if (!detail) {
    return null
  }
  const latestAction = detail.latestAction || null
  const latestStatus = latestAction?.actionStatus || latestAction?.status || ''
  if (detail.pendingAction) {
    return detail.pendingAction
  }
  if (latestStatus === 'APPROVING' || latestStatus === 'ORDERING') {
    return latestAction
  }
  return null
}

function normalizeList(value) {
  return Array.isArray(value) ? value.filter(Boolean) : []
}

function normalizeStageTrace(trace, index = 0) {
  if (!trace) {
    return null
  }
  return {
    ...trace,
    id: trace.traceId || trace.id || `stage-${index}`,
    metadata: parseJsonSafely(trace.metadata, {}),
    success: typeof trace.success === 'boolean' ? trace.success : null
  }
}

function normalizeRetrievalTrace(trace, index = 0) {
  if (!trace) {
    return null
  }
  const denseHits = normalizeList(parseJsonSafely(trace.denseHitsJson, []))
  const sparseHits = normalizeList(parseJsonSafely(trace.sparseHitsJson, []))
  const fusedHits = normalizeList(parseJsonSafely(trace.fusedHitsJson, []))
  const finalHits = normalizeList(parseJsonSafely(trace.finalHitsJson, []))
  return {
    ...trace,
    id: trace.traceId || trace.id || `retrieval-${index}`,
    metadata: parseJsonSafely(trace.metadataJson, {}),
    denseHits,
    sparseHits,
    fusedHits,
    finalHits,
    denseHitCount: denseHits.length,
    sparseHitCount: sparseHits.length,
    fusedHitCount: fusedHits.length,
    finalHitCount: finalHits.length
  }
}

function normalizeStructuredMemory(value, fallbackSummary = '') {
  const structured = parseJsonSafely(value, {})
  return {
    summary: structured?.summary || fallbackSummary || '',
    conversationGoal: structured?.conversationGoal || '',
    stableFacts: normalizeList(structured?.stableFacts),
    pendingQuestions: normalizeList(structured?.pendingQuestions),
    retrievalHints: normalizeList(structured?.retrievalHints)
  }
}

function normalizeMemorySummary(summary) {
  if (!summary) {
    return null
  }
  const structuredMemory = normalizeStructuredMemory(summary.memoryJson, summary.summary || '')
  const hasContent = [
    structuredMemory.summary,
    structuredMemory.conversationGoal,
    ...structuredMemory.stableFacts,
    ...structuredMemory.pendingQuestions,
    ...structuredMemory.retrievalHints
  ].some(value => typeof value === 'string' && value.trim())
  return {
    ...summary,
    structuredMemory,
    hasContent
  }
}

export function useAssistantRuntime() {
  const messagesRef = ref(null)
  const inputRef = ref(null)
  const userInput = ref('')
  const isStreaming = ref(false)
  const currentChatId = ref('')
  const currentRunId = ref('')
  const currentRoute = ref('')
  const currentSkillId = ref('')
  const currentSkillName = ref('')
  const currentMessages = ref([])
  const conversations = ref([])
  const runTimeline = ref([])
  const evidenceCards = ref([])
  const stageTraces = ref([])
  const retrievalTraces = ref([])
  const memorySummary = ref(null)
  const pendingAction = ref(null)
  const toolCalls = ref([])
  const clarificationOptions = ref([])
  const errorMessage = ref('')
  const refusalReason = ref('')
  const runStatus = ref('')
  const capabilities = ref({ admin: false, allowedRoutes: ['business', 'knowledge', 'general'], skills: [] })
  const seenEventIds = ref(new Set())

  const orderedTimeline = computed(() => [...runTimeline.value].reverse())
  const hasMessages = computed(() => currentMessages.value.length > 0)
  const canUseOps = computed(() => capabilities.value.allowedRoutes?.includes('ops') === true)

  const scrollToBottom = async () => {
    await nextTick()
    if (messagesRef.value) {
      messagesRef.value.scrollTop = messagesRef.value.scrollHeight
    }
  }

  const adjustTextareaHeight = () => {
    if (!inputRef.value) {
      return
    }
    inputRef.value.style.height = 'auto'
    inputRef.value.style.height = `${Math.min(inputRef.value.scrollHeight, 220)}px`
  }

  const resetRunState = () => {
    runTimeline.value = []
    evidenceCards.value = []
    stageTraces.value = []
    retrievalTraces.value = []
    memorySummary.value = null
    pendingAction.value = null
    toolCalls.value = []
    clarificationOptions.value = []
    refusalReason.value = ''
    errorMessage.value = ''
    runStatus.value = ''
    currentRoute.value = ''
    currentSkillId.value = ''
    currentSkillName.value = ''
    seenEventIds.value = new Set()
  }

  const resolveSkillName = (skillId) => {
    if (!skillId) {
      return ''
    }
    return capabilities.value.skills?.find(item => item.skillId === skillId)?.name || ''
  }

  const upsertStageTrace = (trace) => {
    if (!trace?.traceId) {
      stageTraces.value.push(trace)
      return
    }
    const index = stageTraces.value.findIndex(item => item.traceId === trace.traceId)
    if (index >= 0) {
      stageTraces.value[index] = {
        ...stageTraces.value[index],
        ...trace
      }
      return
    }
    stageTraces.value.push(trace)
  }

  const upsertRetrievalTrace = (trace) => {
    if (!trace?.traceId) {
      retrievalTraces.value.push(trace)
      return
    }
    const index = retrievalTraces.value.findIndex(item => item.traceId === trace.traceId)
    if (index >= 0) {
      retrievalTraces.value[index] = {
        ...retrievalTraces.value[index],
        ...trace
      }
      return
    }
    retrievalTraces.value.push(trace)
  }

  const applyRunEvents = (events) => {
    if (!Array.isArray(events)) {
      runTimeline.value = []
      seenEventIds.value = new Set()
      return
    }
    seenEventIds.value = new Set(events.map(event => event.eventId).filter(Boolean))
    runTimeline.value = [...events]
      .map((event, index) => ({
        id: event.eventId || `evt-${index}`,
        event: event.eventType,
        data: parseJsonSafely(event.payloadJson, {}),
        at: event.createTime || new Date().toISOString()
      }))
      .reverse()
  }

  const applyRunDetail = (detail) => {
    pendingAction.value = normalizePendingAction(resolveActionFromDetail(detail))
    evidenceCards.value = detail?.retrieval?.finalHitsJson
      ? normalizeList(parseJsonSafely(detail.retrieval.finalHitsJson, []))
      : []
    applyRunEvents(detail?.events)
    stageTraces.value = Array.isArray(detail?.stageTraces)
      ? detail.stageTraces.map((trace, index) => normalizeStageTrace(trace, index)).filter(Boolean)
      : []
    retrievalTraces.value = Array.isArray(detail?.retrievalTraces)
      ? detail.retrievalTraces.map((trace, index) => normalizeRetrievalTrace(trace, index)).filter(Boolean)
      : []
    memorySummary.value = normalizeMemorySummary(detail?.memorySummary)
    if (detail?.run?.routeType) {
      currentRoute.value = detail.run.routeType
    }
    if (detail?.run?.skillId) {
      currentSkillId.value = detail.run.skillId
      currentSkillName.value = resolveSkillName(detail.run.skillId) || currentSkillName.value
    }
    if (detail?.run?.runStatus) {
      runStatus.value = detail.run.runStatus
    }
  }

  const appendAssistantDelta = async (delta) => {
    if (!delta) {
      return
    }
    const lastMessage = currentMessages.value[currentMessages.value.length - 1]
    if (lastMessage?.role === 'assistant') {
      lastMessage.content += delta
    } else {
      currentMessages.value.push({
        id: `assistant-${Date.now()}`,
        role: 'assistant',
        content: delta,
        timestamp: new Date().toISOString()
      })
    }
    await scrollToBottom()
  }

  const recordTimeline = (event, data, eventId = '') => {
    runTimeline.value.unshift({
      id: eventId || `${event}-${runTimeline.value.length}-${Date.now()}`,
      event,
      data,
      at: new Date().toISOString()
    })
  }

  const normalizePendingAction = (action) => {
    if (!action) {
      return null
    }
    const preview = parseJsonSafely(action.previewJson || action.preview, {})
    const status = action.status || action.actionStatus || ''
    return {
      ...action,
      runId: action.runId || currentRunId.value,
      status,
      preview,
      summary: action.previewSummary || action.summary || preview.previewSummary || preview.previewText || '',
      processing: status === 'APPROVING' || status === 'ORDERING'
    }
  }

  const updateConversationTitle = (chatId, title) => {
    if (!chatId || !title) {
      return
    }
    const target = conversations.value.find(item => item.id === chatId)
    if (target) {
      target.title = title
    }
  }

  const loadConversations = async () => {
    const result = await assistantAPI.listConversations()
    conversations.value = Array.isArray(result?.data)
      ? result.data.map(normalizeConversation)
      : []
  }

  const loadCapabilities = async () => {
    const result = await assistantAPI.getCapabilities()
    capabilities.value = {
      admin: result?.data?.admin === true,
      allowedRoutes: Array.isArray(result?.data?.allowedRoutes) ? result.data.allowedRoutes : ['business', 'knowledge', 'general'],
      skills: Array.isArray(result?.data?.skills) ? result.data.skills : []
    }
  }

  const loadConversation = async (chatId) => {
    const result = await assistantAPI.listMessages(chatId)
    const conversation = conversations.value.find(item => item.id === chatId)
    currentChatId.value = chatId
    currentMessages.value = Array.isArray(result?.data)
      ? result.data.map(normalizeMessage)
      : []
    resetRunState()
    currentRunId.value = conversation?.latestRunId || ''
    currentRoute.value = conversation?.routeType || ''
    runStatus.value = conversation?.workflowStatus || ''

    if (conversation?.latestRunId) {
      const runDetail = await assistantAPI.getRun(conversation.latestRunId)
      applyRunDetail(runDetail?.data)
    }
    await scrollToBottom()
  }

  const startNewChat = () => {
    currentChatId.value = ''
    currentRunId.value = ''
    currentMessages.value = []
    resetRunState()
    userInput.value = ''
  }

  const handleRunEvent = async (event, data, eventId = '') => {
    switch (event) {
      case 'run.started':
        currentRunId.value = data?.runId || currentRunId.value
        currentChatId.value = data?.chatId || currentChatId.value
        runStatus.value = data?.status || 'RUNNING'
        recordTimeline(event, data, eventId)
        break
      case 'route.selected':
        currentRoute.value = data?.routeType || ''
        currentSkillId.value = data?.skillId || ''
        currentSkillName.value = data?.skillName || resolveSkillName(data?.skillId) || ''
        updateConversationTitle(currentChatId.value, data?.conversationTitle)
        recordTimeline(event, data, eventId)
        break
      case 'clarification.required':
        clarificationOptions.value = Array.isArray(data?.options)
          ? data.options.filter(option => canUseOps.value || !/日志|trace|cpu|jvm|监控|运维/i.test(option))
          : []
        recordTimeline(event, data, eventId)
        break
      case 'skill.started':
      case 'skill.completed':
        currentSkillId.value = data?.skillId || currentSkillId.value
        currentSkillName.value = data?.skillName || resolveSkillName(data?.skillId) || currentSkillName.value
        recordTimeline(event, data, eventId)
        break
      case 'stage.started':
        upsertStageTrace(normalizeStageTrace({
          traceId: data?.traceId,
          stepKey: data?.stageKey,
          requestType: data?.requestType,
          metadata: data?.metadata,
          createTime: new Date().toISOString()
        }))
        recordTimeline(event, data, eventId)
        break
      case 'stage.completed':
        upsertStageTrace(normalizeStageTrace({
          traceId: data?.traceId,
          stepKey: data?.stageKey,
          latencyMs: data?.latencyMs,
          metadata: data?.metadata,
          success: true,
          editTime: new Date().toISOString()
        }))
        recordTimeline(event, data, eventId)
        break
      case 'stage.failed':
        upsertStageTrace(normalizeStageTrace({
          traceId: data?.traceId,
          stepKey: data?.stageKey,
          latencyMs: data?.latencyMs,
          metadata: data?.metadata,
          errorMessage: data?.message,
          success: false,
          editTime: new Date().toISOString()
        }))
        recordTimeline(event, data, eventId)
        break
      case 'knowledge.route.shadowed':
        upsertRetrievalTrace(normalizeRetrievalTrace({
          traceId: `shadow-route-${currentRunId.value || Date.now()}`,
          traceType: 'route',
          stepKey: 'knowledge.shadow_route',
          originalQuery: data?.query || '',
          metadataJson: {
            shadowRoute: {
              query: data?.query || '',
              mode: data?.mode || 'shadow',
              scopeCandidates: normalizeList(data?.scopeCandidates),
              topicCandidates: normalizeList(data?.topicCandidates),
              documentCandidates: normalizeList(data?.documentCandidates)
            }
          },
          createTime: new Date().toISOString()
        }))
        recordTimeline(event, data, eventId)
        break
      case 'retrieval.started':
      case 'retrieval.completed':
        if (Array.isArray(data?.sources)) {
          evidenceCards.value = data.sources
        }
        if (data?.refusalReason) {
          refusalReason.value = data.refusalReason
        }
        recordTimeline(event, data, eventId)
        break
      case 'tool.started':
      case 'tool.completed':
        toolCalls.value.unshift({
          id: eventId || `${event}-${Date.now()}`,
          event,
          toolName: data?.toolName || data?.name || 'tool',
          summary: data?.summary || data?.status || ''
        })
        recordTimeline(event, data, eventId)
        break
      case 'action.required':
        pendingAction.value = normalizePendingAction(data)
        recordTimeline(event, data, eventId)
        break
      case 'action.processing':
        pendingAction.value = normalizePendingAction({
          ...(pendingAction.value || {}),
          ...data
        })
        recordTimeline(event, data, eventId)
        break
      case 'message.delta':
        await appendAssistantDelta(data?.delta || '')
        break
      case 'message.replaced': {
        const lastMessage = currentMessages.value[currentMessages.value.length - 1]
        if (lastMessage?.role === 'assistant') {
          lastMessage.content = data?.content || lastMessage.content
        }
        recordTimeline(event, data, eventId)
        await scrollToBottom()
        break
      }
      case 'message.completed':
        runStatus.value = data?.status || 'COMPLETED'
        recordTimeline(event, data, eventId)
        if (data?.conversationTitle) {
          updateConversationTitle(currentChatId.value, data.conversationTitle)
        }
        break
      case 'guardrail.warn':
      case 'guardrail.triggered':
        refusalReason.value = Array.isArray(data?.reasons) ? data.reasons.join('；') : (data?.message || refusalReason.value)
        recordTimeline(event, data, eventId)
        break
      case 'run.completed':
        runStatus.value = data?.status || 'COMPLETED'
        recordTimeline(event, data, eventId)
        break
      case 'run.failed':
        runStatus.value = data?.status || 'FAILED'
        errorMessage.value = data?.message || '统一助手执行失败'
        recordTimeline(event, data, eventId)
        break
      default:
        recordTimeline(event, data, eventId)
        break
    }
  }

  const refreshRunState = async (runId) => {
    if (!runId) {
      return
    }
    const runDetail = await assistantAPI.getRun(runId)
    applyRunDetail(runDetail?.data)
  }

  const sendMessage = async (presetMessage, clientContextOverrides = {}) => {
    const message = (presetMessage ?? userInput.value).trim()
    if (!message || isStreaming.value) {
      return
    }

    currentMessages.value.push({
      id: `user-${Date.now()}`,
      role: 'user',
      content: message,
      timestamp: new Date().toISOString()
    })
    userInput.value = ''
    adjustTextareaHeight()
    resetRunState()
    isStreaming.value = true
    await scrollToBottom()

    try {
      const stream = await assistantAPI.sendMessage(message, currentChatId.value || null, {
        entry: 'assistant-hub',
        ...clientContextOverrides
      })
      for await (const item of stream) {
        if (!item) {
          continue
        }
        if (item.id && seenEventIds.value.has(item.id)) {
          continue
        }
        if (item.id) {
          seenEventIds.value.add(item.id)
        }
        await handleRunEvent(item.event, item.data, item.id)
      }
      await refreshRunState(currentRunId.value)
      await loadConversations()
    } catch (error) {
      errorMessage.value = error.message || '统一助手执行失败'
    } finally {
      isStreaming.value = false
    }
  }

  const resolveAction = async (action, decision) => {
    if (!action?.runId || !action?.actionId || action.processing) {
      return
    }
    pendingAction.value = normalizePendingAction({
      ...action,
      status: decision === 'approve' ? 'APPROVING' : action.status,
      processing: decision === 'approve'
    })
    const result = decision === 'approve'
      ? await assistantAPI.approveAction(action.runId, action.actionId)
      : await assistantAPI.rejectAction(action.runId, action.actionId)

    pendingAction.value = null
    currentMessages.value.push({
      id: `assistant-action-${Date.now()}`,
      role: 'assistant',
      content: result?.data?.message || (decision === 'approve' ? '审批已通过。' : '审批已拒绝。'),
      timestamp: new Date().toISOString()
    })
    await refreshRunState(action.runId)
    await loadConversations()
    await scrollToBottom()
  }

  return {
    messagesRef,
    inputRef,
    userInput,
    isStreaming,
    currentChatId,
    currentRunId,
    currentRoute,
    currentSkillId,
    currentSkillName,
    currentMessages,
    conversations,
    runTimeline,
    orderedTimeline,
    evidenceCards,
    stageTraces,
    retrievalTraces,
    memorySummary,
    pendingAction,
    toolCalls,
    clarificationOptions,
    errorMessage,
    refusalReason,
    runStatus,
    hasMessages,
    capabilities,
    canUseOps,
    adjustTextareaHeight,
    loadCapabilities,
    loadConversations,
    loadConversation,
    startNewChat,
    sendMessage,
    approveAction: () => resolveAction(pendingAction.value, 'approve'),
    rejectAction: () => resolveAction(pendingAction.value, 'reject')
  }
}
