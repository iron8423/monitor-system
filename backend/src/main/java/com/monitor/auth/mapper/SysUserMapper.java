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
}
