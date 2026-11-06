# Day 3 · 会话、状态、记忆与 RAG

> 时长：约 3.5 小时
> 目标：彻底搞清“为什么同一个 sessionId 能连续对话”，并亲手看到状态落盘、重启恢复、知识库检索。

---

## 今日目标（可检验）

- [ ] 能画出带 `sessionId` 和不带 `sessionId` 的两条调用链；
- [ ] 能指出状态真正写到了哪个文件/哪个存储；
- [ ] 重启应用后，用同一 `sessionId` 能恢复历史；
- [ ] 能解释 `saveSession()` 为什么“看起来什么都没做”；
- [ ] 上传一份文档并让 `rag-chat` 基于它回答。

---

## 1. 概念映射

| 概念        | AgentScope 2.0                      | 本项目                                          |
|-----------|-------------------------------------|----------------------------------------------|
| 无状态 Agent | 每次调用传入消息与 `RuntimeContext`          | `AgentService.createStreamFlux` 无 session 分支 |
| 状态存储      | `AgentStateStore`                   | `agent/AgentFactory.java:80-92`              |
| 内存存储      | `InMemoryAgentStateStore`           | 默认                                           |
| 文件存储      | `JsonFileAgentStateStore`           | `sessionConfig.defaultType: json`            |
| 分布式存储     | Redis / MySQL / PostgreSQL store    | `config/DistributedStateStoreConfig.java`    |
| 会话上下文     | `SessionContext(agent, stateStore)` | `service/SessionManagerService.java:45-73`   |
| RAG       | `builder.knowledge(...)`（v1，已废弃）    | `service/KnowledgeService.java`              |

---

## 2. 两条路径，先分清

打开 `service/AgentService.java:166-173`：

```java
if (sessionId != null && !sessionId.isBlank()) {
    return createSessionStreamFlux(...);     // 有会话
}
AgentRuntime runtime = runtimeFactory.createRuntime(agentId);
return runtime.stream(userMsg);              // 无会话（每次全新）
```

|            | 无 `sessionId`                      | 有 `sessionId`                                |
|------------|------------------------------------|----------------------------------------------|
| Agent 实例   | 每次请求新建                             | 缓存复用（`SessionManagerService.activeSessions`） |
| StateStore | 每次 `new InMemoryAgentStateStore()` | 每个会话一个，可持久化                                  |
| 多轮记忆       | 没有                                 | 有                                            |
| 适用         | 一次性工具调用                            | 聊天、需要上下文的流程                                  |

**关键理解**：本项目的“会话”是**进程内缓存 + 外部 StateStore 的组合**。缓存让同一会话复用 Agent 实例；StateStore
才是状态的权威来源。重启后缓存没了，但 StateStore 还在，所以历史能恢复。

---

## 3. 动手实验

### Lab 3.1：创建会话并连续对话（20 分钟）

```bash
# 1) 创建一个 json 持久化的会话
curl -s -X POST http://localhost:8081/api/sessions \
  -H 'Content-Type: application/json' \
  -d '{"agentId":"session-demo"}'
```

返回示例：

```json
{"sessionId":"a1b2c3d4e5f6a7b8","agentId":"session-demo","agentName":"会话持久化助手","messageCount":0}
```

记下 `sessionId`，然后：

```bash
SID=a1b2c3d4e5f6a7b8

curl -N -sS -X POST http://localhost:8081/chat/send \
  -H 'Content-Type: application/json' \
  -d "{\"agentId\":\"session-demo\",\"sessionId\":\"$SID\",\"message\":\"记住我的名字是小明\"}"

curl -N -sS -X POST http://localhost:8081/chat/send \
  -H 'Content-Type: application/json' \
  -d "{\"agentId\":\"session-demo\",\"sessionId\":\"$SID\",\"message\":\"我叫什么名字？\"}"
```

**验收**：第二轮能答出“小明”。

### Lab 3.2：看到状态落盘（15 分钟）

```bash
ls -la "$HOME/.agentscope/demo-sessions/"
find "$HOME/.agentscope/demo-sessions" -type f | head
```

用编辑器打开最新文件，找 `agent_state` 这个键（对话状态就存在这个 key 下）。

**要观察**：

- 目录/文件结构（`AgentStateStore` 的逻辑键是 `(userId, sessionId, key)`）；
- `agent_state` 这个键下是否能看到消息列表；
- 你的 `sessionId` 是否出现在路径或文件名里。

