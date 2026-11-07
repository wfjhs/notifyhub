package com.notifyhub.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发送对象表单（新增与修改共用）。
 * <p>
 * 两处校验完全一致：名称、通道、地址都必填，长度受列宽约束。
 * 修改时前端会把原值预填，提交的是整份表单而非差量——
 * 「地址簿」这种小对象做 PATCH 语义没有收益，反而容易出现
 * 「只改了名称，地址被忽略」的困惑。
 */
@Data
public class RecipientFormDTO {

    @NotBlank(message = "对象名称不能为空")
    @Size(max = 64, message = "名称不能超过 64 字符")
    private String name;

    @NotBlank(message = "通道不能为空")
    private String channel;

    @NotBlank(message = "地址不能为空")
    @Size(max = 128, message = "地址不能超过 128 字符")
    private String address;

    /** 绑定的通道账号；为空表示回落全局配置 */
    private Long accountId;

    @Size(max = 255, message = "备注不能超过 255 字符")
    private String remark;
}
