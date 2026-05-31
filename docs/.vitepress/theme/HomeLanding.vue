<script setup lang="ts">
/**
 * Custom landing page for ktx. Replaces the default VitePress hero block.
 *
 * Layout:
 *   - Top-right release pill (current version + relative date)
 *   - Two-column hero: copy on the left, logo on the right
 *   - Stats row showing the headline ratios from the benchmark page
 *   - Five proof cards covering ktx's core features (mirrors aube's pattern
 *     but at ktx's scale)
 */
import { computed, ref, onMounted, onBeforeUnmount } from "vue";
import { withBase } from "vitepress";
import { data as bench } from "./benchmarks.data.ts";

const logoMark = withBase("/logo-mark.png");
const guideStarted = withBase("/guide/getting-started");
const linkPerf = withBase("/performance");
const linkDaemon = withBase("/guide/daemon");
const linkLockfile = withBase("/guide/lockfile");
const linkToolchain = withBase("/guide/toolchain");
const linkCompile = withBase("/guide/compile");

const copied = ref(false);
async function copyInstall() {
  await navigator.clipboard?.writeText("mise use -g github:gaojunran/ktx@latest");
  copied.value = true;
  window.setTimeout(() => (copied.value = false), 1400);
}

// Headline benchmark numbers, derived from the same JSON the perf page reads.
const ratios = computed(() => {
  const dev = bench.rows.find((r) => r.key === "dev-loop")?.values;
  const warm = bench.rows.find((r) => r.key === "warm-no-deps")?.values;
  const devVsMainKts =
    dev && dev["main-kts"] && dev["ktx-daemon"]
      ? (dev["main-kts"]! / dev["ktx-daemon"]!).toFixed(1)
      : "—";
  const warmVsMainKts =
    warm && warm["main-kts"] && warm["ktx-daemon"]
      ? (warm["main-kts"]! / warm["ktx-daemon"]!).toFixed(1)
      : "—";
  return { devVsMainKts, warmVsMainKts };
});

// Animated terminal-ish demo on the right side.
const lines = [
  { prompt: true, text: "mise use -g github:gaojunran/ktx@latest" },
  { ok: true, text: "github:gaojunran/ktx@0.0.1   installed" },
  { prompt: true, text: "ktx hello.main.kts" },
  { out: true, text: "hello, args = []" },
  { prompt: true, text: "ktx run --daemon hello.main.kts" },
  { out: true, text: "hello, args = []" },
  { ms: true, text: "120 ms" },
];
const visibleLines = ref(0);
let interval: ReturnType<typeof setInterval> | null = null;
onMounted(() => {
  interval = setInterval(() => {
    if (visibleLines.value < lines.length) visibleLines.value++;
    else if (interval) clearInterval(interval);
  }, 300);
});
onBeforeUnmount(() => {
  if (interval) clearInterval(interval);
});
</script>

