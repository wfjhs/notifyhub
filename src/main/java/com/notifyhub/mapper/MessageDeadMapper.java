package com.notifyhub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.notifyhub.model.entity.MessageDead;
import org.apache.ibatis.annotations.Mapper;

/**
 * 死信 Mapper。CRUD 由 BaseMapper 提供，暂不需要自定义 SQL。
 */
@Mapper
public interface MessageDeadMapper extends BaseMapper<MessageDead> {
}
