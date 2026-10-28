package com.taskly.config;

import com.taskly.integration.calendar.CalendarClients;
import com.taskly.integration.calendar.GoogleCalendarClient;
import com.taskly.integration.calendar.OutlookCalendarClient;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Client lịch chạy thật (Google Calendar API, Microsoft Graph) — M13b. Test thay CalendarClients bằng bản @Primary giả. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CalendarProperties.class)
public class CalendarConfig {

    @Bean
    CalendarClients calendarClients(CalendarProperties properties) {
        return new CalendarClients(List.of(new GoogleCalendarClient(properties), new OutlookCalendarClient(properties)));
    }
}