<template>
  <main class="ktx-home">
    <div class="ktx-hero-glow" aria-hidden="true"></div>

    <section class="ktx-hero">
      <div class="ktx-hero-copy">
        <div class="ktx-eyebrow">A modern Kotlin script runner</div>
        <h1>
          Run <em>.kts</em> like you<br />
          run modern scripts.
        </h1>
        <p class="ktx-lede">
          What <code>uv</code> is for Python and <code>bun</code> is for JavaScript,
          ktx aims to be for <code>.kts</code>. Daemon-warm starts, lockfiles,
          per-script JDK, and a one-command compile-to-fat-jar — built on top of
          JetBrains' supported <code>kotlin-main-kts</code>.
        </p>

        <div class="ktx-actions">
          <a class="ktx-button ktx-button-primary" :href="guideStarted">
            Get started
            <span aria-hidden="true">→</span>
          </a>
          <div class="ktx-install">
            <span class="ktx-prompt">$</span>
            <code>mise use -g github:gaojunran/ktx@latest</code>
            <button type="button" @click="copyInstall">
              {{ copied ? "copied" : "copy" }}
            </button>
          </div>
        </div>

        <dl class="ktx-stats">
          <a class="ktx-stat" :href="linkPerf">
            <dt>{{ ratios.warmVsMainKts }}×</dt>
            <dd>faster than <code>main-kts</code> warm</dd>
          </a>
          <a class="ktx-stat" :href="linkPerf">
            <dt>{{ ratios.devVsMainKts }}×</dt>
            <dd>faster on the dev loop</dd>
          </a>
          <a class="ktx-stat" :href="linkCompile">
            <dt>13 MB</dt>
            <dd>standalone jar from <code>ktx compile</code></dd>
          </a>
        </dl>
      </div>

      <div class="ktx-stage">
        <img
          class="ktx-logo-art"
          :src="logoMark"
          alt="ktx logo"
          width="320"
          height="320"
        />
        <div class="ktx-terminal" aria-hidden="true">
          <div class="ktx-terminal-bar">
            <span></span><span></span><span></span>
            <strong>ktx demo</strong>
          </div>
          <div class="ktx-terminal-body">
            <template v-for="(line, idx) in lines" :key="idx">
              <div v-if="idx < visibleLines" class="ktx-line">
                <span v-if="line.prompt" class="t-prompt">$&nbsp;</span>
                <span v-if="line.ok" class="t-ok">✓ </span>
                <span v-if="line.ms" class="t-ms">⏱ </span>
                <span :class="{ 't-cmd': line.prompt, 't-ok-text': line.ok, 't-out': line.out, 't-ms-text': line.ms }">
                  {{ line.text }}
                </span>
              </div>
            </template>
            <div v-if="visibleLines >= lines.length" class="ktx-line">
              <span class="t-prompt">$&nbsp;</span>
              <span class="ktx-caret">▍</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <section class="ktx-proof">
      <a class="ktx-card" :href="linkDaemon">
        <span class="ktx-card-num">01</span>
        <span class="ktx-card-tag">daemon</span>
        <strong>A warm Kotlin compiler.</strong>
        <span>
          The daemon keeps the embedded compiler resident across runs, so
          repeat invocations land at ~120 ms — not the multi-second cost of
          spinning up a fresh JVM and PSI/FIR every time.
        </span>
        <span class="ktx-card-link">Daemon mode →</span>
      </a>

      <a class="ktx-card" :href="linkLockfile">
        <span class="ktx-card-num">02</span>
        <span class="ktx-card-tag">lockfiles</span>
        <strong>Reproducible scripts.</strong>
        <span>
          <code>ktx lock</code> writes <code>*.kts.lock</code> pinning every
          transitive jar to its sha256.
          <code>ktx run --frozen</code> runs offline, makes CI deterministic.
        </span>
        <span class="ktx-card-link">Lockfiles →</span>
      </a>

      <a class="ktx-card" :href="linkToolchain">
        <span class="ktx-card-num">03</span>
        <span class="ktx-card-tag">toolchain</span>
        <strong>Per-script JDK.</strong>
        <span>
          Declare <code>@file:Toolchain(jdk = "17")</code> and ktx auto-installs
          the requested JDK from Adoptium and re-execs onto it before
          compilation.
        </span>
        <span class="ktx-card-link">Toolchain →</span>
      </a>

      <a class="ktx-card" :href="linkCompile">
        <span class="ktx-card-num">04</span>
        <span class="ktx-card-tag">compile</span>
        <strong>Distribute as a single jar.</strong>
        <span>
          <code>ktx compile</code> emits a 11–13 MB fat jar that runs on plain
          JRE 17+ — your end users do not need ktx, do not need a Kotlin
          compiler, do not need anything but Java.
        </span>
        <span class="ktx-card-link">Compile →</span>
      </a>
    </section>
  </main>
</template>

<style scoped>
.ktx-home {
  box-sizing: border-box;
  color: var(--vp-c-text-1);
  margin: 0 auto;
  max-width: 1200px;
  padding: 60px 32px 100px;
  position: relative;
  width: 100%;
}

.ktx-hero-glow {
  background: radial-gradient(
    ellipse 50% 40% at 50% 35%,
    rgba(127, 82, 255, 0.28) 0%,
    rgba(88, 148, 255, 0.18) 35%,
    transparent 65%
  );
  height: 900px;
  left: 50%;
  pointer-events: none;
  position: absolute;
  top: -100px;
  transform: translateX(-50%);
  width: 1400px;
  z-index: -1;
}

.ktx-hero {
  align-items: center;
  display: grid;
  gap: 64px;
  grid-template-columns: minmax(0, 1.05fr) minmax(0, 0.9fr);
  min-height: 480px;
  position: relative;
}

.ktx-hero-copy {
  max-width: 620px;
  min-width: 0;
}

