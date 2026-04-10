package com.example.openclaw.demo;

import com.example.openclaw.client.OpenClawClient;
import com.example.openclaw.model.OpenClawMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;

public class Test {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    public static void main(String[] args) {
        /*try (OpenClawClient client = OpenClawClient
                .init("ws://127.0.0.1:18789", "59d90cb3433df256033acff0b9d37a2f2e55aaa7cdab0ff6c9191421900fd8a5")
                .join()) {
            client.streamChatRawEvents(
                    "现在有哪些定时任务？",
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
        }*/

        try (OpenClawClient client = OpenClawClient
                .init("ws://127.0.0.1:18789", "59d90cb3433df256033acff0b9d37a2f2e55aaa7cdab0ff6c9191421900fd8a5")
                .join()) {
            /*OpenClawMessage createResponse = client.createScheduleJob(
                    "日报总结任务",
                    "0 9 * * *",
                    "请按日报模板总结今天的处理结果"
            ).join();

            System.out.println("create.ok = " + createResponse.ok());
            System.out.println("create.payload = " + createResponse.payload());
            System.out.println("create.error = " + createResponse.error());*/

            /*ObjectNode listParams = OBJECT_MAPPER.createObjectNode();
            listParams.put("includeDisabled", true);
            listParams.put("limit", 50);
            listParams.put("offset", 0);
            listParams.put("enabled", "all");
            listParams.put("sortBy", "nextRunAtMs");
            listParams.put("sortDir", "asc");

            OpenClawMessage listResponse = client.listScheduleJobs(listParams, Duration.ofSeconds(30)).join();
            System.out.println("list.ok = " + listResponse.ok());
            System.out.println("list.payload = " + listResponse.payload());
            System.out.println("list.error = " + listResponse.error());*/
            OpenClawMessage response = client.deleteScheduleJob("e683e63e-641f-474e-815c-5c3ee38b5113").join();
            System.out.println("ok = " + response.ok());
            System.out.println("payload = " + response.payload());
            System.out.println("error = " + response.error());
        }
    }
}
