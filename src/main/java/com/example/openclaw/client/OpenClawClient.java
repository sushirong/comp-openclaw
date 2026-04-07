package com.example.openclaw.client;

import com.example.openclaw.model.OpenClawMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * OpenClaw Java SDK 客户端。
 */
public final class OpenClawClient implements AutoCloseable {
    // 协议版本固定为 OpenClaw v3。
    private static final int PROTOCOL_VERSION = 3;
    // 默认会话键用于未传入 sessionKey 的场景。
    private static final String DEFAULT_SESSION_KEY = "main";
    // 默认请求超时时间用于等待 req/res 响应。
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    // 默认流式超时时间用于等待一次完整对话结束。
    private static final Duration DEFAULT_STREAM_TIMEOUT = Duration.ofSeconds(180);
    // 握手时上报的客户端标识。
    private static final String DEFAULT_CLIENT_ID = "openclaw-tui";
    // 握手时上报的客户端模式。
    private static final String DEFAULT_CLIENT_MODE = "ui";
    // 握手时上报的客户端角色。
    private static final String DEFAULT_ROLE = "operator";
    // 握手时上报的平台信息。
    private static final String DEFAULT_PLATFORM = "java";
    // 握手时上报的设备类型。
    private static final String DEFAULT_DEVICE_FAMILY = "desktop";
    // HTTP 请求和握手参数中复用的用户代理字符串。
    private static final String DEFAULT_USER_AGENT = "openclaw-java-sdk-demo/1.0";
    // Ed25519 公钥的 SPKI 前缀用于提取 raw public key。
    private static final byte[] ED25519_SPKI_PREFIX = hex("302a300506032b6570032100");

    // 保存 SDK 配置对象。
    private final OpenClawConfig config;
    // 保存 JSON 序列化组件。
    private final ObjectMapper objectMapper;
    // 保存底层 HTTP/WebSocket 客户端。
    private final HttpClient httpClient;
    // 保存设备身份使用的密钥对。
    private final KeyPair deviceKeyPair;
    // 保存设备唯一标识。
    private final String deviceId;
    // 保存 raw public key 的 base64url 字符串。
    private final String publicKeyRawBase64Url;
    // 保存待响应请求映射。
    private final Map<String, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
    // 保存按 runId 聚合中的对话映射。
    private final Map<String, ChatConversation> conversations = new ConcurrentHashMap<>();
    // 保存逻辑握手完成信号。
    private final CompletableFuture<Void> connected = new CompletableFuture<>();
    // 保存当前 WebSocket 连接。
    private volatile WebSocket webSocket;
    // 保存外部注册的事件监听器。
    private volatile Consumer<OpenClawMessage> eventListener;

    /**
     * 使用配置创建客户端。
     *
     * @param config SDK 配置
     */
    public OpenClawClient(OpenClawConfig config) {
        // 保存配置对象。
        this.config = Objects.requireNonNull(config, "config is required");
        // 初始化 JSON 处理器。
        this.objectMapper = new ObjectMapper();
        // 初始化底层 HTTP 客户端。
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                .build();
        // 生成设备密钥对。
        this.deviceKeyPair = generateDeviceKeyPair();
        // 提取原始公钥。
        byte[] publicKeyRaw = extractEd25519RawPublicKey(deviceKeyPair.getPublic());
        // 计算设备标识。
        this.deviceId = sha256Hex(publicKeyRaw);
        // 编码原始公钥。
        this.publicKeyRawBase64Url = base64Url(publicKeyRaw);
    }

    /**
     * 使用网关地址创建客户端。
     *
     * @param gatewayUri 网关地址
     */
    public OpenClawClient(String gatewayUri) {
        // 使用默认配置创建客户端。
        this(OpenClawConfig.of(gatewayUri));
    }

    /**
     * 使用网关地址和令牌创建客户端。
     *
     * @param gatewayUri 网关地址
     * @param authToken 鉴权令牌
     */
    public OpenClawClient(String gatewayUri, String authToken) {
        // 使用网关地址和令牌创建客户端。
        this(OpenClawConfig.of(gatewayUri, authToken));
    }

