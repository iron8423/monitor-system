package com.monitor.twin.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 标定试算的输入：**只覆盖"想试一下"的那两个高度**，其余几何一律取档案现况。
 *
 * <p>刻意不放 azimuth/slant 之类的"目标值"进来：那会变成"我用自己填的数校验自己填的数"，
 * 什么也证明不了。试算的意义是「按档案里的设备位置、测点位置和地形，这条视线成不成立」，
 * 所以输入只有两个可以现场调整、且不影响几何关系的高度。</p>
 */
@Data
public class CalibrationPreviewRequest {

    /** 天线高（米，地面之上）。留空 = 用设备档案里的 antenna_height_m。 */
    @DecimalMin("0.0")
    @DecimalMax("64.0")
    private BigDecimal antennaHeightM;

    /** 反射器高（米，测点标记之上）。留空 = 用绑定上的 reflector_height_m，再缺省取 2.5。 */
    @DecimalMin("0.0")
    @DecimalMax("20.0")
    private BigDecimal reflectorHeightM;
}
