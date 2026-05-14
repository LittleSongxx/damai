import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAssistantRuntime } from './useAssistantRuntime'

const { assistantAPIMock } = vi.hoisted(() => ({
  assistantAPIMock: {
    listConversations: vi.fn(),
    listMessages: vi.fn(),
    getRun: vi.fn(),
    getCapabilities: vi.fn(),
    sendMessage: vi.fn(),
    approveAction: vi.fn(),
    rejectAction: vi.fn()
  }
}))

vi.mock('../api/api', () => ({
  assistantAPI: assistantAPIMock
}))

function createEventStream(events) {
  return {
    async *[Symbol.asyncIterator]() {
      for (const event of events) {
        yield event
      }
    }
  }
}

describe('useAssistantRuntime', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    assistantAPIMock.listConversations.mockResolvedValue({
      data: [
        {
          chatId: 'chat-1',
          title: '退票规则咨询',
          latestRunId: 'run-1',
          routeType: 'KNOWLEDGE',
          latestStatus: 'COMPLETED'
        }
      ]
    })
    assistantAPIMock.listMessages.mockResolvedValue({
      data: [
        {
          id: 'msg-1',
          role: 'user',
          content: '开演前一天还能退票吗',
          createdAt: '2026-05-14T09:00:00Z'
        }
      ]
    })
    assistantAPIMock.getCapabilities.mockResolvedValue({
      data: {
        admin: true,
        allowedRoutes: ['business', 'knowledge', 'general'],
        skills: [{ skillId: 'knowledge.policy.qa', name: '规则知识问答' }]
      }
    })
  })

  it('hydrates stage traces, retrieval traces, and structured memory from run detail', async () => {
    assistantAPIMock.getRun.mockResolvedValue({
      data: {
        run: {
          runId: 'run-1',
          routeType: 'KNOWLEDGE',
          skillId: 'knowledge.policy.qa',
          runStatus: 'COMPLETED'
        },
        retrieval: {
          finalHitsJson: JSON.stringify([
            { chunkId: 'faq-1', title: '退票 FAQ', snippet: '开演前一天按规则退票。' }
          ])
        },
        events: [
          {
            eventId: 'evt-1',
            eventType: 'route.selected',
            payloadJson: JSON.stringify({ routeType: 'KNOWLEDGE' }),
            createTime: '2026-05-14T09:01:00Z'
          }
        ],
        stageTraces: [
          {
            traceId: 'trace-stage-1',
            stepKey: 'KNOWLEDGE_RETRIEVAL_FIRST_PASS',
            requestType: 'KnowledgeRetrieval',
            latencyMs: 188,
            success: true,
            metadata: JSON.stringify({ finalHitCount: 4 }),
            createTime: '2026-05-14T09:01:01Z'
          }
        ],
        retrievalTraces: [
          {
            traceId: 'trace-retrieval-1',
            traceType: 'stage',
            stepKey: 'knowledge.retrieval.first_pass',
            originalQuery: '退票规则',
            rewrittenQuery: '退票 退款 条件',
            denseHitsJson: JSON.stringify([{ chunkId: 'dense-1' }]),
            sparseHitsJson: JSON.stringify([{ chunkId: 'sparse-1' }]),
            fusedHitsJson: JSON.stringify([{ chunkId: 'fused-1' }]),
            finalHitsJson: JSON.stringify([{ chunkId: 'final-1' }]),
            metadataJson: JSON.stringify({ topK: 6 }),
            createTime: '2026-05-14T09:01:02Z'
          }
        ],
        memorySummary: {
          summary: '用户在确认某场演出的退票规则。',
          memoryJson: JSON.stringify({
            summary: '用户在确认某场演出的退票规则。',
            conversationGoal: '弄清是否还能退票',
            stableFacts: ['关注开演前一天'],
            pendingQuestions: ['需要确认具体场次'],
            retrievalHints: ['退票', '开演前一天']
          }),
          summaryVersion: 2,
          compressionCount: 1
        }
      }
    })

    const runtime = useAssistantRuntime()
    await runtime.loadCapabilities()
    await runtime.loadConversations()
    await runtime.loadConversation('chat-1')

    expect(runtime.currentSkillName.value).toBe('规则知识问答')
    expect(runtime.evidenceCards.value[0].title).toBe('退票 FAQ')
    expect(runtime.stageTraces.value[0].metadata.finalHitCount).toBe(4)
    expect(runtime.retrievalTraces.value[0].denseHitCount).toBe(1)
    expect(runtime.retrievalTraces.value[0].metadata.topK).toBe(6)
    expect(runtime.memorySummary.value.structuredMemory.conversationGoal).toBe('弄清是否还能退票')
    expect(runtime.memorySummary.value.structuredMemory.retrievalHints).toContain('退票')
  })

  it('refreshes run detail after streaming completes', async () => {
    assistantAPIMock.sendMessage.mockResolvedValue(createEventStream([
      { event: 'run.started', data: { runId: 'run-101', chatId: 'chat-101', status: 'RUNNING' }, id: 'evt-start' },
      {
        event: 'route.selected',
        data: { routeType: 'KNOWLEDGE', skillId: 'knowledge.policy.qa', skillName: '规则知识问答' },
        id: 'evt-route'
      },
      { event: 'message.delta', data: { delta: '根据规则，' }, id: 'evt-delta-1' },
      { event: 'message.delta', data: { delta: '当前支持退票。' }, id: 'evt-delta-2' },
      { event: 'run.completed', data: { status: 'COMPLETED' }, id: 'evt-done' }
    ]))
    assistantAPIMock.getRun.mockResolvedValue({
      data: {
        run: {
          runId: 'run-101',
          routeType: 'KNOWLEDGE',
          skillId: 'knowledge.policy.qa',
          runStatus: 'COMPLETED'
        },
        stageTraces: [
          {
            traceId: 'trace-stage-101',
            stepKey: 'KNOWLEDGE_ANSWER',
            requestType: 'KnowledgeAnswer',
            latencyMs: 320,
            success: true,
            metadata: JSON.stringify({ streamed: true })
          }
        ],
        retrievalTraces: [],
        memorySummary: {
          summary: '用户已拿到退票结论。',
          memoryJson: JSON.stringify({
            summary: '用户已拿到退票结论。',
            conversationGoal: '确认当前订单的退票策略',
            stableFacts: ['当前话题是退票规则'],
            pendingQuestions: [],
            retrievalHints: ['订单退票']
          }),
          summaryVersion: 2,
          compressionCount: 1
        },
        events: []
      }
    })

    const runtime = useAssistantRuntime()
    await runtime.loadCapabilities()
    await runtime.sendMessage('这张票能退吗')

    expect(assistantAPIMock.getRun).toHaveBeenCalledWith('run-101')
    expect(runtime.currentMessages.value.at(-1).content).toBe('根据规则，当前支持退票。')
    expect(runtime.stageTraces.value[0].stepKey).toBe('KNOWLEDGE_ANSWER')
    expect(runtime.memorySummary.value.structuredMemory.summary).toBe('用户已拿到退票结论。')
  })
})
