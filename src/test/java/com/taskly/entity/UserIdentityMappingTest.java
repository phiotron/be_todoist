package com.taskly.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.IntegrationTestBase;
import com.taskly.repository.UserRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Liên kết Google là một phần của aggregate User: thêm / bỏ qua User, lưu theo cascade + orphanRemoval. */
class UserIdentityMappingTest extends IntegrationTestBase {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void savesPasswordlessUserWithGoogleIdentityByCascade() {
        String email = uniqueEmail();
        String subject = "google-sub-" + UUID.randomUUID();
        User user = new User(email, null, "Chỉ có Google");
        user.linkIdentity(IdentityProvider.GOOGLE, subject, email);

        userRepository.saveAndFlush(user);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select user_id, provider, email from user_identities where subject = ?", subject);
        assertThat(row).containsEntry("user_id", user.getId())
                .containsEntry("provider", "GOOGLE")
                .containsEntry("email", email);
        String hash = jdbcTemplate.queryForObject(
                "select password_hash from users where id = ?", String.class, user.getId());
        assertThat(hash).isNull();
    }

    @Test
    void findByIdentityReturnsLinkedUserWithProviders() {
        String email = uniqueEmail();
        String subject = "google-sub-" + UUID.randomUUID();
        User user = new User(email, null, "Chỉ có Google");
        user.linkIdentity(IdentityProvider.GOOGLE, subject, email);
        userRepository.saveAndFlush(user);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            User found = userRepository.findByIdentity(IdentityProvider.GOOGLE, subject).orElseThrow();
            assertThat(found.getId()).isEqualTo(user.getId());
            assertThat(found.hasPassword()).isFalse();
            assertThat(found.linkedProviders()).containsExactly(IdentityProvider.GOOGLE);
            assertThat(found.identity(IdentityProvider.GOOGLE)).get()
                    .extracting(UserIdentity::getSubject).isEqualTo(subject);
        });
        assertThat(userRepository.findByIdentity(IdentityProvider.GOOGLE, "khong-ton-tai")).isEmpty();
    }

    @Test
    void unlinkIdentityDeletesRowByOrphanRemoval() {
        String email = uniqueEmail();
        String subject = "google-sub-" + UUID.randomUUID();
        User user = new User(email, "hash-not-used", "Có mật khẩu");
        user.linkIdentity(IdentityProvider.GOOGLE, subject, email);
        userRepository.saveAndFlush(user);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            User managed = userRepository.findById(user.getId()).orElseThrow();
            assertThat(managed.unlinkIdentity(IdentityProvider.GOOGLE)).isTrue();
            assertThat(managed.unlinkIdentity(IdentityProvider.GOOGLE)).isFalse();
        });

        Integer left = jdbcTemplate.queryForObject(
                "select count(*) from user_identities where subject = ?", Integer.class, subject);
        assertThat(left).isZero();
    }

    @Test
    void inMemoryRules_onePerProviderAndHasPassword() {
        User withPassword = new User(uniqueEmail(), "hash-not-used", "A");
        withPassword.linkIdentity(IdentityProvider.GOOGLE, "sub-1", withPassword.getEmail());

        assertThatThrownBy(() -> withPassword.linkIdentity(IdentityProvider.GOOGLE, "sub-2", withPassword.getEmail()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(withPassword.hasPassword()).isTrue();
        assertThat(new User(uniqueEmail(), null, "B").hasPassword()).isFalse();
        assertThat(new User(uniqueEmail(), null, "C").linkedProviders()).isEmpty();
    }
}
