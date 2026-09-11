package com.monitor.media.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 媒体（无人机影像 / 现场照片，挂到测点）。表见 V1 {@code media}。
 *
 * <p>{@code filePath} 是服务端落盘路径，{@code objectKey} 是对外对象键
 * （{@code media/<pointCode>/<uuid>.<ext>}），{@code url} 是对外访问地址
 * {@code /api/v1/media/{id}/content}。</p>
 */
@Data
@TableName("media")
public class Media {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long pointId;
    /** 客户端原始文件名：只留档展示，绝不参与落盘路径（防路径穿越）。 */
    private String fileName;
    private String filePath;
    private String objectKey;
    private String url;
    private String mimeType;
    private Long fileSize;
    /** 拍摄时间（与上传时间 {@code createdAt} 区分）。 */
    private LocalDateTime takenAt;
    private String note;
    private String uploadedBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
