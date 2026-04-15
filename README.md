# OpenClaw Java SDK Demo 使用说明

## 1. 项目简介

本项目是一个基于 Java 的 OpenClaw SDK Demo。

它既可以作为控制台示例程序直接运行，也可以打包为 `jar` 后提供给其他 Java 项目引入调用。当前项目提供了这些能力：

- OpenClaw 网关连接配置初始化
- 多种客户端初始化方式
- 原始协议调用能力
- 返回解析后纯文本的对话调用能力
- 返回原始 `event` 消息体列表的对话调用能力
- 返回小塔 APP 兼容事件对象列表的对话调用能力
- 定时任务创建、执行、删除、查询能力
- 控制台对话 Demo

如果你的目标是把它作为 SDK 集成到其他项目中，重点关注“SDK 使用方式”和“对话调用方式”章节。

如果你的目标是直接体验当前项目，重点关注“快速开始”和“Demo 运行说明”章节。

## 2. 环境要求

- JDK 8
- Maven 3.8+
- 可访问的 OpenClaw 网关
- 可用的网关 Token

当前 Maven 坐标：

```xml
<groupId>com.example</groupId>
<artifactId>openclaw-java-sdk-demo</artifactId>
<version>1.0.0-SNAPSHOT</version>
```

默认打包产物：

```text
target/openclaw-java-sdk-demo-1.0.0-SNAPSHOT.jar
```

说明：

- 该产物适合作为普通 SDK jar 被其他项目依赖。
- 当前项目已配置可执行 jar 打包，执行 `mvn clean package` 后可直接通过 `java -jar` 启动控制台 Demo。
- 控制台 Demo 推荐通过 Maven 的 `exec:java` 方式启动。

## 3. 快速开始

### 3.1 编译项目

```bash
mvn clean compile
```

### 3.2 打包项目

```bash
mvn clean package
```

### 3.3 运行控制台 Demo

通过 JVM 参数传入 Token：

```bash
mvn exec:java -Dopenclaw.token=你的token
```

也可以直接启动打包后的可执行 jar：

```bash
java -Dopenclaw.token=你的token -jar target/openclaw-java-sdk-demo-1.0.0-SNAPSHOT.jar
```

同时指定网关地址与 Token：

```bash
mvn exec:java -Dopenclaw.gateway=ws://127.0.0.1:18789 -Dopenclaw.token=你的token
```

显式指定返回解析后的纯文本：

```bash
mvn exec:java -Dopenclaw.token=你的token -Dopenclaw.responseFormat=text
```

显式指定返回原始 event：

```bash
mvn exec:java -Dopenclaw.token=你的token -Dopenclaw.responseFormat=event
```

也可以通过环境变量传入 Token：

```bash
set OPENCLAW_GATEWAY_TOKEN=你的token
mvn exec:java
```

运行后会进入控制台对话模式：

- 输入问题后按回车发送
- 输入 `exit` 或 `quit` 退出

### 3.4 最小可运行 SDK 示例

```java
import com.example.openclaw.client.OpenClawClient;

public class QuickStartExample {
    public static void main(String[] args) {
        try (OpenClawClient client = OpenClawClient
                .init("ws://127.0.0.1:18789", "你的token")
                .join()) {
            String reply = client.sendChatText("你好，请介绍一下自己").join();
            System.out.println("assistant> " + reply);
        }
    }
}
```

## 4. 其他项目引入

### 4.1 本地安装到 Maven 仓库

如果当前 SDK 还没有发布到私服，建议先执行：

```bash
mvn clean install
```

然后在其他项目中通过标准 Maven 依赖方式引入：

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>openclaw-java-sdk-demo</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 4.2 发布到私服后引入

如果后续你将该 SDK 发布到了公司 Maven 私服，可以在消费方项目中配置仓库后按同样的依赖坐标引入。

示例：

```xml
<repositories>
    <repository>
        <id>company-maven</id>
        <name>Company Maven Repository</name>
        <url>https://your-maven-repository/repository/maven-public/</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>com.example</groupId>
        <artifactId>openclaw-java-sdk-demo</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </dependency>
</dependencies>
```

## 5. SDK 核心类

常用类：

