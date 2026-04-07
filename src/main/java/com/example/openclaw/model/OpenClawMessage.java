package com.example.openclaw.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

// 忽略协议中当前模型未声明的字段，避免扩展字段导致反序列化失败。
@JsonIgnoreProperties(ignoreUnknown = true)
// 仅在字段存在值时序列化输出，减少无效空字段。
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenClawMessage(
        // 标识当前消息属于请求、响应或事件。
        String type,
        // 标识事件名称，仅 event 类型消息会使用该字段。
        String event,
        // 标识请求或响应的唯一编号，用于匹配一次调用链路。
        String id,
        // 标识请求调用的方法名，例如 chat.send 或 connect。
        String method,
        // 标识响应是否成功，仅响应类型消息会使用该字段。
        Boolean ok,
        // 保存请求参数、响应数据或事件数据的主体内容。
        JsonNode payload,
        // 保存响应失败时返回的错误信息。
        JsonNode error
) {
}