    /**
     * 使用网关地址和连接超时时间创建客户端。
     *
     * @param gatewayUri 网关地址
     * @param connectTimeout 连接超时时间
     */
    public OpenClawClient(String gatewayUri, Duration connectTimeout) {
        // 使用网关地址和连接超时时间创建客户端。
        this(OpenClawConfig.of(gatewayUri, connectTimeout));
    }

    /**
     * 使用完整连接参数创建客户端。
     *
     * @param gatewayUri 网关地址
     * @param authToken 鉴权令牌
     * @param connectTimeout 连接超时时间
     */
    public OpenClawClient(String gatewayUri, String authToken, Duration connectTimeout) {
        // 使用完整连接参数创建客户端。
        this(OpenClawConfig.of(gatewayUri, authToken, connectTimeout));
    }

    /**
     * 初始化并连接客户端。
     *
     * @param config SDK 配置
     * @return 已完成连接的客户端
     */
    public static CompletableFuture<OpenClawClient> init(OpenClawConfig config) {
        // 创建客户端实例。
        OpenClawClient client = new OpenClawClient(config);
        // 建立连接成功后返回客户端实例。
        return client.connect()
                .thenApply(ignored -> client)
                .whenComplete((readyClient, error) -> {
                    // 初始化失败时关闭已创建的客户端。
                    if (error != null) {
                        client.close();
                    }
                });
    }

    /**
     * 使用网关地址初始化并连接客户端。
     *
     * @param gatewayUri 网关地址
     * @return 已完成连接的客户端
     */
    public static CompletableFuture<OpenClawClient> init(String gatewayUri) {
        // 使用网关地址完成初始化。
        return init(OpenClawConfig.of(gatewayUri));
    }

    /**
     * 使用网关地址和令牌初始化并连接客户端。
     *
     * @param gatewayUri 网关地址
     * @param authToken 鉴权令牌
     * @return 已完成连接的客户端
     */
    public static CompletableFuture<OpenClawClient> init(String gatewayUri, String authToken) {
        // 使用网关地址和令牌完成初始化。
        return init(OpenClawConfig.of(gatewayUri, authToken));
    }

    /**
     * 使用网关地址和连接超时时间初始化并连接客户端。
     *
     * @param gatewayUri 网关地址
     * @param connectTimeout 连接超时时间
     * @return 已完成连接的客户端
     */
    public static CompletableFuture<OpenClawClient> init(String gatewayUri, Duration connectTimeout) {
        // 使用网关地址和超时时间完成初始化。
        return init(OpenClawConfig.of(gatewayUri, connectTimeout));
    }

    /**
     * 使用完整参数初始化并连接客户端。
     *
     * @param gatewayUri 网关地址
     * @param authToken 鉴权令牌
     * @param connectTimeout 连接超时时间
     * @return 已完成连接的客户端
     */
    public static CompletableFuture<OpenClawClient> init(String gatewayUri, String authToken, Duration connectTimeout) {
        // 使用完整连接参数完成初始化。
        return init(OpenClawConfig.of(gatewayUri, authToken, connectTimeout));
    }

    /**
     * 建立 WebSocket 连接并等待握手完成。
     *
     * @return 握手完成信号
     */
    public CompletableFuture<Void> connect() {
        // 创建 WebSocket 构建器。
        WebSocket.Builder builder = httpClient.newWebSocketBuilder()
                .connectTimeout(config.connectTimeout())
                .header("User-Agent", DEFAULT_USER_AGENT);

        // 已配置令牌时补充 Authorization 头。
        if (config.authToken() != null && !config.authToken().isBlank()) {
            // 按网关要求编码 token。
            String encoded = Base64.getEncoder()
                    .encodeToString(("token:" + config.authToken()).getBytes(StandardCharsets.UTF_8));
            // 写入 Authorization 请求头。
            builder.header("Authorization", "Basic " + encoded);
        }

        // 建立物理连接并等待逻辑握手完成。
        return builder.buildAsync(config.gatewayUri(), new Listener())
                .thenAccept(ws -> this.webSocket = ws)
                .thenCompose(ignored -> connected);
    }

