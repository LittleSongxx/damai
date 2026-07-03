import { aiOpsAdminAPI, createAbsoluteUrl, createSseIterator, customerServiceAPI, dataOpsAdminAPI, getAuthState, parseSseChunk, promptVersionAPI, ragEvalAPI } from './api'

function createReader(chunks) {
  const encoder = new TextEncoder()
  let index = 0
  return {
    async read() {
      if (index >= chunks.length) {
        return { done: true, value: undefined }
      }
      return {
        done: false,
        value: encoder.encode(chunks[index++])
      }
    }
  }
}

describe('api sse helpers', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('parses structured SSE chunks', () => {
    const event = parseSseChunk('event: retrieval.sources\ndata: {"traceId":"trace-1","rewrittenQuery":"退票 退款"}')
    expect(event).toEqual({
      id: '',
      event: 'retrieval.sources',
      data: {
        traceId: 'trace-1',
        rewrittenQuery: '退票 退款'
      }
    })
  })

  it('iterates across multi-chunk SSE payloads', async () => {
    const reader = createReader([
      'event: message.delta\ndata: {"delta":"你好"}\n\n',
      'event: workflow.step\ndata: {"steps":[{"id":1,"stepKey":"ANSWER"}]}\n\n'
    ])

    const iterator = createSseIterator(reader)
    const events = []
    for await (const event of iterator) {
      events.push(event)
    }

    expect(events).toHaveLength(2)
    expect(events[0]).toEqual({
      id: '',
      event: 'message.delta',
      data: {
        delta: '你好'
      }
    })
    expect(events[1].event).toBe('workflow.step')
    expect(events[1].data.steps[0].stepKey).toBe('ANSWER')
  })

  it('ignores comment-only SSE chunks and parses ids', () => {
    expect(parseSseChunk(': ping')).toBeNull()
    expect(parseSseChunk('id: evt-1\nevent: run.started\ndata: {"runId":"run-1"}')).toEqual({
      id: 'evt-1',
      event: 'run.started',
      data: {
        runId: 'run-1'
      }
    })
  })

  it('normalizes relative API URLs against the current origin', () => {
    const url = createAbsoluteUrl('/damai-ai-dev/assistant/conversations?routeType=knowledge')
    expect(url.toString()).toBe(
      new URL('/damai-ai-dev/assistant/conversations?routeType=knowledge', window.location.origin).toString()
    )
  })

  it('reads auth state from shared cookies', () => {
    Object.defineProperty(document, 'cookie', {
      value: 'Admin-Token=test-token; userId=5',
      configurable: true
    })

    expect(getAuthState()).toEqual({
      token: 'test-token',
      userId: '5',
      isAuthenticated: true
    })
  })

  it('calls RAG eval, AIOps, and prompt governance endpoints', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ code: 0, data: { ok: true } }), {
      status: 200,
      headers: {
        'Content-Type': 'application/json'
      }
    }))
    vi.stubGlobal('fetch', fetchMock)

    await ragEvalAPI.listBadCases('PENDING', 'citation_missing')
    await ragEvalAPI.runBenchmark({ limit: 5, profiles: ['STANDARD_HYBRID'] })
    await ragEvalAPI.convertBadCase('bad-1', { datasetId: 'default-golden' })
    await ragEvalAPI.reviewBadCase('bad-1', { reviewStatus: 'FIXED', reviewNote: 'done' })
    await ragEvalAPI.createReindexJob('incremental')
    await ragEvalAPI.listIngestionTasks()
    await aiOpsAdminAPI.listProviders()
    await aiOpsAdminAPI.listRunbooks()
    await aiOpsAdminAPI.buildRcaEvidence({ serviceName: 'order-service', traceId: 'trace-1' })
    await dataOpsAdminAPI.getCatalog()
    await dataOpsAdminAPI.reloadCatalog()
    await dataOpsAdminAPI.rebuildMetrics({ fromRaw: true })
    const { assistantAPI } = await import('./api')
    await assistantAPI.getMcpGovernance()
    await assistantAPI.readMcpResource({ resourceUri: 'assistant://runs/{runId}/graph', runId: 'run-9', confirmed: true })
    await assistantAPI.renderMcpPrompt({ promptName: 'ops.rca', confirmed: true })
    await assistantAPI.replayRun('run-9')
    await assistantAPI.runEvalSuite('rag', { limit: 5 })
    await assistantAPI.getEvalRun('rag', 'rag-eval-2')
    await customerServiceAPI.getStarterPrompts()
    await customerServiceAPI.quickAnswer({ message: '退票规则', hotQuestionId: 'refund-rule' })
    await customerServiceAPI.createWorkItem({ conversationId: 'chat-1', userQuestion: '转人工' })
    await customerServiceAPI.getWorkItem('work-item-1')
    await customerServiceAPI.getDashboard()
    await customerServiceAPI.getTopQuestions()
    await customerServiceAPI.getUnresolvedCases()
    await promptVersionAPI.list('knowledge.answer')
    await promptVersionAPI.listReleaseRecords('knowledge.answer')
    await promptVersionAPI.createDraft({ promptKey: 'knowledge.answer', template: 'draft' })
    await promptVersionAPI.buildReleasePlan({ promptKey: 'knowledge.answer', version: 2, rolloutStatus: 'GRADUAL' })
    await promptVersionAPI.publish({ promptKey: 'knowledge.answer', version: 2, rolloutStatus: 'GRADUAL', trafficPercent: 10 })
    await promptVersionAPI.rollback({ promptKey: 'knowledge.answer', version: 1, reason: 'bad eval' })

    expect(fetchMock.mock.calls[0][0].toString()).toContain('/assistant/admin/rag-eval/bad-cases?reviewStatus=PENDING&failureType=citation_missing')
    expect(fetchMock.mock.calls[1][0].toString()).toContain('/assistant/admin/rag-eval/benchmark')
    expect(fetchMock.mock.calls[1][1].method).toBe('POST')
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({ limit: 5, profiles: ['STANDARD_HYBRID'] })
    expect(fetchMock.mock.calls[2][0].toString()).toContain('/assistant/admin/rag-eval/bad-cases/bad-1/convert')
    expect(fetchMock.mock.calls[2][1].method).toBe('POST')
    expect(fetchMock.mock.calls[3][0].toString()).toContain('/assistant/admin/rag-eval/bad-cases/bad-1/review')
    expect(fetchMock.mock.calls[3][1].method).toBe('POST')
    expect(fetchMock.mock.calls[4][0].toString()).toContain('/assistant/admin/knowledge/reindex-jobs?taskType=incremental')
    expect(fetchMock.mock.calls[4][1].method).toBe('POST')
    expect(fetchMock.mock.calls[5][0].toString()).toContain('/assistant/admin/knowledge/ingestion/tasks')
    expect(fetchMock.mock.calls[6][0].toString()).toContain('/assistant/admin/ops/providers')
    expect(fetchMock.mock.calls[7][0].toString()).toContain('/assistant/admin/ops/runbooks')
    expect(fetchMock.mock.calls[8][0].toString()).toContain('/assistant/admin/ops/rca-evidence')
    expect(fetchMock.mock.calls[8][1].method).toBe('POST')
    expect(fetchMock.mock.calls[9][0].toString()).toContain('/assistant/admin/dataops/catalog')
    expect(fetchMock.mock.calls[10][0].toString()).toContain('/assistant/admin/dataops/catalog/reload')
    expect(fetchMock.mock.calls[10][1].method).toBe('POST')
    expect(fetchMock.mock.calls[11][0].toString()).toContain('/assistant/admin/dataops/metrics/rebuild')
    expect(fetchMock.mock.calls[11][1].method).toBe('POST')
    expect(fetchMock.mock.calls[12][0].toString()).toContain('/assistant/admin/mcp/governance')
    expect(fetchMock.mock.calls[13][0].toString()).toContain('/assistant/admin/mcp/resources/read')
    expect(fetchMock.mock.calls[13][1].method).toBe('POST')
    expect(fetchMock.mock.calls[14][0].toString()).toContain('/assistant/admin/mcp/prompts/render')
    expect(fetchMock.mock.calls[14][1].method).toBe('POST')
    expect(fetchMock.mock.calls[15][0].toString()).toContain('/assistant/runs/run-9/replay')
    expect(fetchMock.mock.calls[15][1].method).toBe('POST')
    expect(fetchMock.mock.calls[16][0].toString()).toContain('/assistant/evals/rag/run')
    expect(fetchMock.mock.calls[16][1].method).toBe('POST')
    expect(JSON.parse(fetchMock.mock.calls[16][1].body)).toEqual({ limit: 5 })
    expect(fetchMock.mock.calls[17][0].toString()).toContain('/assistant/evals/rag/runs/rag-eval-2')
    expect(fetchMock.mock.calls[18][0].toString()).toContain('/assistant/customer-service/starter-prompts')
    expect(fetchMock.mock.calls[19][0].toString()).toContain('/assistant/customer-service/quick-answer')
    expect(fetchMock.mock.calls[19][1].method).toBe('POST')
    expect(fetchMock.mock.calls[20][0].toString()).toContain('/assistant/customer-service/handoff')
    expect(fetchMock.mock.calls[20][1].method).toBe('POST')
    expect(fetchMock.mock.calls[21][0].toString()).toContain('/assistant/customer-service/work-items/work-item-1')
    expect(fetchMock.mock.calls[22][0].toString()).toContain('/assistant/admin/customer-service/dashboard')
    expect(fetchMock.mock.calls[23][0].toString()).toContain('/assistant/admin/customer-service/top-questions')
    expect(fetchMock.mock.calls[24][0].toString()).toContain('/assistant/admin/customer-service/unresolved-cases')
    expect(fetchMock.mock.calls[25][0].toString()).toContain('/assistant/admin/prompt-versions?promptKey=knowledge.answer')
    expect(fetchMock.mock.calls[26][0].toString()).toContain('/assistant/admin/prompt-versions/release-records?promptKey=knowledge.answer')
    expect(fetchMock.mock.calls[27][0].toString()).toContain('/assistant/admin/prompt-versions')
    expect(fetchMock.mock.calls[27][1].method).toBe('POST')
    expect(fetchMock.mock.calls[28][0].toString()).toContain('/assistant/admin/prompt-versions/release-plan')
    expect(fetchMock.mock.calls[28][1].method).toBe('POST')
    expect(fetchMock.mock.calls[29][0].toString()).toContain('/assistant/admin/prompt-versions/publish')
    expect(fetchMock.mock.calls[30][0].toString()).toContain('/assistant/admin/prompt-versions/rollback')
  })
})
