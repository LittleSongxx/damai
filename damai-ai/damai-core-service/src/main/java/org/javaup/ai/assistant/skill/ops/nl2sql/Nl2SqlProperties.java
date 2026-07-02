package org.javaup.ai.assistant.skill.ops.nl2sql;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "damai.ai.nl2sql")
public class Nl2SqlProperties {

    private boolean enabled = false;

    private int maxRows = 100;

    private int schemaTopK = 5;

    private int queryTimeoutMs = 5000;

    private int repairAttempts = 1;

    private double minSqlConfidence = 0.5D;

    private String schemaCollection = "damai_ai_nl2sql_schema";

    private CostGuard costGuard = new CostGuard();

    private boolean allowJoins = false;

    private boolean allowSubqueries = false;

    private boolean allowCte = false;

    private boolean allowSetOperations = false;

    private boolean allowWindowFunctions = false;

    private List<String> allowedFunctions = new ArrayList<>(List.of(
            "count", "sum", "avg", "min", "max",
            "date", "current_date", "current_time", "current_timestamp",
            "now", "date_sub", "date_add", "hour", "day", "month", "year",
            "coalesce", "ifnull", "round"
    ));

    private DataSource datasource = new DataSource();

    private List<String> sensitiveColumns = new ArrayList<>(List.of(
            "mobile", "phone", "email", "password", "id_number", "idcard", "identity",
            "salt", "access_token", "refresh_token", "secret", "secret_key", "signsecretkey", "aeskey", "aes_key",
            "private_key", "access_key", "credential"
    ));

    private List<String> blockedFunctions = new ArrayList<>(List.of(
            "sleep", "benchmark", "load_file", "into outfile", "into dumpfile", "uuid_short"
    ));

    @Data
    public static class DataSource {

        private String driverClassName = "com.mysql.cj.jdbc.Driver";

        private String url = "";

        private String username = "";

        private String password = "";
    }

    @Data
    public static class CostGuard {

        private boolean enabled = true;

        private boolean requireReadOnlyConnection = true;

        private int explainTimeoutMs = 2000;

        private long maxEstimatedRows = 10000;

        private double maxQueryCost = 100000D;

        private boolean failClosedOnExplainError = true;
    }

    @Data
    public static class Table {

        private String name;

        private String description;

        private boolean allowed = true;

        private List<String> aliases = new ArrayList<>();

        private List<Column> columns = new ArrayList<>();
    }

    @Data
    public static class Column {

        private String name;

        private String type;

        private String description;

        private boolean sensitive = false;
    }

    @Data
    public static class Term {

        private String name;

        private String description;
    }

    @Data
    public static class Example {

        private String question;

        private String sql;
    }

}