> `session-demo` 的配置是 `sessionConfig.defaultType: json`（`agents.yml` 里搜 `session-demo`）。其他 agent 默认是 `memory`
> ，不会落盘。
>
> **重要实现细节**：`AgentFactory.buildAgent` 里写的是 `.defaultSessionId(agentId)`（`AgentFactory.java:143`），而普通
> SINGLE 路径调用 `agent.streamEvents(userMsg)` 时**没有传 `RuntimeContext`**（`AgentRuntime.java:103`）。所以在这个路径下，状态的实际命名空间是
**agent 维度**，不一定等于你传的 `ChatRequest.sessionId`。以你看到的文件内容为准，不要想当然。

### Lab 3.2b：文件到底长什么样（10 分钟）

用“同一个 agent、两个不同 sessionId”各聊一句，再对比文件：

```bash
curl -s -X POST http://localhost:8081/chat/send -H 'Content-Type: application/json' \
  -d '{"agentId":"session-demo","sessionId":"sid-A","message":"我是 A"}'
curl -s -X POST http://localhost:8081/chat/send -H 'Content-Type: application/json' \
  -d '{"agentId":"session-demo","sessionId":"sid-B","message":"我是 B"}'

find "$HOME/.agentscope/demo-sessions" -type f -newermt '-5 minutes'
```

**观察结论**（自己验证，不要照抄）：普通 SINGLE 路径是否真的按 `sid-A` / `sid-B` 分了两个文件？如果没有，回到
`AgentFactory.java:143` 和 `AgentRuntime.java:103` 找原因。

### Lab 3.3：重启恢复（15 分钟，关键实验）

1. `Ctrl+C` 停掉应用；
2. 重新 `mvn spring-boot:run`；
3. **不要重新创建会话**，直接用刚才的 `SID` 发问：

```bash
curl -N -sS -X POST http://localhost:8081/chat/send \
  -H 'Content-Type: application/json' \
  -d "{\"agentId\":\"session-demo\",\"sessionId\":\"$SID\",\"message\":\"重启后你还记得我叫什么吗？\"}"
```

**验收**：仍然答出“小明”。

**如果失败**，检查：

- 你是否用了 `session-demo`（`json`），而不是 `chat-basic`（默认 `memory`）；
- 是否传了不同的 `userId`；
- 状态文件是否真的写到了预期目录（看启动日志与 `find` 结果）。

> **再次强调**：普通 SINGLE 路径实际的 `defaultSessionId` 是 `agentId`。所以“换了 `sessionId` 却还能恢复”并不一定是
> bug，而是这个演示路径的实现方式。真正按 `(userId, sessionId)` 做隔离的样本，看 Supervisor 路径：
`AgentRuntimeFactory.java:171-174` 显式构造了 `RuntimeContext`，对应测试是 `RuntimeContextPropagationTest`。

### Lab 3.4：请求级覆盖会话类型（10 分钟）

`ChatRequest` 支持请求级 `sessionType`，可以覆盖 agent 配置：

```bash
# 使用内存模式（不落盘）
curl -N -sS -X POST http://localhost:8081/chat/send \
  -H 'Content-Type: application/json' \
  -d '{"agentId":"session-demo","sessionId":"tmp-memory","sessionType":"memory","message":"这条不会落盘"}'
```

读 `SessionManagerService.resolveSessionType`（151-155）和 `getOrCreateSession`（75-93），理解“会话类型变化时迁移”的逻辑。

### Lab 3.5：RAG 知识库（30 分钟）

项目默认的 `src/main/resources/knowledge/` 目录**不存在**，所以刚启动时知识库是空的。两种方式入库：

**方式 A：通过 API 上传（推荐）**

```bash
curl -s -X POST http://localhost:8081/api/knowledge/upload \
  -F "file=@docs/learning/README.md"

curl -s http://localhost:8081/api/knowledge/status
```

**方式 B：放进本地目录再重启**

```bash
mkdir -p src/main/resources/knowledge
cp docs/learning/README.md src/main/resources/knowledge/
# 重启应用，等待后台索引完成
```

然后提问：

```bash
curl -N -sS -X POST http://localhost:8081/chat/send \
  -H 'Content-Type: application/json' \
  -d '{"agentId":"rag-chat","message":"根据知识库回答：这个项目支持哪些 AgentScope 2.0 的学习内容？"}'
```

**验收**：回答引用了你上传的文档内容，而不是泛泛而谈。

再对比 `rag-agent`：

- `rag-chat`：`ragMode: generic`，**每个问题都会先检索**；
- `rag-agent`：把检索做成工具，由模型**自己决定**要不要检索（Agentic RAG）。

问一个常识问题（“1+1 等于几”），观察 `rag-agent` 不调用检索工具；再问文档相关问题，观察它调用。

### Lab 3.6：分布式存储（可选，需要 Docker）

```bash
# 启动 Redis
docker run -d --name as-redis -p 6379:6379 redis:7

# 用 redis profile 启动应用
mvn spring-boot:run -Dspring-boot.run.profiles=redis
```

