# Day 5 · Harness 与生态：从“能对话”到“能干活”

> 时长：约 4 小时
> 目标：理解 `HarnessAgent` 相比 `ReActAgent` 多了什么，并在真实 demo 里看到工作区、计划、记忆、技能自学习、压缩、沙箱。

---

## 今日目标（可检验）

- [ ] 能说出 `HarnessAgent` 至少 6 项 `ReActAgent` 没有的能力；
- [ ] 能区分 `CLAW` 与 `BUILDER`、`LOCAL` 与 `DOCKER`；
- [ ] 能跑通 Plan → 审批 → Build 全流程；
- [ ] 能看到记忆文件、技能文件、压缩行为；
- [ ] 能说清 A2A / AG-UI / MCP 各自解决什么问题。

---

## 1. Harness 是什么

`ReActAgent` 是**发动机**：会思考、会调工具。
`HarnessAgent` 是**整车**：发动机 + 工作区 + 文件系统 + 计划 + 记忆 + 技能 + 子 Agent + 沙箱。

本项目用 `HarnessAgentFactory` 把它接进来（`harness/HarnessAgentFactory.java`），核心组装：

```java
HarnessAgent.builder()
    .name(...)
    .description(...)
    .sysPrompt(...)
    .model(model)
    .workspace(workspace)
    .filesystem(...)        // LOCAL / DOCKER
    .compaction(...)        // 上下文压缩
    .memory(...)            // 分层记忆
    .enableTaskList()       // 任务清单
    .enablePlanMode()       // 计划模式
    .skillRepository(...)   // 技能
    // ... subagents / artifactDelivery / permission
    .build();
```

### 能力对照

| 能力      | `ReActAgent`             | `HarnessAgent`                                 | 本项目代码                                         |
|---------|--------------------------|------------------------------------------------|-----------------------------------------------|
| 工作区     | 无                        | `workspace` 目录 + `AGENTS.md`                   | `WorkspaceInitializer`                        |
| 文件系统    | 靠工具自己实现                  | `LocalFilesystemSpec` / `DockerFilesystemSpec` | `FilesystemSpecFactory`                       |
| 上下文压缩   | 项目自研 `autoContext`       | 框架 `CompactionConfig`                          | `CompactionConfigFactory`                     |
| 计划模式    | 无                        | `enablePlanMode()`                             | `HarnessAgentFactory.java:138-152`            |
| 任务清单    | 无                        | `enableTaskList()`                             | `HarnessAgentFactory.java:132-136`            |
| 分层记忆    | v1 `longTermMemory`（已废弃） | `MemoryConfig`                                 | `HarnessAgentFactory.java:154-192`            |
| 技能自学习   | 无                        | `skillLearning`                                | `HarnessAgentFactory` + `skill-learning-demo` |
| 子 Agent | 手写 `SubAgentTool`        | 内置 `subagents`                                 | `harnessConfig.subagents`                     |
| 产物交付    | 无                        | `deliver_artifact`                             | `UploadsArtifactDeliveryTarget`               |

**一句话**：需要“在一个目录里持续干活、能恢复、能审批、能记住”时用 Harness；只是问答或固定编排时用 `ReActAgent`。

---

## 2. 两个关键维度

| 维度               | 取值        | 含义                                                        |
|------------------|-----------|-----------------------------------------------------------|
| `executionMode`  | `CLAW`    | 偏“助手”：直接对话、较少文件操作                                         |
|                  | `BUILDER` | 偏“工程”：在工作区里读写文件、执行命令、构建产物                                 |
| `filesystemMode` | `LOCAL`   | 本地目录（`LocalFilesystemSpec`）                               |
|                  | `DOCKER`  | Docker 沙箱（`DockerFilesystemSpec`，默认镜像 `python:3.11-slim`） |
| `isolationScope` | `USER`    | 按 userId 隔离工作区                                            |

> `CLAW` / `BUILDER` 不是“安全级别”，而是**行为模式**；真正的隔离靠 `filesystemMode` 和 `permissionConfig`。

---

## 3. 动手实验

### Lab 5.1：Plan → 审批 → Build（30 分钟）

用 UI 打开 `plan-build-demo`（或 curl），输入：

```
帮我规划并执行：在工作区创建一个 hello.py，并写一个简单测试
```

预期流程：

```
PLAN 模式（只读）
  → plan_write 写出计划
  → plan_exit 请求审批
  → 前端出现审批卡片
你点击“批准”
  → 进入 BUILD 模式
  → todo_write 建立任务清单
  → 逐步执行（写文件、运行测试）
```

读：

- `HarnessAgentFactory.java:138-152`（Plan Mode 配置）；
- `controller/ChatController.java:161-207`（`/chat/approve` 如何恢复）；
- `middleware/ApprovalMiddleware.java`（拦截点）。

**要观察**：审批前 Agent 有没有写文件？（不应该有，PLAN 是只读）

### Lab 5.2：分层记忆（25 分钟）