- `com.example.openclaw.client.OpenClawConfig`
- `com.example.openclaw.client.OpenClawClient`
- `com.example.openclaw.model.OpenClawMessage`
- `com.example.openclaw.model.TowerAppSseEvent`

`OpenClawMessage` 是统一消息模型，主要字段如下：

| 字段        | 类型         | 说明                          |
| --------- | ---------- | --------------------------- |
| `type`    | `String`   | 消息类型，例如 `req`、`res`、`event` |
| `event`   | `String`   | 事件名称，仅 `event` 类型使用         |
| `id`      | `String`   | 请求或响应 ID                    |
| `method`  | `String`   | 方法名，例如 `chat.send`          |
| `ok`      | `Boolean`  | 响应是否成功                      |
| `payload` | `JsonNode` | 请求参数、响应数据或事件内容              |
| `error`   | `JsonNode` | 错误信息                        |

## 6. 初始化方式

### 6.1 使用 `OpenClawConfig.builder()`

适合配置项较多时使用：

```java
import com.example.openclaw.client.OpenClawConfig;

import java.time.Duration;

OpenClawConfig config = OpenClawConfig.builder()
        .gatewayUri("ws://127.0.0.1:18789")
        .authToken("你的token")
        .connectTimeout(Duration.ofSeconds(10))
        .build();
```

### 6.2 使用 `OpenClawConfig.of(...)`

适合快速构造配置：

```java
OpenClawConfig.of("ws://127.0.0.1:18789");
OpenClawConfig.of("ws://127.0.0.1:18789", "你的token");
OpenClawConfig.of("ws://127.0.0.1:18789", Duration.ofSeconds(10));
OpenClawConfig.of("ws://127.0.0.1:18789", "你的token", Duration.ofSeconds(10));
```

### 6.3 使用构造方法

```java
new OpenClawClient(config);
new OpenClawClient("ws://127.0.0.1:18789");
new OpenClawClient("ws://127.0.0.1:18789", "你的token");
new OpenClawClient("ws://127.0.0.1:18789", Duration.ofSeconds(10));
new OpenClawClient("ws://127.0.0.1:18789", "你的token", Duration.ofSeconds(10));
```

注意：

- 构造方法只负责创建客户端对象
- 如果使用构造方法，仍需手动调用 `connect().join()`

### 6.4 使用 `OpenClawClient.init(...)`

如果你希望“创建并立即连接”，推荐直接使用 `init(...)`：

```java
OpenClawClient.init(config);
OpenClawClient.init("ws://127.0.0.1:18789");
OpenClawClient.init("ws://127.0.0.1:18789", "你的token");
OpenClawClient.init("ws://127.0.0.1:18789", Duration.ofSeconds(10));
OpenClawClient.init("ws://127.0.0.1:18789", "你的token", Duration.ofSeconds(10));
```

这些方法返回 `CompletableFuture<OpenClawClient>`。

推荐写法：

```java
try (OpenClawClient client = OpenClawClient
        .init("ws://127.0.0.1:18789", "你的token")
        .join()) {
    // client 已连接完成
}
```

## 7. 对话调用方式

### 7.1 `sendChat(...)`

返回 `chat.send` 的原始响应消息：

```java
sendChat(String message)
sendChat(String sessionKey, String message)
sendChat(String sessionKey, String message, Duration timeout)
```

示例：

```java
import com.example.openclaw.client.OpenClawClient;
import com.example.openclaw.model.OpenClawMessage;

try (OpenClawClient client = OpenClawClient
        .init("ws://127.0.0.1:18789", "你的token")
        .join()) {
    OpenClawMessage response = client.sendChat("你好").join();
    System.out.println("ok = " + response.ok());
    System.out.println("payload = " + response.payload());
}
```

适用场景：

- 你需要直接读取 `OpenClawMessage`
- 你需要自己解析 `payload`
- 你要先拿到 `runId`

### 7.2 `sendChatText(...)`

返回聚合后的最终纯文本：

```java
sendChatText(String message)
sendChatText(String sessionKey, String message)
sendChatText(String sessionKey, String message, Duration requestTimeout, Duration streamTimeout)
```

示例：

```java
import com.example.openclaw.client.OpenClawClient;

try (OpenClawClient client = OpenClawClient
        .init("ws://127.0.0.1:18789", "你的token")
        .join()) {
    String reply = client.sendChatText("请用一句话介绍 OpenClaw").join();
    System.out.println("assistant> " + reply);
}
```

