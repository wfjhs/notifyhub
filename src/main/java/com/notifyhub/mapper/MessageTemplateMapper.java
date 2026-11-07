package com.notifyhub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.notifyhub.model.entity.MessageTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MessageTemplateMapper extends BaseMapper<MessageTemplate> {

    @Select("SELECT * FROM message_template " +
            "WHERE deleted = 0 AND code = #{code} AND channel = #{channel} LIMIT 1")
    MessageTemplate findByCodeAndChannel(@Param("code") String code,
                                         @Param("channel") String channel);
}
