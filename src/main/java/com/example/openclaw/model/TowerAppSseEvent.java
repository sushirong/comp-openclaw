package com.example.openclaw.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 小塔 APP 兼容的 SSE 外层消息对象。
 *
 * <p>桌面对接文档要求每一条事件都使用统一的外层包装结构：
 * <pre>
 * {
 *   "sseType": "TYPE_EVENT",
 *   "eventData": "{...}",
 *   "errorMsg": ""
 * }
 * </pre>
 *
 * <p>其中 {@code eventData} 必须是已经序列化完成的 JSON 字符串，而不是直接放一个对象。
 * 这样可以与桌面端现有的解包逻辑保持一致，避免消费端对字段类型产生歧义。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class TowerAppSseEvent {
    // 固定为 TYPE_EVENT，用于标识当前是一条普通事件消息。
    private final String sseType;
    // 内层事件数据，必须是 JSON 字符串而不是对象本身。
    private final String eventData;
    // 外层错误消息字段，兼容协议保留；正常事件场景下通常为空字符串。
    private final String errorMsg;

    /**
     * 创建一条小塔 APP 兼容 SSE 外层事件。
     *
     * @param sseType 外层事件类型，当前固定为 {@code TYPE_EVENT}
     * @param eventData 内层事件的 JSON 字符串
     * @param errorMsg 外层错误信息，正常事件通常传空字符串
     */
    public TowerAppSseEvent(String sseType, String eventData, String errorMsg) {
        this.sseType = sseType;
        this.eventData = eventData;
        this.errorMsg = errorMsg;
    }

    /**
     * 返回外层 SSE 事件类型。
     */
    public String getSseType() {
        return sseType;
    }

    /**
     * 返回内层事件 JSON 字符串。
     */
    public String getEventData() {
        return eventData;
    }

    /**
     * 返回外层错误信息。
     */
    public String getErrorMsg() {
        return errorMsg;
    }

    /**
     * 提供 record 风格读取方法，兼容部分调用方使用习惯。
     */
    public String sseType() {
        return sseType;
    }

    /**
     * 提供 record 风格读取方法，兼容部分调用方使用习惯。
     */
    public String eventData() {
        return eventData;
    }

    /**
     * 提供 record 风格读取方法，兼容部分调用方使用习惯。
     */
    public String errorMsg() {
        return errorMsg;
    }

    @Override
    public String toString() {
        return "TowerAppSseEvent{"
                + "sseType='" + sseType + '\''
                + ", eventData='" + eventData + '\''
                + ", errorMsg='" + errorMsg + '\''
                + '}';
    }
}
