package com.notifyhub.controller;

import com.notifyhub.config.ApiResult;
import com.notifyhub.model.dto.RecipientFormDTO;
import com.notifyhub.model.entity.Recipient;
import com.notifyhub.service.RecipientService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 发送对象管理（增删改查）。
 * <p>
 * 发送消息时前端只传 recipientId，地址由服务端解析，
 * 所以这组接口是「谁能发、发到哪」的唯一入口。
 */
@RestController
@RequestMapping("/api/recipients")
@RequiredArgsConstructor
public class RecipientController {

    private final RecipientService recipientService;

    /** 查：keyword 可选，按名称或地址模糊匹配 */
    @GetMapping
    public ApiResult<List<Recipient>> list(@RequestParam(value = "keyword", required = false) String keyword) {
        return ApiResult.ok(recipientService.list(keyword));
    }

    /** 增 */
    @PostMapping
    public ApiResult<Recipient> create(@Valid @RequestBody RecipientFormDTO dto) {
        return ApiResult.ok(recipientService.create(dto));
    }

    /** 改 */
    @PutMapping("/{id:\\d+}")
    public ApiResult<Recipient> update(@PathVariable("id") Long id,
                                       @Valid @RequestBody RecipientFormDTO dto) {
        return ApiResult.ok(recipientService.update(id, dto));
    }

    /** 删（逻辑删除） */
    @DeleteMapping("/{id:\\d+}")
    public ApiResult<Void> delete(@PathVariable("id") Long id) {
        recipientService.delete(id);
        return ApiResult.ok(null);
    }
}
