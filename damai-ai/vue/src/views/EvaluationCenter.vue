<template>
  <div class="eval-page">
    <header class="eval-page__header">
      <div>
        <p class="eyebrow">EvalOps</p>
        <h1>AI 评测中心</h1>
        <p>统一管理客服 RAG、AI 购票 Agent 和管理员问数的离线评测、质量门禁与失败回放。</p>
      </div>
      <div class="header-actions">
        <button class="ghost-button" :disabled="busy" @click="loadDashboard">刷新看板</button>
        <button class="primary-button" :disabled="busy" @click="createRun">发起评测</button>
      </div>
    </header>

    <p v-if="successMessage" class="notice">{{ successMessage }}</p>
    <p v-if="errorMessage" class="notice notice--error">{{ errorMessage }}</p>

    <section class="summary-strip">
      <article class="summary-card" :class="`summary-card--${statusClass(overallStatus)}`">
        <span>总体状态</span>
        <strong>{{ overallStatus }}</strong>
        <p>{{ releaseStatus }}</p>
      </article>
      <article class="summary-card">
        <span>最近 RAG</span>
        <strong>{{ latestRunId('ragCustomer') }}</strong>
        <p>{{ metricText(ragMetrics, 'avgRecall', 'Recall') }}</p>
      </article>
      <article class="summary-card">
        <span>最近购票</span>
        <strong>{{ latestRunId('purchaseAgent') }}</strong>
        <p>{{ metricText(purchaseMetrics, 'trajectoryPassRate', '轨迹通过率') }}</p>
      </article>
      <article class="summary-card">
        <span>最近问数</span>
        <strong>{{ latestRunId('nl2SqlAdmin') }}</strong>
        <p>{{ metricText(nl2SqlMetrics, 'resultSetEquivalenceRate', '结果等价率') }}</p>
      </article>
    </section>

    <main class="eval-grid">
      <section class="panel run-panel">
        <div class="panel-title">
          <div>
            <p class="eyebrow">Run</p>
            <h2>创建评测任务</h2>
          </div>
        </div>
        <div class="form-grid">
          <label>
            业务线
            <select v-model="runForm.domain">
              <option v-for="domain in domains" :key="domain.value" :value="domain.value">{{ domain.label }}</option>
            </select>
          </label>
          <label>
            数据集
            <input v-model="runForm.datasetId" placeholder="default-golden" />
          </label>
          <label>
            Candidate 配置
            <input v-model="runForm.candidateConfigId" placeholder="retrieval / prompt / schema config" />
          </label>
          <label>
            抽样模式
            <select v-model="runForm.sampleMode">
              <option value="FULL">FULL</option>
              <option value="SMOKE">SMOKE</option>
              <option value="FAILED_ONLY">FAILED_ONLY</option>
            </select>
          </label>
          <label>
            样本上限
            <input v-model.number="runForm.limit" type="number" min="1" placeholder="可选" />
          </label>
          <label>
            标签
            <input v-model="tagText" placeholder="release,baseline" />
          </label>
        </div>
        <div class="panel-actions">
          <button class="primary-button" :disabled="busy" @click="createRun">发起评测</button>
          <button class="ghost-button ghost-button--soft" :disabled="!lastCreatedRunId || busy" @click="loadRun(lastCreatedRunId)">查看最新任务</button>
        </div>
      </section>

      <section class="panel domain-panel">
        <div class="domain-tabs">
          <button
            v-for="domain in domains"
            :key="domain.value"
            :class="{ active: selectedDomain === domain.key }"
            @click="selectedDomain = domain.key"
          >
            {{ domain.label }}
          </button>
        </div>
        <div class="panel-title">
          <div>
            <p class="eyebrow">Quality Gate</p>
            <h2>{{ selectedDomainTitle }}</h2>
          </div>
          <span class="status-pill" :class="`status-pill--${statusClass(selectedGate.status)}`">{{ selectedGate.status || 'UNKNOWN' }}</span>
        </div>
        <p class="gate-message">{{ selectedGate.message || '暂无质量门禁结果。' }}</p>
        <div class="metric-grid">
          <div v-for="metric in selectedMetrics" :key="metric.key" class="metric-cell">
            <span>{{ metric.label }}</span>
            <strong>{{ formatMetric(metric.value, metric.percent) }}</strong>
          </div>
        </div>
      </section>

      <section class="panel trend-panel">
        <div class="panel-title">
          <div>
            <p class="eyebrow">Trend</p>
            <h2>最近 7 次评测</h2>
          </div>
        </div>
        <p v-if="!selectedTrend.length" class="empty-text">暂无趋势数据。</p>
        <ol v-else class="trend-list">
          <li v-for="run in selectedTrend" :key="run.evalRunId">
            <button @click="loadRun(run.evalRunId)">
              <strong>{{ run.evalRunId }}</strong>
              <span>{{ run.runStatus || '-' }}</span>
              <span>{{ trendMetric(run) }}</span>
            </button>
          </li>
        </ol>
      </section>

      <section class="panel failure-panel">
        <div class="panel-title">
          <div>
            <p class="eyebrow">Bad Cases</p>
            <h2>失败样例</h2>
          </div>
          <button class="ghost-button ghost-button--soft" :disabled="!activeRunId || busy" @click="replayFailed(activeRunId)">回放失败</button>
        </div>
        <p v-if="!failureItems.length" class="empty-text">暂无失败样例。</p>
        <ol v-else class="failure-list">
          <li v-for="item in failureItems" :key="`${item.caseId}-${item.failureReason || item.failureType || item.errorMessage}`">
            <strong>{{ item.caseId || '-' }}</strong>
            <p>{{ item.question || item.failureReason || item.errorMessage || '未记录问题描述' }}</p>
            <span>{{ item.failureReason || item.failureType || item.errorMessage || '待诊断' }}</span>
          </li>
        </ol>
      </section>

      <section class="panel result-panel">
        <div class="panel-title">
          <div>
            <p class="eyebrow">Run Detail</p>
            <h2>评测明细</h2>
          </div>
          <span>{{ resultItems.length }} 条</span>
        </div>
        <p v-if="!resultItems.length" class="empty-text">点击趋势或发起任务后查看 case 明细。</p>
        <div v-else class="result-table">
          <div class="result-row result-row--head">
            <span>Case</span>
            <span>状态</span>
            <span>关键指标</span>
          </div>
          <div v-for="item in resultItems.slice(0, 20)" :key="item.caseId || item.id" class="result-row">
            <span>{{ item.caseId || '-' }}</span>
            <span>{{ item.finalStatus || item.status || '-' }}</span>
            <span>{{ resultMetric(item) }}</span>
          </div>
        </div>
      </section>
    </main>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ensureAuthenticated, evaluationCenterAPI } from '../api/api'

