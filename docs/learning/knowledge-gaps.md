# AgentScope Java 学习盲点与补强记录

> 用途：持续记录学习 AgentScope Java 和响应式编程时遇到的“不知道、不确定、似懂非懂”。
> 原则：每个盲点都要落到一个具体问题、一处项目代码和一次可验证的练习上。

## 1. 状态约定

| 状态   | 含义                 |
|------|--------------------|
| 未开始  | 知道这是一个盲点，但还没有开始查资料 |
| 一知半解 | 能背概念，但无法结合项目代码解释   |
| 能解释  | 能用自己的话讲清原理，并指出对应代码 |
| 能落地  | 能独立修改代码、排查问题或编写测试  |
| 已消除  | 经过复盘后，能稳定应用，不再依赖提示 |

关闭一个盲点需要同时满足：

1. 能脱离资料解释核心概念；
2. 能在本项目中找到对应代码；
3. 完成过一次小实验、测试或问题排查；
4. 能说明常见误区和适用边界。

## 2. 盲点看板

| ID      | 主题                          | 当前状态 | 优先级 | 下一次动作                                 | 最后复盘       |
|---------|-----------------------------|------|-----|---------------------------------------|------------|
| GAP-001 | Reactor、`Flux`、`Mono` 与 SSE | 一知半解 | 高   | 完成第 3 节的算子和事件流练习                      | 2026-11-03 |
| GAP-002 | LLM、ReAct 循环与 Agent 心智模型    | 未开始  | 高   | 阅读 [Day 0](00-primer.md)              | -          |
| GAP-003 | `Msg`、内容块与 Tool Calling     | 未开始  | 高   | 阅读 [Day 2](day2-agent-assembly.md)    | -          |
| GAP-004 | State、Memory 与 RAG 的区别      | 未开始  | 中   | 阅读 [Day 3](day3-state-memory-rag.md)  | -          |
| GAP-005 | 多 Agent 编排与框架原语的边界          | 未开始  | 中   | 阅读 [Day 4](day4-multi-agent.md)       | -          |
| GAP-006 | Harness 工程能力                | 未开始  | 低   | 阅读 [Day 5](day5-harness-ecosystem.md) | -          |

## 3. GAP-001：Reactor、`Flux`、`Mono` 与 SSE

### 3.1 要回答的问题

- `Flux` 和响应式编程是什么关系？
- `Flux<T>`、`Mono<T>`、`List<T>`、Java `Stream<T>` 和 `CompletableFuture<T>` 有什么区别？
- 为什么声明了 `Flux` 的链，不订阅就不会执行？
- `map`、`flatMap`、`concatMap` 分别在什么时候使用？
- `merge`、`concatWith`、`takeUntil` 对流的结束条件有什么影响？
- `doFinally`、`doOnComplete`、`doOnCancel` 分别在什么时机执行？
- `Sinks.Many` 为什么必须显式 `complete()`？
- Spring MVC 或 WebFlux 在什么时机订阅 Controller 返回的 `Flux`？
- 为什么不能在响应式链路中随意调用 `.block()`？

### 3.2 最小心智模型

```text
Mono<T>  = 异步结果流，最多产生 1 个元素，可以成功结束或报错
Flux<T>  = 异步结果流，可以产生 0 到 N 个元素，可以成功结束或报错
```

`Flux` 不是“集合”，也不是“一定通过网络的流”。它是 Reactor 对异步多值序列的抽象。这个序列可能是几个内存元素，也可能是大模型持续产生的
Token 或 SSE 事件。

| 类型                     | 元素数量 | 执行模型             | 本项目的例子       |
|------------------------|-----:|------------------|--------------|
| `List<T>`              | 0..N | 数据已经计算完成         | Agent 配置列表   |
| Java `Stream<T>`       | 0..N | 拉取式，由终端操作触发      | 普通集合转换       |
| `CompletableFuture<T>` |    1 | 异步，通常表示一次任务结果    | 异步查询结果       |
| `Mono<T>`              | 0..1 | 推送式，可组合          | 一次保存或单项查询    |
| `Flux<T>`              | 0..N | 推送式，可组合，有完成和取消信号 | Agent 生命周期事件 |

响应式链的本质是“先组装处理规则，订阅后再执行”：

```java
Flux<String> chain = source
        .map(String::trim)
        .filter(text -> !text.isEmpty());

// 到这里只是定义了链，source 还不一定执行。

chain.subscribe(System.out::println); // 订阅后触发执行
```

Spring 返回 `Flux` 时，由框架负责订阅，所以 Controller 通常直接 `return flux`，不要自行调用 `subscribe()`。

### 3.3 本项目中的响应式链路

```text
ReActAgent.streamEvents(msg)
    → Flux<AgentEvent>
    → handle(...) 转为 Flux<Map<String, Object>>
    → Flux.merge(manualEvents, agentEvents)
    → concatWith(done)
    → ChatController.map(...)
    → Flux<ServerSentEvent<String>>
    → Spring 写入 HTTP SSE 响应
```

| 代码                             | 作用                    |
|--------------------------------|-----------------------|
| `agent.streamEvents(userMsg)`  | 产生 Agent 生命周期事件流      |
| `hook.getEventSink().asFlux()` | 把多 Agent 手工事件转成另一条流   |
| `Flux.merge(...)`              | 并发合并两条来源不同的事件流        |
| `concatWith(...)`              | 等前面的流结束后追加结束事件        |
| `takeUntil(...)`               | 遇到 `done` 事件后终止 SSE 流 |
| `doFinally(...)`               | 在完成、报错或取消后执行统一清理      |
| `Mono.fromCallable(...)`       | 把一次同步计算包装成单值异步结果      |
| `Sinks.Many`                   | 从普通代码向响应式流主动发送事件      |

