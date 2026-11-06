# Day 2 · Agent 是怎么组装出来的：工具、技能、多模态

> 时长：约 4 小时
> 目标：能从零写出一个等价的 `ReActAgent.builder()`，并独立新增「工具 + 技能 + Agent」三件套。

---

## 今日目标（可检验）

- [ ] 说清 `ReActAgent.builder()` 里至少 6 个关键配置项的作用；
- [ ] 新增一个 `@Tool` 类，不改任何框架代码就在 UI 里调到；
- [ ] 新增一个 `SKILL.md`，并绑定到 agent；
- [ ] 写一个不依赖 API Key 的工具单测并跑绿；
- [ ] 说清 `userTools` / `systemTools` / `skills` / MCP 四者的区别。

---

## 1. 概念映射

| 概念        | AgentScope 2.0 API                                    | 本项目代码                                 |
|-----------|-------------------------------------------------------|---------------------------------------|
| Agent 构建器 | `ReActAgent.builder()`                                | `agent/AgentFactory.java:138-143`     |
| 模型解析      | `ModelRegistry.resolve(id, ctx)`                      | `model/ModelFactory.java:45-53`       |
| 工具容器      | `Toolkit`                                             | `agent/AgentFactory.java:151`         |
| 工具声明      | `@Tool` / `@ToolParam`                                | `tool/SimpleTools.java`               |
| 技能仓库      | `ClasspathSkillRepository` + `DynamicSkillMiddleware` | `agent/AgentFactory.java:298-313`     |
| 多模态消息     | `TextBlock` + `ImageBlock` / `AudioBlock`             | `model/MultiModalMessage.java:40-52`  |
| 配置加载      | YAML → `AgentConfig`                                  | `agent/AgentConfigService.java:41-76` |

---

## 2. 先看框架调用骨架（5 分钟）

打开 `src/main/java/com/skloda/agentscope/agent/AgentFactory.java`，只看 `buildAgent`（131-207）：

```java
ReActAgent.Builder builder = ReActAgent.builder()
        .name(config.getName())
        .sysPrompt(config.getSystemPrompt())
        .model(model)
        .stateStore(stateStore)
        .defaultSessionId(agentId);

Toolkit toolkit = new Toolkit();
registerToolsAndSkills(builder, toolkit, config, agentId);   // 工具 + 技能
registerMcpTools(config, toolkit);                            // MCP
// 可选：RAG、长期记忆、中间件
builder.enablePendingToolRecovery(true);

return builder.build();
```

**这就是 AgentScope 的全部“组装学”**。剩下的都是配置分支。

带着三个问题读：

1. `name` 和 `defaultSessionId` 有什么区别？（前者是显示/事件里的名字，后者是状态键）
2. `stateStore` 是必填吗？（单次无状态调用可以给 `InMemoryAgentStateStore`）
3. `enablePendingToolRecovery(true)` 解决什么问题？（上一轮工具执行到一半进程挂了，下一轮能继续）

---

## 3. 动手实验

### Lab 2.1：先验证内置工具链路（10 分钟）

跑 `tool-test-simple`，观察 `tool_start` / `tool_end`：

```bash
curl -N -sS -X POST http://localhost:8081/chat/send \
  -H 'Content-Type: application/json' \
  -d '{"agentId":"tool-test-simple","message":"上海天气怎么样？现在几点？"}' \
  | grep '^data:'
```

对照 `tool/SimpleTools.java`：

- `@Tool(name = "get_weather", ...)`；
- `@ToolParam(name = "city", ...)`；
- 返回值就是普通的 `String`。

**结论**：工具不需要实现任何接口，只要注解 + 返回值可序列化。

### Lab 2.2：新增一个工具类（30 分钟）

**第 1 步**：创建 `src/main/java/com/skloda/agentscope/tool/TextStatsTool.java`

```java
package com.skloda.agentscope.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

public class TextStatsTool {

    @Tool(name = "text_stats",
          description = "统计一段文本的字符数、汉字数、英文单词数和行数。当用户要求字数统计、文本分析时使用。")
    public String textStats(
            @ToolParam(name = "text", description = "要统计的文本内容") String text) {
        if (text == null || text.isEmpty()) {
            return "文本为空，无法统计";
        }
        int chars = text.length();
        long han = text.codePoints()
                .filter(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)
                .count();
        int words = text.trim().isEmpty() ? 0 : text.trim().split("\\s+").length;
        long lines = text.lines().count();
        return String.format("字符数=%d, 汉字数=%d, 英文单词数=%d, 行数=%d",
                chars, han, words, lines);
    }
}
```

