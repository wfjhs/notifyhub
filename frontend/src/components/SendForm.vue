<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { listAccounts, listRecipients, sendMessage } from '@/api';
import type { ChannelAccount, ChannelType, Recipient, SendResult } from '@/types';

const props = defineProps<{
  /** 对象列表变化时由父组件递增，用来触发重新加载 */
  refreshKey?: number;
}>();

const emit = defineEmits<{
  sent: [bizId: string];
  /** 请求父组件打开「新增对象」弹窗 */
  'create-recipient': [];
}>();

const recipients = ref<Recipient[]>([]);
const accounts = ref<ChannelAccount[]>([]);
const loadingRecipients = ref(false);
const selectedId = ref<number | null>(null);

const form = reactive({
  bizId: '',
  bizType: 'ALERT',
  content: '【测试】这是一条来自 NotifyHub 控制台的消息',
});

const sending = ref(false);
const result = ref('');
const failed = ref(false);
const listError = ref('');

const selected = computed(
  () => recipients.value.find((r) => r.id === selectedId.value) ?? null,
);

/** 发送前让读者看清「用哪个账号发」，多公众号时这点很关键 */
function accountLabel(r: Recipient): string {
  if (r.accountId == null) return '全局配置';
  return accounts.value.find((a) => a.id === r.accountId)?.name ?? `#${r.accountId}`;
}

const channelLabels: Record<ChannelType, string> = {
  INBOX: '站内信',
  SMS: '短信',
  WECHAT_WORK: '企业微信',
  WECHAT_MP: '公众号',
};

async function loadRecipients() {
  loadingRecipients.value = true;
  listError.value = '';
  try {
    recipients.value = await listRecipients();
    // 选中的对象被删掉后不能让选择框停留在无效值上
    if (selectedId.value != null && !recipients.value.some((r) => r.id === selectedId.value)) {
      selectedId.value = null;
    }
    // 只有一个对象时默认选中，少点一次
    if (selectedId.value == null && recipients.value.length > 0) {
      selectedId.value = recipients.value[0].id;
    }
    try {
      accounts.value = await listAccounts();
    } catch {
      accounts.value = [];
    }
  } catch (e) {
    listError.value = '加载发送对象失败：' + (e as Error).message;
  } finally {
    loadingRecipients.value = false;
  }
}

onMounted(loadRecipients);
watch(() => props.refreshKey, loadRecipients);

async function submit() {
  if (selectedId.value == null) {
    failed.value = true;
    result.value = '请先选择发送对象（点「+ 新增对象」创建）';
    return;
  }

  sending.value = true;
  failed.value = false;

  const bizId = form.bizId.trim() || `MANUAL-${Date.now()}`;
  try {
    // 只传 recipientId：通道与地址由服务端解析，前端不接触地址
    const data: SendResult = await sendMessage({
      bizId,
      bizType: form.bizType.trim() || 'ALERT',
      recipientId: selectedId.value,
      content: form.content,
    });
    result.value = JSON.stringify(data, null, 2);
    form.bizId = bizId;
    emit('sent', bizId);
  } catch (e) {
    failed.value = true;
    result.value = '发送失败：' + (e as Error).message;
  } finally {
    sending.value = false;
  }
}
</script>

<template>
  <section class="card">
    <h2>发送消息</h2>

    <div class="row" style="margin-bottom: 10px">
      <div class="field">
        <label>发送对象</label>
        <select v-model="selectedId" :disabled="loadingRecipients || !recipients.length">
          <option v-if="!recipients.length" :value="null">暂无对象</option>
          <option v-for="r in recipients" :key="r.id" :value="r.id">
            {{ r.name }}（{{ channelLabels[r.channel] ?? r.channel }} · {{ r.address }}<template v-if="r.channel === 'WECHAT_MP' || r.channel === 'WECHAT_WORK'"> · {{ accountLabel(r) }}</template>）
          </option>
        </select>
      </div>
      <div class="field">
        <label>业务唯一键 bizId</label>
        <input v-model="form.bizId" placeholder="留空自动生成" size="20" />
      </div>
      <div class="field">
        <label>业务类型</label>
        <input v-model="form.bizType" size="12" />
      </div>
    </div>

    <div class="field">
      <label>内容</label>
      <textarea v-model="form.content"></textarea>
    </div>

    <div class="row" style="margin-top: 10px">
      <button :disabled="sending || selectedId == null" @click="submit">
        {{ sending ? '发送中…' : '发送' }}
      </button>
      <button class="ghost" @click="emit('create-recipient')">+ 新增对象</button>
      <span class="hint" v-if="selected">
        将发往「{{ selected.name }}」的{{ channelLabels[selected.channel] ?? selected.channel }}<template v-if="selected.accountId != null">，使用账号「{{ accountLabel(selected) }}」</template>
      </span>
      <span class="hint error" v-else-if="listError">{{ listError }}</span>
      <span class="hint error" v-else>
        还没有发送对象，点「+ 新增对象」创建
      </span>
    </div>

    <pre v-if="result" :class="{ error: failed }">{{ result }}</pre>
  </section>
</template>
