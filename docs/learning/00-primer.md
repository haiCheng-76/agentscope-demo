# Day 0 · LLM / Agent 概念速成（Java 开发者视角）

> 时间：30–45 分钟。目标不是学会调参，而是建立**能和 AgentScope API 一一对应的词汇表**。
> 读完后你再看到 `ReActAgent`、`Toolkit`、`streamEvents`、`AgentStateStore`，应该能立刻说出“它对应我已知的哪个概念”。

---

## 1. 从“一次 HTTP 调用”理解 LLM

把大模型想象成一个**无状态函数**：

```java
String completion = llm.complete(List < Message > messages);
```

- 它不记得上一次调用（除非你把历史消息再传一遍）；
- 输出是**概率生成的 token 序列**，所以同样输入可能不同输出；
- 计费和限流都按 **token**（≈ 字/词片段）算；
- 它有**上下文窗口**上限（输入 + 输出总 token 数），超了必须截断或压缩。

AgentScope 里对应：

```java
Model model = ModelRegistry.resolve("dashscope:qwen-plus", context);
Msg answer = agent.call(List.of(userMsg), runtimeContext).block();
```

> 本项目统一走 `ModelFactory.createModel(...)` → `ModelRegistry.resolve(...)`，不直接 new 模型类。

---

## 2. 消息不是 String，是“角色 + 内容块”

一次对话是一串消息，每条消息有角色：

| 角色          | 谁说的 | 作用                |
|-------------|-----|-------------------|
| `SYSTEM`    | 开发者 | 设定人设、规则、输出格式      |
| `USER`      | 用户  | 本轮输入              |
| `ASSISTANT` | 模型  | 模型的回复（可能包含工具调用请求） |
| `TOOL`      | 工具  | 工具执行结果，回传给模型      |

内容也不一定是纯文本，而是**内容块**：

```
TextBlock       纯文本
ImageBlock      图片（URL 或 Base64）
AudioBlock      音频
ToolUseBlock    模型请求调用某个工具（工具名 + JSON 参数）
ToolResultBlock 工具执行结果
```

AgentScope 2.0 的写法：

```java
Msg msg = Msg.builder()
        .role(MsgRole.USER)
        .content(TextBlock.builder().text("你好").build())
        .build();
```

多模态就是往 `content` 里塞多个块，见 `model/MultiModalMessage.java:40-52`。

---

## 3. Function Calling：模型怎么“调用”你的 Java 方法

模型本身不能执行代码。它只会**输出一个结构化的“我想调用这个工具”**：

```json
{
  "tool": "get_weather",
  "arguments": {
    "city": "上海"
  }
}
```

框架负责：

1. 解析这个请求；
2. 在你的进程里找到对应方法并执行；
3. 把返回值包成 `ToolResultBlock` 塞回消息历史；
4. 再次调用模型，让它基于结果继续回答。

你只需要给方法加注解：

```java

@Tool(name = "get_weather", description = "查询城市天气")
public String getWeather(
        @ToolParam(name = "city", description = "城市名") String city) { ...}
```

`description` 是写给模型看的，**写得越清楚，模型越不容易调错**。

---

## 4. ReAct 循环：Agent 和“一次 LLM 调用”的本质区别

ReAct = **Reason + Act**。Agent 会循环“想 → 做 → 看结果”，直到认为可以回答：

```
用户提问
   │
   ▼
┌─────────────────────────────────────────────┐
│ 1. Reason：把 system + 历史 + 工具清单发给 LLM │
│    LLM 返回：要么最终回答，要么“调用工具 X”    │
└───────────────┬─────────────────────────────┘
                │ 需要工具
                ▼
┌─────────────────────────────────────────────┐
│ 2. Act：框架执行工具，拿到结果                 │
└───────────────┬─────────────────────────────┘
                │
                ▼
┌─────────────────────────────────────────────┐
│ 3. Observe：把 ToolResult 追加进消息历史       │
└───────────────┬─────────────────────────────┘
                │ 回到 1，直到 LLM 给出最终答案
                ▼
             最终回答
```

AgentScope 的 `ReActAgent` 就是这个循环的封装。循环里每一步都会发事件：

| 阶段   | 事件（本项目前端名）                | 含义                  |
|------|---------------------------|---------------------|
| 开始   | `agent_start`             | Agent 被调用           |
| 调用模型 | `llm_start`               | 第 N 次 LLM 调用        |
| 模型思考 | `thinking`                | 推理增量（开启 thinking 时） |
| 模型结束 | `llm_end`                 | token 用量、本轮工具请求     |
| 执行工具 | `tool_start` / `tool_end` | 工具名、参数、结果、耗时        |
| 结束   | `agent_end`               | 总调用次数、总耗时           |
| 文本   | `text`                    | 流式回答增量              |

**这是 Day 1 的全部重点**：你只要能把这些事件和一次 curl 的输出对上，就理解了 Agent 的执行模型。

---

## 5. 流式：为什么前端是一个字一个字出来的

普通调用：等模型生成完整答案，一次性返回。
流式调用：模型每生成一小段，就推一个增量（delta）给客户端。

AgentScope 2.0 用 Reactor：

```java
Flux<AgentEvent> events = agent.streamEvents(userMsg);
```

