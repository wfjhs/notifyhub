package com.notifyhub.controller;

import com.notifyhub.config.ApiResult;
import com.notifyhub.model.dto.SaveChannelAccountDTO;
import com.notifyhub.model.vo.ChannelAccountVO;
import com.notifyhub.service.ChannelAccountService;
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
 * 通道账号增删改查。
 * <p>
 * 列表与详情返回 {@link ChannelAccountVO}（密钥脱敏），
 * 只有写入接口接收 appSecret。管理台本身没有鉴权，这条边界必须守住。
 */
@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class ChannelAccountController {

    private final ChannelAccountService accountService;

    /** 查：channel 可选，用于给「发送对象」弹窗按通道筛账号 */
    @GetMapping
    public ApiResult<List<ChannelAccountVO>> list(
            @RequestParam(value = "channel", required = false) String channel) {
        return ApiResult.ok(accountService.list(channel));
    }

    @PostMapping
    public ApiResult<ChannelAccountVO> create(@Valid @RequestBody SaveChannelAccountDTO dto) {
        return ApiResult.ok(accountService.create(dto));
    }

    @PutMapping("/{id:\\d+}")
    public ApiResult<ChannelAccountVO> update(@PathVariable("id") Long id,
                                              @Valid @RequestBody SaveChannelAccountDTO dto) {
        return ApiResult.ok(accountService.update(id, dto));
    }

    @DeleteMapping("/{id:\\d+}")
    public ApiResult<Void> delete(@PathVariable("id") Long id) {
        accountService.delete(id);
        return ApiResult.ok(null);
    }
}
