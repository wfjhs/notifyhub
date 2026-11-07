<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { createAccount, updateAccount } from '@/api';
import type { ChannelAccount, ChannelType, SaveChannelAccountPayload } from '@/types';
import ModalDialog from './ModalDialog.vue';

const props = defineProps<{
  open: boolean;
  /** 传了就进入编辑模式 */
  account: ChannelAccount | null;
}>();

const emit = defineEmits<{ close: []; saved: [] }>();

const form = reactive({
  name: '',
  channel: 'WECHAT_MP' as ChannelType,
  appId: '',
  appSecret: '',
  templateId: '',
  webhookUrl: '',
  enabled: true,
  remark: '',
});

const saving = ref(false);
const error = ref('');

const isMp = computed(() => form.channel === 'WECHAT_MP');
const isEditing = computed(() => props.account !== null);
const title = computed(() =>
  props.account ? `修改通道账号 #${props.account.id}` : '新增通道账号',
);

/** 每次打开重置：新增清空，编辑预填。密钥永远不回填（接口不返回） */
watch(
  () => props.open,
  (open) => {
    if (!open) return;
    error.value = '';
    const a = props.account;
    form.name = a?.name ?? '';
    form.channel = a?.channel ?? 'WECHAT_MP';
    form.appId = a?.appId ?? '';
    form.templateId = a?.templateId ?? '';
    form.appSecret = '';
    form.webhookUrl = '';
    form.enabled = a?.enabled ?? true;
    form.remark = a?.remark ?? '';
  },
  { immediate: true },
);

async function submit() {
  error.value = '';
  if (!form.name.trim()) {
    error.value = '请填写账号名称';
    return;
  }

  const payload: SaveChannelAccountPayload = {
    name: form.name.trim(),
    channel: form.channel,
    enabled: form.enabled,
    remark: form.remark.trim() || undefined,
  };

  if (isMp.value) {
    payload.appId = form.appId.trim();
    payload.templateId = form.templateId.trim();
    // 留空表示不修改原密钥（编辑时），新增时留空就是没配
    if (form.appSecret.trim()) {
      payload.appSecret = form.appSecret.trim();
    }
  } else {
    if (form.webhookUrl.trim()) {
      payload.webhookUrl = form.webhookUrl.trim();
    }
  }

  saving.value = true;
  try {
    if (props.account) {
      await updateAccount(props.account.id, payload);
    } else {
      await createAccount(payload);
    }
    emit('saved');
  } catch (e) {
    error.value = '保存失败：' + (e as Error).message;
  } finally {
    saving.value = false;
  }
}

/** 编辑态下提示「已经配过」，避免用户以为密钥丢了 */
function secretHint(configured?: boolean): string {
  return isEditing.value && configured
    ? '已配置，留空表示保持不变；填新值会覆盖'
    : '仅写入时提交，列表接口不会返回';
}
</script>

<template>
  <ModalDialog :open="open" :title="title" @close="emit('close')">
    <div class="modal-grid">
      <div class="field">
        <label>账号名称</label>
        <input v-model="form.name" placeholder="如 主公众号 / 告警号" />
      </div>
      <div class="field">
        <label>通道</label>
        <select v-model="form.channel">
          <option value="WECHAT_MP">公众号</option>
          <option value="WECHAT_WORK">企业微信</option>
        </select>
      </div>

      <template v-if="isMp">
        <div class="field span-2">
          <label>AppID</label>
          <input v-model="form.appId" placeholder="wx..." />
        </div>
        <div class="field span-2">
          <label>AppSecret</label>
          <input
            v-model="form.appSecret"
            type="password"
            :placeholder="secretHint(account?.secretConfigured)"
          />
        </div>
        <div class="field span-2">
          <label>模板 ID</label>
          <input v-model="form.templateId" placeholder="公众号模板消息 template_id" />
        </div>
      </template>

      <template v-else>
        <div class="field span-2">
          <label>群机器人 Webhook</label>
          <input
            v-model="form.webhookUrl"
            :placeholder="secretHint(account?.webhookConfigured)"
          />
        </div>
      </template>

      <div class="field span-2">
        <label>备注（可选）</label>
        <input v-model="form.remark" placeholder="便于区分多个账号" />
      </div>

      <label class="span-2" style="display: flex; align-items: center; gap: 6px">
        <input v-model="form.enabled" type="checkbox" style="width: auto" />
        启用（停用后绑定它的消息会明确失败，不会静默改用别的账号）
      </label>
    </div>

    <div class="hint error" v-if="error">{{ error }}</div>
    <div class="hint">
      凭据存库仅用于本地演示；生产应换成密钥管理或加密列。列表接口不下发密钥。
    </div>

    <template #footer>
      <button class="ghost" :disabled="saving" @click="emit('close')">取消</button>
      <button :disabled="saving" @click="submit">
        {{ saving ? '保存中…' : isEditing ? '保存修改' : '保存' }}
      </button>
    </template>
  </ModalDialog>
</template>
