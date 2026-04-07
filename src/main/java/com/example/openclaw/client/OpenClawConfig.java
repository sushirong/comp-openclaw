package com.example.openclaw.client;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * OpenClaw 网关连接配置。
 */
public final class OpenClawConfig {
    // 保存 OpenClaw 网关的 WebSocket 地址。
    private final URI gatewayUri;
    // 保存建立连接时使用的超时时间。
    private final Duration connectTimeout;
    // 保存访问网关时使用的鉴权令牌。
    private final String authToken;

    /**
     * 根据构建器中的参数创建配置对象。
     *
     * @param builder 构建器中暂存的配置参数
     */
    private OpenClawConfig(Builder builder) {
        // 校验网关地址是否已经传入。
        this.gatewayUri = Objects.requireNonNull(builder.gatewayUri, "gatewayUri is required");
        // 未显式指定超时时间时使用默认值。
        this.connectTimeout = builder.connectTimeout == null ? Duration.ofSeconds(10) : builder.connectTimeout;
        // 保留调用方传入的令牌内容。
        this.authToken = builder.authToken;
    }

    /**
     * 返回网关地址。
     *
     * @return OpenClaw 网关地址
     */
    public URI gatewayUri() {
        // 返回配置中的网关地址。
        return gatewayUri;
    }

    /**
     * 返回连接超时时间。
     *
     * @return 连接超时时间
     */
    public Duration connectTimeout() {
        // 返回建立连接时使用的超时时间。
        return connectTimeout;
    }

    /**
     * 返回网关令牌。
     *
     * @return 鉴权令牌
     */
    public String authToken() {
        // 返回调用方配置的令牌内容。
        return authToken;
    }

    /**
     * 使用字符串网关地址快速创建配置。
     *
     * @param gatewayUri 网关地址
     * @return 配置对象
     */
    public static OpenClawConfig of(String gatewayUri) {
        // 仅设置网关地址，其余配置使用默认值。
        return builder()
                .gatewayUri(gatewayUri)
                .build();
    }

    /**
     * 使用字符串网关地址和令牌快速创建配置。
     *
     * @param gatewayUri 网关地址
     * @param authToken 鉴权令牌
     * @return 配置对象
     */
    public static OpenClawConfig of(String gatewayUri, String authToken) {
        // 在基础网关地址上补充令牌配置。
        return builder()
                .gatewayUri(gatewayUri)
                .authToken(authToken)
                .build();
    }

    /**
     * 使用字符串网关地址和连接超时时间快速创建配置。
     *
     * @param gatewayUri 网关地址
     * @param connectTimeout 连接超时时间
     * @return 配置对象
     */
    public static OpenClawConfig of(String gatewayUri, Duration connectTimeout) {
        // 在基础网关地址上补充连接超时时间配置。
        return builder()
                .gatewayUri(gatewayUri)
                .connectTimeout(connectTimeout)
                .build();
    }

    /**
     * 使用字符串网关地址、令牌和连接超时时间快速创建配置。
     *
     * @param gatewayUri 网关地址
     * @param authToken 鉴权令牌
     * @param connectTimeout 连接超时时间
     * @return 配置对象
     */
    public static OpenClawConfig of(String gatewayUri, String authToken, Duration connectTimeout) {
        // 同时补齐网关地址、令牌和超时时间。
        return builder()
                .gatewayUri(gatewayUri)
                .authToken(authToken)
                .connectTimeout(connectTimeout)
                .build();
    }

    /**
     * 使用 URI 网关地址快速创建配置。
     *
     * @param gatewayUri 网关地址
     * @return 配置对象
     */
    public static OpenClawConfig of(URI gatewayUri) {
        // 直接使用现成的 URI 对象构建配置。
        return builder()
                .gatewayUri(gatewayUri)
                .build();
    }

    /**
     * 使用 URI 网关地址、令牌和连接超时时间快速创建配置。
     *
     * @param gatewayUri 网关地址
     * @param authToken 鉴权令牌
     * @param connectTimeout 连接超时时间
     * @return 配置对象
     */
    public static OpenClawConfig of(URI gatewayUri, String authToken, Duration connectTimeout) {
        // 使用外部传入的 URI 和附加参数构造完整配置。
        return builder()
                .gatewayUri(gatewayUri)
                .authToken(authToken)
                .connectTimeout(connectTimeout)
                .build();
    }

    /**
     * 创建配置构建器。
     *
     * @return 构建器实例
     */
    public static Builder builder() {
        // 返回新的构建器实例。
        return new Builder();
    }

    /**
     * 配置构建器。
     */
    public static final class Builder {
        // 暂存调用方传入的网关地址。
        private URI gatewayUri;
        // 暂存调用方传入的连接超时时间。
        private Duration connectTimeout;
        // 暂存调用方传入的鉴权令牌。
        private String authToken;

        /**
         * 使用字符串地址设置网关地址。
         *
         * @param uri 网关地址字符串
         * @return 当前构建器
         */
        public Builder gatewayUri(String uri) {
            // 将字符串地址转换成 URI 对象后保存。
            this.gatewayUri = URI.create(uri);
            // 返回当前构建器以支持链式调用。
            return this;
        }

        /**
         * 使用 URI 设置网关地址。
         *
         * @param uri 网关地址
         * @return 当前构建器
         */
        public Builder gatewayUri(URI uri) {
            // 直接保存调用方传入的 URI 对象。
            this.gatewayUri = Objects.requireNonNull(uri, "gatewayUri is required");
            // 返回当前构建器以支持链式调用。
            return this;
        }

        /**
         * 设置连接超时时间。
         *
         * @param timeout 连接超时时间
         * @return 当前构建器
         */
        public Builder connectTimeout(Duration timeout) {
            // 保存连接超时时间。
            this.connectTimeout = timeout;
            // 返回当前构建器以支持链式调用。
            return this;
        }

        /**
         * 设置鉴权令牌。
         *
         * @param token 鉴权令牌
         * @return 当前构建器
         */
        public Builder authToken(String token) {
            // 保存调用方传入的令牌内容。
            this.authToken = token;
            // 返回当前构建器以支持链式调用。
            return this;
        }

        /**
         * 构建配置对象。
         *
         * @return 配置对象
         */
        public OpenClawConfig build() {
            // 使用当前构建器中的参数创建不可变配置对象。
            return new OpenClawConfig(this);
        }
    }
}