package com.monitor.media.web;

import com.monitor.auth.security.SecurityUser;
import com.monitor.common.Result;
import com.monitor.media.dto.MediaUploadVO;
import com.monitor.media.dto.MediaVO;
import com.monitor.media.entity.Media;
import com.monitor.media.service.MediaService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 媒体接口（《B侧接口契约_M0》§7）。
 *
 * <p>{@code /media/{id}/content} 返回的是二进制本体，走 {@code ResponseEntity<Resource>}
 * 而非统一信封——前端 {@code <img>} 直接引；该路径允许 {@code ?token=} 鉴权
 * （{@code <img>} 带不了请求头，见 {@code JwtAuthFilter}）。</p>
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SuppressWarnings("null")
public class MediaController {

    private final MediaService mediaService;

    /** 上传影像并挂到测点（multipart：file、pointId、takenAt、note）。 */
    @PostMapping(value = "/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<MediaUploadVO> upload(@RequestParam("file") MultipartFile file,
                                        @RequestParam("pointId") Long pointId,
                                        @RequestParam(required = false) String takenAt,
                                        @RequestParam(required = false) String note,
                                        @AuthenticationPrincipal SecurityUser currentUser) {
        String uploader = currentUser == null ? null : currentUser.getUsername();
        return Result.ok(mediaService.upload(file, pointId, takenAt, note, uploader));
    }

    /** 测点影像列表。 */
    @GetMapping("/points/{pointId}/media")
    public Result<List<MediaVO>> listByPoint(@PathVariable Long pointId) {
        return Result.ok(mediaService.listByPoint(pointId));
    }

    /** 影像内容（二进制）。 */
    @GetMapping("/media/{id}/content")
    public ResponseEntity<Resource> content(@PathVariable Long id) {
        Media media = mediaService.require(id);
        Resource resource = mediaService.content(media);
        return ResponseEntity.ok()
                .contentType(contentTypeOf(media))
                .body(resource);
    }

    /** 库内 MIME 只作提示：解析失败退回二进制流，不因一个字段让图片读不出来。 */
    private static MediaType contentTypeOf(Media media) {
        if (media.getMimeType() != null) {
            try {
                return MediaType.parseMediaType(media.getMimeType());
            } catch (Exception ignored) {
                // 落到下面的兜底
            }
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
