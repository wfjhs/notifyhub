package com.notifyhub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 模板渲染的单元测试。
 * <p>
 * 选择先测这个类，是因为它是纯函数、无外部依赖，且逻辑上有几个容易忽略的边界：
 * 变量缺失、变量值含正则特殊字符、模板本身为空。
 */
class TemplateRendererTest {

    @Test
    @DisplayName("单个变量能被替换")
    void render_singleVar() {
        String result = TemplateRenderer.render("订单 ${orderNo} 已支付", Map.of("orderNo", "A1001"));
        assertEquals("订单 A1001 已支付", result);
    }

    @Test
    @DisplayName("多个变量按顺序替换")
    void render_multipleVars() {
        String result = TemplateRenderer.render(
                "订单 ${orderNo} 金额 ${amount} 元",
                Map.of("orderNo", "A1001", "amount", "99.00"));
        assertEquals("订单 A1001 金额 99.00 元", result);
    }

    @Test
    @DisplayName("变量缺失时替换为空串，不抛异常")
    void render_missingVarBecomesEmpty() {
        // 缺一个变量不该让整条通知发不出去
        String result = TemplateRenderer.render("订单 ${orderNo} 金额 ${amount}", Map.of("orderNo", "A1001"));
        assertEquals("订单 A1001 金额 ", result);
    }

    @Test
    @DisplayName("变量值含 $ 与 \\ 时不被当成正则分组引用")
    void render_specialCharsInValue() {
        // 这里验证 Matcher.quoteReplacement 的作用：
        // 不转义的话 $1 / \ 会被 appendReplacement 当作分组引用，抛 IllegalArgumentException
        Map<String, String> vars = new HashMap<>();
        vars.put("price", "$100\\200");
        String result = TemplateRenderer.render("价格 ${price}", vars);
        assertEquals("价格 $100\\200", result);
    }

    @Test
    @DisplayName("没有变量时保留模板原样")
    void render_noVarsKeepsTemplate() {
        String template = "系统维护通知：${time} 起暂停服务";
        assertEquals(template, TemplateRenderer.render(template, null));
        assertEquals(template, TemplateRenderer.render(template, Map.of()));
    }

    @Test
    @DisplayName("模板为空时原样返回")
    void render_emptyTemplate() {
        assertEquals("", TemplateRenderer.render("", Map.of("a", "b")));
        assertEquals(null, TemplateRenderer.render(null, Map.of("a", "b")));
    }
}
