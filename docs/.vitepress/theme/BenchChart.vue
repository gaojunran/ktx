<script setup lang="ts">
/**
 * Pure-CSS bar chart for benchmark comparisons. No mermaid, no chart.js,
 * no canvas — every bar is a styled <div> whose width is a percentage of
 * the row's max value.
 *
 * Each row represents a scenario (e.g. "Warm — hello.kts"); each bar
 * within the row shows one tool's measurement. The fastest bar in each
 * row gets a 1px outline as a "winner" hint.
 */
import { computed } from "vue";

interface Row {
  label: string;
  values: Record<string, number | null>;
}

const props = defineProps<{
  rows: Row[];
  tools: string[];
  unit?: string;
  versions?: Record<string, string>;
}>();

const COLORS: Record<string, string> = {
  // Kotlin's signature corner gradient distributed across the three flavors:
  // ktx-daemon claims the headline violet; ktx the mid blue; main-kts the
  // softer warm tone so it reads as the "baseline" rather than the hero.
  "ktx-daemon": "#7F52FF",
  ktx: "#5894FF",
  "main-kts": "#a3a3b5",
};

function legendLabel(tool: string): string {
  const v = props.versions?.[tool];
  return v ? `${tool} ${v}` : tool;
}

function rowMax(row: Row): number {
  let m = 0;
  for (const tool of props.tools) {
    const v = row.values[tool];
    if (v != null && v > m) m = v;
  }
  return m || 1;
}

function format(ms: number): string {
  if (ms >= 1000) return `${(ms / 1000).toFixed(2)} s`;
  return `${Math.round(ms)} ms`;
}

function winner(row: Row): string | null {
  let best: string | null = null;
  let bestVal = Infinity;
  for (const tool of props.tools) {
    const v = row.values[tool];
    if (v != null && v < bestVal) {
      bestVal = v;
      best = tool;
    }
  }
  return best;
}
</script>

<template>
  <div class="bench-chart">
    <div class="legend">
      <span v-for="tool in tools" :key="tool" class="legend-item">
        <span class="swatch" :style="{ background: COLORS[tool] || '#888' }"></span>
        {{ legendLabel(tool) }}
      </span>
    </div>
    <div v-for="row in rows" :key="row.label" class="scenario">
      <div class="scenario-label">{{ row.label }}</div>
      <div class="bars">
        <div v-for="tool in tools" :key="tool" class="bar-row">
          <div class="bar-name">{{ tool }}</div>
          <div class="bar-track">
            <div
              v-if="row.values[tool] != null"
              class="bar"
              :class="{ winner: winner(row) === tool }"
              :style="{
                width: ((row.values[tool]! / rowMax(row)) * 100) + '%',
                background: COLORS[tool] || '#888',
              }"
            ></div>
            <div v-else class="bar-missing">n/a</div>
          </div>
          <div class="bar-value">
            {{ row.values[tool] != null ? format(row.values[tool]!) : "—" }}
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.bench-chart {
  margin: 1.5rem 0;
  font-size: 14px;
}
.legend {
  display: flex;
  gap: 1.25rem;
  flex-wrap: wrap;
  margin-bottom: 1.25rem;
  padding-bottom: 0.85rem;
  border-bottom: 1px solid var(--vp-c-divider);
}
.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 0.45rem;
  color: var(--vp-c-text-2);
  font-variant-numeric: tabular-nums;
}
.swatch {
  display: inline-block;
  width: 12px;
  height: 12px;
  border-radius: 3px;
}
.scenario {
  margin-bottom: 1.4rem;
}
.scenario-label {
  font-weight: 600;
  margin-bottom: 0.5rem;
  color: var(--vp-c-text-1);
}
.bars {
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.bar-row {
  display: grid;
  grid-template-columns: 110px 1fr 80px;
  align-items: center;
  gap: 0.6rem;
}
.bar-name {
  color: var(--vp-c-text-2);
  font-variant-numeric: tabular-nums;
  font-family: var(--vp-font-family-mono, monospace);
  font-size: 12px;
}
.bar-track {
  position: relative;
  height: 20px;
  background: var(--vp-c-bg-soft);
  border-radius: 4px;
  overflow: hidden;
}
.bar {
  height: 100%;
  border-radius: 4px;
  transition: width 0.3s ease;
  min-width: 2px;
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.05);
}
.bar.winner {
  box-shadow:
    inset 0 0 0 1px rgba(255, 255, 255, 0.15),
    0 0 12px rgba(127, 82, 255, 0.45);
}
.bar-missing {
  padding-left: 8px;
  color: var(--vp-c-text-3);
  line-height: 20px;
  font-size: 11px;
  font-style: italic;
}
.bar-value {
  text-align: right;
  font-variant-numeric: tabular-nums;
  color: var(--vp-c-text-1);
  font-size: 13px;
}

@media (max-width: 640px) {
  .bar-row {
    grid-template-columns: 80px 1fr 64px;
    gap: 0.4rem;
  }
  .bar-name {
    font-size: 11px;
  }
  .bar-value {
    font-size: 12px;
  }
}
</style>
