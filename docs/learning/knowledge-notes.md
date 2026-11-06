# Java 与 AgentScope 知识点

> 用途：记录已经学习或需要定期复习的知识点。
> 与 [knowledge-gaps.md](knowledge-gaps.md) 的区别：本文件记录“知识内容”，盲点文件记录“尚未理解和需要补强的问题”。

## Java 17 新特性

记录日期：2026-11-03  
项目基线：Java 17 LTS、Spring Boot 3.5.14、AgentScope Java 2.0.3

### 1. 先理解版本边界

这里比较的是 Java 8 与 Java 17 的**正式语言特性**。Java 17 是 LTS
版本，但表中的语法并不都是在 Java 17 才出现，而是在 Java 9–17
之间陆续成为正式特性。

| 特性                        | 状态 | 正式版本    |
|---------------------------|----|---------|
| 模块声明 `module-info.java`   | 正式 | Java 9  |
| 私有接口方法                    | 正式 | Java 9  |
| try-with-resources 使用已有变量 | 正式 | Java 9  |
| 匿名类使用菱形运算符                | 正式 | Java 9  |
| `var` 局部变量类型推断            | 正式 | Java 10 |
| Lambda 参数使用 `var`         | 正式 | Java 11 |
| Switch 表达式                | 正式 | Java 14 |
| 文本块                       | 正式 | Java 15 |
| `instanceof` 模式匹配         | 正式 | Java 16 |
| Record 类                  | 正式 | Java 16 |
| Sealed 密封类/接口             | 正式 | Java 17 |

注意：`switch` 模式匹配、Record Pattern 和虚拟线程不是 Java 17 的正式特性。Java 17 中 `switch` 模式匹配仍属于
preview，不能在普通生产构建中直接使用。

下文按“典型 Spring Boot 业务代码中的常见程度”从高到低排列。这个顺序
是工程经验判断，不是语言规范给出的客观排名；具体项目会因代码风格而不同。

### 2. 使用频率总览

| 排名 | 语法特性                      | 常见程度 | 典型用途                 |
|----|---------------------------|------|----------------------|
| 1  | `var` 局部变量类型推断            | 很高   | 缩短局部变量声明             |
| 2  | Switch 表达式                | 很高   | 状态映射、配置分支、消除 `break` |
| 3  | 文本块                       | 高    | SQL、JSON、Prompt、测试数据 |
| 4  | `instanceof` 模式匹配         | 高    | 事件处理、类型分派            |
| 5  | Record                    | 中高   | DTO、事件、查询结果、值对象      |
| 6  | try-with-resources 使用已有变量 | 中    | 流、文件、连接等资源管理         |
| 7  | 匿名类使用菱形运算符                | 中低   | 少量匿名实现和测试辅助代码        |
| 8  | Sealed 密封类/接口             | 中低   | 有限状态、封闭结果集合          |
| 9  | 私有接口方法                    | 低    | 抽取接口默认方法中的公共逻辑       |
| 10 | Lambda 参数使用 `var`         | 低    | 参数注解、显式可变参数          |
| 11 | Java 模块声明                 | 很低   | 非 Spring 的传统模块化项目    |

`Stream.toList()`、`List.of()`、`Map.of()`、`HttpClient` 等属于新增 API，
不是新语法。它们同样常用，但不应混进这份语法排名。

### 3. `var`：局部变量类型推断

编译器根据初始化表达式推断局部变量的类型。它仍然是静态类型，不是
JavaScript 那样的动态类型。

```java
var users = userRepository.findAll();
var requestId = UUID.randomUUID().toString();

for (var user : users) {
    System.out.println(user.getName());
}
```

使用边界：

- 只能用于有初始化表达式的局部变量、增强 `for` 循环变量和传统 `for`
  循环的初始化变量；
- 不能用于字段、方法参数、方法返回值；
- 不能只写 `var value;`；
- 变量名、空值推断和可读性比少写几个字符更重要，类型不明显时应保留
  显式类型。

### 4. Switch 表达式

Switch 从语句扩展为可以产生值的表达式，箭头语法不再需要容易遗漏的
`break`。复杂分支可以用代码块并通过 `yield` 返回值。

```java
String category = switch (messageType) {
    case "error" -> "failure";
    case "done" -> "complete";
    default -> "in_progress";
};
```

```java
int priority = switch (level) {
    case "high" -> 1;
    case "medium" -> 2;
    case "low" -> {
        int calculated = calculatePriority();
        yield calculated;
    }
    default -> 3;
};
```

项目中的 `AgentFactory`、`HarnessAgentFactory` 使用箭头 Switch 做配置解析。

注意：普通箭头 Switch 是正式特性；类型模式匹配 Switch 在 Java 17 中仍是
preview。

### 5. 文本块

文本块适合书写多行 SQL、JSON、Prompt 和测试数据。

```java
String prompt = """
        你是一个 Java 后端助手。
        回答时先说明结论，再解释原因。
        """;
```

```java
String sql = """
        SELECT id, name
        FROM users
        WHERE tenant_id = ?
        """;
```

常见注意点：

- 文本块使用三个双引号；
- 结束分隔符的位置会影响公共缩进；
- 字符串仍可通过 `formatted(...)` 做格式化；
- 不应把用户输入直接拼进 SQL 文本块。

### 6. `instanceof` 模式匹配

传统写法：

```java
if (event instanceof TextBlockDeltaEvent) {
    TextBlockDeltaEvent textEvent = (TextBlockDeltaEvent) event;
    String text = textEvent.getText();
}
```

Java 17 正式写法：

```java
if (event instanceof TextBlockDeltaEvent textEvent) {
    String text = textEvent.getText();
}
```

