<script setup lang="ts">
import { ref } from 'vue';
import { listByBizId } from '@/api';
import MessageTable from './MessageTable.vue';
import type { Message, MessageRow } from '@/types';

const bizId = ref('');
const rows = ref<MessageRow[]>([]);
const loading = ref(false);
const error = ref('');
const searched = ref(false);

function toRow(m: Message): MessageRow {
  return {
    id: m.id,
    status: m.status,
    channel: m.channel,
    receiver: m.receiver,
    retryCount: m.retryCount,
    maxRetry: m.maxRetry,
    content: m.content,
    failReason: m.failReason,
    time: m.updateTime || m.createTime,
  };
}

/**
 * 查询指定业务键下的全部通道消息。
 * 暴露给父组件：发送成功后自动按新 bizId 查一次。
 */
async function query(id?: string) {
  if (id !== undefined) {
    bizId.value = id;
  }
  const key = bizId.value.trim();
  if (!key) {
    error.value = '请先输入 bizId';
    searched.value = false;
    return;
  }

  loading.value = true;
  error.value = '';
  searched.value = true;
  try {
    const list = await listByBizId(key);
    rows.value = list.map(toRow);
  } catch (e) {
    rows.value = [];
    error.value = '查询失败：' + (e as Error).message;
  } finally {
    loading.value = false;
  }
}

defineExpose({ query });
</script>

<template>
  <section class="card">
    <h2>按业务键查询</h2>

    <div class="row">
      <input
        v-model="bizId"
        placeholder="输入 bizId"
        size="34"
        class="mono"
        @keyup.enter="query()"
      />
      <button class="ghost" :disabled="loading" @click="query()">查询</button>
      <span class="hint">一次看清该业务下所有通道的投递状态</span>
    </div>

    <div class="empty" v-if="!searched && !error">暂无查询结果</div>
    <div class="empty error" v-else-if="error">{{ error }}</div>
    <div class="empty" v-else-if="loading">查询中…</div>
    <div class="empty" v-else-if="!rows.length">没有找到该业务键下的消息</div>
    <MessageTable v-else :rows="rows" />
  </section>
</template>
