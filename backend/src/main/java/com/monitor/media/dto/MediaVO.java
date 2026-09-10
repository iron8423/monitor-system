package com.monitor.media.dto;

import lombok.Data;

/**
 * 测点影像列表项（《B侧接口契约_M0》§7）。{@code mediaId} 同样按 D10 返回数值 id。
 */
@Data
public class MediaVO {

    private Long mediaId;
    private String url;
    private String takenAt;
    private String note;
}
