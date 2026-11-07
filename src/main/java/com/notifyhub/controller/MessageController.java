package com.notifyhub.controller;

import com.notifyhub.config.ApiResult;
import com.notifyhub.model.dto.SendMessageDTO;
import com.notifyhub.model.entity.Message;
import com.notifyhub.model.entity.MessageDead;
import com.notifyhub.model.vo.SendResultVO;
import com.notifyhub.service.MessageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 消息接口。
 * <p>
 * 只有两个动作：提交发送、查询状态。管理类接口（重放死信、模板维护）
 * 属于下一步，当前范围不做。
 */
@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    /**
     * 提交一条消息。落库后立即尝试投递一次，失败交给补偿任务。
     * <p>
     * 重复提交（相同 bizId + channel）不会产生新消息，返回 {@code duplicated=true}。
     */
    @PostMapping
    public ApiResult<SendResultVO> send(@Valid @RequestBody SendMessageDTO dto) {
        return ApiResult.ok(messageService.submit(dto));
    }

    /** 按 ID 查消息状态 */
    @GetMapping("/{id:\\d+}")
    public ApiResult<Message> detail(@PathVariable Long id) {
        return ApiResult.ok(messageService.detail(id));
    }

    /** 按业务键查该业务下的所有通道消息，便于排查「为什么用户没收到」 */
    @GetMapping
    public ApiResult<List<Message>> listByBiz(@RequestParam String bizId) {
        return ApiResult.ok(messageService.listByBizId(bizId));
    }

    /**
     * 死信列表：重试耗尽、需要人工介入的消息。
     */
    @GetMapping("/dead")
    public ApiResult<List<MessageDead>> deadList(@RequestParam(defaultValue = "50") int limit) {
        return ApiResult.ok(messageService.listDead(limit));
    }

    /**
     * 重放死信：通道恢复后把消息放回待投递队列。
     */
    @PostMapping("/dead/{id:\\d+}/replay")
    public ApiResult<String> replayDead(@PathVariable Long id) {
        messageService.replayDead(id);
        return ApiResult.ok("已重放，等待补偿任务重新投递");
    }
}
