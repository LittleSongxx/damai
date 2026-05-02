import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAiChat } from './useAiChat'

const { chatAPIMock } = vi.hoisted(() => ({
  chatAPIMock: {
    chatTypeHistoryList: vi.fn(),
    chatHistoryMessageList: vi.fn(),
    deleteChat: vi.fn(),
    getWorkflow: vi.fn(),
    approveWorkflow: vi.fn(),
    rejectWorkflow: vi.fn()
  }
}))

vi.mock('../api/api', () => ({
  chatAPI: chatAPIMock
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

describe('useAiChat', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    chatAPIMock.chatTypeHistoryList.mockResolvedValue([])
    chatAPIMock.chatHistoryMessageList.mockResolvedValue([])
    chatAPIMock.deleteChat.mockResolvedValue(true)
    chatAPIMock.getWorkflow.mockResolvedValue({
      steps: [{ id: 9, stepKey: 'ANSWER', stepStatus: 'COMPLETED' }],
      pendingApproval: null
    })
    chatAPIMock.approveWorkflow.mockResolvedValue({
      orderNumber: 'ORD-1001',
      orderListAddress: '/order/list'
    })
    chatAPIMock.rejectWorkflow.mockResolvedValue(true)
  })

  it('consumes stream events and updates retrieval state', async () => {
    const sendMessageApi = vi.fn().mockResolvedValue(createEventStream([
      { event: 'message.delta', data: { delta: '根据规则，' } },
      { event: 'message.delta', data: { delta: '本单支持退票。' } },
      {
        event: 'retrieval.sources',
        data: {
          runId: 'run-rag-1',
          traceId: 'trace-rag-1',
          rewrittenQuery: '退票 退款 取消订单',
          sources: [
            {
              chunkId: 'faq-1',
              title: '实名制规则',
              source: 'faq.md',
              section: '退票规则',
              snippet: '开演前可按规则退票。'
            }
          ]
        }
      },
      { event: 'workflow.step', data: { steps: [{ id: 1, stepKey: 'RERANK', stepStatus: 'COMPLETED' }] } },
      { event: 'message.done', data: { runId: 'run-rag-1' } }
    ]))

    const chat = useAiChat({
      chatType: 3,
      sendMessageApi,
      newChatTitle: '新的规则问答'
    })

    await chat.startNewChat()
    await chat.sendMessage('这张票能退吗')

    expect(sendMessageApi).toHaveBeenCalledWith('这张票能退吗', expect.any(String))
    expect(chat.currentMessages.value).toHaveLength(2)
    expect(chat.currentMessages.value[1].content).toBe('根据规则，本单支持退票。')
    expect(chat.retrievalMeta.value).toEqual({
      traceId: 'trace-rag-1',
      rewrittenQuery: '退票 退款 取消订单'
    })
    expect(chat.retrievalSources.value[0].section).toBe('退票规则')
    expect(chat.workflowSteps.value[0].stepKey).toBe('ANSWER')
  })

  it('approves pending order and appends order result message', async () => {
    const chat = useAiChat({
      chatType: 2,
      sendMessageApi: vi.fn().mockResolvedValue(createEventStream([])),
      newChatTitle: '新的购票咨询'
    })

    chat.currentRunId.value = 'run-order-1'
    await chat.approvePending()

    expect(chatAPIMock.approveWorkflow).toHaveBeenCalledWith('run-order-1')
    expect(chat.currentMessages.value.at(-1).content).toContain('ORD-1001')
    expect(chat.currentMessages.value.at(-1).content).toContain('/order/list')
  })
})