const domains = [
  { key: 'ragCustomer', value: 'RAG_CUSTOMER', label: 'RAG 客服' },
  { key: 'purchaseAgent', value: 'PURCHASE_AGENT', label: 'AI 购票' },
  { key: 'nl2SqlAdmin', value: 'NL2SQL 问数' }
]

const dashboard = ref({})
const runDetail = ref(null)
const runResults = ref(null)
const selectedDomain = ref('ragCustomer')
const busy = ref(false)
const errorMessage = ref('')
const successMessage = ref('')
const tagText = ref('')
const lastCreatedRunId = ref('')

const runForm = reactive({
  domain: 'RAG_CUSTOMER',
  datasetId: 'default-golden',
  candidateConfigId: '',
  sampleMode: 'SMOKE',
  limit: 10
})

const overallStatus = computed(() => dashboard.value?.qualityGate?.status || 'UNKNOWN')
const releaseStatus = computed(() => dashboard.value?.qualityGate?.releaseReadiness?.status || '等待评测')
const ragMetrics = computed(() => latestDetails('ragCustomer'))
const purchaseMetrics = computed(() => latestDetails('purchaseAgent'))
const nl2SqlMetrics = computed(() => latestDetails('nl2SqlAdmin'))
const activeRunId = computed(() => runDetail.value?.run?.evalRunId || latestRunId(selectedDomain.value))
const selectedDomainTitle = computed(() => domains.find(domain => domain.key === selectedDomain.value)?.label || '评测')
const selectedGate = computed(() => dashboard.value?.[selectedDomain.value]?.qualityGate || {})
const selectedTrend = computed(() => dashboard.value?.[selectedDomain.value]?.trend || [])
const failureItems = computed(() => {
  const domainSamples = dashboard.value?.[selectedDomain.value]?.failureSamples
  if (Array.isArray(domainSamples)) {
    return domainSamples
  }
  return []
})
const resultItems = computed(() => runResults.value?.results || [])

