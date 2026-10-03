package com.orchard.mall;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public class WechatCodeExchange {
    private final String appId;
    private final String appSecret;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public WechatCodeExchange(@Value("${app.wechat-miniapp.app-id:}") String appId,
                              @Value("${app.wechat-miniapp.app-secret:}") String appSecret,
                              ObjectMapper mapper) {
        this.appId = appId;
        this.appSecret = appSecret;
        this.mapper = mapper;
    }

    public String appId() { return appId; }

    public String openId(String code) {
        if (!appId.matches("wx[0-9a-fA-F]{16}") || appSecret.isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "微信登录尚未配置");
        String query = "appid=" + encode(appId) + "&secret=" + encode(appSecret)
            + "&js_code=" + encode(code) + "&grant_type=authorization_code";
        try {
            var request = HttpRequest.newBuilder(URI.create("https://api.weixin.qq.com/sns/jscode2session?" + query))
                .timeout(Duration.ofSeconds(5)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200)
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "微信登录服务暂不可用");
            return validatedOpenId(mapper.readTree(response.body()));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "微信登录服务暂不可用");
        } catch (Exception e) {
            // Do not log the request URI: it contains the AppSecret and one-time code.
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "微信登录服务暂不可用");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String validatedOpenId(JsonNode body) {
        int error = body.path("errcode").asInt(0);
        if (error == 40029 || error == 40163)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "微信登录凭证已失效，请重试");
        if (error != 0)
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "微信登录服务暂不可用");
        String openId = body.path("openid").asText("");
        if (openId.isBlank() || openId.length() > 180)
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "微信登录响应无效");
        return openId;
    }
}
