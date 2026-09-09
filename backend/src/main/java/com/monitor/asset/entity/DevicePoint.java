package com.monitor.asset.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.monitor.common.base.Identifiable;
import lombok.Data;

/**
 * 设备-测点绑定（硬删除，无软删除字段）。
 */
@Data
@TableName("device_point")
public class DevicePoint implements Identifiable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long deviceId;
    private Long pointId;
}
