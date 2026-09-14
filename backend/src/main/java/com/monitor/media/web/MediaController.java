package com.monitor.media.web;

import com.monitor.audit.annotation.AuditAction;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * <p>{@code /media/{mediaId}/content} 返回的是二进制本体，走 {@code ResponseEntity<Resource>}
 * 而非统一信封——前端 {@code <img>} 直接引；该路径允许 {@code ?token=} 鉴权
 * （{@code <img>} 带不了请求头，见 {@code JwtAuthFilter}）。
 * {@code {mediaId}} 是字符串编码（{@code M001}），见 {@code MediaCode}。</p>
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

    /** 影像内容（二进制）。路径变量是<b>对外编码</b>（{@code M001}），也容忍裸数字 id。 */
    @GetMapping("/media/{mediaId}/content")
    public ResponseEntity<Resource> content(@PathVariable String mediaId) {
        Media media = mediaService.requireByCode(mediaId);
        Resource resource = mediaService.content(media);
        return ResponseEntity.ok()
                .contentType(contentTypeOf(media))
                .body(resource);
    }

    /**
     * 删除影像（**逻辑删除**，契约 §7）。
     *
     * <p>角色取 {@code ADMIN}/{@code MAINTAINER}：与设备-测点绑定/解绑同一个口径
     * （见 {@code DeviceController}），因为影像的实际使用场景就是运维上传现场照片——
     * 传错了该由传的人自己撤，不该非要找管理员。值班/研判是**读**影像的角色，
     * 不给他们删的权限。</p>
     *
     * <p>{@code @AuditAction} 必须挂：软删意味着「删了还在库里」，不留痕的话
     * 「这张图为什么不见了」在库层面无从查证——审计日志是软删唯一的补位手段。</p>
     */
    @DeleteMapping("/media/{mediaId}")
    @PreAuthorize("hasAnyRole('ADMIN','MAINTAINER')")
    // targetIdFromStringArg：路径变量是编码 "M1024" 而非数字主键，
    // 不声明的话审计行会缺 target_id（详见该属性的注释）
    @AuditAction(action = "删除影像", targetIdFromStringArg = true)
    public Result<Void> delete(@PathVariable String mediaId) {
        mediaService.delete(mediaId);
        return Result.ok();
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
