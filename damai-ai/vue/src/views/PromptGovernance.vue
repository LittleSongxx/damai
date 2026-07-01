<template>
  <div class="prompt-page">
    <header class="prompt-page__header">
      <div>
        <p class="eyebrow">PromptOps</p>
        <h1>Prompt 版本治理</h1>
        <p>草稿、发布、灰度、回滚和缓存刷新集中管理，发布动作会留下审计记录。</p>
      </div>
      <div class="header-actions">
        <RouterLink class="ghost-button" to="/assistant">返回助手</RouterLink>
        <button class="ghost-button" @click="loadAll">刷新</button>
        <button class="ghost-button" @click="invalidateCache">刷新缓存</button>
      </div>
    </header>

    <p v-if="successMessage" class="notice">{{ successMessage }}</p>
    <p v-if="errorMessage" class="notice notice--error">{{ errorMessage }}</p>

    <section class="prompt-toolbar">
      <label>
        Prompt Key
        <input v-model="promptKeyFilter" placeholder="knowledge.answer" @keyup.enter="loadAll" />
      </label>
      <button class="primary-button" @click="loadAll">查询</button>
    </section>

    <main class="prompt-grid">
      <section class="panel">
        <div class="panel-title">
          <div>
            <p class="eyebrow">Versions</p>
            <h2>版本列表</h2>
          </div>
          <span>{{ versionItems.length }} 个版本</span>
        </div>
        <p v-if="!versionItems.length" class="empty-text">暂无 Prompt 版本。</p>
        <div v-else class="version-list">
          <button
            v-for="version in versionItems"
            :key="`${version.promptKey}-${version.version}`"
            class="version-row"
            :class="{ active: selectedVersionKey === version.rowKey }"
            @click="selectVersion(version)"
          >
            <div>
              <strong>{{ version.promptKey }} v{{ version.version }}</strong>
              <p>{{ version.description || version.releaseNote || '无描述' }}</p>
            </div>
            <span class="status-pill" :class="version.statusClass">{{ version.rolloutStatus || 'DRAFT' }}</span>
            <span>{{ version.trafficPercent ?? 0 }}%</span>
          </button>
        </div>
      </section>

      <section class="panel editor-panel">
        <div class="panel-title">
          <div>
            <p class="eyebrow">Draft</p>
            <h2>创建草稿</h2>
          </div>
        </div>
        <div class="form-grid">
          <label>
            Prompt Key
            <input v-model="draftForm.promptKey" placeholder="knowledge.answer" />
          </label>
          <label>
            描述
            <input v-model="draftForm.description" placeholder="本次修改目的" />
          </label>
          <label class="full">
            Template
            <textarea v-model="draftForm.template" rows="10" placeholder="输入 Prompt 模板内容"></textarea>
          </label>
        </div>
        <div class="panel-actions">
          <button class="primary-button" :disabled="busy" @click="createDraft">保存草稿</button>
        </div>

        <div class="selected-card" v-if="selectedVersion">
          <p class="eyebrow">Selected</p>
          <h3>{{ selectedVersion.promptKey }} v{{ selectedVersion.version }}</h3>
          <p>{{ selectedVersion.description || selectedVersion.releaseNote || '未填写描述' }}</p>
          <pre>{{ selectedVersion.template }}</pre>
        </div>
      </section>

      <section class="panel release-panel">
        <div class="panel-title">
          <div>
            <p class="eyebrow">Release</p>
            <h2>发布控制</h2>
          </div>
        </div>
        <div class="form-grid">
          <label>
            目标版本
            <input :value="releaseTargetText" readonly />
          </label>
          <label>
            发布模式
            <select v-model="releaseForm.rolloutStatus">
              <option value="STABLE">稳定发布</option>
              <option value="GRADUAL">灰度发布</option>
            </select>
          </label>
          <label>
            灰度比例
            <input v-model.number="releaseForm.trafficPercent" type="number" min="1" max="99" />
          </label>
          <label>
            Baseline Eval Run
            <input v-model="releaseForm.baselineEvalRunId" placeholder="rag-eval-run-id" />
          </label>
          <label class="full">
            发布说明
            <textarea v-model="releaseForm.releaseNote" rows="4" placeholder="说明评测结果、风险和回滚策略"></textarea>
          </label>
          <label class="full">
            回滚原因
            <textarea v-model="rollbackReason" rows="3" placeholder="仅回滚时填写"></textarea>
          </label>
        </div>
        <div v-if="releasePlan" class="release-plan" :class="`release-plan--${String(releasePlan.status || '').toLowerCase()}`">
          <div class="release-plan__head">
            <div>
              <p class="eyebrow">Release Plan</p>
              <h3>{{ releasePlan.status || 'UNKNOWN' }}</h3>
            </div>
            <span class="status-pill" :class="releasePlan.publishAllowed ? 'status-pill--stable' : 'status-pill--danger'">
              {{ releasePlan.publishAllowed ? 'publish allowed' : 'blocked' }}
            </span>
          </div>
          <div class="plan-grid">
            <span>traffic {{ releasePlan.recommendedTrafficPercent ?? 0 }}%</span>
            <span>baseline {{ releasePlan.baselineEvalRunId || '-' }}</span>
            <span>rollback v{{ releasePlan.rollbackTargetVersion || '-' }}</span>
            <span>quality {{ releasePlan.evidence?.qualityGateStatus || '-' }}</span>
          </div>
          <ul v-if="releasePlan.blockers?.length" class="plan-list plan-list--danger">
            <li v-for="item in releasePlan.blockers" :key="item">{{ item }}</li>
          </ul>
          <ul v-if="releasePlan.warnings?.length" class="plan-list">
            <li v-for="item in releasePlan.warnings" :key="item">{{ item }}</li>
          </ul>
          <ol v-if="releasePlan.nextActions?.length" class="plan-list">
            <li v-for="item in releasePlan.nextActions" :key="item">{{ item }}</li>
          </ol>
        </div>
        <p v-else class="empty-text">选择版本后生成发布计划。</p>
        <div class="panel-actions">
          <button class="ghost-button ghost-button--soft" :disabled="!selectedVersion || busy" @click="refreshReleasePlan">生成计划</button>
          <button class="primary-button" :disabled="!selectedVersion || busy" @click="publishSelected">发布</button>
          <button class="ghost-button ghost-button--soft" :disabled="!selectedVersion || busy" @click="promoteSelected">提升稳定</button>
          <button class="ghost-button ghost-button--soft" :disabled="!selectedVersion || busy" @click="rollbackSelected">回滚到此版本</button>
        </div>
      </section>

      <section class="panel records-panel">
        <div class="panel-title">
          <div>
            <p class="eyebrow">Audit</p>
            <h2>发布记录</h2>
          </div>
          <span>{{ recordItems.length }} 条</span>
        </div>
        <p v-if="!recordItems.length" class="empty-text">暂无发布记录。</p>
        <ol v-else class="record-list">
          <li v-for="record in recordItems" :key="record.releaseId || record.id">
            <div>
              <strong>{{ record.actionType }} · {{ record.promptKey }}</strong>
              <p>v{{ record.fromVersion || '-' }} -> v{{ record.toVersion }} · {{ record.rolloutStatus }} {{ record.trafficPercent ?? '-' }}%</p>
              <p v-if="record.releaseNote">{{ record.releaseNote }}</p>
              <p v-if="record.rollbackReason" class="danger-text">{{ record.rollbackReason }}</p>
            </div>
            <span>{{ formatTime(record.createTime) }}</span>
          </li>
        </ol>
      </section>
    </main>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { ensureAuthenticated, promptVersionAPI } from '../api/api'

