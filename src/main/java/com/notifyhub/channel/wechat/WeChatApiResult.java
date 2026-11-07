package com.notifyhub.channel.wechat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 微信系列接口的通用返回体。
 * <p>
 * 公众号模板消息与企业微信机器人成功时都返回 {@code errcode=0}，
 * 失败时带上错误码，因此用一个 DTO 覆盖两类接口。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WeChatApiResult {

    @JsonProperty("errcode")
    private Integer errcode;

    @JsonProperty("errmsg")
    private String errmsg;

    /** 公众号模板消息成功时返回的 msgid，其他接口可能没有 */
    @JsonProperty("msgid")
    private Long msgid;

    public boolean isSuccess() {
        return errcode == null || errcode == 0;
    }

    public String describe() {
        return "errcode=" + errcode + ", errmsg=" + errmsg;
    }
}