1. 打开 `memory-assistant`，说：

```
我叫小明，我是做 Java 后端的，最近在研究 AI Agent。
```

2. 找到工作区：

```bash
ls -la "$HOME/.agentscope/memory-assistant"
find "$HOME/.agentscope/memory-assistant" -maxdepth 2 -type f
```

3. 重启应用，再问：

```
你还记得我是谁吗？
```

**验收**：能回忆出小明 / Java 后端 / AI Agent。

读 `HarnessAgentFactory.java:154-192`，理解：

- `flushTrigger: ALWAYS`：每次对话后都尝试沉淀；
- `consolidationMaxTokens`：合并时的预算；
- `dailyFileRetentionDays` / `sessionRetentionDays`：保留策略；
- 最终会合并到 `MEMORY.md`（工作区里找）。

### Lab 5.3：技能自学习（30 分钟）

打开 `skill-learning-demo`：

```
帮我写一个 Python 函数计算斐波那契数列，然后把它保存成一个可复用的技能。
```

预期：

1. Agent 先执行任务；
2. 调用 `propose_skill` 提出新技能；
3. （若 `autoPromote: false`）等待审批；
4. 通过后技能上线；
5. 问“你现在有哪些技能”，它会用 `skill_manage` 列出。

```bash
find "$HOME/.agentscope/skill-learning" -maxdepth 3 -type f | head -30
```

读 `HarnessConfig.SkillLearningConfig` 与 `agents.yml` 中 `skillLearning` 的字段：

- `securityScan`：上线前扫描；
- `curatorEnabled` / `staleAfterDays` / `archiveAfterDays`：过期归档。

**思考**：技能自学习最大的风险是什么？（提示：模型自己写的能力，谁来保证安全）

### Lab 5.4：上下文压缩（25 分钟）

打开 `compaction-demo`（配置是 `triggerMessages: 10, keepMessages: 5`）。

连续聊 10 轮以上（每轮都提供一些信息），然后在第 12 轮问：

```
你还记得我最开始说的那件事吗？
```

**预期**：早期细节可能被压缩掉，但关键事实仍在；对话不会因上下文过长而失败。

读 `CompactionConfigFactory.java`，重点看：

- `triggerMessages` / `triggerTokens`；
- `keepMessages` / `keepTokens`；
- `flushBeforeCompact`：压缩前先把重要信息沉淀到记忆；
- `truncateArgs`：工具参数过长时截断。

对比项目的 `autoContext`（`long-conversation` agent）：这是**项目自研**的轻量方案，Harness 的 compaction 是**框架内置**的。

### Lab 5.5：Docker 沙箱（30 分钟，需要 Docker）

打开 `sandbox-demo`：

```
用 Python 计算 1 到 100 的和
```

或 `sandbox-artifact-demo`：

```
用 Python 生成一份 100 行的模拟销售数据 CSV，并交付给我
```

预期：

- 代码在 `python:3.11-slim` 容器内执行；
- `sandbox-artifact-demo` 会调用 `deliver_artifact`，把文件交付到宿主机的 `{java.io.tmpdir}/agentscope-uploads`；
- 宿主机的 `/tmp/agentscope-sandbox` 是工作区映射。

读：

- `FilesystemSpecFactory.createDocker()`；
- `HarnessAgentFactory.java:82-114`（Docker + artifactDelivery）；
- `harness/UploadsArtifactDeliveryTarget.java`。

> 没有 Docker 就跳过这个 Lab，不影响其他内容；其他 Harness demo 都是 LOCAL 模式。

### Lab 5.6：MCP（25 分钟，需要 Node.js）

MCP = 让 Agent 调用**外部进程**提供的工具。

```bash
# 确认 Node 可用
npx --version

# 启动应用后，试 MCP agent
curl -N -sS -X POST http://localhost:8081/chat/send \
  -H 'Content-Type: application/json' \
  -d '{"agentId":"mcp-filesystem","message":"列出当前工作区的文件"}'
```

观察 `tool_start` 里的工具名是否来自 MCP server（可能带 server 前缀）。

读：

- `config/mcp-servers.yml`（STDIO / SSE / HTTP 三种 transport）；
- `mcp/McpClientService.java`；
- `AgentFactory.registerMcpTools`（`AgentFactory.java:340-382`）。

**对比**：`userTools` 是进程内 Java 方法；MCP 是跨进程协议。前者简单，后者可复用生态。

### Lab 5.7：协议出口 A2A / AG-UI（20 分钟，可选）

**A2A（Agent-to-Agent）**：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=a2a

# Agent Card
curl -s http://localhost:8081/.well-known/agent-card.json

# JSON-RPC 调用
curl -s -X POST http://localhost:8081/a2a/jsonrpc \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"message/send","params":{"message":{"role":"user","parts":[{"kind":"text","text":"你好"}]}}}'
```

读 `config/A2aServerConfig.java` 与 `config/A2aClientDemoRunner.java`。

**AG-UI（前端协议）**：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=agui
# 浏览器打开
# http://localhost:8081/agui.html
```

