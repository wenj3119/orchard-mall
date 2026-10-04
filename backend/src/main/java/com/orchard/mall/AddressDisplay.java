package com.orchard.mall;

final class AddressDisplay {
    private AddressDisplay() {}
    static String full(String province, String city, String district, String detail) {
        String region = safe(province) + safe(city) + safe(district);
        String rest = safe(detail);
        return region + (!region.isEmpty() && rest.startsWith(region) ? rest.substring(region.length()) : rest);
    }
    private static String safe(String value) { return value == null ? "" : value; }
}
