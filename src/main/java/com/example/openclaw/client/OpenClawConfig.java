package com.example.openclaw.client;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * OpenClaw gateway connection settings.
 */
public final class OpenClawConfig {
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final URI gatewayUri;
    private final Duration connectTimeout;
    private final String authToken;
    private final String origin;
    private final String clientInstanceId;
    private final String deviceId;
    private final String devicePrivateKeyPkcs8Base64Url;

    private OpenClawConfig(Builder builder) {
        this.gatewayUri = Objects.requireNonNull(builder.gatewayUri, "gatewayUri is required");
        this.connectTimeout = builder.connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : builder.connectTimeout;
        this.authToken = trimToNull(builder.authToken);
        this.origin = trimToNull(builder.origin);
        this.clientInstanceId = trimToNull(builder.clientInstanceId);
        this.deviceId = trimToNull(builder.deviceId);
        this.devicePrivateKeyPkcs8Base64Url = trimToNull(builder.devicePrivateKeyPkcs8Base64Url);
    }

    public URI gatewayUri() {
        return gatewayUri;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public String authToken() {
        return authToken;
    }

    /**
     * Optional Origin header value for the WebSocket upgrade request.
     */
    public String origin() {
        return origin;
    }

    /**
     * Optional fixed client instance identifier for the connect handshake.
     */
    public String clientInstanceId() {
        return clientInstanceId;
    }

    /**
     * Optional expected device identifier. When provided, it must match the
     * deviceId derived from the configured device private key.
     */
    public String deviceId() {
        return deviceId;
    }

    /**
     * Optional Ed25519 private key encoded as PKCS#8 base64url, used to derive
     * the stable device identity for the connect handshake.
     */
    public String devicePrivateKeyPkcs8Base64Url() {
        return devicePrivateKeyPkcs8Base64Url;
    }

    public static OpenClawConfig of(String gatewayUri) {
        return builder()
                .gatewayUri(gatewayUri)
                .build();
    }

    public static OpenClawConfig of(String gatewayUri, String authToken) {
        return builder()
                .gatewayUri(gatewayUri)
                .authToken(authToken)
                .build();
    }

    public static OpenClawConfig of(String gatewayUri, Duration connectTimeout) {
        return builder()
                .gatewayUri(gatewayUri)
                .connectTimeout(connectTimeout)
                .build();
    }

    public static OpenClawConfig of(String gatewayUri, String authToken, Duration connectTimeout) {
        return builder()
                .gatewayUri(gatewayUri)
                .authToken(authToken)
                .connectTimeout(connectTimeout)
                .build();
    }

    public static OpenClawConfig of(URI gatewayUri) {
        return builder()
                .gatewayUri(gatewayUri)
                .build();
    }

    public static OpenClawConfig of(URI gatewayUri, String authToken, Duration connectTimeout) {
        return builder()
                .gatewayUri(gatewayUri)
                .authToken(authToken)
                .connectTimeout(connectTimeout)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    public static final class Builder {
        private URI gatewayUri;
        private Duration connectTimeout;
        private String authToken;
        private String origin;
        private String clientInstanceId;
        private String deviceId;
        private String devicePrivateKeyPkcs8Base64Url;

        public Builder gatewayUri(String uri) {
            this.gatewayUri = URI.create(uri);
            return this;
        }

        public Builder gatewayUri(URI uri) {
            this.gatewayUri = Objects.requireNonNull(uri, "gatewayUri is required");
            return this;
        }

        public Builder connectTimeout(Duration timeout) {
            this.connectTimeout = timeout;
            return this;
        }

        public Builder authToken(String token) {
            this.authToken = token;
            return this;
        }

        public Builder origin(String origin) {
            this.origin = origin;
            return this;
        }

        public Builder clientInstanceId(String clientInstanceId) {
            this.clientInstanceId = clientInstanceId;
            return this;
        }

        public Builder deviceId(String deviceId) {
            this.deviceId = deviceId;
            return this;
        }

        public Builder devicePrivateKeyPkcs8Base64Url(String devicePrivateKeyPkcs8Base64Url) {
            this.devicePrivateKeyPkcs8Base64Url = devicePrivateKeyPkcs8Base64Url;
            return this;
        }

        public OpenClawConfig build() {
            return new OpenClawConfig(this);
        }
    }
}
