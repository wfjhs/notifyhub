<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { deleteRecipient, listAccounts, listRecipients } from '@/api';
import type { ChannelAccount, Recipient } from '@/types';

const emit = defineEmits<{
  /** 请求父组件打开编辑弹窗 */
  edit: [recipient: Recipient];
  /** 新增/修改/删除后通知父组件刷新发送区的下拉 */
  changed: [];
}>();

const items = ref<Recipient[]>([]);
const accounts = ref<ChannelAccount[]>([]);
const keyword = ref('');
const loading = ref(false);
const error = ref('');
const notice = ref('');

async function load() {
  loading.value = true;
  error.value = '';
  try {
    items.value = await listRecipients(keyword.value);
  } catch (e) {
    error.value = '加载失败：' + (e as Error).message;
  } finally {
    loading.value = false;
  }
  // 账号名只是展示用，拉不到不该让整个对象列表消失
  try {
    accounts.value = await listAccounts();
  } catch {
    accounts.value = [];
  }
}

function accountName(id?: number | null): string {
  if (id == null) return '全局配置';
  return accounts.value.find((a) => a.id === id)?.name ?? `#${id}`;
}

function resetKeyword() {
  keyword.value = '';
  load();
}

async function remove(r: Recipient) {
  error.value = '';
  notice.value = '';
  try {
    await deleteRecipient(r.id);
    notice.value = `已删除：${r.name}`;
    await load();
    emit('changed');
  } catch (e) {
    error.value = '删除失败：' + (e as Error).message;
  }
}

/** 弹窗保存成功后由父组件调用 */
function reload(noticeText?: string) {
  if (noticeText) {
    notice.value = noticeText;
  }
  return load();
}

defineExpose({ reload });
onMounted(load);
</script>

<template>
  <section class="card">
    <h2>管理对象</h2>

    <div class="row">
      <input
        v-model="keyword"
        placeholder="按名称或地址搜索"
        size="28"
        class="mono"
        @keyup.enter="load()"
      />
      <button class="ghost" :disabled="loading" @click="load()">查询</button>
      <button class="ghost" :disabled="loading" @click="resetKeyword">重置</button>
      <span class="hint">共 {{ items.length }} 个对象</span>
    </div>

    <div class="hint" v-if="notice">{{ notice }}</div>
    <div class="hint error" v-if="error">{{ error }}</div>

    <div class="empty" v-if="loading">加载中…</div>
    <div class="empty" v-else-if="!items.length">
      {{ keyword ? '没有匹配的对象' : '还没有发送对象，点右上角「新增对象」创建' }}
    </div>
    <table v-else>
      <thead>
        <tr>
          <th>ID</th>
          <th>名称</th>
          <th>通道</th>
          <th>地址</th>
          <th>通道账号</th>
          <th>备注</th>
          <th>创建时间</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="r in items" :key="r.id">
          <td class="num">{{ r.id }}</td>
          <td>{{ r.name }}</td>
          <td>{{ r.channel }}</td>
          <td class="mono">{{ r.address }}</td>
          <td>{{ accountName(r.accountId) }}</td>
          <td class="mono">{{ r.remark || '-' }}</td>
          <td class="num">{{ r.createTime || '-' }}</td>
          <td>
            <button class="tiny ghost" @click="emit('edit', r)">编辑</button>
            <button class="tiny ghost" @click="remove(r)">删除</button>
          </td>
        </tr>
      </tbody>
    </table>

    <div class="hint">
      同一个人在不同通道地址不同（站内信用用户 ID，短信用手机号），所以按「名称 + 通道」唯一
    </div>
  </section>
</template>
