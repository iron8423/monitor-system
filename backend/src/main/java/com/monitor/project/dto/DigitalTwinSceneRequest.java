package com.monitor.project.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/** 数字孪生场景创建/更新请求。projectId 由路径提供，禁止客户端在 body 中另传。 */
@Data
public class DigitalTwinSceneRequest {

    private Boolean enabled = true;

    @NotBlank
    @Pattern(regexp = "GLB|3D_TILES", message = "assetType 仅支持 GLB / 3D_TILES")
    private String assetType;

    @NotBlank
    @Pattern(regexp = "ENU|EMBEDDED", message = "coordinateMode 仅支持 ENU / EMBEDDED")
    private String coordinateMode;

    @NotBlank
    @Size(max = 512)
    private String assetUrl;

    @NotBlank
    @Size(max = 64)
    private String assetVersion;

    @Pattern(regexp = "^$|[0-9a-fA-F]{64}", message = "assetSha256 必须是 64 位十六进制 SHA-256")
    private String assetSha256;

    @DecimalMin("-180") @DecimalMax("180")
    private BigDecimal anchorLongitude;
    @DecimalMin("-90") @DecimalMax("90")
    private BigDecimal anchorLatitude;
    private BigDecimal anchorHeight = BigDecimal.ZERO;

    private BigDecimal headingDegrees = BigDecimal.ZERO;
    private BigDecimal pitchDegrees = BigDecimal.ZERO;
    private BigDecimal rollDegrees = BigDecimal.ZERO;

    @DecimalMin(value = "0.000001", inclusive = true)
    private BigDecimal modelScale = BigDecimal.ONE;

    private BigDecimal cameraHeadingDegrees = BigDecimal.ZERO;
    @DecimalMin("-90") @DecimalMax("0")
    private BigDecimal cameraPitchDegrees = new BigDecimal("-35");
    @DecimalMin("1")
    private BigDecimal cameraRange = new BigDecimal("1000");

    @DecimalMin("1") @DecimalMax("128")
    private BigDecimal maximumScreenError = new BigDecimal("16");
    @Min(64) @Max(8192)
    private Integer maximumMemoryMb = 512;
    @Min(0) @Max(5000)
    private Integer maxHeatPoints = 200;
    @DecimalMin("10")
    private BigDecimal labelDistance = new BigDecimal("2000");
}