读 `config/AguiConfig.java`，观察 `POST /ag-ui/run` 返回的事件格式与自家 SSE 的差异。

**飞书 Channel**（需要公网回调，了解即可）：`application-feishu.yml` + `/channel/feishu/webhook`。

---

## 4. 精读路线

| 文件                                                     | 看什么                      |
|--------------------------------------------------------|--------------------------|
| `harness/HarnessAgentFactory.java`                     | 全部 builder 配置，按编号 1–12 读 |
| `harness/HarnessRuntime.java`                          | Harness 如何接入统一流式接口       |
| `harness/CompactionConfigFactory.java`                 | 压缩与工具结果淘汰                |
| `harness/FilesystemSpecFactory.java`                   | LOCAL / DOCKER 两种文件系统    |
| `harness/WorkspaceInitializer.java`                    | 工作区模板与 `AGENTS.md`       |
| `agent/HarnessConfig.java`                             | 所有可配字段                   |
| `config/A2aServerConfig.java` / `AguiConfig.java`      | 协议出口                     |
| `mcp/McpClientService.java` + `config/mcp-servers.yml` | MCP 接入                   |

---

## 5. 今日产出

- [ ] 一张 `ReActAgent` vs `HarnessAgent` 能力对照表（自己写）；
- [ ] 一次 Plan → 审批 → Build 的截图/日志；
- [ ] 一个记忆文件或技能文件的实际路径；
- [ ] 一句话回答：*“什么任务我会毫不犹豫用 Harness，什么任务我坚持用 ReActAgent？”*

---

## 6. 自测题

1. `HarnessAgent` 和 `ReActAgent` 的本质关系是什么？
2. `CLAW` 与 `BUILDER` 的区别是什么？和安全隔离有关吗？
3. `LOCAL` 与 `DOCKER` 文件系统分别适合什么场景？
4. `compaction` 的 `triggerMessages` 和 `keepMessages` 分别控制什么？
5. Harness 的分层记忆和 `AgentStateStore` 是什么关系？
6. A2A、AG-UI、MCP 三者分别解决什么问题？不要混淆。
7. 技能自学习为什么必须配 `securityScan` 和审批？

<details>
<summary>参考答案</summary>

1. `HarnessAgent` 内部仍然是一个 ReAct 循环，但外面包了工作区、文件系统、计划、记忆、技能、子 Agent 等工程能力。
2. `CLAW` 偏对话助手，`BUILDER` 偏在工作区里构建产物；它们是行为模式，不直接等于安全级别，隔离靠 `filesystemMode` + 权限。
3. `LOCAL` 适合可信环境、快速调试；`DOCKER` 适合执行不可信代码、需要资源限制的场景。
4. `triggerMessages` 达到多少条触发压缩；`keepMessages` 压缩后保留最近多少条。
5. 互补：`AgentStateStore` 保存“这次会话的可恢复状态”；分层记忆保存“跨会话提炼出的事实与偏好”。
6. A2A：Agent 之间互相调用的协议；AG-UI：Agent 与前端 UI 的事件协议；MCP：Agent 调用外部工具/数据源的协议。
7. 技能会变成模型可执行的能力，未经审查的技能可能包含危险操作或提示词注入；扫描 + 审批是最后一道闸。

</details>

---

## 7. 排错

| 现象                               | 排查                                                                      |
|----------------------------------|-------------------------------------------------------------------------|
| Plan 卡在审批                        | 前端审批卡片是否出现；`/chat/approve` 是否被调用                                        |
| 记忆重启后丢失                          | 工作区路径是否可写；`flushTrigger` 是否 `NEVER`                                     |
| 沙箱启动失败                           | Docker 是否运行；镜像是否能拉取                                                     |
| MCP agent 无工具                    | `npx` 是否存在；9090/9091 是否被占                                               |
| a2a/agui 启动报错                    | profile 是否正确；自动配置是否被排除                                                  |
| Harness 回答变慢                     | 压缩/记忆 consolidation 在后台跑；看日志                                            |
| `InvalidPathException` / 工作区路径怪异 | `agents.yml` 的 `workspace` 只能用英文引号或裸值；中文引号 `“”` 会被当成路径字符（Windows 上尤其明显） |

---

## 8. 毕业项目

你已经看完了框架的三层。现在轮到你独立造一个 Agent：

→ [毕业项目 · 会议纪要助手](capstone.md)

---

## 9. 延伸阅读

- `ROADMAP.md` 第一～三部分：2.0 能力差距与设计取舍；
- `docs/superpowers/specs/2026-05-16-harness-complaint-reviewer-design.md`：Harness 四大能力的完整业务案例；
- `docs/superpowers/specs/2026-06-04-claw-to-builder-upgrade-design.md`：CLAW/BUILDER 的设计动机；
- `docs/supervisor-shared-blackboard-design.md`：多 Agent 状态边界的反模式。
