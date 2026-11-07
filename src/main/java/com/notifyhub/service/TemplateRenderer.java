package com.notifyhub.service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 极简模板渲染：把 {@code ${var}} 替换成变量值。
 * <p>
 * 没有引入 Freemarker/Velocity 之类的模板引擎，原因很直接：
 * 通知内容只需要变量替换，不需要条件分支、循环、宏。
 * 引一个模板引擎会带来「模板作者可以调用任意方法」的风险（SSTI 那类问题），
 * 而收益几乎为零。
 * <p>
 * 占位符找不到对应变量时替换为空串而不是抛异常——缺一个变量不该让整条通知发不出去，
 * 但保留原样又会把 {@code ${orderNo}} 这种字样发给用户，两害相权取了前者。
 */
public final class TemplateRenderer {

    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([\\w.]+)}");

    private TemplateRenderer() {
    }

    public static String render(String template, Map<String, String> vars) {
        if (template == null || template.isEmpty()) {
            return template;
        }
        if (vars == null || vars.isEmpty()) {
            // 没有变量时保留原样，方便排查「为什么模板没渲染」
            return template;
        }

        Matcher matcher = VAR_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = vars.getOrDefault(key, "");
            // quoteReplacement：变量值里含 $ 或 \ 时不能被当成正则的分组引用
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
