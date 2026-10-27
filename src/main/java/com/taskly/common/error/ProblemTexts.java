package com.taskly.common.error;

/**
 * MỌI chữ lỗi tiếng Việt của API: title theo mã HTTP, detail mặc định cho lỗi framework, và detail/message
 * nghiệp vụ. Là hằng compile-time nên dùng được trong annotation (@Size(message = ProblemTexts.X)).
 * App mobile hiển thị nguyên văn; tình huống app cần PHÂN BIỆT thì dùng thêm ProblemCodes (spec 12.10).
 * Thêm text mới: thêm một hằng vào đúng nhóm bên dưới.
 */
public final class ProblemTexts {

    // ---- Chung
    public static final String SESSION_INVALID = "Phiên đăng nhập không hợp lệ hoặc đã hết hạn";
    public static final String FORBIDDEN = "Bạn không có quyền thực hiện thao tác này";
    public static final String INVALID_DATA = "Dữ liệu gửi lên không hợp lệ";
    public static final String INVALID_VALUE = "Giá trị không hợp lệ";
    public static final String DATA_CONFLICT = "Dữ liệu bị trùng hoặc xung đột với bản ghi đã có";
    public static final String CONCURRENT_UPDATE = "Dữ liệu đã được thay đổi ở nơi khác, hãy tải lại";
    public static final String TOO_MANY_REQUESTS = "Bạn thao tác quá nhanh, vui lòng thử lại sau";
    public static final String SERVER_ERROR = "Đã có lỗi phía máy chủ, vui lòng thử lại sau";
    public static final String LIMIT_REACHED = "Đã đạt giới hạn cho phép";
    public static final String EMAIL_TAKEN = "Email đã được dùng";
    public static final String ID_CONFLICT = "Mã này đã được dùng cho dữ liệu khác";
    public static final String DESCRIPTION_SIZE = "Mô tả tối đa 16383 ký tự";

    // ---- Phân trang
    public static final String INVALID_CURSOR = "Con trỏ phân trang không hợp lệ";
    public static final String INVALID_LIMIT = "limit phải từ 1 đến 200";

    // ---- Dự án
    public static final String PROJECT_NOT_FOUND = "Không tìm thấy dự án";
    public static final String PROJECT_NAME_SIZE = "Tên dự án phải từ 1 đến 120 ký tự";
    public static final String PROJECT_ARCHIVED = "Dự án đã lưu trữ";
    public static final String INBOX_PROTECTED = "Không thể xoá, lưu trữ, sửa, chuyển hay yêu thích Inbox";
    public static final String INBOX_NOT_NESTABLE = "Inbox không thể làm dự án cha hay dự án con";
    public static final String PROJECT_TOO_DEEP = "Dự án con tối đa 3 cấp";
    public static final String PROJECT_CYCLE = "Không thể đặt dự án làm con của chính nó hoặc của dự án con của nó";
    public static final String TARGET_ARCHIVED = "Nơi chuyển đến đang được lưu trữ";
    public static final String CROSS_WORKSPACE_MOVE = "Không thể chuyển sang workspace khác";
    public static final String MOVE_PARENT_ID_REQUIRED = "Thiếu parentId (gửi null để chuyển thành dự án gốc)";

    // ---- Section
    public static final String SECTION_NOT_FOUND = "Không tìm thấy section";
    public static final String SECTION_NAME_SIZE = "Tên section phải từ 1 đến 2048 ký tự";
    public static final String SECTION_NOT_IN_PROJECT = "Section không thuộc dự án đích";
    public static final String SECTION_ARCHIVED = "Section đã lưu trữ";

    // ---- Công việc
    public static final String TASK_NOT_FOUND = "Không tìm thấy công việc";
    public static final String PROJECT_ID_REQUIRED = "Thiếu projectId";
    public static final String TASK_CONTENT_SIZE = "Tên công việc phải từ 1 đến 500 ký tự";
    public static final String PRIORITY_RANGE = "Ưu tiên phải từ 1 đến 4";
    public static final String DUE_CONFLICT = "Chỉ được đặt một trong dueDate hoặc dueDatetime";
    public static final String DURATION_RANGE = "Thời lượng phải từ 1 đến 1440 phút";
    public static final String DURATION_NEEDS_TIME = "Chỉ đặt được thời lượng cho công việc có giờ";
    public static final String VERSION_REQUIRED = "Thiếu version";
    public static final String VERSION_CONFLICT = "Đã có thay đổi ở nơi khác";
    public static final String SUBTASK_TOO_DEEP = "Công việc con tối đa 4 cấp";
    public static final String SUBTASK_CYCLE = "Không thể đặt công việc làm con của chính nó hoặc của con cháu nó";
    public static final String PARENT_NOT_IN_PROJECT = "Công việc cha phải cùng dự án";
    public static final String PARENT_COMPLETED = "Công việc cha đã hoàn thành";
    public static final String PARENT_NOT_IN_SECTION = "Công việc con phải cùng section với công việc cha";
    public static final String TASK_COMPLETED = "Công việc đã hoàn thành";

