# Phase 2.3：流式 stdin + `--lock` via daemon

> 时间：2026-05-31
> 主题：daemon 模式收尾。补上 stdin 透传与 lock 流程，daemon 体验完整。

## 这一波要解决什么

Phase 2.2 上线后留了两个明显短板：

1. **`--daemon` 模式下脚本读不到 stdin**——daemon 进程的 System.in 是 /dev/null。
2. **`--lock` 与 `--daemon` 互斥**——lock 流程要禁用编译缓存才能让
   RecordingResolver 拿到记录，daemon 共享缓存场景下逻辑没设计。

Phase 2.3 把这两件事补上。

## 关键设计

### 1. 协议：StdinChunk 流式帧

新加 RequestEnvelope.body 的一个分支：

```proto
oneof body {
    RunRequest run = 10;
    ShutdownRequest shutdown = 11;
    StatusRequest status = 12;
    StdinChunk stdin_chunk = 13;  // ← 新增
}

message StdinChunk {
    bytes data = 1;
    bool eof = 2;
}
```

交互：

```
client → server:
    RequestEnvelope { run: RunRequest{...} }
    RequestEnvelope { stdin_chunk: { data: ... } }   ← 0..N 个
    RequestEnvelope { stdin_chunk: { eof: true } }   ← 收尾

server → client:
    ResponseEnvelope { run_event: stdout/stderr } ...
    ResponseEnvelope { run_event: exit }
```

帧机制完全复用——RequestEnvelope 长度前缀 + protobuf。client 主线程发完
RUN 帧后启 stdin pump 线程，daemon 端 worker 1 接管 RUN 处理、worker 2
（pump）持续从同一 socket 读后续帧。

### 2. RoutedInputStream

类比 RoutedOutputStream（Phase 2.2）：daemon 启动时一次性 setIn 到 routed
版本，每个 worker 进入 handleRun 时 bind 自己的 PipedInputStream，离开时
unbind。脚本里的 `readLine()` / `System.in.read()` 自动落到对的 pipe。

### 3. PipedInputStream / PipedOutputStream 桥

stdin 数据流向：

```
client.stdin → pump 线程 → socket frames → daemon stdin pump → PipedOutputStream
                                                                  ↓
                                                            PipedInputStream → routed System.in → 脚本
```

PipedInputStream 缓冲区 64KB（默认 1KB 太小，长行容易卡）。client 端用
4KB 一段打 frame，daemon 端写 pipe；脚本 read 阻塞等下一段。`eof=true` 来
时 daemon 关 pipe，脚本 readLine 拿 null。

### 4. lock 模式语义简化

`ktx run --lock`（非 daemon 路径）原本语义是「跑 + 刷 lockfile」——脚本主体
执行 + RecordingResolver 抓依赖 + 写 lockfile。daemon 模式下这个语义有冲突：

- daemon 共享编译缓存。脚本之前跑过 → main-kts 缓存命中 → resolver 不被调
  → RecordingResolver 拿不到记录。
- 要让 RecordingResolver 工作，必须临时禁用编译缓存（改全局 system property）。
- daemon 是并发的，禁用缓存影响其他 worker。

权衡：daemon 模式下 `--lock` 退化为 **只刷 lockfile，不跑脚本**——与
`ktx lock` 子命令的语义一致。仍用 `lockSerializer` 全局锁串行所有 LOCK
请求，避免缓存属性变更冲突。

用户想要「跑 + 刷 lockfile」的话用非 daemon 路径（`ktx run --lock` 不带
`--daemon`）。

### 5. **默认不 forward stdin**

最先撞到的性能坑。原本设计：daemon 模式下 client **总是**启 stdin pump 线程
转发 ktx 自己的 stdin。代价：`hello.main.kts`（不读 stdin）启动从 120ms
退化到 500ms。

原因：pump 是 daemon 线程，阻塞在 `System.in.read()` native call。主线程
完成 RPC、`exitProcess` 后，**JVM shutdown 等 pump 线程的 native call 解开**，
mise exec 下要 ~380ms。

修复：`--forward-stdin` flag（也可 `KTX_FORWARD_STDIN=1` 环境变量），默认
关。脚本不读 stdin 是绝大多数场景，该体验不退化。需要 stdin 的脚本显式开。

## 项目布局变化

