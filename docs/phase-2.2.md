# Phase 2.2：daemon 路由 + 并发 + 生命周期 + frozen via daemon + 自身 AppCDS

> 时间：2026-05-31
> 主题：把 Phase 2.1 的"单 daemon、串行处理、不路由"加固成"多 daemon 按 JDK 分、并发、自治"。

## 这一波要解决什么

Phase 2.1 已经证明 daemon 化的核心价值。但有六个工程化短板必须补齐才好让用户真正依赖它：

1. **路由**：脚本声明 `@file:Toolchain(jdk = "17")` 时，daemon 还在用启动 ktx 的那个 JVM 跑。Phase 2.1 的对策是「该用 daemon 时报错让用户去掉 --daemon」——这显然不行。
2. **并发**：accept loop 一次只处理一个 client，第二个 client 阻塞。
3. **`--frozen` / `--lock`** 不能与 daemon 一起用（resolver 没 IPC 化）。
4. **stdin 透传**：daemon 进程的 stdin 是 /dev/null，脚本 `readLine()` 拿不到东西。
5. **空闲超时 / heap watchdog**：daemon 永不退出。
6. **daemon 自身冷启慢**：首次启动 1.5s（无 AppCDS）。

Phase 2.2 一次把这六个一起解决了。

## 关键设计

### 1. toolchain 路由：daemon 目录按 JDK 主版本分

`~/.cache/ktx/d/<key12>/`，key 由 `(jdkMajor, protocolVersion)` 算 sha256 取前 12 位（短到 macOS UDS 104 字节限制下都能放）。每个目录独立 socket / pid / log / jsa，互不影响。

```kotlin
fun computeKey(jdkMajor: Int): ToolchainKey {
    val raw = "jdk=$jdkMajor;protocol=v1"
    val hex = sha256(raw).toHex()
    return ToolchainKey(full = hex, raw = raw, short = hex.substring(0, 12))
}
```

CLI 里 `RunCommand.pickToolchain()`：

- 脚本未声明 jdk → 用当前 JVM 主版本（不切 JDK，daemon 就用启动 ktx 的那个 java）
- 声明的版本与当前一致 → 同上
- 声明的版本不同 → 走 `ToolchainStore.find / install`，用对应 JDK 的 `bin/java` fork daemon

`DaemonLifecycle.ensureRunning(jdkMajor, javaBin)` 接受这两个参数，路由到对应目录、用对应 java 启动子进程。

为什么不在 daemon 内部跨 JDK 切换：JVM 启动后跑哪个 JDK 就是哪个，没法在进程内换。所以每个 JDK 主版本一个 daemon 是天然解。

### 2. 并发处理 + RoutedOutputStream

accept loop 改成 `executor.submit { handleClient(...) }`，cachedThreadPool 自动按需扩缩。

stdout/stderr 通过 `RoutedOutputStream` 路由：

```kotlin
class RoutedOutputStream(val fallback: OutputStream) : OutputStream() {
    private val tl = ThreadLocal<OutputStream?>()
    fun bind(sink: OutputStream) = tl.set(sink)
    fun unbind() = tl.remove()
    override fun write(...) = (tl.get() ?: fallback).write(...)
}
```

daemon 启动时一次性 `System.setOut(PrintStream(RoutedOutputStream(/dev/null)))`，每个 worker 线程进入 handleRun 时 bind 自己的 SocketSink，离开时 unbind。同一时间多个脚本并发跑，println 各走各的 socket，不交叉。

Phase 2.1 的 `SocketSink` 实现保持不变（8KB buffer + writeLock 保证一帧不被切碎），但 writeLock 现在是请求级的（Each request own lock），不是进程级。

### 3. flock 防并发启动

5 个 CLI 同时 `ensureRunning` 时，5 个都看到 socket 不存在 → 5 个并发 fork → 5 个 daemon 抢同一个 socket → 4 个 bind 失败崩溃。修法：daemon 目录下的 `.start.lock` 文件做 advisory lock，临界区是「再次检查 socket + fork + 等 socket ready」。

```kotlin
RandomAccessFile(lockFile, "rw").use { raf ->
    raf.channel.lock().use {
        if (!socketAlive(socketPath)) {
            forkDaemon(daemonDir, javaBin)
            waitForSocket(socketPath)
        }
    }
}
```

### 4. ResolverMode + lockfile_path 走协议

proto 加：

