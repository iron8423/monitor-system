package com.monitor.project.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.monitor.common.base.BaseEntity;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 监测测点（灰库 3 点 + 边坡 4 点，共 7 点）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("monitor_point")
public class MonitorPoint extends BaseEntity {

    private Long objectId;

    /**
     * 点号（外部设备上报时携带的业务键，见契约 D10）。
     *
     * <p>{@code @Size(max = 64)} 与库里的 {@code VARCHAR(64)} 一致，缺了它会出现
     * 「建得出来、收不到数」：点号 33–64 字符时建档 200，一上报就是 500
     * {@code value too long for type character varying(32)}（B-13，V5 已把
     * {@code measurement.point_code} 一并改宽到 64）。这里挡在入口，
     * 报 400 并说明原因，比让数据静默丢失好。</p>
     *
     * <p>上限写 64 而不是别的数：点号由现场编码习惯决定，平台没理由拒绝一个
     * 自己允许建出来的点号。</p>
     */
    @Size(max = 64, message = "点号长度不能超过 64 个字符")
    private String code;
    private String name;
    private String type;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private BigDecimal altitude;
    private Boolean enabled;
}
