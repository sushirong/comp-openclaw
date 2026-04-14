package com.example.openclaw.client;

import com.example.openclaw.model.OpenClawMessage;
import com.example.openclaw.model.TowerAppSseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.bouncycastle.jcajce.interfaces.EdDSAPrivateKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.PublicKey;
import java.security.Security;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * OpenClaw Java SDK 客户端。
 *
 * <p>该实现为了兼容 JDK 8，底层 WebSocket 改为基于 OkHttp，
 * 对外暴露的方法签名和业务语义保持不变。</p>
 */
public final class OpenClawClient implements AutoCloseable {
    // 协议版本固定为 OpenClaw v3。
    private static final int PROTOCOL_VERSION = 3;
    // 默认会话键，用于未显式传入 sessionKey 的场景。
    private static final String DEFAULT_SESSION_KEY = "main";
    // chat.send 请求阶段默认超时时间。
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    // 流式回复阶段默认超时时间。
    private static final Duration DEFAULT_STREAM_TIMEOUT = Duration.ofSeconds(180);
    // 握手时上报的客户端标识信息。
    private static final String DEFAULT_CLIENT_ID = "openclaw-tui";
    private static final String DEFAULT_CLIENT_MODE = "ui";
    private static final String DEFAULT_ROLE = "operator";
    private static final String DEFAULT_PLATFORM = "java";
    private static final String DEFAULT_DEVICE_FAMILY = "desktop";
    private static final String DEFAULT_USER_AGENT = "openclaw-java-sdk-demo/1.0";
    // Gateway 内置 cron 调度相关的底层协议方法名。
    private static final String METHOD_CRON_ADD = "cron.add";
    private static final String METHOD_CRON_RUN = "cron.run";
    private static final String METHOD_CRON_REMOVE = "cron.remove";
    private static final String METHOD_CRON_LIST = "cron.list";
    private static final String METHOD_CRON_STATUS = "cron.status";
    private static final String METHOD_CRON_RUNS = "cron.runs";
    private static final String DEFAULT_CRON_SESSION_TARGET = "isolated";
    private static final String DEFAULT_CRON_MAIN_SESSION = "main";
    private static final String DEFAULT_CRON_WAKE_MODE_MAIN = "now";
    private static final String DEFAULT_CRON_WAKE_MODE_AGENT = "next-heartbeat";
    // Ed25519 公钥的 SPKI 前缀，用于提取 32 字节 raw public key。
    private static final byte[] ED25519_SPKI_PREFIX = hex("302a300506032b6570032100");
    // 通过 BouncyCastle 提供 JDK 8 下的 Ed25519 支持。
    private static final Provider BC_PROVIDER = ensureBcProvider();
    // 统一调度 CompletableFuture 超时任务。
    private static final ScheduledExecutorService TIMEOUT_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(newDaemonThreadFactory("openclaw-timeout"));

    // 保存 SDK 配置。
    private final OpenClawConfig config;
    // 保存 JSON 序列化组件。
    private final ObjectMapper objectMapper;
    // 保存底层 OkHttp 客户端。
    private final OkHttpClient httpClient;
    // 保存设备身份使用的密钥对。
    private final KeyPair deviceKeyPair;
    // 保存设备 ID。
    private final String deviceId;
    // 保存 raw public key 的 base64url 字符串。
    private final String publicKeyRawBase64Url;
    private final String clientInstanceId;
    // 保存待响应请求映射。
    private final Map<String, PendingRequest> pendingRequests = new ConcurrentHashMap<String, PendingRequest>();
    // 保存按 runId 聚合中的对话上下文。
    private final Map<String, ChatConversation> conversations = new ConcurrentHashMap<String, ChatConversation>();
    // 保存握手完成信号。
    private final CompletableFuture<Void> connected = new CompletableFuture<Void>();
    // 保存当前 WebSocket 连接。
    private volatile WebSocket webSocket;
    // 保存外部注册的事件监听器。
    private volatile Consumer<OpenClawMessage> eventListener;

    /**
     * 原始事件的附加映射类型。
     * <p>
     *这里单独抽一个枚举，而不是直接新增布尔参数，是为了后续如果还要支持其他
     * 下游消费格式时，可以继续通过重载扩展而不破坏现有方法签名。
     */
    public enum RawEventMappingType {
        // 将原始事件映射成小塔 APP 可直接消费的 SSE 外层对象。
        TOWER_APP
    }

