# Day 4 · 多智能体编排：11 种模式一次讲透

> 时长：约 4 小时
> 目标：拿到任何需求，能判断该用哪种编排模式；看到任何一条多智能体事件，能说出是哪个类发的。

---

## 今日目标（可检验）

- [ ] 能区分**框架原生组合**与**本项目手写 runtime**；
- [ ] 能说出 11 种模式各自适合什么任务、代价是什么；
- [ ] 能读懂 `SequentialRuntime` 的 90 行代码，并自己改一处；
- [ ] 能根据事件序列反推出当前跑的是哪种模式；
- [ ] 能解释 Supervisor + 共享黑板解决了什么问题。

---

## 1. 先建立最重要的认知：这里有两类“多智能体”

AgentScope 2.0 **移除了 1.x 的 `Pipeline<Msg>` / `MsgHub` 等编排原语**。所以本项目里的多智能体分成两类：

### A. 框架原生组合（框架负责调度）

| 模式         | 机制                                            | 代表 agent                          |
|------------|-----------------------------------------------|-----------------------------------|
| `ROUTING`  | 子 Agent 注册为 `SubAgentTool`，主 Agent 通过工具调用选择专家 | `smart-router`、`super-supervisor` |
| `HANDOFFS` | 同上，但用显式触发规则（关键词/意图）约束交接                       | `customer-service`                |
| `HARNESS`  | `HarnessAgent` 自带的 subagents 机制               | Day 5 讲                           |

代码入口：`composite/CompositeAgentFactory.java:174, 284`（`new SubAgentTool(provider, subAgentConfig)`）。

### B. 本项目手写编排（`ReActAgent` + Reactor）

| 模式             | Runtime              | 代表 agent                   |
|----------------|----------------------|----------------------------|
| `SEQUENTIAL`   | `SequentialRuntime`  | `doc-analysis-pipeline-v2` |
| `PARALLEL`     | `ParallelRuntime`    | `parallel-analysis-v2`     |
| `DEBATE`       | `DebateRuntime`      | `debate-review-v2`         |
| `LOOP`         | `LoopRuntime`        | `copywriter-refiner-v2`    |
| `MSG_HUB`      | `MsgHubRuntime`      | `expert-roundtable-v2`     |
| `SUBAGENT_SEQ` | `SubAgentSeqRuntime` | `report-generator-v2`      |
| `SUBAGENT_PAR` | `SubAgentParRuntime` | `project-manager-v2`       |
| `STATE_GRAPH`  | `StateGraphRuntime`  | `order-fulfillment`        |

这些类都实现同一个接口：

```java
public interface StreamingAgentRuntime {
    Flux<Map<String,Object>> stream(Msg msg);
    ObservabilityHook getHook();
    void close();
}
```

**学习重点**：不要把它们当成“框架魔法”。打开 `SequentialRuntime.java`，你会发现它就是：

1. 对每个子 Agent 调 `MultiAgentStreamSupport.runSubAgent(...)`（内部仍是 `streamEvents`）；
2. 用 `hook.emitPipelineStart/StepStart/StepEnd` 发手动事件；
3. 最后 `Flux.merge(sinkEvents, pipelineFlux)`。

看懂一个，其余七个都是同一套骨架的变体。

---

## 2. 模式选型表（贴在显示器上）

| 模式           | 一句话               | 适合             | 不适合      | 主要代价      |
|--------------|-------------------|----------------|----------|-----------|
| SINGLE       | 一个 Agent 干完       | 大多数任务          | 需要多视角/分工 | 低         |
| SEQUENTIAL   | A 的输出给 B          | 有严格先后依赖（解析→检索） | 步骤可并行    | 串行延迟累加    |
| PARALLEL     | 同时问多个 Agent       | 多视角独立分析、竞速     | 有依赖      | 并发费用、聚合逻辑 |
| ROUTING      | 先判断该谁做            | 专家分工明确、请求类型可判别 | 需要多专家协作  | 多一次路由调用   |
| HANDOFFS     | 按规则转交             | 客服、按关键词升级      | 复杂意图     | 规则维护      |
| DEBATE       | 正反方 + 裁判          | 高风险决策、需要权衡     | 事实查询     | 调用次数最多    |
| LOOP         | 写→评→改             | 文案、代码迭代优化      | 无明确质量标准  | 轮数不可控     |
| MSG_HUB      | 多专家多轮圆桌           | 方案评审、跨领域讨论     | 简单问答     | 上下文膨胀     |
| STATE_GRAPH  | 显式状态机             | 订单、审批、工单       | 开放式对话    | 状态设计成本    |
| SUBAGENT_SEQ | 任务分解后串行委托         | 长流程（调研→分析→报告）  | 子任务独立    | 模板变量耦合    |
| SUBAGENT_PAR | 任务分解后并行委托         | 多方案并行产出        | 子任务有依赖   | 结果聚合      |
| HARNESS      | 带工作区/沙箱/计划的 Agent | 长任务、代码执行       | 简单问答     | 启动与资源成本   |

