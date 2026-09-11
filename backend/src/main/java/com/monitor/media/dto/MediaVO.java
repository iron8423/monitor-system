package com.monitor.media.dto;

import lombok.Data;

/**
 * 测点影像列表项（《B侧接口契约_M0》§7）。{@code mediaId} 用字符串编码，同 {@link MediaUploadVO}。
 */
@Data
public class MediaVO {

    private String mediaId;
    private String url;
    private String takenAt;
    private String note;
}
