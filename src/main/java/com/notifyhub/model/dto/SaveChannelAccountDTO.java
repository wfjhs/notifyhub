package com.notifyhub.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 通道账号表单（新增与修改共用）。
 * <p>
 * appSecret 特意允许为空：列表接口不返回密钥，前端编辑时也不会带回来。
 * 修改时留空 = 保持原密钥不变；只有显式填了新值才覆盖。
 * 否则「改个备注」会把密钥清空。
 */
@Data
public class SaveChannelAccountDTO {

    @NotBlank(message = "账号名称不能为空")
    @Size(max = 64)
    private String name;

    @NotBlank(message = "通道不能为空")
    private String channel;

    @Size(max = 64)
    private String appId;

    @Size(max = 256)
    private String appSecret;

    @Size(max = 128)
    private String templateId;

    @Size(max = 512)
    private String webhookUrl;

    /** 不传视为启用 */
    private Boolean enabled;

    @Size(max = 255)
    private String remark;
}
