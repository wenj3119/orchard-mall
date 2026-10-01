package com.orchard.mall;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties={
    "spring.datasource.url=jdbc:h2:mem:phase2;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.profiles.active=test",
    "app.admin-init-password=long-test-password-123", "app.dev-consumer-login-enabled=true",
    "storage.access-key=test", "storage.secret-key=test", "app.order-expiry-scan-ms=3600000"
})
class Phase2FlowTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper mapper;
    @Autowired OrderService orders;
    String customer1;
    String customer2;

    @BeforeEach void seed() throws Exception {
        db.update("DELETE FROM inventory_movement"); db.update("DELETE FROM stock_reservation");
        db.update("DELETE FROM order_item"); db.update("DELETE FROM order_group"); db.update("DELETE FROM sales_order");
        db.update("DELETE FROM shopping_cart"); db.update("DELETE FROM customer_address"); db.update("DELETE FROM consumer_session");
        db.update("DELETE FROM consumer_identity"); db.update("DELETE FROM consumer_account");
        db.update("DELETE FROM supply_inventory"); db.update("DELETE FROM sku_supply");
        db.update("DELETE FROM shipping_rule"); db.update("DELETE FROM shipping_template");
        db.update("DELETE FROM sku"); db.update("DELETE FROM product_image");
        db.update("DELETE FROM product"); db.update("DELETE FROM origin"); db.update("DELETE FROM supplier"); db.update("DELETE FROM category");
        db.update("INSERT INTO category(id,name,sort_order,enabled) VALUES (101,'鲜果',1,TRUE)");
        db.update("INSERT INTO supplier(id,name,contact_name,contact_phone,source_type,enabled) VALUES (101,'果园','甲','13800000000','SELF',TRUE)");
        db.update("INSERT INTO origin(id,supplier_id,label,province,city,address) VALUES (101,101,'果园仓','陕西省','西安市','测试仓')");
        db.update("INSERT INTO product(id,category_id,title,description,published) VALUES (101,101,'测试苹果','快照测试',TRUE)");
        db.update("INSERT INTO sku(id,product_id,code,spec_json,retail_price_fen,net_weight_g,billable_weight_g,active) VALUES (101,101,'P2-APPLE','{\"包装\":\"一箱\"}',5000,1200,1500,TRUE)");
        db.update("INSERT INTO shipping_template(id,name,free_threshold_fen,enabled,version) VALUES (101,'大陆模板',10000,TRUE,1)");
        db.update("INSERT INTO shipping_rule(id,template_id,region_code,blocked,first_weight_g,first_fee_fen,step_weight_g,step_fee_fen) VALUES (101,101,'610000',FALSE,1000,700,500,100),(102,101,'650000',TRUE,1000,700,500,100)");
        db.update("INSERT INTO sku_supply(id,sku_id,supplier_id,origin_id,supply_price_fen,is_default,shipping_template_id) VALUES (101,101,101,101,3000,TRUE,101)");
        db.update("INSERT INTO supply_inventory(supply_id,on_hand_qty,reserved_qty) VALUES (101,10,0)");
        customer1=login("WECHAT","phase2-user-1"); customer2=login("ALIPAY","phase2-user-2");
    }
    private String login(String platform,String user) throws Exception {
        String body=mvc.perform(post("/api/dev/consumer-login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"platform\":\""+platform+"\",\"externalUserId\":\""+user+"\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer "+mapper.readTree(body).get("token").asText();
    }
    private long address(String bearer,String recipient,String provinceCode,String cityCode,String districtCode) throws Exception {
        String json="{\"recipient\":\""+recipient+"\",\"mobile\":\"13800000001\",\"provinceCode\":\""+provinceCode+"\",\"provinceName\":\"陕西省\",\"cityCode\":\""+cityCode+"\",\"cityName\":\"西安市\",\"districtCode\":\""+districtCode+"\",\"districtName\":\"新城区\",\"detail\":\"测试路1号\"}";
        return mapper.readTree(mvc.perform(post("/api/customer/addresses").header("Authorization",bearer)
            .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).get("id").asLong();
    }
    private long cart(String bearer,int quantity) throws Exception {
        JsonNode body=mapper.readTree(mvc.perform(post("/api/customer/cart").header("Authorization",bearer)
            .contentType(MediaType.APPLICATION_JSON).content("{\"skuId\":101,\"quantity\":"+quantity+"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return body.get(0).get("id").asLong();
    }
    private JsonNode quote(String bearer,long address,long cart) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/customer/checkout/quote").header("Authorization",bearer)
            .contentType(MediaType.APPLICATION_JSON).content("{\"addressId\":"+address+",\"cartItemIds\":["+cart+"]}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test void productionProfileRejectsDevelopmentConsumerLogin() {
        var environment=new MockEnvironment();
        environment.setActiveProfiles("prod");
        assertThatThrownBy(()->new ConsumerAuthController(db,environment,true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("forbidden");
    }

    @Test void developmentLoginReusesIdentityWithinPlatformButSeparatesPlatforms() throws Exception {
        String sameId="dev-fixed-login-check";
        String wechat="{\"platform\":\"WECHAT\",\"externalUserId\":\""+sameId+"\"}";
        String alipay="{\"platform\":\"ALIPAY\",\"externalUserId\":\""+sameId+"\"}";
        long first=mapper.readTree(mvc.perform(post("/api/dev/consumer-login").contentType(MediaType.APPLICATION_JSON)
            .content(wechat)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
            .get("consumerId").asLong();
        long again=mapper.readTree(mvc.perform(post("/api/dev/consumer-login").contentType(MediaType.APPLICATION_JSON)
            .content(wechat)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
            .get("consumerId").asLong();
        long otherPlatform=mapper.readTree(mvc.perform(post("/api/dev/consumer-login").contentType(MediaType.APPLICATION_JSON)
            .content(alipay)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
            .get("consumerId").asLong();
        assertThat(again).isEqualTo(first);
        assertThat(otherPlatform).isNotEqualTo(first);
    }

    @Test void ownershipShippingIdempotencySnapshotsAndRelease() throws Exception {
        long address=address(customer1,"张三","610000","610100","610102");
        mvc.perform(get("/api/customer/addresses").header("Authorization",customer2)).andExpect(status().isOk())
            .andExpect(content().json("[]"));
        mvc.perform(put("/api/customer/addresses/"+address).header("Authorization",customer2).contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipient\":\"越权\",\"mobile\":\"13800000002\",\"provinceCode\":\"610000\",\"provinceName\":\"陕西省\",\"cityCode\":\"610100\",\"cityName\":\"西安市\",\"districtCode\":\"610102\",\"districtName\":\"新城区\",\"detail\":\"其他\"}"))
            .andExpect(status().isNotFound());
        long cart=cart(customer1,1); JsonNode q=quote(customer1,address,cart);
        assertThat(q.get("shippingAmountFen").asLong()).isEqualTo(800);
        assertThat(q.get("groups").get(0).get("billableWeightG").asLong()).isEqualTo(1500);

        db.update("UPDATE sku SET retail_price_fen=5100 WHERE id=101");
        mvc.perform(post("/api/customer/orders").header("Authorization",customer1).contentType(MediaType.APPLICATION_JSON)
            .content("{\"addressId\":"+address+",\"cartItemIds\":["+cart+"],\"quoteHash\":\""+q.get("quoteHash").asText()+"\",\"idempotencyKey\":\"phase2-price-change\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUOTE_CHANGED"));
        assertThat(db.queryForObject("SELECT reserved_qty FROM supply_inventory WHERE supply_id=101",Integer.class)).isZero();

        JsonNode fresh=quote(customer1,address,cart); String request="{\"addressId\":"+address+",\"cartItemIds\":["+cart+"],\"quoteHash\":\""+fresh.get("quoteHash").asText()+"\",\"idempotencyKey\":\"phase2-idempotent\"}";
        JsonNode created=mapper.readTree(mvc.perform(post("/api/customer/orders").header("Authorization",customer1).contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long orderId=created.get("order").get("id").asLong();
        mvc.perform(post("/api/customer/orders").header("Authorization",customer1).contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isCreated()).andExpect(jsonPath("$.order.id").value(orderId));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sales_order",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT reserved_qty FROM supply_inventory WHERE supply_id=101",Integer.class)).isEqualTo(1);
        mvc.perform(get("/api/customer/orders/"+orderId).header("Authorization",customer2)).andExpect(status().isNotFound());

        db.update("UPDATE product SET title='已修改商品' WHERE id=101"); db.update("UPDATE shipping_template SET name='已修改模板' WHERE id=101");
        db.update("UPDATE customer_address SET recipient='已修改收件人' WHERE id=?",address);
        mvc.perform(get("/api/customer/orders/"+orderId).header("Authorization",customer1)).andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].productTitle").value("测试苹果"))
            .andExpect(jsonPath("$.groups[0].templateName").value("大陆模板"))
            .andExpect(jsonPath("$.address.recipient").value("张三"));
        mvc.perform(post("/api/customer/orders/"+orderId+"/cancel").header("Authorization",customer1)).andExpect(status().isOk()).andExpect(jsonPath("$.order.status").value("CANCELLED"));
        mvc.perform(post("/api/customer/orders/"+orderId+"/cancel").header("Authorization",customer1)).andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT reserved_qty FROM supply_inventory WHERE supply_id=101",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE order_id=? AND delta_reserved=-1",Integer.class,orderId)).isEqualTo(1);
    }

    @Test void blockedRegionAndFreeShippingBoundary() throws Exception {
        long blocked=address(customer1,"李四","650000","650100","650102"); long c1=cart(customer1,1);
        JsonNode blockedQuote=quote(customer1,blocked,c1); assertThat(blockedQuote.get("purchasable").asBoolean()).isFalse();
        assertThat(blockedQuote.get("groups").get(0).get("reason").asText()).contains("不配送");
        db.update("DELETE FROM shopping_cart");
        long allowed=address(customer1,"王五","610000","610100","610102"); long c2=cart(customer1,2);
        JsonNode free=quote(customer1,allowed,c2); assertThat(free.get("itemAmountFen").asLong()).isEqualTo(10000);
        assertThat(free.get("shippingAmountFen").asLong()).isZero();
    }

    @Test void cancellationAndExpiryRaceReleasesReservationOnce() throws Exception {
        long address=address(customer1,"竞争买家","610000","610100","610102");
        long cart=cart(customer1,1); JsonNode q=quote(customer1,address,cart);
        String request="{\"addressId\":"+address+",\"cartItemIds\":["+cart+"],\"quoteHash\":\""+q.get("quoteHash").asText()+"\",\"idempotencyKey\":\"phase2-cancel-expiry\"}";
        long orderId=mapper.readTree(mvc.perform(post("/api/customer/orders").header("Authorization",customer1)
            .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString()).get("order").get("id").asLong();
        long consumer=db.queryForObject("SELECT consumer_id FROM consumer_identity WHERE platform_user_id='phase2-user-1'",Long.class);
        db.update("UPDATE sales_order SET expires_at=? WHERE id=?",Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)),orderId);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var cancel=executor.submit(()->orders.cancel(consumer,orderId));
            var expire=executor.submit(()->{orders.closeExpired(orderId);return null;});
            cancel.get();expire.get();
        }
        assertThat(db.queryForObject("SELECT status FROM sales_order WHERE id=?",String.class,orderId))
            .isIn("CANCELLED","CLOSED");
        assertThat(db.queryForObject("SELECT reserved_qty FROM supply_inventory WHERE supply_id=101",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE order_id=? AND delta_reserved=-1",Integer.class,orderId)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT status FROM stock_reservation WHERE order_id=?",String.class,orderId)).isEqualTo("RELEASED");
    }
}