    // ---- Danh sách công việc
    public static final String LIST_QUERY_INVALID = "Cần đúng một trong hai tham số view hoặc projectId";
    public static final String FROM_ONLY_UPCOMING = "Tham số from và to chỉ dùng với view=upcoming";
    public static final String SINCE_ONLY_COMPLETED = "Tham số since và until chỉ dùng với view=completed";
    public static final String COMPLETED_RANGE_INVALID = "since không được sau until";
    public static final String UPCOMING_WINDOW_INVALID = "view=upcoming chỉ xem tối đa 31 ngày mỗi lần và không quá 2 năm kể từ hôm nay";
    public static final String COMPLETED_ONLY_PROJECT = "Tham số completed chỉ dùng với projectId";

    // ---- Sắp xếp
    public static final String REORDER_TYPE_REQUIRED = "Thiếu type";
    public static final String REORDER_ID_REQUIRED = "Thiếu id";
    public static final String AFTER_ID_NOT_IN_CONTAINER = "afterId phải cùng vùng chứa";
    public static final String REORDER_AFTER_ID_REQUIRED = "Thiếu afterId (gửi null để đặt lên đầu vùng chứa)";

    // ---- Chọn nhiều, Reschedule
    public static final String BATCH_ACTION_REQUIRED = "Thiếu action";
    public static final String BATCH_IDS_SIZE = "taskIds phải có từ 1 đến 200 id";
    public static final String BATCH_IDS_DUPLICATE = "taskIds không được trùng nhau";
    public static final String BATCH_FIELDS_INVALID = "Các trường gửi lên không khớp với action";
    public static final String RESCHEDULE_DATE_REQUIRED = "Thiếu date";
    public static final String RESCHEDULE_DATE_PAST = "Ngày mới không được trước hôm nay";

    // ---- Chống gửi trùng
    public static final String IDEMPOTENCY_KEY_INVALID = "Idempotency-Key phải từ 1 đến 64 ký tự";
    public static final String IDEMPOTENCY_IN_PROGRESS = "Yêu cầu trước với cùng Idempotency-Key đang được xử lý";
    public static final String IDEMPOTENCY_MISMATCH = "Idempotency-Key đã được dùng cho nội dung khác";

    // ---- M2: tìm kiếm, label, filter, Display (ID_CONFLICT / UPCOMING_WINDOW_INVALID đã có từ M1)
    public static final String SEARCH_QUERY_TOO_LONG = "Từ khoá tìm kiếm tối đa 100 ký tự";
    public static final String LABEL_NOT_FOUND = "Không tìm thấy label";
    public static final String LABEL_NAME_SIZE = "Tên label phải từ 1 đến 60 ký tự";
    public static final String LABEL_NAME_CHARS =
            "Tên label không được chứa khoảng trắng và các ký tự @ % # / , ( ) | & ! \\ *";
    public static final String LABEL_NAME_TAKEN = "Đã có label cùng tên";
    public static final String LABELS_PER_TASK = "Task đã đạt số label tối đa";
    public static final String FILTER_NOT_FOUND = "Không tìm thấy filter";
    public static final String FILTER_NAME_SIZE = "Tên filter phải từ 1 đến 120 ký tự";
    public static final String FILTER_QUERY_SIZE = "Truy vấn filter phải từ 1 đến 1024 ký tự";
    public static final String FILTER_SYNTAX = "Truy vấn filter sai cú pháp";
    public static final String VIEW_KEY_INVALID = "Khoá view không hợp lệ";
    public static final String DISPLAY_INVALID = "Tuỳ chọn hiển thị không hợp lệ với view này";
    public static final String TASK_LIST_SELECTOR =
            "Dùng đúng một trong view, projectId, labelId, filter (completed chỉ đi với projectId, from / to chỉ đi với view=upcoming, since / until chỉ đi với view=completed)";
    public static final String BATCH_LABEL_IDS =
            "labelIds bắt buộc với ADD_LABELS / REMOVE_LABELS và chỉ dùng cho hai thao tác này";

    private ProblemTexts() {
    }

    public static String title(int status) {
        return switch (status) {
            case 400 -> "Dữ liệu không hợp lệ";
            case 401 -> "Chưa xác thực";
            case 403 -> "Không có quyền";
            case 404 -> "Không tìm thấy";
            case 405 -> "Phương thức không được hỗ trợ";
            case 409 -> "Xung đột dữ liệu";
            case 415 -> "Định dạng dữ liệu không được hỗ trợ";
            case 422 -> "Không thể xử lý yêu cầu";
            case 429 -> "Quá nhiều yêu cầu";
            case 500 -> "Lỗi máy chủ";
            default -> "Lỗi";
        };
    }

    /** Thay detail tiếng Anh của Spring (vd "Failed to read request") bằng câu tiếng Việt. */
    public static String defaultDetail(int status) {
        return switch (status) {
            case 400 -> "Dữ liệu gửi lên không đọc được hoặc không hợp lệ";
            case 403 -> FORBIDDEN;
            case 404 -> "Không tìm thấy đường dẫn yêu cầu";
            case 405 -> "Đường dẫn này không hỗ trợ phương thức HTTP đã gửi";
            case 415 -> "Chỉ chấp nhận dữ liệu JSON";
            case 429 -> TOO_MANY_REQUESTS;
            default -> "Đã có lỗi xảy ra, vui lòng thử lại sau";
        };
    }
}