const selectedMetrics = computed(() => {
  const details = selectedGate.value?.details || latestDetails(selectedDomain.value)
  if (selectedDomain.value === 'ragCustomer') {
    return [
      { key: 'avgRecall', label: 'Recall@5', value: details.avgRecall, percent: true },
      { key: 'avgMrr', label: 'MRR', value: details.avgMrr, percent: true },
      { key: 'avgFaithfulness', label: 'Faithfulness', value: details.avgFaithfulness, percent: true },
      { key: 'avgAnswerRelevancy', label: 'Relevancy', value: details.avgAnswerRelevancy, percent: true }
    ]
  }
  if (selectedDomain.value === 'purchaseAgent') {
    return [
      { key: 'slotAccuracy', label: '槽位命中率', value: details.slotAccuracy, percent: true },
      { key: 'toolCallAccuracy', label: '工具准确率', value: details.toolCallAccuracy, percent: true },
      { key: 'trajectoryPassRate', label: '轨迹通过率', value: details.trajectoryPassRate, percent: true },
      { key: 'p95LatencyMs', label: 'p95 延迟', value: details.p95LatencyMs, percent: false }
    ]
  }
  return [
    { key: 'sqlValidityRate', label: 'SQL Validity', value: details.sqlValidityRate, percent: true },
    { key: 'resultSetEquivalenceRate', label: '结果等价率', value: details.resultSetEquivalenceRate, percent: true },
    { key: 'unsafeRejectionRate', label: '危险拒绝率', value: details.unsafeRejectionRate, percent: true },
    { key: 'avgLatencyMs', label: '平均延迟', value: details.avgLatencyMs, percent: false }
  ]
})

onMounted(async () => {
  if (!ensureAuthenticated()) {
    return
  }
  await loadDashboard()
})

async function loadDashboard() {
  await runTask(async () => {
    dashboard.value = unwrap(await evaluationCenterAPI.getDashboard())
    successMessage.value = '评测看板已刷新'
  })
}

async function createRun() {
  await runTask(async () => {
    const payload = {
      ...runForm,
      runTags: tagText.value.split(',').map(item => item.trim()).filter(Boolean)
    }
    const result = unwrap(await evaluationCenterAPI.createRun(payload))
    lastCreatedRunId.value = result.evalRunId || ''
    successMessage.value = `已创建评测任务 ${lastCreatedRunId.value || ''}`.trim()
    await loadRun(lastCreatedRunId.value)
    await loadDashboard()
  })
}

async function loadRun(evalRunId) {
  if (!evalRunId) {
    return
  }
  await runTask(async () => {
    runDetail.value = unwrap(await evaluationCenterAPI.getRun(evalRunId))
    runResults.value = unwrap(await evaluationCenterAPI.getRunResults(evalRunId))
    successMessage.value = `已加载 ${evalRunId}`
  })
}

async function replayFailed(evalRunId) {
  if (!evalRunId) {
    return
  }
  await runTask(async () => {
    const result = unwrap(await evaluationCenterAPI.replayFailed(evalRunId))
    successMessage.value = `已提交失败回放，样例数 ${result.replayed ?? 0}`
    await loadDashboard()
  })
}

