# AgentScope Java 2.0 速查表（本项目版）

> 学习期间常开着。所有行号基于当前仓库（AgentScope 2.0.3）。

---

## 1. 常用命令

```bash
# 编译 / 测试
mvn -q -DskipTests compile
mvn test
mvn test -Dtest=AgentEventMapperTest
mvn test -Dtest=LoopRuntimeTest#maxIterationsReached_emitsLastWriterOutput_regression
mvn test -Dtest='*RuntimeTest'

# 启动
export DASHSCOPE_API_KEY=your_key
mvn spring-boot:run

# 可选 profile
mvn spring-boot:run -Dspring-boot.run.profiles=a2a
mvn spring-boot:run -Dspring-boot.run.profiles=agui
mvn spring-boot:run -Dspring-boot.run.profiles=redis
mvn spring-boot:run -Dspring-boot.run.profiles=mysql
mvn spring-boot:run -Dspring-boot.run.profiles=postgresql
mvn spring-boot:run -Dspring-boot.run.profiles=feishu

# 最小对话
curl -N -sS -X POST http://localhost:8081/chat/send \
  -H 'Content-Type: application/json' \
  -d '{"agentId":"chat-basic","message":"你好"}'
```

---

## 2. 核心 API

### 2.1 构建 Agent

```java
ReActAgent agent = ReActAgent.builder()
        .name("assistant")              // 事件/显示名
        .sysPrompt("你是...")            // 系统提示词
        .model(model)                   // Model
        .stateStore(stateStore)         // AgentStateStore
        .defaultSessionId("agent-id")   // 默认状态键
        .toolkit(toolkit)               // Toolkit
        .skillRepository(repo)          // SkillRepository
        .dynamicSkillsEnabled(true)     // 允许动态加载技能
        .middlewares(List.of(...))      // Middleware
        .permissionContext(state)       // 权限上下文
        .knowledge(...)                 // RAG（v1，已废弃）
        .longTermMemory(...)            // 长期记忆（v1，已废弃）
        .enablePendingToolRecovery(true)// 中断恢复
        .build();
```

对应代码：`agent/AgentFactory.java:131-207`。

### 2.2 模型

```java
ModelCreationContext ctx = ModelCreationContext.builder()
        .apiKey(apiKey)
        .stream(true)
        .enableThinking(true)
        .component(Formatter.class, new DashScopeChatFormatter())
        .build();
Model model = ModelRegistry.resolve("dashscope:qwen-plus", ctx);
```

- `modelName` 不写前缀时，`ModelFactory` 会自动补 `dashscope:`。
- 本项目绝大多数 agent 用 `deepseek-v4.1-flash-1017`（见 `agents.yml`）；`modelName` 为空时 `ModelFactory` 回退到
  `dashscope:qwen-plus`。

### 2.3 消息与内容块

```java
Msg text = Msg.builder()
        .role(MsgRole.USER)
        .content(TextBlock.builder().text("你好").build())
        .build();

Msg multi = Msg.builder()
        .role(MsgRole.USER)
        .content(List.of(
                TextBlock.builder().text("看图").build(),
                ImageBlock.builder().source(new Base64Source(base64)).build()))
        .build();
```

- `ToolUseBlock`：模型请求调用工具；
- `ToolResultBlock`：工具执行结果；
- 多模态参考 `model/MultiModalMessage.java`。

### 2.4 工具

```java
public class MyTools {
    @Tool(name = "my_tool", description = "给模型看的说明，决定它会不会/怎么调")
    public String myTool(
            @ToolParam(name = "arg", description = "参数说明") String arg) {
        return "结果";
    }
}
```

- 放在 `com.skloda.agentscope.tool` 包下会被自动扫描；
- 在 agent 的 `userTools` 里引用 `@Tool.name`。

### 2.5 技能

```markdown
---
name: my-skill
description: "触发条件，写给模型看"
tools: my_tool, other_tool
---

# 标题
## 步骤
1. ...
```

- 放在 `src/main/resources/skills/<name>/SKILL.md`；
- 在 agent 的 `skills` 里引用 `name`；
- 绑定后框架使用 `ClasspathSkillRepository` + `DynamicSkillMiddleware`。

### 2.6 状态存储

```java
new InMemoryAgentStateStore();
new JsonFileAgentStateStore(Path.of("..."));
RedisAgentStateStore.builder().jedisClient(jedis).keyPrefix("as:").build();
```

选择逻辑：`agent/AgentFactory.java:80-92`。

### 2.7 Harness

```java
HarnessAgent.builder()
        .name(...).description(...).sysPrompt(...).model(...)
        .workspace(Path.of("..."))
        .filesystem(new LocalFilesystemSpec() /* 或 DockerFilesystemSpec */)
        .compaction(CompactionConfig.builder()
                .triggerMessages(30).keepMessages(10).flushBeforeCompact(true).build())
        .memory(MemoryConfig.builder()...build())
        .enableTaskList()
        .enablePlanMode()
        .build();
```

