<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { deleteAccount, listAccounts } from '@/api';
import type { ChannelAccount } from '@/types';
import AccountFormModal from './AccountFormModal.vue';

const emit = defineEmits<{
  /** 账号增删改后通知父组件刷新对象弹窗里的下拉 */
  changed: [];
}>();

const items = ref<ChannelAccount[]>([]);
const loading = ref(false);
const error = ref('');
const notice = ref('');

const modalOpen = ref(false);
const editing = ref<ChannelAccount | null>(null);

async function load() {
  loading.value = true;
  error.value = '';
  try {
    items.value = await listAccounts();
  } catch (e) {
    error.value = '加载失败：' + (e as Error).message;
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  editing.value = null;
  modalOpen.value = true;
}

function openEdit(a: ChannelAccount) {
  editing.value = a;
  modalOpen.value = true;
}

async function onSaved() {
  modalOpen.value = false;
  notice.value = editing.value ? '已保存修改' : '已新增账号';
  await load();
  emit('changed');
}

async function remove(a: ChannelAccount) {
  error.value = '';
  try {
    await deleteAccount(a.id);
    notice.value = `已删除：${a.name}`;
    await load();
    emit('changed');
  } catch (e) {
    error.value = '删除失败：' + (e as Error).message;
  }
}

onMounted(load);
</script>

<template>
  <section class="card">
    <h2>通道账号</h2>

    <div class="row">
      <button @click="openCreate">+ 新增账号</button>
      <button class="ghost" :disabled="loading" @click="load">刷新</button>
      <span class="hint">
        一套凭据一个账号。对象绑定账号后，消息会按这个账号投递；不绑定则使用全局配置
      </span>
    </div>

    <div class="hint" v-if="notice">{{ notice }}</div>
    <div class="hint error" v-if="error">{{ error }}</div>

    <div class="empty" v-if="loading">加载中…</div>
    <div class="empty" v-else-if="!items.length">
      还没有账号。点「新增账号」录入第一套公众号或企业微信凭据。
    </div>
    <table v-else>
      <thead>
        <tr>
          <th>ID</th>
          <th>名称</th>
          <th>通道</th>
          <th>AppID</th>
          <th>模板 / Webhook</th>
          <th>密钥</th>
          <th>状态</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="a in items" :key="a.id">
          <td class="num">{{ a.id }}</td>
          <td>{{ a.name }}</td>
          <td>{{ a.channel }}</td>
          <td class="mono">{{ a.appId || '-' }}</td>
          <td class="mono">{{ a.templateId || a.webhookMasked || '-' }}</td>
          <td class="mono">{{ a.secretConfigured ? '已配置' : '-' }}</td>
          <td>
            <span class="status" :class="a.enabled ? 's-SUCCESS' : 's-DEAD'">
              {{ a.enabled ? '启用' : '停用' }}
            </span>
          </td>
          <td>
            <button class="tiny ghost" @click="openEdit(a)">编辑</button>
            <button class="tiny ghost" @click="remove(a)">删除</button>
          </td>
        </tr>
      </tbody>
    </table>

    <AccountFormModal
      :open="modalOpen"
      :account="editing"
      @close="modalOpen = false"
      @saved="onSaved"
    />
  </section>
</template>
