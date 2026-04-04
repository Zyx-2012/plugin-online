<script setup lang="ts">
import { VPageHeader } from "@halo-dev/components";
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { fetchStats, fetchSummary } from "@/api";
import type { OnlineStatItem, OnlineSummary } from "@/types";

const loading = ref(true);
const error = ref("");
const items = ref<OnlineStatItem[]>([]);
const summary = ref<OnlineSummary>({
  total: 0,
  peak24h: 0,
  activePages: 0,
  updatedAt: new Date().toISOString(),
  wsActive: true,
});

const refreshRateSeconds = ref(10);
let timer: number | undefined;

const sortedItems = computed(() => {
  return [...items.value].sort((a, b) => b.count - a.count);
});

const totalLabel = computed(() => summary.value.total.toLocaleString());
const peakLabel = computed(() => summary.value.peak24h.toLocaleString());
const activePagesLabel = computed(() => summary.value.activePages.toLocaleString());

function formatRelativeTime(input?: string | null) {
  if (!input) return "—";
  const time = new Date(input).getTime();
  const diff = Date.now() - time;

  if (diff < 10_000) return "刚刚";
  if (diff < 60_000) return `${Math.floor(diff / 1000)} 秒前`;
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`;
  return `${Math.floor(diff / 86_400_000)} 天前`;
}

async function loadData() {
  try {
    error.value = "";
    const [summaryRes, statsRes] = await Promise.all([
      fetchSummary(),
      fetchStats(),
    ]);
    summary.value = summaryRes;
    items.value = statsRes;
  } catch (e) {
    console.error(e);
    error.value = "获取在线数据失败，请检查插件后端接口是否正常。";
  } finally {
    loading.value = false;
  }
}

function startPolling() {
  stopPolling();
  timer = window.setInterval(loadData, refreshRateSeconds.value * 1000);
}

function stopPolling() {
  if (timer) {
    window.clearInterval(timer);
    timer = undefined;
  }
}

function openPage(uri: string) {
  const url = uri.startsWith("/") ? uri : `/${uri}`;
  window.open(url, "_blank");
}

onMounted(async () => {
  await loadData();
  startPolling();
});

onBeforeUnmount(() => {
  stopPolling();
});
</script>

<template>
  <div class="online-monitor-page">
    <VPageHeader title="在线监控看板">
      <template #actions>
        <button class="om-btn" @click="loadData">立即刷新</button>
      </template>
    </VPageHeader>

    <div class="om-grid">
      <section class="om-stat-card om-stat-card--primary">
        <div class="om-stat-label">当前全站总计</div>
        <div class="om-stat-main">
          <span class="om-breathing-dot" :class="{ 'is-active': summary.wsActive }"></span>
          <span class="om-stat-value">{{ totalLabel }}</span>
        </div>
        <div class="om-stat-sub">WebSocket 活跃中</div>
      </section>

      <section class="om-stat-card">
        <div class="om-stat-label">过去 24 小时峰值</div>
        <div class="om-stat-value">{{ peakLabel }}</div>
        <div class="om-stat-sub">当前为内存态统计，插件重启后会重新累计</div>
      </section>

      <section class="om-stat-card">
        <div class="om-stat-label">活跃页面数</div>
        <div class="om-stat-value">{{ activePagesLabel }}</div>
        <div class="om-stat-sub">当前仍有人停留的 URI 数量</div>
      </section>
    </div>

    <section class="om-panel">
      <div class="om-panel-header">
        <div>
          <h3>实时路径热度榜</h3>
          <p>最后更新：{{ formatRelativeTime(summary.updatedAt) }}</p>
        </div>
        <div class="om-panel-meta">
          刷新频率：{{ refreshRateSeconds }} 秒
        </div>
      </div>

      <div v-if="loading" class="om-empty">正在加载实时数据…</div>
      <div v-else-if="error" class="om-empty om-empty--error">{{ error }}</div>
      <div v-else-if="sortedItems.length === 0" class="om-empty">当前没有在线页面</div>
      <div v-else class="om-table-wrap">
        <table class="om-table">
          <thead>
            <tr>
              <th>页面路径 (URI)</th>
              <th>在线人数</th>
              <th>最后活跃时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in sortedItems" :key="item.uri">
              <td class="om-uri">{{ item.uri }}</td>
              <td>
                <span class="om-count-chip">{{ item.count }}</span>
              </td>
              <td>{{ formatRelativeTime(item.lastActiveAt) }}</td>
              <td>
                <button class="om-link-btn" @click="openPage(item.viewUrl)">
                  查看页面
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>