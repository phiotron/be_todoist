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
1. Chép `.env.example` thành `.env` rồi điền `DB_PASSWORD`, `JWT_SECRET` và `TOTP_ENCRYPTION_KEY` (lệnh sinh khoá ghi ngay trong `.env.example`).
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
`common/` · `integration/` (gửi email qua `MailSender`, từ mốc M3)

## Hợp đồng API với app di động
App di động sinh kiểu TypeScript từ `/v3/api-docs` bằng lệnh `npm run gen:api`. Sau mỗi lần đổi API, cần chạy
lại lệnh đó ở repo `mobile`. Thay đổi phá vỡ tương thích thì phải lên `/api/v2` thay vì sửa thẳng `/api/v1`.

> **Lưu ý cho dev mobile:** mọi field của `AuthResponse` và `UserResponse` được đánh dấu `required` trong
> `/v3/api-docs` (`@Schema(requiredMode = REQUIRED)`), nên `openapi-typescript` sinh chúng thành bắt buộc.
> Thêm response DTO mới thì đánh dấu tương tự; `OpenApiDocsTest` / `OpenApiM3DocsTest` khoá danh sách `required`.
>
> **Ngôn ngữ lỗi:** gửi header `Accept-Language: vi` hoặc `en` — `title`, `detail`, `errors[].message` của ProblemDetail
> trả theo ngôn ngữ đó (thiếu header hoặc ngôn ngữ khác → tiếng Việt).

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

## API mốc M3
| Method | Path | JWT | Kết quả |
|---|---|---|---|
| PATCH | `/me` `{displayName}` | có | 200 `UserResponse`; tên rỗng / > 100 ký tự 400 |
| POST | `/me/password` `{currentPassword, newPassword}` | có | 200 `AuthResponse` mới, mọi refresh token cũ bị thu hồi; sai mật khẩu hiện tại 400 |
| GET | `/me/settings` | có | 200 `SettingsResponse` |
| PATCH | `/me/settings` (mọi field của `SettingsResponse`, tuỳ chọn) | có | 200 `SettingsResponse`; field không gửi / `null` giữ nguyên; giá trị sai 400 kèm `errors[]` |
| POST | `/me/settings/reset` | có | 200 `SettingsResponse` về mặc định (`TASKLY`, `AUTO`, `vi`…; giữ múi giờ) |
| POST | `/auth/password-reset/request` `{email}` | không | luôn 200 body rỗng dù email có hay không; gửi thư lỗi 503 |
| POST | `/auth/password-reset/confirm` `{token, newPassword}` | không | 204 (không tự đăng nhập); mã sai 404; hết hạn / đã dùng 410 |
| POST | `/auth/2fa/verify` `{twoFactorToken, code}` | không | 200 `AuthResponse`; token sai / hết hạn hoặc mã sai 401 |
| GET | `/me/2fa` | có | 200 `{enabled, recoveryCodesLeft}` |
| POST | `/me/2fa/setup` | có | 200 `{secret, otpauthUri}` (tạo / thay secret chờ bật); đang bật 409 `two-factor-already-enabled` |
| POST | `/me/2fa/enable` `{code}` | có | 200 `{recoveryCodes[10]}` (chỉ trả một lần); chưa setup 409 `two-factor-not-setup`; sai mã 400 |
| POST | `/me/2fa/disable` `{password, code}` | có | 204; sai mật khẩu / mã 400; chưa bật 409 `two-factor-not-setup` |
| POST | `/me/2fa/recovery-codes` `{code}` | có | 200 `{recoveryCodes[10]}` mới (mã cũ hết hiệu lực) |
| POST | `/me/logout-all` | có | 204 — thu hồi mọi refresh token (đăng xuất mọi thiết bị) |
| DELETE | `/me` `{password, code?}` | có | 204 — xoá vĩnh viễn tài khoản + dữ liệu; sai mật khẩu / mã 2FA 400; chưa có mật khẩu 409 `password-required` |

- `SettingsResponse` = `{theme: TASKLY|MOONSTONE|TANGERINE|KALE|BLUEBERRY|LAVENDER|RASPBERRY, appearance: AUTO|LIGHT|DARK,
  locale: vi|en, timeZone (IANA), startDay / nextWeek / weekendStartDay: 1–7 (1 = Thứ Hai), dateFormat: DMY|MDY,
  timeFormat: H24|H12, startPage: INBOX|TODAY|UPCOMING|project:<uuid>|label:<uuid>|filter:<uuid>, smartDateRecognition,
  swipeLeft / swipeRight: COMPLETE|SCHEDULE|DELETE|REMINDERS|SELECT|NONE, navTabs: 3–5 khoá, cuối là BROWSE}`;
  mặc định `TASKLY`, `AUTO`, `vi`, 1, 1, 6, `DMY`, `H24`, `TODAY`, `true`, `SCHEDULE`, `COMPLETE`,
  `[INBOX, TODAY, UPCOMING, BROWSE]`. Bản 2 `theme: LIGHT|DARK|SYSTEM` không còn (→ `appearance`, `SYSTEM` = `AUTO`).
- Thư đặt lại mật khẩu (xem ở Mailpit http://localhost:8025) có liên kết `taskly://reset-password/<mã>` và chính mã đó
  để dán vào app. Mã dùng một lần, hết hạn sau 30 phút; yêu cầu mới làm mã cũ hết hiệu lực; đặt lại thành công thu hồi
  mọi refresh token (đăng xuất mọi máy).
- Đổi / đặt lại mật khẩu không vô hiệu access token đang cầm (JWT stateless, tối đa 15 phút).
- SMTP cấu hình bằng `MAIL_HOST`, `MAIL_PORT`, `MAIL_FROM` trong `.env` (mặc định Mailpit `localhost:1025`).
- **2FA (TOTP RFC 6238, 6 số, 30 giây, lệch ±1 bước):** đăng nhập tài khoản bật 2FA trả **401** `code:
  "two-factor-required"` kèm `twoFactorToken` (5 phút) → gọi `/auth/2fa/verify` với mã 6 số hoặc một mã khôi phục
  (`xxxx-xxxx`, dùng một lần). Mỗi mã TOTP chỉ dùng được một lần. Secret lưu mã hoá AES-256-GCM bằng
  `TOTP_ENCRYPTION_KEY` trong `.env` (thiếu thì server không khởi động; mất khoá → chỉ đăng nhập được bằng mã khôi phục).
  `UserResponse` có thêm `twoFactorEnabled`. Đặt lại mật khẩu không tắt 2FA.
- Lỗi có `code` (để app phân biệt, không dựa vào câu chữ): `two-factor-required`, `two-factor-already-enabled`,
  `two-factor-not-setup`, `password-required`; riêng `auth/2fa/verify` (401): `two-factor-code-invalid` (mã sai, token còn
  sống — nhập lại) và `two-factor-token-invalid` (token hết hạn / đã dùng / không hợp lệ — đăng nhập lại).