对应代码：`harness/HarnessAgentFactory.java:74-219`。

---

## 3. 事件表

| 前端 `type`                                                   | 来源                     | 触发点             |
|-------------------------------------------------------------|------------------------|-----------------|
| `agent_start`                                               | `AGENT_START`          | Agent 开始        |
| `llm_start`                                                 | `MODEL_CALL_START`     | 第 N 次模型调用       |
| `thinking`                                                  | `THINKING_BLOCK_DELTA` | 推理增量            |
| `llm_end`                                                   | `MODEL_CALL_END`       | token 用量、工具请求   |
| `tool_start`                                                | `TOOL_CALL_START`      | 准备执行工具          |
| `tool_end`                                                  | `TOOL_CALL_END`        | 工具执行结束          |
| `tool_result_*`                                             | `TOOL_RESULT_*`        | 工具结果流式输出        |
| `text`                                                      | `TEXT_BLOCK_DELTA`     | 回答增量            |
| `agent_end`                                                 | `AGENT_END`            | 调用次数/耗时         |
| `done`                                                      | `ChatEvent.done()`     | 流结束哨兵           |
| `error`                                                     | 映射异常/运行时               | 错误              |
| `pipeline_*`                                                | `EventSink`            | 顺序/并行/辩论编排      |
| `routing_decision`                                          | `EventSink`            | 路由决策（部分路径）      |
| `handoff_start`                                             | `EventSink`            | 交接（部分路径）        |
| `loop_*`                                                    | `EventSink`            | 写-评-改循环         |
| `roundtable_*` / `round_*`                                  | `EventSink`            | 圆桌              |
| `graph_*`                                                   | `EventSink`            | 状态图             |
| `task_*`                                                    | `EventSink`            | 子 Agent 任务      |
| `supervisor_start` / `routing_event` / `blackboard_patched` | `EventSink`            | Supervisor + 黑板 |

映射实现：`runtime/AgentEventMapper.java:64-105`。

---

## 4. `agents.yml` 字段

| 字段                                                                  | 作用                                                                                                                                                             |
|---------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `agentId`                                                           | 唯一 ID（kebab-case）                                                                                                                                              |
| `type`                                                              | `SINGLE`（默认）/ `SEQUENTIAL` / `PARALLEL` / `ROUTING` / `HANDOFFS` / `DEBATE` / `LOOP` / `STATE_GRAPH` / `MSG_HUB` / `SUBAGENT_SEQ` / `SUBAGENT_PAR` / `HARNESS` |
| `category`                                                          | UI 分组                                                                                                                                                          |
| `name` / `description`                                              | 展示                                                                                                                                                             |
| `systemPrompt`                                                      | 系统提示词                                                                                                                                                          |
| `modelName`                                                         | 模型 ID                                                                                                                                                          |
| `streaming` / `enableThinking`                                      | 流式 / 推理                                                                                                                                                        |
| `skills`                                                            | 技能名列表                                                                                                                                                          |
| `userTools`                                                         | 业务工具名列表                                                                                                                                                        |
| `systemTools`                                                       | 框架内置工具名列表                                                                                                                                                      |
| `modality`                                                          | `text` / `vision` / `audio`                                                                                                                                    |
| `ragEnabled` / `ragMode` / `ragRetrieveLimit` / `ragScoreThreshold` | RAG                                                                                                                                                            |
| `autoContext*`                                                      | 项目自研上下文管理                                                                                                                                                      |
| `sessionConfig.defaultType`                                         | `memory` / `json`                                                                                                                                              |
| `approvalRequired` / `approvalTools`                                | HITL                                                                                                                                                           |
| `middlewares`                                                       | 中间件名列表                                                                                                                                                         |
| `permissionConfig`                                                  | `defaultMode` / `denyTools` / `askTools`                                                                                                                       |
| `subAgents`                                                         | 子 Agent 列表（`agentId` + `description` + 可选 `taskTemplate`）                                                                                                      |
| `handoffTriggers`                                                   | `type` / `keywords` / `target`                                                                                                                                 |
| `loopConfig`                                                        | `maxIterations` / `exitCondition`                                                                                                                              |
| `msgHubConfig`                                                      | `rounds` / `summaryRole`                                                                                                                                       |
| `states`                                                            | StateGraph 状态与转移                                                                                                                                               |
| `harnessConfig`                                                     | Harness 全部配置                                                                                                                                                   |
| `samplePrompts`                                                     | UI 示例提示                                                                                                                                                        |

---

## 5. 多智能体映射

