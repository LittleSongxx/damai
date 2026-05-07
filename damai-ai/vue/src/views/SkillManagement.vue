<template>
  <div class="skill-page">
    <header class="skill-page__header">
      <div>
        <p class="eyebrow">Skill Platform</p>
        <h1>Skill 管理</h1>
      </div>
      <RouterLink class="back-link" to="/assistant">返回助手</RouterLink>
    </header>

    <div v-if="errorMessage" class="notice notice--error">{{ errorMessage }}</div>
    <div v-if="successMessage" class="notice">{{ successMessage }}</div>

    <section class="skill-grid">
      <div class="skill-table">
        <button
          v-for="skill in skills"
          :key="skill.skillId"
          class="skill-row"
          :class="{ active: selectedSkill?.skillId === skill.skillId, disabled: !skill.enabled }"
          @click="selectSkill(skill)"
        >
          <span>
            <strong>{{ skill.name }}</strong>
            <small>{{ skill.skillId }}</small>
          </span>
          <span class="route-pill">{{ skill.routeType }}</span>
          <span class="risk-pill">{{ skill.riskLevel }}</span>
          <span>{{ skill.enabled ? '启用' : '停用' }}</span>
        </button>
      </div>

      <form v-if="selectedSkill" class="skill-editor" @submit.prevent="saveSkill">
        <div class="editor-title">
          <div>
            <p class="eyebrow">{{ selectedSkill.skillId }}</p>
            <h2>{{ selectedSkill.name }}</h2>
          </div>
          <label class="switch">
            <input v-model="form.enabled" type="checkbox" />
            <span>启用</span>
          </label>
        </div>

        <label>
          名称
          <input v-model="form.name" />
        </label>

        <label>
          描述
          <textarea v-model="form.description" rows="4" />
        </label>

        <label>
          目标
          <textarea v-model="form.goal" rows="2" />
        </label>

        <label>
          执行指令
          <textarea v-model="form.instructions" rows="4" />
        </label>

        <div class="field-pair">
          <label>
            风险等级
            <select v-model="form.riskLevel">
              <option value="LOW">LOW</option>
              <option value="MEDIUM">MEDIUM</option>
              <option value="HIGH">HIGH</option>
              <option value="CRITICAL">CRITICAL</option>
            </select>
          </label>
          <label>
            版本
            <input v-model="form.version" />
          </label>
        </div>

        <label>
          触发关键词（逗号分隔）
          <input v-model="keywordText" />
        </label>

        <label>
          工具白名单（逗号分隔，支持 nl2sql.*）
          <input v-model="toolAllowlistText" />
        </label>

        <label>
          示例（每行一个）
          <textarea v-model="examplesText" rows="3" />
        </label>

        <label>
          评测用例（每行一个）
          <textarea v-model="evalCasesText" rows="3" />
        </label>

        <div class="toggle-row">
          <label><input v-model="form.requiresAdmin" type="checkbox" /> 管理员</label>
          <label><input v-model="form.requiresApproval" type="checkbox" /> 审批</label>
          <label><input v-model="form.frontendSelectable" type="checkbox" /> 前端直选</label>
          <label><input v-model="form.modelSelectable" type="checkbox" /> 自动选择</label>
        </div>

        <div class="editor-actions">
          <button class="primary-button" type="submit">保存</button>
          <button class="ghost-button" type="button" @click="runEval">创建评测运行</button>
        </div>

        <section v-if="resources.length" class="resource-panel">
          <p class="eyebrow">Resources</p>
          <ul>
            <li v-for="resource in resources" :key="resource.resourceId">
              <strong>{{ resource.title || resource.resourceId }}</strong>
              <span>{{ resource.resourceType }}</span>
            </li>
          </ul>
        </section>
      </form>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { assistantAPI, ensureAuthenticated } from '../api/api'

const skills = ref([])
const selectedSkill = ref(null)
const resources = ref([])
const keywordText = ref('')
const toolAllowlistText = ref('')
const examplesText = ref('')
const evalCasesText = ref('')
const errorMessage = ref('')
const successMessage = ref('')

const form = reactive({
  name: '',
  description: '',
  goal: '',
  instructions: '',
  version: '',
  riskLevel: 'LOW',
  enabled: true,
  requiresAdmin: false,
  requiresApproval: false,
  frontendSelectable: true,
  modelSelectable: true
})

const triggerKeywords = computed(() => keywordText.value
  .split(/[,，]/)
  .map(item => item.trim())
  .filter(Boolean))

const toolAllowlist = computed(() => toolAllowlistText.value
  .split(/[,，]/)
  .map(item => item.trim())
  .filter(Boolean))

const examples = computed(() => examplesText.value
  .split(/\n+/)
  .map(item => item.trim())
  .filter(Boolean))

const evalCases = computed(() => evalCasesText.value
  .split(/\n+/)
  .map(item => item.trim())
  .filter(Boolean))

const loadSkills = async () => {
  const result = await assistantAPI.listSkills()
  skills.value = Array.isArray(result?.data) ? result.data : []
  if (!selectedSkill.value && skills.value.length) {
    await selectSkill(skills.value[0])
  }
}

