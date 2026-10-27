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
| POST | `/auth/refresh` `{refreshToken}` | không | 200 `AuthResponse` (token cũ bị thu hồi); token sai/hết hạn/đã thu hồi 401; quá 30 lần/phút/IP 429 |
| POST | `/auth/logout` `{refreshToken}` | có | 204 (thu hồi cả họ token, kể cả token mới sinh do refresh) |
| GET | `/me` | có | 200 `UserResponse` |

- `AuthResponse` = `{accessToken, refreshToken, accessTokenExpiresIn (giây, 900), user}`
- `UserResponse` = `{id, email, displayName, locale, timeZone, hasPassword, linkedProviders}` (2 field cuối từ M5)
- Access token: JWT HS256 sống 15 phút. Refresh token: chuỗi ngẫu nhiên, sống 30 ngày, xoay vòng mỗi lần refresh.
- `login` / `register`: tối đa 10 lần / phút / IP mỗi loại → 429 kèm `Retry-After`; `refresh`: 30 lần / phút / IP → 429 (mobile coi là lỗi tạm, thử lại sau).

## API mốc M1 (giống Todoist)
Mọi endpoint cần JWT. Không thấy được tài nguyên (không tồn tại / của người khác / đã xoá) → 404. Mọi ProblemDetail có
`requestId` (cũng ở header `X-Request-Id`) và, ở các tình huống app cần phân biệt, `code` (`version-conflict`,
`project-archived`, `section-archived`, `parent-completed`, `task-completed`, `target-archived`, `limit-reached` kèm
`limit` + `max`, `id-conflict`, `email-taken`, `idempotency-*`). Danh sách task phân trang: `?cursor=&limit=` (1–200, mặc
định 50) → `{items, nextCursor}`. Giờ của task (`dueDatetime`) là **giờ floating**: xuất `yyyy-MM-ddTHH:mm:ss` (không có
`Z`), nhập chấp nhận cả `HH:mm`. `POST projects|sections|tasks` nhận `id` UUID tuỳ chọn do client sinh (cùng `id` + cùng
nội dung → 200 + `Idempotent-Replay: true`; khác nội dung / của người khác → 409 `id-conflict`).

