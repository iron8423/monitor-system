package com.monitor.media.dto;

import lombok.Data;

/**
 * 上传结果（《B侧接口契约_M0》§7）。
 *
 * <p>契约示例把 {@code mediaId} 写成 {@code "M001"} 这种字符串，但库里 id 是数值
 * （D10：id 数值 / code 字符串，media 表无 code 列）——故此处按 D10 返回<b>数值 id</b>，
 * 与其余接口的 {@code pointId}/{@code alarmId} 一致；URL 也据此拼。</p>
 */
@Data
public class MediaUploadVO {

    private Long mediaId;
    /** 对外对象键 {@code media/<pointCode>/<uuid>.<ext>}。 */
    private String objectKey;
    /** 访问地址 {@code /api/v1/media/{id}/content}。 */
    private String url;
    private Long pointId;
    private String takenAt;
}
