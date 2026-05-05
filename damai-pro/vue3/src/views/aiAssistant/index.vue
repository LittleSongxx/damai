<template>
  <Header />
  <div class="ai-assistant-page">
    <div class="ai-assistant-panel">
      <div class="ai-assistant-toolbar">
        <div>
          <div class="ai-assistant-title">AI助手</div>
          <div class="ai-assistant-subtitle">在大麦系统内直接使用智能购票、规则和订单助手</div>
        </div>
        <a :href="aiAssistantUrl" target="_blank" rel="noopener noreferrer" class="open-link">新窗口打开</a>
      </div>
      <iframe
        :src="aiAssistantUrl"
        class="ai-assistant-frame"
        allow="clipboard-read; clipboard-write"
      ></iframe>
    </div>
  </div>
</template>

<script setup>
import Header from '@/components/header/index'

const aiAssistantUrl = import.meta.env.VITE_DAMAI_AI_WEB_URL || 'http://127.0.0.1:15174/assistant'
</script>

<style scoped lang="scss">
.ai-assistant-page {
  width: 100%;
  min-height: calc(100vh - 72px);
  background: #f7f8fa;
  padding: 18px 0 28px;
}

.ai-assistant-panel {
  width: 1200px;
  height: calc(100vh - 118px);
  min-height: 680px;
  margin: 0 auto;
  background: #fff;
  border: 1px solid #ebedf0;
  border-radius: 14px;
  box-shadow: 0 8px 28px rgba(31, 35, 41, 0.08);
  overflow: hidden;
}

.ai-assistant-toolbar {
  height: 72px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 22px;
  border-bottom: 1px solid #eef0f4;
  background: linear-gradient(90deg, #fff7f5 0%, #ffffff 52%, #f6f9ff 100%);
}

.ai-assistant-title {
  font-size: 20px;
  font-weight: 600;
  color: #1f2329;
}

.ai-assistant-subtitle {
  margin-top: 6px;
  font-size: 13px;
  color: #646a73;
}

.open-link {
  height: 36px;
  line-height: 36px;
  padding: 0 16px;
  border-radius: 18px;
  color: #fff;
  background: rgba(255, 55, 29, 0.85);
  font-size: 14px;
}

.open-link:hover {
  color: #fff;
  background: rgba(255, 55, 29, 1);
}

.ai-assistant-frame {
  width: 100%;
  height: calc(100% - 72px);
  border: 0;
  display: block;
}
</style>
