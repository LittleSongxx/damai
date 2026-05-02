import { shallowMount } from '@vue/test-utils'
import { ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const runtimeState = vi.hoisted(() => ({
  loadCapabilities: vi.fn(),
  loadConversations: vi.fn(),
  loadConversation: vi.fn(),
  startNewChat: vi.fn(),
  sendMessage: vi.fn(),
  approveAction: vi.fn(),
  rejectAction: vi.fn()
}))

vi.mock('../api/api', () => ({
  ensureAuthenticated: vi.fn(() => true)
}))

vi.mock('../composables/useAssistantRuntime', () => ({
  useAssistantRuntime: () => ({
    messagesRef: ref(null),
    inputRef: ref(null),
    userInput: ref(''),
    isStreaming: ref(false),
    currentChatId: ref('chat-9'),
    currentRunId: ref('run-9'),
    currentRoute: ref('KNOWLEDGE'),
    currentMessages: ref([]),
    conversations: ref([
      { id: 'chat-9', title: '退票规则咨询', routeType: 'KNOWLEDGE', workflowStatus: 'COMPLETED' }
    ]),
    orderedTimeline: ref([
      { id: 'evt-1', event: 'route.selected', data: { routeType: 'KNOWLEDGE' } }
    ]),
    evidenceCards: ref([
      { chunkId: 'faq-1', title: '退票 FAQ', snippet: '开演前一天按规则退票。' }
    ]),
    pendingAction: ref({
      runId: 'run-9',
      actionId: 'action-1',
      summary: '确认是否提交订单'
    }),
    clarificationOptions: ref([
      '查询或购买演出票',
      '咨询购票/退票/入场规则'
    ]),
    toolCalls: ref([
      { id: 'tool-1', toolName: 'hybrid-search', summary: 'retrieval.completed' }
    ]),
    errorMessage: ref(''),
    refusalReason: ref(''),
    runStatus: ref('WAITING_ACTION'),
    hasMessages: ref(false),
    canUseOps: ref(false),
    adjustTextareaHeight: vi.fn(),
    ...runtimeState
  })
}))

import AssistantHub from './AssistantHub.vue'

describe('AssistantHub', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders unified assistant panels and action controls', async () => {
    const wrapper = shallowMount(AssistantHub, {
      global: {
        stubs: {
          Chat: true
        }
      }
    })

    await Promise.resolve()
    expect(runtimeState.loadCapabilities).toHaveBeenCalled()
    expect(runtimeState.loadConversations).toHaveBeenCalled()
    expect(wrapper.text()).toContain('大麦统一助手')
    expect(wrapper.text()).toContain('KNOWLEDGE')
    expect(wrapper.text()).toContain('General')
    expect(wrapper.text()).toContain('介绍一下我刚看到的这个歌手')
    expect(wrapper.text()).toContain('退票 FAQ')
    expect(wrapper.text()).toContain('确认是否提交订单')
    expect(wrapper.text()).toContain('请选择意图')

    await wrapper.get('.primary-button').trigger('click')
    expect(runtimeState.approveAction).toHaveBeenCalled()
    await wrapper.findAll('.starter-chip').find(item => item.text() === '查询或购买演出票').trigger('click')
    expect(runtimeState.sendMessage).toHaveBeenCalledWith('查询或购买演出票')
  })
})