| Method | Path | Ghi chú |
|---|---|---|
| GET | `/projects?archived=` | mảng (không phân trang), Inbox đứng đầu rồi theo cây; `isFavorite`, `activeTaskCount` (task đang mở), `descendantProjectCount` + `descendantActiveTaskCount` (cho hộp xác nhận xoá) |
| POST | `/projects` `{id?, name, color?, description?, parentId?, afterId?}` | 201; 20 màu mã viết thường (`charcoal`…); dự án con ≤ 3 cấp; 300 đang hoạt động / 500 tổng → 422 |
| GET / PATCH / DELETE | `/projects/{id}` | PATCH tên / màu / mô tả; DELETE = **xoá vĩnh viễn** kèm dự án con, section, task (204); Inbox → 403; dự án lưu trữ chỉ đọc (409 `project-archived`) |
| POST | `/projects/{id}/move` `{parentId, afterId?}` | đổi cha (`parentId` bắt buộc có khoá, null = gốc), cả cây đi theo; `afterId` vắng = cuối, `null` = đầu |
| POST | `/projects/{id}/archive`, `/unarchive` | kèm dự án con; lặp lại vẫn 200 |
| PUT / DELETE | `/projects/{id}/favorite` | 200 + dự án |
| GET | `/favorites` | `[{targetType, targetId, createdAt}]` theo thứ tự thêm, bỏ dự án lưu trữ |
| GET / POST | `/projects/{id}/sections` | mảng (section lưu trữ cuối) / 201 `{id?, name, description?, afterId?}`; 20 section chưa lưu trữ hoặc 100 tổng → 422 |
| PATCH / DELETE | `/projects/{id}/sections/{sid}` | PATCH `{name?, description?}`; DELETE xoá vĩnh viễn kèm mọi task (204) |
| POST | `/projects/{id}/sections/{sid}/archive`, `/unarchive` | lưu trữ → task đang mở thành đã xong; khôi phục giữ task đã xong |
| GET | `/tasks?view=today\|upcoming[&from=][&to=]\|overdue\|completed[&since=][&until=]` hoặc `/tasks?projectId=[&completed=true]` | trang `{items, nextCursor}`; dự án trả task gốc đang mở kèm đủ cây con. `overdue` / khối Quá hạn = `due_day < hôm nay`; `upcoming` theo cửa sổ ngày (mặc định 14 ngày, tối đa 31, tầm xem 2 năm); `completed` = màn Completed (mọi dự án, mỗi task một dòng, mới xong trước) |
| GET | `/tasks/{id}/subtasks` | mọi con cháu (kể cả đã xong) theo thứ tự hiển thị |
| POST | `/tasks` | 201; `{id?, projectId?, sectionId?, parentId?, content, description?, priority?, dueDate? \| dueDatetime?, durationMinutes?, deadlineDate?, afterId?}`; vắng `projectId` = Inbox; 300 task đang mở / dự án → 422 |
| GET / PATCH / DELETE | `/tasks/{id}` | PATCH bắt buộc `version` (lệch → 409 `version-conflict` kèm `current` = task hiện tại); `null` = xoá due / duration / deadline; DELETE xoá vĩnh viễn kèm cây con (204) |
| POST | `/tasks/{id}/close`, `/reopen` | `{task, cascaded: [{id, version, completedAt}]}`; `reopen` nhận body tuỳ chọn `{undo: true}` (Undo: mở lại task + con cháu cùng `completion_batch_id`) |
| POST | `/tasks/{id}/move` `{projectId, sectionId?, parentId?, afterId?}` | đích lưu trữ → 422 `target-archived`; cha đã xong → 409 `parent-completed` |
| POST | `/tasks/{id}/duplicate` | 201 + bản sao gốc (chỉ chép con đang mở), đặt ngay dưới bản gốc |
| POST | `/tasks/batch` `{action: CLOSE\|REOPEN\|MOVE\|DELETE\|SCHEDULE\|PRIORITY, taskIds, …}` | tất cả hoặc không → `{affectedTaskIds}`; nhận `Idempotency-Key` (MOVE / DELETE không idempotent nếu thiếu); dự án / section lưu trữ → 409 kể cả DELETE; `SCHEDULE` / `MOVE` có task đã xong → 409 `task-completed` |
| POST | `/tasks/overdue/reschedule` `{date}` | dời mọi task trong khối Quá hạn (`due_day < hôm nay`; task có giờ giữ giờ) sang `date ≥ hôm nay` (sớm hơn → 400) → `{affectedTaskIds}` |
| POST | `/reorder` `{type: PROJECT\|SECTION\|TASK, id, afterId}` | `afterId` bắt buộc có khoá: `null` = lên đầu vùng chứa; Inbox → 403; không tăng `version` của task |

- **Chống gửi trùng:** `POST /projects`, `/projects/{id}/sections`, `/tasks`, `/tasks/{id}/duplicate`, `/tasks/batch`
  nhận header `Idempotency-Key` (1–64 ký tự). Hàng khoá được chèn trước khi xử lý (hàng đang xử lý sống 60 giây, kết quả
  2xx sống 24 giờ, lỗi thì xoá hàng). Gửi lại cùng khoá + cùng body → kết quả cũ kèm `Idempotent-Replay: true`; khác body
  → 422 `idempotency-mismatch`; đang xử lý → 409 `idempotency-in-progress`.
- **Giới hạn (gói Pro, `taskly.limits.*` trong `application.yml`):** 300 dự án đang hoạt động, 500 dự án, 300 task đang mở /
  dự án, 20 section chưa lưu trữ và 100 section tổng / dự án; vượt → 422 `limit-reached`. Đếm-rồi-chèn được bảo vệ bằng
  `SELECT … FOR UPDATE` hàng cha trong cùng transaction (test đua `LimitRaceApiTest`).
- **`version` của task** chỉ tăng khi nội dung đổi (sửa, close / reopen kể cả task lan truyền, move, duplicate, batch ghi
  nội dung, reschedule); `sort_order` / `reorder` / cây con theo `move` đổi bằng native UPDATE, không tăng `version`.
- **Job nền:** chỉ dọn `Idempotency-Key` hết hạn mỗi giờ (không có thùng rác — xoá là vĩnh viễn).
- **Test hiệu năng (không chạy mặc định):** `.\mvnw test "-Dtest=TaskViewPerformanceTest" "-Dtaskly.perf=true"`.

## Quy ước chung từ M1 (app di động phụ thuộc)
- **`id` do client sinh:** `POST projects|sections|tasks` nhận `id` UUID tuỳ chọn. Cùng `id` + cùng nội dung → 200 kèm header
  `Idempotent-Replay: true`; khác nội dung hoặc `id` của người khác → 409 `id-conflict`.