适用场景：

- 你只关心最终文本结果
- 你不想自己处理流式事件聚合
- 你希望接入代码尽量简单

### 7.3 `sendChatRawEvents(...)`

返回本轮对话产生的原始 `event` 消息体列表：

```java
sendChatRawEvents(String message)
sendChatRawEvents(String sessionKey, String message)
sendChatRawEvents(String sessionKey, String message, Duration requestTimeout, Duration streamTimeout)
```

示例：

```java
import com.example.openclaw.client.OpenClawClient;

import java.util.List;

try (OpenClawClient client = OpenClawClient
        .init("ws://127.0.0.1:18789", "你的token")
        .join()) {
    List<String> events = client.sendChatRawEvents("请输出本轮产生的原始事件").join();
    for (String event : events) {
        System.out.println(event);
    }
}
```

适用场景：

- 你需要保留原始事件
- 你要做调试、审计、落库或回放
- 你要观察完整事件链路

在需要直接对接小塔 APP 响应协议时，可以使用带映射类型的重载方法。

### 7.4 `sendChatRawEvents(..., RawEventMappingType.TOWER_APP)`

返回按小塔 APP 协议映射后的兼容事件对象列表：

```java
sendChatRawEvents(String message, RawEventMappingType mappingType)
sendChatRawEvents(String sessionKey, String message, RawEventMappingType mappingType)
sendChatRawEvents(String sessionKey, String message, Duration requestTimeout, Duration streamTimeout, RawEventMappingType mappingType)
```

示例：

```java
import com.example.openclaw.client.OpenClawClient;
import com.example.openclaw.client.OpenClawClient.RawEventMappingType;
import com.example.openclaw.model.TowerAppSseEvent;

import java.util.List;

try (OpenClawClient client = OpenClawClient
        .init("ws://127.0.0.1:18789", "你的token")
        .join()) {
    List<TowerAppSseEvent> events = client.sendChatRawEvents(
            "请输出适合小塔 APP 消费的响应事件",
            RawEventMappingType.TOWER_APP
    ).join();

    for (TowerAppSseEvent event : events) {
        System.out.println("sseType = " + event.getSseType());
        System.out.println("eventData = " + event.getEventData());
        System.out.println("errorMsg = " + event.getErrorMsg());
    }
}
```

说明：

- 该重载不会改变原有 `sendChatRawEvents(...)` 的行为
- 原有方法仍然返回 `List<String>` 原始事件列表
- 只有显式传入 `RawEventMappingType.TOWER_APP` 时，才会执行小塔 APP 协议映射

映射规则：

- `agent.lifecycle.start` 映射为 `message`，并返回空字符串 `answer`
- `agent.assistant.data.delta` 映射为 `message`，`answer` 取增量文本
- `agent.lifecycle.end` 映射为 `message_end`
- `chat.state=error` 映射为 `error`
- 忽略 `chat.state=delta`，避免和 `agent.assistant.data.delta` 重复拼接

适用场景：

- 你要把 SDK 输出直接适配到小塔 APP 桌面端
- 你希望保留流式事件语义，但不想自己写字段映射逻辑
- 你需要拿到 `sseType / eventData / errorMsg` 这一层兼容结构

### 7.5 `streamChatRawEvents(..., RawEventMappingType.TOWER_APP, Consumer<TowerAppSseEvent>)`

实时回调按小塔 APP 协议映射后的兼容事件对象：

```java
streamChatRawEvents(String message, RawEventMappingType mappingType, Consumer<TowerAppSseEvent> onEvent)
streamChatRawEvents(String sessionKey, String message, RawEventMappingType mappingType, Consumer<TowerAppSseEvent> onEvent)
streamChatRawEvents(String sessionKey, String message, Duration requestTimeout, Duration streamTimeout, RawEventMappingType mappingType, Consumer<TowerAppSseEvent> onEvent)
```

示例：

```java
import com.example.openclaw.client.OpenClawClient;
import com.example.openclaw.client.OpenClawClient.RawEventMappingType;

try (OpenClawClient client = OpenClawClient
        .init("ws://127.0.0.1:18789", "你的token")
        .join()) {
    client.streamChatRawEvents(
            "请实时输出适合小塔 APP 消费的响应事件",
            RawEventMappingType.TOWER_APP,
            event -> System.out.println("event> " + event)
    ).join();
}
```

