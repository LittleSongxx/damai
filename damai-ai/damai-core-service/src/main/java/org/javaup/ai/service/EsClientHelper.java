package org.javaup.ai.service;

import cn.hutool.core.codec.Base64;
import cn.hutool.http.ContentType;
import cn.hutool.http.HttpRequest;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class EsClientHelper {

    @Value("${DAMAI_ES_ADDR:127.0.0.1:19200}")
    private String esAddress;

    @Value("${DAMAI_ES_USERNAME:elastic}")
    private String esUsername;

    @Value("${DAMAI_ES_PASSWORD:elastic}")
    private String esPassword;

    public JSONObject execute(String path, String body, String method) {
        String url = "http://" + esAddress + path;
        HttpRequest request = switch (method) {
            case "PUT" -> HttpRequest.put(url);
            case "POST" -> HttpRequest.post(url);
            default -> HttpRequest.get(url);
        };
        request.contentType(ContentType.JSON.getValue())
                .header("Authorization", authorization())
                .body(body);
        String response = request.execute().body();
        return JSON.parseObject(response == null ? "{}" : response);
    }

    public JSONObject execute(String path, String body) {
        return execute(path, body, "GET");
    }

    public JSONObject fieldMapping(String type) {
        return new JSONObject(Map.of("type", type));
    }

    public String authorization() {
        return "Basic " + Base64.encode(esUsername + ":" + esPassword);
    }

    public String bulkPost(String bulkBody) {
        String url = "http://" + esAddress + "/_bulk";
        return cn.hutool.http.HttpRequest.post(url)
                .contentType("application/x-ndjson")
                .header("Authorization", authorization())
                .body(bulkBody)
                .execute()
                .body();
    }

    public String getEsAddress() {
        return esAddress;
    }
}