重点阅读：

- [AgentRuntime.java](../../src/main/java/com/skloda/agentscope/runtime/AgentRuntime.java)
- [AgentEventMapper.java](../../src/main/java/com/skloda/agentscope/runtime/AgentEventMapper.java)
- [EventSink.java](../../src/main/java/com/skloda/agentscope/runtime/EventSink.java)
- [ChatController.java](../../src/main/java/com/skloda/agentscope/controller/ChatController.java)

### 3.4 先用好这七个算子

| 算子           | 输入到输出                                 | 典型用途           |
|--------------|---------------------------------------|----------------|
| `map`        | `T` → `R`                             | 同步转换事件         |
| `filter`     | `T` → `boolean`                       | 丢弃不需要的事件       |
| `flatMap`    | `T` → `Publisher<R>`                  | 异步展开，结果可能交错    |
| `concatMap`  | `T` → `Publisher<R>`                  | 异步展开，严格按输入顺序   |
| `merge`      | 多个 `Publisher<T>` → 一个 `Publisher<T>` | 合并实时事件来源       |
| `concatWith` | 一个流结束后接另一个流                           | 追加 `done`、收尾事件 |
| `takeUntil`  | 条件满足时取消上游                             | 遇到结束哨兵后关闭 SSE  |

`map` 只适合同步转换，而且不能返回 `null`。项目中需要“按条件过滤”时使用 `handle`，因为它可以选择不向下游发送元素。

### 3.5 当前最大的风险点

- 只创建 `Flux`，却误以为副作用已经执行；
- 在 Controller 内调用 `subscribe()`，导致 Spring 无法正确管理响应流；
- 在响应式链路中执行阻塞数据库、HTTP 或文件操作；
- 使用 `Flux.merge` 后，忘记其中一条流可能永远不结束；
- 只处理正常完成，没有处理异常和取消；
- 以为调用了 `doOnComplete` 就一定会执行；取消时可能需要 `doFinally`；
- 以为 `flatMap` 会保持输入顺序；
- 把 SSE、`Flux`、WebFlux 混成一件事。

这几个概念的关系是：

```text
Project Reactor 提供 Mono/Flux 抽象
Spring WebFlux/Reactive MVC 负责把 Flux 接入 HTTP
SSE 是 HTTP 上的一种响应格式
AgentRuntime 用 Flux 表达 Agent 事件序列
```

### 3.6 补强练习

1. 用 `StepVerifier` 写三个测试：
    - `Flux.just(1, 2, 3).map(x -> x * 2)` 的结果和完成信号；
    - `flatMap` 与 `concatMap` 在异步返回顺序上的差异；
    - `Flux.error(...)` 后的错误信号。
2. 对照 `AgentRuntime.stream(...)` 画出事件流生命周期：
    - 谁产生元素；
    - 谁合并流；
    - 哪条流决定 `done`；
    - 取消和异常时分两条流分别发生什么。
3. 给 `ChatController.sendMessage(...)` 写一份调用链笔记，解释 Spring 在哪里订阅 `Flux<ServerSentEvent<String>>`。
4. 人为构造“`Sinks.Many` 不调用 `complete()`”的场景，复现 HTTP 流不结束的问题，再解释项目为什么在
   `agentEvents.doFinally(...)` 中完成 Sink。

### 3.7 完成标准

- [ ] 能区分 `Mono`、`Flux`、`List`、Java `Stream`、`CompletableFuture`；
- [ ] 能解释“组装”和“订阅”的区别；
- [ ] 能说明 `map`、`flatMap`、`concatMap` 的差异；
- [ ] 能解释 `Flux.merge(...).concatWith(...)` 在项目中的目的；
- [ ] 能解释 `done` 事件、`takeUntil`、`doFinally` 和 Sink 完成之间的关系；
- [ ] 能用 `StepVerifier` 验证至少三个算子；
- [ ] 能指出响应式链路中的阻塞代码风险。

## 4. 学习记录

### 2026-11-03：确认 Reactor 是响应式编程

问题：`Flux` 等是不是响应式编程？

结论：是。`Flux` 是 Project Reactor 提供的响应式多值序列类型；AgentScope 2.0 使用 `Flux<AgentEvent>` 表达 Agent
执行期间产生的连续事件，Spring 再把这些事件转换为 SSE。

仍需补强：`Flux`/`Mono` 语义、算子选择、订阅模型、背压、取消与完成、在 Spring 中的执行线程。

## 5. 新盲点记录模板

添加新条目时复制下面模板，并在第 2 节看板中增加对应行。

```markdown
## GAP-XXX：盲点标题

### 问题来源

我在阅读哪个文件、执行什么操作时遇到该问题？

### 当前理解

用三到五句话写下自己的理解，不复制大段原文。

### 待回答的问题

- 问题 1
- 问题 2

### 对应代码

- 文件与入口方法

### 最小实验

通过测试、请求、日志或断点验证什么现象？

### 常见误区

- 误区 1
- 误区 2

### 完成标准

- [ ] 能用自己的话解释
- [ ] 能找到对应代码
- [ ] 完成实验
- [ ] 能说明边界和风险

### 复盘日期

YYYY-MM-DD
```
