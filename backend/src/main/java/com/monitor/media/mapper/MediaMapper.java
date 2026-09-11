package com.monitor.media.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.media.entity.Media;
import org.apache.ibatis.annotations.Mapper;

/**
 * 媒体表 Mapper。
 */
@Mapper
public interface MediaMapper extends BaseMapper<Media> {
}