async function runTask(task) {
  busy.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    await task()
  } catch (error) {
    errorMessage.value = error?.message || '操作失败'
  } finally {
    busy.value = false
  }
}

function unwrap(response) {
  return response?.data ?? response ?? {}
}

function latestDetails(key) {
  return dashboard.value?.[key]?.qualityGate?.details
    || dashboard.value?.[key]?.latestRun
    || {}
}

function latestRunId(key) {
  return dashboard.value?.[key]?.latestRun?.evalRunId || '-'
}

function metricText(metrics, key, label) {
  const value = metrics?.[key] ?? metrics?.value?.[key]
  return `${label} ${formatMetric(value, true)}`
}

function formatMetric(value, percent = false) {
  if (value === undefined || value === null || value === '') {
    return '-'
  }
  const number = Number(value)
  if (!Number.isFinite(number)) {
    return String(value)
  }
  if (percent) {
    return `${(number * 100).toFixed(1)}%`
  }
  return number >= 1000 ? `${Math.round(number)} ms` : number.toFixed(number % 1 === 0 ? 0 : 2)
}

function statusClass(status) {
  const normalized = String(status || '').toLowerCase()
  if (normalized === 'pass' || normalized === 'ready') {
    return 'pass'
  }
  if (normalized === 'fail' || normalized === 'blocked') {
    return 'fail'
  }
  return 'warn'
}

function trendMetric(run) {
  if (selectedDomain.value === 'ragCustomer') {
    return `Recall ${formatMetric(run.avgRecall, true)}`
  }
  if (selectedDomain.value === 'purchaseAgent') {
    return `轨迹 ${formatMetric(run.trajectoryPassRate, true)}`
  }
  return `等价 ${formatMetric(run.resultSetEquivalenceRate, true)}`
}

function resultMetric(item) {
  if (item.recallAt5 !== undefined) {
    return `Recall ${formatMetric(item.recallAt5, true)} / MRR ${formatMetric(item.mrr, true)}`
  }
  if (item.trajectoryPassed !== undefined) {
    return `槽位 ${formatMetric(item.slotAccuracy, true)} / 工具 ${formatMetric(item.toolCallAccuracy, true)}`
  }
  if (item.resultSetEquivalent !== undefined) {
    return `等价 ${item.resultSetEquivalent} / Valid ${item.isValidSql}`
  }
  return '-'
}
</script>

<style scoped lang="scss">
.eval-page {
  max-width: 1480px;
  margin: 0 auto;
}

.eval-page__header,
.panel,
.summary-card {
  border: 1px solid var(--border-color);
  background: var(--surface-color);
  box-shadow: var(--shadow-sm);
  backdrop-filter: blur(18px);
}

.eval-page__header {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  align-items: flex-start;
  padding: 24px;
  border-radius: var(--radius-xl);
}

.eval-page__header h1,
.panel h2 {
  margin: 0;
}

.eval-page__header p {
  margin: 6px 0 0;
  color: var(--text-soft);
}

.eyebrow {
  margin: 0 0 4px;
  color: var(--primary-color);
  font-size: 0.72rem;
  font-weight: 800;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.header-actions,
.panel-actions,
.domain-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.primary-button,
.ghost-button {
  min-height: 40px;
  border: 0;
  border-radius: 14px;
  padding: 0 16px;
  cursor: pointer;
  font-weight: 800;
}

.primary-button {
  color: #fff;
  background: var(--primary-color);
}

.ghost-button {
  color: var(--text-color);
  background: var(--surface-strong);
  border: 1px solid var(--border-color);
}

.ghost-button--soft {
  background: var(--primary-soft);
  color: var(--primary-strong);
}

button:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.notice {
  margin: 14px 0 0;
  padding: 12px 16px;
  border-radius: 14px;
  color: var(--secondary-color);
  background: rgba(37, 80, 200, 0.1);
}

.notice--error {
  color: #b42318;
  background: rgba(180, 35, 24, 0.1);
}

.summary-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin: 16px 0;
}

