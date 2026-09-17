package com.monitor.asset.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 雷达目标标定结果。地形视线由离线建模/现场标定工具计算，平台负责范围与状态校验。 */
@Data
public class DevicePointCalibrationRequest {
    @NotBlank
    @Size(max = 96)
    private String targetCode;

    @NotNull
    @DecimalMin("0.0")
    @DecimalMax("360.0")
    private BigDecimal azimuthDegrees;

    @NotNull
    @DecimalMin("-90.0")
    @DecimalMax("90.0")
    private BigDecimal elevationDegrees;

    @NotNull
    @DecimalMin(value = "0.0", inclusive = false)
    private BigDecimal slantRangeM;

    @DecimalMin("0.0")
    private BigDecimal reflectorHeightM;

    @NotNull
    private Boolean lineOfSight;

    private BigDecimal minimumClearanceM;
    private LocalDateTime validFrom;
    private LocalDateTime validTo;

    @Size(max = 512)
    private String note;
}
