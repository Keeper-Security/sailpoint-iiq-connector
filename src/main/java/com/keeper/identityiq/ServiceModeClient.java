package com.keeper.identityiq;

import static com.keeper.identityiq.Values.escapeJson;
import static com.keeper.identityiq.Values.firstNonBlank;
import static com.keeper.identityiq.Values.isBlank;
import static com.keeper.identityiq.Values.nvl;
import static com.keeper.identityiq.Values.str;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import sailpoint.connector.ConnectorException;

/**
 * Keeper Commander Service Mode API v2 client: runs commands and caches
 * enterprise-down / sync-down.
 */
final class ServiceModeClient {

    /** Connection settings, read from the application on every use. */
    interface Settings {
        String serviceUrl() throws ConnectorException;

        String apiKey() throws ConnectorException;

        int pollTimeoutSeconds();

        int syncCacheTtlSeconds();
    }

    private static final long INITIAL_POLL_DELAY_MS = 500L;
    private static final long MAX_POLL_DELAY_MS = 5000L;

    /**
     * Last successful enterprise-down / sync-down per Service Mode URL. IIQ
     * creates a connector instance per operation, so the cache is static to
     * avoid re-syncing on every getObject during a provisioning flow.
     */
    private static final Map<String, Long> LAST_SYNC = new ConcurrentHashMap<String, Long>();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final Settings settings;

    ServiceModeClient(Settings settings) {
        this.settings = settings;
    }

    /**
     * Submits a command (POST /api/v2/executecommand-async) and polls
     * GET /api/v2/result/{request_id} with backoff until it completes.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> runCommand(String command) throws ConnectorException {
        String commandName = command.split(" ", 2)[0];
        HttpResponse<String> submitted = send("POST", "/api/v2/executecommand-async",
                "{\"command\":\"" + escapeJson(command) + "\"}");
        if (submitted.statusCode() != 202) {
            if (submitted.statusCode() >= 200 && submitted.statusCode() < 300) {
                throw new ConnectorException("unexpected Keeper API response status " + submitted.statusCode());
            }
            throw apiError(submitted);
        }
        Object parsed = JsonParser.parse(submitted.body());
        String requestId = parsed instanceof Map ? str(((Map<?, ?>) parsed).get("request_id")) : null;
        if (isBlank(requestId)) {
            throw new ConnectorException("Keeper API accepted request but did not return request_id");
        }

        long timeoutMs = settings.pollTimeoutSeconds() * 1000L;
        long deadline = System.currentTimeMillis() + timeoutMs;
        long delay = INITIAL_POLL_DELAY_MS;
        boolean first = true;
        while (System.currentTimeMillis() < deadline) {
            if (!first) {
                sleep(delay);
                delay = Math.min(delay * 2, MAX_POLL_DELAY_MS);
            }
            first = false;

            HttpResponse<String> response = send("GET", "/api/v2/result/" + requestId, null);
            if (response.statusCode() == 202) {
                continue;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw apiError(response);
            }
            Object result = JsonParser.parse(response.body());
            if (!(result instanceof Map)) {
                throw new ConnectorException("Keeper returned an invalid result for \"" + commandName + "\".");
            }
            Map<String, Object> map = (Map<String, Object>) result;
            if (!"success".equalsIgnoreCase(str(map.get("status")))) {
                String message = firstNonBlank(str(map.get("message")), str(map.get("error")),
                        "Keeper API request failed");
                throw new ConnectorException("Keeper command \"" + commandName + "\" failed: " + message);
            }
            return map;
        }
        throw new ConnectorException("Keeper API poll timed out after " + (timeoutMs / 1000)
                + " seconds for \"" + commandName + "\".");
    }

    // ---------------------------------------------------------------------
    // Sync cache (enterprise-down / sync-down)
    // ---------------------------------------------------------------------

    void syncEnterprise() throws ConnectorException {
        runCachedSync("enterprise", "enterprise-down -f");
    }

    void syncVault() throws ConnectorException {
        runCachedSync("vault", "sync-down -f");
    }

    /** Runs a forced sync at most once per syncCacheTtlSeconds per Service Mode URL. */
    private void runCachedSync(String kind, String command) throws ConnectorException {
        String key = settings.serviceUrl() + "|" + kind;
        long ttlMs = settings.syncCacheTtlSeconds() * 1000L;
        synchronized (key.intern()) {
            Long last = LAST_SYNC.get(key);
            if (last != null && System.currentTimeMillis() - last < ttlMs) {
                return;
            }
            runCommand(command);
            LAST_SYNC.put(key, System.currentTimeMillis());
        }
    }

    void invalidateEnterpriseSync() throws ConnectorException {
        LAST_SYNC.remove(settings.serviceUrl() + "|enterprise");
    }

    void invalidateVaultSync() throws ConnectorException {
        LAST_SYNC.remove(settings.serviceUrl() + "|vault");
    }

    // ---------------------------------------------------------------------
    // HTTP
    // ---------------------------------------------------------------------

    /** Maps a non-2xx Service Mode response to a connector error. */
    private ConnectorException apiError(HttpResponse<String> response) {
        int status = response.statusCode();
        String detail = nvl(response.body());
        try {
            Object parsed = JsonParser.parse(response.body());
            if (parsed instanceof Map) {
                detail = firstNonBlank(str(((Map<?, ?>) parsed).get("message")),
                        str(((Map<?, ?>) parsed).get("error")), detail);
            }
        } catch (ConnectorException ignored) {
            // keep the raw body
        }
        switch (status) {
            case 503:
                return new ConnectorException("queue is full (503): service unavailable, please try again later");
            case 429:
                return new ConnectorException("rate limit exceeded (429): too many requests, please retry after some time");
            case 404:
                return new ConnectorException("request id not found (404)");
            case 500:
                return new ConnectorException(detail.toLowerCase().contains("not found")
                        ? detail : "internal server error (500): " + detail);
            case 400:
                return new ConnectorException("bad request (400): " + detail);
            default:
                return new ConnectorException("keeper Security API request failed with status " + status + ": " + detail);
        }
    }

    private HttpResponse<String> send(String method, String path, String body) throws ConnectorException {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(settings.serviceUrl() + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .header("api-key", settings.apiKey());
            if ("POST".equals(method)) {
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
            } else {
                builder.GET();
            }
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConnectorException("Keeper Service Mode request was interrupted.", e);
        } catch (Exception e) {
            throw new ConnectorException("Unable to call Keeper Service Mode. Check the URL, "
                    + "network, TLS configuration, and API key.", e);
        }
    }

    private void sleep(long ms) throws ConnectorException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConnectorException("Interrupted while waiting for a Keeper command.", e);
        }
    }
}
