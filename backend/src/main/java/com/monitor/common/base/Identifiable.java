package com.monitor.common.base;

/**
 * 具有主键 ID 的实体标记接口，供审计切面等通用逻辑识别实体并提取 ID。
 */
public interface Identifiable {

    Long getId();
}
