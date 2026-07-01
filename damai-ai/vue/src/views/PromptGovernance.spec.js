import { shallowMount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const { promptVersionAPIMock } = vi.hoisted(() => ({
  promptVersionAPIMock: {
    list: vi.fn(),
    listReleaseRecords: vi.fn(),
    createDraft: vi.fn(),
    buildReleasePlan: vi.fn(),
    publish: vi.fn(),
    promote: vi.fn(),
    rollback: vi.fn(),
    invalidateCache: vi.fn()
  }
}))

vi.mock('../api/api', () => ({
  ensureAuthenticated: vi.fn(() => true),
  promptVersionAPI: promptVersionAPIMock
}))

import PromptGovernance from './PromptGovernance.vue'

async function flushPromises() {
  await Promise.resolve()
  await Promise.resolve()
  await nextTick()
}

describe('PromptGovernance', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    promptVersionAPIMock.list.mockResolvedValue({
      data: [
        {
          promptKey: 'knowledge.answer',
          version: 2,
          template: 'candidate prompt',
          description: 'citation upgrade',
          active: false,
          rolloutStatus: 'DRAFT',
          trafficPercent: 0
        },
        {
          promptKey: 'knowledge.answer',
          version: 1,
          template: 'stable prompt',
          description: 'stable baseline',
          active: true,
          rolloutStatus: 'STABLE',
          trafficPercent: 100
        }
      ]
    })
    promptVersionAPIMock.listReleaseRecords.mockResolvedValue({
      data: [
        {
          releaseId: 'release-1',
          promptKey: 'knowledge.answer',
          fromVersion: 1,
          toVersion: 2,
          actionType: 'PUBLISH',
          rolloutStatus: 'GRADUAL',
          trafficPercent: 10,
          releaseNote: 'baseline passed'
        }
      ]
    })
    promptVersionAPIMock.createDraft.mockResolvedValue({
      data: {
        promptKey: 'knowledge.answer',
        version: 3,
        template: 'draft prompt',
        rolloutStatus: 'DRAFT'
      }
    })
    promptVersionAPIMock.buildReleasePlan.mockResolvedValue({
      data: {
        status: 'READY',
        publishAllowed: true,
        recommendedTrafficPercent: 10,
        baselineEvalRunId: 'rag-baseline-1',
        rollbackTargetVersion: 1,
        evidence: {
          qualityGateStatus: 'PASS'
        },
        nextActions: ['publish with recorded eval evidence']
      }
    })
    promptVersionAPIMock.publish.mockResolvedValue({
      data: { promptKey: 'knowledge.answer', version: 2 },
      releasePlan: { status: 'READY', publishAllowed: true }
    })
    promptVersionAPIMock.promote.mockResolvedValue({
      data: { promptKey: 'knowledge.answer', version: 2 },
      releasePlan: { status: 'READY', publishAllowed: true }
    })
    promptVersionAPIMock.rollback.mockResolvedValue({
      data: { promptKey: 'knowledge.answer', version: 1 },
      releasePlan: { status: 'READY', publishAllowed: true }
    })
    promptVersionAPIMock.invalidateCache.mockResolvedValue({ code: 0 })
  })

  it('renders prompt versions and triggers release actions', async () => {
    const wrapper = shallowMount(PromptGovernance)
    await flushPromises()

    expect(promptVersionAPIMock.list).toHaveBeenCalled()
    expect(wrapper.text()).toContain('Prompt 版本治理')
    expect(wrapper.text()).toContain('knowledge.answer v2')
    expect(wrapper.text()).toContain('baseline passed')

    await wrapper.findAll('.version-row')[0].trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('candidate prompt')
    expect(wrapper.text()).toContain('Release Plan')
    expect(wrapper.text()).toContain('READY')
    expect(wrapper.text()).toContain('traffic 10%')
    expect(wrapper.text()).toContain('baseline rag-baseline-1')
    expect(wrapper.text()).toContain('rollback v1')
    expect(wrapper.text()).toContain('quality PASS')

    await wrapper.findAll('button').find(item => item.text() === '发布').trigger('click')
    await flushPromises()
    expect(promptVersionAPIMock.publish).toHaveBeenCalledWith(expect.objectContaining({
      promptKey: 'knowledge.answer',
      version: 2,
      rolloutStatus: 'STABLE',
      trafficPercent: 10,
      baselineEvalRunId: '',
      releaseNote: ''
    }))
    expect(promptVersionAPIMock.publish.mock.calls[0][0]).not.toHaveProperty('releaseEvidenceJson')

    await wrapper.findAll('button').find(item => item.text() === '提升稳定').trigger('click')
    await flushPromises()
    expect(promptVersionAPIMock.promote).toHaveBeenCalledWith(expect.objectContaining({
      promptKey: 'knowledge.answer',
      version: 2,
      baselineEvalRunId: '',
      releaseNote: 'promote to stable'
    }))
    expect(promptVersionAPIMock.promote.mock.calls[0][0]).not.toHaveProperty('releaseEvidenceJson')

    await wrapper.findAll('button').find(item => item.text() === '回滚到此版本').trigger('click')
    await flushPromises()
    expect(promptVersionAPIMock.rollback).toHaveBeenCalledWith(expect.objectContaining({
      promptKey: 'knowledge.answer',
      version: 2,
      baselineEvalRunId: '',
      reason: ''
    }))
    expect(promptVersionAPIMock.rollback.mock.calls[0][0]).not.toHaveProperty('releaseEvidenceJson')

    await wrapper.findAll('button').find(item => item.text() === '刷新缓存').trigger('click')
    await flushPromises()
    expect(promptVersionAPIMock.invalidateCache).toHaveBeenCalled()
  })

  it('creates prompt drafts from form values', async () => {
    const wrapper = shallowMount(PromptGovernance)
    await flushPromises()

    const inputs = wrapper.findAll('input')
    await inputs[1].setValue('knowledge.answer')
    await inputs[2].setValue('new judge prompt')
    await wrapper.find('textarea').setValue('answer with citations')

    await wrapper.findAll('button').find(item => item.text() === '保存草稿').trigger('click')

    expect(promptVersionAPIMock.createDraft).toHaveBeenCalledWith(expect.objectContaining({
      promptKey: 'knowledge.answer',
      description: 'new judge prompt',
      template: 'answer with citations'
    }))
  })
})