说明：

- `onEvent` 会在流式过程中按顺序收到每条映射后的 `TowerAppSseEvent`
- 返回的 `CompletableFuture<Void>` 在本轮流式响应结束时完成
- 如果回调抛异常、网关返回错误或流式超时，`CompletableFuture<Void>` 会异常完成
- 当你想保留小塔 APP 兼容结构，同时又要实时消费时，优先使用这个方法

### 7.6 `setEventListener(...)`

如果你需要实时监听事件，可以使用：

```java
client.setEventListener(message -> {
    if ("event".equalsIgnoreCase(message.type())) {
        System.out.println("event = " + message.event());
        System.out.println("payload = " + message.payload());
    }
});
```

说明：

- `setEventListener(...)` 适合实时监听原始 `OpenClawMessage`
- `streamChatRawEvents(..., TOWER_APP, ...)` 适合实时消费小塔 APP 兼容事件
- `sendChatRawEvents(...)` 适合在一次调用结束后统一收集

### 7.7 定时任务相关方法

当前 SDK 额外补充了 4 类定时任务管理能力：

- 创建定时任务 `createScheduleJob(...)`
- 手动执行定时任务 `executeScheduleJob(...)`
- 删除定时任务 `deleteScheduleJob(...)`
- 查询全部定时任务 `listScheduleJobs(...)`

这些方法底层仍然走 `OpenClawClient.call(...)` 这一套协议封装，但需要对齐 Gateway 官方 cron 协议，当前对应的底层方法名分别是：

- `cron.add`
- `cron.run`
- `cron.remove`
- `cron.list`

另外补充了两个便于排查和追踪的接口：

- 查询单个任务状态 `getScheduleJobStatus(...)`，底层对应 `cron.status`
- 查询任务执行记录 `listScheduleJobRuns(...)`，底层对应 `cron.runs`

返回值统一为 `CompletableFuture<OpenClawMessage>`，方便和现有 SDK 的响应处理方式保持一致。

说明：

- `cronExpr` 需要按 Gateway 官方 cron 工具格式传入，推荐直接使用 5 段写法，例如 `0 9 * * *`
- 如果你手头是 Quartz 风格表达式，例如 `0 0 9 * * ?`，SDK 会把其中的 `?` 兼容转换为 `*`
- 便捷重载默认创建 `sessionTarget=isolated` 的任务；如果传入 `sessionKey=main`，则会映射为主会话任务
- 如果需要使用 cron 工具的完整字段，优先使用 `JsonNode` 重载自行透传官方参数

#### 7.7.1 `createScheduleJob(...)`

常用签名：

```java
createScheduleJob(String jobName, String cronExpr, String promptTemplate)
createScheduleJob(
        String jobName,
        String cronExpr,
        String promptTemplate,
        String sessionKey,
        Duration requestTimeout,
        Duration streamTimeout,
        String resultMode,
        Duration timeout
)
createScheduleJob(JsonNode params)
createScheduleJob(JsonNode params, Duration timeout)
```

说明：

- 便捷重载会自动组装官方 `cron.add` 所需的 `name / schedule / sessionTarget / payload`
- 当 `sessionKey=main` 时，SDK 会发送 `payload.kind=systemEvent`
- 当 `sessionKey` 为空、`isolated` 或自定义值时，SDK 会发送 `payload.kind=agentTurn`
- 旧版签名中的 `requestTimeout / streamTimeout / resultMode` 不再直接写入 cron 定义；如果需要高级参数，请使用 `JsonNode` 重载

示例：

```java
import com.example.openclaw.client.OpenClawClient;
import com.example.openclaw.model.OpenClawMessage;

try (OpenClawClient client = OpenClawClient
        .init("ws://127.0.0.1:18789", "你的token")
        .join()) {
    OpenClawMessage response = client.createScheduleJob(
            "日报总结任务",
            "0 9 * * *",
            "请按日报模板总结今天的处理结果"
    ).join();

    System.out.println("ok = " + response.ok());
    System.out.println("payload = " + response.payload());
    System.out.println("error = " + response.error());
}
```

#### 7.7.2 `executeScheduleJob(...)`

