# Day 1 · 一次对话的完整生命周期

> 时长：约 3 小时
> 目标：不看代码也能说出 `agent_start → … → done` 的顺序；看代码能指出每个事件由哪个类、哪一行发出。

---

## 今日目标（可检验）

- [ ] 用 curl 抓到一次完整 SSE 流，并保存成文件；
- [ ] 能区分 `text` / `thinking` / `tool_start` / `tool_end` / `done` 的来源；
- [ ] 能画出“一个 sessionId 请求”从 Controller 到 Agent 的调用链；
- [ ] 能解释：为什么 `done` 事件不会因为 Flux 未完成而丢失。

---

## 0. 开工（3 分钟）

```bash
# 终端 A
mvn spring-boot:run

# 终端 B：确认服务活着
curl -s http://localhost:8081/api/agents | head -c 300
```

---

## 1. 概念映射（先看表，再动手）

| 你要观察的东西     | AgentScope 2.0 机制                                   | 本项目代码                                  |
|-------------|-----------------------------------------------------|----------------------------------------|
| 一次 Agent 调用 | `agent.streamEvents(userMsg)` 返回 `Flux<AgentEvent>` | `runtime/AgentRuntime.java:103`        |
| 事件类型        | `AgentEventType`（约 30 种）                            | `runtime/AgentEventMapper.java:70-100` |
| 转成前端协议      | 纯函数映射 `Map<String,Object>`                          | `runtime/AgentEventMapper.java:64`     |
| 多智能体手动事件    | Reactor `Sinks.Many`                                | `runtime/EventSink.java:19-37`         |
| 两条流合并       | `Flux.merge(...)`                                   | `runtime/AgentRuntime.java:125-135`    |
| SSE 出口      | `ServerSentEvent<String>`                           | `controller/ChatController.java:79-92` |

---

## 2. 动手实验

### Lab 1.1：抓一次纯对话的 SSE

```bash
curl -N -sS --max-time 90 -X POST http://localhost:8081/chat/send \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{"agentId":"chat-basic","message":"用三句话解释什么是 ReAct Agent"}' \
  > /tmp/day1-chat.sse

# 看事件类型序列（去掉 data: 前缀里的 JSON，只抽 type）
grep '^data:' /tmp/day1-chat.sse | sed 's/^data://' | grep -o '"type":"[^"]*"' | uniq -c
```

**预期**（顺序可能因是否触发工具而略有差异）：

```
agent_start → llm_start → thinking* → llm_end → text* → agent_end → done
```

**要观察的细节**：

- `text` 有很多条，每条只是一个增量片段；
- `thinking` 只在 `enableThinking: true` 时出现；
- 最后一定有且只有一个 `done`。

> 如果 `grep` 在 Windows 上不方便，直接把 `/tmp/day1-chat.sse` 用编辑器打开，肉眼扫 `"type"`。

### Lab 1.2：抓一次工具调用

```bash
curl -N -sS --max-time 90 -X POST http://localhost:8081/chat/send \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{"agentId":"tool-test-simple","message":"现在几点？再用工具算一下 128+256+512"}' \
  > /tmp/day1-tool.sse

grep '^data:' /tmp/day1-tool.sse | sed 's/^data://' | grep -o '"type":"[^"]*"' | uniq -c
```

**预期**：出现两轮或更多 `llm_start … tool_start … tool_end … llm_start … text … agent_end`。

**重点看 `tool_start` 的 JSON**：里面有工具名和模型生成的参数。再对比 `tool_end` 的结果。这就是“模型决定调什么、框架执行、结果回传”的全过程。

### Lab 1.3：对比“有 sessionId / 无 sessionId”

```bash
# 第一轮
curl -N -sS -X POST http://localhost:8081/chat/send \
  -H 'Content-Type: application/json' \
  -d '{"agentId":"chat-basic","message":"我叫小明，请记住","sessionId":"day1-demo"}'

# 第二轮，同一个 sessionId
curl -N -sS -X POST http://localhost:8081/chat/send \
  -H 'Content-Type: application/json' \
  -d '{"agentId":"chat-basic","message":"我叫什么？","sessionId":"day1-demo"}'
```