```diff
modules/protocol/src/main/proto/ktx.proto
+ message StdinChunk { bytes data; bool eof; }
+ RequestEnvelope.oneof.stdin_chunk = 13
+ ResolverMode.LOCK = 2
  RunRequest.stdin (bytes) 字段保留兼容老协议（不再优先使用）

modules/daemon/src/main/kotlin/.../daemon/
+ RoutedInputStream.kt          ThreadLocal stdin
  DaemonServer.kt
    + serve() 加 routedIn / setIn
    + handleRun 加 PipedInputStream + 后台 stdin pump 线程
    + runLockMode（synchronized lockSerializer）

modules/cli/src/main/kotlin/.../cli/
  ipc/DaemonClient.kt
    + run(stdin: InputStream?) 取代 ByteArray
    + startStdinPump 后台线程发 chunks
  command/RunCommand.kt
    + --forward-stdin flag（默认 false）
    + pickResolver: --lock 走 ResolverMode.LOCK
```

## 性能矩阵

| 场景 | 用时 | vs Phase 2.2 |
|---|---|---|
| daemon 暖 + hello | **120ms** | 持平 |
| frozen via daemon | **200ms** | 持平 |
| lock via daemon（首次解析） | 5.3s | n/a，新功能 |
| `--forward-stdin` 多行 stdin | 工作正常 | n/a，新功能 |
| `--forward-stdin` 1000 行 stdin | 工作正常 | n/a，新功能 |

## 这一波撞到的坑

1. **stdin pump 拉低不读 stdin 脚本的性能 4 倍**。pump 阻塞在 native read，
   JVM shutdown 等它解开。修：默认不启 pump，`--forward-stdin` 显式开。

2. **lock 模式编译缓存命中导致 RecordingResolver 空**。daemon 共享缓存，
   不能像非 daemon 路径那样直接复用 LockingFlow.lockAndOptionallyRun
   (skipExecution=false)。退化为 skipExecution=true（只 lock 不跑），与
   `ktx lock` 一致。

3. **daemon 端 ExitEvent 必须在 routed unbind 之后发**。前一版写在 finally
   外面但还在 routedOut bind 状态下，结果 ExitEvent 帧本身可能被错误地
   sink 进 socket（如果 Frames.write 内部用到 stdout/stderr 调试）。修：
   把 ExitEvent 移到 unbind 之后的独立 try 块。

4. **lock 跑完报"daemon 提前关闭连接"** —— 因为 daemon 跑完没正常发
   ExitEvent。原因是 LockingFlow.runInline 的 wrapForLockOnly 路径搭配
   bypassCompiledCache 触发了某个 codepath 让 worker 异常退出。修复方案
   就是上面 #3：调整 finally 与 ExitEvent 顺序后好了。

5. **重复 Edit 留下重复 import / declaration** 又出现。Edit 工具对
   多次插入相同段落不防御性去重。需要养成「写新代码用 Write 整片覆盖、
   小改用 Edit」的习惯。

## 已知遗留 / 未来优化

1. **`--forward-stdin` 仍有 ~300ms shutdown 延迟**。要消除得用 NIO
   non-blocking read + 主动取消，工程量较大。等用户实际反馈再做。

2. **stdin pump 线程在 daemon 端永久跑**直到客户端关连接。脚本不读
   stdin 也启 pump、读 socket、看到 client.close 退出 —— 浪费几个 KB
   内存 / 一次线程切换。优化方向：daemon 看 RunRequest 里有 stdin 信号
   再启 pump。需要协议加字段。Phase 2.4+ 再说。

3. **`ktx run --daemon -` (stdin 是源码) 与 `--forward-stdin` 混用怪异**：
   stdin 既是源码源也是脚本输入源，没法兼得。文档说清楚就行。

4. **协议版本仍是 v1**。Phase 2.3 的字段是 proto3 兼容性扩展，老 client
   发不带 `stdin_chunk` 的 RUN 仍能跑。等到不兼容改动再升 v2。

## 总结

Phase 2.3 把 daemon 体验补完整：可读 stdin（按需开）、可 lock。配合 Phase
2.2 的路由 / 并发 / 生命周期，daemon 已经是真正可日常用的形态。

剩下 daemon 可加的小特性（多 Kotlin 版本、`daemon logs` 子命令、流式 stdin
shutdown 优化）都在「等用户反馈再做」名单上。Phase 2 子计划至此告一段落。

下一站 **Phase 3.1：`ktx compile --self-contained`**——用 jpackage + jlink
把 ktx compile 产物封装成「带最小 JRE 的目录树」，end user 连 JRE 都不用装。
