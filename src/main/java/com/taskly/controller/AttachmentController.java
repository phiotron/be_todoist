package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.comment.AttachmentLinkResponse;
import com.taskly.dto.comment.AttachmentResponse;
import com.taskly.mapper.CommentMapper;
import com.taskly.security.CurrentUser;
import com.taskly.service.AttachmentService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * File đính kèm comment (M8b, plan F2). Tải lên: multipart, trường "file" (≤ 100 MB). Tải về: xin link ký (cần Bearer)
 * rồi GET link đó (KHÔNG cần Bearer — SecurityConfig cho phép riêng đường dẫn download). Ảnh an toàn hiện inline, mọi
 * kiểu khác buộc tải về (attachment + nosniff) để file HTML không chạy trên origin của API.
 */
@RestController
@RequestMapping("/api/v1/attachments")
public class AttachmentController {

    /** SecurityConfig.permitAll — link tải đã ký thay cho Bearer. */
    public static final String DOWNLOAD_PATH = "/api/v1/attachments/*/download";

    private final AttachmentService attachmentService;

    public AttachmentController(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    public AttachmentResponse upload(@AuthenticationPrincipal Jwt jwt,
                                     @RequestPart(name = "file", required = false) MultipartFile file) {
        return attachmentService.upload(CurrentUser.id(jwt), file);
    }

    @GetMapping("/{attachmentId}/link")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    public AttachmentLinkResponse link(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID attachmentId) {
        return attachmentService.link(CurrentUser.id(jwt), attachmentId);
    }

    @GetMapping("/{attachmentId}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable UUID attachmentId, @RequestParam long expires,
                                                        @RequestParam String sig) {
        AttachmentService.Download download = attachmentService.open(attachmentId, expires, sig);
        var row = download.attachment();
        boolean inline = CommentMapper.isImage(row.contentType());
        ContentDisposition disposition = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(row.fileName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(inline ? MediaType.parseMediaType(row.contentType()) : MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(row.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                .body(new InputStreamResource(download.content()));
    }
}