const promptKeyFilter = ref('')
const versions = ref([])
const releaseRecords = ref([])
const selectedVersion = ref(null)
const releasePlan = ref(null)
const busy = ref(false)
const errorMessage = ref('')
const successMessage = ref('')
const rollbackReason = ref('')

const draftForm = reactive({
  promptKey: '',
  description: '',
  template: ''
})

const releaseForm = reactive({
  rolloutStatus: 'STABLE',
  trafficPercent: 10,
  baselineEvalRunId: '',
  releaseNote: ''
})

const versionItems = computed(() => versions.value.map(version => ({
  ...version,
  rowKey: `${version.promptKey}:${version.version}`,
  statusClass: statusClass(version.rolloutStatus)
})))

const recordItems = computed(() => releaseRecords.value.slice(0, 30))

const selectedVersionKey = computed(() => selectedVersion.value
  ? `${selectedVersion.value.promptKey}:${selectedVersion.value.version}`
  : '')

const releaseTargetText = computed(() => selectedVersion.value
  ? `${selectedVersion.value.promptKey} v${selectedVersion.value.version}`
  : '请选择一个版本')

function statusClass(status) {
  if (status === 'STABLE') return 'status-pill--stable'
  if (status === 'GRADUAL') return 'status-pill--gradual'
  if (status === 'ROLLED_BACK') return 'status-pill--danger'
  if (status === 'SUPERSEDED') return 'status-pill--muted'
  return 'status-pill--draft'
}

function normalizeData(result) {
  return Array.isArray(result?.data) ? result.data : []
}