**不需要改 `ToolRegistry`**：`ToolRegistry` 构造时会扫描 `com.skloda.agentscope.tool` 包下所有带 `@Tool` 的类（
`ToolRegistry.java:34,73-96`）。这是本项目比 `CLAUDE.md` 里“手动注册”更新的做法。

**第 2 步**：在 `src/main/resources/config/agents.yml` 末尾追加：

```yaml
  - agentId: text-stats-agent
    category: single
    name: Text Stats Agent
    description: 文本统计助手
    systemPrompt: |
      你是一个文本统计助手。
      当用户提供文本并要求统计时，必须调用 text_stats 工具，不要自己心算。
      拿到结果后，用简洁的中文复述关键数字。
    modelName: deepseek-v4.1-flash-1017
    streaming: true
    enableThinking: false
    skills: []
    userTools:
      - text_stats
    systemTools: []
    samplePrompts:
      - prompt: "帮我统计这段文字：AgentScope 是一个 Java Agent 框架。它支持工具调用。"
        expectedBehavior: "调用 text_stats 并复述统计结果"
```

**第 3 步**：重启（配置在启动时加载），验证：

```bash
mvn -q -DskipTests compile
# Ctrl+C 停掉旧进程后
mvn spring-boot:run
```

```bash
curl -N -sS -X POST http://localhost:8081/chat/send \
  -H 'Content-Type: application/json' \
  -d '{"agentId":"text-stats-agent","message":"统计：AgentScope 是一个 Java Agent 框架。"}'
```

**验收**：输出里出现 `tool_start`，工具名为 `text_stats`，且最终回答包含“汉字数=…”。

> 排错：如果出现 `Tool not registered: text_stats`，说明类没编译进 target 或包名不在 `com.skloda.agentscope.tool` 下。

### Lab 2.3：新增一个技能（30 分钟）

技能 = **给模型看的操作手册（SKILL.md）+ 一组工具的打包**。它适合“多步骤、有固定流程”的能力。

**第 1 步**：创建 `src/main/resources/skills/text-report/SKILL.md`

```markdown
---
name: text-report
description: "当用户需要统计文本并生成简要报告时使用。触发词：字数统计、文本报告、统计这段文字。"
tools: text_stats
---

# Text Report

## 步骤

1. 调用 `text_stats` 获取统计结果。
2. 用 Markdown 表格输出：指标 | 数值。
3. 给出一句话结论（例如“这是一段约 20 字的中文文本”）。

## 注意

- 不要自行估算数字，必须使用工具返回结果。
- 表格之后不要再重复大段原文。
```

`tools` 必须引用已经注册的工具名（这里就是 Lab 2.2 的 `text_stats`）。

**第 2 步**：把 agent 的 `skills` 改成：

```yaml
    skills:
      - text-report
    userTools: []
```

（技能会自动把 `text_stats` 注册进 Toolkit，见 `AgentFactory.java:301-305`，所以 `userTools` 不用再写。）

**第 3 步**：重启，问一个会触发技能的问题：

```
请统计下面这段话并给我一份报告：Spring Boot 让 Java 开发变简单了。
```

**验收**：回答是表格形式；调试面板里可能出现 `load_skill_through_path` 或技能相关工具调用。

> 为什么需要技能，而不是只给工具？工具只告诉模型“能做什么”，技能告诉它“按什么流程做、输出成什么样”。复杂任务里，后者往往决定效果上限。

### Lab 2.4：写第一个单测（20 分钟）

创建 `src/test/java/com/skloda/agentscope/tool/TextStatsToolTest.java`：

```java
package com.skloda.agentscope.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextStatsToolTest {

    private final TextStatsTool tool = new TextStatsTool();

    @Test
    void countsChineseCharactersAndLines() {
        String result = tool.textStats("你好 world\n第二行");
        assertTrue(result.contains("汉字数=5"), result);
        assertTrue(result.contains("行数=2"), result);
    }

    @Test
    void handlesEmptyText() {
        assertEquals("文本为空，无法统计", tool.textStats(""));
    }
}
```

运行：

```bash
mvn test -Dtest=TextStatsToolTest
```

**验收**：2 个测试通过，**不需要 API Key**。

这就是本项目 379 个测试的典型形态：纯 Java 单测，不碰模型。只有少量集成测试才依赖真实模型。