    /**
     * 发送底层协议调用。
     *
     * @param method 方法名
     * @param params 请求参数
     * @param timeout 请求超时时间
     * @return 原始响应消息
     */
    public CompletableFuture<OpenClawMessage> call(String method, JsonNode params, Duration timeout) {
        // 校验连接是否已经建立。
        if (webSocket == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("WebSocket is not connected"));
        }

        // 生成本次请求的唯一 id。
        String requestId = UUID.randomUUID().toString();
        // 组装请求报文。
        ObjectNode request = objectMapper.createObjectNode();
        request.put("type", "req");
        request.put("id", requestId);
        request.put("method", method);
        if (params != null) {
            request.set("params", params);
        }

        // 创建等待响应的 Future。
        CompletableFuture<OpenClawMessage> responseFuture = new CompletableFuture<>();
        // 保存请求方法和 Future 的映射关系。
        PendingRequest pendingRequest = new PendingRequest(method, responseFuture);
        pendingRequests.put(requestId, pendingRequest);
        // 请求完成后移除映射关系。
        responseFuture.whenComplete((message, error) -> pendingRequests.remove(requestId, pendingRequest));

        final String text;
        try {
            // 序列化请求报文。
            text = objectMapper.writeValueAsString(request);
        } catch (IOException e) {
            // 序列化失败时清理待响应映射。
            pendingRequests.remove(requestId, pendingRequest);
            return CompletableFuture.failedFuture(e);
        }

        // 发送请求文本。
        webSocket.sendText(text, true)
                .whenComplete((ignored, error) -> {
                    // 发送失败时直接结束本次调用。
                    if (error != null) {
                        pendingRequests.remove(requestId, pendingRequest);
                        responseFuture.completeExceptionally(error);
                    }
                });