    /**
     * 使用配置对象创建客户端。
     *
     * @param config SDK 配置
     */
    public OpenClawClient(OpenClawConfig config) {
        this.config = Objects.requireNonNull(config, "config is required");
        this.objectMapper = new ObjectMapper();
        // JDK 8 下使用 OkHttp 负责 WebSocket 建连与收发。
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(config.connectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
        ClientIdentity identity = resolveClientIdentity(config);
        this.deviceKeyPair = identity.deviceKeyPair();
        this.deviceId = identity.deviceId();
        this.publicKeyRawBase64Url = identity.publicKeyRawBase64Url();
        this.clientInstanceId = identity.clientInstanceId();
    }

    /**
     * 使用网关地址创建客户端。
     */
    public OpenClawClient(String gatewayUri) {
        this(OpenClawConfig.of(gatewayUri));
    }

    /**
     * 使用网关地址和鉴权 token 创建客户端。
     */
    public OpenClawClient(String gatewayUri, String authToken) {
        this(OpenClawConfig.of(gatewayUri, authToken));
    }

    /**
     * 使用网关地址和连接超时创建客户端。
     */
    public OpenClawClient(String gatewayUri, Duration connectTimeout) {
        this(OpenClawConfig.of(gatewayUri, connectTimeout));
    }

    /**
     * 使用完整连接参数创建客户端。
     */
    public OpenClawClient(String gatewayUri, String authToken, Duration connectTimeout) {
        this(OpenClawConfig.of(gatewayUri, authToken, connectTimeout));
    }

    /**
     * 初始化并连接客户端。
     */
    public static CompletableFuture<OpenClawClient> init(OpenClawConfig config) {
        final OpenClawClient client = new OpenClawClient(config);
        return client.connect().thenApply(ignored -> client).whenComplete((readyClient, error) -> {
            if (error != null) {
                client.close();
            }
        });
    }

    public static CompletableFuture<OpenClawClient> init(String gatewayUri) {
        return init(OpenClawConfig.of(gatewayUri));
    }

    public static CompletableFuture<OpenClawClient> init(String gatewayUri, String authToken) {
        return init(OpenClawConfig.of(gatewayUri, authToken));
    }

    public static CompletableFuture<OpenClawClient> init(String gatewayUri, Duration connectTimeout) {
        return init(OpenClawConfig.of(gatewayUri, connectTimeout));
    }

    public static CompletableFuture<OpenClawClient> init(String gatewayUri, String authToken, Duration connectTimeout) {
        return init(OpenClawConfig.of(gatewayUri, authToken, connectTimeout));
    }

    /**
     * 建立 WebSocket 连接并等待握手完成。
     *
     * @return 握手完成信号
     */
    public CompletableFuture<Void> connect() {
        Request.Builder builder = new Request.Builder()
                .url(config.gatewayUri().toString())
                .addHeader("User-Agent", DEFAULT_USER_AGENT)
                .addHeader("origin", resolveOriginHeaderValue());
        if (hasText(config.authToken())) {
            String encoded = Base64.getEncoder()
                    .encodeToString(("token:" + config.authToken()).getBytes(StandardCharsets.UTF_8));
            builder.addHeader("Authorization", "Basic " + encoded);
        }
        webSocket = httpClient.newWebSocket(builder.build(), new Listener());
        return connected;
    }

    /**
     * 发送底层协议请求。
     *
     * @param method 方法名
     * @param params 请求参数
     * @param timeout 超时时间
     * @return 原始响应消息
     */
    public CompletableFuture<OpenClawMessage> call(String method, JsonNode params, Duration timeout) {
        if (webSocket == null) {
            return failedFuture(new IllegalStateException("WebSocket is not connected"));
        }

        String requestId = UUID.randomUUID().toString();
        ObjectNode request = objectMapper.createObjectNode();
        request.put("type", "req");
        request.put("id", requestId);
        request.put("method", method);
        if (params != null) {
            request.set("params", params);
        }

        CompletableFuture<OpenClawMessage> responseFuture = new CompletableFuture<OpenClawMessage>();
        PendingRequest pendingRequest = new PendingRequest(method, responseFuture);
        pendingRequests.put(requestId, pendingRequest);
        responseFuture.whenComplete((message, error) -> pendingRequests.remove(requestId, pendingRequest));

        final String requestText;
        try {
            requestText = objectMapper.writeValueAsString(request);
        } catch (IOException e) {
            pendingRequests.remove(requestId, pendingRequest);
            return failedFuture(e);
        }

        if (!webSocket.send(requestText)) {
            pendingRequests.remove(requestId, pendingRequest);
            responseFuture.completeExceptionally(new IllegalStateException("WebSocket send failed"));
            return responseFuture;
        }

        Duration effective = timeout == null ? DEFAULT_REQUEST_TIMEOUT : timeout;
        return applyTimeout(responseFuture, effective, "OpenClaw request");
    }

    /**
     * 创建定时任务并返回原始响应消息。
     *
     * <p>该便捷方法按官方 cron 工具调用协议构造请求，默认创建一个
     * {@code sessionTarget=isolated} 的循环任务，并把提示词写入
     * {@code payload.kind=agentTurn.message}。
     *
     * @param jobName 任务名称
     * @param cronExpr Cron 表达式
     * @param promptTemplate 提示词模板
     * @return 创建任务的原始响应消息
     */
    public CompletableFuture<OpenClawMessage> createScheduleJob(String jobName, String cronExpr, String promptTemplate) {
        return createScheduleJob(
                jobName,
                cronExpr,
                promptTemplate,
                DEFAULT_CRON_SESSION_TARGET,
                DEFAULT_REQUEST_TIMEOUT,
                DEFAULT_STREAM_TIMEOUT,
                null,
                DEFAULT_REQUEST_TIMEOUT
        );
    }

    /**
     * 创建定时任务并返回原始响应消息。
     *
     * <p>该重载保留原有 SDK 方法签名，但底层会映射到官方 {@code cron.add} 协议。
     * 其中 {@code sessionKey} 会映射为 cron 的 {@code sessionTarget}：
     * <ul>
     *     <li>{@code main} -> {@code sessionTarget=main} + {@code payload.kind=systemEvent}</li>
     *     <li>{@code isolated} 或空 -> {@code sessionTarget=isolated} + {@code payload.kind=agentTurn}</li>
     *     <li>其他值 -> {@code sessionTarget=session:<sessionKey>} + {@code payload.kind=agentTurn}</li>
     * </ul>
     * 现阶段官方 cron 协议未直接暴露旧版 SDK 中的
     * {@code requestTimeout / streamTimeout / resultMode} 字段，因此这里不会写入请求。
     *
     * @param jobName 任务名称
     * @param cronExpr Cron 表达式
     * @param promptTemplate 提示词模板
     * @param sessionKey 会话标识，为空时回退到默认会话
     * @param requestTimeout 任务执行时 chat.send 请求超时时间
     * @param streamTimeout 任务执行时等待流式响应结束的超时时间
     * @param resultMode 任务结果保存模式，例如 {@code text}、{@code raw_event}、{@code tower_stream} 或 {@code all}
     * @param timeout 当前创建请求本身的超时时间
     * @return 创建任务的原始响应消息
     */
    public CompletableFuture<OpenClawMessage> createScheduleJob(
            String jobName,
            String cronExpr,
            String promptTemplate,
            String sessionKey,
            Duration requestTimeout,
            Duration streamTimeout,
            String resultMode,
            Duration timeout
    ) {
        return createScheduleJob(
                buildCronAddParams(
                        jobName,
                        cronExpr,
                        promptTemplate,
                        sessionKey
                ),
                timeout
        );
    }

    /**
     * 使用完整参数创建定时任务并返回原始响应消息。
     *
     * <p>当需要使用官方 cron 工具更多字段时，推荐直接传入完整的 {@link JsonNode}。
     * 底层实际调用的是 {@code cron.add}。
     *
     * @param params 创建任务参数
     * @return 创建任务的原始响应消息
     */
    public CompletableFuture<OpenClawMessage> createScheduleJob(JsonNode params) {
        return createScheduleJob(params, DEFAULT_REQUEST_TIMEOUT);
    }

    /**
     * 使用完整参数创建定时任务并返回原始响应消息。
     *
     * @param params 创建任务参数
     * @param timeout 请求超时时间
     * @return 创建任务的原始响应消息
     */
    public CompletableFuture<OpenClawMessage> createScheduleJob(JsonNode params, Duration timeout) {
        Objects.requireNonNull(params, "params is required");
        return call(METHOD_CRON_ADD, params, timeout);
    }

    /**
     * 手动执行指定定时任务并返回原始响应消息。
     *
     * <p>该方法是对“手动触发任务”的语义化封装，底层实际调用
     * {@code cron.run}。
     *
     * @param jobId 任务 ID
     * @return 执行任务的原始响应消息
     */
    public CompletableFuture<OpenClawMessage> executeScheduleJob(String jobId) {
        return triggerScheduleJob(jobId, DEFAULT_REQUEST_TIMEOUT);
    }

    /**
     * 手动执行指定定时任务并返回原始响应消息。
     *
     * @param jobId 任务 ID
     * @param timeout 请求超时时间
     * @return 执行任务的原始响应消息
     */
    public CompletableFuture<OpenClawMessage> executeScheduleJob(String jobId, Duration timeout) {
        return triggerScheduleJob(jobId, timeout);
    }

    /**
     * 手动触发指定定时任务并返回原始响应消息。
     *
     * @param jobId 任务 ID
     * @return 执行任务的原始响应消息
     */
    public CompletableFuture<OpenClawMessage> triggerScheduleJob(String jobId) {
        return triggerScheduleJob(jobId, DEFAULT_REQUEST_TIMEOUT);
    }

    /**
     * 手动触发指定定时任务并返回原始响应消息。
     *
     * @param jobId 任务 ID
     * @param timeout 请求超时时间
     * @return 执行任务的原始响应消息
     */
    public CompletableFuture<OpenClawMessage> triggerScheduleJob(String jobId, Duration timeout) {
        return triggerScheduleJob(jobId, null, timeout);
    }

    /**
     * 手动触发指定定时任务并返回原始响应消息。
     *
     * <p>当调度服务支持附加执行参数时，可以通过 {@code params} 一并传入。
     * SDK 会统一补上 {@code jobId} 字段。
     *
     * @param jobId 任务 ID
     * @param params 额外执行参数
     * @param timeout 请求超时时间
     * @return 执行任务的原始响应消息
     */
    public CompletableFuture<OpenClawMessage> triggerScheduleJob(String jobId, JsonNode params, Duration timeout) {
        return call(METHOD_CRON_RUN, buildCronRunParams(jobId, params), timeout);
    }

    /**
     * 删除指定定时任务并返回原始响应消息。
     *
     * @param jobId 任务 ID
     * @return 删除任务的原始响应消息
     */
    public CompletableFuture<OpenClawMessage> deleteScheduleJob(String jobId) {
        return deleteScheduleJob(jobId, DEFAULT_REQUEST_TIMEOUT);
    }

    /**
     * 删除指定定时任务并返回原始响应消息。
     *
     * @param jobId 任务 ID
     * @param timeout 请求超时时间
     * @return 删除任务的原始响应消息
     */
    public CompletableFuture<OpenClawMessage> deleteScheduleJob(String jobId, Duration timeout) {
        return deleteScheduleJob(jobId, null, timeout);
    }

    /**
     * 删除指定定时任务并返回原始响应消息。
     *
     * <p>当调度服务要求额外删除参数时，可以通过 {@code params} 一并透传。
     * SDK 会统一补上 {@code jobId} 字段。
     *
     * @param jobId 任务 ID
     * @param params 额外删除参数
     * @param timeout 请求超时时间
     * @return 删除任务的原始响应消息
     */
    public CompletableFuture<OpenClawMessage> deleteScheduleJob(String jobId, JsonNode params, Duration timeout) {
        return call(METHOD_CRON_REMOVE, buildCronJobIdParams(jobId, params), timeout);
    }

    /**
     * 查询全部定时任务并返回原始响应消息。
     *
     * @return 任务列表原始响应消息
     */
    public CompletableFuture<OpenClawMessage> listScheduleJobs() {
        return listScheduleJobs(null, DEFAULT_REQUEST_TIMEOUT);
    }

    /**
     * 查询全部定时任务并返回原始响应消息。
     *
     * @param timeout 请求超时时间
     * @return 任务列表原始响应消息
     */
    public CompletableFuture<OpenClawMessage> listScheduleJobs(Duration timeout) {
        return listScheduleJobs(null, timeout);
    }

    /**
     * 查询定时任务列表并返回原始响应消息。
     *
     * <p>如果后端支持分页、状态过滤等附加查询条件，可以通过 {@code params} 透传。
     * 当 {@code params} 为空时，SDK 会自动发送空对象，语义上等同于“查询全部任务”。
     *
     * @param params 查询参数
     * @param timeout 请求超时时间
     * @return 任务列表原始响应消息
     */
    public CompletableFuture<OpenClawMessage> listScheduleJobs(JsonNode params, Duration timeout) {
        JsonNode effectiveParams = params == null ? objectMapper.createObjectNode() : params;
        return call(METHOD_CRON_LIST, effectiveParams, timeout);
    }

    /**
     * 查询单个定时任务状态并返回原始响应消息。
     *
     * @param jobId 任务 ID
     * @return 任务状态原始响应消息
     */
    public CompletableFuture<OpenClawMessage> getScheduleJobStatus(String jobId) {
        return getScheduleJobStatus(jobId, DEFAULT_REQUEST_TIMEOUT);
    }

    /**
     * 查询单个定时任务状态并返回原始响应消息。
     *
     * @param jobId 任务 ID
     * @param timeout 请求超时时间
     * @return 任务状态原始响应消息
     */
    public CompletableFuture<OpenClawMessage> getScheduleJobStatus(String jobId, Duration timeout) {
        return call(METHOD_CRON_STATUS, buildCronJobIdParams(jobId, null), timeout);
    }

    /**
     * 查询指定定时任务的执行记录并返回原始响应消息。
     *
     * @param jobId 任务 ID
     * @return 任务执行记录原始响应消息
     */
    public CompletableFuture<OpenClawMessage> listScheduleJobRuns(String jobId) {
        return listScheduleJobRuns(jobId, null, null, DEFAULT_REQUEST_TIMEOUT);
    }

    /**
     * 查询指定定时任务的执行记录并返回原始响应消息。
     *
     * @param jobId 任务 ID
     * @param limit 返回条数
     * @param offset 偏移量
     * @param timeout 请求超时时间
     * @return 任务执行记录原始响应消息
     */
    public CompletableFuture<OpenClawMessage> listScheduleJobRuns(
            String jobId,
            Integer limit,
            Integer offset,
            Duration timeout
    ) {
        ObjectNode params = buildCronJobIdParams(jobId, null);
        if (limit != null) {
            params.put("limit", limit.intValue());
        }
        if (offset != null) {
            params.put("offset", offset.intValue());
        }
        return call(METHOD_CRON_RUNS, params, timeout);
    }

    /**
     * 使用默认会话发送 chat.send 请求。
     */
    public CompletableFuture<OpenClawMessage> sendChat(String message) {
        return sendChat(DEFAULT_SESSION_KEY, message, DEFAULT_REQUEST_TIMEOUT);
    }

    /**
     * 发送 chat.send 请求并返回原始响应。
     */
    public CompletableFuture<OpenClawMessage> sendChat(String sessionKey, String message) {
        return sendChat(sessionKey, message, DEFAULT_REQUEST_TIMEOUT);
    }

    /**
     * 发送 chat.send 请求并返回原始响应。
     */
    public CompletableFuture<OpenClawMessage> sendChat(String sessionKey, String message, Duration timeout) {
        Objects.requireNonNull(message, "message is required");
        ObjectNode params = objectMapper.createObjectNode();
        params.put("sessionKey", normalizeSessionKey(sessionKey));
        params.put("message", message);
        params.put("idempotencyKey", UUID.randomUUID().toString());
        return call("chat.send", params, timeout);
    }

    /**
     * 发送聊天请求并返回聚合后的最终文本。
     */
    public CompletableFuture<String> sendChatText(String message) {
        return sendChatText(DEFAULT_SESSION_KEY, message, DEFAULT_REQUEST_TIMEOUT, DEFAULT_STREAM_TIMEOUT);
    }

    /**
     * 发送聊天请求并返回聚合后的最终文本。
     */
    public CompletableFuture<String> sendChatText(String sessionKey, String message) {
        return sendChatText(sessionKey, message, DEFAULT_REQUEST_TIMEOUT, DEFAULT_STREAM_TIMEOUT);
    }

    /**
     * 发送聊天请求并返回聚合后的最终文本。
     */
    public CompletableFuture<String> sendChatText(String sessionKey, String message, Duration requestTimeout, Duration streamTimeout) {
        return executeChatConversation(sessionKey, message, requestTimeout, streamTimeout)
                .thenApply(ChatConversationSnapshot::assistantContent);
    }

    /**
     * 发送聊天请求并返回完整原始事件列表。
     */
    public CompletableFuture<List<String>> sendChatRawEvents(String message) {
        return sendChatRawEvents(DEFAULT_SESSION_KEY, message, DEFAULT_REQUEST_TIMEOUT, DEFAULT_STREAM_TIMEOUT);
    }

    /**
     * 发送聊天请求并返回完整原始事件列表。
     */
    public CompletableFuture<List<String>> sendChatRawEvents(String sessionKey, String message) {
        return sendChatRawEvents(sessionKey, message, DEFAULT_REQUEST_TIMEOUT, DEFAULT_STREAM_TIMEOUT);
    }

    /**
     * 发送聊天请求并返回完整原始事件列表。
     */
    public CompletableFuture<List<String>> sendChatRawEvents(
            String sessionKey,
            String message,
            Duration requestTimeout,
            Duration streamTimeout
    ) {
        return executeChatConversation(sessionKey, message, requestTimeout, streamTimeout)
                .thenApply(ChatConversationSnapshot::rawEvents);
    }

    /**
     * 使用默认会话发送聊天请求，并把原始事件映射为指定兼容格式。
     *
     * <p>该重载不会影响原有 {@code sendChatRawEvents(String)} 的返回结果。
     * 原方法仍然返回原始字符串事件列表；只有显式传入 {@link RawEventMappingType}
     * 时，才会执行附加的协议转换。
     *
     * @param message 用户输入消息
     * @param mappingType 原始事件映射类型
     * @return 小塔 APP 兼容事件对象列表
     */
    public CompletableFuture<List<TowerAppSseEvent>> sendChatRawEvents(String message, RawEventMappingType mappingType) {
        return sendChatRawEvents(DEFAULT_SESSION_KEY, message, DEFAULT_REQUEST_TIMEOUT, DEFAULT_STREAM_TIMEOUT, mappingType);
    }

    /**
     * 使用指定会话发送聊天请求，并把原始事件映射为指定兼容格式。
     *
     * <p>该方法适合调用方已经自己维护会话上下文的场景，映射逻辑与默认会话版本完全一致。
     *
     * @param sessionKey 会话标识，为空时内部仍会回退到默认会话
     * @param message 用户输入消息
     * @param mappingType 原始事件映射类型
     * @return 小塔 APP 兼容事件对象列表
     */
    public CompletableFuture<List<TowerAppSseEvent>> sendChatRawEvents(
            String sessionKey,
            String message,
            RawEventMappingType mappingType
    ) {
        return sendChatRawEvents(sessionKey, message, DEFAULT_REQUEST_TIMEOUT, DEFAULT_STREAM_TIMEOUT, mappingType);
    }

    /**
     * 发送聊天请求，并把原始事件映射为指定兼容格式。
     *
     * <p>这是完整参数版本，允许调用方同时控制请求超时、流式超时以及映射类型。
     * 其业务主流程仍然复用现有的 {@link #executeChatConversation(String, String, Duration, Duration)}，
     * 因此不会改变原本的发送、聚合、异常传播和超时处理语义。
     *
     * @param sessionKey 会话标识
     * @param message 用户输入消息
     * @param requestTimeout chat.send 请求超时时间
     * @param streamTimeout 流式响应等待超时时间
     * @param mappingType 原始事件映射类型
     * @return 映射后的兼容事件对象列表
     */
    public CompletableFuture<List<TowerAppSseEvent>> sendChatRawEvents(
            String sessionKey,
            String message,
            Duration requestTimeout,
            Duration streamTimeout,
            RawEventMappingType mappingType
    ) {
        Objects.requireNonNull(mappingType, "mappingType is required");
        return executeChatConversation(sessionKey, message, requestTimeout, streamTimeout)
                .thenApply(snapshot -> mapRawEvents(snapshot.rawEvents(), mappingType));
    }

    /**
     * Streams mapped Tower APP events to the callback as soon as they arrive.
     */
    public CompletableFuture<Void> streamChatRawEvents(
            String message,
            RawEventMappingType mappingType,
            Consumer<TowerAppSseEvent> onEvent
    ) {
        return streamChatRawEvents(
                DEFAULT_SESSION_KEY,
                message,
                DEFAULT_REQUEST_TIMEOUT,
                DEFAULT_STREAM_TIMEOUT,
                mappingType,
                onEvent
        );
    }

    /**
     * Streams mapped Tower APP events to the callback as soon as they arrive.
     */
    public CompletableFuture<Void> streamChatRawEvents(
            String sessionKey,
            String message,
            RawEventMappingType mappingType,
            Consumer<TowerAppSseEvent> onEvent
    ) {
        return streamChatRawEvents(
                sessionKey,
                message,
                DEFAULT_REQUEST_TIMEOUT,
                DEFAULT_STREAM_TIMEOUT,
                mappingType,
                onEvent
        );
    }

    /**
     * Streams mapped Tower APP events to the callback as soon as they arrive.
     */
    public CompletableFuture<Void> streamChatRawEvents(
            String sessionKey,
            String message,
            Duration requestTimeout,
            Duration streamTimeout,
            RawEventMappingType mappingType,
            Consumer<TowerAppSseEvent> onEvent
    ) {
        Objects.requireNonNull(mappingType, "mappingType is required");
        Objects.requireNonNull(onEvent, "onEvent is required");
        return executeStreamingChatConversation(sessionKey, message, requestTimeout, streamTimeout, rawEvent -> {
            TowerAppSseEvent mappedEvent = mapRawEvent(rawEvent, mappingType);
            if (mappedEvent != null) {
                onEvent.accept(mappedEvent);
            }
        });
    }

    /**
     * Registers a global listener for raw OpenClaw messages.
     */
    public void setEventListener(Consumer<OpenClawMessage> listener) {
        this.eventListener = listener;
    }

    /**
     * 关闭当前连接。
     */
    @Override
    public void close() {
        WebSocket ws = webSocket;
        if (ws != null) {
            ws.close(1000, "bye");
        }
    }

    /**
     * 执行一次完整对话，先拿到 runId，再等待流式事件完成聚合。
     */
    private CompletableFuture<ChatConversationSnapshot> executeChatConversation(
            String sessionKey,
            String message,
            Duration requestTimeout,
            Duration streamTimeout
    ) {
        final Duration effectiveRequestTimeout = requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;
        final Duration effectiveStreamTimeout = streamTimeout == null ? DEFAULT_STREAM_TIMEOUT : streamTimeout;

        return sendChat(sessionKey, message, effectiveRequestTimeout).thenCompose(response -> {
            if (!Boolean.TRUE.equals(response.ok())) {
                return failedFuture(new IllegalStateException("chat.send rejected: " + describeJson(response.error())));
            }

            String runId = response.payload() == null ? null : response.payload().path("runId").asText(null);
            if (isBlank(runId)) {
                return failedFuture(new IllegalStateException("chat.send response does not contain runId"));
            }

            final ChatConversation conversation = conversations.computeIfAbsent(runId, ChatConversation::new);
            return conversation.await(effectiveStreamTimeout)
                    .thenApply(ignored -> conversation.snapshot())
                    .whenComplete((snapshot, error) -> conversations.remove(conversation.runId(), conversation));
        });
    }

    /**
     * 组装 connect 请求所需参数。
     */
    private CompletableFuture<Void> executeStreamingChatConversation(
            String sessionKey,
            String message,
            Duration requestTimeout,
            Duration streamTimeout,
            Consumer<String> rawEventSink
    ) {
        final Duration effectiveRequestTimeout = requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;
        final Duration effectiveStreamTimeout = streamTimeout == null ? DEFAULT_STREAM_TIMEOUT : streamTimeout;

        return sendChat(sessionKey, message, effectiveRequestTimeout).thenCompose(response -> {
            if (!Boolean.TRUE.equals(response.ok())) {
                return failedFuture(new IllegalStateException("chat.send rejected: " + describeJson(response.error())));
            }

            String runId = response.payload() == null ? null : response.payload().path("runId").asText(null);
            if (isBlank(runId)) {
                return failedFuture(new IllegalStateException("chat.send response does not contain runId"));
            }

            final ChatConversation conversation = conversations.computeIfAbsent(runId, ChatConversation::new);
            try {
                conversation.bindRawEventSink(rawEventSink);
            } catch (RuntimeException error) {
                conversations.remove(conversation.runId(), conversation);
                return failedFuture(error);
            }

            return conversation.await(effectiveStreamTimeout)
                    .whenComplete((ignored, error) -> conversations.remove(conversation.runId(), conversation));
        });
    }

    private ObjectNode buildConnectParams(String nonce) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("minProtocol", PROTOCOL_VERSION);
        params.put("maxProtocol", PROTOCOL_VERSION);

        ObjectNode client = objectMapper.createObjectNode();
        client.put("id", DEFAULT_CLIENT_ID);
        client.put("displayName", "openclaw java sdk demo");
        client.put("version", "1.0.0");
        client.put("platform", DEFAULT_PLATFORM);
        client.put("deviceFamily", DEFAULT_DEVICE_FAMILY);
        client.put("mode", DEFAULT_CLIENT_MODE);
        client.put("instanceId", clientInstanceId);
        params.set("client", client);

        params.put("locale", "zh-CN");
        params.put("userAgent", DEFAULT_USER_AGENT);
        params.put("role", DEFAULT_ROLE);

        ArrayNode scopes = objectMapper.createArrayNode();
        scopes.add("operator.read");
        scopes.add("operator.write");
        scopes.add("operator.admin");
        params.set("scopes", scopes);

        params.set("caps", objectMapper.createArrayNode());
        params.set("commands", objectMapper.createArrayNode());
        params.set("permissions", objectMapper.createObjectNode());

        if (hasText(config.authToken())) {
            ObjectNode auth = objectMapper.createObjectNode();
            auth.put("token", config.authToken());
            params.set("auth", auth);
        }

        long signedAt = System.currentTimeMillis();
        String signaturePayload = String.join("|",
                "v3", deviceId, DEFAULT_CLIENT_ID, DEFAULT_CLIENT_MODE, DEFAULT_ROLE,
                "operator.read,operator.write,operator.admin", String.valueOf(signedAt),
                config.authToken() == null ? "" : config.authToken(),
                nonce, DEFAULT_PLATFORM, DEFAULT_DEVICE_FAMILY);

        ObjectNode device = objectMapper.createObjectNode();
        device.put("id", deviceId);
        device.put("publicKey", publicKeyRawBase64Url);
        device.put("signature", signBase64Url(deviceKeyPair.getPrivate(), signaturePayload));
        device.put("signedAt", signedAt);
        device.put("nonce", nonce);
        params.set("device", device);
        return params;
    }

