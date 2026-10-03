package com.orchard.mall;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:wechat-login;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.profiles.active=test",
    "app.admin-init-password=long-test-password-123", "storage.access-key=test", "storage.secret-key=test",
    "app.order-expiry-scan-ms=3600000"
})
class WechatLoginFlowTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper mapper;
    @Autowired WechatLoginController controller;
    @MockitoBean WechatCodeExchange exchange;

    @BeforeEach void clean() {
        db.update("DELETE FROM customer_address");
        db.update("DELETE FROM consumer_session");
        db.update("DELETE FROM consumer_identity");
        db.update("DELETE FROM consumer_account");
        when(exchange.appId()).thenReturn("wx-test-app");
        when(exchange.openId(anyString())).thenAnswer(call -> call.getArgument(0));
    }

    private JsonNode login(String code) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/wechat/auth/login")
            .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test void verifiedIdentityReusesConsumerAndSessionsOwnOnlyTheirData() throws Exception {
        JsonNode first = login("openid-one");
        JsonNode again = login("openid-one");
        JsonNode other = login("openid-two");
        long owner = first.get("consumerId").asLong();
        assertThat(again.get("consumerId").asLong()).isEqualTo(owner);
        assertThat(other.get("consumerId").asLong()).isNotEqualTo(owner);
        assertThat(first.get("token").asText()).isNotEqualTo(again.get("token").asText());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM consumer_identity WHERE platform='WECHAT' AND app_id='wx-test-app'", Integer.class)).isEqualTo(2);

        String bearer = "Bearer " + first.get("token").asText();
        mvc.perform(get("/api/customer/me").header("Authorization", bearer))
            .andExpect(status().isOk()).andExpect(jsonPath("$.consumerId").value(owner));
        mvc.perform(get("/api/customer/me")).andExpect(status().isUnauthorized());
        String address = "{\"recipient\":\"测试用户\",\"mobile\":\"13800000001\",\"provinceCode\":\"610000\",\"provinceName\":\"陕西省\",\"cityCode\":\"610100\",\"cityName\":\"西安市\",\"districtCode\":\"610102\",\"districtName\":\"新城区\",\"detail\":\"测试路1号\"}";
        mvc.perform(post("/api/customer/addresses").header("Authorization", bearer)
            .contentType(MediaType.APPLICATION_JSON).content(address)).andExpect(status().isOk());
        mvc.perform(get("/api/customer/addresses").header("Authorization", "Bearer " + again.get("token").asText()))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].recipient").value("测试用户"));
        mvc.perform(get("/api/customer/addresses").header("Authorization", "Bearer " + other.get("token").asText()))
            .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test void clientSuppliedIdentityAndDevelopmentIdentityCannotChooseWechatConsumer() throws Exception {
        db.update("INSERT INTO consumer_account (created_at) VALUES (CURRENT_TIMESTAMP)");
        long devConsumer = db.queryForObject("SELECT MAX(id) FROM consumer_account", Long.class);
        db.update("INSERT INTO consumer_identity (consumer_id,platform,app_id,platform_user_id) VALUES (?,?,?,?)",
            devConsumer, "WECHAT", "DEV", "openid-real");
        when(exchange.openId("valid-code")).thenReturn("openid-real");
        JsonNode response = mapper.readTree(mvc.perform(post("/api/wechat/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"valid-code\",\"externalUserId\":\"attacker-chosen\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(response.get("consumerId").asLong()).isNotEqualTo(devConsumer);
        assertThat(db.queryForObject("SELECT platform_user_id FROM consumer_identity WHERE consumer_id=?", String.class,
            response.get("consumerId").asLong())).isEqualTo("openid-real");
    }

    @Test void rejectedCodeCannotCreateSessionOrConsumer() throws Exception {
        when(exchange.openId("bad-code")).thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "微信登录凭证已失效，请重试"));
        mvc.perform(post("/api/wechat/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"bad-code\"}")).andExpect(status().isBadRequest());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM consumer_account", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM consumer_session", Integer.class)).isZero();
    }

    @Test void concurrentFirstLoginCreatesOneConsumer() throws Exception {
        int count = 6;
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(count);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<Long>>();
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return ((Number) controller.login(new WechatLoginController.Login("same-openid")).get("consumerId")).longValue();
                }));
            }
            start.countDown();
            long first = futures.getFirst().get(15, TimeUnit.SECONDS);
            for (var future : futures) assertThat(future.get(15, TimeUnit.SECONDS)).isEqualTo(first);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM consumer_account", Integer.class)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM consumer_session", Integer.class)).isEqualTo(count);
        } finally {
            pool.shutdownNow();
        }
    }
}
