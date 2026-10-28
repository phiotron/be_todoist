package com.taskly.integration.calendar;

import com.taskly.entity.CalendarProvider;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

/** Chọn CalendarClient theo nhà cung cấp. Bean dựng ở CalendarConfig; test thay bằng bản @Primary chứa client giả. */
public class CalendarClients {

    private final Map<CalendarProvider, CalendarClient> clients = new EnumMap<>(CalendarProvider.class);

    public CalendarClients(Collection<? extends CalendarClient> clients) {
        clients.forEach(client -> this.clients.put(client.provider(), client));
    }

    public CalendarClient get(CalendarProvider provider) {
        CalendarClient client = clients.get(provider);
        if (client == null) {
            throw new IllegalStateException("Chưa có CalendarClient cho " + provider);
        }
        return client;
    }
}
