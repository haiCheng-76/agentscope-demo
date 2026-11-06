# 毕业项目 · 会议纪要助手

> 预计 4–6 小时。目标：只用本项目已有的模式与 API，独立完成一个“能演示、能测试、能扩展”的 Agent。

---

## 1. 需求

用户粘贴一段会议记录（或上传 `.txt` / `.docx`），Agent 输出结构化会议纪要：

```markdown
# 产品周会 · 2026-11-03

## 参会人
张三、李四、王五

## 关键决策
1. 下季度优先做移动端适配。
2. 暂缓数据看板二期。

## 行动项
| 负责人 | 事项 | 截止时间 |
|---|---|---|
| 李四 | 出移动端技术方案 | 2026-11-07 |
| 王五 | 整理用户反馈 | 2026-11-05 |

## 风险与待确认
- 移动端人力是否足够尚未确认。
```

要求：

1. 通过**工具**做确定性处理（抽取行动项、生成 Markdown），不让模型凭空编；
2. 通过**技能**约束输出格式与步骤；
3. 支持**多轮会话**（“把刚才的行动项按负责人分组再列一遍”）；
4. 至少有 **1 个单元测试**；
5. 可选：做成 `SEQUENTIAL` 两段式（抽取 → 撰写）。

---

## 2. 实施步骤

### Step 1：写工具类

创建 `src/main/java/com/skloda/agentscope/tool/MeetingMinutesTool.java`：

```java
package com.skloda.agentscope.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.ArrayList;
import java.util.List;

public class MeetingMinutesTool {

    @Tool(name = "extract_action_items",
          description = "从会议记录中抽取行动项。识别包含“负责/待办/TODO/截止/完成”等关键词的句子。")
    public String extractActionItems(
            @ToolParam(name = "transcript", description = "原始会议记录文本") String transcript) {
        if (transcript == null || transcript.isBlank()) {
            return "未提供会议记录";
        }
        List<String> items = new ArrayList<>();
        for (String raw : transcript.split("[。\\n]")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.contains("负责") || line.contains("待办") || line.contains("TODO")
                    || line.contains("截止") || line.contains("完成")) {
                items.add(line);
            }
        }
        if (items.isEmpty()) {
            return "未识别到明确行动项";
        }
        StringBuilder sb = new StringBuilder("识别到 " + items.size() + " 条行动项：\n");
        for (int i = 0; i < items.size(); i++) {
            sb.append(i + 1).append(". ").append(items.get(i)).append('\n');
        }
        return sb.toString();
    }

    @Tool(name = "format_minutes",
          description = "把会议要素格式化为 Markdown 会议纪要，包含标题、参会人、关键决策、行动项、风险。")
    public String formatMinutes(
            @ToolParam(name = "title", description = "会议标题") String title,
            @ToolParam(name = "attendees", description = "参会人，逗号分隔") String attendees,
            @ToolParam(name = "decisions", description = "关键决策，分号或换行分隔") String decisions,
            @ToolParam(name = "actionItems", description = "行动项，可包含负责人和截止时间") String actionItems,
            @ToolParam(name = "risks", description = "风险与待确认事项，没有则传空字符串") String risks) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title == null || title.isBlank() ? "会议纪要" : title).append("\n\n");

        sb.append("## 参会人\n");
        sb.append(emptyToDash(attendees)).append("\n\n");

        sb.append("## 关键决策\n");
        for (String d : splitItems(decisions)) {
            sb.append("- ").append(d).append('\n');
        }
        if (splitItems(decisions).isEmpty()) sb.append("- 无\n");
        sb.append('\n');

        sb.append("## 行动项\n");
        for (String a : splitItems(actionItems)) {
            sb.append("- ").append(a).append('\n');
        }
        if (splitItems(actionItems).isEmpty()) sb.append("- 无\n");
        sb.append('\n');

        sb.append("## 风险与待确认\n");
        for (String r : splitItems(risks)) {
            sb.append("- ").append(r).append('\n');
        }
        if (splitItems(risks).isEmpty()) sb.append("- 无\n");

        return sb.toString();
    }

    private List<String> splitItems(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isBlank()) return result;
        for (String part : text.split("[;；\\n]")) {
            String s = part.trim();
            if (!s.isEmpty()) result.add(s);
        }
        return result;
    }

    private String emptyToDash(String text) {
        return text == null || text.isBlank() ? "未记录" : text;
    }
}
```

> 不需要改 `ToolRegistry`：包扫描会自动注册 `extract_action_items` 和 `format_minutes`。

### Step 2：写技能

创建 `src/main/resources/skills/meeting-minutes/SKILL.md`：

```markdown
---
name: meeting-minutes
description: "当用户提供会议记录并需要整理成会议纪要时使用。触发词：会议纪要、会议记录整理、行动项、待办。"
tools: extract_action_items, format_minutes
---

# Meeting Minutes

## 步骤

1. 先调用 `extract_action_items` 抽取行动项。
2. 从记录中提取：会议标题、参会人、关键决策、风险。
3. 调用 `format_minutes` 生成最终 Markdown。
4. 只输出 `format_minutes` 的结果，不要自行改写结构。

## 规则

- 信息缺失时写“未记录”，不要编造。
- 行动项必须保留负责人和截止时间（如果有）。
- 决策和风险分开，不要把讨论过程当成决策。
```

### Step 3：配置 Agent

在 `src/main/resources/config/agents.yml` 末尾追加：

