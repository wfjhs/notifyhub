package com.notifyhub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.notifyhub.model.entity.Recipient;
import org.apache.ibatis.annotations.Mapper;

/**
 * 发送对象 Mapper。CRUD 由 BaseMapper 提供。
 */
@Mapper
public interface RecipientMapper extends BaseMapper<Recipient> {
}