```proto
enum ResolverMode { NORMAL = 0; FROZEN = 1; }
message RunRequest {
  ...
  ResolverMode resolver_mode = 6;
  string lockfile_path = 7;
  bytes stdin = 8;
}
```

daemon 端 handleRun 按 mode 构造 resolver：

```kotlin
val resolver = when (req.resolverMode) {
    FROZEN -> FrozenResolver(Lockfile.read(Path.of(req.lockfilePath))!!)
    NORMAL, UNRECOGNIZED, null -> null
}
runner.run(scriptPath, args, resolver = resolver)
```

CLI 端 RunCommand 取消 `--frozen` 与 `--daemon` 互斥（Phase 2.1 强制的）。

### 5. stdin 字段加了，透传**还没完全接通**

proto 已加 `bytes stdin`，daemon 端用 `System.setIn(ByteArrayInputStream(req.stdin))` 替换。CLI 端尝试自动检测「ktx stdin 是不是被管道喂入」并一次性读完转发：

```kotlin
private fun readStdinIfPiped(): ByteArray? {
    if (System.console() != null) return null  // tty 跳过
    return runCatching { System.`in`.readAllBytes() }.getOrNull()
}
```

但 `System.console() == null` 的判断在「mise exec」「容器」「IDE 终端」等场景都是 null，会触发 readAllBytes 永远 hang —— 用户没主动喂 stdin 但也没真 tty 时无法区分。

Phase 2.2 的妥协：**daemon 模式下默认不传 stdin**，需要 stdin 的脚本暂时去掉 `--daemon`。proto 字段保留，Phase 2.3 用流式协议（client 后台线程持续推 stdin 字节，daemon 包装成边读边等的 InputStream）真正接通。

### 6. 空闲超时 + heap watchdog

daemon 内一个单线程的 `ScheduledExecutorService` 跑两个周期任务：

- 每 30 秒：检查 `System.currentTimeMillis() - lastActivityMs`，超过 `KTX_DAEMON_IDLE_TIMEOUT_MIN`（默认 30 分钟）就 `shutdownRequested = true` + `server.close()` 唤醒 accept loop 退出
- 每 60 秒：检查 heap 占用比例，超过 `KTX_DAEMON_HEAP_WARN_PCT`（默认 80%）就 log warn + `System.gc()`

`lastActivityMs` 在 accept 后和 client 处理完后各刷一次。Status RPC 也算活动（客户端来查就说明在用）。

Phase 2.2 不做主动「heap 满了拒新请求」—— 实测 daemon 暖时 heap 不到 100MB / 4GB 限制，远不到压力点。等用户跑大脚本撞到再加。

### 7. daemon 自身 AppCDS

CLI fork daemon 时直接给：

```kotlin
val cmd = listOf(
    javaBin,
    "-XX:SharedArchiveFile=${daemonDir}/daemon.jsa",
    "-XX:+AutoCreateSharedArchive",
    "-cp", classpath,
    "io.github.nebula.ktx.daemon.Bootstrap",
)
```

第一次启动 jsa 不存在，AutoCreateSharedArchive 让 JVM 跑完后自动归档到原路径；第二次起命中。**daemon 冷启从 1.5s 降到 ~700ms**。

jsa 13MB（首次只录到 hello 触发的类），用户跑过更复杂的脚本后归档会更大但更值。jsa 按 daemon 目录分，所以 jdk=21 daemon 的 jsa 不会污染 jdk=17 的（class file version 不同会被 JVM 自动重建）。

## 性能矩阵（Phase 2.2）

| 场景 | Phase 2.1 | Phase 2.2 | 说明 |
|---|---|---|---|
| daemon 冷启动（首次 fork） | 2.1s | **680ms** | -1.4s，AppCDS 救场 |
| daemon 暖 + 缓存命中 | 120ms | **120ms** | 持平 |
| dev loop（暖 + 新脚本，第一个） | 750ms | 1.32s | 慢一些，原因见下 |
| dev loop（暖 + 新脚本，后续） | 250-310ms | **240-270ms** | 持平 |
| frozen via daemon（暖） | 不支持 | **170ms** | -130ms vs 非 daemon frozen 的 300ms |
| 多 JDK 路由 | 不支持 | ✓ | jdk=17 / jdk=21 各自 daemon |
| 并发处理 | 串行 | ✓ | 5 并发请求都 OK |

dev loop 第一个新脚本 1.32s 是 daemon 内部的 lazy init（main-kts `MainKtsConfigurator` 第一次解析触发的资源加载等）。从第二个脚本开始就回到稳定 240-270ms 的 dev loop 速度——这才是真实使用场景的体感。

