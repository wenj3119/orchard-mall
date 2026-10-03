package com.orchard.mall;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WechatCodeExchangeTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void acceptsOnlyVerifiedOpenIdAndNeverReturnsSessionKey() throws Exception {
        assertThat(WechatCodeExchange.validatedOpenId(mapper.readTree(
            "{\"openid\":\"openid-one\",\"session_key\":\"server-only\"}"))).isEqualTo("openid-one");
        assertThatThrownBy(() -> WechatCodeExchange.validatedOpenId(mapper.readTree("{\"session_key\":\"server-only\"}")))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test void rejectsInvalidAndReusedCodesWithoutExposingProviderMessage() throws Exception {
        for (int code : new int[]{40029, 40163}) {
            assertThatThrownBy(() -> WechatCodeExchange.validatedOpenId(mapper.readTree(
                "{\"errcode\":" + code + ",\"errmsg\":\"secret-provider-detail\"}")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageNotContaining("secret-provider-detail");
        }
    }

    @Test void missingCredentialsFailClosed() {
        var exchange = new WechatCodeExchange("", "", mapper);
        assertThatThrownBy(() -> exchange.openId("unused-code"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
