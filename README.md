# OpenClaw Java SDK Demo 使用说明

## 1. 项目简介

本项目是一个基于 Java 的 OpenClaw SDK Demo。

它既可以作为控制台示例程序直接运行，也可以打包为 `jar` 后提供给其他 Java 项目引入调用。当前项目提供了这些能力：

- OpenClaw 网关连接配置初始化
- 多种客户端初始化方式
- 原始协议调用能力
- 返回解析后纯文本的对话调用能力
- 返回原始 `event` 消息体列表的对话调用能力
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
- 当前项目没有配置 fat-jar 或 shaded-jar，不建议把它当成双击即可运行的独立可执行包。
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

`OpenClawMessage` 是统一消息模型，主要字段如下：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `type` | `String` | 消息类型，例如 `req`、`res`、`event` |
| `event` | `String` | 事件名称，仅 `event` 类型使用 |
| `id` | `String` | 请求或响应 ID |
| `method` | `String` | 方法名，例如 `chat.send` |
| `ok` | `Boolean` | 响应是否成功 |
| `payload` | `JsonNode` | 请求参数、响应数据或事件内容 |
| `error` | `JsonNode` | 错误信息 |

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

### 7.4 `setEventListener(...)`

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

- `setEventListener(...)` 适合实时监听
- `sendChatRawEvents(...)` 适合在一次调用结束后统一收集

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

### 8.2 启动参数与环境变量

| 名称 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `openclaw.gateway` | JVM 参数 | `ws://127.0.0.1:18789` | OpenClaw 网关地址 |
| `openclaw.token` | JVM 参数 | 空 | 网关 Token，优先级高于环境变量 |
| `OPENCLAW_GATEWAY_TOKEN` | 环境变量 | 空 | 网关 Token，作为 JVM 参数缺失时的兜底 |
| `openclaw.sessionKey` | JVM 参数 | `main` | 对话会话键 |
| `openclaw.responseFormat` | JVM 参数 | `text` | Demo 输出格式；`text` 为解析后的纯文本，`event` 为原始 event |
| `openclaw.timeoutSeconds` | JVM 参数 | `30` | `chat.send` 请求超时秒数 |
| `openclaw.streamTimeoutSeconds` | JVM 参数 | `180` | 等待流式回复结束的超时秒数 |

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

### 9.6 什么时候选 `sendChat(...)`

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
5. 需要排查协议细节时再增加 `setEventListener(...)` 或 `sendChatRawEvents(...)`