    /**
     * 组装官方 {@code cron.add} 请求参数。
     */
    private ObjectNode buildCronAddParams(
            String jobName,
            String cronExpr,
            String promptTemplate,
            String sessionKey
    ) {
        String sessionTarget = normalizeCronSessionTarget(sessionKey);

        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", requireText(jobName, "jobName is required"));

        ObjectNode schedule = params.putObject("schedule");
        schedule.put("kind", "cron");
        schedule.put("expr", normalizeCronExpr(cronExpr));

        params.put("sessionTarget", sessionTarget);
        params.put("wakeMode", DEFAULT_CRON_MAIN_SESSION.equals(sessionTarget)
                ? DEFAULT_CRON_WAKE_MODE_MAIN
                : DEFAULT_CRON_WAKE_MODE_AGENT);

        ObjectNode payload = params.putObject("payload");
        if (DEFAULT_CRON_MAIN_SESSION.equals(sessionTarget)) {
            payload.put("kind", "systemEvent");
            payload.put("text", requireText(promptTemplate, "promptTemplate is required"));
        } else {
            payload.put("kind", "agentTurn");
            payload.put("message", requireText(promptTemplate, "promptTemplate is required"));
            payload.put("lightContext", true);
        }
        return params;
    }

    /**
     * 组装带 {@code jobId} 的 cron 请求参数。
     */
    private ObjectNode buildCronJobIdParams(String jobId, JsonNode params) {
        ObjectNode request;
        if (params == null || params.isNull()) {
            request = objectMapper.createObjectNode();
        } else if (params.isObject()) {
            request = ((ObjectNode) params).deepCopy();
        } else {
            throw new IllegalArgumentException("params must be an object node");
        }
        request.put("jobId", requireText(jobId, "jobId is required"));
        return request;
    }

