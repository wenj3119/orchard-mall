package com.orchard.mall;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.util.List;

@Component
public class RegionCatalog {
    public record Region(String code, String name, List<Region> children) {}
    public record Names(String province, String city, String district) {}
    private final List<Region> provinces;

    public RegionCatalog(ObjectMapper mapper) throws IOException {
        try (var stream = new ClassPathResource("regions-pca.json").getInputStream()) {
            provinces = mapper.readValue(stream, new TypeReference<List<Region>>() {});
        }
    }

    public List<Region> provinces() { return provinces; }

    public String ruleName(String code) {
        if ("000000".equals(code)) return "全国默认";
        for (var province : provinces) {
            if (province.code().equals(code)) return province.name();
            for (var city : province.children()) {
                if (city.code().equals(code)) return province.name().equals(city.name()) ? city.name() + "（市级）" : province.name() + city.name();
                for (var district : city.children())
                    if (district.code().equals(code)) return province.name() + (province.name().equals(city.name()) ? "" : city.name()) + district.name();
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "地区：编码无效，请重新选择省、市或区县");
    }

    public Names resolve(String provinceCode, String cityCode, String districtCode) {
        for (var province : provinces) {
            if (!province.code().equals(provinceCode)) continue;
            for (var city : province.children()) {
                if (!city.code().equals(cityCode)) continue;
                for (var district : city.children()) {
                    if (district.code().equals(districtCode))
                        return new Names(province.name(), city.name(), district.name());
                }
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "所在地区：省、市、区县编码无效或不属于同一层级");
    }
}

@RestController
class RegionController {
    private final RegionCatalog catalog;
    RegionController(RegionCatalog catalog) { this.catalog = catalog; }
    @GetMapping("/api/public/regions")
    public List<RegionCatalog.Region> regions() { return catalog.provinces(); }
}
