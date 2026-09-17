package com.monitor.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.auth.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
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
}
