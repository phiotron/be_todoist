package com.taskly.common.web;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.StringJoiner;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Deep link quay về app sau luồng web (M13b: Apple / Facebook / Calendar callback). Giá trị null bị bỏ; mọi giá trị được
 * mã hoá percent (khoảng trắng = %20, không dùng "+"). Trả 303 See Other để trình duyệt GET deep link kể cả sau form POST.
 */
public final class DeepLinks {

    private DeepLinks() {
    }

    public static URI build(String base, Map<String, String> params) {
        StringJoiner query = new StringJoiner("&");
        params.forEach((key, value) -> {
            if (value != null) {
                query.add(encode(key) + "=" + encode(value));
            }
        });
        String separator = base.contains("?") ? "&" : "?";
        return URI.create(query.length() == 0 ? base : base + separator + query);
    }

    public static ResponseEntity<Void> redirect(String base, Map<String, String> params) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(build(base, params)).build();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
