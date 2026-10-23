package com.taskly.common.error;

/** Chữ tiếng Việt cho ProblemDetail: title theo mã HTTP, và detail mặc định cho lỗi do framework sinh. */
final class ProblemTexts {

    private ProblemTexts() {
    }

    static String title(int status) {
        return switch (status) {
            case 400 -> "Dữ liệu không hợp lệ";
            case 401 -> "Chưa xác thực";
            case 403 -> "Không có quyền";
            case 404 -> "Không tìm thấy";
            case 405 -> "Phương thức không được hỗ trợ";
            case 409 -> "Xung đột dữ liệu";
            case 415 -> "Định dạng dữ liệu không được hỗ trợ";
            case 500 -> "Lỗi máy chủ";
            default -> "Lỗi";
        };
    }

    /** Thay detail tiếng Anh của Spring (vd "Failed to read request") bằng câu tiếng Việt. */
    static String defaultDetail(int status) {
        return switch (status) {
            case 400 -> "Dữ liệu gửi lên không đọc được hoặc không hợp lệ";
            case 404 -> "Không tìm thấy đường dẫn yêu cầu";
            case 405 -> "Đường dẫn này không hỗ trợ phương thức HTTP đã gửi";
            case 415 -> "Chỉ chấp nhận dữ liệu JSON";
            default -> "Đã có lỗi xảy ra, vui lòng thử lại sau";
        };
    }
}