**选型口诀**：先问“能不能一个 Agent
做完”；不能，再问“是分工（routing/handoff）还是分步（sequential/graph）还是多视角（parallel/debate/roundtable）”。

---

## 3. 动手实验：逐个跑，逐个对事件

> 下面所有请求都走同一个入口。建议用 UI 跑（右侧调试面板更直观），也可以 curl 后 grep 事件类型。

### Lab 4.1：SEQUENTIAL（20 分钟）

```bash
curl -N -sS -X POST http://localhost:8081/chat/send \
  -H 'Content-Type: application/json' \
  -d '{"agentId":"doc-analysis-pipeline-v2","message":"请分析并补充：AgentScope 是一个 Java Agent 框架，支持工具调用和多智能体协作。"}' \
  | grep '^data:'
```

预期事件（顺序）：

```
pipeline_start
pipeline_step_start (step 0 = doc-expert)
  agent_start / llm_start / text* / agent_end
pipeline_step_end
pipeline_step_start (step 1 = search-expert)
  ...
pipeline_step_end / pipeline_step_result
text (最终输出)
pipeline_end
done
```

读 `SequentialRuntime.java:38-89`，对照每一行：

- 第 44 行：`chain` 的初始值是用户输入；
- 第 50 行：`flatMap(prevOutput -> ...)` 把上一步输出作为下一步输入；
- 第 54-56 行：构造新的 `Msg`；
- 第 63 行：`pipeline_step_result` 传的是截断后的输出。

**改造实验**：把 `agents.yml` 里 `doc-analysis-pipeline-v2` 的两个 subAgent 调换顺序，重启再跑，观察最终质量变化，并解释为什么。

### Lab 4.2：PARALLEL（15 分钟）

```bash
curl -N -sS -X POST http://localhost:8081/chat/send \
  -H 'Content-Type: application/json' \
  -d '{"agentId":"parallel-analysis-v2","message":"从文档、搜索、视觉三个角度分析 AI Agent 的发展趋势"}' \
  | grep '^data:'
```

**观察重点**：

- 多个 `pipeline_step_start` 几乎同时出现；
- 各子 Agent 的事件会**交错**（这是并发的直接证据）；
- 最后一条 `text` 是聚合后的结果。

读 `ParallelRuntime.java:44-87`，注意它用的是 `Flux.merge(...)` 还是 `concat`，以及聚合方式（简单拼接 vs 让某个 Agent 总结）。

### Lab 4.3：DEBATE（20 分钟）

```bash
curl -N -sS -X POST http://localhost:8081/chat/send \
  -H 'Content-Type: application/json' \
  -d '{"agentId":"debate-review-v2","message":"AI Agent 会不会取代初级程序员？"}' \
  | grep '^data:'
```

预期：

```
pipeline_start
round_message × N（乐观派/批评家/分析师）
roundtable_summary（裁判总结，可有）
text（最终综合）
pipeline_end
done
```

读 `DebateRuntime.java:52-100`：几轮、每轮谁发言、裁判在哪一步介入。

### Lab 4.4：LOOP（20 分钟）

```bash
curl -N -sS -X POST http://localhost:8081/chat/send \
  -H 'Content-Type: application/json' \
  -d '{"agentId":"copywriter-refiner-v2","message":"写一句关于春天的新品发布文案"}' \
  | grep '^data:'
```

预期：`loop_start → loop_iteration_result → ... → loop_end`，以及 writer/critic 的文本。

读 `LoopRuntime.java`，回答：

1. “通过”的判断来自哪里？（critic 的输出关键词？还是另一个 LLM 判断？）
2. 达到 `maxIterations: 3` 时会怎么收尾？

对照测试 `LoopRuntimeTest#maxIterationsReached_emitsLastWriterOutput_regression`。

### Lab 4.5：MSG_HUB 圆桌（20 分钟）

```bash
curl -N -sS -X POST http://localhost:8081/chat/send \
  -H 'Content-Type: application/json' \
  -d '{"agentId":"expert-roundtable-v2","message":"小团队做后端选型：单体还是微服务？"}' \
  | grep '^data:'
```

预期：

```
roundtable_start
 round_start → round_message × 3（架构师/DBA/安全）→ round_end
 round_start → ...
roundtable_summary（主持人）
done
```

