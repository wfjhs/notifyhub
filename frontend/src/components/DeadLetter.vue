<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { listDead, replayDead } from '@/api';
import MessageTable from './MessageTable.vue';
import type { DeadLetter, MessageRow } from '@/types';

const emit = defineEmits<{ replayed: [] }>();

const rows = ref<MessageRow[]>([]);
const loading = ref(false);
const error = ref('');
const loaded = ref(false);
const replayingId = ref<number | null>(null);

function toRow(d: DeadLetter): MessageRow {
  return {
    // 死信表的主键，重放时用它定位
    id: d.id,
    // 死信必然是 DEAD，实体里没有 status 字段
    status: 'DEAD',
    channel: d.channel,
    receiver: d.receiver,
    retryCount: d.retryCount,
    content: d.content,
    failReason: d.failReason,
    time: d.createTime,
  };
}

async function load() {
  loading.value = true;
  error.value = '';
  try {
    const list = await listDead(50);
    rows.value = list.map(toRow);
  } catch (e) {
    rows.value = [];
    error.value = '加载失败：' + (e as Error).message;
  } finally {
    loading.value = false;
    loaded.value = true;
  }
}

async function replay(deadId: number) {
  replayingId.value = deadId;
  try {
    await replayDead(deadId);
    await load();
    emit('replayed');
  } catch (e) {
    error.value = '重放失败：' + (e as Error).message;
  } finally {
    replayingId.value = null;
  }
}

onMounted(load);
</script>

<template>
  <section class="card">
    <h2>死信</h2>

    <div class="row">
      <button class="ghost" :disabled="loading" @click="load">刷新列表</button>
      <span class="hint">重试耗尽的消息。通道恢复后可点「重放」重新投递</span>
    </div>

    <div class="empty error" v-if="error">{{ error }}</div>
    <div class="empty" v-else-if="loading">加载中…</div>
    <div class="empty" v-else-if="!rows.length">
      {{ loaded ? '当前没有死信' : '点「刷新列表」加载' }}
    </div>
    <MessageTable v-else :rows="rows" with-replay @replay="replay" />

    <div class="hint" v-if="replayingId !== null">正在重放 #{{ replayingId }}…</div>
  </section>
</template>
