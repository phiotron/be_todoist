package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.support.M8aApiTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * M8a: ma trận vai trò × endpoint (spec mục 13 tiêu chí M8a). Sáu người trong cùng một team:
 * A1 = Admin tạo dự án (đã tham gia), A2 = Admin chưa tham gia (xem trước), M1 = Member đã tham gia (không phải người
 * tạo), M2 = Member chưa tham gia (xem trước), G = Guest được mời vào dự án, X = người ngoài. Mỗi ô (endpoint × người)
 * chạy trên dự án MỚI nên thao tác phá huỷ (archive, delete, leave) không ảnh hưởng ô khác.
 */
class RoleMatrixApiTest extends M8aApiTestBase {

    private enum Actor { A1, A2, M1, M2, G, X }

    private record Fixture(UUID project, UUID task, UUID teamId) {
    }

    private record Row(String name, String method, Function<Fixture, String> uri, Function<Fixture, String> body,
                       int[] expected) {
    }

    private RegisteredUser a1;
    private RegisteredUser a2;
    private RegisteredUser m1;
    private RegisteredUser m2;
    private RegisteredUser g;
    private RegisteredUser x;
    private UUID team;

    private RegisteredUser user(Actor actor) {
        return switch (actor) {
            case A1 -> a1;
            case A2 -> a2;
            case M1 -> m1;
            case M2 -> m2;
            case G -> g;
            case X -> x;
        };
    }

    private Fixture newFixture() throws Exception {
        UUID project = createTeamProject(a1, team, "P-" + UUID.randomUUID().toString().substring(0, 8), "TEAM");
        UUID task = createTask(a1, project, "Việc");
        authPost(m1.accessToken(), "/api/v1/projects/" + project + "/join").andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        addToProject(a1, project, g);
        return new Fixture(project, task, team);
    }

    private static int[] expect(int a1, int a2, int m1, int m2, int g, int x) {
        return new int[] {a1, a2, m1, m2, g, x};
    }

