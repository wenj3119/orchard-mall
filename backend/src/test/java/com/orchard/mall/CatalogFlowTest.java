package com.orchard.mall;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.http.MediaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties={
    "spring.datasource.url=jdbc:h2:mem:malltest;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.profiles.active=test",
    "app.dev-consumer-login-enabled=true",
    "app.admin-init-password=long-test-password-123",
    "storage.access-key=test",
    "storage.secret-key=test"
})
class CatalogFlowTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper mapper;
    @Test void securedPublishReadAndUnpublish() throws Exception {
        mvc.perform(post("/api/admin/categories").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"苹果\",\"sortOrder\":1,\"enabled\":true}"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"admin\",\"password\":\"wrong\"}")).andExpect(status().isUnauthorized());
        String body=mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"admin\",\"password\":\"long-test-password-123\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String bearer="Bearer "+mapper.readTree(body).get("token").asText();
        int category=mapper.readTree(mvc.perform(post("/api/admin/categories").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"苹果\",\"sortOrder\":1,\"enabled\":true}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asInt();
        int supplier=mapper.readTree(mvc.perform(post("/api/admin/suppliers").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"农户\",\"contactName\":\"张三\",\"contactPhone\":\"13800000000\",\"enabled\":true}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asInt();
        int origin=mapper.readTree(mvc.perform(post("/api/admin/origins").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON)
            .content("{\"supplierId\":"+supplier+",\"label\":\"果园\",\"provinceCode\":\"610000\",\"province\":\"陕西\",\"cityCode\":\"610600\",\"city\":\"延安\",\"districtCode\":\"610602\",\"district\":\"宝塔区\",\"address\":\"村一号\",\"contactName\":\"张三\",\"contactPhone\":\"13800000000\",\"isDefault\":true,\"enabled\":true}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asInt();
        int template=mapper.readTree(mvc.perform(post("/api/admin/shipping-templates").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"测试模板\",\"freeThresholdFen\":null,\"enabled\":true}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asInt();
        int product=mapper.readTree(mvc.perform(post("/api/admin/products").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON)
            .content("{\"categoryId\":"+category+",\"title\":\"苹果5斤装\",\"description\":\"产地直发\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asInt();
        mvc.perform(put("/api/admin/products/"+product+"/publication").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON)
            .content("{\"published\":true}")).andExpect(status().isBadRequest());
        int sku=mapper.readTree(mvc.perform(post("/api/admin/products/"+product+"/skus").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"APPLE-5J-TEST\",\"specJson\":\"{\\\"包装\\\":\\\"5斤装\\\"}\",\"retailPriceFen\":3990,\"active\":true}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asInt();
        mvc.perform(post("/api/admin/skus/"+sku+"/supplies").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON)
            .content("{\"supplierId\":"+supplier+",\"originId\":"+origin+",\"shippingTemplateId\":"+template+",\"supplyPriceFen\":2500,\"isDefault\":true}")).andExpect(status().isOk());
        db.update("INSERT INTO media_object (object_key,content_type) VALUES ('test-image','image/png')");
        int media=db.queryForObject("SELECT id FROM media_object WHERE object_key='test-image'",Integer.class);
        mvc.perform(put("/api/admin/products/"+product+"/images").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON)
            .content("["+media+"]")).andExpect(status().isOk());
        mvc.perform(put("/api/admin/products/"+product+"/publication").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON)
            .content("{\"published\":true}")).andExpect(status().isOk());
        mvc.perform(get("/api/public/products/"+product)).andExpect(status().isOk()).andExpect(jsonPath("$.title").value("苹果5斤装"))
            .andExpect(jsonPath("$.skus[0].retailPriceFen").value(3990))
            .andExpect(jsonPath("$.skus[0].supplyPriceFen").doesNotExist());
        mvc.perform(put("/api/admin/products/"+product+"/publication").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON)
            .content("{\"published\":false}")).andExpect(status().isOk());
        mvc.perform(get("/api/public/products/"+product)).andExpect(status().isNotFound());
    }

    @Test void supplyValidationKeepsAuthenticationAndAuthorizationSemantics() throws Exception {
        String admin="Bearer "+mapper.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"admin\",\"password\":\"long-test-password-123\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
        long category=mapper.readTree(mvc.perform(post("/api/admin/categories").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"校验测试分类\",\"sortOrder\":99,\"enabled\":true}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        long supplier=mapper.readTree(mvc.perform(post("/api/admin/suppliers").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"校验测试供应商\",\"contactName\":\"测试\",\"contactPhone\":\"13800009999\",\"enabled\":true}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        long origin=mapper.readTree(mvc.perform(post("/api/admin/origins").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"supplierId\":"+supplier+",\"label\":\"校验测试仓\",\"provinceCode\":\"110000\",\"province\":\"北京市\",\"cityCode\":\"110100\",\"city\":\"北京市\",\"districtCode\":\"110101\",\"district\":\"东城区\",\"address\":\"测试地址\",\"contactName\":\"测试\",\"contactPhone\":\"13800009999\",\"isDefault\":true,\"enabled\":true}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        long template=mapper.readTree(mvc.perform(post("/api/admin/shipping-templates").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"校验测试模板\",\"freeThresholdFen\":null,\"enabled\":true}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        long product=mapper.readTree(mvc.perform(post("/api/admin/products").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"categoryId\":"+category+",\"title\":\"校验测试商品\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        long sku=mapper.readTree(mvc.perform(post("/api/admin/products/"+product+"/skus").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"VALIDATION-SKU\",\"specJson\":\"{}\",\"retailPriceFen\":1234,\"netWeightG\":2500,\"billableWeightG\":2500,\"active\":true}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        String valid="{\"supplierId\":"+supplier+",\"originId\":"+origin+",\"shippingTemplateId\":"+template+",\"supplyPriceFen\":800,\"isDefault\":true}";
        String missingTemplate="{\"supplierId\":"+supplier+",\"originId\":"+origin+",\"shippingTemplateId\":null,\"supplyPriceFen\":800,\"isDefault\":true}";

        mvc.perform(post("/api/admin/skus/"+sku+"/supplies").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content(valid))
            .andExpect(status().isOk()).andExpect(jsonPath("$.shippingTemplateId").value(template));
        mvc.perform(post("/api/admin/skus/"+sku+"/supplies").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content(missingTemplate))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value("请求参数校验失败"))
            .andExpect(jsonPath("$.fields[0].field").value("shippingTemplateId"))
            .andExpect(jsonPath("$.fields[0].message").value("不能为空"));
        mvc.perform(post("/api/admin/skus/"+sku+"/supplies").contentType(MediaType.APPLICATION_JSON).content(valid))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        String customer="Bearer "+mapper.readTree(mvc.perform(post("/api/dev/consumer-login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"platform\":\"WECHAT\",\"externalUserId\":\"validation-role-user\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
        mvc.perform(post("/api/admin/skus/"+sku+"/supplies").header("Authorization",customer).contentType(MediaType.APPLICATION_JSON).content(valid))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test void shippingOriginsAreScopedGroupedSnapshottedAndCannotMoveUsedStock() throws Exception {
        String bearer="Bearer "+mapper.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"admin\",\"password\":\"long-test-password-123\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
        long category=mapper.readTree(mvc.perform(post("/api/admin/categories").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"发货地验证\",\"sortOrder\":2,\"enabled\":true}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        long supplier1=mapper.readTree(mvc.perform(post("/api/admin/suppliers").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"供应商甲\",\"contactName\":\"甲\",\"contactPhone\":\"13800000001\",\"enabled\":true}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        long supplier2=mapper.readTree(mvc.perform(post("/api/admin/suppliers").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"供应商乙\",\"contactName\":\"乙\",\"contactPhone\":\"13800000002\",\"enabled\":true}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        String origin1Json="{\"supplierId\":"+supplier1+",\"label\":\"甲一仓\",\"provinceCode\":\"610000\",\"province\":\"陕西省\",\"cityCode\":\"610100\",\"city\":\"西安市\",\"districtCode\":\"610102\",\"district\":\"新城区\",\"address\":\"一号地址\",\"contactName\":\"甲一\",\"contactPhone\":\"13800000001\",\"isDefault\":true,\"enabled\":true}";
        String origin2Json="{\"supplierId\":"+supplier1+",\"label\":\"甲二仓\",\"provinceCode\":\"610000\",\"province\":\"陕西省\",\"cityCode\":\"610100\",\"city\":\"西安市\",\"districtCode\":\"610103\",\"district\":\"碑林区\",\"address\":\"二号地址\",\"contactName\":\"甲二\",\"contactPhone\":\"13800000003\",\"isDefault\":false,\"enabled\":true}";
        long origin1=mapper.readTree(mvc.perform(post("/api/admin/origins").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON).content(origin1Json)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        long origin2=mapper.readTree(mvc.perform(post("/api/admin/origins").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON).content(origin2Json)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        long template=mapper.readTree(mvc.perform(post("/api/admin/shipping-templates").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"分仓模板\",\"freeThresholdFen\":null,\"enabled\":true}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(post("/api/admin/shipping-templates/"+template+"/rules").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON)
            .content("{\"regionCode\":\"610000\",\"blocked\":false,\"firstWeightG\":1000,\"firstFeeFen\":600,\"stepWeightG\":1000,\"stepFeeFen\":200}")).andExpect(status().isOk());
        long product=mapper.readTree(mvc.perform(post("/api/admin/products").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON)
            .content("{\"categoryId\":"+category+",\"title\":\"分仓商品\",\"description\":\"验证\",\"originDescription\":\"陕西果园\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        long sku1=mapper.readTree(mvc.perform(post("/api/admin/products/"+product+"/skus").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"ORIGIN-SKU-1\",\"specJson\":\"{}\",\"retailPriceFen\":2000,\"netWeightG\":500,\"billableWeightG\":500,\"active\":true}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        long sku2=mapper.readTree(mvc.perform(post("/api/admin/products/"+product+"/skus").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"ORIGIN-SKU-2\",\"specJson\":\"{}\",\"retailPriceFen\":3000,\"netWeightG\":500,\"billableWeightG\":500,\"active\":true}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        String supply1Json="{\"supplierId\":"+supplier1+",\"originId\":"+origin1+",\"shippingTemplateId\":"+template+",\"supplyPriceFen\":1000,\"isDefault\":true}";
        String supply2Json="{\"supplierId\":"+supplier1+",\"originId\":"+origin2+",\"shippingTemplateId\":"+template+",\"supplyPriceFen\":1500,\"isDefault\":true}";
        long supply1=mapper.readTree(mvc.perform(post("/api/admin/skus/"+sku1+"/supplies").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON).content(supply1Json)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        long supply2=mapper.readTree(mvc.perform(post("/api/admin/skus/"+sku2+"/supplies").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON).content(supply2Json)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(post("/api/admin/skus/"+sku1+"/supplies").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON)
            .content("{\"supplierId\":"+supplier2+",\"originId\":"+origin1+",\"shippingTemplateId\":"+template+",\"supplyPriceFen\":900,\"isDefault\":false}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/inventory/"+supply1+"/adjust").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON).content("{\"delta\":5,\"reason\":\"来源一入库\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/admin/inventory/"+supply2+"/adjust").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON).content("{\"delta\":7,\"reason\":\"来源二入库\"}")).andExpect(status().isOk());
        mvc.perform(put("/api/admin/skus/"+sku1+"/supplies/"+supply1).header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON)
            .content("{\"supplierId\":"+supplier1+",\"originId\":"+origin2+",\"shippingTemplateId\":"+template+",\"supplyPriceFen\":1000,\"isDefault\":true}")).andExpect(status().isConflict());
        mvc.perform(put("/api/admin/origins/"+origin2+"/default").header("Authorization",bearer)).andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT origin_id FROM sku_supply WHERE id=?",Long.class,supply1)).isEqualTo(origin1);

        db.update("INSERT INTO media_object(object_key,content_type) VALUES('origin-flow-image','image/png')");
        long media=db.queryForObject("SELECT id FROM media_object WHERE object_key='origin-flow-image'",Long.class);
        mvc.perform(put("/api/admin/products/"+product+"/images").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON).content("["+media+"]")).andExpect(status().isOk());
        mvc.perform(put("/api/admin/products/"+product+"/publication").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON).content("{\"published\":true}")).andExpect(status().isOk());
        String customer="Bearer "+mapper.readTree(mvc.perform(post("/api/dev/consumer-login").contentType(MediaType.APPLICATION_JSON).content("{\"platform\":\"WECHAT\",\"externalUserId\":\"origin-flow-user\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
        long address=mapper.readTree(mvc.perform(post("/api/customer/addresses").header("Authorization",customer).contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipient\":\"买家\",\"mobile\":\"13900000000\",\"provinceCode\":\"610000\",\"provinceName\":\"陕西省\",\"cityCode\":\"610100\",\"cityName\":\"西安市\",\"districtCode\":\"610102\",\"districtName\":\"新城区\",\"detail\":\"收货地址\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        JsonNode cart=mapper.readTree(mvc.perform(post("/api/customer/cart").header("Authorization",customer).contentType(MediaType.APPLICATION_JSON).content("{\"skuId\":"+sku1+",\"quantity\":1}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        cart=mapper.readTree(mvc.perform(post("/api/customer/cart").header("Authorization",customer).contentType(MediaType.APPLICATION_JSON).content("{\"skuId\":"+sku2+",\"quantity\":1}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        long cart1=0,cart2=0; for(JsonNode item:cart){if(item.get("skuId").asLong()==sku1)cart1=item.get("id").asLong();if(item.get("skuId").asLong()==sku2)cart2=item.get("id").asLong();}
        JsonNode quote=mapper.readTree(mvc.perform(post("/api/customer/checkout/quote").header("Authorization",customer).contentType(MediaType.APPLICATION_JSON).content("{\"addressId\":"+address+",\"cartItemIds\":["+cart1+","+cart2+"]}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(quote.get("groups").size()).isEqualTo(2);
        JsonNode order=mapper.readTree(mvc.perform(post("/api/customer/orders").header("Authorization",customer).contentType(MediaType.APPLICATION_JSON)
            .content("{\"addressId\":"+address+",\"cartItemIds\":["+cart1+","+cart2+"],\"quoteHash\":\""+quote.get("quoteHash").asText()+"\",\"idempotencyKey\":\"origin-flow-order\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long orderId=order.get("order").get("id").asLong();
        String changedOrigin1=origin1Json.replace("一号地址","已修改地址").replace("\"isDefault\":true","\"isDefault\":false");
        mvc.perform(put("/api/admin/origins/"+origin1).header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON).content(changedOrigin1)).andExpect(status().isOk());
        mvc.perform(get("/api/customer/orders/"+orderId).header("Authorization",customer)).andExpect(status().isOk()).andExpect(jsonPath("$.groups[?(@.originId == "+origin1+")].originAddress").value("一号地址"));
        String disabledOrigin1=changedOrigin1.replace("\"enabled\":true","\"enabled\":false");
        mvc.perform(put("/api/admin/origins/"+origin1).header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON).content(disabledOrigin1)).andExpect(status().isOk());
        JsonNode newCart=mapper.readTree(mvc.perform(post("/api/customer/cart").header("Authorization",customer).contentType(MediaType.APPLICATION_JSON).content("{\"skuId\":"+sku1+",\"quantity\":1}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        long unavailableCart=0;for(JsonNode item:newCart)if(item.get("skuId").asLong()==sku1)unavailableCart=item.get("id").asLong();
        mvc.perform(post("/api/customer/checkout/quote").header("Authorization",customer).contentType(MediaType.APPLICATION_JSON).content("{\"addressId\":"+address+",\"cartItemIds\":["+unavailableCart+"]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.purchasable").value(false)).andExpect(jsonPath("$.reasons[0]").value(org.hamcrest.Matchers.containsString("发货地已停用")));
        mvc.perform(post("/api/customer/orders/"+orderId+"/cancel").header("Authorization",customer)).andExpect(status().isOk()).andExpect(jsonPath("$.order.status").value("CANCELLED"));
        assertThat(db.queryForObject("SELECT reserved_qty FROM supply_inventory WHERE supply_id=?",Integer.class,supply1)).isZero();
        assertThat(db.queryForObject("SELECT reserved_qty FROM supply_inventory WHERE supply_id=?",Integer.class,supply2)).isZero();
    }
}
