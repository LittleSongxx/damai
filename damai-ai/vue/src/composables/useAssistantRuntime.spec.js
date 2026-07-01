import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAssistantRuntime } from './useAssistantRuntime'

const { aiOpsAdminAPIMock, assistantAPIMock, customerServiceAPIMock, ragEvalAPIMock } = vi.hoisted(() => ({
  assistantAPIMock: {
    listConversations: vi.fn(),
    listMessages: vi.fn(),
    getRun: vi.fn(),
    getRunGraph: vi.fn(),
    getCapabilities: vi.fn(),
    getQualityGate: vi.fn(),
    getMcpGovernance: vi.fn(),
    readMcpResource: vi.fn(),
    renderMcpPrompt: vi.fn(),
    runEvalSuite: vi.fn(),
    getEvalRun: vi.fn(),
    sendMessage: vi.fn(),
    streamRun: vi.fn(),
    replayRun: vi.fn(),
    approveAction: vi.fn(),
    rejectAction: vi.fn()
  },
  customerServiceAPIMock: {
    getStarterPrompts: vi.fn(),
    quickAnswer: vi.fn(),
    createEscalation: vi.fn(),
    getEscalation: vi.fn(),
    getDashboard: vi.fn(),
    getTopQuestions: vi.fn(),
    getUnresolvedCases: vi.fn()
  },
  ragEvalAPIMock: {
    getRunReport: vi.fn(),
    compareRun: vi.fn(),
    listBadCases: vi.fn(),
    convertBadCase: vi.fn(),
    reviewBadCase: vi.fn(),
    createReindexJob: vi.fn(),
    listIngestionTasks: vi.fn()
  },
  aiOpsAdminAPIMock: {
    listFaultScenarios: vi.fn(),
    injectFaultScenario: vi.fn(),
    buildRcaEvidence: vi.fn()
  }
}))

