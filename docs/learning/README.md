# 用这个项目，5 天吃透 AgentScope Java 2.0

> 面向：**Java / Spring 熟练、LLM 应用经验为零**的开发者。
> 目标：5 天后你能独立读懂本项目任何一条链路，并能从零写出一个带工具、带会话、可观测的 Agent。
> 基线版本：AgentScope Java **2.0.3** + Spring Boot 3.5.14 + Java 17。

---

## 1. 为什么拿这个项目当教材

市面上不缺 AgentScope 的 "Hello World"，缺的是**一条完整工程链路**。本项目刚好补齐：

| 教学价值           | 具体证据                                                                                     |
|----------------|------------------------------------------------------------------------------------------|
| 从最小闭环到生产形态都有   | `chat-basic`（最小）→ `tool-test-simple`（工具）→ 10+ 种多智能体 → `HARNESS`（工程化外壳）                   |
| 每一步都能“看见”      | Spring SSE + 右侧调试面板，把 `agent_start / thinking / tool_start / text / done` 实时画出来          |
| 有可回归的验收标准      | `src/test/java` 下 **81 个测试类、379 个 `@Test`**，几乎每个概念都有对应测试                                 |
| 覆盖 2.0 的全部关键迁移 | `streamEvents`、`AgentStateStore`、`Middleware`、`SkillRepository`、`HarnessAgent` 都在代码里真实使用 |
| 配置驱动，改造成本低     | 新增 agent 主要改 `config/agents.yml`，不用碰框架源码                                                 |

**重要认知**：本项目不是“AgentScope 官方最佳实践”，而是**一个把框架能力用满的演示工程**。其中很多多智能体 runtime 是“用
`ReActAgent` + `Flux` 手工编排”的示范，不是框架内置原语。学会区分“框架能力”和“工程封装”，是这 5 天最重要的收获（Day 4 会专门讲）。

---

## 2. 先建立三层心智模型

读代码前，先把仓库切成三层，后面所有内容都往这三层里挂：

```
┌─────────────────────────────────────────────────────────────┐
│ 第三层：工程外壳（Spring / SSE / 配置 / 前端）                │
│ ChatController · AgentService · SessionManagerService        │
│ AgentConfigService · ToolRegistry · EventSink                │
├─────────────────────────────────────────────────────────────┤
│ 第二层：编排层（谁在什么时候调用哪个 Agent）                  │
│ AgentRuntimeFactory → AgentRuntime / SequentialRuntime /     │
│ ParallelRuntime / DebateRuntime / LoopRuntime / MsgHubRuntime│
│ / StateGraphRuntime / SubAgent*Runtime / HarnessRuntime      │
├─────────────────────────────────────────────────────────────┤
│ 第一层：Agent 内核（AgentScope 框架本体）                     │
│ ReActAgent · Model(ModelRegistry) · Msg/ContentBlock ·       │
│ Toolkit/@Tool · AgentStateStore · AgentEvent · Middleware    │
└─────────────────────────────────────────────────────────────┘
```

一句话记忆：

- **第一层决定“一个 Agent 怎么思考和行动”**；
- **第二层决定“多个 Agent 怎么排队、路由、辩论、交接”**；
- **第三层决定“用户怎么通过 HTTP 看到这一切”**。

Day 1–2 主攻第一层，Day 3 打通第一/三层，Day 4 主攻第二层，Day 5 看第三层顶端（Harness）和生态出口。

---

## 3. 五天安排

每天约 **3–4 小时**，建议按“跑 → 看事件 → 读代码 → 改 + 测”四步走。

| 天                                  | 主题             | 你会亲手做什么                                                | 产出                       |
|------------------------------------|----------------|--------------------------------------------------------|--------------------------|
| [Day 1](day1-agent-lifecycle.md)   | 一次对话的完整生命周期    | 用 curl 抓 SSE，画出事件时序图，对照 `AgentEventMapper` 认全 12 个核心事件 | 一张你自己的事件时序图              |
| [Day 2](day2-agent-assembly.md)    | Agent 是怎么组装出来的 | 新增一个 `@Tool`、一个新 skill、一个新 agent，并写一个工具单测              | 一个可用的 `text-stats` agent |
| [Day 3](day3-state-memory-rag.md)  | 会话、状态、记忆、RAG   | 用 sessionId 连续对话、查看落盘 JSON、重启后恢复、跑一次知识库问答              | 一份状态文件结构笔记               |
| [Day 4](day4-multi-agent.md)       | 11 种多智能体编排     | 每种模式各跑一次，对照 runtime 代码读出事件顺序；改造一个 pipeline             | 一张“模式 → runtime → 事件”对照表 |
| [Day 5](day5-harness-ecosystem.md) | Harness + 生态出口 | 跑 Plan/Memory/Skill 自学习/压缩/沙箱；试 A2A、AG-UI、MCP profile  | 一个 Harness 能力清单          |
| [毕业项目](capstone.md)                | 独立造一个 Agent    | 会议纪要助手：工具 + 技能 + 会话 + 可选多智能体 + 测试                      | 可演示的完整功能                 |

配套材料：

- [00-primer.md](00-primer.md)：LLM/Agent 概念速成。**Day 0 先读 30 分钟**，后面不慌。
- [cheatsheet.md](cheatsheet.md)：API、事件、配置字段、排错总表。每天开着当参考。
- [knowledge-gaps.md](knowledge-gaps.md)：个人知识盲点看板、深度笔记和复盘记录。
- [knowledge-notes.md](knowledge-notes.md)：Java 与 AgentScope 知识点卡片和复习清单。

---

## 4. 开工前自检（10 分钟）

在项目根目录执行：

