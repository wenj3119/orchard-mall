package com.orchard.mall;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties={
    "spring.datasource.url=jdbc:h2:mem:shippingruletest;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.profiles.active=test", "app.dev-consumer-login-enabled=true", "app.admin-init-password=long-test-password-123",
    "storage.access-key=test", "storage.secret-key=test"
})
class ShippingRuleValidationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired RegionCatalog regions;
    private String rule(String code, int firstWeight, long firstFee) {
        return "{\"regionCode\":\""+code+"\",\"blocked\":false,\"firstWeightG\":"+firstWeight+",\"firstFeeFen\":"+firstFee+",\"stepWeightG\":500,\"stepFeeFen\":100}";
    }
    @Test void regionLevelsDuplicateAndAmountValidation() throws Exception {
        assertThat(regions.ruleName("110100")).isEqualTo("北京市（市级）");
        assertThat(regions.ruleName("110101")).isEqualTo("北京市东城区");
        String admin="Bearer "+mapper.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"admin\",\"password\":\"long-test-password-123\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
        long template=mapper.readTree(mvc.perform(post("/api/admin/shipping-templates").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"地区校验\",\"enabled\":true}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        for (String code : new String[]{"000000","610000","610600","610602"})
            mvc.perform(post("/api/admin/shipping-templates/"+template+"/rules").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON)
                .content(rule(code,1000,0))).andExpect(status().isOk());
        mvc.perform(get("/api/admin/shipping-templates/"+template).header("Authorization",admin))
            .andExpect(status().isOk()).andExpect(jsonPath("$.rules[0].regionName").value("全国默认"));
        mvc.perform(post("/api/admin/shipping-templates/"+template+"/rules").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON)
            .content(rule("610600",1000,0))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("已有此地区规则")));
        for (String code : new String[]{"042200","710000","999999","610601"})
            mvc.perform(post("/api/admin/shipping-templates/"+template+"/rules").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON)
                .content(rule(code,1000,0))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/shipping-templates/"+template+"/rules").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON)
            .content(rule("110000",0,0))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fields[0].field").value("firstWeightG"))
            .andExpect(jsonPath("$.fields[0].message").value("首重必须是正整数克"));
        mvc.perform(post("/api/admin/shipping-templates/"+template+"/rules").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON)
            .content(rule("110000",1000,-1))).andExpect(status().isBadRequest());
    }
}
