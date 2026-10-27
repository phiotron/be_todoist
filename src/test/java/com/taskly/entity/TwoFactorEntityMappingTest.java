package com.taskly.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.IntegrationTestBase;
import com.taskly.repository.UserRecoveryCodeRepository;
import com.taskly.repository.UserRepository;
import com.taskly.repository.UserTotpRepository;
import com.taskly.security.OpaqueTokens;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** Mapping user_totp (khoá = user_id) và user_recovery_codes; xoá user kéo theo cả hai (ON DELETE CASCADE). */
class TwoFactorEntityMappingTest extends IntegrationTestBase {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserTotpRepository totpRepository;

    @Autowired
    private UserRecoveryCodeRepository recoveryCodeRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void userTotp_isStoredUnderUserId_andTracksEnableAndLastStep() {
        User user = userRepository.saveAndFlush(new User(uniqueEmail(), "hash-not-used", "Test"));
        totpRepository.saveAndFlush(new UserTotp(user.getId(), "ban-ma-gia"));
        Instant enabledAt = clock.instant();

        transactionTemplate.executeWithoutResult(status -> {
            UserTotp managed = totpRepository.findForUpdate(user.getId()).orElseThrow();
            managed.enable(enabledAt);
            managed.markStepUsed(42);
        });

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select secret_encrypted, enabled_at is not null as enabled, last_used_step, created_at is not null "
                        + "as audited from user_totp where user_id = ?", user.getId());
        assertThat(row).containsEntry("secret_encrypted", "ban-ma-gia")
                .containsEntry("enabled", true)
                .containsEntry("last_used_step", 42L)
                .containsEntry("audited", true);
        assertThat(totpRepository.findById(user.getId()).orElseThrow().isEnabled()).isTrue();
    }

    @Test
    void recoveryCodes_findUnusedByHashCountAndMarkUsed() {
        User user = userRepository.saveAndFlush(new User(uniqueEmail(), "hash-not-used", "Test"));
        String first = OpaqueTokens.sha256Hex("abcdef23");
        String second = OpaqueTokens.sha256Hex("ghijkl45");
        recoveryCodeRepository.saveAndFlush(new UserRecoveryCode(user, first));
        recoveryCodeRepository.saveAndFlush(new UserRecoveryCode(user, second));
        assertThat(recoveryCodeRepository.countByUserIdAndUsedAtIsNull(user.getId())).isEqualTo(2);

        // findByUserIdAndCodeHashAndUsedAtIsNull khoá dòng → chạy trong transaction ghi
        transactionTemplate.executeWithoutResult(status -> recoveryCodeRepository
                .findByUserIdAndCodeHashAndUsedAtIsNull(user.getId(), first).orElseThrow()
                .markUsed(clock.instant()));

        assertThat(recoveryCodeRepository.countByUserIdAndUsedAtIsNull(user.getId())).isEqualTo(1);
        Optional<UserRecoveryCode> reused = transactionTemplate.execute(status ->
                recoveryCodeRepository.findByUserIdAndCodeHashAndUsedAtIsNull(user.getId(), first));
        assertThat(reused).isEmpty();
        assertThat(recoveryCodeRepository.findAllByUserId(user.getId())).hasSize(2);
    }

    @Test
    void deletingUser_cascadesTotpAndRecoveryCodes() {
        User user = userRepository.saveAndFlush(new User(uniqueEmail(), "hash-not-used", "Test"));
        totpRepository.saveAndFlush(new UserTotp(user.getId(), "ban-ma-gia"));
        recoveryCodeRepository.saveAndFlush(new UserRecoveryCode(user, OpaqueTokens.sha256Hex("abcdef23")));

        jdbcTemplate.update("delete from users where id = ?", user.getId());

        assertThat(totpRepository.findById(user.getId())).isEmpty();
        assertThat(recoveryCodeRepository.findAllByUserId(user.getId())).isEmpty();
    }
}