读 `MsgHubRuntime.java`，重点看：每个专家是否能看到之前的发言（否则就不叫圆桌了）。

### Lab 4.6：STATE_GRAPH（25 分钟，单请求走完整流程）

先看清一个实现事实：`OrderFulfillmentGraph` 的 `currentState` 是**进程内字段**，而 `AgentRuntimeFactory` 每次请求都会
`new` 一个新的 graph（`AgentRuntimeFactory.java:211-216`）。所以**图状态不会跨 HTTP 请求保留**，传 `sessionId` 也不会。

正确玩法：在**一条消息里**包含多个触发词，让图在同一次请求内递归推进：

```bash
curl -N -sS -X POST http://localhost:8081/chat/send \
  -H 'Content-Type: application/json' \
  -d '{"agentId":"order-fulfillment","message":"我要买一个商品，金额 10000 元。提交订单，审核，支付，发货。"}' \
  | grep '^data:'
```

预期事件（在一次请求内）：

```
pipeline_start
graph_transition (CREATED → SUBMITTED)
graph_transition (SUBMITTED → REVIEWING)
graph_agent_call (REVIEWING → order-reviewer)
graph_transition (REVIEWING → APPROVED/REJECTED)
graph_transition (APPROVED → PAYING)
graph_agent_call (PAYING → payment-agent)
graph_transition (PAYING → PAID/PAY_FAILED)
graph_transition (PAID → SHIPPING)
graph_agent_call (SHIPPING → shipping-agent)
graph_transition (SHIPPING → DONE/LOST_IN_TRANSIT)
text / pipeline_end / done
```

> 这是一个很好的“读源码胜过读文档”的例子：CLAUDE.md 描述的是**设计意图**，实际可复现的行为要以 `StateGraphRuntime` /
`OrderFulfillmentGraph` 的代码为准。如果你想让它跨请求保持状态，需要自己把 `currentState` 持久化到 `AgentStateStore`
> ——这正是“框架能力 vs 工程封装”的边界。

读：

- `composite/graph/OrderFulfillmentGraph.java`：状态、事件、条件；
- `runtime/StateGraphRuntime.java:25-55`：如何把图事件转成 SSE；
- `config/agents.yml` 中 `order-fulfillment` 的 `states` 配置。

回答：哪些转移是**用户事件驱动**（submit/pay/ship），哪些是**Agent 条件驱动**（approved/rejected/success/fail）？

### Lab 4.7：ROUTING 与 HANDOFFS（20 分钟）

```bash
# 路由：问不同类型的问题，看它选哪个专家
curl -N -sS -X POST http://localhost:8081/chat/send \
  -H 'Content-Type: application/json' \
  -d '{"agentId":"smart-router","message":"帮我搜索一下今天的 AI 新闻"}'

# 交接：触发关键词
curl -N -sS -X POST http://localhost:8081/chat/send \
  -H 'Content-Type: application/json' \
  -d '{"agentId":"customer-service","message":"这个产品多少钱？我想购买"}'
```

**观察重点**：

- `tool_start` 里的工具名是否就是某个子 Agent（`SubAgentTool` 的表现）；
- `agent_start` 的名字是否从主 Agent 变成了专家；
- `customer-service` 是否因为“价格/购买”关键词转给 `sales-agent`。

> 以实际输出为准：本项目里 ROUTING/HANDOFFS 的决策主要体现在**子 Agent 工具调用**上；`routing_decision`/`handoff_start`
> 常量存在，但并非所有路径都会发。Supervisor 路径会发 `routing_event`（见 Lab 4.8）。

### Lab 4.8：Supervisor + 共享黑板（25 分钟，进阶）

`customer-service-supervisor` 不是普通 ROUTING：它维护一块跨专家的“共享黑板”，让专家 A 的结论能被专家 B 看到。

```bash
curl -N -sS -X POST http://localhost:8081/chat/send \
  -H 'Content-Type: application/json' \
  -d '{"agentId":"customer-service-supervisor","message":"我买的产品坏了，想退款，但订单号丢了","sessionId":"supervisor-demo"}'
```

预期事件：`supervisor_start`、`routing_event`、`blackboard_patched`、专家文本、`done`。

读：

- `blackboard/SupervisorRuntime.java`（事件与状态边界）；
- `blackboard/BlackboardService.java`（版本号、合并、持久化 key）；
- `blackboard/RoutingDecisionService.java`（KEEP / SWITCH / CLARIFY）；
- `docs/supervisor-shared-blackboard-design.md`（设计动机与反模式）。

回答：

1. 为什么黑板要用 `"shared_blackboard"` 而不是 `"agent_state"`？
2. 专家 A 和专家 B 的对话历史为什么要隔离？
3. `KEEP` 与 `CLARIFY` 分别什么时候出现？