项目中的 [AgentEventMapper.java](../../src/main/java/com/skloda/agentscope/runtime/AgentEventMapper.java)
大量使用这种写法。

### 7. Record：不可变数据载体

Record 自动生成构造器、访问器、`equals`、`hashCode` 和 `toString`，适合表达 DTO、事件、查询结果和值对象。

```java
public record ValidationResult(
        boolean valid,
        List<String> missingFields,
        String message) {
}
```

项目中的 `ValidationResult` 就使用了这种结构，对应
[StructuredOutputValidator.java](../../src/main/java/com/skloda/agentscope/runtime/StructuredOutputValidator.java)。

使用边界：

- 适合不可变的数据载体；
- 字段默认是 `private final`；
- 可以定义紧凑构造器进行参数校验；
- 通常不适合作为 JPA Entity，因为 JPA 通常需要无参构造器和可变状态；
- Record 不是“更短的普通 Bean”，它的语义是透明、不可变的数据载体。

### 8. try-with-resources 使用已有变量

Java 9 起，已经声明且满足 effectively final 的资源变量可以直接放进
`try` 的括号，无需再声明一次。

```java
InputStream input = Files.newInputStream(path);
try (input) {
    return input.readAllBytes();
}
```

资源较多时也可以配合 `var`：

```java
try (var reader = Files.newBufferedReader(path);
     var writer = Files.newBufferedWriter(outputPath)) {
    reader.transferTo(writer);
}
```

### 9. 匿名类使用菱形运算符

Java 9 允许匿名类根据目标类型推断泛型参数。

```java
Comparator<String> comparator = new Comparator<>() {
    @Override
    public int compare(String left, String right) {
        return left.compareToIgnoreCase(right);
    }
};
```

以前内部类必须显式写 `new Comparator<String>()`。日常更常用 Lambda，
所以这个语法在 Java 17 业务代码中出现得较少。

### 10. Sealed：限制谁能实现接口

Sealed 类或接口可以明确列出允许的子类型，用于表达封闭的业务结果集合。

```java
public sealed interface AgentResult
        permits Success, Failure {
}

public record Success(String content) implements AgentResult {
}

public record Failure(String message) implements AgentResult {
}
```

适用场景：

- 路由结果；
- 成功/失败/待确认等有限状态；
- 领域模型中的封闭类型集合。

Java 17 只能稳定使用 Sealed 类型本身。虽然可以配合 `instanceof` 模式匹配处理子类型，但针对 Sealed 类型的穷尽 `switch`
模式匹配仍是 preview，不能宣称 Java 17 已正式支持。

### 11. 私有接口方法

接口中的 `private` 方法可以抽取多个 `default` 或 `static` 方法共享的
实现细节，避免把辅助方法暴露给实现类。

```java
public interface NotificationService {
    default void send(String message) {
        validate(message);
        deliver(message);
    }

    void deliver(String message);

    private void validate(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }
}
```

私有接口方法不能是抽象方法，也不能被接口实现类直接调用。

### 12. Lambda 参数使用 `var`

Java 11 允许 Lambda 参数使用 `var`，主要价值是能自然添加参数注解，
而不是为了缩短代码。

```java
BiFunction<Integer, Integer, Integer> add =
        (var left, var right) -> left + right;
```

```java
Stream<String> names = Stream.of("Alice", "Bob");
names.map((@NotNull var name) -> name.trim());
```

参数使用 `var` 时，所有参数都必须显式写出 `var`；不能一个写 `var`、
另一个省略。

### 13. Java 模块声明

Java 9 引入 Java Platform Module System，模块声明写在
`module-info.java` 中。

```java
module demo.app {
    requires java.sql;
    exports com.example.demo.api;
}
```

这是 Java 的模块系统，不是 Maven 的 `module` 结构。常规 Spring Boot
应用很少主动声明 `module-info.java`，因此使用频率最低。

### 14. 容易与语法混淆的变化

以下变化常被归入 Java 17 新特性，但它们不是新增语法：

- 更有帮助的 NullPointerException 信息是运行时诊断能力；
- 强封装 JDK 内部 API 是 JVM/平台策略变化；
- 上下文相关反序列化过滤器是安全 API；
- 增强的伪随机数生成器是标准库 API；
- macOS AArch64 支持、恢复严格的浮点语义、移除 Applet API 等是平台变化。

### 15. 后端开发常见误区

- 把 Record 当可变 JavaBean 或 JPA Entity；
- 到处使用 `var`，反而让关键业务类型难以阅读；
- 认为 Sealed 子类可以来自任意包和任意类；
- 把 Java 17 的 preview 特性直接用于生产构建；
- 把新增标准库 API 误称为新语法；
- 忽略 Maven 的 `--release 17` 与运行 JVM 版本的差异；
- 把 `--add-opens` 当成 JDK 17 兼容问题的长期解决方案。

### 复习检查

- [ ] 能区分哪些特性在 Java 17 正式发布，哪些更早正式发布；
- [ ] 能说明 `var` 可以使用和不能使用的位置；
- [ ] 能用 Record 表达一个不可变 DTO；
- [ ] 能解释 Sealed 类型的用途和限制；
- [ ] 能改写一段传统 `instanceof + cast`；
- [ ] 能使用 Switch 表达式、`yield` 和文本块；
- [ ] 能用已有变量改写一段 try-with-resources；
- [ ] 能区分 Java 模块和 Maven 模块；
- [ ] 能说出 Java 17 中仍是 preview 的常见 feature；
- [ ] 能说明 JPA Entity 为什么通常不适合声明为 Record；
