package com.notifyhub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.notifyhub.model.entity.Message;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    /**
     * 抢占一条待投递消息。
     * <p>
     * 用条件更新而不是「先查后改」：并发下两个 worker 同时扫到同一条，
     * 只有一个的更新行数为 1，另一个拿到 0 直接跳过。
     * 与项目一判题任务的抢占思路一致，只是这里没有 Redis，
     * 用数据库行锁承担同样的职责。
     *
     * @return 影响行数，1 表示抢占成功
     */
    @Update("UPDATE message SET status = 'SENDING', update_time = NOW() " +
            "WHERE id = #{id} AND status IN ('PENDING', 'FAILED')")
    int claim(@Param("id") Long id);

    /**
     * 扫描到期且未达终态的消息。
     * <p>
     * 条件是「PENDING（首次投递）或 FAILED（等待重试）且未到退避时间之前不投」。
     * 排序按 next_retry_time 保证先到期的先投；PENDING 的 next_retry_time 为 NULL，
     * 用 IS NULL 分支让它优先被处理。
     */
    @Select("SELECT * FROM message " +
            "WHERE deleted = 0 AND status IN ('PENDING', 'FAILED') " +
            "  AND (next_retry_time IS NULL OR next_retry_time <= NOW()) " +
            "ORDER BY next_retry_time IS NULL DESC, next_retry_time ASC, id ASC " +
            "LIMIT #{limit}")
    List<Message> selectDue(@Param("limit") int limit);

    /**
     * 投递成功后置为终态。
     */
    @Update("UPDATE message SET status = 'SUCCESS', sent_at = NOW(), update_time = NOW(), " +
            "fail_reason = NULL WHERE id = #{id}")
    int markSuccess(@Param("id") Long id);
}
