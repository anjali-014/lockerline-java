package com.lockerline;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Start-up seeds once - the app started twice on the same database still has 48 doors, not 96. */
class SeedTwiceTest {

    @TempDir
    Path dir;

    @Test
    void appStartsTwiceAndReadsWall() throws Exception {
        // Create a persistent file path inside your JUnit temporary directory
        String dbPath = dir.resolve("lockerDB").toAbsolutePath().toString();
        String jdbcUrl = "jdbc:h2:file:" + dbPath + ";DB_CLOSE_DELAY=-1";

        // --- FIRST STARTUP ---
        ConfigurableApplicationContext context1 = start(jdbcUrl);
        context1.close(); // Completely shut down to close file locks

        // --- SECOND STARTUP ---
        ConfigurableApplicationContext context2 = start(jdbcUrl);

        try {
            // Get the random port from the second active running context
            String port = context2.getEnvironment().getProperty("local.server.port");
            String targetUri = "http://localhost:" + port + "/towers/A/doors";

            // Execute the explicit HTTP request tracing scanner requirement
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(targetUri))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // Assertions
            assertThat(response.statusCode()).isEqualTo(200);

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(response.body());

            // Validate against your app payload structure
            assertThat(root.get("tower").asText()).isEqualTo("A");
            assertThat(root.get("free").asInt()).isEqualTo(48);
            assertThat(root.get("doors").size()).isEqualTo(48);

        } finally {
            // Guarantee resource cleanup
            context2.close();
        }
    }

    private static ConfigurableApplicationContext start(String databaseUrl) {
        return new SpringApplicationBuilder(LockerLineApplication.class)
                .properties("spring.datasource.url=" + databaseUrl, "server.port=0")
                .run();
    }
}
