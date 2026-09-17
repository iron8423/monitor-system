package com.monitor.project.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 3D 大屏读取的完整项目场景配置，含归属于该项目的雷达姿态。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DigitalTwinSceneVO {
    private Long id;
    private Long projectId;
    private boolean enabled;
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
    private List<RadarPose> radars = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RadarPose {
        private Long deviceId;
        private String code;
        private String name;
        private BigDecimal longitude;
        private BigDecimal latitude;
        private BigDecimal altitude;
        private BigDecimal headingDegrees;
        private BigDecimal pitchDegrees;
        private BigDecimal detectionRangeM;
        private BigDecimal halfAngleDegrees;
        private BigDecimal antennaHeightM;
        private BigDecimal verticalHalfAngleDegrees;
        private String status;
        private List<RadarTargetPose> targets = new ArrayList<>();
    }

    /** 已完成标定、可由对应雷达观测的测点及其几何关系。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RadarTargetPose {
        private Long bindingId;
        private Long pointId;
        private String pointCode;
        private String pointName;
        private BigDecimal longitude;
        private BigDecimal latitude;
        private BigDecimal altitude;
        private String targetCode;
        private BigDecimal azimuthDegrees;
        private BigDecimal elevationDegrees;
        private BigDecimal slantRangeM;
        private BigDecimal reflectorHeightM;
        private boolean lineOfSight;
        private BigDecimal minimumClearanceM;
        private String calibrationStatus;
    }
}