const selectSkill = async (skill) => {
  selectedSkill.value = skill
  Object.assign(form, {
    name: skill.name || '',
    description: skill.description || '',
    goal: skill.goal || '',
    instructions: skill.instructions || '',
    version: skill.version || '1.0.0',
    riskLevel: skill.riskLevel || 'LOW',
    enabled: skill.enabled !== false,
    requiresAdmin: skill.requiresAdmin === true,
    requiresApproval: skill.requiresApproval === true,
    frontendSelectable: skill.frontendSelectable !== false,
    modelSelectable: skill.modelSelectable !== false
  })
  keywordText.value = Array.isArray(skill.triggerKeywords) ? skill.triggerKeywords.join(', ') : ''
  toolAllowlistText.value = Array.isArray(skill.toolAllowlist) ? skill.toolAllowlist.join(', ') : ''
  examplesText.value = Array.isArray(skill.examples) ? skill.examples.join('\n') : ''
  evalCasesText.value = Array.isArray(skill.evalCases) ? skill.evalCases.join('\n') : ''
  const detail = await assistantAPI.getSkill(skill.skillId)
  resources.value = Array.isArray(detail?.data?.resources) ? detail.data.resources : []
}

const saveSkill = async () => {
  errorMessage.value = ''
  successMessage.value = ''
  try {
    await assistantAPI.updateSkill(selectedSkill.value.skillId, {
      ...form,
      triggerKeywords: triggerKeywords.value,
      toolAllowlist: toolAllowlist.value,
      examples: examples.value,
      evalCases: evalCases.value
    })
    successMessage.value = 'Skill 已保存'
    selectedSkill.value = null
    await loadSkills()
  } catch (error) {
    errorMessage.value = error.message || '保存失败'
  }
}

const runEval = async () => {
  errorMessage.value = ''
  successMessage.value = ''
  try {
    const result = await assistantAPI.createSkillEvalRun(selectedSkill.value.skillId)
    successMessage.value = `评测运行已创建：${result?.data?.evalRunId || ''}`
  } catch (error) {
    errorMessage.value = error.message || '创建评测运行失败'
  }
}

onMounted(async () => {
  if (!ensureAuthenticated()) {
    return
  }
  await loadSkills()
})
</script>

<style scoped lang="scss">
.skill-page {
  display: grid;
  gap: 18px;
}

.skill-page__header,
.skill-grid,
.editor-title,
.field-pair,
.toggle-row,
.editor-actions {
  display: flex;
}

.skill-page__header {
  align-items: center;
  justify-content: space-between;
}

.back-link,
.ghost-button,
.primary-button {
  border: none;
  border-radius: 8px;
  cursor: pointer;
  text-decoration: none;
}

.back-link,
.ghost-button {
  padding: 10px 14px;
  background: var(--surface-strong);
  color: var(--text-color);
}

.primary-button {
  padding: 10px 16px;
  background: var(--primary-color);
  color: #fff;
  font-weight: 700;
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

.skill-grid {
  align-items: flex-start;
  gap: 18px;
}

.skill-table,
.skill-editor {
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: var(--surface-strong);
}

.skill-table {
  width: 46%;
  min-width: 420px;
  overflow: hidden;
}

.skill-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 82px 86px 56px;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-height: 68px;
  padding: 12px 14px;
  border: 0;
  border-bottom: 1px solid var(--border-color);
  background: transparent;
  color: var(--text-color);
  text-align: left;
  cursor: pointer;
}

.skill-row.active {
  background: rgba(255, 90, 54, 0.08);
}

.skill-row.disabled {
  color: var(--text-soft);
}

.skill-row small {
  display: block;
  margin-top: 4px;
  color: var(--text-soft);
}

.route-pill,
.risk-pill {
  padding: 4px 8px;
  border-radius: 8px;
  background: var(--surface-color);
  text-align: center;
  font-size: 0.78rem;
}

.skill-editor {
  flex: 1;
  display: grid;
  gap: 14px;
  padding: 18px;
}

.editor-title,
.field-pair,
.toggle-row,
.editor-actions {
  gap: 12px;
}

.editor-title {
  align-items: center;
  justify-content: space-between;
}

.field-pair > label {
  flex: 1;
}

.skill-editor label {
  display: grid;
  gap: 8px;
  color: var(--text-soft);
}

.skill-editor input,
.skill-editor textarea,
.skill-editor select {
  width: 100%;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 10px 12px;
  background: var(--surface-color);
  color: var(--text-color);
}

.toggle-row {
  flex-wrap: wrap;
}

.toggle-row label,
.switch {
  display: inline-flex;
  grid-template-columns: none;
  align-items: center;
  gap: 8px;
}

.resource-panel {
  border-top: 1px solid var(--border-color);
  padding-top: 14px;
}

.resource-panel ul {
  display: grid;
  gap: 8px;
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
}

.resource-panel li {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 10px;
  border-radius: 8px;
  background: var(--surface-color);
}

@media (max-width: 980px) {
  .skill-grid {
    flex-direction: column;
  }

  .skill-table {
    width: 100%;
    min-width: 0;
  }
}
</style>
