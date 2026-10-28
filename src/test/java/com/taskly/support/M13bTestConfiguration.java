package com.taskly.support;

import com.taskly.entity.CalendarProvider;
import com.taskly.integration.calendar.CalendarClients;
import com.taskly.integration.facebook.FacebookClient;
import com.taskly.security.AppleIdTokenVerifier;
import com.taskly.security.AppleProperties;
import com.taskly.security.NimbusAppleIdTokenVerifier;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * M13b trong test: Apple verifier tin khoá RSA giả (cùng validator với bản thật), Facebook / Calendar là bản giả trong bộ
 * nhớ — không gọi Apple, Facebook, Google hay Microsoft. Import ở IntegrationTestBase → mọi test dùng chung MỘT context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class M13bTestConfiguration {

    @Bean
    public AppleTestTokens appleTestTokens(Clock clock) {
        return new AppleTestTokens(clock);
    }

    @Bean
    @Primary
    public AppleIdTokenVerifier testAppleIdTokenVerifier(AppleTestTokens tokens, AppleProperties properties,
                                                         Clock clock) {
        return NimbusAppleIdTokenVerifier.withPublicKey(tokens.trustedPublicKey(), properties, clock);
    }

    @Bean
    public FakeFacebookClient fakeFacebookClient() {
        return new FakeFacebookClient();
    }

    @Bean
    @Primary
    public FacebookClient testFacebookClient(FakeFacebookClient fake) {
        return fake;
    }

    @Bean
    public FakeCalendarClients fakeCalendarClients(Clock clock) {
        return new FakeCalendarClients(new FakeCalendarClient(CalendarProvider.GOOGLE, clock),
                new FakeCalendarClient(CalendarProvider.OUTLOOK, clock));
    }

    @Bean
    @Primary
    public CalendarClients testCalendarClients(FakeCalendarClients fakes) {
        return new CalendarClients(List.of(fakes.google(), fakes.outlook()));
    }

    /** Hai client lịch giả để test lấy ra. */
    public record FakeCalendarClients(FakeCalendarClient google, FakeCalendarClient outlook) {
    }
}
