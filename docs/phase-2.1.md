# Phase 2.1：daemon 化，最小可运行版

> 时间：2026-05-31
> 主题：把"每次脚本付一次编译器冷启"摊销成"daemon 启动时付一次"。
> dev loop 实测从 750ms 降到 250ms。

## 这一波要解决什么

Phase 1 把 CLI 启动 + 编译产物缓存做完后，剩下最大的一块时间是 **Kotlin 嵌入式编译器自身的冷启动**——每次新脚本（缓存未命中）都付 ~1.4s，AppCDS 救不了。

daemon 让一个长驻 JVM 持有已初始化的编译器、PSI、ClassLoader 等重型对象，把成本从「每次脚本付」摊销成「daemon 启动时付一次」。这是脚本开发体验的核心提升——uv 没这个问题（Python 不编译），Bun 没这个问题（JIT 启动极快），ktx 必须做。

Phase 2.1 范围**极简**：
- 单 daemon，固定 socket 路径
- 单客户端串行处理（一次跑一个脚本）
- 唯一 RPC：Run（含 stdout/stderr 流式回传）
- CLI 通过 `--daemon` flag 或 `KTX_DAEMON=1` 环境变量 opt-in
- 不在 daemon 里：toolchain 路由（2.2）、并发处理（2.2）、生命周期管理（空闲超时/heap watchdog 2.2）、多 Kotlin 版本（2.3）、`--frozen` / `--lock`（2.2 接通）

## 关键设计

### 1. 协议：4 字节长度前缀 + protobuf

```proto
RequestEnvelope { protocol_version, oneof body { run | shutdown | status } }
ResponseEnvelope { oneof body { run_event | shutdown | status | error } }

RunEvent { oneof kind { stdout | stderr | exit } }
```

不用 gRPC（过重，~10MB 依赖）、不用 protobuf delimited（varint 长度对损坏数据不友好）。32MB 上限防恶意 OOM。`protocol_version=1` 不匹配直接拒，让 CLI 杀旧 daemon 重启新版本。

### 2. stdout/stderr 透传：System.setOut + PipedOutputStream

ktx 用户的 println 写到 `System.out`，daemon 把它转给 socket 回传。实现方式是 **进程级** `System.setOut(...)` 替换：
- 串行处理时这没问题，整个 daemon JVM 同时只有一个脚本在跑
- Phase 2.2 上并发时这条要改成 ThreadLocal stdout（每线程独立）

不做 fd 传递（纯 Java 不支持 SCM_RIGHTS，要 JNI）—— Gradle daemon / Kotlin daemon 也都这样。

### 3. ScriptRunner 在 daemon 启动时构造，常驻

```kotlin
class DaemonServer {
    private val runner = ScriptRunner()  // ← 一次构造、永远复用
    fun handleRun(...) = runner.run(...)
}
```

`ScriptRunner` 内部首次 `evaluate` 时才真正初始化 Kotlin compiler embeddable—— 此后所有请求复用，**这就是 daemon 削掉编译器冷启的本质**。

### 4. Unix domain socket，路径短

`~/.cache/ktx/d/socket`（不是 `daemons/default/socket`）—— macOS UDS 路径上限 104 字节，保守起见用最短形式。Phase 2.2 加路由后会变成 `~/.cache/ktx/d/<keyhash>/socket`，仍然在限制内。

### 5. CLI fork daemon：复用当前 JVM + classpath

不打包 daemon launcher 脚本。CLI 直接：

```kotlin
val javaBin = ProcessHandle.current().info().command().get()
val classpath = System.getProperty("java.class.path")
ProcessBuilder(javaBin, "-cp", classpath, "io.github.nebula.ktx.daemon.Bootstrap")
    .redirectInput(/dev/null).redirectOutput(DISCARD).redirectError(DISCARD)
    .start()
```

`redirectOutput(DISCARD)` + 不绑 CLI 终端 → CLI 退出后 daemon 变 init 孤儿继续活。Java 没原生 setsid()，但 ProcessBuilder 的子进程默认就脱离 controlling terminal。

### 6. 启动等待：轮询 socket，不靠 PID 文件

CLI fork 完 daemon 后阻塞，每 50ms 试连 socket，连上即放行，30s 超时报错。比看 PID 文件可靠——PID 写早了 socket 还没 listen，CLI 连过去会失败。

### 7. 残留 socket 自动清理

daemon 启动时 `socket.deleteIfExists()`—— 上一次崩溃留下的 socket 文件不会阻塞重新 bind。CLI 端检测「socket 文件存在但连不上」也直接当作未运行处理。

## 项目布局变化

```diff
+ modules/protocol/                    新模块
+   build.gradle.kts                   protobuf plugin
+   src/main/proto/ktx.proto           RequestEnvelope / ResponseEnvelope / Run / Status
+   src/main/kotlin/.../Frames.kt      4 字节长度前缀帧编解码

+ modules/daemon/                      新模块
+   build.gradle.kts
+   src/main/kotlin/.../Bootstrap.kt   main 入口
+   src/main/kotlin/.../DaemonServer.kt UDS 服务端 + Run handler
+   src/main/kotlin/.../DaemonPaths.kt
+   src/main/resources/logback.xml

  modules/cli/
    build.gradle.kts                   ← 加 protocol + daemon 依赖
+   src/main/kotlin/.../cli/ipc/
+     DaemonClient.kt                  连接、发请求、读流
+     DaemonLifecycle.kt               检测 / fork
+   src/main/kotlin/.../cli/command/
+     DaemonCommand.kt                 ktx daemon status / stop
    command/RunCommand.kt              ← 加 --daemon 选项
    Main.kt                            ← 注册 daemon 子命令
```

