<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { createRecipient, listAccounts, updateRecipient } from '@/api';
import type { ChannelAccount, ChannelType, Recipient } from '@/types';
import ModalDialog from './ModalDialog.vue';

const props = defineProps<{
  open: boolean;
  /** 传了就进入编辑模式，为空表示新增 */
  recipient: Recipient | null;
}>();

const emit = defineEmits<{
  close: [];
  /** 保存成功，父组件负责关闭弹窗并刷新列表与发送区的下拉 */
  saved: [];
}>();

const form = reactive({
  name: '',
  channel: 'INBOX' as ChannelType,
  address: '',
  accountId: null as number | null,
  remark: '',
});

const saving = ref(false);
const error = ref('');
const accounts = ref<ChannelAccount[]>([]);

const isEditing = computed(() => props.recipient !== null);
const title = computed(() =>
  props.recipient ? `修改发送对象 #${props.recipient.id}` : '新增发送对象',
);

const channels: { value: ChannelType; label: string; placeholder: string }[] = [
  { value: 'INBOX', label: '站内信', placeholder: '用户 ID，如 1001' },
  { value: 'SMS', label: '短信', placeholder: '手机号，如 13800000000' },
  { value: 'WECHAT_WORK', label: '企业微信', placeholder: '群机器人 Webhook 地址' },
  { value: 'WECHAT_MP', label: '公众号', placeholder: '测试号「用户列表」里的 openid' },
];

const addressHint = computed(
  () => channels.find((c) => c.value === form.channel)?.placeholder ?? '',
);

/** 只有微信类通道才有「账号」这个概念；站内信、短信不需要凭据 */
const needsAccount = computed(
  () => form.channel === 'WECHAT_MP' || form.channel === 'WECHAT_WORK',
);

/** 下拉只给同通道的账号，后端也会再校验一次，不能只靠前端 */
const availableAccounts = computed(() =>
  accounts.value.filter((a) => a.channel === form.channel && a.enabled !== false),
);

/** 每次打开都拉一次账号列表，这样账号页刚新增的账号立刻可选 */
async function loadAccounts() {
  try {
    accounts.value = await listAccounts();
  } catch (e) {
    error.value = '加载通道账号失败：' + (e as Error).message;
  }
}

/** 每次打开都重置表单：新增清空，编辑预填 */
watch(
  () => props.open,
  (open) => {
    if (!open) return;
    error.value = '';
    const r = props.recipient;
    if (r) {
      form.name = r.name;
      form.channel = r.channel;
      form.address = r.address;
      form.accountId = r.accountId ?? null;
      form.remark = r.remark ?? '';
    } else {
      form.name = '';
      form.channel = 'INBOX';
      form.address = '';
      form.accountId = null;
      form.remark = '';
    }
    loadAccounts();
  },
  { immediate: true },
);

/** 换通道必须清掉账号，否则会把公众号账号带到企业微信对象上。
 *  用 @change 而不是 watch(form.channel)：编辑预填时也会改 channel，
 *  用 watch 会把刚填进去的 accountId 又清掉。 */
function onChannelChange() {
  form.accountId = null;
}

async function submit() {
  error.value = '';

  if (!form.name.trim()) {
    error.value = '请填写对象名称';
    return;
  }
  if (!form.address.trim()) {
    error.value = '请填写地址';
    return;
  }

  const payload = {
    name: form.name.trim(),
    channel: form.channel,
    address: form.address.trim(),
    accountId: needsAccount.value ? form.accountId : null,
    remark: form.remark.trim() || undefined,
  };

  saving.value = true;
  try {
    if (props.recipient) {
      await updateRecipient(props.recipient.id, payload);
    } else {
      await createRecipient(payload);
    }
    emit('saved');
  } catch (e) {
    // 名称重复这类业务错误会在弹窗内提示，不关闭弹窗，方便直接改
    error.value = '保存失败：' + (e as Error).message;
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <ModalDialog :open="open" :title="title" @close="emit('close')">
    <div class="modal-grid">
      <div class="field">
        <label>对象名称</label>
        <input v-model="form.name" placeholder="如 张三 / 运维告警群" />
      </div>
      <div class="field">
        <label>通道</label>
        <select v-model="form.channel" @change="onChannelChange">
          <option v-for="c in channels" :key="c.value" :value="c.value">{{ c.label }}</option>
        </select>
      </div>
      <div class="field span-2">
        <label>地址</label>
        <input v-model="form.address" :placeholder="addressHint" />
      </div>

      <div class="field span-2" v-if="needsAccount">
        <label>通道账号</label>
        <select v-model="form.accountId">
          <option :value="null">不绑定（使用全局配置）</option>
          <option v-for="a in availableAccounts" :key="a.id" :value="a.id">
            {{ a.name }} · {{ a.appId || a.webhookMasked || a.id }}
          </option>
        </select>
        <span class="hint" v-if="!availableAccounts.length">
          该通道还没有启用的账号，可到左侧「通道账号」新增，或留空走全局配置
        </span>
      </div>

      <div class="field span-2">
        <label>备注（可选）</label>
        <input v-model="form.remark" placeholder="便于区分同名对象" />
      </div>
    </div>

    <div class="hint error" v-if="error">{{ error }}</div>
    <div class="hint">
      同一个名称在同一通道下只能有一条；同一个人要收短信和站内信，建两条即可
    </div>

    <template #footer>
      <button class="ghost" :disabled="saving" @click="emit('close')">取消</button>
      <button :disabled="saving" @click="submit">
        {{ saving ? '保存中…' : isEditing ? '保存修改' : '保存' }}
      </button>
    </template>
  </ModalDialog>
</template>