本项目再把 `AgentEvent` 映射成前端约定的 JSON（`runtime/AgentEventMapper.java`），通过 SSE 推给浏览器。`text` 事件里的
`content` 是**增量**，前端负责拼接——不是每次发全量。

---

## 6. 状态（State）vs 记忆（Memory）

这是新手最容易混的一对概念：

|      | State（状态）                                            | Memory（记忆）                                                          |
|------|------------------------------------------------------|---------------------------------------------------------------------|
| 是什么  | 一次会话的**完整可恢复快照**：消息历史、待执行工具、计划等                      | 从历史中**提炼出的长期信息**：用户偏好、事实、摘要                                         |
| 存哪   | `AgentStateStore`（内存 / JSON 文件 / Redis / MySQL / PG） | 长期记忆扩展（如 Bailian LTM）、Harness 的 memory 文件                           |
| 生命周期 | 随会话，可精确恢复                                            | 跨会话，按需检索                                                            |
| 本项目  | `AgentFactory.createStateStore()`                    | `personal-assistant` 的 `longTermMemory`；`memory-assistant`（Harness） |

**关键理解**：Agent 本身是**无状态**的。同一个 `sessionId` 能连续对话，是因为框架在每次调用前后从 `AgentStateStore`
读写状态，而不是因为 Agent 对象记住了什么。

在本项目里，带 `sessionId` 的请求还会复用一个缓存的 `ReActAgent` 实例（`SessionManagerService`），但**状态真正的权威来源仍是
StateStore**。这两件事不要混为一谈。

---

## 7. RAG：让模型“开卷考试”

RAG（Retrieval-Augmented Generation）= 先从你的文档里检索相关片段，再把片段塞进 prompt：

```
问题 ──► Embedding ──► 向量库相似度搜索 ──► Top-K 片段
                                              │
                    问题 + 片段 + 指令 ──► LLM ──► 回答
```

本项目：

- 入库：`KnowledgeService`（DashScope `text-embedding-v3`，1024 维）
- 检索：`RetrieveConfig(limit, scoreThreshold)`
- 两种模式：`GENERIC`（先检索再回答）与 Agentic（由 Agent 自己决定是否检索）

对应配置字段：`ragEnabled`、`ragMode`、`ragRetrieveLimit`、`ragScoreThreshold`。

---

## 8. 多智能体：为什么要拆成多个 Agent

一个 Agent 的 system prompt 越长、工具越多，模型越容易“分心”。拆分的动机：

- **职责单一**：搜索专家只负责搜索；
- **上下文隔离**：各专家的历史互不污染；
- **可组合**：串行、并行、路由、辩论、循环、状态机……

但要记住：**多智能体不是免费的**。每多一次 Agent 调用就多一轮 LLM 费用和延迟。Day 4 会给你一张选型表。

---

## 9. 人类在环（HITL）：为什么需要“审批”

有些工具是危险或不可逆的（发邮件、下单、生成正式报告）。Agent 调用前应暂停，等人确认：

```
Agent 想调用工具 ──► 中间件拦截 ──► 前端弹出审批卡片
                                        │
                              批准 ─────┴───── 拒绝
                                │               │
                        继续执行工具      把“拒绝”作为工具结果回传
```

本项目：`ApprovalMiddleware` + `POST /chat/approve`，示例 agent 是 `contract-review-workflow`。

---

## 10. 术语对照总表（背下来）

| LLM/Agent 概念 | AgentScope 2.0 API                           | 本项目位置                                |
|--------------|----------------------------------------------|--------------------------------------|
| 模型           | `Model` / `ModelRegistry`                    | `model/ModelFactory.java`            |
| 对话消息         | `Msg` + `TextBlock` 等                        | `service/AgentService.java:366`      |
| 工具           | `@Tool` / `@ToolParam` / `Toolkit`           | `tool/SimpleTools.java`              |
| Agent 循环     | `ReActAgent`                                 | `agent/AgentFactory.java:138`        |
| 流式事件         | `agent.streamEvents()` → `AgentEvent`        | `runtime/AgentRuntime.java:103`      |
| 事件转前端协议      | `AgentEventMapper`                           | `runtime/AgentEventMapper.java:64`   |
| 会话状态         | `AgentStateStore`                            | `agent/AgentFactory.java:76-92`      |
| 中间件          | `MiddlewareBase`                             | `middleware/ApprovalMiddleware.java` |
| 技能           | `SkillRepository` + `DynamicSkillMiddleware` | `agent/AgentFactory.java:298-313`    |
| RAG          | `builder.knowledge(...)`（v1，已废弃）             | `service/KnowledgeService.java`      |
| 工程化外壳        | `HarnessAgent`                               | `harness/HarnessAgentFactory.java`   |

---

## 11. 自测（读完立刻答）

1. 模型为什么“记不住”上一轮？本项目靠什么实现连续对话？
2. `ToolUseBlock` 和 `ToolResultBlock` 分别由谁产生？
3. `text` 事件是增量还是全量？前端为什么要拼接？
4. State 和 Memory 的区别是什么？各存在哪里？
5. 什么情况下应该拆成多 Agent？代价是什么？

答不上来就回到对应小节。带着这些问题进入 [Day 1](day1-agent-lifecycle.md)。