    @Test
    void roleByEndpointMatrix() throws Exception {
        a1 = newUser();
        a2 = newUser();
        m1 = newUser();
        m2 = newUser();
        g = newUser();
        x = newUser();
        team = createTeam(a1, "Ma trận");
        addToTeam(a1, team, a2, "ADMIN");
        addToTeam(a1, team, m1, "MEMBER");
        addToTeam(a1, team, m2, "MEMBER");

        String ws = "/api/v1/workspaces/";
        List<Row> rows = List.of(
                // ---- cấp dự án (dự án mới cho từng ô)
                new Row("GET project", "GET", f -> "/api/v1/projects/" + f.project(), f -> null,
                        expect(200, 200, 200, 200, 200, 404)),
                // PERM (28/10): Member / Guest đã tham gia dự án nhóm chỉ hoàn thành; mọi ghi khác 403 team-admin-only
                new Row("PATCH project name", "PATCH", f -> "/api/v1/projects/" + f.project(),
                        f -> "{\"name\": \"Đổi tên\"}", expect(200, 403, 403, 403, 403, 404)),
                new Row("POST task", "POST", f -> "/api/v1/tasks",
                        f -> "{\"content\": \"Mới\", \"projectId\": \"" + f.project() + "\"}",
                        expect(201, 403, 403, 403, 403, 404)),
                new Row("GET project tasks", "GET", f -> "/api/v1/tasks?projectId=" + f.project(), f -> null,
                        expect(200, 200, 200, 200, 200, 404)),
                new Row("GET task", "GET", f -> "/api/v1/tasks/" + f.task(), f -> null,
                        expect(200, 200, 200, 200, 200, 404)),
                new Row("POST task close", "POST", f -> "/api/v1/tasks/" + f.task() + "/close", f -> null,
                        expect(200, 403, 200, 403, 200, 404)),
                new Row("DELETE task", "DELETE", f -> "/api/v1/tasks/" + f.task(), f -> null,
                        expect(204, 403, 403, 403, 403, 404)),
                new Row("POST section", "POST", f -> "/api/v1/projects/" + f.project() + "/sections",
                        f -> "{\"name\": \"Phần\"}", expect(201, 403, 403, 403, 403, 404)),
                new Row("GET members", "GET", f -> "/api/v1/projects/" + f.project() + "/members", f -> null,
                        expect(200, 200, 200, 200, 200, 404)),
                new Row("POST project invitation", "POST", f -> "/api/v1/projects/" + f.project() + "/invitations",
                        f -> "{\"email\": \"" + uniqueEmail() + "\"}", expect(201, 403, 201, 403, 403, 404)),
                new Row("GET project invitations", "GET", f -> "/api/v1/projects/" + f.project() + "/invitations",
                        f -> null, expect(200, 403, 200, 403, 403, 404)),
                new Row("PUT project invite-link", "PUT", f -> "/api/v1/projects/" + f.project() + "/invite-link",
                        f -> null, expect(200, 403, 200, 403, 403, 404)),
                new Row("POST project archive", "POST", f -> "/api/v1/projects/" + f.project() + "/archive",
                        f -> null, expect(200, 200, 403, 403, 403, 404)),
                new Row("DELETE project", "DELETE", f -> "/api/v1/projects/" + f.project(), f -> null,
                        expect(204, 204, 403, 403, 403, 404)),
                new Row("POST project move-to-workspace", "POST",
                        f -> "/api/v1/projects/" + f.project() + "/move-to-workspace",
                        f -> "{\"workspaceId\": \"" + f.teamId() + "\"}", expect(400, 400, 403, 403, 403, 404)),
                new Row("POST project leave", "POST", f -> "/api/v1/projects/" + f.project() + "/leave", f -> null,
                        expect(204, 404, 204, 404, 204, 404)),
                new Row("POST project join", "POST", f -> "/api/v1/projects/" + f.project() + "/join", f -> null,
                        expect(200, 200, 200, 200, 200, 404)),
                // ---- cấp workspace (không đổi trạng thái giữa các ô)
                new Row("GET workspace", "GET", f -> ws + f.teamId(), f -> null, expect(200, 200, 200, 200, 200, 404)),
                new Row("PATCH workspace", "PATCH", f -> ws + f.teamId(), f -> "{\"name\": \"Ma trận\"}",
                        expect(200, 200, 403, 403, 403, 404)),
                new Row("GET people", "GET", f -> ws + f.teamId() + "/members", f -> null,
                        expect(200, 200, 200, 200, 403, 404)),
                new Row("PATCH member role", "PATCH", f -> ws + f.teamId() + "/members/" + m2.id(),
                        f -> "{\"role\": \"MEMBER\"}", expect(200, 200, 403, 403, 403, 404)),
                new Row("GET team projects", "GET", f -> ws + f.teamId() + "/projects", f -> null,
                        expect(200, 200, 200, 200, 403, 404)),
                new Row("GET folders", "GET", f -> ws + f.teamId() + "/folders", f -> null,
                        expect(200, 200, 200, 200, 403, 404)),
                new Row("POST folder", "POST", f -> ws + f.teamId() + "/folders", f -> "{\"name\": \"Thư mục\"}",
                        expect(201, 201, 201, 201, 403, 404)),
                new Row("POST workspace invitation", "POST", f -> ws + f.teamId() + "/invitations",
                        f -> "{\"email\": \"" + uniqueEmail() + "\"}", expect(201, 201, 403, 403, 403, 404)),
                new Row("GET workspace invitations", "GET", f -> ws + f.teamId() + "/invitations", f -> null,
                        expect(200, 200, 403, 403, 403, 404)),
                new Row("PUT workspace invite-link", "PUT", f -> ws + f.teamId() + "/invite-link", f -> null,
                        expect(200, 200, 403, 403, 403, 404)),
                new Row("POST project in team", "POST", f -> "/api/v1/projects",
                        f -> "{\"name\": \"Mới\", \"workspaceId\": \"" + f.teamId() + "\"}",
                        expect(201, 201, 403, 403, 403, 404))); // PERM: chỉ Admin tạo dự án nhóm

        List<String> failures = new ArrayList<>();
        int cells = 0;
        for (Row row : rows) {
            for (Actor actor : Actor.values()) {
                Fixture fixture = newFixture();
                int actual = call(user(actor), row.method(), row.uri().apply(fixture), row.body().apply(fixture))
                        .andReturn().getResponse().getStatus();
                int expected = row.expected()[actor.ordinal()];
                cells++;
                if (actual != expected) {
                    failures.add(row.name() + " / " + actor + ": mong " + expected + ", được " + actual);
                }
            }
        }
        assertThat(cells).isEqualTo(rows.size() * Actor.values().length);
        assertThat(failures).as("Ô sai trong ma trận vai trò × endpoint").isEmpty();
    }
}
