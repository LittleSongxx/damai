import { computed, nextTick, ref } from 'vue'
import { aiOpsAdminAPI, assistantAPI, customerServiceAPI, dataOpsAdminAPI, ragEvalAPI } from '../api/api'

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
  const customerStarterPrompts = ref([])
  const customerServiceCard = ref(null)
  const customerSuggestions = ref([])
  const customerSentiment = ref(null)
  const customerWorkItem = ref(null)
  const conversations = ref([])
  const runTimeline = ref([])
  const evidenceCards = ref([])
  const stageTraces = ref([])
  const retrievalTraces = ref([])
  const runGraph = ref(null)
  const memorySummary = ref(null)
  const pendingAction = ref(null)
  const toolCalls = ref([])
  const clarificationOptions = ref([])
  const errorMessage = ref('')
  const refusalReason = ref('')
  const runStatus = ref('')
  const qualityGate = ref(null)
  const mcpGovernance = ref(null)
  const mcpBoundaryResult = ref(null)
  const evalSuiteResult = ref(null)
  const evalSuiteRunning = ref('')
  const ragEvalReport = ref(null)
  const ragBaselineRunId = ref('')
  const ragEvalComparison = ref(null)
  const ragBadCases = ref([])
  const ragIngestionTasks = ref([])
  const ragLastReindexJob = ref(null)
  const opsProviderStatus = ref(null)
  const opsRunbooks = ref([])
  const opsEvidenceResult = ref(null)
  const semanticCatalog = ref(null)
  const metricsRebuildResult = ref(null)
  const adminWorkspaceError = ref('')
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
    runGraph.value = null
    memorySummary.value = null
    pendingAction.value = null
    toolCalls.value = []
    clarificationOptions.value = []
    refusalReason.value = ''
    errorMessage.value = ''
    customerServiceCard.value = null
    customerSuggestions.value = []
    customerSentiment.value = null
    customerWorkItem.value = null
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

  const applyRunGraph = (graph) => {
    runGraph.value = graph || null
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
    if (capabilities.value.admin) {
      await loadAdminWorkspace()
    } else {
      qualityGate.value = null
      mcpGovernance.value = null
      mcpBoundaryResult.value = null
      evalSuiteResult.value = null
      evalSuiteRunning.value = ''
      ragEvalReport.value = null
      ragEvalComparison.value = null
      ragBaselineRunId.value = ''
      ragBadCases.value = []
      ragIngestionTasks.value = []
      ragLastReindexJob.value = null
      opsProviderStatus.value = null
      opsRunbooks.value = []
      opsEvidenceResult.value = null
      semanticCatalog.value = null
      metricsRebuildResult.value = null
    }
  }

  const loadAdminWorkspace = async () => {
    adminWorkspaceError.value = ''
    try {
      const gate = await assistantAPI.getQualityGate()
      qualityGate.value = gate?.data || null
    } catch (error) {
      qualityGate.value = null
      adminWorkspaceError.value = error.message || '治理门禁加载失败'
    }

    try {
      const mcp = await assistantAPI.getMcpGovernance()
      mcpGovernance.value = mcp?.data || null
    } catch (error) {
      mcpGovernance.value = null
    }

    try {
      if (qualityGate.value?.latestRagRunId) {
        const report = await ragEvalAPI.getRunReport(qualityGate.value.latestRagRunId)
        ragEvalReport.value = report?.data || null
        ragBaselineRunId.value = ragEvalReport.value?.baselineRunId || ragBaselineRunId.value || ''
      } else {
        ragEvalReport.value = null
      }
    } catch (error) {
      ragEvalReport.value = null
    }

    try {
      const badCases = await ragEvalAPI.listBadCases('PENDING')
      ragBadCases.value = Array.isArray(badCases?.data) ? badCases.data : []
    } catch (error) {
      ragBadCases.value = []
    }

    try {
      const tasks = await ragEvalAPI.listIngestionTasks()
      ragIngestionTasks.value = Array.isArray(tasks?.data) ? tasks.data : []
    } catch (error) {
      ragIngestionTasks.value = []
    }

    try {
      const providers = await aiOpsAdminAPI.listProviders()
      opsProviderStatus.value = providers?.data || null
    } catch (error) {
      opsProviderStatus.value = null
    }

    try {
      const runbooks = await aiOpsAdminAPI.listRunbooks()
      opsRunbooks.value = Array.isArray(runbooks?.data) ? runbooks.data : []
    } catch (error) {
      opsRunbooks.value = []
    }

    try {
      const catalog = await dataOpsAdminAPI.getCatalog()
      semanticCatalog.value = catalog?.data || null
    } catch (error) {
      semanticCatalog.value = null
    }
  }

  const loadCustomerStarterPrompts = async () => {
    try {
      const result = await customerServiceAPI.getStarterPrompts()
      customerStarterPrompts.value = Array.isArray(result?.data) ? result.data : []
    } catch (error) {
      customerStarterPrompts.value = []
    }
  }

  const convertBadCaseToEval = async (badCase, overrides = {}) => {
    if (!badCase?.badCaseId) {
      return null
    }
    const payload = {
      expectedAnswer: badCase.expectedAnswer || badCase.generatedAnswer || '',
      expectedChunks: badCase.expectedChunks || '',
      category: badCase.category || 'online-bad-case',
      difficulty: badCase.difficulty || 'medium',
      caseType: badCase.caseType || 'single_hop',
      datasetId: 'default-golden',
      datasetVersion: 'v1',
      ...overrides
    }
    const result = await ragEvalAPI.convertBadCase(badCase.badCaseId, payload)
    await loadAdminWorkspace()
    return result
  }

  const reviewBadCase = async (badCase, reviewStatus, reviewNote = '') => {
    if (!badCase?.badCaseId || !reviewStatus) {
      return null
    }
    const result = await ragEvalAPI.reviewBadCase(badCase.badCaseId, {
      reviewStatus,
      reviewNote
    })
    await loadAdminWorkspace()
    return result
  }

  const compareRagEvalWithBaseline = async (baselineRunId = ragBaselineRunId.value) => {
    const evalRunId = qualityGate.value?.latestRagRunId || ragEvalReport.value?.evalRunId || ''
    if (!evalRunId || !baselineRunId) {
      ragEvalComparison.value = null
      return null
    }
    ragBaselineRunId.value = baselineRunId
    const result = await ragEvalAPI.compareRun(evalRunId, baselineRunId)
    ragEvalComparison.value = result?.data || null
    return result
  }

  const createRagReindexJob = async (taskType = 'full') => {
    const normalizedType = taskType === 'incremental' ? 'incremental' : 'full'
    const result = await ragEvalAPI.createReindexJob(normalizedType)
    ragLastReindexJob.value = result?.data || null
    await loadAdminWorkspace()
    return result
  }

  const runEvalSuite = async (suite, payload = {}) => {
    const normalizedSuite = String(suite || '').trim().toLowerCase()
    if (!normalizedSuite) {
      return null
    }
    evalSuiteRunning.value = normalizedSuite
    try {
      const result = await assistantAPI.runEvalSuite(normalizedSuite, payload)
      evalSuiteResult.value = result?.data || null
      await loadAdminWorkspace()
      return result
    } finally {
      evalSuiteRunning.value = ''
    }
  }

  const refreshEvalSuiteRun = async (suite = evalSuiteResult.value?.suite, evalRunId = evalSuiteResult.value?.evalRunId) => {
    const normalizedSuite = String(suite || '').trim().toLowerCase()
    if (!normalizedSuite || !evalRunId) {
      return null
    }
    const result = await assistantAPI.getEvalRun(normalizedSuite, evalRunId)
    evalSuiteResult.value = result?.data || evalSuiteResult.value
    return result
  }

  const buildOpsRcaEvidence = async (payload = {}) => {
    const result = await aiOpsAdminAPI.buildRcaEvidence({
      query: payload.query || opsEvidenceResult.value?.assistantPrompt || 'diagnose recent production incident',
      serviceName: payload.serviceName || opsEvidenceResult.value?.evidenceBundle?.serviceName || 'order-service',
      traceId: payload.traceId || opsEvidenceResult.value?.evidenceBundle?.traceId || '',
      windowMinutes: payload.windowMinutes || 30,
      releaseVersion: payload.releaseVersion || '',
      configKey: payload.configKey || '',
      changeWindowMinutes: payload.changeWindowMinutes || Math.max(60, Number(payload.windowMinutes || 30) * 2)
    })
    opsEvidenceResult.value = {
      status: 'RCA_EVIDENCE',
      assistantPrompt: `RCA evidence bundle for ${result?.data?.serviceName || payload.serviceName || 'order-service'}`,
      evidenceBundle: result?.data || null
    }
    await loadAdminWorkspace()
    return result
  }

  const reloadSemanticCatalog = async () => {
    const result = await dataOpsAdminAPI.reloadCatalog()
    semanticCatalog.value = result?.data || semanticCatalog.value
    await loadAdminWorkspace()
    return result
  }

  const rebuildOpsMetrics = async (payload = {}) => {
    const result = await dataOpsAdminAPI.rebuildMetrics(payload)
    metricsRebuildResult.value = result?.data || null
    await loadAdminWorkspace()
    return result
  }

  const readMcpResource = async (resourceUri = 'assistant://runs/{runId}/graph', overrides = {}) => {
    const result = await assistantAPI.readMcpResource({
      resourceUri,
      runId: currentRunId.value,
      traceId: opsEvidenceResult.value?.evidenceBundle?.traceId || '',
      confirmed: true,
      ...overrides
    })
    mcpBoundaryResult.value = result?.data || null
    return result
  }

  const renderMcpPrompt = async (promptName = 'ops.rca', overrides = {}) => {
    const result = await assistantAPI.renderMcpPrompt({
      promptName,
      serviceName: opsEvidenceResult.value?.evidenceBundle?.serviceName || 'order-service',
      traceId: opsEvidenceResult.value?.evidenceBundle?.traceId || '',
      windowMinutes: 30,
      confirmed: true,
      ...overrides
    })
    mcpBoundaryResult.value = result?.data || null
    return result
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
      try {
        const graph = await assistantAPI.getRunGraph(conversation.latestRunId)
        applyRunGraph(graph?.data)
      } catch (error) {
        runGraph.value = null
      }
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
      case 'customer.quick_answer.hit':
      case 'customer.quick_answer.miss':
      case 'customer.service.card':
        customerServiceCard.value = data || null
        if (Array.isArray(data?.sourceRefs)) {
          evidenceCards.value = data.sourceRefs
        }
        if (Array.isArray(data?.suggestions)) {
          customerSuggestions.value = data.suggestions
        }
        recordTimeline(event, data, eventId)
        break
      case 'customer.sentiment.detected':
        customerSentiment.value = data || null
        recordTimeline(event, data, eventId)
        break
      case 'customer.suggestions.generated':
        customerSuggestions.value = Array.isArray(data?.suggestions) ? data.suggestions : []
        recordTimeline(event, data, eventId)
        break
      case 'customer.work_item.created':
        customerWorkItem.value = data?.workItem || data?.ticket || data || null
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
    try {
      const graph = await assistantAPI.getRunGraph(runId)
      applyRunGraph(graph?.data)
    } catch (error) {
      runGraph.value = null
    }
  }

  const resumeRun = async () => {
    if (!currentRunId.value || isStreaming.value) {
      return
    }
    isStreaming.value = true
    errorMessage.value = ''
    try {
      const result = await assistantAPI.resumeRun(currentRunId.value)
      const stream = await assistantAPI.streamRun(result?.data?.eventStreamPath)
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
      errorMessage.value = error.message || '恢复运行失败'
    } finally {
      isStreaming.value = false
    }
  }

  const replayRun = async () => {
    if (!currentRunId.value || isStreaming.value) {
      return
    }
    isStreaming.value = true
    errorMessage.value = ''
    try {
      const result = await assistantAPI.replayRun(currentRunId.value)
      const stream = await assistantAPI.streamRun(result?.data?.eventStreamPath)
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
      errorMessage.value = error.message || '重放运行失败'
    } finally {
      isStreaming.value = false
    }
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
      const customerContext = {
        scene: 'customer_service',
        entry: 'assistant-hub',
        ...clientContextOverrides
      }
      const quickAnswer = await customerServiceAPI.quickAnswer({
        chatId: currentChatId.value || null,
        message,
        intentHint: clientContextOverrides.intentHint || clientContextOverrides.intentCode,
        hotQuestionId: clientContextOverrides.hotQuestionId,
        programId: clientContextOverrides.programId,
        orderNo: clientContextOverrides.orderNo,
        categoryId: clientContextOverrides.categoryId,
        clientContext: customerContext
      })
      const quickData = quickAnswer?.data || null
      if (quickData) {
        customerSentiment.value = quickData.sentiment || null
        customerSuggestions.value = Array.isArray(quickData.suggestions) ? quickData.suggestions : []
        customerWorkItem.value = quickData.workItem || null
      }
      if (quickData?.hit && quickData.answerMode === 'CACHED_ANSWER') {
        const content = quickData.directAnswer || '已为你命中客服高频问题。'
        currentMessages.value.push({
          id: `assistant-customer-${Date.now()}`,
          role: 'assistant',
          content,
          timestamp: new Date().toISOString()
        })
        evidenceCards.value = Array.isArray(quickData.sourceRefs) ? quickData.sourceRefs : []
        clarificationOptions.value = Array.isArray(quickData.actionButtons)
          ? quickData.actionButtons.map(button => button.label).filter(Boolean)
          : []
        customerServiceCard.value = quickData
        runStatus.value = 'QUICK_ANSWER'
        currentRoute.value = quickData.routeHint || 'customer_service'
        currentSkillId.value = quickData.intentCode || ''
        currentSkillName.value = '客服秒答'
        recordTimeline('customer.quick_answer.hit', quickData, `customer-hit-${Date.now()}`)
        if (quickData.sentiment) {
          recordTimeline('customer.sentiment.detected', quickData.sentiment, `customer-sentiment-${Date.now()}`)
        }
        if (quickData.workItem) {
          recordTimeline('customer.work_item.created', quickData.workItem, `customer-work-item-${Date.now()}`)
        }
        await scrollToBottom()
        return
      }

      const stream = await assistantAPI.sendMessage(message, currentChatId.value || null, {
        ...customerContext,
        ...(quickData?.clientContext || {})
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
    customerStarterPrompts,
    customerServiceCard,
    customerSuggestions,
    customerSentiment,
    customerWorkItem,
    conversations,
    runTimeline,
    orderedTimeline,
    evidenceCards,
    stageTraces,
    retrievalTraces,
    runGraph,
    memorySummary,
    pendingAction,
    toolCalls,
    clarificationOptions,
    errorMessage,
    refusalReason,
    runStatus,
    qualityGate,
    mcpGovernance,
    mcpBoundaryResult,
    evalSuiteResult,
    evalSuiteRunning,
    ragEvalReport,
    ragBaselineRunId,
    ragEvalComparison,
    ragBadCases,
    ragIngestionTasks,
    ragLastReindexJob,
    opsProviderStatus,
    opsRunbooks,
    opsEvidenceResult,
    semanticCatalog,
    metricsRebuildResult,
    adminWorkspaceError,
    hasMessages,
    capabilities,
    canUseOps,
    adjustTextareaHeight,
    loadCustomerStarterPrompts,
    loadCapabilities,
    loadAdminWorkspace,
    loadConversations,
    loadConversation,
    startNewChat,
    sendMessage,
    resumeRun,
    replayRun,
    compareRagEvalWithBaseline,
    runEvalSuite,
    refreshEvalSuiteRun,
    convertBadCaseToEval,
    reviewBadCase,
    createRagReindexJob,
    readMcpResource,
    renderMcpPrompt,
    buildOpsRcaEvidence,
    reloadSemanticCatalog,
    rebuildOpsMetrics,
    approveAction: () => resolveAction(pendingAction.value, 'approve'),
    rejectAction: () => resolveAction(pendingAction.value, 'reject')
  }
}