        // 计算实际超时时间。
        Duration effective = timeout == null ? DEFAULT_REQUEST_TIMEOUT : timeout;
        return responseFuture.orTimeout(effective.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * 发送 chat.send 并返回原始响应消息。
     *
     * @param message 用户消息
     * @return 原始响应消息
     */
    public CompletableFuture<OpenClawMessage> sendChat(String message) {
        // 使用默认会话键发送聊天请求。
        return sendChat(DEFAULT_SESSION_KEY, message, DEFAULT_REQUEST_TIMEOUT);
    }

    /**
     * 发送 chat.send 并返回原始响应消息。
     *
     * @param sessionKey 会话键
     * @param message 用户消息
     * @return 原始响应消息
     */
    public CompletableFuture<OpenClawMessage> sendChat(String sessionKey, String message) {
        // 使用默认请求超时时间发送聊天请求。
        return sendChat(sessionKey, message, DEFAULT_REQUEST_TIMEOUT);
    }

    /**
     * 发送 chat.send 并返回原始响应消息。
     *
     * @param sessionKey 会话键
     * @param message 用户消息
     * @param timeout 请求超时时间
     * @return 原始响应消息
     */
    public CompletableFuture<OpenClawMessage> sendChat(String sessionKey, String message, Duration timeout) {
        // 校验消息内容不能为空。
        Objects.requireNonNull(message, "message is required");
        // 组装 chat.send 参数。
        ObjectNode params = objectMapper.createObjectNode();
        params.put("sessionKey", normalizeSessionKey(sessionKey));
        params.put("message", message);
        params.put("idempotencyKey", UUID.randomUUID().toString());
        return call("chat.send", params, timeout);
    }

    /**
     * 发送聊天请求并返回拼接后的正常消息内容。
     *
     * @param message 用户消息
     * @return 完整回复文本
     */
    public CompletableFuture<String> sendChatText(String message) {
        // 使用默认会话键和默认超时时间获取完整回复。
        return sendChatText(DEFAULT_SESSION_KEY, message, DEFAULT_REQUEST_TIMEOUT, DEFAULT_STREAM_TIMEOUT);
    }

    /**
     * 发送聊天请求并返回拼接后的正常消息内容。
     *
     * @param sessionKey 会话键
     * @param message 用户消息
     * @return 完整回复文本
     */
    public CompletableFuture<String> sendChatText(String sessionKey, String message) {
        // 使用指定会话键和默认超时时间获取完整回复。
        return sendChatText(sessionKey, message, DEFAULT_REQUEST_TIMEOUT, DEFAULT_STREAM_TIMEOUT);
    }

    /**
     * 发送聊天请求并返回拼接后的正常消息内容。
     *
     * @param sessionKey 会话键
     * @param message 用户消息
     * @param requestTimeout 请求超时时间
     * @param streamTimeout 流式超时时间
     * @return 完整回复文本
     */
    public CompletableFuture<String> sendChatText(
            String sessionKey,
            String message,
            Duration requestTimeout,
            Duration streamTimeout
    ) {
        // 执行完整对话采集流程并提取最终文本。
        return executeChatConversation(sessionKey, message, requestTimeout, streamTimeout)
                .thenApply(ChatConversationSnapshot::assistantContent);
    }

    /**
     * 发送聊天请求并返回原始 event 消息体集合。
     *
     * @param message 用户消息
     * @return 原始 event 消息体集合
     */
    public CompletableFuture<List<String>> sendChatRawEvents(String message) {
        // 使用默认会话键和默认超时时间获取原始事件。
        return sendChatRawEvents(DEFAULT_SESSION_KEY, message, DEFAULT_REQUEST_TIMEOUT, DEFAULT_STREAM_TIMEOUT);
    }

    /**
     * 发送聊天请求并返回原始 event 消息体集合。
     *
     * @param sessionKey 会话键
     * @param message 用户消息
     * @return 原始 event 消息体集合
     */
    public CompletableFuture<List<String>> sendChatRawEvents(String sessionKey, String message) {
        // 使用指定会话键和默认超时时间获取原始事件。
        return sendChatRawEvents(sessionKey, message, DEFAULT_REQUEST_TIMEOUT, DEFAULT_STREAM_TIMEOUT);
    }

    /**
     * 发送聊天请求并返回原始 event 消息体集合。
     *
     * @param sessionKey 会话键
     * @param message 用户消息
     * @param requestTimeout 请求超时时间
     * @param streamTimeout 流式超时时间
     * @return 原始 event 消息体集合
     */
    public CompletableFuture<List<String>> sendChatRawEvents(
            String sessionKey,
            String message,
            Duration requestTimeout,
            Duration streamTimeout
    ) {
        // 执行完整对话采集流程并提取原始事件列表。
        return executeChatConversation(sessionKey, message, requestTimeout, streamTimeout)
                .thenApply(ChatConversationSnapshot::rawEvents);
    }

    /**
     * 注册外部事件监听器。
     *
     * @param listener 事件监听器
     */
    public void setEventListener(Consumer<OpenClawMessage> listener) {
        // 保存外部事件监听器。
        this.eventListener = listener;
    }

    /**
     * 关闭客户端连接。
     */
    @Override
    public void close() {
        // 读取当前连接引用。
        WebSocket ws = this.webSocket;
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        }
    }