然后重复 Lab 3.3 的重启实验（agent 用 `session-persistence`）。状态会写到 Redis，而不是本地 JSON。

读 `config/DistributedStateStoreConfig.java`，重点看：

- `RedisAgentStateStore.builder()`；
- `supportsVersioning()` / `ConflictPolicy`（乐观并发控制）；
- MySQL / PostgreSQL 的 DataSource 构造。

> 这是 2.0 相比 1.x 最大的工程能力提升之一：状态不再绑死在单机内存。

---

## 4. 精读路线

### 4.1 `agent/AgentFactory.java:76-129`

读什么：

- `createStateStore()` 无参版本 → 永远内存；
- `createStateStore(type, path)` → 分布式 Bean 优先，其次 `json`，最后 `memory`；
- `createAgent` / `createAgentForSession` 的区别（前者每次新 store）。

回答：为什么“无 session 的请求”天然不共享状态？

### 4.2 `service/SessionManagerService.java:45-73, 135-162`

读什么：

- `SessionContext` 持有什么（agent + store + type + 时间戳）；
- `createSessionContext` 如何把同一个 store 同时交给 Agent 和上下文；
- `saveSession` 只做 `touch()`。

回答：**状态到底是谁写的？**（提示：框架在每轮结束后通过 `AgentStateStore` 写入，`saveSession` 不负责内容）

### 4.3 `agent/AgentConfig.java:98-113`

读什么：`sessionConfig.defaultType` / `storagePath`。

回答：如果给某个 agent 配 `storagePath`，JSON 会写到哪里？

### 4.4 `config/DistributedStateStoreConfig.java`

读什么：Redis / MySQL / PostgreSQL 三种 store 的构建，以及版本/冲突策略。

回答：多个实例同时写同一个会话，框架靠什么避免互相覆盖？

### 4.5 `controller/KnowledgeController.java` + `service/KnowledgeService.java`

读什么：

- `uploadDocument` 的入库流程；
- `/status` 暴露的索引状态（`KnowledgeIndexStatus`）；
- `GENERIC` vs Agentic 两种模式的差别。

回答：上传后为什么要等索引？索引状态里 `READY` 代表什么？

---

## 5. 今日产出

- [ ] 一份“状态文件结构”笔记（贴一段脱敏后的 JSON 片段）；
- [ ] 一张两条路径的对比图（无 session / 有 session）；
- [ ] 一次重启恢复成功的记录；
- [ ] 一次 RAG 命中记录（问题 + 引用片段）。

---

## 6. 自测题

1. 不带 `sessionId` 的请求，`AgentStateStore` 是什么实现？请求结束后状态去哪了？
2. `saveSession()` 为什么只 `touch()`？如果它真的没保存，历史为什么还在？
3. `AgentStateStore` 的逻辑隔离键是什么？本项目普通 SINGLE 路径实际用的是哪个值？
4. `sessionType` 在请求里传 `memory`，但 agent 配置是 `json`，最终用哪个？
5. `rag-chat` 和 `rag-agent` 的检索时机有什么本质区别？

<details>
<summary>参考答案</summary>

1. `InMemoryAgentStateStore`，请求结束后随对象被 GC，不会保留。
2. 因为持久化由框架在每轮调用后通过 `AgentStateStore` 完成；`saveSession` 只更新“最近访问时间”。
3. 逻辑键是 `(userId, sessionId, key)`；但本项目普通 SINGLE 路径用 `.defaultSessionId(agentId)` 且未传 `RuntimeContext`
   ，所以实际按 **agent 维度** 落盘。Supervisor 路径才显式传 `(userId, sessionId)`。
4. 请求级参数优先（`resolveSessionType` 先看 `requestType`）。
5. `rag-chat` 每轮无条件检索；`rag-agent` 把检索封装成工具，由模型判断是否需要调用。

</details>

---

## 7. 排错

| 现象                 | 排查                                                   |
|--------------------|------------------------------------------------------|
| 重启后失忆              | agent 是否 `json`/分布式；`sessionId` 是否一致；`userId` 是否一致   |
| 找不到状态文件            | 默认路径是 `${user.home}/.agentscope/demo-sessions`；看启动日志 |
| 上传知识库报错            | 文件类型是否支持（PDF/DOCX/TXT/MD）；大小是否超限                     |
| RAG 回答“没有相关信息”     | 索引是否 READY；`scoreThreshold` 是否过高；问题与文档是否相关           |
| redis profile 启动失败 | Redis 是否在 6379；`REDIS_URL` 是否正确                      |

---

## 8. 明天见

状态是“一个 Agent 的记忆”。明天进入第二层：**多个 Agent 如何协作**——11 种编排模式一次讲透。
→ [Day 4 · 多智能体编排](day4-multi-agent.md)
