package io.infrahack.moviewatchlistdb.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Runtime configuration, resolved from (in priority order) real environment variables, then a local
 * {@code .env} file, then built-in defaults. Real env wins so containers/CI can override without a file.
 *
 * <p>Secrets (the DB password) are read but never logged or put in {@code toString}.
 */
public final class AppConfig {

    private final Map<String, String> dotenv;

    private AppConfig(Map<String, String> dotenv) {
        this.dotenv = dotenv;
    }

    public static AppConfig load() {
        return new AppConfig(readDotenv(Path.of(".env")));
    }

    public Optional<String> dbUrl() {
        return value("DB_URL");
    }

    /**
     * Use Postgres only when a real URL is configured. A URL still containing a template placeholder
     * (an unedited copy of {@code .env.example}) is treated as unset, so the app safely stays in-memory.
     */
    public boolean usePostgres() {
        return dbUrl().filter(url -> !url.isBlank() && !url.contains("<")).isPresent();
    }

    public String dbUser() {
        return value("DB_USER").orElse("postgres");
    }

    public String dbPassword() {
        return value("DB_PASSWORD").orElse("");
    }

    public int dbPoolMax() {
        return intValue("DB_POOL_MAX", 10);
    }

    public int serverPort() {
        return intValue("SERVER_PORT", 8080);
    }

    private Optional<String> value(String key) {
        String env = System.getenv(key);
        if (env != null && !env.isBlank()) {
            return Optional.of(env);
        }
        String fromFile = dotenv.get(key);
        return (fromFile != null && !fromFile.isBlank()) ? Optional.of(fromFile) : Optional.empty();
    }

    private int intValue(String key, int defaultValue) {
        return value(key).map(Integer::parseInt).orElse(defaultValue);
    }

    private static Map<String, String> readDotenv(Path path) {
        if (!Files.exists(path)) {
            return Map.of();
        }
        Map<String, String> parsed = new HashMap<>();
        try {
            for (String raw : Files.readAllLines(path)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).strip();
                String val = stripQuotes(line.substring(eq + 1).strip());
                parsed.put(key, val);
            }
        } catch (IOException e) {
            return Map.of(); // absent/unreadable .env just means "use env vars + defaults"
        }
        return parsed;
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