- **`Idempotency-Key`** (1–64 ký tự) cho các POST tạo / `duplicate` / `batch`: gửi lại cùng khoá + cùng body → kết quả cũ
  kèm `Idempotent-Replay: true`.
- **`version` / 409:** `PATCH /tasks/{id}` lệch `version` → 409 `version-conflict` kèm `current`. Các 409 `version-conflict`
  do đua ghi ở tầng DB (optimistic lock, deadlock) **không** kèm `current` → app tải lại rồi thử lại.
- **429 của `auth/refresh`** là lỗi tạm: không được đăng xuất người dùng, chờ `Retry-After` rồi thử lại.
- **Clock mili giây:** mọi thời điểm (`completedAt`, `createdAt`…) cắt ở mili giây để Hermes (mobile) parse được.
- **`requestId`:** ProblemDetail nào cũng có (cũng ở header `X-Request-Id`; client gửi `X-Request-Id` an toàn thì được dùng lại).
- **Giờ floating:** `dueDatetime` không có `Z` / offset, hiểu theo múi giờ tài khoản; `Z`, offset, `""` → 400.
- **Kiểu lỗi trong OpenAPI:** schema `ProblemDetail` (`status`, `detail`, `code?`, `requestId?`, `errors?`) có trong
  `/v3/api-docs` để `types.gen.ts` có kiểu lỗi.
- **Migration V2 đã được viết lại** (chưa phát hành): DB dev dựng từ trước V2 mới phải **dựng lại** (`docker compose down -v`
  rồi `up -d`), vì Flyway sẽ báo checksum không khớp.

## Giao diện cho mốc sau (M2–M5)
Các mốc sau dựa vào tên dưới đây (chi tiết: plan M1 backend part 1, mục cùng tên):
- `ApiException` (`withProperty`, `withCode`) + `ProblemCodes` — mốc sau THÊM hằng, không đổi hằng cũ.
- `UsageLimits` (`check`, `lockWorkspace`, `lockProject`, `lockProjects`, `lockUser`, `overrideMax` chỉ cho test) +
  `LimitKind`; khoá `taskly.limits.*` trong `application.yml`.
- `AccessPolicy` (`canRead`, `checkRead`, `checkWrite`, `readableWorkspaceIds`) — nơi duy nhất quyết định quyền.
- `CursorCodec` / `Page<T>` (phân trang keyset), `Created<T>` (`fresh` 201 / `replay` 200), `BaseEntity.assignId`.
- `TaskDueRules` (giờ floating, `isOverdue`, `isInOverdueBlock`, `reschedule`), `TaskViewRepository` (`COLUMNS`, `BASE_SELECT`,
  `findOne`, `findByIds`, `findSubtrees`, `page`), `TaskMapper.toResponse(TaskView, LocalDateTime)`.
- `IdempotencyFilter.COVERED` — M2 thêm `labels`, `filters`; M4 thêm `tasks/{id}/reminders`.
- `FavoriteService` (`add`, `remove`, `removeAllFor`), sự kiện `ProjectsDeletedEvent`, `TaskTimeClearedEvent`.
- `IntegrationTestBase` / `TestData` / `MutableClock` cho test; Flyway `V3` trở đi do M2+ thêm theo thứ tự gộp M1 → M5.

## API mốc M2 — Tìm kiếm, Label & Bộ lọc

Migration `V3__m2_search_labels_filters.sql` bật `unaccent` + `pg_trgm` (có sẵn trong `postgres:16`), hàm `f_unaccent`,
6 chỉ mục trigram và tạo `labels`, `task_labels`, `filters`, `view_options`. Mọi endpoint cần JWT.

