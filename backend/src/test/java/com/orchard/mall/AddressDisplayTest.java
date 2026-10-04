package com.orchard.mall;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AddressDisplayTest {
    @Test void onlyExactRegionPrefixIsRemoved() {
        assertThat(AddressDisplay.full("陕西省","延安市","宝塔区","陕西省延安市宝塔区村一号"))
            .isEqualTo("陕西省延安市宝塔区村一号");
        assertThat(AddressDisplay.full("陕西省","延安市","宝塔区","村一号陕西省延安市宝塔区二楼"))
            .isEqualTo("陕西省延安市宝塔区村一号陕西省延安市宝塔区二楼");
        assertThat(AddressDisplay.full("陕西省","延安市","宝塔区","陕西省延安市村一号"))
            .isEqualTo("陕西省延安市宝塔区陕西省延安市村一号");
    }
}