### Lab 2.5：多模态（20 分钟，可选）

视觉和语音只是“内容块不同”，Agent 组装方式完全一样。

1. 在 UI 上传一张图片，系统会自动切到 `vision-analyzer`；
2. 或直接调接口（先上传拿 `filePath`）：

```bash
curl -s -X POST http://localhost:8081/chat/upload \
  -F "file=@/path/to/your.png"
# 返回 {"filePath":"...","fileName":"...","fileType":"image"}

curl -N -sS -X POST http://localhost:8081/chat/send \
  -H 'Content-Type: application/json' \
  -d '{
    "agentId":"vision-analyzer",
    "message":"图片里有什么？",
    "images":[{"path":"上一步返回的 filePath","fileName":"your.png"}]
  }'
```

读 `model/MultiModalMessage.java:40-52`，确认它是怎么把 `TextBlock` + `ImageBlock` + `Base64Source` 拼成一个 `Msg` 的。

---

## 4. 四种“给 Agent 加能力”的方式

| 方式     | 配置字段          | 谁执行               | 适合             | 本项目例子                                    |
|--------|---------------|-------------------|----------------|------------------------------------------|
| 业务工具   | `userTools`   | 本进程 Java 方法       | 确定性计算、查库、调 API | `get_current_time`                       |
| 系统工具   | `systemTools` | 框架内置              | 读写文件、执行命令      | `view_text_file`、`execute_shell_command` |
| 技能     | `skills`      | 工具 + SKILL.md 提示词 | 多步骤、有 SOP 的任务  | `docx`、`pdf`、`text-report`               |
| MCP 工具 | `mcpServers`  | 外部进程/服务           | 复用生态、跨语言工具     | `mcp-filesystem`                         |

选择顺序建议：**能写 Java 工具就别上 MCP；单步能力用 tool，多步流程用 skill；系统工具默认别开，安全风险最高。**

---

## 5. 今日产出

- [ ] `TextStatsTool.java` + `TextStatsToolTest.java`；
- [ ] `skills/text-report/SKILL.md`；
- [ ] `text-stats-agent` 能在 UI 中回答统计请求；
- [ ] 一句话总结：*“Agent 的能力边界 = ______ + ______ + ______。”*

---

## 6. 自测题

1. `ReActAgent.builder()` 中 `name` 和 `defaultSessionId` 分别影响什么？
2. 新增工具类后为什么不用改 `ToolRegistry`？扫描的包名是什么？
3. `userTools` 里写了某个工具，但 `skills` 也引用了它，会发生重复注册吗？
4. `systemTools` 里的 `execute_shell_command` 有什么风险？生产环境怎么控制？
5. 技能和工具的本质区别是什么？

<details>
<summary>参考答案</summary>

1. `name` 用于事件/显示（`agent_start` 里的名字）；`defaultSessionId` 是状态存储的默认键。
2. 因为 `ToolRegistry` 构造时自动扫描 `com.skloda.agentscope.tool` 包（`autoScanToolClasses`）。
3. 不会。`AgentFactory.registerToolsAndSkills` 会收集技能覆盖的工具名，并从 `userTools` 里过滤掉（
   `AgentFactory.java:321-330`）。
4. 任意命令执行/文件读写，属于高危能力；本项目用 `PermissionContextFactory` + `permissionMode` 做限制（见
   `permission-demo`）。
5. 工具是“一个函数”；技能是“一套流程说明 + 相关工具”，通过 SKILL.md 影响模型的行为策略。

</details>

---

## 7. 排错

| 现象                         | 排查                                                         |
|----------------------------|------------------------------------------------------------|
| 新 agent 不出现在 `/api/agents` | YAML 缩进错误；`AgentConfigService` 启动日志有解析异常                   |
| 模型不调工具                     | `@Tool.description` 太模糊；system prompt 没要求“必须调用工具”          |
| `Tool not registered`      | 类不在 `com.skloda.agentscope.tool` 包；没重新编译                   |
| 技能没生效                      | `SKILL.md` frontmatter 的 `name`/`tools` 缺失；`tools` 指向未注册工具 |
| 多模态报错                      | 图片路径不是服务端可读的绝对路径；文件超过 50MB                                 |

---

## 8. 明天见

工具和技能解决“能做什么”。明天解决“**为什么它记得上一轮**”——会话、状态、持久化与 RAG。
→ [Day 3 · 会话、状态、记忆与 RAG](day3-state-memory-rag.md)
