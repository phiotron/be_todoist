package com.taskly.integration;

import java.util.Objects;

/** Một email văn bản thường. toString() KHÔNG in người nhận và nội dung (nội dung có thể chứa token). */
public record OutgoingMail(String to, String subject, String body) {

    public OutgoingMail {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(body, "body");
    }

    @Override
    public String toString() {
        return "OutgoingMail[to=***, subject=" + subject + ", body=***]";
    }
}