**预期**：第二轮能答出“小明”。
再把 `sessionId` 去掉重问一次——大概率答不出来（无状态路径每次新建 `InMemoryAgentStateStore`）。

> 带 sessionId 的请求会走 `AgentService.createSessionStreamFlux`，复用缓存的 Agent 实例；不带则走 `createRuntime`
> ，每次新建。两条分支都在 Day 3 展开。

### Lab 1.4：给事件流加一行日志（可选，10 分钟）

打开 `src/main/java/com/skloda/agentscope/runtime/AgentRuntime.java`，在 `mapAgentEvent`（约 195 行）里临时加：

```java
log.info("[event] type={}", event.getType());
```

重启、再跑 Lab 1.1，控制台会按顺序打印事件类型。**验证完请删掉这行**，或保留在你自己的分支上。

---

## 3. 精读路线（带着问题读，不要通读）

### 3.1 `controller/ChatController.java:124-152`

读什么：

- `sendMessage` 如何接收 `ChatRequest`；
- 为什么返回 `Flux<ServerSentEvent<String>>`；
- `.takeUntil(this::isDoneEvent)` 的作用；
- `.map(this::sseEvent)` 如何把 `Map` 包成 SSE。

回答：

1. 如果 Agent 的 Flux 永远不 complete，HTTP 响应会结束吗？
2. `done` 事件在这里扮演什么角色？

> 提示：`takeUntil` 让前端在收到 `done` 时主动结束，不依赖上游 complete。这也是为什么 `done` 必须可靠送达。

### 3.2 `service/AgentService.java:131-177`

读什么：

- 方法入口为什么有 11 个参数（以及前面 5 个重载只是补默认值）；
- `HARNESS` 类型为什么被旁路到 `HarnessAgentService`；
- `ROUTING + sharedBlackboard` 为什么被旁路到 Supervisor；
- 第 166-173 行：有/无 `sessionId` 的分叉。

回答：

1. 普通 `SINGLE` 请求最终调用的是 `runtimeFactory` 的哪个方法？
2. 什么条件下不会走 `AgentRuntimeFactory`？

### 3.3 `runtime/StreamingAgentRuntime.java`

全读（很短）。这是所有 runtime 的统一契约：

```java
Flux<Map<String,Object>> stream(Msg msg);
ObservabilityHook getHook();
void close();
```

回答：为什么多智能体 runtime 也要实现同一个接口？

### 3.4 `runtime/AgentRuntime.java:79-142`

**这是今天最重要的一段。** 读什么：

- 第 91 行：清理上次中断留下的孤立 ToolUse（理解“状态可恢复”的副作用）；
- 第 95 行：拿到手动事件流；
- 第 103 行：`agent.streamEvents(userMsg)`；
- 第 104-109 行：为什么用 `handle` 而不是 `map`（映射可能返回 `null`）；
- 第 125-135 行：`Flux.merge` + `concatWith("done")`；
- 第 112-126 行的注释：**必读**，解释了 `EventSink.complete()` 为什么必须放在 `agentEvents.doFinally` 里。

回答：

1. 自动事件和手动事件分别来自哪里？
2. 为什么不能把 `sink.complete()` 放在外层 Flux 的 `doOnComplete`？
3. `Flux.merge` 的两条流各自什么时候结束？

### 3.5 `runtime/AgentEventMapper.java:64-105`

读什么：

- `apply(AgentEvent)` 的总 switch；
- 块边界事件（`TEXT_BLOCK_START/END`）为什么返回 `null`；
- 异常为什么被吞掉并转成 `error` 事件。

回答：

1. 列出 `text`、`thinking`、`llm_start`、`llm_end`、`tool_start`、`tool_end`、`agent_start`、`agent_end` 各自对应的
   `AgentEventType`；
2. 如果新增一个事件类型，你需要改几处？

### 3.6 `runtime/EventSink.java:19-37`

读什么：`Sinks.Many.multicast().onBackpressureBuffer()` 的用法，以及 `emit(...)` 的容错。