| Method | Path | Kết quả |
|---|---|---|
| GET | `/search?q=&completed=false\|true&cursor=&limit=` | `{items: TaskResponse[], nextCursor}`; không phân biệt hoa thường / dấu; `*` đại diện; nhiều từ = AND; `completed=true` chỉ task đã xong (mới xong trước; gồm task trong section lưu trữ, loại dự án lưu trữ); `q` rỗng → rỗng; > 100 ký tự → 400 |
| GET | `/search/objects?q=` | `{projects, sections, labels, filters}` mỗi loại ≤ 20 (không có dự án / section lưu trữ) |
| GET / POST | `/labels` | danh sách (không phân trang) / tạo `{id?, name, color?}` (`Idempotency-Key`; `id` UUID do client sinh: đã có + cùng nội dung → 200 kèm `Idempotent-Replay: true`, khác / của người khác → 409 `id-conflict`; trùng tên → 409 `label-name-taken`; thứ 501 → 422 `limit-reached`, khoá chống đua) |
| PATCH / DELETE | `/labels/{id}` | sửa `{name?, color?}` / xoá (gỡ khỏi mọi task) |
| PUT / DELETE | `/labels/{id}/favorite` | yêu thích → `LabelResponse` |
| GET / POST | `/filters` | danh sách / tạo `{id?, name, query, color?}` (`id` client như label; query sai → 400 `filter-syntax` + `position`; thứ 151 → 422) |
| PATCH / DELETE | `/filters/{id}`, PUT / DELETE `/filters/{id}/favorite` | như label |
| POST | `/filters/validate` | `{parts: string[]}` (tách theo `,` cấp ngoài) hoặc 400 `filter-syntax` |
| GET | `/tasks?labelId=` · `/tasks?filter=` | task đang mở có label / khớp một danh sách filter; phân trang; nhận Display |
| GET | `/tasks?view=today\|upcoming` · `/tasks?projectId=` + Display | sort / group / lọc phía server |
| GET / PUT / DELETE | `/view-options`, `/view-options/{viewKey}` | Display lưu theo tài khoản (server không tự áp khi liệt kê) |
| POST | `/reorder` `{type: LABEL\|FILTER, id, afterId}` | sắp xếp label / filter |
| POST | `/tasks/batch` `{action: ADD_LABELS\|REMOVE_LABELS, taskIds, labelIds}` | gắn / bỏ label hàng loạt |
| POST / PATCH | `/tasks` | thêm `labelIds` (PATCH: vắng = giữ, `[]` = bỏ hết, mảng = thay toàn bộ); `TaskResponse.labelIds` |

**Display** (`sort=manual|date|deadline|priority|name|added`, `order=asc|desc`, `group=none|date|deadline|priority|project|label`,
`filterPriority=1..4`, `filterLabelId=<uuid>`, `filterDue=today|this_week|next_7_days|this_month|next_30_days|no_date|overdue`):
chỉ cho phép theo bảng spec 12.7 (sai → 400); server sắp **nhóm → khoá sắp → id**; không có ngày / deadline / label luôn
cuối. Chỉ dùng **đúng một** trong `view`, `projectId`, `labelId`, `filter`. `filterDue=no_date` ở Today → 400; `no_date` /
`overdue` ở Upcoming → 400; `filterDue=overdue` và filter `overdue` / `od` theo `isOverdue` (giờ đã qua) — **khác** khối
Quá hạn của Today (`due_day < hôm nay`). `view=completed` (màn Completed toàn cục) là của M1 và không nhận Display.

**Cú pháp filter:** `|` `&` `!` `( )` `,` `\` `*`; `today` `tomorrow` `yesterday` `overdue`/`od` `no date` `no time` `date:` /
`date before:` / `date after:` (ngày hoặc ngày-giờ floating `yyyy-MM-ddTHH:mm[:ss]`) `next N days` `deadline:` … `no deadline` `p1`–`p4` `no priority` `#Dự án` `##Dự án`
`/Section` `/*` `@label` / `%label` `no labels` `search:` `created:` … `subtask` `recurring`; bí danh tiếng Việt `hôm nay`,
`ngày mai`, `hôm qua`, `quá hạn`, `không có ngày`. Filter không trả task đã xong.

Test hiệu năng 10 000 task (không chạy mặc định): `.\mvnw test "-Dtest=M2PerformanceTest" "-Dtaskly.perf=true" "-Dgroups=perf"`.
Sau khi đổi API: ở repo `mobile` chạy `npm run gen:api` + `npx tsc --noEmit`.
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
| POST | `/auth/2fa/verify` `{twoFactorToken, code}` | không | 200 `AuthResponse`; token sai / hết hạn hoặc mã sai 401; quá 5 lần / 5 phút / tài khoản 429 |
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
- Quên mật khẩu: tối đa 5 yêu cầu / giờ / email (`app.rate-limit.password-reset.*`, bộ đếm trong bộ nhớ); vượt thì vẫn
  trả 200 nhưng không gửi thư (200 hay 429 khi vượt: chờ người dùng chốt).