| type           | Runtime                         | 代表 agent                      | 关键事件                                   |
|----------------|---------------------------------|-------------------------------|----------------------------------------|
| `SINGLE`       | `AgentRuntime`                  | `chat-basic`                  | `agent_*` / `llm_*` / `tool_*`         |
| `SEQUENTIAL`   | `SequentialRuntime`             | `doc-analysis-pipeline-v2`    | `pipeline_*`                           |
| `PARALLEL`     | `ParallelRuntime`               | `parallel-analysis-v2`        | `pipeline_*`（交错）                       |
| `DEBATE`       | `DebateRuntime`                 | `debate-review-v2`            | `round_message` / `roundtable_summary` |
| `LOOP`         | `LoopRuntime`                   | `copywriter-refiner-v2`       | `loop_*`                               |
| `MSG_HUB`      | `MsgHubRuntime`                 | `expert-roundtable-v2`        | `roundtable_*` / `round_*`             |
| `SUBAGENT_SEQ` | `SubAgentSeqRuntime`            | `report-generator-v2`         | `task_*`                               |
| `SUBAGENT_PAR` | `SubAgentParRuntime`            | `project-manager-v2`          | `task_*`                               |
| `STATE_GRAPH`  | `StateGraphRuntime`             | `order-fulfillment`           | `graph_*`                              |
| `ROUTING`      | `AgentRuntime` + `SubAgentTool` | `smart-router`                | 子 Agent 工具调用                           |
| `HANDOFFS`     | `AgentRuntime` + `SubAgentTool` | `customer-service`            | 子 Agent 工具调用                           |
| `ROUTING` + 黑板 | `SupervisorRuntime`             | `customer-service-supervisor` | `routing_event` / `blackboard_patched` |
| `HARNESS`      | `HarnessRuntime`                | `plan-build-demo`             | Harness 事件 + `agent_*`                 |

---

## 6. 关键文件索引

| 想看什么            | 文件                                                                      |
|-----------------|-------------------------------------------------------------------------|
| 请求入口 / SSE      | `controller/ChatController.java`                                        |
| 路由到 runtime     | `service/AgentService.java`                                             |
| 单 Agent 组装      | `agent/AgentFactory.java`                                               |
| 模型解析            | `model/ModelFactory.java`                                               |
| 单 Agent 运行时     | `runtime/AgentRuntime.java`                                             |
| 事件映射            | `runtime/AgentEventMapper.java`                                         |
| 手动事件总线          | `runtime/EventSink.java`                                                |
| 多智能体组装          | `composite/CompositeAgentFactory.java`                                  |
| Supervisor / 黑板 | `blackboard/SupervisorRuntime.java`、`blackboard/BlackboardService.java` |
| 会话管理            | `service/SessionManagerService.java`                                    |
| 分布式状态           | `config/DistributedStateStoreConfig.java`                               |
| Harness         | `harness/HarnessAgentFactory.java`                                      |
| MCP             | `mcp/McpClientService.java`、`config/mcp-servers.yml`                    |
| A2A / AG-UI     | `config/A2aServerConfig.java`、`config/AguiConfig.java`                  |

---

## 7. 排错速查

| 现象                    | 可能原因                         | 处理                                   |
|-----------------------|------------------------------|--------------------------------------|
| 启动 401 / 模型报错         | 没设 `DASHSCOPE_API_KEY`       | `export` 到当前终端后重启                    |
| 端口冲突                  | 8081 被占                      | 改 `application.yml` 或杀进程             |
| Agent 不出现             | YAML 缩进/字段错误                 | 看启动日志 `AgentConfigService`           |
| 工具不调用                 | description 太模糊 / prompt 未要求 | 改 `@Tool.description` 与 systemPrompt |
| `Tool not registered` | 类不在扫描包 / 未重新编译               | 放到 `com.skloda.agentscope.tool`      |
| RAG 无结果               | 索引未 READY / 阈值过高             | 看 `/api/knowledge/status`，调低阈值       |
| 重启失忆                  | 用了 `memory` 或 sessionId 变了   | 用 `json`/分布式，保持 `sessionId`          |
| 多智能体无子事件              | 命中 SINGLE 分支                 | 查启动日志 runtime 类型                     |
| MCP 无工具               | 没有 `npx` / 端口占用              | 安装 Node，释放 9090/9091                 |
| sandbox 失败            | Docker 未运行 / 镜像拉不到           | 启动 Docker，预拉 `python:3.11-slim`      |
| 流不结束                  | 上游未 complete                 | 确认 `done` 是否出现；查 `takeUntil`         |

---

## 8. 术语速查

| 术语                | 含义                               |
|-------------------|----------------------------------|
| ReAct             | Reason + Act，Agent 的“思考-行动-观察”循环 |
| Tool Calling      | 模型输出结构化工具调用请求，由框架执行              |
| State             | 会话的完整可恢复快照                       |
| Memory            | 跨会话提炼的事实/偏好                      |
| RAG               | 先检索再生成                           |
| HITL              | Human-in-the-loop，人审批            |
| SubAgentTool      | 把子 Agent 包装成工具供主 Agent 调用        |
| Compaction        | 上下文压缩                            |
| Sandbox           | 隔离的执行环境                          |
| Artifact Delivery | 把沙箱产物交付回宿主机                      |
