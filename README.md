# Taskly — Backend

REST API cho Taskly, app quản lý công việc kiểu Todoist. App di động nằm ở repo riêng (`mobile`).

- **Stack:** Java 17 · Spring Boot 3 · PostgreSQL 16 · Flyway · Maven Wrapper
- **API:** `http://localhost:8080/api/v1/...`; tài liệu OpenAPI ở `/v3/api-docs` (Swagger UI: `/swagger-ui.html`)
- **Thiết kế:** xem spec `docs/superpowers/specs/2026-10-23-taskly-design.md` trong repo tài liệu gốc

## Cần có
- JDK 17
- Docker Desktop (chạy Postgres, Mailpit và Testcontainers khi test)

Không cần cài Maven, vì repo dùng `mvnw`.

## Chạy lần đầu
1. Chép `.env.example` thành `.env` rồi điền `DB_PASSWORD` và `JWT_SECRET`.
2. Bật hạ tầng: `docker compose up -d`. Hộp thư giả của Mailpit xem ở http://localhost:8025.
3. Chạy API: `.\mvnw spring-boot:run`

## Lệnh
| Việc | Lệnh |
|---|---|
| Chạy | `.\mvnw spring-boot:run` |
| Test | `.\mvnw test` |
| Kiểm tra biên dịch | `.\mvnw -q compile` |
| Tắt hạ tầng | `docker compose down` |

## Cấu trúc package (`com.taskly`, sắp theo lớp)
`controller/` · `service/` · `repository/` · `entity/` · `dto/` · `mapper/` · `security/` · `integration/` ·
`config/` · `common/`

## Hợp đồng API với app di động
App di động sinh kiểu TypeScript từ `/v3/api-docs` bằng lệnh `npm run gen:api`. Sau mỗi lần đổi API, cần chạy
lại lệnh đó ở repo `mobile`. Thay đổi phá vỡ tương thích thì phải lên `/api/v2` thay vì sửa thẳng `/api/v1`.