- SMTP cấu hình bằng `MAIL_HOST`, `MAIL_PORT`, `MAIL_FROM` trong `.env` (mặc định Mailpit `localhost:1025`).
- **2FA (TOTP RFC 6238, 6 số, 30 giây, lệch ±1 bước):** đăng nhập tài khoản bật 2FA trả **401** `code:
  "two-factor-required"` kèm `twoFactorToken` (5 phút) → gọi `/auth/2fa/verify` với mã 6 số hoặc một mã khôi phục
  (`xxxx-xxxx`, dùng một lần). Mỗi mã TOTP chỉ dùng được một lần. Giới hạn THEO TÀI KHOẢN, **5 lần / 5 phút**, vượt → **429** kèm `Retry-After` (kể cả khi mã đúng): mã 2FA dùng chung một bộ đếm cho `auth/2fa/verify`, `me/2fa/enable`, `me/2fa/disable`, `me/2fa/recovery-codes`, `DELETE me` (`app.rate-limit.two-factor.*`); sai mật khẩu ở `DELETE me` / `me/2fa/disable` có bộ đếm riêng (`app.rate-limit.account-password.*`). Bộ đếm trong bộ nhớ (một instance). Secret lưu mã hoá AES-256-GCM bằng
  `TOTP_ENCRYPTION_KEY` trong `.env` (thiếu thì server không khởi động; mất khoá → chỉ đăng nhập được bằng mã khôi phục).
  `UserResponse` có thêm `twoFactorEnabled`. Đặt lại mật khẩu không tắt 2FA.
- Lỗi có `code` (để app phân biệt, không dựa vào câu chữ): `two-factor-required`, `two-factor-already-enabled`,
  `two-factor-not-setup`, `password-required`; riêng `auth/2fa/verify` (401): `two-factor-code-invalid` (mã sai, token còn
  sống — nhập lại) và `two-factor-token-invalid` (token hết hạn / đã dùng / không hợp lệ — đăng nhập lại).

## API mốc M4 — nhắc việc

App đặt lịch nhắc cục bộ trên máy (`expo-notifications`); server lưu cài đặt + nhắc riêng và tính sẵn lịch nhắc.
Giờ nhắc là giờ **floating**: xuất `"yyyy-MM-dd'T'HH:mm:ss"` (không `Z` / offset), nhập `yyyy-MM-ddTHH:mm` hoặc có giây;
đổi sang thời điểm tuyệt đối bằng **`users.time_zone`** (`me/settings.timeZone`), không dùng múi giờ máy.

| Method | Path | JWT | Kết quả |
|---|---|---|---|
| GET | `/tasks/{taskId}/reminders` | có | 200 `ReminderResponse[] {id, taskId, type, at, minuteOffset, createdAt}` (nhắc riêng của mình, theo thứ tự tạo; đọc được cả khi task đã xong / dự án lưu trữ) |
| POST | `/tasks/{taskId}/reminders` | có | `{type: "ABSOLUTE", at: "yyyy-MM-ddTHH:mm"}` hoặc `{type: "RELATIVE", minuteOffset: 0..10080}` → 201; `Idempotency-Key`; `at` đã qua / có `Z` hoặc offset / `RELATIVE` cho task không có giờ / field không khớp `type` → 400; task đã xong → 409 `task-completed`; dự án / section lưu trữ → 409 `project-archived` / `section-archived`; vượt 700 nhắc → 422 `limit-reached` (khoá hàng `users` chống đua) |
| DELETE | `/tasks/{taskId}/reminders/{reminderId}` | có | 204 (kể cả task đã xong); không thấy → 404; dự án / section lưu trữ → 409 |
| GET | `/tasks/reminders?limit=` | có | 200 `ReminderOccurrence[] {key, taskId, reminderId, kind: AUTO\|ABSOLUTE\|RELATIVE, fireAt, content}` — task chưa xong, không thuộc dự án / section lưu trữ; `fireAt ≥ bây giờ`; sắp theo `fireAt`; cùng task + cùng `fireAt` chỉ một (ưu tiên nhắc riêng); `limit` 1–50 (mặc định 50) |

