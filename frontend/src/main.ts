import { createApp } from 'vue';
import App from './App.vue';
import './style.css';

// 单页控制台：没有路由与全局状态，三个区块平铺，故不引入 vue-router / pinia
createApp(App).mount('#app');
