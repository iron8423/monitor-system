package com.monitor.common.constant;

/**
 * 4 个演示角色（D1）。Spring Security 中映射为 {@code ROLE_<name>} 权限。
 */
public enum Role {

    ADMIN("管理员"),
    OPERATOR("值班员"),
    ANALYST("研判员"),
    MAINTAINER("运维员");

    private final String label;

    Role(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
