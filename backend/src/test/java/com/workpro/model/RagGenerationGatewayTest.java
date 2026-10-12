package com.workpro.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class RagGenerationGatewayTest {
    private HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<String> request = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private String payload;
    private RagGenerationGateway.Settings settings;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        settings = new RagGenerationGateway.Settings();
        settings.setProvider("OpenAI");
        settings.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        settings.setApiKey("test-key");
        settings.setModel("deepseek-flash");
        settings.setThinking("disabled");
        settings.setRetries(0);
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void readsAssistantTextAndSendsChatCompletionProtocol() throws Exception {
        payload = """
            {"choices":[{"message":{"role":"assistant","content":"{\\"supported\\":true,\\"answerability\\":\\"FULL\\",\\"answer\\":\\"27个岗位[E1]\\"}"}}]}
            """;
        assertEquals("{\"supported\":true,\"answerability\":\"FULL\",\"answer\":\"27个岗位[E1]\"}",
            new RagGenerationGateway(settings).complete("岗位？").content());
        var body = mapper.readTree(request.get());
        assertEquals("deepseek-flash", body.path("model").asText());
        assertEquals("disabled", body.at("/thinking/type").asText());
        assertEquals("岗位？", body.at("/messages/0/content").asText());
        assertEquals("user", body.at("/messages/0/role").asText());
        assertTrue(body.has("max_tokens"));
        assertTrue(body.has("temperature"));
        assertEquals("submit_answer", body.at("/tools/0/function/name").asText());
        assertEquals("submit_answer", body.at("/tool_choice/function/name").asText());
        assertFalse(body.has("input"));
        assertEquals("Bearer test-key", authorization.get());
    }

    @Test void readsStructuredToolCallArguments() {
        payload = "{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"name\":\"submit_answer\","
                + "\"arguments\":\"{\\\"supported\\\":true,\\\"answerability\\\":\\\"FULL\\\",\\\"answer\\\":\\\"ok[E1]\\\"}\"}}]}}]}";
        assertEquals("{\"supported\":true,\"answerability\":\"FULL\",\"answer\":\"ok[E1]\"}",
                new RagGenerationGateway(settings).complete("question").content());
    }

    @Test void rejectsIncompleteAndEmptyResponses() {
        var gateway = new RagGenerationGateway(settings);
        payload = "{\"choices\":[]}";
        assertThrows(IllegalStateException.class, () -> gateway.complete("question"));
        payload = "{\"choices\":[{\"message\":{}}]}";
        assertThrows(IllegalStateException.class, () -> gateway.complete("question"));
    }

    @Test void ragFailureDoesNotReconfigureOrOpenDialogueCircuit() {
        var original = new ModelServices.Properties();
        original.getLlm().setProvider("mock");
        original.getLlm().setModel("original-chat");
        var dialogue = new ModelGateway(new ModelServices.LlmClient(original));
        var rag = new RagGenerationGateway(settings);
        payload = "{\"choices\":[]}";
        for (int i = 0; i < 3; i++)
            assertThrows(IllegalStateException.class, () -> rag.complete("question"));
        assertTrue(dialogue.complete("hello").content().contains("hello"));
        assertEquals("original-chat", original.getLlm().getModel());
    }
}