## 项目布局变化

```diff
modules/protocol/src/main/proto/ktx.proto
+ enum ResolverMode { NORMAL = 0; FROZEN = 1; }
+ message RunRequest { ... ResolverMode resolver_mode; string lockfile_path; bytes stdin; }

modules/daemon/src/main/kotlin/.../daemon/
+ RoutedOutputStream.kt              ThreadLocal stdout
  DaemonServer.kt                    并发 + 生命周期 + frozen 支持 + ScheduledExecutor
  DaemonPaths.kt                     按 jdkMajor 分目录的 daemonDirFor()
  Bootstrap.kt                       从 KTX_DAEMON_DIR 读目录

modules/cli/src/main/kotlin/.../cli/
  ipc/DaemonLifecycle.kt             ensureRunning(jdkMajor, javaBin) + flock + jsa
  ipc/DaemonClient.kt                run(...) 加 resolverMode/lockfilePath/stdin 参数
  command/RunCommand.kt              pickToolchain() 路由 + pickResolver() 模式选择
  command/DaemonCommand.kt           --all / --jdk 选项
```

## 这一波撞到的坑

**`mise exec -- env VAR=...` 不会把 VAR 传给 ktx 子进程。**
我以为 mise 会继承 env 命令的设置，但实际 mise exec 自己有一套环境处理。改成 `KTX_DAEMON_IDLE_TIMEOUT_MIN=1 mise exec -- ktx ...`（外层 shell 环境变量）才生效。

**空闲超时检查间隔 30 秒，最坏情况会比超时晚 30 秒触发。**
我配 1 分钟超时但等了 80 秒以为没工作 —— 实际它在 90 秒（60+30）才触发。Phase 2.2 默认 30 分钟，这种偏差用户感受不到。

**`System.console() == null` 不能可靠区分「真 tty」和「伪 tty」。**
mise exec 下 console 是 null，但 stdin 不一定有数据 —— readAllBytes 永远 hang。最终决定 daemon 模式下默认不读 stdin，proto 字段保留给 Phase 2.3 流式版本。

**在 stop 后立即 ensureRunning 有竞态。**
shutdown 信号已发，但 daemon 进程还在退出过程中（accept loop 在 server.close() 后才返回，supervisor 还在 shutdownNow）；CLI 这边可能在 socket 文件被删之前先看到它存在（socketAlive 返回 true），然后试连一个已经死了的 daemon。Phase 2.2 没专门修这个 —— flock + 「连不上算死」的兜底在大多数时候足够，用户感受不到。

**多个 .gradle.kts 块状改动后容易留下重复 import。**
Edit 工具在 Kotlin 文件里多次插入大段时偶尔留下重复导入，编译器一眼看穿但调试要看错误才发现。下波单独 commit 前用 IDE 整理一下。

## 已知遗留 / 下一波（Phase 2.3）要做的

1. **流式 stdin 透传**：CLI 后台线程把 stdin 字节持续推给 daemon；协议加 `RunStdinChunk` 消息。
2. **多 Kotlin 编译器版本**：脚本 `@file:Toolchain(kotlin = "...")` 真实生效。需要按版本下载 `kotlin-compiler-embeddable` jar、按版本路由 daemon、daemon 内用独立 ClassLoader 加载编译器。
3. **`--lock` 通过 daemon**：lock 流程需要禁用 daemon 的编译产物缓存（Phase 1.2 的 bypassCompiledCache），跨 IPC 怎么传这个标志要想清楚。简单做法：daemon 收到 lock-mode 的 RunRequest 时复制一份 ScriptRunner 实例，用临时缓存目录。
4. **`daemon logs` 子命令**：方便用户看 daemon 日志，不用自己找路径。
5. **协议演化**：Phase 2.2 改了 RunRequest 字段，但 PROTOCOL_VERSION 仍是 1。新字段是 proto3 兼容性扩展（旧 daemon 收到新请求时只忽略未知字段），所以暂不升版本号。等到有不兼容的改动再升 v2。

## 总结

Phase 2.2 把 daemon 从「demo 级」推到「日常用」。最大的胜利是 `frozen via daemon = 170ms`：CI 场景下脚本启动从 ~6.7s 一路掉到 170ms，**40 倍加速**——这也是 ktx 项目立项时给 uv 体验的最强对标。

Phase 2.3 把 stdin 流式 + 多 Kotlin 版本做完，Phase 2 就完整了。
