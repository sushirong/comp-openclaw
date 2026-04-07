package com.example.openclaw.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
/**
 * OpenClaw 网关通用消息模型。
 */
public final class OpenClawMessage {
    // 标识当前消息类型，例如 req、res、event。
    private final String type;
    // 标识事件名称，仅 event 类型消息会使用。
    private final String event;
    // 标识请求或响应的唯一编号。
    private final String id;
    // 标识请求方法名，例如 chat.send。
    private final String method;
    // 标识响应是否成功。
    private final Boolean ok;
    // 保存请求参数、响应数据或事件负载。
    private final JsonNode payload;
    // 保存失败时返回的错误信息。
    private final JsonNode error;

    /**
     * 根据反序列化结果创建消息对象。
     */
    @JsonCreator
    public OpenClawMessage(
            @JsonProperty("type") String type,
            @JsonProperty("event") String event,
            @JsonProperty("id") String id,
            @JsonProperty("method") String method,
            @JsonProperty("ok") Boolean ok,
            @JsonProperty("payload") JsonNode payload,
            @JsonProperty("error") JsonNode error
    ) {
        this.type = type;
        this.event = event;
        this.id = id;
        this.method = method;
        this.ok = ok;
        this.payload = payload;
        this.error = error;
    }

    /**
     * @return 消息类型
     */
    public String type() {
        return type;
    }

    /**
     * @return 事件名称
     */
    public String event() {
        return event;
    }

    /**
     * @return 请求或响应 ID
     */
    public String id() {
        return id;
    }

    /**
     * @return 方法名
     */
    public String method() {
        return method;
    }

    /**
     * @return 是否成功
     */
    public Boolean ok() {
        return ok;
    }

    /**
     * @return 负载内容
     */
    public JsonNode payload() {
        return payload;
    }

    /**
     * @return 错误信息
     */
    public JsonNode error() {
        return error;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof OpenClawMessage)) {
            return false;
        }
        OpenClawMessage that = (OpenClawMessage) other;
        return Objects.equals(type, that.type)
                && Objects.equals(event, that.event)
                && Objects.equals(id, that.id)
                && Objects.equals(method, that.method)
                && Objects.equals(ok, that.ok)
                && Objects.equals(payload, that.payload)
                && Objects.equals(error, that.error);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, event, id, method, ok, payload, error);
    }

    @Override
    public String toString() {
        return "OpenClawMessage{"
                + "type='" + type + '\''
                + ", event='" + event + '\''
                + ", id='" + id + '\''
                + ", method='" + method + '\''
                + ", ok=" + ok
                + ", payload=" + payload
                + ", error=" + error
                + '}';
    }
}
