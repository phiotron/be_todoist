# Taskly — Backend

REST API cho Taskly, app quản lý công việc kiểu Todoist. App di động nằm ở repo riêng (`mobile`).

- **Stack:** Java 17 · Spring Boot 4.1 · Spring Security (OAuth2 Resource Server, JWT HS256) · PostgreSQL 16 · Flyway · springdoc-openapi · Maven Wrapper
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
| Test một class | `.\mvnw test "-Dtest=RegisterApiTest"` |
| Kiểm tra biên dịch | `.\mvnw -q compile` |
| Tắt hạ tầng | `docker compose down` |

## Cấu trúc package (`com.taskly`, sắp theo lớp)
`controller/` · `service/` · `repository/` · `entity/` · `dto/<feature>/` · `mapper/` · `security/` · `config/` ·
`common/` (`integration/` — gửi email — có từ mốc M4)

## Hợp đồng API với app di động
App di động sinh kiểu TypeScript từ `/v3/api-docs` bằng lệnh `npm run gen:api`. Sau mỗi lần đổi API, cần chạy
lại lệnh đó ở repo `mobile`. Thay đổi phá vỡ tương thích thì phải lên `/api/v2` thay vì sửa thẳng `/api/v1`.

> **Lưu ý cho dev mobile:** mọi field của `AuthResponse` và `UserResponse` được đánh dấu `required` trong
> `/v3/api-docs` (`@Schema(requiredMode = REQUIRED)`), nên `openapi-typescript` sinh chúng thành bắt buộc.
> Thêm response DTO mới thì đánh dấu tương tự; `OpenApiDocsTest` khoá danh sách `required` của từng schema.

## API mốc M0
Tiền tố `/api/v1`, JSON camelCase. Lỗi luôn là ProblemDetail (`application/problem+json`); lỗi validation có thêm
`errors: [{field, message}]`.

| Method | Path | JWT | Kết quả |
|---|---|---|---|
| POST | `/auth/register` `{email, password, displayName}` | không | 201 `AuthResponse`; trùng email 409; sai dữ liệu 400 |
| POST | `/auth/login` `{email, password}` | không | 200 `AuthResponse`; sai email/mật khẩu 401 |
| POST | `/auth/refresh` `{refreshToken}` | không | 200 `AuthResponse` (token cũ bị thu hồi); token sai/hết hạn/đã thu hồi 401 |
| POST | `/auth/logout` `{refreshToken}` | có | 204 |
| GET | `/me` | có | 200 `UserResponse` |

- `AuthResponse` = `{accessToken, refreshToken, accessTokenExpiresIn (giây, 900), user}`
- `UserResponse` = `{id, email, displayName, locale, timeZone}`
- Access token: JWT HS256 sống 15 phút. Refresh token: chuỗi ngẫu nhiên, sống 30 ngày, xoay vòng mỗi lần refresh.