常用签名：

```java
executeScheduleJob(String jobId)
executeScheduleJob(String jobId, Duration timeout)
```

说明：

- 该方法是“手动执行任务”的语义化封装
- 底层实际调用的是 `cron.run`
- SDK 默认会补上 `mode=force`
- 如果你需要附加执行参数，也可以使用 `triggerScheduleJob(String jobId, JsonNode params, Duration timeout)`

示例：

```java
OpenClawMessage response = client.executeScheduleJob("job-001").join();
System.out.println("ok = " + response.ok());
System.out.println("payload = " + response.payload());
System.out.println("error = " + response.error());
```

#### 7.7.3 `deleteScheduleJob(...)`

常用签名：

```java
deleteScheduleJob(String jobId)
deleteScheduleJob(String jobId, Duration timeout)
deleteScheduleJob(String jobId, JsonNode params, Duration timeout)
```

示例：

```java
OpenClawMessage response = client.deleteScheduleJob("job-001").join();
System.out.println("ok = " + response.ok());
System.out.println("payload = " + response.payload());
System.out.println("error = " + response.error());
```

#### 7.7.4 `listScheduleJobs(...)`

常用签名：

```java
listScheduleJobs()
listScheduleJobs(Duration timeout)
listScheduleJobs(JsonNode params, Duration timeout)
```

说明：

- 不传参数时，语义上等同于“查询全部定时任务”
- 如果需要分页、启停状态、排序等附加查询条件，可以通过 `JsonNode params` 透传官方 `cron.list` 参数

示例：

```java
ObjectNode params = objectMapper.createObjectNode();
params.put("includeDisabled", true);
params.put("limit", 50);
params.put("offset", 0);
params.put("enabled", "all");
params.put("sortBy", "nextRunAtMs");
params.put("sortDir", "asc");

OpenClawMessage response = client.listScheduleJobs(params, Duration.ofSeconds(30)).join();
System.out.println("ok = " + response.ok());
System.out.println("payload = " + response.payload());
System.out.println("error = " + response.error());
```

#### 7.7.5 `getScheduleJobStatus(...)`

常用签名：

```java
getScheduleJobStatus(String jobId)
getScheduleJobStatus(String jobId, Duration timeout)
```

示例：

```java
OpenClawMessage response = client.getScheduleJobStatus("job-001").join();
System.out.println("ok = " + response.ok());
System.out.println("payload = " + response.payload());
System.out.println("error = " + response.error());
```

#### 7.7.6 `listScheduleJobRuns(...)`

常用签名：

```java
listScheduleJobRuns(String jobId)
listScheduleJobRuns(String jobId, Integer limit, Integer offset, Duration timeout)
```

示例：

```java
OpenClawMessage response = client.listScheduleJobRuns("job-001", 20, 0, Duration.ofSeconds(30)).join();
System.out.println("ok = " + response.ok());
System.out.println("payload = " + response.payload());
System.out.println("error = " + response.error());
```

## 8. Demo 运行说明

当前控制台示例入口类：

[`src/main/java/com/example/openclaw/demo/App.java`](D:/BONC/tower/code/comp-openclaw/src/main/java/com/example/openclaw/demo/App.java)

### 8.1 启动命令

最简启动方式：

```bash
mvn exec:java -Dopenclaw.token=你的token
```

带网关地址启动：

```bash
mvn exec:java -Dopenclaw.gateway=ws://127.0.0.1:18789 -Dopenclaw.token=你的token
```

使用解析后的纯文本格式启动：

```bash
mvn exec:java -Dopenclaw.token=你的token -Dopenclaw.responseFormat=text
```

使用原始 event 格式启动：

```bash
mvn exec:java -Dopenclaw.token=你的token -Dopenclaw.responseFormat=event
```

使用小塔 APP 实时流式格式启动：

```bash
mvn exec:java -Dopenclaw.token=你的token -Dopenclaw.responseFormat=tower-stream
```

### 8.2 启动参数与环境变量

