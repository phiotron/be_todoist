package com.taskly.controller;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.support.M2ApiTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SearchObjectsApiTest extends M2ApiTestBase {

    private static final String OBJECTS = "/api/v1/search/objects";

    @Test
    void findsEveryObjectTypeIgnoringAccents() throws Exception {
        var user = registerUser(uniqueEmail());
        String token = user.accessToken();
        UUID plan = data.project(user.id(), "Kế hoạch quý");
        UUID work = data.project(user.id(), "Việc");
        UUID section = data.section(work, "Kế hoạch tuần");
        UUID label = data.label(user.id(), "kế-hoạch");
        UUID filter = data.filter(user.id(), "Kế hoạch nhanh", "today");
        data.project(user.id(), "Không liên quan");

        apiGet(token, OBJECTS, "q", "ke hoach")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects[*].id", contains(plan.toString())))
                .andExpect(jsonPath("$.projects[0].isFavorite").value(false))
                .andExpect(jsonPath("$.sections[*].id", contains(section.toString())))
                .andExpect(jsonPath("$.sections[0].projectId").value(work.toString()))
                .andExpect(jsonPath("$.sections[0].projectName").value("Việc"))
                .andExpect(jsonPath("$.sections[0].name").value("Kế hoạch tuần"))
                .andExpect(jsonPath("$.labels[*].id", contains(label.toString())))
                .andExpect(jsonPath("$.filters[*].id", contains(filter.toString())))
                .andExpect(jsonPath("$.filters[0].query").value("today"));
    }

    @Test
    void inboxIsSearchable() throws Exception {
        var user = registerUser(uniqueEmail());

        apiGet(user.accessToken(), OBJECTS, "q", "inbox")
                .andExpect(jsonPath("$.projects[*].id", contains(data.inboxOf(user.id()).toString())));
    }

    @Test
    void excludesArchivedAndOtherUsersObjects() throws Exception {
        var user = registerUser(uniqueEmail());
        var other = registerUser(uniqueEmail());
        UUID archived = data.project(user.id(), "Mục tiêu cũ");
        data.section(archived, "Mục tiêu A");
        data.archiveProject(archived);
        UUID active = data.project(user.id(), "Đang làm");
        UUID oldSection = data.section(active, "Mục tiêu lưu trữ");
        data.archiveSection(oldSection);
        data.project(other.id(), "Mục tiêu của người khác");
        data.label(other.id(), "muc-tieu");
        data.filter(other.id(), "Mục tiêu", "p1");

        apiGet(user.accessToken(), OBJECTS, "q", "muc tieu")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects").isEmpty())
                .andExpect(jsonPath("$.sections").isEmpty())
                .andExpect(jsonPath("$.labels").isEmpty())
                .andExpect(jsonPath("$.filters").isEmpty());
    }

    @Test
    void eachListIsCappedAt20() throws Exception {
        var user = registerUser(uniqueEmail());
        for (int i = 1; i <= 21; i++) {
            data.project(user.id(), "Dự án " + i);
        }

        apiGet(user.accessToken(), OBJECTS, "q", "du an").andExpect(jsonPath("$.projects.length()").value(20));
    }

    @Test
    void emptyQueryGivesEmptyLists_tooLongIs400() throws Exception {
        var user = registerUser(uniqueEmail());

        apiGet(user.accessToken(), OBJECTS, "q", " ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects").isEmpty())
                .andExpect(jsonPath("$.sections").isEmpty())
                .andExpect(jsonPath("$.labels").isEmpty())
                .andExpect(jsonPath("$.filters").isEmpty());
        apiGet(user.accessToken(), OBJECTS, "q", "x".repeat(101)).andExpect(status().isBadRequest());
    }

    @Test
    void ranksBySimilarityThenName_andEscapesLikeWildcards() throws Exception {
        var user = registerUser(uniqueEmail());
        UUID exact = data.project(user.id(), "Mua sắm");
        UUID longer = data.project(user.id(), "Mua sắm cuối tuần cho cả nhà");
        data.project(user.id(), "Muaxsắm");
        data.label(user.id(), "ab_cd");
        data.label(user.id(), "abxcd");

        apiGet(user.accessToken(), OBJECTS, "q", "mua sam")
                .andExpect(jsonPath("$.projects.length()").value(3))
                .andExpect(jsonPath("$.projects[0].id").value(exact.toString()))
                .andExpect(jsonPath("$.projects[2].id").value(longer.toString()));
        apiGet(user.accessToken(), OBJECTS, "q", "mua*sam")
                .andExpect(jsonPath("$.projects.length()").value(3));
        apiGet(user.accessToken(), OBJECTS, "q", "_")
                .andExpect(jsonPath("$.projects").isEmpty())
                .andExpect(jsonPath("$.labels.length()").value(1));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(OBJECTS)
                .param("q", "a")).andExpect(status().isUnauthorized());
    }
}