    /**
     * 组装 {@code cron.run} 请求参数。
     *
     * <p>官方接口支持 {@code mode=force|due}，为保持原有 execute 语义，这里默认补
     * {@code force}；调用方如显式传入 {@code mode}，则保留原值。
     */
    private ObjectNode buildCronRunParams(String jobId, JsonNode params) {
        ObjectNode request = buildCronJobIdParams(jobId, params);
        if (!request.hasNonNull("mode")) {
            request.put("mode", "force");
        }
        return request;
    }

    /**
     * 按指定类型将原始事件列表映射为兼容事件对象。
     *
     * <p>这里作为统一入口，后续如果还要支持其他消费端协议，只需要继续在这里分发即可，
     * 不需要改动聊天主流程。
     */
    private List<TowerAppSseEvent> mapRawEvents(List<String> rawEvents, RawEventMappingType mappingType) {
        List<TowerAppSseEvent> mappedEvents = new ArrayList<TowerAppSseEvent>();
        for (String rawEvent : rawEvents) {
            TowerAppSseEvent mappedEvent = mapRawEvent(rawEvent, mappingType);
            if (mappedEvent != null) {
                mappedEvents.add(mappedEvent);
            }
        }
        return mappedEvents;
    }

    /**
     * 将 OpenClaw 原始事件列表映射为小塔 APP 兼容对象列表。
     *
     * <p>原始列表中会包含多种事件：握手事件、聊天生命周期事件、文本增量事件以及可能的错误事件。
     * 这里只保留桌面协议真正需要消费的那几类，其余事件直接忽略，避免把无意义中间态暴露给调用方。
     */
    private TowerAppSseEvent mapRawEvent(String rawEvent, RawEventMappingType mappingType) {
        if (mappingType != RawEventMappingType.TOWER_APP) {
            throw new IllegalArgumentException("Unsupported mapping type: " + mappingType);
        }
        return mapToTowerAppEvent(rawEvent);
    }