## 验证结果

```
$ rm -rf ~/.cache/ktx/d
$ ktx daemon status
daemon: 未运行

$ time ktx run --daemon samples/hello.main.kts a b c
[INFO] starting daemon...
hello from main.kts
args (3): [a, b, c]
        2.11 real      ← 含 fork daemon + JVM 启动 + 编译器初始化 + 编译

$ time ktx run --daemon samples/hello.main.kts x y
hello from main.kts
        0.13 real      ← daemon 暖时

$ time ktx run --daemon samples/hello.main.kts
hello from main.kts
        0.12 real

$ ktx daemon status
daemon: 运行中 (pid=42631)
  uptime          2s
  scripts served  3
  heap            93M / 4096M
```

性能矩阵（macOS M3）：

| 场景 | 无 daemon | daemon 暖 | 提升 |
|---|---|---|---|
| 编译缓存命中（同脚本） | 220ms | **120ms** | -100ms |
| **编译缓存未命中（dev loop）** | **750ms** | **250-310ms** | **-450ms / 60%** |

dev loop 是 daemon 真正的杀手锏：每次改代码重跑能省 60% 时间。

`daemon stop` / 崩溃自动恢复都验证通过：

- `ktx daemon stop` 优雅关闭
- `kill -9 <pid>` 后再 `ktx run --daemon` 自动检测残留 socket、清理、fork 新 daemon
- 第二次启动 800ms（AppCDS 归档已在盘上，比第一次 2.1s 快 1.3s）

## 这一波撞到的坑

**`Channels.newOutputStream` 没有 buffering，println 一字一帧。**
最初每次 println 调一次 `output.write(byte)` 直接 `Frames.write` 一帧。开销巨大且乱。修法：自定义 `SocketSink: OutputStream`，内部 8KB buffer，`flush()` 时打包成一个 RunEvent。脚本里 `println("xxx")` 就是一帧。

**stdout 和 stderr 共享一个 socket，写时要互斥。**
stdout 后台 sink + stderr 后台 sink 都往同一个 channel 写。两个线程同时 `Frames.write` 会把帧字节交叉，客户端解出来全乱。`writeLock` 保护即可——粒度到「一帧」。

**Phase 2.1 daemon 用 `System.setOut` 全局替换，是因为串行处理。**
并发处理（2.2）时同一进程同时跑多脚本，全局 setOut 会冲突。届时改成 ThreadLocal stdout，或者每脚本一个独立 ClassLoader（更重，但隔离更彻底）。Phase 2.1 串行下不预先抽象，避免过度设计。

**`run_event.exit_code` 必须最后发，且必须发。**
client 端读到 `KIND_NOT_SET` / 流提前关闭都当 daemon 异常。daemon 端在 `finally` 块里发 ExitEvent—— 即便脚本编译失败、抛异常，也保证发一个 exit_code 让客户端有的等。否则 client `Frames.read` 永远阻塞或读到 EOF 报错。

**`runEvent.kindCase` 在 protobuf-java 4.x 里有命名陷阱。**
新版本生成器的 oneof case 字段命名变了（CamelCase vs UPPER_SNAKE_CASE）。直接 IDE 补全更安全；记忆里的旧名字会失效。

## 已知遗留 / 下一波（Phase 2.2）要做的

1. **toolchain 路由**：当前一个 daemon 一个 socket，所有用户、所有脚本共用同一个 daemon JVM。脚本声明 `@file:Toolchain(jdk = "17")` 时 daemon 仍用启动它的 JVM 跑（即 RunCommand 里我直接 require 走非 daemon 路径）。Phase 2.2 加 `toolchainKey = sha256(kotlin + jdk + protocolVersion)`，每个 key 独立 daemon。
2. **并发处理**：当前 accept-loop 一次只服务一个 client，第二个 client 阻塞。daemon 用着如果同时跑两个 ktx 命令会卡住。改成 thread-per-connection 即可，但 stdout 要换成 ThreadLocal。
3. **空闲超时 / heap watchdog**：daemon 永不退出。Phase 2.2 加 30 分钟空闲自杀、heap > 80% 主动 GC + 拒绝新请求。
4. **`--frozen` / `--lock` 走 daemon**：当前 daemon RPC 没传 resolver 选项。RunRequest 加 `resolver_mode` 字段（normal / frozen / record）。
5. **stdin 透传**：当前不传 stdin，脚本读 stdin 会得到客户端的（CLI 进程的）stdin？—— 不，daemon JVM 的 stdin 是 /dev/null。脚本若用 stdin 会异常。Phase 2.2 接通：CLI 把 stdin 字节流转发到 daemon，daemon 把脚本的 System.in 包装成消费这些字节的 InputStream。
6. **daemon AppCDS**：daemon 自身启动 1.5s 还是慢。给 daemon 也归档一份独立的 jsa（含完整 Kotlin compiler）能砍到 ~600ms。

## 总结

Phase 2.1 用 ~600 行新代码（protocol + daemon + ipc client）打通了 ktx daemon 化的最小路径，**dev loop 实测加速 60%**——这就是项目立项时承诺的「像 uv/bun 一样的体验」最关键的一环。

剩下的 Phase 2.2 / 2.3 都是工程化加固（路由、并发、生命周期、多 Kotlin 版本），核心机制已经跑通。
