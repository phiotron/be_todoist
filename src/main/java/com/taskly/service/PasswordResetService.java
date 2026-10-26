package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.config.PasswordResetProperties;
import com.taskly.entity.PasswordResetToken;
import com.taskly.entity.User;
import com.taskly.integration.MailDeliveryException;
import com.taskly.integration.MailSender;
import com.taskly.integration.OutgoingMail;
import com.taskly.repository.PasswordResetTokenRepository;
import com.taskly.security.OpaqueTokens;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quên / đặt lại mật khẩu (spec mục 12.1). Sở hữu bảng password_reset_tokens.
 * Token = 32 byte ngẫu nhiên base64url (OpaqueTokens), DB chỉ lưu SHA-256, hết hạn 30 phút, dùng một lần.
 * KHÔNG log email hay token.
 */
@Service
@Transactional
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    static final String MAIL_SUBJECT_KEY = "mail.password-reset.subject";
    static final String MAIL_BODY_KEY = "mail.password-reset.body";

    private final UserService userService;
    private final PasswordResetTokenRepository tokenRepository;
    private final MailSender mailSender;
    private final MessageSource messageSource;
    private final PasswordResetProperties properties;
    private final Clock clock;

    public PasswordResetService(UserService userService, PasswordResetTokenRepository tokenRepository,
                                MailSender mailSender, MessageSource messageSource,
                                PasswordResetProperties properties, Clock clock) {
        this.userService = userService;
        this.tokenRepository = tokenRepository;
        this.mailSender = mailSender;
        this.messageSource = messageSource;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Kết thúc bình thường (controller trả 200 giống nhau) dù email có tài khoản hay không.
     * Có tài khoản: vô hiệu token cũ còn dùng được, tạo token mới, gửi thư theo ngôn ngữ của tài khoản.
     * Gửi lỗi → 503 và rollback cả transaction: token mới biến mất, token cũ giữ nguyên như trước yêu cầu.
     */
    public void requestReset(String normalizedEmail) {
        Optional<User> found = userService.findByEmail(normalizedEmail);
        if (found.isEmpty()) {
            return;
        }
        User user = found.get();
        Instant now = Instant.now(clock);
        tokenRepository.findAllByUserIdAndUsedAtIsNullAndExpiresAtAfter(user.getId(), now)
                .forEach(token -> token.invalidate(now));
        String token = OpaqueTokens.generate();
        tokenRepository.save(new PasswordResetToken(user, OpaqueTokens.sha256Hex(token),
                now.plus(properties.tokenTtl())));
        try {
            mailSender.send(composeMail(user, token));
        } catch (MailDeliveryException e) {
            log.warn("Không gửi được email đặt lại mật khẩu; trả 503 và huỷ token vừa tạo");
            throw ApiException.serviceUnavailable(ErrorMessage.MAIL_UNAVAILABLE);
        }
    }

    /** Thư theo users.locale: liên kết taskly://reset-password/<token> + chính token làm mã để dán vào app. */
    private OutgoingMail composeMail(User user, String token) {
        Locale locale = Locale.forLanguageTag(user.getLocale());
        String link = properties.linkBase() + token;
        String minutes = String.valueOf(properties.tokenTtl().toMinutes());
        String subject = messageSource.getMessage(MAIL_SUBJECT_KEY, null, locale);
        String body = messageSource.getMessage(MAIL_BODY_KEY,
                new Object[] {user.getDisplayName(), link, token, minutes}, locale);
        return new OutgoingMail(user.getEmail(), subject, body);
    }
}
