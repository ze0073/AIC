package com.shijie.accessibility.service;

import com.shijie.accessibility.common.ApiException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Range;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/** Creates minimal source edits without serializing the whole HTML document. */
@Component
public class SourcePatchService {
    private static final Set<String> NAME_ROLES = Set.of("button", "link");

    public PatchResult patchAiAttribute(String source, String selector, String ruleId,
                                        String candidateText, boolean decorative) {
        if (source == null || source.isBlank()) {
            throw ApiException.badRequest("SOURCE_EMPTY", "目标 HTML 文件为空");
        }
        Element element = locate(source, selector);
        String attribute;
        String normalized;
        if ("R001".equals(ruleId)) {
            if (!"img".equals(element.normalName())) {
                throw ApiException.badRequest("SOURCE_ELEMENT_MISMATCH", "R001 只能修改图片元素");
            }
            if (element.hasAttr("alt")) {
                throw ApiException.badRequest("PATCH_TARGET_STALE", "源码中的图片已包含 alt 属性，请重新扫描");
            }
            attribute = "alt";
            normalized = decorative ? "" : normalize(candidateText, 300, "图片替代文本");
        } else if ("R003".equals(ruleId)) {
            if (decorative) {
                throw ApiException.badRequest("DECORATIVE_NOT_ALLOWED", "按钮或链接不能标记为装饰性元素");
            }
            boolean supportedTag = "button".equals(element.normalName())
                    || ("a".equals(element.normalName()) && element.hasAttr("href"));
            boolean supportedRole = NAME_ROLES.contains(element.attr("role").strip().toLowerCase(Locale.ROOT));
            if (!supportedTag && !supportedRole) {
                throw ApiException.badRequest("SOURCE_ELEMENT_MISMATCH", "R003 只能修改按钮、链接或对应角色元素");
            }
            if (element.hasAttr("aria-label")) {
                throw ApiException.badRequest("PATCH_TARGET_STALE", "源码中的元素已包含 aria-label，请重新扫描");
            }
            attribute = "aria-label";
            normalized = normalize(candidateText, 120, "可访问名称");
        } else {
            throw ApiException.badRequest("AI_PATCH_NOT_SUPPORTED", "该规则不支持 AI 候选补丁");
        }

        Range range = element.sourceRange();
        if (!range.isTracked() || range.isImplicit()) {
            throw ApiException.badRequest("SOURCE_ELEMENT_NOT_FOUND", "元素不是可安全修改的静态源码节点");
        }
        int openingEnd = openingTagEnd(source, range.startPos(), range.endPos());
        int insertAt = insertionPoint(source, range.startPos(), openingEnd);
        String addition = " " + attribute + "=\"" + escapeAttribute(normalized) + "\"";
        String patched = source.substring(0, insertAt) + addition + source.substring(insertAt);
        return new PatchResult(patched, normalized, attribute);
    }

    private Element locate(String source, String selector) {
        if (selector == null || selector.isBlank()) {
            throw ApiException.badRequest("SOURCE_SELECTOR_MISSING", "缺少问题元素定位信息");
        }
        try {
            Parser parser = Parser.htmlParser().setTrackPosition(true);
            Elements matches = Jsoup.parse(source, "", parser).select(selector);
            if (matches.isEmpty()) {
                throw ApiException.badRequest("SOURCE_ELEMENT_NOT_FOUND", "无法在静态源码中定位问题元素");
            }
            if (matches.size() != 1) {
                throw ApiException.badRequest("SOURCE_ELEMENT_AMBIGUOUS", "问题元素定位不唯一，拒绝生成补丁");
            }
            return matches.first();
        } catch (ApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw ApiException.badRequest("SOURCE_SELECTOR_INVALID", "问题元素选择器无法安全解析");
        }
    }

    private int openingTagEnd(String source, int start, int end) {
        int limit = Math.min(source.length(), Math.max(start, end));
        char quote = 0;
        for (int index = Math.max(0, start); index < limit; index++) {
            char current = source.charAt(index);
            if (quote != 0) {
                if (current == quote) quote = 0;
            } else if (current == '\'' || current == '"') {
                quote = current;
            } else if (current == '>') {
                return index;
            }
        }
        throw ApiException.badRequest("SOURCE_TAG_INVALID", "无法确定目标元素开始标签的边界");
    }

    private int insertionPoint(String source, int start, int openingEnd) {
        int index = openingEnd - 1;
        while (index > start && Character.isWhitespace(source.charAt(index))) index--;
        return source.charAt(index) == '/' ? index : openingEnd;
    }

    private String normalize(String value, int maxLength, String label) {
        String normalized = value == null ? "" : value
                .replaceAll("[\\p{Cc}\\p{Cf}]", " ")
                .replaceAll("\\s+", " ")
                .strip();
        if (normalized.isEmpty()) throw ApiException.badRequest("CANDIDATE_REQUIRED", label + "不能为空");
        if (normalized.length() > maxLength) {
            throw ApiException.badRequest("CANDIDATE_TOO_LONG", label + "不能超过 " + maxLength + " 个字符");
        }
        return normalized;
    }

    private String escapeAttribute(String value) {
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "&#39;");
    }

    public record PatchResult(String content, String normalizedText, String attribute) {}
}