```yaml
  - agentId: meeting-minutes-assistant
    category: demo
    type: SINGLE
    name: 会议纪要助手
    description: 把会议记录整理成结构化纪要，支持多轮追问
    systemPrompt: |
      你是一个会议纪要助手。
      当用户提供会议记录时，必须使用 meeting-minutes 技能：
      先抽取行动项，再格式化为 Markdown 会议纪要。
      信息缺失时写“未记录”，禁止编造。
      用户追问时，基于当前会话中已生成的纪要回答。
    modelName: deepseek-v4.1-flash-1017
    streaming: true
    enableThinking: false
    skills:
      - meeting-minutes
    userTools: []
    systemTools: []
    sessionConfig:
      defaultType: json
    samplePrompts:
      - prompt: |
          整理会议纪要：
          参会人：张三、李四、王五
          决策：下季度优先做移动端适配。数据看板二期暂缓。
          李四负责出移动端技术方案，截止 11 月 7 日。
          王五负责整理用户反馈，截止 11 月 5 日。
          风险：移动端人力是否足够尚未确认。
        expectedBehavior: "输出包含参会人、关键决策、行动项、风险的 Markdown 纪要"
```

### Step 4：写单元测试

创建 `src/test/java/com/skloda/agentscope/tool/MeetingMinutesToolTest.java`：

```java
package com.skloda.agentscope.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MeetingMinutesToolTest {

    private final MeetingMinutesTool tool = new MeetingMinutesTool();

    @Test
    void extractsActionItemsByKeyword() {
        String transcript = "李四负责出移动端技术方案，截止 11 月 7 日。"
                + "大家讨论了很久。王五负责整理用户反馈。";
        String result = tool.extractActionItems(transcript);
        assertTrue(result.contains("李四负责"), result);
        assertTrue(result.contains("王五负责"), result);
        assertTrue(result.contains("2 条"), result);
    }

    @Test
    void formatsMarkdownWithAllSections() {
        String md = tool.formatMinutes(
                "产品周会", "张三,李四", "做移动端;暂缓数据看板",
                "李四负责移动端方案;王五整理反馈", "人力不足");
        assertTrue(md.contains("# 产品周会"), md);
        assertTrue(md.contains("## 参会人"), md);
        assertTrue(md.contains("## 关键决策"), md);
        assertTrue(md.contains("## 行动项"), md);
        assertTrue(md.contains("## 风险与待确认"), md);
    }

    @Test
    void handlesMissingFields() {
        String md = tool.formatMinutes("临时会", "", "", "", "");
        assertTrue(md.contains("未记录"), md);
        assertTrue(md.contains("无"), md);
    }
}
```

运行：

```bash
mvn test -Dtest=MeetingMinutesToolTest
```

### Step 5：端到端验证

```bash
mvn -q -DskipTests compile
mvn spring-boot:run
```

1. UI 选择 `会议纪要助手`，粘贴 `samplePrompts` 里的会议记录；
2. 检查输出四段齐全；
3. 追问：“把行动项按负责人分组再列一遍”；
4. 再追问：“我们决定了什么？”——验证会话记忆；
5. 重启应用，用同一 `sessionId` 继续追问，验证 `json` 持久化。

---

## 3. 加分项（任选）

| 加分项       | 做法                                                                        | 涉及知识         |
|-----------|---------------------------------------------------------------------------|--------------|
| 两段式流水线    | 新建 `minutes-writer` agent，配置 `type: SEQUENTIAL`，subAgents = [会议纪要助手, 审核员] | Day 4        |
| 多格式输入     | 复用 `parse_docx` / `parse_pdf`，把 `filePath` 传给 Agent                       | Day 2/3      |
| 历史纪要检索    | 把过往纪要放进知识库，`ragEnabled: true`                                             | Day 3        |
| 审批后发布     | 用 `approvalTools` 拦截“发布纪要”工具                                              | Day 5 / HITL |
| Harness 版 | 改成 `type: HARNESS`，让它在工作区里持续维护 `minutes/` 目录                              | Day 5        |
| 可观测       | 在调试面板确认 `tool_start/end`、`agent_end` 事件齐全                                 | Day 1        |

---

## 4. 验收清单

- [ ] `mvn test -Dtest=MeetingMinutesToolTest` 全绿；
- [ ] `/api/agents` 能看到 `meeting-minutes-assistant`；
- [ ] UI 输出包含四个二级标题；
- [ ] 追问能利用上一轮纪要；
- [ ] 重启后同 `sessionId` 仍能追问；
- [ ] 至少完成 1 个加分项；
- [ ] 能向别人解释：为什么行动项抽取要用工具，而不是让模型直接写。

---

## 5. 自评维度

| 维度  | 1 分     | 3 分     | 5 分            |
|-----|---------|---------|----------------|
| 正确性 | 输出缺段    | 四段齐全    | 缺失信息不编造        |
| 工程性 | 全写死     | 配置驱动    | 工具 + 技能 + 测试齐全 |
| 可观测 | 只有文本    | 能看到工具调用 | 能解释事件时序        |
| 可扩展 | 单 Agent | 支持多轮    | 完成加分项并说明取舍     |

---

## 6. 完成后

你已经走完“读框架 → 用框架 → 造 Agent”的闭环。下一步建议：

1. 把毕业项目写成一篇设计稿（参考 `docs/superpowers/specs/` 的格式）；
2. 给项目补一个端到端测试（参考 `ChatControllerStreamTest`）；
3. 回到 [README](README.md) 的自测表，确认五天目标全部达成。