vi.mock('../api/api', () => ({
  assistantAPI: assistantAPIMock,
  customerServiceAPI: customerServiceAPIMock,
  ragEvalAPI: ragEvalAPIMock,
  aiOpsAdminAPI: aiOpsAdminAPIMock
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
    customerServiceAPIMock.getStarterPrompts.mockResolvedValue({
      data: [
        { questionId: 'refund-rule', displayText: '退票规则', queryText: '退票规则是什么', intentCode: 'REFUND_RULE' }
      ]
    })
    customerServiceAPIMock.quickAnswer.mockResolvedValue({
      data: {
        hit: false,
        answerMode: 'RUN_ASSISTANT',
        intentCode: 'REFUND_RULE',
        routeHint: 'knowledge',
        clientContext: {
          scene: 'customer_service',
          intentHint: 'REFUND_RULE'
        },
        suggestions: [],
        sentiment: {
          sentiment: 'NEUTRAL',
          intensity: 0,
          urgent: false
        }
      }
    })
    assistantAPIMock.getQualityGate.mockResolvedValue({
      data: {
        status: 'PASS',
        latestRagRunId: 'rag-1',
        latestNl2SqlRunId: 'sql-1',
        gates: [{ name: 'RAG_EVAL', status: 'PASS' }]
      }
    })
    assistantAPIMock.getMcpGovernance.mockResolvedValue({
      data: {
        status: 'PASS',
        enabled: true,
        requireAdmin: true,
        requireHighRiskConfirmation: true,
        exposeNl2Sql: false,
        allowlistSize: 2,
        resourceAllowlistSize: 4,
        promptAllowlistSize: 3,
        allowlistedTools: [
          { toolName: 'log.getServiceList', riskLevel: 'LOW', scope: 'logs' }
        ],
        allowlistedResources: [
          { name: 'assistant://runs/{runId}/graph', riskLevel: 'HIGH', scope: 'assistant-runtime' }
        ],
        allowlistedPrompts: [
          { name: 'ops.rca', riskLevel: 'HIGH', scope: 'ops-prompt' }
        ],
        highRiskTools: [
          { toolName: 'nl2sql.nl2sqlQuery', riskLevel: 'HIGH', exposed: false, requiresConfirmation: true }
        ]
      }
    })
    assistantAPIMock.readMcpResource.mockResolvedValue({
      data: {
        surface: 'resource',
        name: 'assistant://runs/{runId}/graph',
        policy: 'explicit allowlist + admin + high-risk confirmation + audit',
        content: {
          runId: 'run-1'
        }
      }
    })
    assistantAPIMock.renderMcpPrompt.mockResolvedValue({
      data: {
        surface: 'prompt',
        name: 'ops.rca',
        policy: 'explicit allowlist + admin + high-risk confirmation + audit',
        template: 'Diagnose service=order-service with RCA evidence bundle'
      }
    })
    assistantAPIMock.runEvalSuite.mockResolvedValue({
      data: {
        suite: 'RAG',
        status: 'RUNNING',
        evalRunId: 'rag-eval-2',
        resultType: 'RAG_EVAL_RUN',
        nextActions: ['Poll /api/rag-eval/status/rag-eval-2']
      }
    })
    assistantAPIMock.getEvalRun.mockResolvedValue({
      data: {
        suite: 'RAG',
        status: 'COMPLETED',
        evalRunId: 'rag-eval-2',
        totalCases: 10,
        completedCases: 10,
        progress: 1,
        metrics: {
          avgRecall: 0.86
        },
        nextActions: ['Review bad cases after completion']
      }
    })
    assistantAPIMock.getRunGraph.mockResolvedValue({
      data: {
        runId: 'run-1',
        checkpointId: 'run-1:ROUTED',
        checkpointStage: 'ROUTED',
        summary: {
          checkpoint: {
            resumable: true,
            checkpointFingerprint: 'fp-run-1-routed',
            latestReplayAttemptId: 'run-1:replay:2:fp-run-1-routed',
            idempotencyPolicy: 'RUN_ID_MODE_RESUME_COUNT_CHECKPOINT_FINGERPRINT',
            checkpointReplayEvents: 1
          },
          recoveryPlan: {
            resumable: true,
            resumeFromStage: 'ROUTED',
            resumeCheckpointId: 'run-1:ROUTED',
            checkpointFingerprint: 'fp-run-1-routed',
            latestReplayAttemptId: 'run-1:replay:2:fp-run-1-routed',
            idempotencyPolicy: 'RUN_ID_MODE_RESUME_COUNT_CHECKPOINT_FINGERPRINT'
          }
        },
        nodes: []
      }
    })
    ragEvalAPIMock.getRunReport.mockResolvedValue({
      data: {
        evalRunId: 'rag-1',
        baselineRunId: 'rag-0',
        completedCases: 50
      }
    })
    ragEvalAPIMock.compareRun.mockResolvedValue({
      data: {
        evalRunId: 'rag-1',
        baselineRunId: 'rag-0',
        metricDiff: {
          avgRecall: 0.04
        },
        caseDiffs: {
          count: 12
        }
      }
    })
    ragEvalAPIMock.listBadCases.mockResolvedValue({
      data: [
        {
          badCaseId: 'bad-1',
          question: '退票答案没有引用证据',
          failureType: 'citation_missing',
          reviewStatus: 'PENDING'
        }
      ]
    })
    ragEvalAPIMock.convertBadCase.mockResolvedValue({
      data: {
        caseId: 'case-1'
      }
    })
    ragEvalAPIMock.reviewBadCase.mockResolvedValue({
      data: {
        badCaseId: 'bad-1',
        reviewStatus: 'FIXED'
      }
    })
    ragEvalAPIMock.listIngestionTasks.mockResolvedValue({
      data: [
        {
          taskId: 'reindex-1',
          taskType: 'full',
          taskStatus: 'RUNNING',
          completedChunks: 12,
          totalChunks: 40
        }
      ]
    })
    ragEvalAPIMock.createReindexJob.mockResolvedValue({
      data: {
        taskId: 'reindex-2',
        taskType: 'incremental',
        taskStatus: 'SUBMITTED'
      }
    })
    aiOpsAdminAPIMock.listFaultScenarios.mockResolvedValue({
      data: [
        {
          scenarioId: 'error-spike',
          name: '错误率升高',
          serviceName: 'order-service',
          windowMinutes: 30,
          injectedSignals: {
            traceId: 'error-trace-002'
          }
        }
      ]
    })
    aiOpsAdminAPIMock.injectFaultScenario.mockResolvedValue({
      data: {
        status: 'SIMULATED',
        evidenceBundle: {
          suspectedCause: 'application error spike',
          serviceName: 'order-service',
          traceId: 'error-trace-002'
        }
      }
    })
    aiOpsAdminAPIMock.buildRcaEvidence.mockResolvedValue({
      data: {
        serviceName: 'order-service',
        traceId: 'error-trace-002',
        suspectedCause: 'application error spike',
        serviceTopology: {
          rootService: 'gateway-service',
          suspectService: 'order-service',
          dependencyEdges: [{ from: 'gateway-service', to: 'order-service' }]
        },
        recentChanges: {
          items: [{ type: 'release', serviceName: 'order-service', version: 'error-spike-release-20260630' }]
        },
        evidenceTimeline: [{ kind: 'trace', service: 'order-service', summary: 'first failing span' }],
        suggestedActions: ['rollback risky release']
      }
    })
  })

  it('loads admin quality, RAG bad cases, and AIOps fault scenarios', async () => {
    const runtime = useAssistantRuntime()

    await runtime.loadCapabilities()

    expect(runtime.qualityGate.value.status).toBe('PASS')
    expect(runtime.mcpGovernance.value.requireHighRiskConfirmation).toBe(true)
    expect(runtime.mcpGovernance.value.highRiskTools[0].exposed).toBe(false)
    expect(runtime.ragEvalReport.value.evalRunId).toBe('rag-1')
    expect(runtime.ragBaselineRunId.value).toBe('rag-0')
    expect(runtime.ragBadCases.value[0].badCaseId).toBe('bad-1')
    expect(runtime.ragIngestionTasks.value[0].taskId).toBe('reindex-1')
    expect(runtime.aiOpsFaultScenarios.value[0].scenarioId).toBe('error-spike')

    await runtime.compareRagEvalWithBaseline()
    expect(ragEvalAPIMock.compareRun).toHaveBeenCalledWith('rag-1', 'rag-0')
    expect(runtime.ragEvalComparison.value.caseDiffs.count).toBe(12)

    await runtime.convertBadCaseToEval(runtime.ragBadCases.value[0])
    expect(ragEvalAPIMock.convertBadCase).toHaveBeenCalledWith('bad-1', expect.objectContaining({
      datasetId: 'default-golden'
    }))

    await runtime.reviewBadCase(runtime.ragBadCases.value[0], 'FIXED', 'fixed')
    expect(ragEvalAPIMock.reviewBadCase).toHaveBeenCalledWith('bad-1', {
      reviewStatus: 'FIXED',
      reviewNote: 'fixed'
    })

    await runtime.createRagReindexJob('incremental')
    expect(ragEvalAPIMock.createReindexJob).toHaveBeenCalledWith('incremental')
    expect(runtime.ragLastReindexJob.value.taskId).toBe('reindex-2')
    expect(ragEvalAPIMock.listIngestionTasks).toHaveBeenCalled()

    await runtime.runEvalSuite('rag', { limit: 5 })
    expect(assistantAPIMock.runEvalSuite).toHaveBeenCalledWith('rag', { limit: 5 })
    expect(runtime.evalSuiteResult.value.evalRunId).toBe('rag-eval-2')
    expect(runtime.evalSuiteRunning.value).toBe('')

    await runtime.refreshEvalSuiteRun()
    expect(assistantAPIMock.getEvalRun).toHaveBeenCalledWith('rag', 'rag-eval-2')
    expect(runtime.evalSuiteResult.value.status).toBe('COMPLETED')
    expect(runtime.evalSuiteResult.value.metrics.avgRecall).toBe(0.86)

    await runtime.injectAiOpsFaultScenario(runtime.aiOpsFaultScenarios.value[0])
    expect(runtime.aiOpsFaultResult.value.evidenceBundle.suspectedCause).toBe('application error spike')
    expect(aiOpsAdminAPIMock.injectFaultScenario).toHaveBeenCalledWith('error-spike', expect.objectContaining({
      traceId: 'error-trace-002',
      releaseVersion: 'error-spike-release-20260630',
      configKey: 'error-spike.feature-flag'
    }))

    await runtime.buildAiOpsRcaEvidence(runtime.aiOpsFaultScenarios.value[0])
    expect(aiOpsAdminAPIMock.buildRcaEvidence).toHaveBeenCalledWith(expect.objectContaining({
      serviceName: 'order-service',
      traceId: 'error-trace-002',
      releaseVersion: 'error-spike-release-20260630'
    }))
    expect(runtime.aiOpsFaultResult.value.status).toBe('RCA_EVIDENCE')
    expect(runtime.aiOpsFaultResult.value.evidenceBundle.serviceTopology.suspectService).toBe('order-service')

    await runtime.readMcpResource('assistant://runs/{runId}/graph', { runId: 'run-1' })
    expect(assistantAPIMock.readMcpResource).toHaveBeenCalledWith(expect.objectContaining({
      resourceUri: 'assistant://runs/{runId}/graph',
      confirmed: true
    }))
    expect(runtime.mcpBoundaryResult.value.name).toBe('assistant://runs/{runId}/graph')

    await runtime.renderMcpPrompt('ops.rca', { serviceName: 'order-service' })
    expect(assistantAPIMock.renderMcpPrompt).toHaveBeenCalledWith(expect.objectContaining({
      promptName: 'ops.rca',
      confirmed: true
    }))
    expect(runtime.mcpBoundaryResult.value.template).toContain('RCA evidence bundle')
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

  it('renders cached customer-service quick answer without opening an assistant run', async () => {
    customerServiceAPIMock.quickAnswer.mockResolvedValueOnce({
      data: {
        hit: true,
        answerMode: 'CACHED_ANSWER',
        intentCode: 'REFUND_RULE',
        routeHint: 'knowledge',
        directAnswer: '退票以项目详情页和订单页规则为准。',
        sourceRefs: [
          { title: '平台规则知识库', snippet: '退票规则以项目页为准' }
        ],
        actionButtons: [
          { label: '咨询订单售后', action: 'ask', intentCode: 'ORDER_AFTERSALE' }
        ],
        suggestions: [
          { questionId: 'real-name-entry', displayText: '实名入场', queryText: '实名入场要带什么证件', intentCode: 'REAL_NAME_RULE' }
        ],
        sentiment: {
          sentiment: 'NEUTRAL',
          intensity: 0
        },
        latencyMs: 80
      }
    })

    const runtime = useAssistantRuntime()
    await runtime.sendMessage('退票规则')

    expect(assistantAPIMock.sendMessage).not.toHaveBeenCalled()
    expect(runtime.currentMessages.value.at(-1).content).toContain('退票以项目详情页')
    expect(runtime.evidenceCards.value[0].title).toBe('平台规则知识库')
    expect(runtime.customerServiceCard.value.hit).toBe(true)
    expect(runtime.runStatus.value).toBe('QUICK_ANSWER')
    expect(runtime.customerSuggestions.value[0].displayText).toBe('实名入场')
  })

  it('replays a checkpointed run through the explicit replay endpoint', async () => {
    assistantAPIMock.replayRun.mockResolvedValue({
      data: {
        runId: 'run-1',
        chatId: 'chat-1',
        checkpointId: 'run-1:ROUTED',
        checkpointStage: 'ROUTED',
        checkpointFingerprint: 'fp-run-1-routed',
        replayAttemptId: 'run-1:replay:2:fp-run-1-routed',
        idempotencyPolicy: 'RUN_ID_MODE_RESUME_COUNT_CHECKPOINT_FINGERPRINT',
        replayScheduled: true,
        eventStreamPath: '/assistant/runs/run-1/events'
      }
    })
    assistantAPIMock.streamRun.mockResolvedValue(createEventStream([
      {
        event: 'run.replay_requested',
        data: {
          runId: 'run-1',
          checkpointStage: 'ROUTED',
          checkpointFingerprint: 'fp-run-1-routed',
          replayAttemptId: 'run-1:replay:2:fp-run-1-routed',
          idempotencyPolicy: 'RUN_ID_MODE_RESUME_COUNT_CHECKPOINT_FINGERPRINT',
          skipRouting: true,
          riskHint: 'REPLAY_AFTER_ROUTING'
        },
        id: 'evt-replay'
      },
      {
        event: 'checkpoint.replayed',
        data: {
          runId: 'run-1',
          checkpointStage: 'ROUTED',
          checkpointFingerprint: 'fp-run-1-routed',
          replayAttemptId: 'run-1:process:2:fp-run-1-routed',
          idempotencyPolicy: 'RUN_ID_MODE_RESUME_COUNT_CHECKPOINT_FINGERPRINT'
        },
        id: 'evt-checkpoint'
      },
      {
        event: 'run.completed',
        data: {
          status: 'COMPLETED'
        },
        id: 'evt-replay-done'
      }
    ]))
    assistantAPIMock.getRun.mockResolvedValue({
      data: {
        run: {
          runId: 'run-1',
          routeType: 'KNOWLEDGE',
          skillId: 'knowledge.policy.qa',
          runStatus: 'COMPLETED'
        },
        events: [
          {
            eventId: 'evt-replay',
            eventType: 'run.replay_requested',
            payloadJson: JSON.stringify({
              runId: 'run-1',
              checkpointStage: 'ROUTED',
              checkpointFingerprint: 'fp-run-1-routed',
              replayAttemptId: 'run-1:replay:2:fp-run-1-routed',
              idempotencyPolicy: 'RUN_ID_MODE_RESUME_COUNT_CHECKPOINT_FINGERPRINT',
              skipRouting: true,
              riskHint: 'REPLAY_AFTER_ROUTING'
            }),
            createTime: '2026-05-14T09:02:00Z'
          },
          {
            eventId: 'evt-checkpoint',
            eventType: 'checkpoint.replayed',
            payloadJson: JSON.stringify({
              runId: 'run-1',
              checkpointStage: 'ROUTED',
              checkpointFingerprint: 'fp-run-1-routed',
              replayAttemptId: 'run-1:process:2:fp-run-1-routed',
              idempotencyPolicy: 'RUN_ID_MODE_RESUME_COUNT_CHECKPOINT_FINGERPRINT'
            }),
            createTime: '2026-05-14T09:02:01Z'
          }
        ],
        stageTraces: [],
        retrievalTraces: []
      }
    })
    assistantAPIMock.getRunGraph.mockResolvedValue({
      data: {
        runId: 'run-1',
        checkpointId: 'run-1:ROUTED',
        summary: {
          checkpoint: {
            resumable: true,
            checkpointFingerprint: 'fp-run-1-routed',
            latestReplayAttemptId: 'run-1:process:2:fp-run-1-routed',
            idempotencyPolicy: 'RUN_ID_MODE_RESUME_COUNT_CHECKPOINT_FINGERPRINT',
            checkpointReplayEvents: 1
          },
          recoveryPlan: {
            resumable: true,
            checkpointFingerprint: 'fp-run-1-routed',
            latestReplayAttemptId: 'run-1:process:2:fp-run-1-routed',
            idempotencyPolicy: 'RUN_ID_MODE_RESUME_COUNT_CHECKPOINT_FINGERPRINT'
          }
        },
        nodes: []
      }
    })

    const runtime = useAssistantRuntime()
    await runtime.loadCapabilities()
    await runtime.loadConversations()
    await runtime.loadConversation('chat-1')
    await runtime.replayRun()

    expect(assistantAPIMock.replayRun).toHaveBeenCalledWith('run-1')
    expect(assistantAPIMock.streamRun).toHaveBeenCalledWith('/assistant/runs/run-1/events')
    expect(runtime.orderedTimeline.value.map(item => item.event)).toContain('run.replay_requested')
    expect(String(runtime.orderedTimeline.value.map(item => item.data?.checkpointFingerprint))).toContain('fp-run-1-routed')
    expect(runtime.runGraph.value.checkpointId).toBe('run-1:ROUTED')
    expect(runtime.runGraph.value.summary.recoveryPlan.latestReplayAttemptId).toBe('run-1:process:2:fp-run-1-routed')
    expect(runtime.runGraph.value.summary.recoveryPlan.idempotencyPolicy).toBe('RUN_ID_MODE_RESUME_COUNT_CHECKPOINT_FINGERPRINT')
    expect(runtime.runStatus.value).toBe('COMPLETED')
  })
})