```bash
java -version          # 期望 17 或更高
mvn -v                 # 期望 3.9+
echo $DASHSCOPE_API_KEY   # 必须非空

# 编译（第一次会下载依赖）
mvn -q -DskipTests compile

# 启动
mvn spring-boot:run
```

另开一个终端验证最小链路：

```bash
curl -N -sS -X POST http://localhost:8081/chat/send \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{"agentId":"chat-basic","message":"用一句话介绍你自己"}'
```

看到连续的 `data: {"type":"..."}` 行（最后一条是 `{"type":"done"}`）就算通过。

> 注意：
> - 端口是 **8081**，不是 8080。
> - Node.js/npx 缺失只影响 MCP 演示，不影响启动；Docker 只影响 `sandbox-demo`。
> - 测试大多不需要 API Key：`mvn test` 可离线跑；需要真实模型的实验会单独标注。

### 建议的两个终端

```bash
# 终端 A：跑应用
mvn spring-boot:run

# 终端 B：发请求 / 跑测试 / 看日志
curl ...
mvn test -Dtest=AgentEventMapperTest
```

---

## 5. 每天怎么学：四步法

1. **先跑**：按文档里的命令把现象跑出来，不急着看代码。
2. **看事件**：打开浏览器 `http://localhost:8081` 的右侧调试面板，或看 curl 的 SSE 输出，记录事件顺序。
3. **再读代码**：带着“这个事件是谁发的”去读指定文件与行号，不要通读整个类。
4. **改一处 + 测一次**：每天至少做一次小改动（改配置、加工具、换 runtime），并跑对应测试或 curl 验证。

> 不要试图从 `AgentScopeDemoApplication.java` 一路读到 `ChatController` 结束。按“现象 → 事件 → 代码”倒着读，效率高得多。

---

## 6. 每天学习前先看一眼“检查点”

每个 Day 文档末尾都有 **自测题** 和 **今日产出**。如果自测题答不上来，不要进入下一天——后面的内容会建立在它上面。

| 天     | 进入下一天前，你必须能回答                                                      |
|-------|--------------------------------------------------------------------|
| Day 1 | `text` / `thinking` / `tool_start` / `done` 分别由谁发出？为什么 `done` 不会丢？ |
| Day 2 | 新增一个工具需要改几个地方？`userTools`、`systemTools`、`skills` 有什么区别？            |
| Day 3 | 不带 sessionId 和带 sessionId 的两条路径差在哪？状态到底存在哪里？                       |
| Day 4 | 哪些模式是框架原语，哪些是本项目手写的 runtime？                                       |
| Day 5 | `HarnessAgent` 相比 `ReActAgent` 多了什么？什么时候该用它？                       |

---

## 7. 和仓库里已有文档的关系

| 文档                         | 定位                | 怎么用                    |
|----------------------------|-------------------|------------------------|
| `README.md`                | 功能清单 + 快速开始       | 当“功能地图”，不要当教程          |
| `CLAUDE.md` / `AGENTS.md`  | 给 AI 编码助手看的结构说明   | 查文件职责、字段含义很快           |
| `ROADMAP.md`               | 2.0 能力差距分析 + 演进记录 | Day 4/5 想深入“为什么这样设计”时读 |
| `docs/superpowers/specs/*` | 每个功能的设计稿          | 某个模块看不懂时，找对应日期的 spec   |
| `docs/superpowers/plans/*` | 实施计划（含验证命令）       | 想看“当时怎么一步步做的”时读        |
| **`docs/learning/*`（本目录）** | **为你设计的学习路径**     | 按天执行                   |

---

## 8. 版本提醒（避免被旧教程带偏）

网上大量 AgentScope 教程是 1.x / 0.2.x 的，和 2.0.3 有硬差异：

| 旧写法                               | 2.0.3 现状                                                | 本项目对应                             |
|-----------------------------------|---------------------------------------------------------|-----------------------------------|
| `agent.stream()`                  | 已废弃，用 `agent.streamEvents()`                            | `AgentRuntime.java:103`           |
| `Pipeline<Msg>` / `MsgHub` 作为框架原语 | 已移除，本项目用 `ReActAgent + Flux` 手工编排                       | `runtime/*Runtime.java`           |
| `Hook` 接口                         | 改为 `Middleware`；本项目的 `ObservabilityHook` 只是 EventSink 桥 | `hook/ObservabilityHook.java`     |
| `Session` / `SessionManager`      | 改为 `AgentStateStore`                                    | `AgentFactory.createStateStore()` |
| `SkillBox`                        | 改为 `SkillRepository` + `DynamicSkillMiddleware`         | `AgentFactory.java:298-313`       |
| 直接 `new DashScopeChatModel(...)`  | 统一走 `ModelRegistry.resolve(...)`                        | `ModelFactory.java:42-53`         |
| `agent.reply()`                   | `agent.call(...)` / `agent.streamEvents(...)`           | `AgentRuntime.java`               |

遇到网上示例跑不通，先回到这张表对照。

---

## 9. 学完之后你能做什么

- 给现有项目加一个新工具 / 新技能 / 新 agent（不改框架代码）；
- 看懂任何一条 SSE 事件的来龙去脉，独立排查“为什么没输出 / 为什么没调工具”；
- 在 `SINGLE / SEQUENTIAL / ROUTING / HANDOFFS / DEBATE / LOOP / STATE_GRAPH / MSG_HUB / SUBAGENT_*` 之间做选型；
- 判断什么时候该上 `HarnessAgent`（沙箱、计划、长期记忆、技能自学习）；
- 用 `AgentStateStore` 做会话持久化，并能切到 Redis/MySQL/PostgreSQL；
- 通过 A2A / AG-UI / MCP 把 Agent 暴露给别的系统。

开始吧 → [00-primer.md](00-primer.md)
