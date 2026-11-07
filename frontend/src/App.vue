<script setup lang="ts">
import { computed, ref } from 'vue';
import SendForm from '@/components/SendForm.vue';
import RecipientManager from '@/components/RecipientManager.vue';
import RecipientFormModal from '@/components/RecipientFormModal.vue';
import AccountManager from '@/components/AccountManager.vue';
import BizQuery from '@/components/BizQuery.vue';
import DeadLetter from '@/components/DeadLetter.vue';
import type { Recipient } from '@/types';

type ViewKey = 'send' | 'recipients' | 'accounts' | 'query' | 'dead';

const views: { key: ViewKey; label: string; desc: string }[] = [
  { key: 'send', label: '发送消息', desc: '选一个发送对象，填内容发出去' },
  { key: 'recipients', label: '管理对象', desc: '维护接收方地址簿，发送时的下拉就来自这里' },
  { key: 'accounts', label: '通道账号', desc: '管理多套公众号 / 企业微信凭据，对象可绑定其中一套' },
  { key: 'query', label: '消息查询', desc: '按业务键一次看清所有通道的投递状态' },
  { key: 'dead', label: '死信', desc: '重试耗尽的消息，通道恢复后可重放' },
];

const active = ref<ViewKey>('send');
const activeView = computed(() => views.find((v) => v.key === active.value) ?? views[0]);

const bizQueryRef = ref<InstanceType<typeof BizQuery> | null>(null);
const recipientManagerRef = ref<InstanceType<typeof RecipientManager> | null>(null);

/** 对象变更时递增，发送区的下拉据此重新加载 */
const recipientsVersion = ref(0);

const modalOpen = ref(false);
const editingRecipient = ref<Recipient | null>(null);

function openCreateRecipient() {
  editingRecipient.value = null;
  modalOpen.value = true;
}

function openEditRecipient(recipient: Recipient) {
  editingRecipient.value = recipient;
  modalOpen.value = true;
}

function closeModal() {
  modalOpen.value = false;
}

function onRecipientSaved() {
  const text = editingRecipient.value ? '已保存修改' : '已新增对象';
  modalOpen.value = false;
  recipientsVersion.value += 1;
  // 管理列表可能没挂载在当前视图，用可选链兜住
  recipientManagerRef.value?.reload(text);
}

function onRecipientsChanged() {
  recipientsVersion.value += 1;
}

/** 账号增删改后：对象表格里的账号名、发送区下拉都要刷新 */
function onAccountsChanged() {
  recipientsVersion.value += 1;
  recipientManagerRef.value?.reload();
}

function onSent(bizId: string) {
  bizQueryRef.value?.query(bizId);
}

function onReplayed() {
  // 重放会把消息重置为 PENDING，刷新当前查询结果能看到状态变化
  bizQueryRef.value?.query();
}
</script>

<template>
  <div class="layout">
    <aside class="sidebar">
      <div class="brand">
        NotifyHub
        <small>通知中台 · 投递控制台</small>
      </div>
      <button
        v-for="v in views"
        :key="v.key"
        class="nav-item"
        :class="{ active: active === v.key }"
        @click="active = v.key"
      >
        {{ v.label }}
      </button>
    </aside>

    <div class="content">
      <header>
        <h1>{{ activeView.label }}</h1>
        <span>{{ activeView.desc }}</span>
      </header>

      <main>
        <SendForm
          v-show="active === 'send'"
          :refresh-key="recipientsVersion"
          @sent="onSent"
          @create-recipient="openCreateRecipient"
        />

        <RecipientManager
          v-show="active === 'recipients'"
          ref="recipientManagerRef"
          @edit="openEditRecipient"
          @changed="onRecipientsChanged"
        />

        <AccountManager v-show="active === 'accounts'" @changed="onAccountsChanged" />

        <BizQuery v-show="active === 'query'" ref="bizQueryRef" />

        <DeadLetter v-show="active === 'dead'" @replayed="onReplayed" />
      </main>
    </div>

    <RecipientFormModal
      :open="modalOpen"
      :recipient="editingRecipient"
      @close="closeModal"
      @saved="onRecipientSaved"
    />
  </div>
</template>
