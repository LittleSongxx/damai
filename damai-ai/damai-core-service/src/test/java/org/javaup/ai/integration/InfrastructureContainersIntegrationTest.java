package org.javaup.ai.integration;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class InfrastructureContainersIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.39");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine")
            .withExposedPorts(6379);

    @Container
    static final GenericContainer<?> QDRANT = new GenericContainer<>("qdrant/qdrant:v1.13.4")
            .withExposedPorts(6333);

    @Test
    void shouldLoadDamaiAiSchemaIntoMysqlContainer() throws Exception {
        String adminJdbcUrl = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(MySQLContainer.MYSQL_PORT) + "/mysql";
        try (Connection connection = DriverManager.getConnection(adminJdbcUrl, "root", MYSQL.getPassword())) {
            executeSqlScript(connection, Files.readString(Path.of("..", "sql", "damai_ai.sql")));
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("""
                         SELECT COUNT(*) FROM information_schema.TABLES
                         WHERE TABLE_SCHEMA = 'damai_ai'
                           AND TABLE_NAME IN (
                             'd_ai_session',
                             'd_ai_workflow_run',
                             'd_ai_workflow_step',
                             'd_ai_approval',
                             'd_ai_tool_audit',
                             'd_ai_retrieval_trace',
                             'd_ai_trace'
                           )
                         """)) {
                resultSet.next();
                assertEquals(7, resultSet.getInt(1));
            }
        }
    }

    @Test
    void shouldRespondToRedisPing() throws Exception {
        try (Socket socket = new Socket(REDIS.getHost(), REDIS.getMappedPort(6379));
             OutputStreamWriter writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            writer.write("*1\r\n$4\r\nPING\r\n");
            writer.flush();
            assertEquals("+PONG", reader.readLine());
        }
    }

    @Test
    void shouldCreateQdrantCollectionOverHttp() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String baseUrl = "http://" + QDRANT.getHost() + ":" + QDRANT.getMappedPort(6333);
        HttpRequest create = HttpRequest.newBuilder(URI.create(baseUrl + "/collections/damai-ai-test"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("""
                        {"vectors":{"size":4,"distance":"Cosine"}}
                        """))
                .build();
        HttpResponse<String> createResponse = client.send(create, HttpResponse.BodyHandlers.ofString());
        assertTrue(createResponse.statusCode() >= 200 && createResponse.statusCode() < 300);

        HttpRequest list = HttpRequest.newBuilder(URI.create(baseUrl + "/collections")).GET().build();
        HttpResponse<String> listResponse = client.send(list, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, listResponse.statusCode());
        assertTrue(listResponse.body().contains("damai-ai-test"));
    }

    private void executeSqlScript(Connection connection, String script) throws Exception {
        String normalized = Arrays.stream(script.split("\\R"))
                .filter(line -> !line.trim().startsWith("--"))
                .reduce("", (left, right) -> left + "\n" + right);
        for (String statementSql : normalized.split(";")) {
            String sql = statementSql.trim();
            if (sql.isEmpty()) {
                continue;
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }
    }
}
