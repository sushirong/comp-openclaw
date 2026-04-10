package com.example.openclaw.demo;

import com.example.openclaw.client.OpenClawClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class Test {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    public static void main(String[] args) {
        try (OpenClawClient client = OpenClawClient
                .init("ws://127.0.0.1:18789", "59d90cb3433df256033acff0b9d37a2f2e55aaa7cdab0ff6c9191421900fd8a5")
                .join()) {
            /*List<String> events = client.sendChatRawEvents("北京现在的天气？").join();
            System.out.println("event count = " + events.size());
            for (String event : events) {
                System.out.println(event);
            }*/
            client.streamChatRawEvents(
                    "北京现在的天气？",
                    OpenClawClient.RawEventMappingType.TOWER_APP,
                    event -> {
                        try {
                            System.out.println(OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(event));
                        } catch (JsonProcessingException ex) {
                            throw new RuntimeException(ex);
                        }
                    }
            ).join();
        } catch (RuntimeException e) {
            throw new RuntimeException(e);
        }
    }
}