    /**
     * 将单条 OpenClaw 原始事件映射为小塔 APP 兼容对象。
     *
     * <p>当前只处理 {@code type=event} 的消息，并继续区分 {@code agent} 与 {@code chat}
     * 两类事件来源。这样可以最大程度贴合桌面文档中的消息语义，同时保留后续扩展空间。
     */
    private TowerAppSseEvent mapToTowerAppEvent(String rawEvent) {
        try {
            JsonNode root = objectMapper.readTree(rawEvent);
            if (!"event".equalsIgnoreCase(root.path("type").asText(""))) {
                return null;
            }

            String eventName = root.path("event").asText("");
            JsonNode payload = root.path("payload");
            if (payload.isMissingNode() || payload.isNull()) {
                return null;
            }

            if ("agent".equals(eventName)) {
                return mapAgentEventToTowerApp(payload);
            }
            if ("chat".equals(eventName)) {
                return mapChatEventToTowerApp(payload);
            }
            return null;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to map raw event to tower app format: " + rawEvent, e);
        }
    }

    /**
     * 映射 agent 事件，只使用 assistant.delta 与 lifecycle 事件。
     *
     * <p>这里故意只认三种桌面端真正需要的消息：
     * start 映射为 {@code message} 空答案，
     * assistant.delta 映射为 {@code message} 增量答案，
     * end 映射为 {@code message_end}。
     *
     * <p>这样做的目的，是避免把 OpenClaw 侧更多内部流式状态直接透传给 APP，
     * 否则很容易导致桌面端重复拼接文本或错误结束会话。
     */
    private TowerAppSseEvent mapAgentEventToTowerApp(JsonNode payload) throws IOException {
        String stream = payload.path("stream").asText("");
        JsonNode data = payload.path("data");

        if ("assistant".equals(stream) && data != null && data.has("delta")) {
            String delta = data.get("delta").asText("");
            if (delta.isEmpty()) {
                return null;
            }
            // assistant.delta 是桌面端真正需要消费的文本增量字段。
            ObjectNode eventData = createTowerAppEventData("message", payload);
            eventData.put("answer", delta);
            return buildTowerAppSseEvent(eventData);
        }

        if ("lifecycle".equals(stream) && data != null) {
            String phase = data.path("phase").asText("");
            if ("start".equals(phase)) {
                // start 事件需要先通知桌面端“消息开始了”，answer 明确给空字符串。
                ObjectNode eventData = createTowerAppEventData("message", payload);
                eventData.put("answer", "");
                return buildTowerAppSseEvent(eventData);
            }
            if ("end".equals(phase)) {
                // end 事件用于收尾；如果响应里附带检索资源，也一并透传到 metadata。
                ObjectNode eventData = createTowerAppEventData("message_end", payload);
                JsonNode retrieverResources = extractRetrieverResources(payload);
                if (retrieverResources != null) {
                    ObjectNode metadata = objectMapper.createObjectNode();
                    metadata.set("retriever_resources", retrieverResources);
                    eventData.set("metadata", metadata);
                }
                return buildTowerAppSseEvent(eventData);
            }
        }

        return null;
    }