.summary-card {
  min-height: 118px;
  padding: 18px;
  border-radius: var(--radius-lg);
}

.summary-card span,
.metric-cell span,
.trend-list span,
.failure-list span {
  color: var(--text-soft);
  font-size: 0.86rem;
}

.summary-card strong {
  display: block;
  margin: 8px 0;
  font-size: 1.35rem;
}

.summary-card p {
  margin: 0;
  color: var(--text-soft);
}

.summary-card--pass {
  border-color: rgba(20, 150, 80, 0.38);
}

.summary-card--warn {
  border-color: rgba(245, 158, 11, 0.42);
}

.summary-card--fail {
  border-color: rgba(220, 38, 38, 0.42);
}

.eval-grid {
  display: grid;
  grid-template-columns: 0.95fr 1.05fr;
  gap: 16px;
}

.panel {
  padding: 20px;
  border-radius: var(--radius-lg);
}

.panel-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.form-grid,
.metric-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

label {
  display: grid;
  gap: 6px;
  color: var(--text-soft);
  font-size: 0.88rem;
  font-weight: 700;
}

input,
select {
  min-height: 42px;
  width: 100%;
  border: 1px solid var(--border-color);
  border-radius: 12px;
  padding: 0 12px;
  color: var(--text-color);
  background: var(--surface-strong);
}

.panel-actions {
  margin-top: 16px;
}

.domain-tabs {
  margin-bottom: 18px;
}

.domain-tabs button {
  border: 1px solid var(--border-color);
  border-radius: 999px;
  padding: 8px 14px;
  color: var(--text-soft);
  background: var(--surface-strong);
  cursor: pointer;
  font-weight: 800;
}

.domain-tabs button.active {
  color: #fff;
  background: var(--accent-color);
}

.status-pill {
  border-radius: 999px;
  padding: 6px 10px;
  font-size: 0.8rem;
  font-weight: 900;
}

.status-pill--pass {
  color: #087443;
  background: rgba(20, 150, 80, 0.14);
}

.status-pill--warn {
  color: #a15c00;
  background: rgba(245, 158, 11, 0.14);
}

.status-pill--fail {
  color: #b42318;
  background: rgba(220, 38, 38, 0.13);
}

.gate-message,
.empty-text {
  color: var(--text-soft);
}

.metric-cell {
  padding: 14px;
  border: 1px solid var(--border-color);
  border-radius: 14px;
  background: var(--surface-strong);
}

.metric-cell strong {
  display: block;
  margin-top: 6px;
  font-size: 1.2rem;
}

.trend-list,
.failure-list {
  display: grid;
  gap: 10px;
  padding: 0;
  margin: 0;
  list-style: none;
}

.trend-list button {
  width: 100%;
  display: grid;
  grid-template-columns: 1fr auto auto;
  gap: 12px;
  align-items: center;
  border: 1px solid var(--border-color);
  border-radius: 14px;
  padding: 12px;
  color: var(--text-color);
  background: var(--surface-strong);
  cursor: pointer;
  text-align: left;
}

.failure-list li {
  border: 1px solid var(--border-color);
  border-radius: 14px;
  padding: 12px;
  background: var(--surface-strong);
}

.failure-list p {
  margin: 4px 0;
  color: var(--text-soft);
}

.result-panel {
  grid-column: 1 / -1;
}

.result-table {
  display: grid;
  gap: 8px;
}

.result-row {
  display: grid;
  grid-template-columns: 1.2fr 0.5fr 1fr;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--border-color);
  border-radius: 12px;
  background: var(--surface-strong);
}

.result-row--head {
  color: var(--text-soft);
  font-weight: 900;
}

@media (max-width: 960px) {
  .eval-page__header,
  .summary-strip,
  .eval-grid,
  .form-grid,
  .metric-grid {
    grid-template-columns: 1fr;
  }

  .eval-page__header {
    display: grid;
  }

  .result-row,
  .trend-list button {
    grid-template-columns: 1fr;
  }
}
</style>
