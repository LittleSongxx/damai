import { shallowMount } from '@vue/test-utils'
import { ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const runtimeState = vi.hoisted(() => ({
  loadCapabilities: vi.fn(),
  loadCustomerStarterPrompts: vi.fn(),
  loadConversations: vi.fn(),
  loadConversation: vi.fn(),
  loadAdminWorkspace: vi.fn(),
  startNewChat: vi.fn(),
  sendMessage: vi.fn(),
  resumeRun: vi.fn(),
  replayRun: vi.fn(),
  compareRagEvalWithBaseline: vi.fn(),
  runEvalSuite: vi.fn(),
  refreshEvalSuiteRun: vi.fn(),
  convertBadCaseToEval: vi.fn(),
  reviewBadCase: vi.fn(),
  createRagReindexJob: vi.fn(),
  readMcpResource: vi.fn(),
  renderMcpPrompt: vi.fn(),
  injectAiOpsFaultScenario: vi.fn(),
  buildAiOpsRcaEvidence: vi.fn(),
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
    currentSkillId: ref('knowledge.policy.qa'),
    currentSkillName: ref('规则知识问答'),
    currentMessages: ref([]),
    customerStarterPrompts: ref([
      { questionId: 'refund-rule', displayText: '退票规则', queryText: '节目开演前还能退票吗', intentCode: 'REFUND_RULE' },
      { questionId: 'human-handoff', displayText: '转人工', queryText: '我要转人工客服', intentCode: 'HUMAN_HANDOFF' }
    ]),
    customerServiceCard: ref({
      hit: true,
      intentCode: 'REFUND_RULE',
      latencyMs: 80
    }),
    customerSuggestions: ref([
      { questionId: 'real-name-entry', displayText: '实名入场', queryText: '实名入场要带什么证件', intentCode: 'REAL_NAME_RULE' }
    ]),
    customerSentiment: ref({
      sentiment: 'NEUTRAL',
      intensity: 0
    }),
    customerEscalationTicket: ref({
      ticketId: 'ticket-1',
      priority: 'MEDIUM',
      ticketStatus: 'OPEN',
      suggestedReply: '请先确认用户诉求和订单信息。'
    }),
    conversations: ref([
      { id: 'chat-9', title: '退票规则咨询', routeType: 'KNOWLEDGE', workflowStatus: 'COMPLETED' }
    ]),
    orderedTimeline: ref([
      { id: 'evt-1', event: 'route.selected', data: { routeType: 'KNOWLEDGE' } }
    ]),
    evidenceCards: ref([
      { chunkId: 'faq-1', title: '退票 FAQ', snippet: '开演前一天按规则退票。' }
    ]),
    stageTraces: ref([
      {
        id: 'stage-1',
        stepKey: 'KNOWLEDGE_RETRIEVAL_FIRST_PASS',
        requestType: 'KnowledgeRetrieval',
        latencyMs: 218,
        success: true,
        metadata: { finalHitCount: 4 }
      }
    ]),
    retrievalTraces: ref([
      {
        id: 'retrieval-1',
        stepKey: 'knowledge.shadow_route',
        traceType: 'route',
        originalQuery: '退票规则',
        denseHitCount: 0,
        sparseHitCount: 0,
        fusedHitCount: 0,
        finalHitCount: 0,
        metadata: {
          shadowRoute: {
            scopeCandidates: [{ name: '票务规则', score: 3.2 }],
            topicCandidates: [{ name: '退票 FAQ', score: 2.4 }],
            documentCandidates: [{ name: 'refund.md', score: 2.1 }]
          }
        }
      }
    ]),
    runGraph: ref({
      runId: 'run-9',
      checkpointId: 'run-9:ROUTED',
      checkpointStage: 'ROUTED',
      summary: {
        checkpoint: {
          resumable: true,
          resumeCount: 2,
          checkpointReplayEvents: 1,
          checkpointFingerprint: 'fp-run-9',
          latestReplayAttemptId: 'run-9:replay:2:fp-run-9',
          idempotencyPolicy: 'RUN_ID_MODE_RESUME_COUNT_CHECKPOINT_FINGERPRINT'
        },
        recoveryPlan: {
          resumable: true,
          resumeFromStage: 'ROUTED',
          resumeCheckpointId: 'run-9:ROUTED',
          checkpointFingerprint: 'fp-run-9',
          latestReplayAttemptId: 'run-9:replay:2:fp-run-9',
          idempotencyPolicy: 'RUN_ID_MODE_RESUME_COUNT_CHECKPOINT_FINGERPRINT',
          skipRouting: true,
          skipRetrieval: false,
          requiresHumanReview: true,
          hasFailedNodes: true,
          auditComplete: false,
          riskHint: 'FAILED_NODE_REVIEW_REQUIRED',
          nextActions: [
            'Inspect failed graph nodes before replay',
            'Require human review for high-risk approval/tool nodes',
            'Resume from checkpoint and skip already completed safe stages'
          ]
        },
        estimatedCost: '0.001200',
        auditTrail: [
          {
            eventOrder: 4,
            eventType: 'run.resumed',
            checkpointStage: 'ROUTED',
            checkpointFingerprint: 'fp-run-9',
            replayAttemptId: 'run-9:resume:1:fp-run-9',
            idempotencyPolicy: 'RUN_ID_MODE_RESUME_COUNT_CHECKPOINT_FINGERPRINT',
            eventCategory: 'run'
          },
          {
            eventOrder: 5,
            eventType: 'checkpoint.replayed',
            checkpointStage: 'ROUTED',
            checkpointFingerprint: 'fp-run-9',
            replayAttemptId: 'run-9:replay:2:fp-run-9',
            idempotencyPolicy: 'RUN_ID_MODE_RESUME_COUNT_CHECKPOINT_FINGERPRINT',
            eventCategory: 'stage'
          }
        ],
        highRiskNodes: [
          { id: 'approval', label: 'Approval', riskLevel: 'HIGH' }
        ]
      },
      nodes: [
        { id: 'route', type: 'route', label: 'Route', status: 'COMPLETED', outputSummary: 'knowledge' },
        { id: 'plan', type: 'plan', label: 'Plan', status: 'COMPLETED', outputSummary: 'skill mode' },
        { id: 'tool', type: 'tool', label: 'Tools/Retrieval', status: 'COMPLETED', eventCategory: 'retrieval', outputSummary: 'retrieved chunks=3', totalTokens: 321, estimatedCost: '0.0012' },
        { id: 'final', type: 'final', label: 'Finalize', status: 'FAILED', outputSummary: 'boom' }
      ]
    }),
    memorySummary: ref({
      summaryVersion: 2,
      compressionCount: 1,
      hasContent: true,
      structuredMemory: {
        summary: '用户在确认某场演出的退票规则。',
        conversationGoal: '弄清是否还能退票',
        stableFacts: ['用户关注开演前一天的退票限制'],
        pendingQuestions: ['需要确认具体演出场次'],
        retrievalHints: ['退票', '开演前一天']
      }
    }),
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
    qualityGate: ref({
      status: 'PASS',
      latestRagRunId: 'rag-1',
      latestNl2SqlRunId: 'sql-1',
      releaseReadiness: {
        status: 'READY',
        approvalHint: 'prompt/config release can proceed with baseline comparison and rollback plan'
      },
      coverageSummary: {
        domains: ['auth', 'run-graph', 'rag-evalops', 'nl2sql-safety', 'mcp-governance']
      },
      ragClosure: {
        status: 'ACTION_REQUIRED',
        baselineReady: false,
        releaseBlocked: true,
        baselineRunId: '',
        candidateEvalCaseIds: ['case-rag-1'],
        nextActions: [
          'run baseline comparison before prompt/config release',
          'convert low-score top bad cases into golden eval cases'
        ],
        workflow: ['online bad case', 'human review', 'convert to eval', 'baseline comparison', 'prompt/config release']
      },
      nl2SqlContract: {
        status: 'READY',
        contractReady: true,
        evalRunId: 'sql-1',
        unsafeRejectionRate: 1,
        lowConfidenceClarificationRate: 0.2,
        schemaLinkRecall: 0.91,
        costGuardPolicy: 'EXPLAIN_LIMIT_AND_TIMEOUT',
        responseFields: [
          'status',
          'sql',
          'evidence',
          'safetyReport',
          'executionPlan',
          'resultPreview',
          'maskedColumns',
          'repairTrace'
        ],
        executionPlanFields: [
          'mode',
          'costGuard',
          'costGuardReport',
          'estimatedRows',
          'queryCost',
          'rowCount',
          'truncated',
          'durationMs'
        ]
      },
      capabilityEvidence: [
        {
          capability: 'assistant-eval-control-plane',
          evidence: 'Unified EvalOps start/status interface for RAG, NL2SQL, red-team, and quality-gate suites'
        },
        {
          capability: 'rag-reindex-jobs',
          evidence: 'Async knowledge-base reindex jobs with task history surfaced in the admin workspace'
        },
        {
          capability: 'rag-closure-plan',
          evidence: 'RAG reports expose baseline readiness and bad-case closure workflow'
        }
      ],
      failureSamples: [
        {
          gate: 'RAG_EVAL',
          nextAction: 'review RAG bad cases'
        }
      ],
      gates: [
        { name: 'RAG_EVAL', status: 'PASS' },
        {
          name: 'NL2SQL_EVAL',
          status: 'PASS',
          details: {
            executionAccuracy: 0.82,
            schemaLinkRecall: 0.91,
            unsafeRejectionRate: 1,
            lowConfidenceClarificationRate: 0.2
          }
        },
        { name: 'MCP_GOVERNANCE', status: 'PASS' },
        { name: 'AIOPS_LAB', status: 'PASS' }
      ]
    }),
    mcpGovernance: ref({
      status: 'PASS',
      requireAdmin: true,
      requireHighRiskConfirmation: true,
      exposeNl2Sql: false,
      allowlistSize: 12,
      resourceAllowlistSize: 4,
      promptAllowlistSize: 3,
      allowlistedTools: [
        {
          toolName: 'log.getServiceList',
          riskLevel: 'LOW',
          scope: 'logs',
          reason: 'read-only observability tool'
        },
        {
          toolName: 'metrics.getCpuMetrics',
          riskLevel: 'LOW',
          scope: 'metrics',
          reason: 'read-only observability tool'
        }
      ],
      highRiskTools: [
        {
          toolName: 'nl2sql.nl2sqlQuery',
          exposed: false,
          riskLevel: 'HIGH',
          requiresConfirmation: true
        }
      ],
      allowlistedResources: [
        {
          name: 'assistant://runs/{runId}/graph',
          riskLevel: 'HIGH',
          scope: 'assistant-runtime'
        },
        {
          name: 'observability://traces/{traceId}',
          riskLevel: 'LOW',
          scope: 'observability'
        }
      ],
      allowlistedPrompts: [
        {
          name: 'ops.rca',
          riskLevel: 'HIGH',
          scope: 'ops-prompt'
        },
        {
          name: 'ops.log-diagnosis',
          riskLevel: 'LOW',
          scope: 'ops-prompt'
        }
      ]
    }),
    mcpBoundaryResult: ref({
      surface: 'prompt',
      name: 'ops.rca',
      policy: 'explicit allowlist + admin + high-risk confirmation + audit',
      template: 'Diagnose service=order-service with RCA evidence bundle'
    }),
    evalSuiteResult: ref({
      suite: 'RAG',
      status: 'RUNNING',
      evalRunId: 'rag-eval-2',
      resultType: 'RAG_EVAL_RUN',
      totalCases: 10,
      completedCases: 10,
      progress: 1,
      metrics: {
        avgRecall: 0.86,
        avgFaithfulness: 0.91
      },
      nextActions: [
        'Poll /api/rag-eval/status/rag-eval-2',
        'Review bad cases after completion'
      ]
    }),
    evalSuiteRunning: ref(''),
    ragEvalReport: ref({
      evalRunId: 'rag-1',
      baselineRunId: 'rag-0',
      completedCases: 50,
      totalCases: 50
    }),
    ragBaselineRunId: ref('rag-0'),
    ragEvalComparison: ref({
      baselineRunId: 'rag-0',
      metricDiff: {
        avgRecall: 0.04,
        avgAnswerCorrectness: 0.02
      },
      caseDiffs: {
        count: 12
      }
    }),
    ragBadCases: ref([
      {
        badCaseId: 'bad-1',
        question: '退票答案没有引用证据',
        failureType: 'citation_missing',
        reviewStatus: 'PENDING'
      }
    ]),
    ragIngestionTasks: ref([
      {
        taskId: 'reindex-1',
        taskType: 'full',
        taskStatus: 'RUNNING',
        completedChunks: 12,
        totalChunks: 40
      },
      {
        taskId: 'reindex-0',
        taskType: 'incremental',
        taskStatus: 'COMPLETED',
        completedChunks: 8,
        totalChunks: 8
      }
    ]),
    ragLastReindexJob: ref({
      taskId: 'reindex-2',
      taskType: 'incremental',
      taskStatus: 'SUBMITTED'
    }),
    aiOpsFaultScenarios: ref([
      {
        scenarioId: 'error-spike',
        name: '错误率升高',
        serviceName: 'order-service',
        severity: 'SEV1'
      }
    ]),
    aiOpsFaultResult: ref({
        evidenceBundle: {
          suspectedCause: 'application error spike',
          slo: {
            severity: 'CRITICAL',
            burnRate: 4.8,
            errorBudgetRemaining: 0.14,
            alertTriggered: true,
            alertName: 'DamaiAiFaultInjectionSloBurn'
          },
          alertContext: {
            triggered: true,
            name: 'DamaiAiFaultInjectionSloBurn',
            serviceName: 'order-service',
            routingHint: 'oncall:order-service',
            primarySignal: 'scenario=error-spike'
          },
          spanId: 'error-spike-span-001',
          serviceTopology: {
            rootService: 'gateway-service',
            suspectService: 'order-service',
          dependencyEdges: [
            { from: 'gateway-service', to: 'order-service' },
            { from: 'order-service', to: 'payment-service' }
          ]
        },
        signalCorrelation: {
          evidenceCompleteness: 'STRONG',
          coverageScore: 1,
          linkedSignals: ['logs', 'trace', 'metrics', 'promql-range', 'recent-changes'],
          serviceLabels: {
            service: 'order-service',
            traceId: 'error-trace-002',
            spanId: 'error-spike-span-001'
          }
        },
        recentChanges: {
          items: [
            { type: 'release', serviceName: 'order-service', version: 'error-spike-release-20260630' },
            { type: 'config', serviceName: 'order-service', configKey: 'error-spike.feature-flag' }
          ]
        },
        evidenceTimeline: [
          { kind: 'trace', service: 'order-service', summary: 'first failing span' },
          { kind: 'log', service: 'order-service', summary: 'application error spike' }
        ],
        suggestedActions: ['rollback risky release'],
        playbookHints: ['slo-playbook: track burn-rate recovery']
      },
      assistantPrompt: '请基于模拟故障证据诊断 order-service'
    }),
    hasMessages: ref(false),
    canUseOps: ref(true),
    capabilities: ref({
      admin: true,
      allowedRoutes: ['business', 'knowledge', 'general', 'ops'],
      skills: []
    }),
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
    await Promise.resolve()
    await Promise.resolve()
    expect(runtimeState.loadCustomerStarterPrompts).toHaveBeenCalled()
    expect(runtimeState.loadCapabilities).toHaveBeenCalled()
    expect(runtimeState.loadConversations).toHaveBeenCalled()
    expect(wrapper.text()).toContain('大麦智能客服')
    expect(wrapper.text()).toContain('KNOWLEDGE')
    expect(wrapper.text()).toContain('治理后台')
    expect(wrapper.text()).toContain('退票规则')
    expect(wrapper.text()).toContain('客服状态')
    expect(wrapper.text()).toContain('秒答命中')
    expect(wrapper.text()).toContain('ticket-1')
    expect(wrapper.text()).toContain('退票 FAQ')
    expect(wrapper.text()).toContain('确认是否提交订单')
    expect(wrapper.text()).toContain('请选择意图')
    expect(wrapper.text()).toContain('长期记忆')
    expect(wrapper.text()).toContain('用户在确认某场演出的退票规则。')
    expect(wrapper.text()).toContain('阶段观测')
    expect(wrapper.text()).toContain('首轮检索')
    expect(wrapper.text()).toContain('检索细节')
    expect(wrapper.text()).toContain('票务规则')
    expect(wrapper.text()).toContain('图式运行')
    expect(wrapper.text()).toContain('ROUTED')
    expect(wrapper.text()).toContain('Replayable')
    expect(wrapper.text()).toContain('YES')
    expect(wrapper.text()).toContain('2 / 1')
    expect(wrapper.text()).toContain('Recovery Plan')
    expect(wrapper.text()).toContain('route skip')
    expect(wrapper.text()).toContain('retrieval run')
    expect(wrapper.text()).toContain('FAILED_NODE_REVIEW_REQUIRED')
    expect(wrapper.text()).toContain('fingerprint fp-run-9')
    expect(wrapper.text()).toContain('attempt run-9:replay:2:fp-run-9')
    expect(wrapper.text()).toContain('idempotency RUN_ID_MODE_RESUME_COUNT_CHECKPOINT_FINGERPRINT')
    expect(wrapper.text()).toContain('Inspect failed graph nodes before replay')
    expect(wrapper.text()).toContain('Require human review for high-risk approval/tool nodes')
    expect(wrapper.text()).toContain('Replay Audit')
    expect(wrapper.text()).toContain('checkpoint.replayed')
    expect(wrapper.text()).toContain('Risk Nodes')
    expect(wrapper.text()).toContain('Approval · HIGH')
    expect(wrapper.text()).toContain('retrieved chunks=3')
    expect(wrapper.text()).toContain('tokens 321')
    expect(wrapper.text()).toContain('治理门禁')
    expect(wrapper.text()).toContain('Eval Control')
    expect(wrapper.text()).toContain('Run RAG')
    expect(wrapper.text()).toContain('Run NL2SQL')
    expect(wrapper.text()).toContain('Run Red Team')
    expect(wrapper.text()).toContain('RAG · RUNNING')
    expect(wrapper.text()).toContain('rag-eval-2')
    expect(wrapper.text()).toContain('10/10 cases')
    expect(wrapper.text()).toContain('avgRecall 0.86')
    expect(wrapper.text()).toContain('avgFaithfulness 0.91')
    expect(wrapper.text()).toContain('Review bad cases after completion')
    expect(wrapper.text()).toContain('MCP_GOVERNANCE')
    expect(wrapper.text()).toContain('READY')
    expect(wrapper.text()).toContain('baseline comparison')
    expect(wrapper.text()).toContain('run-graph')
    expect(wrapper.text()).toContain('rag-evalops')
    expect(wrapper.text()).toContain('Capability Evidence')
    expect(wrapper.text()).toContain('assistant-eval-control-plane')
    expect(wrapper.text()).toContain('Unified EvalOps start/status interface')
    expect(wrapper.text()).toContain('rag-reindex-jobs')
    expect(wrapper.text()).toContain('RAG Closure')
    expect(wrapper.text()).toContain('ACTION_REQUIRED')
    expect(wrapper.text()).toContain('baseline missing')
    expect(wrapper.text()).toContain('release blocked')
    expect(wrapper.text()).toContain('run baseline comparison before prompt/config release')
    expect(wrapper.text()).toContain('NL2SQL Contract')
    expect(wrapper.text()).toContain('contract ready')
    expect(wrapper.text()).toContain('unsafe 1.00')
    expect(wrapper.text()).toContain('schema 0.91')
    expect(wrapper.text()).toContain('cost EXPLAIN_LIMIT_AND_TIMEOUT')
    expect(wrapper.text()).toContain('status / sql / evidence / safetyReport')
    expect(wrapper.text()).toContain('rag-closure-plan')
    expect(wrapper.text()).toContain('review RAG bad cases')
    expect(wrapper.text()).toContain('exec 0.82')
    expect(wrapper.text()).toContain('schema 0.91')
    expect(wrapper.text()).toContain('协议边界')
    expect(wrapper.text()).toContain('tools 12')
    expect(wrapper.text()).toContain('resources 4')
    expect(wrapper.text()).toContain('prompts 3')
    expect(wrapper.text()).toContain('log.getServiceList')
    expect(wrapper.text()).toContain('metrics.getCpuMetrics')
    expect(wrapper.text()).toContain('Confirm')
    expect(wrapper.text()).toContain('BLOCKED')
    expect(wrapper.text()).toContain('nl2sql.nl2sqlQuery')
    expect(wrapper.text()).toContain('assistant://runs/{runId}/graph')
    expect(wrapper.text()).toContain('observability://traces/{traceId}')
    expect(wrapper.text()).toContain('ops.rca')
    expect(wrapper.text()).toContain('ops.log-diagnosis')
    expect(wrapper.text()).toContain('Boundary Result')
    expect(wrapper.text()).toContain('prompt · ops.rca')
    expect(wrapper.text()).toContain('explicit allowlist + admin + high-risk confirmation + audit')
    expect(wrapper.text()).toContain('Diagnose service=order-service')
    expect(wrapper.text()).toContain('AIOPS_LAB')
    expect(wrapper.text()).toContain('评测闭环')
    expect(wrapper.text()).toContain('rag-0')
    expect(wrapper.text()).toContain('recall 0.040')
    expect(wrapper.text()).toContain('Reindex Jobs')
    expect(wrapper.text()).toContain('submitted incremental · reindex-2')
    expect(wrapper.text()).toContain('reindex-1')
    expect(wrapper.text()).toContain('full · 12/40 chunks')
    expect(wrapper.text()).toContain('RUNNING')
    expect(wrapper.text()).toContain('reindex-0')
    expect(wrapper.text()).toContain('COMPLETED')
    expect(wrapper.text()).toContain('退票答案没有引用证据')
    expect(wrapper.text()).toContain('故障演练')
    expect(wrapper.text()).toContain('错误率升高')
    expect(wrapper.text()).toContain('application error spike')
    expect(wrapper.text()).toContain('SLO / Alert')
    expect(wrapper.text()).toContain('CRITICAL')
    expect(wrapper.text()).toContain('burn 4.8x')
    expect(wrapper.text()).toContain('budget 14%')
    expect(wrapper.text()).toContain('oncall:order-service')
    expect(wrapper.text()).toContain('scenario=error-spike')
    expect(wrapper.text()).toContain('Topology')
    expect(wrapper.text()).toContain('gateway-service -> order-service')
    expect(wrapper.text()).toContain('Correlation')
    expect(wrapper.text()).toContain('STRONG')
    expect(wrapper.text()).toContain('score 1.00')
    expect(wrapper.text()).toContain('span error-spike-span-001')
    expect(wrapper.text()).toContain('logs / trace / metrics / promql-range / recent-changes')
    expect(wrapper.text()).toContain('Recent Changes')
    expect(wrapper.text()).toContain('error-spike-release-20260630')
    expect(wrapper.text()).toContain('Evidence Timeline')
    expect(wrapper.text()).toContain('first failing span')
    expect(wrapper.text()).toContain('rollback risky release')
    expect(wrapper.text()).toContain('Playbook')
    expect(wrapper.text()).toContain('slo-playbook: track burn-rate recovery')

    await wrapper.get('.primary-button').trigger('click')
    expect(runtimeState.approveAction).toHaveBeenCalled()
    await wrapper.findAll('.starter-chip').find(item => item.text() === '退票规则').trigger('click')
    expect(runtimeState.sendMessage).toHaveBeenCalledWith('节目开演前还能退票吗', {
      scene: 'customer_service',
      intentHint: 'REFUND_RULE',
      hotQuestionId: 'refund-rule'
    })
    await wrapper.findAll('.starter-chip').find(item => item.text() === '查询或购买演出票').trigger('click')
    expect(runtimeState.sendMessage).toHaveBeenCalledWith('查询或购买演出票')
    await wrapper.findAll('button').find(item => item.text() === 'Compare').trigger('click')
    expect(runtimeState.compareRagEvalWithBaseline).toHaveBeenCalled()
    await wrapper.findAll('button').find(item => item.text() === 'Run RAG').trigger('click')
    expect(runtimeState.runEvalSuite).toHaveBeenCalledWith('rag')
    await wrapper.findAll('button').find(item => item.text() === 'Run NL2SQL').trigger('click')
    expect(runtimeState.runEvalSuite).toHaveBeenCalledWith('nl2sql')
    await wrapper.findAll('button').find(item => item.text() === 'Run Red Team').trigger('click')
    expect(runtimeState.runEvalSuite).toHaveBeenCalledWith('red-team')
    await wrapper.findAll('button').find(item => item.text() === 'Refresh Eval').trigger('click')
    expect(runtimeState.refreshEvalSuiteRun).toHaveBeenCalledWith('RAG', 'rag-eval-2')
    await wrapper.findAll('button').find(item => item.text() === 'Full').trigger('click')
    expect(runtimeState.createRagReindexJob).toHaveBeenCalledWith('full')
    await wrapper.findAll('button').find(item => item.text() === 'Incremental').trigger('click')
    expect(runtimeState.createRagReindexJob).toHaveBeenCalledWith('incremental')
    await wrapper.findAll('button').find(item => item.text() === 'Read Resource').trigger('click')
    expect(runtimeState.readMcpResource).toHaveBeenCalled()
    await wrapper.findAll('button').find(item => item.text() === 'Render Prompt').trigger('click')
    expect(runtimeState.renderMcpPrompt).toHaveBeenCalled()
    await wrapper.findAll('button').find(item => item.text() === '转 Eval').trigger('click')
    expect(runtimeState.convertBadCaseToEval).toHaveBeenCalled()
    await wrapper.findAll('button').find(item => item.text() === '已修复').trigger('click')
    expect(runtimeState.reviewBadCase).toHaveBeenCalled()
    await wrapper.findAll('button').find(item => item.text() === 'Inject').trigger('click')
    expect(runtimeState.injectAiOpsFaultScenario).toHaveBeenCalled()
    await wrapper.findAll('button').find(item => item.text() === 'Build RCA').trigger('click')
    expect(runtimeState.buildAiOpsRcaEvidence).toHaveBeenCalled()
    await wrapper.findAll('button').find(item => item.text() === 'Replay').trigger('click')
    expect(runtimeState.replayRun).toHaveBeenCalled()
  })
})