    /**
     * 映射 chat 事件，只处理 error，忽略 delta 以避免文本重复拼接。
     *
     * <p>根据当前原始协议表现，文本增量既可能出现在 {@code chat.state=delta}，
     * 也可能出现在 {@code agent.stream=assistant.data.delta}。桌面文档要求只保留一种来源，
     * 这里统一使用 agent.assistant.delta，避免同一段文本被重复转成两次 APP 消息。
     */
    private TowerAppSseEvent mapChatEventToTowerApp(JsonNode payload) throws IOException {
        if (!"error".equals(payload.path("state").asText(""))) {
            return null;
        }

        ObjectNode eventData = createTowerAppEventData("error", payload);
        String errorMessage = extractChatErrorMessage(payload);
        if (!isBlank(errorMessage)) {
            eventData.put("message", errorMessage);
        }
        return buildTowerAppSseEvent(eventData);
    }

    /**
     * 创建小塔 APP eventData 中的公共字段。
     *
     * <p>桌面对接文档里这些字段都来自同一份 OpenClaw 原始 payload：
     * {@code conversation_id <- sessionKey}，
     * {@code message_id/task_id/workflow_run_id <- runId}。
     * 统一在这里组装，避免不同事件类型各自散落赋值。
     */
    private ObjectNode createTowerAppEventData(String event, JsonNode payload) {
        ObjectNode eventData = objectMapper.createObjectNode();
        String runId = payload.path("runId").asText("");
        eventData.put("event", event);
        eventData.put("conversation_id", payload.path("sessionKey").asText(""));
        eventData.put("message_id", runId);
        eventData.put("task_id", runId);
        eventData.put("workflow_run_id", runId);
        return eventData;
    }

    /**
     * 构造符合小塔 APP 协议的外层事件对象。
     *
     * <p>注意这里会把内层 {@code eventData} 再序列化一次，确保字段类型是 JSON 字符串，
     * 而不是直接嵌套对象；这正是桌面协议要求的格式。
     */
    private TowerAppSseEvent buildTowerAppSseEvent(ObjectNode eventData) throws IOException {
        return new TowerAppSseEvent("TYPE_EVENT", objectMapper.writeValueAsString(eventData), "");
    }

    /**
     * 提取 chat.error 事件中的错误描述。
     *
     * <p>不同错误报文里，错误文本可能挂在 {@code message} 或 {@code error} 字段上。
     * 这里做一个兼容兜底，尽量把服务端原始错误信息原样保留下来，方便桌面端展示和排查。
     */
    private String extractChatErrorMessage(JsonNode payload) {
        JsonNode messageNode = payload.get("message");
        if (messageNode != null && !messageNode.isNull()) {
            return messageNode.isValueNode() ? messageNode.asText("") : messageNode.toString();
        }
        JsonNode errorNode = payload.get("error");
        if (errorNode != null && !errorNode.isNull()) {
            return errorNode.isValueNode() ? errorNode.asText("") : errorNode.toString();
        }
        return "";
    }

    /**
     * 在可能存在的几个位置上提取 retriever_resources。
     *
     * <p>不同响应链路里，检索资源元数据可能挂在 payload.metadata、payload.data.metadata
     * 或 payload.data 下。这里按优先级逐一探测，只要命中一个就深拷贝返回，避免后续节点共享引用。
     */
    private JsonNode extractRetrieverResources(JsonNode payload) {
        JsonNode node = payload.path("metadata").path("retriever_resources");
        if (!node.isMissingNode() && !node.isNull()) {
            return node.deepCopy();
        }
        node = payload.path("data").path("metadata").path("retriever_resources");
        if (!node.isMissingNode() && !node.isNull()) {
            return node.deepCopy();
        }
        node = payload.path("data").path("retriever_resources");
        if (!node.isMissingNode() && !node.isNull()) {
            return node.deepCopy();
        }
        return null;
    }

