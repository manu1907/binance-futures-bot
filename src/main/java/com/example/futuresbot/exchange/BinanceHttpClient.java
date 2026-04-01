package com.example.futuresbot.exchange;

import com.example.futuresbot.config.AppConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

public final class BinanceHttpClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MAINNET_REST_BASE_URL = "https://fapi.binance.com";
    private static final String TESTNET_REST_BASE_URL = "https://demo-fapi.binance.com";

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final BinanceSigner signer;

    public BinanceHttpClient(AppConfig.ExchangeConfig config) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.baseUrl = config.useTestnet() ? TESTNET_REST_BASE_URL : normalize(config.baseUrl(), MAINNET_REST_BASE_URL);
        this.apiKey = config.apiKey();
        this.signer = new BinanceSigner(config.apiSecret());
    }

    public JsonNode getPublic(String path, Map<String, String> params) {
        String suffix = params == null || params.isEmpty() ? "" : "?" + toQueryString(params);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path + suffix))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        return send(request);
    }

    public JsonNode getSigned(String path, Map<String, String> params) {
        return signedRequest("GET", path, params);
    }

    public JsonNode postSigned(String path, Map<String, String> params) {
        return signedRequest("POST", path, params);
    }

    public JsonNode deleteSigned(String path, Map<String, String> params) {
        return signedRequest("DELETE", path, params);
    }

    public JsonNode putApiKeyOnly(String path) {
        return apiKeyRequest("PUT", path);
    }

    public JsonNode postApiKeyOnly(String path) {
        return apiKeyRequest("POST", path);
    }

    public JsonNode deleteApiKeyOnly(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15))
                .header("X-MBX-APIKEY", apiKey)
                .DELETE()
                .build();
        return send(request);
    }

    private JsonNode apiKeyRequest(String method, String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15))
                .header("X-MBX-APIKEY", apiKey);

        HttpRequest request = switch (method) {
            case "POST" -> builder.POST(HttpRequest.BodyPublishers.noBody()).build();
            case "PUT" -> builder.PUT(HttpRequest.BodyPublishers.noBody()).build();
            default -> throw new IllegalArgumentException("Unsupported method: " + method);
        };

        return send(request);
    }

    private JsonNode signedRequest(String method, String path, Map<String, String> params) {
        Map<String, String> queryParams = new LinkedHashMap<>(params);
        queryParams.putIfAbsent("recvWindow", "5000");
        queryParams.put("timestamp", Long.toString(System.currentTimeMillis()));

        String queryString = toQueryString(queryParams);
        String signature = signer.sign(queryString);
        URI uri = URI.create(baseUrl + path + "?" + queryString + "&signature=" + signature);

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("X-MBX-APIKEY", apiKey);

        HttpRequest request = switch (method) {
            case "GET" -> builder.GET().build();
            case "POST" -> builder.POST(HttpRequest.BodyPublishers.noBody()).build();
            case "DELETE" -> builder.DELETE().build();
            default -> throw new IllegalArgumentException("Unsupported method: " + method);
        };

        return send(request);
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BinanceApiException(response.statusCode(), response.body());
            }
            return JSON.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse Binance response", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling Binance", e);
        }
    }

    private String toQueryString(Map<String, String> params) {
        StringJoiner joiner = new StringJoiner("&");
        params.forEach((key, value) -> joiner.add(urlEncode(key) + "=" + urlEncode(value)));
        return joiner.toString();
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String normalize(String configured, String fallback) {
        return configured == null || configured.isBlank() ? fallback : configured;
    }
}