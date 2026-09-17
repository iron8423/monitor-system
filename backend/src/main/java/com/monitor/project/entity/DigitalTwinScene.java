package com.monitor.project.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.monitor.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 每个项目唯一的数字孪生场景资产与地理配准配置。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("digital_twin_scene")
public class DigitalTwinScene extends BaseEntity {

    private Long projectId;
    private Boolean enabled;
    private String assetType;
    private String coordinateMode;
    private String assetUrl;
    private String assetVersion;
    private String assetSha256;
    private BigDecimal anchorLongitude;
    private BigDecimal anchorLatitude;
    private BigDecimal anchorHeight;
    private BigDecimal headingDegrees;
    private BigDecimal pitchDegrees;
    private BigDecimal rollDegrees;
    private BigDecimal modelScale;
    private BigDecimal cameraHeadingDegrees;
    private BigDecimal cameraPitchDegrees;
    private BigDecimal cameraRange;
    private BigDecimal maximumScreenError;
    private Integer maximumMemoryMb;
    private Integer maxHeatPoints;
    private BigDecimal labelDistance;
}

