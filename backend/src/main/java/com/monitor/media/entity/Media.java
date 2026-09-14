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
 *
 * <p><b>{@code deleted} 走逻辑删除</b>（V6 加的列，全局配置
 * {@code mybatis-plus.global-config.db-config.logic-delete-field: deleted} 接管）。
 * 本类**不继承 {@code BaseEntity}**（媒体没有「更新」语义，也就没有 {@code updatedAt}），
 * 所以这个字段是手写的一份——全局配置只认字段名，不认继承关系，写在这里同样生效，
 * 且所有既有查询会自动带上 {@code AND deleted = 0}。</p>
 *
 * <p>注意软删**不动盘上的文件**：删的只是库里的可见性，可挽回。理由见 V6 的注释。</p>
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

    /** 逻辑删除标记：0=正常 1=删除。字段名由全局配置识别，无需 {@code @TableLogic} 注解。 */
    private Integer deleted;
}