    // 确保 BouncyCastle Provider 只注册一次。
    private static Provider ensureBcProvider() {
        Provider provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME);
        if (provider != null) {
            return provider;
        }
        BouncyCastleProvider newProvider = new BouncyCastleProvider();
        Security.addProvider(newProvider);
        return newProvider;
    }

    private static ClientIdentity resolveClientIdentity(OpenClawConfig config) {
        String instanceId = hasText(config.clientInstanceId())
                ? config.clientInstanceId().trim()
                : UUID.randomUUID().toString();
        if (!hasText(config.devicePrivateKeyPkcs8Base64Url())) {
            if (hasText(config.deviceId())) {
                throw new IllegalArgumentException("deviceId requires devicePrivateKeyPkcs8Base64Url");
            }
            return generateClientIdentity(instanceId);
        }
        return restoreClientIdentity(
                instanceId,
                config.devicePrivateKeyPkcs8Base64Url(),
                config.deviceId()
        );
    }

    private static ClientIdentity generateClientIdentity(String clientInstanceId) {
        return buildClientIdentity(clientInstanceId, generateDeviceKeyPair(), null);
    }

    private static ClientIdentity restoreClientIdentity(
            String clientInstanceId,
            String devicePrivateKeyPkcs8Base64Url,
            String expectedDeviceId
    ) {
        byte[] privateKeyEncoded = decodeBase64Url(
                devicePrivateKeyPkcs8Base64Url,
                "devicePrivateKeyPkcs8Base64Url"
        );
        PrivateKey privateKey = restoreDevicePrivateKey(privateKeyEncoded);
        PublicKey publicKey = deriveEd25519PublicKey(privateKey);
        return buildClientIdentity(clientInstanceId, new KeyPair(publicKey, privateKey), expectedDeviceId);
    }

    private static ClientIdentity buildClientIdentity(
            String clientInstanceId,
            KeyPair keyPair,
            String expectedDeviceId
    ) {
        byte[] publicKeyRaw = extractEd25519RawPublicKey(keyPair.getPublic());
        String actualDeviceId = sha256Hex(publicKeyRaw);
        if (hasText(expectedDeviceId) && !expectedDeviceId.trim().equals(actualDeviceId)) {
            throw new IllegalArgumentException("Configured deviceId does not match the provided device private key");
        }
        return new ClientIdentity(
                clientInstanceId,
                keyPair,
                actualDeviceId,
                base64Url(publicKeyRaw)
        );
    }

    // 生成用于设备身份的 Ed25519 密钥对。
    private static KeyPair generateDeviceKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519", BC_PROVIDER).generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to generate device key pair", e);
        }
    }

    private static PrivateKey restoreDevicePrivateKey(byte[] privateKeyEncoded) {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("Ed25519", BC_PROVIDER);
            return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privateKeyEncoded));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to restore device private key", e);
        }
    }

    private static PublicKey deriveEd25519PublicKey(PrivateKey privateKey) {
        if (privateKey instanceof EdDSAPrivateKey) {
            return ((EdDSAPrivateKey) privateKey).getPublicKey();
        }
        throw new IllegalStateException("Unable to derive Ed25519 public key from configured private key");
    }

    private String resolveOriginHeaderValue() {
        if (hasText(config.origin())) {
            return config.origin().trim();
        }
        return defaultOriginFor(config.gatewayUri());
    }

    private static String defaultOriginFor(java.net.URI gatewayUri) {
        String scheme = gatewayUri.getScheme();
        String originScheme = "wss".equalsIgnoreCase(scheme) ? "https" : "http";
        String host = gatewayUri.getHost();
        if (isBlank(host)) {
            return originScheme + "://openclaw-client";
        }
        StringBuilder origin = new StringBuilder();
        origin.append(originScheme).append("://").append(formatOriginHost(host));
        int port = gatewayUri.getPort();
        if (port > 0 && !isDefaultOriginPort(originScheme, port)) {
            origin.append(':').append(port);
        }
        return origin.toString();
    }

    private static boolean isDefaultOriginPort(String scheme, int port) {
        return ("http".equalsIgnoreCase(scheme) && port == 80)
                || ("https".equalsIgnoreCase(scheme) && port == 443);
    }

    private static String formatOriginHost(String host) {
        return host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    // 从 SPKI 编码中提取 32 字节原始公钥。
    private static byte[] extractEd25519RawPublicKey(PublicKey publicKey) {
        byte[] encoded = publicKey.getEncoded();
        if (encoded.length == ED25519_SPKI_PREFIX.length + 32) {
            boolean matches = true;
            for (int i = 0; i < ED25519_SPKI_PREFIX.length; i++) {
                if (encoded[i] != ED25519_SPKI_PREFIX[i]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                byte[] raw = new byte[32];
                System.arraycopy(encoded, ED25519_SPKI_PREFIX.length, raw, 0, 32);
                return raw;
            }
        }
        return encoded;
    }

    // 对设备签名载荷做 Ed25519 签名，并转为 base64url。
    private static String signBase64Url(PrivateKey privateKey, String payload) {
        try {
            Signature signer = Signature.getInstance("Ed25519", BC_PROVIDER);
            signer.initSign(privateKey);
            signer.update(payload.getBytes(StandardCharsets.UTF_8));
            return base64Url(signer.sign());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to sign device payload", e);
        }
    }

    // 计算 SHA-256 十六进制摘要。
    private static String sha256Hex(byte[] input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                sb.append(String.format("%02x", value));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute SHA-256", e);
        }
    }

    // 将字节数组编码为不带补位的 base64url。
    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] decodeBase64Url(String value, String fieldName) {
        try {
            return Base64.getUrlDecoder().decode(requireText(value, fieldName + " is required"));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unable to decode " + fieldName, e);
        }
    }

    // 将十六进制字符串转换为字节数组。
    private static byte[] hex(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return out;
    }

    // 规范化会话键，空值时回退默认会话。
    private static String normalizeSessionKey(String sessionKey) {
        return isBlank(sessionKey) ? DEFAULT_SESSION_KEY : sessionKey;
    }

    // 判断字符串是否包含有效文本。
    private static boolean hasText(String value) {
        return !isBlank(value);
    }

    // JDK 8 兼容版的空白判断。
    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    // 校验字符串是否包含有效文本，并返回去除首尾空白后的结果。
    private static String requireText(String value, String message) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    // 将旧版 Quartz 风格中的 ? 兼容转换为 OpenClaw cron 可接受的 *。
    private static String normalizeCronExpr(String cronExpr) {
        String normalized = requireText(cronExpr, "cronExpr is required").replace('?', '*');
        String[] parts = normalized.split("\\s+");
        if (parts.length != 5 && parts.length != 6) {
            throw new IllegalArgumentException("cronExpr must use 5 fields or 6 fields with seconds");
        }
        return normalized;
    }

    // 将历史 SDK 的 sessionKey 兼容映射为官方 cron 的 sessionTarget。
    private static String normalizeCronSessionTarget(String sessionKey) {
        if (isBlank(sessionKey)) {
            return DEFAULT_CRON_SESSION_TARGET;
        }
        String normalized = sessionKey.trim();
        if (DEFAULT_CRON_MAIN_SESSION.equalsIgnoreCase(normalized)) {
            return DEFAULT_CRON_MAIN_SESSION;
        }
        if (DEFAULT_CRON_SESSION_TARGET.equalsIgnoreCase(normalized)) {
            return DEFAULT_CRON_SESSION_TARGET;
        }
        if (normalized.startsWith("session:")) {
            return normalized;
        }
        return "session:" + normalized;
    }

    // 安全读取 JsonNode 中的文本字段。
    private static String text(JsonNode node, String key) {
        if (node == null || key == null || !node.has(key) || node.get(key).isNull()) {
            return "";
        }
        return node.get(key).asText("");
    }

    // 将 JsonNode 转为便于排障的字符串。
    private static String describeJson(JsonNode node) {
        return node == null || node.isNull() ? "unknown error" : node.toString();
    }

    // JDK 8 兼容版的 CompletableFuture.failedFuture。
    private static <T> CompletableFuture<T> failedFuture(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<T>();
        future.completeExceptionally(error);
        return future;
    }

    private static RuntimeException asRuntimeException(Throwable error, String message) {
        if (error instanceof RuntimeException) {
            return (RuntimeException) error;
        }
        return new IllegalStateException(message, error);
    }

    // JDK 8 兼容版的 CompletableFuture 超时包装。
    private static <T> CompletableFuture<T> applyTimeout(final CompletableFuture<T> future, Duration timeout, String operation) {
        if (timeout == null) {
            return future;
        }
        final long timeoutMillis = timeout.toMillis();
        final ScheduledFuture<?> timeoutTask = TIMEOUT_EXECUTOR.schedule(
                () -> future.completeExceptionally(new TimeoutException(operation + " timed out after " + timeoutMillis + " ms")),
                timeoutMillis,
                TimeUnit.MILLISECONDS
        );
        future.whenComplete((result, error) -> timeoutTask.cancel(false));
        return future;
    }

    // 创建守护线程工厂，避免后台超时线程阻塞进程退出。
    private static ThreadFactory newDaemonThreadFactory(final String threadName) {
        return runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        };
    }

    // 统一结束所有待响应请求。
    private void failPendingRequests(Throwable error) {
        for (PendingRequest request : pendingRequests.values()) {
            request.future().completeExceptionally(error);
        }
        pendingRequests.clear();
    }

    // 统一结束所有聚合中的对话。
    private void failActiveConversations(Throwable error) {
        for (ChatConversation conversation : conversations.values()) {
            conversation.fail(error);
        }
        conversations.clear();
    }

    // 将 agent/chat 事件投递给对应 runId 的对话收集器。
    private void handleConversationEvent(OpenClawMessage message, String rawMessage) {
        if (!"event".equalsIgnoreCase(message.type()) || message.payload() == null) {
            return;
        }
        String event = message.event() == null ? "" : message.event();
        if (!"agent".equals(event) && !"chat".equals(event)) {
            return;
        }
        String runId = text(message.payload(), "runId");
        if (isBlank(runId)) {
            return;
        }
        conversations.computeIfAbsent(runId, ChatConversation::new).accept(message, rawMessage);
    }

    // OkHttp WebSocket 监听器，负责驱动握手与消息分发。
    private final class Listener extends WebSocketListener {
        @Override
        public void onMessage(WebSocket webSocket, String text) {
            handleIncoming(text);
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            handleIncoming(bytes.utf8());
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            IllegalStateException closed = new IllegalStateException("OpenClaw WebSocket closed: " + code + " " + reason);
            failPendingRequests(closed);
            failActiveConversations(closed);
            connected.completeExceptionally(closed);
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable error, Response response) {
            failPendingRequests(error);
            failActiveConversations(error);
            connected.completeExceptionally(error);
        }

        // 解析单条完整报文，并分发到请求等待器或事件监听器。
        private void handleIncoming(String messageText) {
            try {
                OpenClawMessage message = objectMapper.readValue(messageText, OpenClawMessage.class);
                handleConversationEvent(message, messageText);

                if ("event".equalsIgnoreCase(message.type())
                        && "connect.challenge".equalsIgnoreCase(message.event())
                        && message.payload() != null
                        && message.payload().hasNonNull("nonce")) {
                    String nonce = message.payload().get("nonce").asText();
                    call("connect", buildConnectParams(nonce), Duration.ofSeconds(15)).whenComplete((res, err) -> {
                        if (err != null) {
                            connected.completeExceptionally(err);
                        } else if (Boolean.TRUE.equals(res.ok())) {
                            connected.complete(null);
                        } else {
                            connected.completeExceptionally(
                                    new IllegalStateException("connect rejected: " + describeJson(res.error()))
                            );
                        }
                    });
                    Consumer<OpenClawMessage> listener = eventListener;
                    if (listener != null) {
                        listener.accept(message);
                    }
                    return;
                }

                if ("res".equalsIgnoreCase(message.type()) && message.id() != null) {
                    PendingRequest pending = pendingRequests.remove(message.id());
                    if (pending != null) {
                        if ("chat.send".equalsIgnoreCase(pending.method())
                                && message.payload() != null
                                && message.payload().hasNonNull("runId")) {
                            conversations.computeIfAbsent(message.payload().get("runId").asText(), ChatConversation::new);
                        }
                        pending.future().complete(message);
                        return;
                    }
                }

                Consumer<OpenClawMessage> listener = eventListener;
                if (listener != null) {
                    listener.accept(message);
                }
            } catch (Exception ex) {
                Consumer<OpenClawMessage> listener = eventListener;
                if (listener != null) {
                    JsonNode payload = objectMapper.createObjectNode().put("raw", messageText).put("error", ex.getMessage());
                    listener.accept(new OpenClawMessage("parse_error", null, null, null, null, payload, null));
                }
                if (!connected.isDone()) {
                    connected.completeExceptionally(ex);
                }
            }
        }
    }

    // 保存单次底层请求的等待上下文。
    private static final class PendingRequest {
        private final String method;
        private final CompletableFuture<OpenClawMessage> future;

        private PendingRequest(String method, CompletableFuture<OpenClawMessage> future) {
            this.method = method;
            this.future = future;
        }

        private String method() {
            return method;
        }

        private CompletableFuture<OpenClawMessage> future() {
            return future;
        }
    }

    private static final class ClientIdentity {
        private final String clientInstanceId;
        private final KeyPair deviceKeyPair;
        private final String deviceId;
        private final String publicKeyRawBase64Url;

        private ClientIdentity(
                String clientInstanceId,
                KeyPair deviceKeyPair,
                String deviceId,
                String publicKeyRawBase64Url
        ) {
            this.clientInstanceId = clientInstanceId;
            this.deviceKeyPair = deviceKeyPair;
            this.deviceId = deviceId;
            this.publicKeyRawBase64Url = publicKeyRawBase64Url;
        }

        private String clientInstanceId() {
            return clientInstanceId;
        }

        private KeyPair deviceKeyPair() {
            return deviceKeyPair;
        }

        private String deviceId() {
            return deviceId;
        }

        private String publicKeyRawBase64Url() {
            return publicKeyRawBase64Url;
        }
    }

    // 按 runId 聚合一次完整对话的流式事件。
    private static final class ChatConversation {
        private final String runId;
        private final StringBuilder assistantContent = new StringBuilder();
        private final List<String> rawEvents = new ArrayList<String>();
        private final CompletableFuture<Void> completed = new CompletableFuture<Void>();
        private Consumer<String> rawEventSink;
        private int streamedRawEventCount;
        private String errorMessage;

        private ChatConversation(String runId) {
            this.runId = runId;
        }

        // 接收并处理一条原始事件。
        private synchronized void accept(OpenClawMessage message, String rawMessage) {
            if (completed.isDone()) {
                return;
            }
            rawEvents.add(rawMessage);
            RuntimeException deliveryError = deliverPendingRawEvents();
            if (deliveryError != null || completed.isDone()) {
                return;
            }
            if (message.payload() == null) {
                return;
            }
            String event = message.event() == null ? "" : message.event();
            if ("agent".equals(event)) {
                acceptAgentPayload(message.payload());
            } else if ("chat".equals(event)) {
                acceptChatPayload(message.payload());
            }
        }

        private synchronized void bindRawEventSink(Consumer<String> sink) {
            Objects.requireNonNull(sink, "rawEventSink is required");
            if (rawEventSink != null && rawEventSink != sink) {
                throw new IllegalStateException("raw event sink already registered for runId " + runId);
            }
            rawEventSink = sink;
            RuntimeException deliveryError = deliverPendingRawEvents();
            if (deliveryError != null) {
                throw deliveryError;
            }
        }

        private RuntimeException deliverPendingRawEvents() {
            if (rawEventSink == null) {
                return null;
            }
            while (streamedRawEventCount < rawEvents.size()) {
                String rawEvent = rawEvents.get(streamedRawEventCount);
                streamedRawEventCount++;
                try {
                    rawEventSink.accept(rawEvent);
                } catch (Throwable error) {
                    RuntimeException wrapped = asRuntimeException(error, "raw event sink failed");
                    fail(wrapped);
                    return wrapped;
                }
            }
            return null;
        }

        // 聚合 agent 事件中的文本增量和生命周期结束信号。
        private void acceptAgentPayload(JsonNode payload) {
            String stream = text(payload, "stream");
            JsonNode data = payload.get("data");
            if ("assistant".equals(stream) && data != null && data.has("delta")) {
                String delta = data.get("delta").asText("");
                if (!delta.isEmpty()) {
                    assistantContent.append(delta);
                }
            } else if ("lifecycle".equals(stream) && data != null && "end".equals(text(data, "phase"))) {
                completed.complete(null);
            }
        }

        // 处理 chat 事件中的错误状态。
        private void acceptChatPayload(JsonNode payload) {
            if ("error".equals(text(payload, "state"))) {
                String message = text(payload, "message");
                if (isBlank(message)) {
                    message = "assistant stream returned error state";
                }
                errorMessage = message;
                completed.completeExceptionally(new IllegalStateException(message));
            }
        }

        // 等待当前对话完成。
        private CompletableFuture<Void> await(Duration timeout) {
            CompletableFuture<Void> result = new CompletableFuture<Void>();
            completed.whenComplete((ignored, error) -> {
                if (error != null) {
                    result.completeExceptionally(error);
                } else {
                    result.complete(null);
                }
            });
            return applyTimeout(result, timeout == null ? DEFAULT_STREAM_TIMEOUT : timeout, "OpenClaw stream");
        }

        // 生成当前对话的只读快照。
        private synchronized ChatConversationSnapshot snapshot() {
            return new ChatConversationSnapshot(
                    runId,
                    assistantContent.toString(),
                    Collections.unmodifiableList(new ArrayList<String>(rawEvents)),
                    errorMessage
            );
        }

        // 将当前对话标记为失败。
        private synchronized void fail(Throwable error) {
            if (completed.isDone()) {
                return;
            }
            errorMessage = error.getMessage() == null ? error.getClass().getName() : error.getMessage();
            completed.completeExceptionally(new IllegalStateException(errorMessage, error));
        }

        private String runId() {
            return runId;
        }
    }

    // 保存一次完整对话的聚合结果。
    private static final class ChatConversationSnapshot {
        private final String runId;
        private final String assistantContent;
        private final List<String> rawEvents;
        private final String errorMessage;

        private ChatConversationSnapshot(String runId, String assistantContent, List<String> rawEvents, String errorMessage) {
            this.runId = runId;
            this.assistantContent = assistantContent;
            this.rawEvents = rawEvents;
            this.errorMessage = errorMessage;
        }

        private String runId() {
            return runId;
        }

        private String assistantContent() {
            return assistantContent;
        }

        private List<String> rawEvents() {
            return rawEvents;
        }

        @SuppressWarnings("unused")
        private String errorMessage() {
            return errorMessage;
        }
    }
}
