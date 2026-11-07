package com.notifyhub.channel.wechat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 微信 access_token 接口的响应。
 * <p>
 * 成功时返回 access_token + expires_in；失败时返回 errcode + errmsg，
 * 所以两类字段都保留，由调用方判断。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AccessTokenResponse {

    @JsonProperty("access_token")
    private String accessToken;

    @JsonProperty("expires_in")
    private Long expiresIn;

    @JsonProperty("errcode")
    private Integer errcode;

    @JsonProperty("errmsg")
    private String errmsg;
}