    // 执行一次完整对话并返回聚合结果。
    private CompletableFuture<ChatConversationSnapshot> executeChatConversation(
            String sessionKey,
            String message,
            Duration requestTimeout,
            Duration streamTimeout
    ) {
        // 计算请求阶段的实际超时时间。
        Duration effectiveRequestTimeout = requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;
        // 计算流式阶段的实际超时时间。
        Duration effectiveStreamTimeout = streamTimeout == null ? DEFAULT_STREAM_TIMEOUT : streamTimeout;

        // 先发送 chat.send 请求。
        return sendChat(sessionKey, message, effectiveRequestTimeout)
                .thenCompose(response -> {
                    // 响应明确失败时直接抛出异常。
                    if (!Boolean.TRUE.equals(response.ok())) {
                        return CompletableFuture.failedFuture(
                                new IllegalStateException("chat.send rejected: " + describeJson(response.error()))
                        );
                    }

                    // 提取当前对话的 runId。
                    String runId = response.payload() == null ? null : response.payload().path("runId").asText(null);
                    if (runId == null || runId.isBlank()) {
                        return CompletableFuture.failedFuture(
                                new IllegalStateException("chat.send response does not contain runId")
                        );
                    }

                    // 取出或创建 runId 对应的对话收集器。
                    ChatConversation conversation = conversations.computeIfAbsent(runId, ChatConversation::new);
                    // 等待流式结束后生成结果快照，并在结束后移除缓存。
                    return conversation.await(effectiveStreamTimeout)
                            .thenApply(ignored -> conversation.snapshot())
                            .whenComplete((snapshot, error) -> conversations.remove(runId, conversation));
                });
    }

    // 组装 connect 方法需要的参数。
    private ObjectNode buildConnectParams(String nonce) {
        // 创建 connect 参数节点。
        ObjectNode params = objectMapper.createObjectNode();
        params.put("minProtocol", PROTOCOL_VERSION);
        params.put("maxProtocol", PROTOCOL_VERSION);

        // 组装 client 节点。
        ObjectNode client = objectMapper.createObjectNode();
        client.put("id", DEFAULT_CLIENT_ID);
        client.put("displayName", "openclaw java sdk demo");
        client.put("version", "1.0.0");
        client.put("platform", DEFAULT_PLATFORM);
        client.put("deviceFamily", DEFAULT_DEVICE_FAMILY);
        client.put("mode", DEFAULT_CLIENT_MODE);
        client.put("instanceId", UUID.randomUUID().toString());
        params.set("client", client);

        // 写入通用参数。
        params.put("locale", "zh-CN");
        params.put("userAgent", DEFAULT_USER_AGENT);
        params.put("role", DEFAULT_ROLE);

        // 写入权限范围。
        ArrayNode scopes = objectMapper.createArrayNode();
        scopes.add("operator.read");
        scopes.add("operator.write");
        params.set("scopes", scopes);

        // 写入空的能力占位节点。
        params.set("caps", objectMapper.createArrayNode());
        params.set("commands", objectMapper.createArrayNode());
        params.set("permissions", objectMapper.createObjectNode());

        // 配置了令牌时补充 auth 节点。
        if (config.authToken() != null && !config.authToken().isBlank()) {
            ObjectNode auth = objectMapper.createObjectNode();
            auth.put("token", config.authToken());
            params.set("auth", auth);
        }

        // 计算签名时间。
        long signedAt = System.currentTimeMillis();
        // 组装设备签名载荷。
        String signaturePayload = String.join("|",
                "v3",
                deviceId,
                DEFAULT_CLIENT_ID,
                DEFAULT_CLIENT_MODE,
                DEFAULT_ROLE,
                "operator.read,operator.write",
                String.valueOf(signedAt),
                config.authToken() == null ? "" : config.authToken(),
                nonce,
                DEFAULT_PLATFORM,
                DEFAULT_DEVICE_FAMILY);
        // 使用设备私钥进行签名。
        String signature = signBase64Url(deviceKeyPair.getPrivate(), signaturePayload);

        // 写入设备身份节点。
        ObjectNode device = objectMapper.createObjectNode();
        device.put("id", deviceId);
        device.put("publicKey", publicKeyRawBase64Url);
        device.put("signature", signature);
        device.put("signedAt", signedAt);
        device.put("nonce", nonce);
        params.set("device", device);
        return params;
    }

    // 生成 Ed25519 设备密钥对。
    private static KeyPair generateDeviceKeyPair() {
        try {
            // 获取 Ed25519 密钥生成器。
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            // 生成并返回密钥对。
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to generate device key pair", e);
        }
    }

    // 提取 Ed25519 原始公钥字节。
    private static byte[] extractEd25519RawPublicKey(PublicKey publicKey) {
        // 读取公钥编码内容。
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
        // 编码结构不匹配时回退返回完整编码。
        return encoded;
    }

