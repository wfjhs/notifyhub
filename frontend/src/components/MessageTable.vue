<script setup lang="ts">
import type { MessageRow } from '@/types';

defineProps<{
  rows: MessageRow[];
  /** 死信表格多一列「重放」 */
  withReplay?: boolean;
}>();

const emit = defineEmits<{ replay: [id: number] }>();

function truncate(text: string | null | undefined, max = 40): string {
  if (!text) return '';
  return text.length > max ? text.slice(0, max) + '…' : text;
}

function retryText(row: MessageRow): string {
  return row.maxRetry == null ? String(row.retryCount) : `${row.retryCount}/${row.maxRetry}`;
}
</script>

<template>
  <table>
    <thead>
      <tr>
        <th>ID</th>
        <th>状态</th>
        <th>通道</th>
        <th>接收方</th>
        <th>重试</th>
        <th>内容</th>
        <th>失败原因</th>
        <th>时间</th>
        <th v-if="withReplay"></th>
      </tr>
    </thead>
    <tbody>
      <tr v-for="row in rows" :key="row.id">
        <td class="num">{{ row.id }}</td>
        <td>
          <span class="status" :class="`s-${row.status}`">{{ row.status }}</span>
        </td>
        <td>{{ row.channel }}</td>
        <td class="mono">{{ row.receiver }}</td>
        <td class="num">{{ retryText(row) }}</td>
        <td class="mono">{{ truncate(row.content) }}</td>
        <td class="mono">{{ truncate(row.failReason, 30) || '-' }}</td>
        <td class="num">{{ row.time || '-' }}</td>
        <td v-if="withReplay">
          <button class="tiny ghost" @click="emit('replay', row.id)">重放</button>
        </td>
      </tr>
    </tbody>
  </table>
</template>
