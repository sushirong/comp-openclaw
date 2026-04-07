package com.example.openclaw.demo;

import com.example.openclaw.client.OpenClawClient;
import com.example.openclaw.client.OpenClawConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.List;

/**
 * 控制台对话示例入口。
 */
public final class App {
    private static final String DEFAULT_GATEWAY = "ws://127.0.0.1:18789";
    private static final String DEFAULT_SESSION_KEY = "main";
    private static final String DEFAULT_RESPONSE_FORMAT = "text";

    private App() {
    }

    /**
     * 启动控制台示例并与 OpenClaw 网关建立交互。
     *
     * @param args 启动参数
     * @throws Exception 启动或交互过程中抛出的异常
     */
    public static void main(String[] args) throws Exception {
        String gateway = System.getProperty("openclaw.gateway", DEFAULT_GATEWAY);
        String tokenFromProp = System.getProperty("openclaw.token", "").trim();
        String tokenFromEnv = System.getenv("OPENCLAW_GATEWAY_TOKEN");
        String token = !tokenFromProp.isEmpty() ? tokenFromProp : (tokenFromEnv == null ? "" : tokenFromEnv.trim());
        String sessionKey = System.getProperty("openclaw.sessionKey", DEFAULT_SESSION_KEY);
        Duration requestTimeout = Duration.ofSeconds(
                Long.parseLong(System.getProperty("openclaw.timeoutSeconds", "30"))
        );
        Duration streamTimeout = Duration.ofSeconds(
                Long.parseLong(System.getProperty("openclaw.streamTimeoutSeconds", "180"))
        );

        ResponseFormat responseFormat;
        try {
            responseFormat = ResponseFormat.parse(
                    System.getProperty("openclaw.responseFormat", DEFAULT_RESPONSE_FORMAT)
            );
        } catch (IllegalArgumentException ex) {
            System.err.println("[错误] openclaw.responseFormat 配置非法：" + ex.getMessage());
            System.err.println("可选值：text、event");
            return;
        }

        if (token.isEmpty()) {
            System.err.println("[错误] 缺少网关 Token。");
            System.err.println("请使用以下任一方式提供：");
            System.err.println("1) JVM 参数：-Dopenclaw.token=<你的token>");
            System.err.println("2) 环境变量：OPENCLAW_GATEWAY_TOKEN=<你的token>");
            return;
        }

        OpenClawConfig config = OpenClawConfig.builder()
                .gatewayUri(gateway)
                .connectTimeout(Duration.ofSeconds(10))
                .authToken(token)
                .build();

        try (OpenClawClient client = new OpenClawClient(config);
             BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            client.connect().join();

            System.out.println("已连接 OpenClaw：" + gateway);
            System.out.println("响应格式：" + responseFormat.value());
            System.out.println("交互式对话已启动，输入内容后回车发送。");
            System.out.println("输入 'exit' 或 'quit' 可退出。");

            while (true) {
                System.out.print("you> ");
                String line = reader.readLine();
                if (line == null) {
                    System.out.println("\n输入流已关闭，程序退出。");
                    break;
                }

                String text = line.trim();
                if (text.isEmpty()) {
                    continue;
                }
                if ("exit".equalsIgnoreCase(text) || "quit".equalsIgnoreCase(text)) {
                    System.out.println("正在关闭会话。");
                    break;
                }

                try {
                    printResponse(
                            client,
                            responseFormat,
                            sessionKey,
                            text,
                            requestTimeout,
                            streamTimeout
                    );
                } catch (Exception ex) {
                    String msg = ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage();
                    System.err.println("[错误] 发送失败：" + msg);
                }
            }
        }
    }

    private static void printResponse(
            OpenClawClient client,
            ResponseFormat responseFormat,
            String sessionKey,
            String message,
            Duration requestTimeout,
            Duration streamTimeout
    ) {
        if (responseFormat == ResponseFormat.EVENT) {
            printEventResponse(client, sessionKey, message, requestTimeout, streamTimeout);
            return;
        }
        printTextResponse(client, sessionKey, message, requestTimeout, streamTimeout);
    }

    private static void printTextResponse(
            OpenClawClient client,
            String sessionKey,
            String message,
            Duration requestTimeout,
            Duration streamTimeout
    ) {
        String reply = client.sendChatText(sessionKey, message, requestTimeout, streamTimeout).join();
        System.out.println("assistant> " + reply);
    }

    private static void printEventResponse(
            OpenClawClient client,
            String sessionKey,
            String message,
            Duration requestTimeout,
            Duration streamTimeout
    ) {
        List<String> events = client.sendChatRawEvents(sessionKey, message, requestTimeout, streamTimeout).join();
        if (events.isEmpty()) {
            System.out.println("event> [empty]");
            return;
        }
        for (String event : events) {
            System.out.println("event> " + event);
        }
    }

    private enum ResponseFormat {
        TEXT("text"),
        EVENT("event");

        private final String value;

        ResponseFormat(String value) {
            this.value = value;
        }

        private String value() {
            return value;
        }

        private static ResponseFormat parse(String rawValue) {
            String normalized = rawValue == null ? "" : rawValue.trim().toLowerCase();
            for (ResponseFormat candidate : values()) {
                if (candidate.value.equals(normalized)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException(rawValue);
        }
    }
}