    // 对载荷执行 Ed25519 签名并返回 base64url 字符串。
    private static String signBase64Url(PrivateKey privateKey, String payload) {
        try {
            java.security.Signature signer = java.security.Signature.getInstance("Ed25519");
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
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute SHA-256", e);
        }
    }

    // 将字节数组编码成不带补位的 base64url 字符串。
    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // 将十六进制字符串转换成字节数组。
    private static byte[] hex(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return out;
    }

    // 规范化会话键。
    private static String normalizeSessionKey(String sessionKey) {
        return sessionKey == null || sessionKey.isBlank() ? DEFAULT_SESSION_KEY : sessionKey;
    }

    // 安全读取 JSON 文本字段。
    private static String text(JsonNode node, String key) {
        if (node == null || key == null || !node.has(key) || node.get(key).isNull()) {
            return "";
        }
        return node.get(key).asText("");
    }

    // 将 JsonNode 转换成可读错误文本。
    private static String describeJson(JsonNode node) {
        return node == null || node.isNull() ? "unknown error" : node.toString();
    }

    // 将所有待响应请求统一置为失败。
    private void failPendingRequests(Throwable error) {
        pendingRequests.values().forEach(request -> request.future().completeExceptionally(error));
        pendingRequests.clear();
    }

    // 将所有聚合中的对话统一置为失败。
    private void failActiveConversations(Throwable error) {
        conversations.values().forEach(conversation -> conversation.fail(error));
        conversations.clear();
    }

    // 将对话相关 event 报文分发给对应 runId 的收集器。
    private void handleConversationEvent(OpenClawMessage message, String rawMessage) {
        if (!"event".equalsIgnoreCase(message.type()) || message.payload() == null) {
            return;
        }
        String event = message.event() == null ? "" : message.event();
        if (!"agent".equals(event) && !"chat".equals(event)) {
            return;
        }
        String runId = text(message.payload(), "runId");
        if (runId.isBlank()) {
            return;
        }
        conversations.computeIfAbsent(runId, ChatConversation::new).accept(message, rawMessage);
    }

    // WebSocket 文本消息监听器。
    private final class Listener implements WebSocket.Listener {
        // 保存分片文本，last=true 时统一解析。
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            WebSocket.Listener.super.onOpen(webSocket);
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            // 追加当前文本分片。
            buffer.append(data);
            if (last) {
                // 读取完整报文并清空缓冲区。
                String messageText = buffer.toString();
                buffer.setLength(0);
                // 处理完整报文。
                handleIncoming(messageText);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            // 构造统一关闭异常。
            IllegalStateException closed = new IllegalStateException(
                    "OpenClaw WebSocket closed: " + statusCode + " " + reason
            );
            failPendingRequests(closed);
            failActiveConversations(closed);
            connected.completeExceptionally(closed);
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            failPendingRequests(error);
            failActiveConversations(error);
            connected.completeExceptionally(error);
            WebSocket.Listener.super.onError(webSocket, error);
        }

