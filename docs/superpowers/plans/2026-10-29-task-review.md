# Chờ duyệt (task review) cho dự án nhóm — kế hoạch ngắn

> For agentic workers: dùng superpowers:test-driven-development; mỗi bước đỏ -> xanh.

**Goal:** Member/Guest tick hoàn thành ở dự án TEAM -> PENDING (chờ Admin duyệt); Admin duyệt/trả lại.
**Architecture:** V22 thêm 3 cột `tasks`; luật quyền gom ở `AccessPolicy` (`mustRequestReview`, `checkReviewDecision`,
`checkReopenCompleted`); logic ở `TaskReviewService` (yêu cầu/huỷ/thông báo/nhật ký) + `TaskCompletionService`
(approve/reject, chèn cổng PENDING vào `closeOrAdvanceWithin`/`reopenIds`); sync + REST dùng chung.
**Tech:** Spring Boot 4.1.1, JPA + JdbcTemplate, Flyway, Testcontainers.

## Nhiệm vụ (mỗi nhiệm vụ: viết test đỏ -> code -> xanh)
1. V22 + entity Task + TaskView/TaskMapper/TaskResponse (+OpenAPI) — test: migration áp trên dữ liệu có sẵn, trường trong response/sync.
2. AccessPolicy + ProjectCapabilities.canReview — test: capabilities, 403 reopen việc người khác.
3. Member tick -> PENDING, Admin tick -> completed, tick lại -> huỷ, việc lặp PENDING — test API.
4. approve/reject (REST + sync `task_review_approve|reject`), 409 `review-not-pending`, 403 `team-admin-only`, lặp sinh kỳ kế, karma cho người yêu cầu.
5. `GET tasks?view=review` + `GET tasks/review/count`, phạm vi theo workspace.
6. Thông báo REVIEW_REQUESTED/APPROVED/REJECTED + activity review_* (CHECK ở V22), i18n vi/en.
7. Sửa test cũ (Member tick kỳ vọng completed), chạy cả bộ, package.

Ghi chú lệch hợp đồng: tên lệnh sync thực tế là snake_case (`task_close`, `task_reopen`), lệnh mới `task_review_approve|reject`
(args `{id}`, nhận thêm `taskId`); không có cột `completed_by` -> Member không mở lại việc đã hoàn thành.