回答：多个订阅者时，事件会被广播还是竞争消费？

### 3.7 `runtime/AgentRuntimeFactory.java:41-61, 283-294`

读什么：按 `AgentType` 分发的 switch；`SINGLE` 分支如何创建 `ObservabilityHook` 和 `AgentRuntime`。

回答：如果你新增一种 `AgentType`，最少要改哪两个地方？

---

## 4. 画一张你自己的时序图（15 分钟）

不要抄本文档，用你抓到的真实事件填充：

```
浏览器/curl
   │ POST /chat/send
   ▼
ChatController.sendMessage
   │ agentService.createStreamFlux(...)
   ▼
AgentService.createStreamFlux          ← 在这里判断：harness? supervisor? session?
   │ runtimeFactory.createRuntime / createRuntimeWithSession
   ▼
AgentRuntimeFactory                     ← 按 type 选 runtime
   │ new AgentRuntime(agent, hook, ...)
   ▼
AgentRuntime.stream
   │ hook.getEventSink().asFlux() ──────────────┐
   │ agent.streamEvents(userMsg) ──┐            │
   │                               ▼            ▼
   │                        AgentEventMapper  手动事件
   │                               └──── Flux.merge ────┐
   │                                                    ▼
   │                                       map → SSE → 浏览器
   └─ doFinally → sink.complete() → concatWith(done)
```

在图上标出：**哪一步可能阻塞、哪一步可能丢事件、`done` 从哪里来**。

---

## 5. 今日产出

- [ ] `/tmp/day1-chat.sse` 和 `/tmp/day1-tool.sse` 两个样本；
- [ ] 一张手绘/文字版时序图（贴到你的笔记里）；
- [ ] 一句自己的总结：*“一个 Agent 请求，本质上是 ______ 条流的合并。”*

---

## 6. 自测题

1. `thinking` 事件为什么有时有、有时没有？
2. `tool_start` 和 `tool_end` 之间，框架做了什么？
3. `Flux.merge(sinkEvents, agentEvents)` 中，如果 `sinkEvents` 永远不 complete，会发生什么？
4. 为什么 `AgentEventMapper` 里块边界事件返回 `null` 而不是直接 `filter` 掉？
5. `takeUntil(isDoneEvent)` 去掉后，前端会看到什么现象？

<details>
<summary>参考答案（先自己答，再展开）</summary>

1. 由模型配置 `enableThinking` 决定；关闭时上游不产生 `THINKING_BLOCK_DELTA`。
2. 框架解析模型给出的工具调用 → 在 `Toolkit` 中找到方法 → 反射执行 → 把结果包成 `ToolResultBlock` 追加进消息历史 →
   再次调用模型。
3. 合并后的 Flux 永远不 complete；HTTP 连接会一直挂着。本项目用 `takeUntil(done)` 兜底，同时把 `sink.complete()` 放进
   `agentEvents.doFinally` 保证正常路径能结束。
4. `AgentScope` 的 `Flux.map` 不允许返回 `null`（会 NPE）；`handle` 可以“不发任何元素”，等价于过滤。
5. 若上游 Flux 不主动 complete，浏览器收不到流结束信号，表现为“回答完了但输入框一直转圈/连接不释放”。

</details>

---

## 7. 排错

| 现象                           | 先查                                 |
|------------------------------|------------------------------------|
| 连不上 8081                     | `mvn spring-boot:run` 是否还在跑；端口是否被占 |
| 401 / 模型报错                   | `DASHSCOPE_API_KEY` 是否导出到**当前终端**  |
| 没有任何 `text`，只有 `agent_end`   | 模型名是否有效；看后端日志                      |
| 有 `tool_start` 没有 `tool_end` | 工具抛异常；看日志里的堆栈                      |
| curl 一直不结束                   | 去掉 `--max-time` 前先确认 `done` 是否出现   |

---

## 8. 明天见

今天你知道了“事件从哪来”。明天回答“**Agent 本身是怎么被组装出来的**”——`ReActAgent.builder()` 里到底塞了什么。
→ [Day 2 · Agent 组装、工具与技能](day2-agent-assembly.md)
