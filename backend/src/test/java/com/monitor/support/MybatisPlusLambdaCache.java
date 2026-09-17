package com.monitor.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

/**
 * 无 Spring 上下文的纯单测里，{@code LambdaQueryWrapper} 会抛
 * "can not find lambda cache for this entity"：列名缓存只在 {@code TableInfo} 初始化时写入，
 * 而这一步属于 MyBatis-Plus 的启动流程，Mockito 单测没有。
 *
 * <p>这里手工建一个最小的 MyBatis 环境把缓存烧热。只构造 {@code TableInfo}，
 * 不需要数据源、不需要建库表，也不碰任何生产代码路径。</p>
 */
public final class MybatisPlusLambdaCache {

    private MybatisPlusLambdaCache() {
    }

    /** 为给定实体（含其父类字段）构建 TableInfo 并写入 lambda 列名缓存；可重复调用。 */
    public static synchronized void warm(Class<?>... entities) {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> entity : entities) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }
}
