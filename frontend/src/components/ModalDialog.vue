<script setup lang="ts">
import { onBeforeUnmount, watch } from 'vue';

const props = defineProps<{
  open: boolean;
  title: string;
}>();

const emit = defineEmits<{ close: [] }>();

/** ESC 关闭：弹窗这类遮罩层不给键盘出口，用户会觉得被卡住 */
function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    emit('close');
  }
}

watch(
  () => props.open,
  (open) => {
    if (open) {
      window.addEventListener('keydown', onKeydown);
    } else {
      window.removeEventListener('keydown', onKeydown);
    }
  },
);

onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown));
</script>

<template>
  <!-- 点遮罩关闭：@click.self 保证点内容区不会误关 -->
  <div v-if="open" class="modal-mask" @click.self="emit('close')">
    <div class="modal" role="dialog" aria-modal="true">
      <div class="modal-head">
        <strong>{{ title }}</strong>
        <button class="close-x" title="关闭" @click="emit('close')">✕</button>
      </div>
      <div class="modal-body">
        <slot />
      </div>
      <div class="modal-foot">
        <slot name="footer" />
      </div>
    </div>
  </div>
</template>