| 名称                              | 类型     | 默认值                    | 说明                                                                           |
| ------------------------------- | ------ | ---------------------- | ---------------------------------------------------------------------------- |
| `openclaw.gateway`              | JVM 参数 | `ws://127.0.0.1:18789` | OpenClaw 网关地址                                                                |
| `openclaw.token`                | JVM 参数 | 空                      | 网关 Token，优先级高于环境变量                                                           |
| `OPENCLAW_GATEWAY_TOKEN`        | 环境变量   | 空                      | 网关 Token，作为 JVM 参数缺失时的兜底                                                     |
| `openclaw.sessionKey`           | JVM 参数 | `main`                 | 对话会话键                                                                        |
| `openclaw.responseFormat`       | JVM 参数 | `text`                 | Demo 输出格式；`text` 为解析后的纯文本，`event` 为原始 event，`tower-stream` 为小塔 APP 兼容事件的实时输出 |
| `openclaw.timeoutSeconds`       | JVM 参数 | `30`                   | `chat.send` 请求超时秒数                                                           |
| `openclaw.streamTimeoutSeconds` | JVM 参数 | `180`                  | 等待流式回复结束的超时秒数                                                                |

Token 读取优先级：

1. `-Dopenclaw.token=...`
2. `OPENCLAW_GATEWAY_TOKEN`

### 8.3 退出方式

控制台运行中，输入以下任一命令后回车即可退出：

- `exit`
- `quit`

## 9. 常见问题

### 9.1 提示“缺少网关 Token”怎么办

原因：

- 未传入 `openclaw.token`
- 也未设置 `OPENCLAW_GATEWAY_TOKEN`

处理方式：

```bash
mvn exec:java -Dopenclaw.token=你的token
```

或：

```bash
set OPENCLAW_GATEWAY_TOKEN=你的token
mvn exec:java
```

### 9.2 报错 “WebSocket is not connected” 怎么办

原因：

- 你使用构造方法创建了 `OpenClawClient`
- 但尚未调用 `connect()`

处理方式：

```java
OpenClawClient client = new OpenClawClient("ws://127.0.0.1:18789", "你的token");
client.connect().join();
```

或者改为：

```java
OpenClawClient client = OpenClawClient
        .init("ws://127.0.0.1:18789", "你的token")
        .join();
```

### 9.3 请求超时或流式超时怎么办

当前默认值：

- 请求超时默认 30 秒
- 流式超时默认 180 秒

如果业务处理更慢，可以在 SDK 调用时显式调大：

```java
String reply = client.sendChatText(
        "main",
        "请处理一个耗时较长的问题",
        Duration.ofSeconds(60),
        Duration.ofSeconds(300)
).join();
```

Demo 场景下也可以通过 JVM 参数调整：

```bash
mvn exec:java ^
  -Dopenclaw.token=你的token ^
  -Dopenclaw.timeoutSeconds=60 ^
  -Dopenclaw.streamTimeoutSeconds=300
```

### 9.4 什么时候选 `sendChatText(...)`

优先选择 `sendChatText(...)` 的场景：

- 你只需要最终回答文本
- 你不想自己处理事件聚合
- 你希望接入代码尽量简洁

### 9.5 什么时候选 `sendChatRawEvents(...)`

优先选择 `sendChatRawEvents(...)` 的场景：

- 你要保留或分析原始事件
- 你需要调试事件流
- 你希望保留更完整的协议层数据

### 9.6 什么时候选 `streamChatRawEvents(...)`

优先选择 `streamChatRawEvents(...)` 的场景：

- 你要实时消费小塔 APP 兼容事件
- 你希望边收边处理 `TowerAppSseEvent`
- 你不想等整轮结束后再统一拿 `List<TowerAppSseEvent>`

### 9.7 什么时候选 `sendChat(...)`

适合场景：

- 你只想发送原始 `chat.send` 请求
- 你要直接拿 `OpenClawMessage`
- 你要自己从响应中读取 `payload` 或 `runId`

## 10. 推荐接入顺序

如果你是第一次把本 SDK 接入到业务项目，推荐顺序如下：

1. 先在本项目执行 `mvn clean install`
2. 在业务项目中加入 Maven 依赖
3. 优先使用 `OpenClawClient.init(...).join()` 完成初始化
4. 优先使用 `sendChatText(...)` 跑通最小闭环
5. 需要实时消费小塔 APP 兼容事件时，使用 `streamChatRawEvents(...)`
6. 需要排查更底层的协议细节时，再增加 `setEventListener(...)` 或 `sendChatRawEvents(...)`