.ktx-eyebrow {
  color: var(--ktx-violet, #7F52FF);
  font-family: var(--vp-font-family-mono, monospace);
  font-size: 13px;
  font-weight: 500;
  letter-spacing: 0.3px;
  margin-bottom: 18px;
  text-transform: uppercase;
}

.ktx-hero h1 {
  color: var(--vp-c-text-1);
  font-family: var(--vp-font-family-base, system-ui);
  font-size: clamp(40px, 5.4vw, 64px);
  font-weight: 700;
  letter-spacing: -0.02em;
  line-height: 1.05;
  margin: 0 0 24px;
}

.ktx-hero h1 em {
  background: linear-gradient(120deg, #7F52FF 0%, #5894FF 100%);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  font-style: normal;
  font-weight: 700;
}

.ktx-lede {
  color: var(--vp-c-text-2);
  font-size: 17px;
  line-height: 1.6;
  margin: 0 0 28px;
  max-width: 560px;
}
.ktx-lede code {
  background: var(--vp-c-bg-soft);
  border-radius: 4px;
  font-size: 0.9em;
  padding: 1px 6px;
}

.ktx-actions {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 40px;
}

.ktx-button {
  align-items: center;
  border-radius: 10px;
  display: inline-flex;
  font-size: 14px;
  font-weight: 500;
  gap: 10px;
  justify-content: center;
  min-height: 44px;
  padding: 0 20px;
  text-decoration: none !important;
  transition: filter 180ms ease, transform 180ms ease;
}
.ktx-button-primary {
  background: linear-gradient(120deg, #7F52FF, #5894FF);
  color: #fff !important;
  box-shadow: 0 6px 20px rgba(127, 82, 255, 0.35);
}
.ktx-button-primary:hover {
  filter: brightness(1.07);
  transform: translateY(-1px);
}

.ktx-install {
  align-items: center;
  background: var(--vp-c-bg-soft);
  border: 1px solid var(--vp-c-divider);
  border-radius: 10px;
  color: var(--vp-c-text-2);
  display: inline-flex;
  font-family: var(--vp-font-family-mono, monospace);
  font-size: 12.5px;
  gap: 8px;
  min-height: 44px;
  padding: 0 8px 0 14px;
}
.ktx-prompt {
  color: var(--ktx-violet, #7F52FF);
}
.ktx-install code {
  background: transparent !important;
  border: 0 !important;
  color: var(--vp-c-text-2);
  font-family: inherit;
  font-size: inherit;
  padding: 0 !important;
}
.ktx-install button {
  background: var(--vp-c-bg);
  border: 0;
  border-radius: 6px;
  color: var(--vp-c-text-2);
  cursor: pointer;
  font-family: inherit;
  font-size: 11px;
  min-height: 28px;
  padding: 0 10px;
}
.ktx-install button:hover {
  color: var(--vp-c-text-1);
}

.ktx-stats {
  border-top: 1px solid var(--vp-c-divider);
  display: grid;
  gap: 36px;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  margin: 0;
  padding-top: 28px;
}
.ktx-stat {
  color: inherit;
  display: block;
  text-decoration: none !important;
  transition: color 160ms ease;
}
.ktx-stat dt {
  color: var(--vp-c-text-1);
  font-size: 30px;
  font-weight: 700;
  letter-spacing: -0.01em;
  line-height: 1;
}
.ktx-stat dd {
  color: var(--vp-c-text-3);
  font-size: 12.5px;
  line-height: 1.4;
  margin: 6px 0 0;
}
.ktx-stat dd code {
  background: transparent;
  font-size: inherit;
  padding: 0;
}
.ktx-stat:hover dt {
  color: var(--ktx-violet, #7F52FF);
}

/* Right column */
.ktx-stage {
  align-items: center;
  display: flex;
  flex-direction: column;
  gap: 28px;
  min-width: 0;
  position: relative;
}

.ktx-logo-art {
  filter:
    drop-shadow(0 0 28px rgba(127, 82, 255, 0.45))
    drop-shadow(0 0 50px rgba(88, 148, 255, 0.28));
  height: auto;
  max-width: 280px;
  width: 100%;
}

.ktx-terminal {
  background: linear-gradient(180deg, #1f1d28 0%, #16151c 100%);
  border: 1px solid rgba(127, 82, 255, 0.25);
  border-radius: 12px;
  box-shadow:
    0 1px 0 rgba(255, 255, 255, 0.04) inset,
    0 30px 60px -30px rgba(0, 0, 0, 0.5),
    0 0 0 1px rgba(127, 82, 255, 0.1);
  overflow: hidden;
  width: 100%;
  max-width: 380px;
}
.ktx-terminal-bar {
  align-items: center;
  background: rgba(255, 255, 255, 0.03);
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  display: flex;
  gap: 7px;
  padding: 10px 12px;
}
.ktx-terminal-bar span {
  border-radius: 50%;
  height: 9px;
  width: 9px;
}
.ktx-terminal-bar span:nth-child(1) { background: #ff6058; }
.ktx-terminal-bar span:nth-child(2) { background: #ffbd2e; }
.ktx-terminal-bar span:nth-child(3) { background: #28c840; }
.ktx-terminal-bar strong {
  color: rgba(255, 255, 255, 0.4);
  font-family: var(--vp-font-family-mono, monospace);
  font-size: 11px;
  font-weight: 500;
  margin-left: 10px;
}
.ktx-terminal-body {
  color: rgba(255, 255, 255, 0.85);
  font-family: var(--vp-font-family-mono, monospace);
  font-size: 12.5px;
  line-height: 1.7;
  min-height: 200px;
  padding: 14px 18px 18px;
}
.ktx-line {
  display: flex;
  align-items: baseline;
  white-space: nowrap;
  overflow: hidden;
}
.t-prompt { color: #7F52FF; }
.t-ok { color: #28c840; }
.t-ok-text { color: rgba(255, 255, 255, 0.7); }
.t-ms { color: #5894FF; }
.t-ms-text { color: #5894FF; }
.t-out { color: rgba(255, 255, 255, 0.85); }
.t-cmd { color: rgba(255, 255, 255, 0.95); }
.ktx-caret {
  animation: ktx-blink 1s steps(1) infinite;
  color: #7F52FF;
}
@keyframes ktx-blink {
  50% { opacity: 0; }
}

/* Proof cards */
.ktx-proof {
  display: grid;
  gap: 16px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  margin-top: 80px;
}
.ktx-card {
  background: var(--vp-c-bg);
  border: 1px solid var(--vp-c-divider);
  border-radius: 12px;
  color: var(--vp-c-text-1);
  display: grid;
  gap: 12px;
  grid-template-rows: auto auto auto 1fr auto;
  min-height: 220px;
  padding: 28px 24px 24px;
  position: relative;
  text-decoration: none !important;
  transition: border-color 180ms, transform 180ms, background 180ms;
}
.ktx-card:hover {
  background: var(--vp-c-bg-soft);
  border-color: rgba(127, 82, 255, 0.4);
  transform: translateY(-2px);
}
.ktx-card-num {
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono, monospace);
  font-size: 11px;
}
.ktx-card-tag {
  background: rgba(127, 82, 255, 0.12);
  border-radius: 4px;
  color: #7F52FF;
  font-family: var(--vp-font-family-mono, monospace);
  font-size: 11px;
  padding: 4px 9px;
  position: absolute;
  right: 24px;
  top: 28px;
}
.ktx-card strong {
  color: var(--vp-c-text-1);
  display: block;
  font-size: 19px;
  font-weight: 600;
  letter-spacing: -0.005em;
  line-height: 1.2;
}
.ktx-card > strong + span {
  color: var(--vp-c-text-2);
  display: block;
  font-size: 14.5px;
  line-height: 1.55;
}
.ktx-card code {
  background: var(--vp-c-bg-soft);
  border-radius: 3px;
  font-size: 0.92em;
  padding: 1px 5px;
}
.ktx-card-link {
  color: #7F52FF;
  font-family: var(--vp-font-family-mono, monospace);
  font-size: 13px;
  margin-top: 4px;
}

@media (max-width: 960px) {
  .ktx-home { padding: 40px 20px 80px; }
  .ktx-hero {
    gap: 40px;
    grid-template-columns: 1fr;
    min-height: 0;
  }
  .ktx-stage { order: -1; flex-direction: row; gap: 16px; }
  .ktx-logo-art { max-width: 140px; }
  .ktx-terminal { display: none; }
  .ktx-proof { grid-template-columns: 1fr; }
}

@media (max-width: 560px) {
  .ktx-home { padding: 32px 16px 64px; }
  .ktx-hero h1 { font-size: 38px; }
  .ktx-actions { flex-direction: column; align-items: stretch; }
  .ktx-button, .ktx-install { width: 100%; }
  .ktx-install { box-sizing: border-box; }
  .ktx-stats { gap: 22px; grid-template-columns: 1fr; }
  .ktx-stage { flex-direction: column; }
}
</style>