async function loadAll() {
  errorMessage.value = ''
  try {
    const [versionResult, recordResult] = await Promise.all([
      promptVersionAPI.list(promptKeyFilter.value),
      promptVersionAPI.listReleaseRecords(promptKeyFilter.value)
    ])
    versions.value = normalizeData(versionResult)
    releaseRecords.value = normalizeData(recordResult)
    if (selectedVersion.value) {
      selectedVersion.value = versions.value.find(item =>
        item.promptKey === selectedVersion.value.promptKey && item.version === selectedVersion.value.version) || null
    }
  } catch (error) {
    errorMessage.value = error.message || '加载 Prompt 治理数据失败'
  }
}

function selectVersion(version) {
  selectedVersion.value = version
  draftForm.promptKey = version.promptKey
  releaseForm.baselineEvalRunId = version.baselineEvalRunId || ''
  releaseForm.releaseNote = version.releaseNote || ''
  releaseForm.rolloutStatus = version.rolloutStatus === 'GRADUAL' ? 'GRADUAL' : 'STABLE'
  releaseForm.trafficPercent = version.rolloutStatus === 'GRADUAL' ? (version.trafficPercent || 10) : 10
  refreshReleasePlan()
}

async function runAction(action, message) {
  busy.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    const result = await action()
    if (result?.releasePlan) {
      releasePlan.value = result.releasePlan
    }
    successMessage.value = message
    await loadAll()
    return result
  } catch (error) {
    if (error.payload?.releasePlan) {
      releasePlan.value = error.payload.releasePlan
    }
    errorMessage.value = error.message || message
    return null
  } finally {
    busy.value = false
  }
}

async function createDraft() {
  await runAction(async () => {
    const result = await promptVersionAPI.createDraft({ ...draftForm })
    draftForm.template = ''
    draftForm.description = ''
    selectedVersion.value = result?.data || null
    return result
  }, '草稿已创建')
}

async function publishSelected() {
  if (!selectedVersion.value) return
  await ensureReleasePlan()
  await runAction(() => promptVersionAPI.publish({
    promptKey: selectedVersion.value.promptKey,
    version: selectedVersion.value.version,
    rolloutStatus: releaseForm.rolloutStatus,
    trafficPercent: releaseForm.trafficPercent,
    baselineEvalRunId: releaseForm.baselineEvalRunId,
    releaseNote: releaseForm.releaseNote
  }), '版本已发布')
}

async function promoteSelected() {
  if (!selectedVersion.value) return
  await ensureReleasePlan()
  await runAction(() => promptVersionAPI.promote({
    promptKey: selectedVersion.value.promptKey,
    version: selectedVersion.value.version,
    baselineEvalRunId: releaseForm.baselineEvalRunId,
    releaseNote: releaseForm.releaseNote || 'promote to stable'
  }), '版本已提升为稳定')
}

async function rollbackSelected() {
  if (!selectedVersion.value) return
  await ensureReleasePlan()
  await runAction(() => promptVersionAPI.rollback({
    promptKey: selectedVersion.value.promptKey,
    version: selectedVersion.value.version,
    baselineEvalRunId: releaseForm.baselineEvalRunId,
    reason: rollbackReason.value
  }), '已回滚到所选版本')
}

async function refreshReleasePlan() {
  if (!selectedVersion.value) {
    releasePlan.value = null
    return null
  }
  try {
    const result = await promptVersionAPI.buildReleasePlan({
      promptKey: selectedVersion.value.promptKey,
      version: selectedVersion.value.version,
      rolloutStatus: releaseForm.rolloutStatus,
      trafficPercent: releaseForm.trafficPercent,
      baselineEvalRunId: releaseForm.baselineEvalRunId
    })
    releasePlan.value = result?.data || null
    return releasePlan.value
  } catch (error) {
    errorMessage.value = error.message || '生成发布计划失败'
    return null
  }
}

async function ensureReleasePlan() {
  if (!releasePlan.value) {
    await refreshReleasePlan()
  }
}

async function invalidateCache() {
  await runAction(() => promptVersionAPI.invalidateCache(), 'Prompt 缓存已刷新')
}

function formatTime(value) {
  if (!value) return '-'
  return new Date(value).toLocaleString()
}

onMounted(async () => {
  if (!ensureAuthenticated()) {
    return
  }
  await loadAll()
})

watch(() => [
  selectedVersion.value?.promptKey,
  selectedVersion.value?.version,
  releaseForm.rolloutStatus,
  releaseForm.trafficPercent,
  releaseForm.baselineEvalRunId
], () => {
  if (selectedVersion.value) {
    refreshReleasePlan()
  }
})
</script>

<style scoped lang="scss">
.prompt-page {
  display: grid;
  gap: 18px;
}

.prompt-page__header,
.header-actions,
.prompt-toolbar,
.panel-title,
.panel-actions {
  display: flex;
}

.prompt-page__header {
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
}

.prompt-page__header h1,
.prompt-page__header p,
.panel-title h2 {
  margin: 0;
}