Task mất giờ (đổi sang chỉ ngày / bỏ due) → nhắc tương đối của nó bị xoá bởi `ReminderService` (nghe `TaskTimeClearedEvent`
của `TaskService.applyDue`, cùng transaction); mọi đường ghi due mới phải đi qua `applyDue`. Xoá task / dự án / section xoá
kèm nhắc; sao chép task không chép nhắc. Migration: `V5__m4_reminders.sql` (V3 / V4 của M2 / M3 chưa có trên nhánh này — Flyway chấp nhận khoảng trống trên DB mới; DB dev đã chạy phiên bản cao hơn cần DB riêng hoặc `out-of-order`).

> Chưa có trong nhánh `feature/m4-backend` (chờ M3): `remindersEnabled` / `autoReminderMinutes` trong `me/settings` (cột `users` đã có ở V5) — plan Task 4.

## Đăng nhập Google (mốc M5)

### Cấu hình — người dùng tự làm (Claude không đăng nhập Google Cloud hộ)
1. Google Cloud Console → tạo OAuth consent screen, một **Web client ID** và một **Android client ID** (package name
   của app + SHA-1 keystore debug — xem README của repo `mobile`).
2. Trong `.env` (chép từ `.env.example`), điền `GOOGLE_CLIENT_IDS` = **Web client ID**. App Android truyền Web client
   ID làm `serverClientId`, nên `aud` của ID token là Web client ID. Nhiều ID cách nhau bởi dấu phẩy.
3. Để trống thì server vẫn chạy, log cảnh báo lúc khởi động, và mọi lần đăng nhập Google bị từ chối (401).

Server tự tải khoá công khai của Google ở `https://www.googleapis.com/oauth2/v3/certs` (cần mạng; không tải được →
503). Kiểm `iss` ∈ {`accounts.google.com`, `https://accounts.google.com`}, `aud` ∈ `GOOGLE_CLIENT_IDS`, `exp`, chữ ký
RS256; tài khoản gắn theo claim `sub`, không theo email. Test tự động dùng khoá RSA giả, không gọi Google.

### API
| Method | Path | JWT | Kết quả |
|---|---|---|---|
| POST | `/auth/google` `{idToken}` | không | 200 `AuthResponse` (tạo tài khoản không mật khẩu nếu email mới); 401 token sai / hết hạn / sai aud; 401 `code=two-factor-required` kèm `twoFactorToken` khi tài khoản bật 2FA (gọi tiếp `POST /auth/2fa/verify`); 403 email Google chưa xác minh; 409 `code=link-required` kèm `linkToken`; 429 quá 10 lần / phút / IP; 503 không kết nối được Google |
| POST | `/me/identities/google` `{idToken}` hoặc `{linkToken}` | có | 204 (gửi lại đúng Google đã liên kết cũng 204); 400 token không dùng được / hết hạn / sai aud, email trong `linkToken` khác email tài khoản, hoặc gửi sai số trường (**không** 401 — app coi 401 là hết phiên); 403 email Google chưa xác minh; 409 Google đã gắn tài khoản khác / tài khoản đã gắn Google khác |
| DELETE | `/me/identities/google` | có | 204; 404 chưa liên kết Google; 409 `code=password-required` tài khoản chưa có mật khẩu |

409 `link-required`:
```json
{"type": "about:blank", "title": "Xung đột dữ liệu", "status": 409,
 "detail": "Email này đã có tài khoản Taskly. Hãy đăng nhập bằng mật khẩu để liên kết Google",
 "instance": "/api/v1/auth/google", "code": "link-required", "linkToken": "<JWT, hạn 10 phút>"}
```
App: đăng nhập bằng mật khẩu → gọi `POST /me/identities/google {linkToken}`. Tài khoản chỉ có Google đặt mật khẩu qua
Quên mật khẩu (M3); đăng nhập mật khẩu vào tài khoản chưa có mật khẩu trả 401 giống sai mật khẩu.

Tài khoản bật 2FA: đăng nhập Google cũng bị đòi mã như đăng nhập mật khẩu (`two-factor-required` → `auth/2fa/verify`);
liên kết Google từ Cài đặt không đòi thêm mã. Xoá tài khoản (`DELETE /me`, M3) xoá luôn liên kết Google; tài khoản chỉ có
Google phải đặt mật khẩu trước (409 `code=password-required`). Tắt 2FA với tài khoản chỉ có Google chỉ cần mã.

`linkToken` là JWT HS256 `typ = google-link`, `aud = taskly-google-link`, hạn 10 phút (spec 3.1): không dùng được làm access token hay `twoFactorToken`, và ngược lại access token / `twoFactorToken` đưa vào `me/identities/google {linkToken}` → 400.
