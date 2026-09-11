package com.monitor.media.dto;

import lombok.Data;

/**
 * 上传结果（《B侧接口契约_M0》§7）。
 *
 * <p>{@code mediaId} 按契约用<b>字符串编码</b>（如 {@code "M001"}，见 {@link com.monitor.media.MediaCode}），
 * 与 {@code url} 中的 {@code {mediaId}} 同一取值。库内主键仍是数值，
 * 编码由主键派生——D10 的「id 数值 / code 字符串」在对外这一层即体现为只给出编码。</p>
 */
@Data
public class MediaUploadVO {

    private String mediaId;
    /** 对外对象键 {@code media/<pointCode>/<uuid>.<ext>}。 */
    private String objectKey;
    /** 访问地址 {@code /api/v1/media/{mediaId}/content}。 */
    private String url;
    private Long pointId;
    private String takenAt;
}