.prompt-page__header p:not(.eyebrow) {
  margin-top: 8px;
  color: var(--text-soft);
}

.header-actions,
.panel-actions {
  flex-wrap: wrap;
  gap: 10px;
}

.prompt-toolbar {
  align-items: end;
  gap: 12px;
  padding: 16px;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: var(--surface-strong);
}

.prompt-toolbar label {
  flex: 1;
}

.prompt-grid {
  display: grid;
  grid-template-columns: minmax(360px, 0.9fr) minmax(420px, 1.1fr);
  gap: 18px;
}

.panel {
  min-width: 0;
  padding: 18px;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: var(--surface-strong);
}

.records-panel {
  grid-column: 1 / -1;
}

.panel-title {
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.panel-title span,
.empty-text {
  color: var(--text-soft);
}

.version-list,
.record-list,
.form-grid {
  display: grid;
  gap: 12px;
}

.version-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto 52px;
  align-items: center;
  gap: 12px;
  width: 100%;
  min-height: 72px;
  padding: 12px;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: var(--surface-color);
  color: var(--text-color);
  text-align: left;
  cursor: pointer;
}

.version-row.active {
  border-color: var(--primary-color);
  background: rgba(255, 90, 54, 0.08);
}

.version-row p {
  margin: 4px 0 0;
  color: var(--text-soft);
}

.status-pill {
  padding: 5px 8px;
  border-radius: 8px;
  background: var(--surface-color);
  font-size: 0.78rem;
  font-weight: 700;
}

.status-pill--stable {
  color: #1c7c54;
  background: rgba(28, 124, 84, 0.12);
}

.status-pill--gradual {
  color: #8b5a00;
  background: rgba(219, 154, 24, 0.16);
}

.status-pill--danger {
  color: #b42318;
  background: rgba(180, 35, 24, 0.12);
}

.status-pill--muted,
.status-pill--draft {
  color: var(--text-soft);
  background: rgba(18, 32, 63, 0.08);
}

.form-grid {
  grid-template-columns: 1fr 1fr;
}

label,
.form-grid label {
  display: grid;
  gap: 8px;
  color: var(--text-soft);
}

.full {
  grid-column: 1 / -1;
}

input,
select,
textarea {
  width: 100%;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 10px 12px;
  background: var(--surface-color);
  color: var(--text-color);
}

textarea {
  resize: vertical;
}

.selected-card {
  display: grid;
  gap: 10px;
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px solid var(--border-color);
}

.release-plan {
  display: grid;
  gap: 12px;
  margin-top: 16px;
  padding: 14px;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: var(--surface-color);
}

.release-plan--blocked {
  border-color: rgba(180, 35, 24, 0.36);
}

.release-plan__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.release-plan__head h3 {
  margin: 0;
}

.plan-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.plan-grid span {
  min-width: 0;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--surface-strong);
  color: var(--text-soft);
  overflow-wrap: anywhere;
}

.plan-list {
  display: grid;
  gap: 6px;
  margin: 0;
  padding-left: 18px;
  color: var(--text-soft);
}

.plan-list--danger {
  color: #b42318;
}

.selected-card h3,
.selected-card p {
  margin: 0;
}

pre {
  max-height: 240px;
  overflow: auto;
  padding: 12px;
  border-radius: 8px;
  background: var(--surface-color);
  white-space: pre-wrap;
}

.record-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.record-list li {
  display: flex;
  justify-content: space-between;
  gap: 18px;
  padding: 14px 0;
  border-top: 1px solid var(--border-color);
}

.record-list li:first-child {
  border-top: 0;
}

.record-list p {
  margin: 4px 0 0;
  color: var(--text-soft);
}

.danger-text {
  color: #b42318;
}

.notice {
  padding: 12px 14px;
  border: 1px solid rgba(38, 146, 92, 0.28);
  border-radius: 8px;
  background: rgba(38, 146, 92, 0.08);
}

.notice--error {
  border-color: rgba(201, 52, 52, 0.28);
  background: rgba(201, 52, 52, 0.08);
}

.ghost-button,
.primary-button {
  border: none;
  border-radius: 8px;
  cursor: pointer;
  text-decoration: none;
}

.ghost-button {
  padding: 10px 14px;
  background: var(--surface-strong);
  color: var(--text-color);
}

.ghost-button--soft {
  background: rgba(18, 32, 63, 0.08);
}

.primary-button {
  padding: 10px 16px;
  background: var(--primary-color);
  color: #fff;
  font-weight: 700;
}

button:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

@media (max-width: 1100px) {
  .prompt-grid {
    grid-template-columns: 1fr;
  }

  .prompt-page__header {
    flex-direction: column;
  }

  .plan-grid {
    grid-template-columns: 1fr 1fr;
  }
}
</style>
