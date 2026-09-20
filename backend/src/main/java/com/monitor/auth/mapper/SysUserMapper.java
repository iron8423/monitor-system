package com.monitor.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.auth.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 令牌版本 +1，作废该用户此前签发的全部令牌。
     *
     * <p>用 {@code token_version = token_version + 1} 而不是「查出来、加一、updateById」：
     * 后者的读改写之间有一个窗口，两次并发登出会各自读到同一版本、写回同一个值——
     * 于是只加了一次。这里是单条原子更新，没有那个窗口。</p>
     */
    @Update("UPDATE sys_user SET token_version = token_version + 1 WHERE id = #{id}")
    int bumpTokenVersion(@Param("id") Long id);

    /**
     * 改密：单条更新口令列。
     *
     * <p>为什么不走 {@code updateById}：实体里带着 role/enabled/organizationId 等字段，
     * 用实体更新等于把「读出来的那一份」整体写回去——改密这个动作只该动 password 一列，
     * 其余的并发写入（管理员正在改角色、正在停用）不该被它覆盖回去。</p>
     *
     * <p>调用方必须在<b>同一个</b>动作里接着调 {@link #bumpTokenVersion}：只改口令而不递增
     * 令牌版本，旧口令签发的令牌仍然可用，这个功能就只做了一半。</p>
     */
    @Update("UPDATE sys_user SET password = #{password} WHERE id = #{id}")
    int updatePassword(@Param("id") Long id, @Param("password") String password);

    /**
     * 按账号查**任意一行**，包括逻辑删除的（墓碑行）。
     *
     * <p>为什么必须绕开 MyBatis-Plus 的逻辑删除过滤：账号名上有全局唯一约束
     * （{@code uk_sys_user_username}），而逻辑删除只是把 {@code deleted} 置 1、行还在库里。
     * 于是「删了账号 → 本人重新注册同一个账号名」会撞唯一键。要支持「删除后重新注册」，
     * 就得先看见那行墓碑，再决定是复用它（见 {@code revive}）还是报重名。</p>
     */
    @Select("SELECT * FROM sys_user WHERE username = #{username}")
    SysUser selectAnyByUsername(@Param("username") String username);

    /**
     * 复活墓碑行：把 {@code deleted} 清零并写入本人重新注册时填的资料。
     *
     * <p>为什么不直接 {@code updateById}：MP 的逻辑删除字段不参与普通更新，
     * 没法把 1 写回 0——必须走 SQL。为什么不「硬删旧行再插新行」：账号的历史
     * （审计里记的是 username）留在同一行更好追溯，也少一次「删了谁」的不可逆动作。</p>
     */
    @Update("""
            UPDATE sys_user
               SET deleted = 0, password = #{u.password}, display_name = #{u.displayName},
                   role = #{u.role}, organization_id = #{u.organizationId}, phone = #{u.phone},
                   email = #{u.email}, job_title = #{u.jobTitle}, enabled = 1,
                   token_version = token_version + 1
             WHERE id = #{u.id}
            """)
    int revive(@Param("u") SysUser user);
}
