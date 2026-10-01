package com.orchard.mall;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@Profile("dev")
public class DevData {
    @Bean CommandLineRunner seedCatalog(JdbcTemplate db) {
        return args -> {
            if(db.queryForObject("SELECT COUNT(*) FROM category",Integer.class)>0) return;
            db.update("INSERT INTO category (name,sort_order) VALUES ('苹果',1),('原包装干果',2)");
            db.update("INSERT INTO supplier (name,contact_name,contact_phone,source_type) VALUES ('演示果园','演示联系人','13800000000','SELF'),('演示干果厂家','演示联系人','13900000000','FACTORY')");
            db.update("INSERT INTO origin (supplier_id,label,province,city,address) VALUES (1,'陕西产地','陕西省','延安市','演示地址'),(2,'厂家仓库','河北省','沧州市','演示地址')");
            db.update("INSERT INTO product (category_id,title,description) VALUES (1,'产地苹果','演示商品，请上传真实图片后上架'),(2,'厂家原包装干果','演示商品，请核实包装及供货资料后上架')");
            db.update("INSERT INTO sku (product_id,code,spec_json,retail_price_fen,net_weight_g,billable_weight_g) VALUES (1,'APPLE-5J','{\"包装\":\"5斤装\"}',3990,2500,3000),(1,'APPLE-10J','{\"包装\":\"10斤装\"}',6990,5000,5500),(2,'NUT-BOX','{\"包装\":\"原厂盒装\"}',5990,900,1200)");
            db.update("INSERT INTO sku_supply (sku_id,supplier_id,origin_id,supply_price_fen,is_default) VALUES (1,1,1,2500,TRUE),(2,1,1,4500,TRUE),(3,2,2,3800,TRUE)");
            db.update("INSERT INTO supply_inventory (supply_id,on_hand_qty,reserved_qty) SELECT id,0,0 FROM sku_supply");
        };
    }
}