---

## 4. 精读路线

| 文件                                                                              | 看什么                                           |
|---------------------------------------------------------------------------------|-----------------------------------------------|
| `composite/CompositeAgentFactory.java`                                          | 所有模式的“组装入口”；ROUTING/HANDOFFS 的 `SubAgentTool` |
| `runtime/StreamingAgentRuntime.java`                                            | 统一契约                                          |
| `runtime/SequentialRuntime.java`                                                | 最干净的编排样本，先读它                                  |
| `runtime/ParallelRuntime.java`                                                  | 并发与聚合                                         |
| `runtime/DebateRuntime.java`                                                    | 多轮 + 裁判                                       |
| `runtime/LoopRuntime.java`                                                      | 迭代终止条件                                        |
| `runtime/MsgHubRuntime.java`                                                    | 多轮共享上下文                                       |
| `runtime/SubAgentSeqRuntime.java` / `SubAgentParRuntime.java`                   | `taskTemplate` 与 `{prevOutput}` 变量            |
| `runtime/StateGraphRuntime.java` + `composite/graph/OrderFulfillmentGraph.java` | 显式状态机                                         |
| `blackboard/SupervisorRuntime.java`                                             | 共享黑板 + 路由决策                                   |

---

## 5. 今日产出

- [ ] 一张表：**模式 → 代表 agent → runtime 类 → 关键事件**（自己填，不要抄）；
- [ ] 一次 SEQUENTIAL 改造实验记录（调换顺序前后的差异）；
- [ ] 一次 STATE_GRAPH 完整走完 `CREATED → DONE` 的记录；
- [ ] 一句话回答：*“为什么 2.0 删掉 Pipeline 后，多智能体反而更好理解了？”*

---

## 6. 自测题

1. `smart-router` 和 `doc-analysis-pipeline-v2` 的调度机制有什么本质不同？
2. `SEQUENTIAL` 和 `SUBAGENT_SEQ` 都是串行，区别在哪？
3. `PARALLEL` 的结果是怎么聚合的？聚合方式会带来什么偏差？
4. `STATE_GRAPH` 中“用户事件驱动”和“Agent 条件驱动”的转移，哪个更可控？为什么？
5. Supervisor 的共享黑板解决了普通 ROUTING 的什么痛点？
6. 如果老板要求“多个专家评审一份合同，最后给结论”，你会选哪种模式？为什么？

<details>
<summary>参考答案</summary>

1. `smart-router` 用框架的 `SubAgentTool`，由主 Agent 在一次 LLM 调用中决定调哪个子 Agent；pipeline 由项目手写 runtime
   按固定顺序调用。
2. `SEQUENTIAL` 是把上一步的**完整输出**直接作为下一步输入；`SUBAGENT_SEQ` 用 `taskTemplate` 显式定义每个子任务的提示词，支持
   `{userInput}` / `{prevOutput}` 占位符，控制更精细。
3. 常见是拼接多个子 Agent 的文本；这会把不同视角并列，但不做真正融合，可能重复或互相矛盾。需要融合时应加一个总结 Agent。
4. 用户事件驱动更可控（外部明确触发）；Agent 条件驱动依赖模型判断，灵活但不确定，需要校验/兜底。
5. 普通 ROUTING 每次只把当前请求交给一个专家，专家之间不共享上下文；黑板上沉淀事实，专家切换后仍能复用。
6. `DEBATE`（多专家 + 裁判）或 `MSG_HUB`（多轮圆桌 + 主持人总结）。如果合同解析是前置步骤，可以是 `SEQUENTIAL` 套 `DEBATE`。

</details>

---

## 7. 排错

| 现象                    | 排查                                       |
|-----------------------|------------------------------------------|
| 多智能体只输出一段文本           | 是否命中了 `SINGLE` 分支；看启动日志里创建的 runtime 类型   |
| `STATE_GRAPH` 每轮都回到起点 | 这是当前实现的预期行为：graph 状态不跨请求保留，需在单条消息里给全触发词  |
| 并行看不出并发               | 子 Agent 太少/太快；看事件是否交错，或加日志时间戳            |
| 圆桌专家互相不知道说过什么         | 检查 `MsgHubRuntime` 是否把历史注入每个子 Agent      |
| Supervisor 回答跑偏       | `RoutingDecisionService` 的置信度/默认 KEEP 配置 |

---

## 8. 明天见

编排层结束。明天看第三层顶端：**Harness**——当 Agent 需要工作区、沙箱、计划、长期记忆和技能自学习时，框架提供了什么。
→ [Day 5 · Harness 与生态](day5-harness-ecosystem.md)
