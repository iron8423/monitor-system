package com.monitor.media.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.monitor.common.exception.BizException;
import com.monitor.common.util.Times;
import com.monitor.media.MediaCode;
import com.monitor.media.dto.MediaUploadVO;
import com.monitor.media.dto.MediaVO;
import com.monitor.media.entity.Media;
import com.monitor.media.mapper.MediaMapper;
import com.monitor.project.entity.MonitorPoint;
import com.monitor.project.mapper.MonitorPointMapper;
import com.monitor.scope.service.DataScopeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 媒体上传 / 列表 / 读取（《B侧接口契约_M0》§7）。
 *
 * <p>落盘两条纪律：<b>文件名由服务端生成</b>（UUID），客户端原文件名只留档展示，
 * 不参与路径拼接；<b>扩展名取自白名单</b>（优先 Content-Type），避免把任意文件写成 {@code .jpg}。
 * 读取时再校验一次文件落在上传根目录内，防库内路径被改写后读到任意文件。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class MediaService {

    private static final String OBJECT_PREFIX = "media";

    /** Content-Type -> 扩展名。只认这几种图片，其余一律拒。 */
    private static final Map<String, String> EXT_BY_MIME = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp",
            "image/gif", "gif",
            "image/bmp", "bmp");

    /** Content-Type 缺失/不标准时的兜底：按原文件名后缀，且必须在白名单内。 */
    private static final Set<String> ALLOWED_EXT = Set.of("jpg", "jpeg", "png", "webp", "gif", "bmp");

    private final MediaMapper mediaMapper;
    private final MonitorPointMapper pointMapper;
    private final DataScopeService dataScope;

    @Value("${monitor.upload.dir:./data/media}")
    private String uploadDir;

    /** 上传影像并挂到测点。 */
    public MediaUploadVO upload(MultipartFile file, Long pointId, String takenAt, String note, String currentUser) {
        if (file == null || file.isEmpty()) {
            throw new BizException("上传文件为空");
        }
        MonitorPoint point = requirePoint(pointId);
        String ext = resolveExt(file);

        // 落盘 = <uploadDir>/<pointCode>/<uuid>.<ext>；objectKey 只是对外逻辑键，
        // 带上 media/ 命名空间，故不重复建目录（否则会得到 data/media/media/… 这种双层目录）
        Path root = root();
        Path dir = root.resolve(point.getCode()).normalize();
        Path target = dir.resolve(UUID.randomUUID().toString().replace("-", "") + "." + ext).normalize();
        if (!dir.startsWith(root) || !target.startsWith(root)) {
            throw new BizException("非法的存储路径");
        }

        try {
            Files.createDirectories(dir);
            file.transferTo(target);
        } catch (IOException e) {
            log.error("影像写入失败 point={} file={}", point.getCode(), file.getOriginalFilename(), e);
            throw new BizException(500, "影像写入失败: " + e.getMessage());
        }

        LocalDateTime taken = Times.parse(takenAt, "takenAt");
        Media media = new Media();
        media.setPointId(point.getId());
        media.setFileName(file.getOriginalFilename());
        media.setFilePath(target.toString());
        media.setObjectKey(OBJECT_PREFIX + "/" + point.getCode() + "/" + target.getFileName());
        media.setMimeType(file.getContentType());
        media.setFileSize(file.getSize());
        media.setTakenAt(taken == null ? LocalDateTime.now() : taken);
        media.setNote(note);
        media.setUploadedBy(currentUser);
        mediaMapper.insert(media);

        // url 依赖自增主键，插入后才能拼；同事务内回填，避免出现没有 url 的记录
        media.setUrl(contentUrl(media.getId()));
        mediaMapper.updateById(media);

        MediaUploadVO vo = new MediaUploadVO();
        vo.setMediaId(MediaCode.of(media.getId()));
        vo.setObjectKey(media.getObjectKey());
        vo.setUrl(media.getUrl());
        vo.setPointId(media.getPointId());
        vo.setTakenAt(Times.iso(media.getTakenAt()));
        return vo;
    }

    /** 测点影像列表，按拍摄时间倒序。 */
    public List<MediaVO> listByPoint(Long pointId) {
        requirePoint(pointId);
        List<Media> rows = mediaMapper.selectList(new LambdaQueryWrapper<Media>()
                .eq(Media::getPointId, pointId)
                .orderByDesc(Media::getTakenAt)
                .orderByDesc(Media::getId));

        List<MediaVO> items = new ArrayList<>(rows.size());
        for (Media m : rows) {
            MediaVO vo = new MediaVO();
            vo.setMediaId(MediaCode.of(m.getId()));
            vo.setUrl(m.getUrl() == null ? contentUrl(m.getId()) : m.getUrl());
            vo.setTakenAt(Times.iso(m.getTakenAt()));
            vo.setNote(m.getNote());
            items.add(vo);
        }
        return items;
    }

    /**
     * 删除影像（**逻辑删除**，契约 §7）。
     *
     * <p>只把库里的行标成 {@code deleted=1}，<b>不删盘上的文件</b>——见 V6 的注释：
     * 软删的全部价值就在于可挽回，接口顺手删文件就把这个价值抵消了。删除后该影像
     * 从列表与内容端点一并消失（两者都走 MyBatis-Plus 的逻辑删除过滤，不必各写一遍）。</p>
     *
     * <p>先 {@link #requireByCode} 再删：已删的行查不出来，所以「删两次」第二次是 404，
     * 而不是静默成功——后者会让「我到底删没删掉」无从判断。</p>
     */
    public void delete(String mediaId) {
        Media media = requireByCode(mediaId);
        mediaMapper.deleteById(media.getId());
        log.info("影像已逻辑删除 mediaId={} pointId={} objectKey={}（盘上文件保留）",
                mediaId, media.getPointId(), media.getObjectKey());
    }

    /**
     * 取影像元数据，不存在抛 404，不在数据范围内抛 403。
     *
     * <p>影像的可见性**随它挂的测点**：{@code /media/{mediaId}/content} 是拿编码直接访问的
     * （{@code M001} 这种顺序编码，枚举成本极低），列表端点滤得再干净，
     * 少了这一处也等于没隔离——一个非管理员顺着编码就能把项目 B 的现场照片一张张读出来。</p>
     */
    public Media require(Long id) {
        Media m = id == null ? null : mediaMapper.selectById(id);
        if (m == null) {
            throw new BizException(404, "影像不存在: " + id);
        }
        dataScope.assertPointVisible(m.getPointId());
        return m;
    }

    /** 按对外编码（{@code "M001"}，或裸数字 id）取影像元数据。 */
    public Media requireByCode(String mediaId) {
        Long id = MediaCode.parse(mediaId);
        if (id == null) {
            throw new BizException(404, "影像不存在: " + mediaId);
        }
        return require(id);
    }

    /**
     * 读取落盘文件。除了 id 存在，还要求路径确实落在上传根目录内——
     * 库内路径一旦被改写，这里是最后一道闸。
     */
    public Resource content(Media media) {
        if (media.getFilePath() == null || media.getFilePath().trim().isEmpty()) {
            throw new BizException(404, "影像文件缺失: " + media.getId());
        }
        Path root = root();
        Path file = Paths.get(media.getFilePath()).toAbsolutePath().normalize();
        if (!file.startsWith(root)) {
            log.warn("影像路径越界 mediaId={} path={}", media.getId(), media.getFilePath());
            throw new BizException(403, "非法的影像路径");
        }
        if (!Files.isReadable(file)) {
            throw new BizException(404, "影像文件不存在: " + media.getId());
        }
        return new FileSystemResource(file);
    }

    // ---------- 内部 ----------

    /** 上传与列表都经由本方法，故范围断言挂在这里（少一处就是「能往别人项目的测点上挂图」）。 */
    private MonitorPoint requirePoint(Long pointId) {
        MonitorPoint p = pointId == null ? null : pointMapper.selectById(pointId);
        if (p == null) {
            throw new BizException(404, "测点不存在: " + pointId);
        }
        dataScope.assertPointVisible(pointId);
        return p;
    }

    /** 扩展名：先按 Content-Type 查表（可信），查不到再拿原文件名后缀比对白名单。 */
    private String resolveExt(MultipartFile file) {
        String mime = file.getContentType() == null ? null : file.getContentType().toLowerCase(Locale.ROOT).trim();
        if (mime != null) {
            String byMime = EXT_BY_MIME.get(mime);
            if (byMime != null) {
                return byMime;
            }
            if (!mime.startsWith("image/")) {
                throw new BizException("仅支持图片上传，当前 Content-Type: " + file.getContentType());
            }
        }
        String ext = extensionOf(file.getOriginalFilename());
        if (ext != null && ALLOWED_EXT.contains(ext)) {
            return ext;
        }
        throw new BizException("无法识别的图片类型（Content-Type: " + file.getContentType()
                + "，文件名: " + file.getOriginalFilename() + "）");
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1
                ? null : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String contentUrl(Long id) {
        return "/api/v1/media/" + MediaCode.of(id) + "/content";
    }

    private Path root() {
        return Paths.get(uploadDir).toAbsolutePath().normalize();
    }
}
