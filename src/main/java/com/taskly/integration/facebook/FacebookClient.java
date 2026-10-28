package com.taskly.integration.facebook;

import com.taskly.security.ExternalIdentity;

/**
 * Cổng tới Facebook Graph API (M13b). Bản thật: {@link GraphFacebookClient}; test dùng bản giả (không gọi Facebook).
 */
public interface FacebookClient {

    /**
     * Xác minh user access token: debug_token (is_valid, app_id = app của Taskly) rồi đọc /me (id, name, email).
     *
     * @throws com.taskly.security.InvalidExternalTokenException      token sai / hết hạn / của app khác
     * @throws com.taskly.security.ExternalServiceUnavailableException không gọi được Graph API / chưa cấu hình
     */
    ExternalIdentity verifyAccessToken(String accessToken);

    /**
     * Đổi authorization code (luồng web) lấy user access token.
     *
     * @throws com.taskly.security.InvalidExternalTokenException      code sai / hết hạn
     * @throws com.taskly.security.ExternalServiceUnavailableException không gọi được Graph API / chưa cấu hình
     */
    String exchangeCode(String code, String redirectUri);
}