        // 处理完整文本报文。
        private void handleIncoming(String messageText) {
            try {
                // 反序列化统一消息对象。
                OpenClawMessage message = objectMapper.readValue(messageText, OpenClawMessage.class);
                // 对话事件先交给内部聚合器处理。
                handleConversationEvent(message, messageText);

                // 收到 connect.challenge 时立即回发 connect 完成逻辑握手。
                if ("event".equalsIgnoreCase(message.type())
                        && "connect.challenge".equalsIgnoreCase(message.event())
                        && message.payload() != null
                        && message.payload().hasNonNull("nonce")) {
                    String nonce = message.payload().get("nonce").asText();
                    call("connect", buildConnectParams(nonce), Duration.ofSeconds(15))
                            .whenComplete((res, err) -> {
                                if (err != null) {
                                    connected.completeExceptionally(err);
                                    return;
                                }
                                if (Boolean.TRUE.equals(res.ok())) {
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

                // 收到响应报文时按请求 id 回填等待中的 Future。
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

                // 其余消息透出给外部监听器。
                Consumer<OpenClawMessage> listener = eventListener;
                if (listener != null) {
                    listener.accept(message);
                }
            } catch (Exception ex) {
                Consumer<OpenClawMessage> listener = eventListener;
                if (listener != null) {
                    JsonNode payload = objectMapper.createObjectNode()
                            .put("raw", messageText)
                            .put("error", ex.getMessage());
                    listener.accept(new OpenClawMessage("parse_error", null, null, null, null, payload, null));
                }
                if (!connected.isDone()) {
                    connected.completeExceptionally(ex);
                }
            }
        }
    }

    // 保存待响应请求信息。
    private record PendingRequest(String method, CompletableFuture<OpenClawMessage> future) {
    }

    // 保存一次 runId 对应的对话聚合结果。
    private static final class ChatConversation {
        // 保存当前 runId。
        private final String runId;
        // 保存拼接后的助手文本。
        private final StringBuilder assistantContent = new StringBuilder();
        // 保存原始 event 报文列表。
        private final List<String> rawEvents = new ArrayList<>();
        // 保存对话完成信号。
        private final CompletableFuture<Void> completed = new CompletableFuture<>();
        // 保存错误信息文本。
        private String errorMessage;

        private ChatConversation(String runId) {
            this.runId = runId;
        }

        // 接收并处理一条 event 消息。
        private synchronized void accept(OpenClawMessage message, String rawMessage) {
            if (completed.isDone()) {
                return;
            }
            rawEvents.add(rawMessage);
            if (message.payload() == null) {
                return;
            }

            String event = message.event() == null ? "" : message.event();
            if ("agent".equals(event)) {
                acceptAgentPayload(message.payload());
                return;
            }
            if ("chat".equals(event)) {
                acceptChatPayload(message.payload());
            }
        }

        // 处理 agent 流式事件。
        private void acceptAgentPayload(JsonNode payload) {
            String stream = text(payload, "stream");
            JsonNode data = payload.get("data");

            if ("assistant".equals(stream) && data != null && data.has("delta")) {
                String delta = data.get("delta").asText("");
                if (!delta.isEmpty()) {
                    assistantContent.append(delta);
                }
                return;
            }

            if ("lifecycle".equals(stream) && data != null && "end".equals(text(data, "phase"))) {
                completed.complete(null);
            }
        }

        // 处理 chat 状态事件。
        private void acceptChatPayload(JsonNode payload) {
            if ("error".equals(text(payload, "state"))) {
                String message = text(payload, "message");
                if (message.isBlank()) {
                    message = "assistant stream returned error state";
                }
                errorMessage = message;
                completed.completeExceptionally(new IllegalStateException(message));
            }
        }

        // 等待当前对话结束。
        private CompletableFuture<Void> await(Duration timeout) {
            Duration effective = timeout == null ? DEFAULT_STREAM_TIMEOUT : timeout;
            CompletableFuture<Void> result = new CompletableFuture<>();
            completed.whenComplete((ignored, error) -> {
                if (error != null) {
                    result.completeExceptionally(error);
                    return;
                }
                result.complete(null);
            });
            return result.orTimeout(effective.toMillis(), TimeUnit.MILLISECONDS);
        }

        // 生成当前对话的不可变快照。
        private synchronized ChatConversationSnapshot snapshot() {
            return new ChatConversationSnapshot(runId, assistantContent.toString(), List.copyOf(rawEvents), errorMessage);
        }

        // 将当前对话标记为失败。
        private synchronized void fail(Throwable error) {
            if (completed.isDone()) {
                return;
            }
            errorMessage = error.getMessage() == null ? error.getClass().getName() : error.getMessage();
            completed.completeExceptionally(new IllegalStateException(errorMessage, error));
        }
    }

    // 保存一次完整对话的结果快照。
    private record ChatConversationSnapshot(
            String runId,
            String assistantContent,
            List<String> rawEvents,
            String errorMessage
    ) {
    }
}
