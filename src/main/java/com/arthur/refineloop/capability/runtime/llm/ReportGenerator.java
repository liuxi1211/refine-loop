package com.arthur.refineloop.capability.runtime.llm;

import java.util.Map;

/**
 * 轻量报告生成器：版本化提示词 + LLM 调用基座，供“今日一句话”等报告类任务使用。
 *
 * <p>它不包含业务判断，只负责模板渲染、调用与返回；业务语义在提示词与报告服务里。
 */
public class ReportGenerator {

    private static final String DEFAULT_PROFILE = "fast";
    private static final String USER_PROMPT = "请严格按系统提示生成结果，不要输出额外解释。";

    private final PromptTemplateLoader promptTemplateLoader;
    private final LlmInvoker llmInvoker;

    public ReportGenerator(PromptTemplateLoader promptTemplateLoader, LlmInvoker llmInvoker) {
        this.promptTemplateLoader = promptTemplateLoader;
        this.llmInvoker = llmInvoker;
    }

    public ReportOutput generate(ReportRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为空");
        }
        String template = promptTemplateLoader.load(request.promptId(), request.promptVersion());
        String systemPrompt = promptTemplateLoader.render(template, request.variables());
        String profile = request.modelProfile() == null || request.modelProfile().isBlank()
                ? DEFAULT_PROFILE
                : request.modelProfile();
        LlmCallResult call = llmInvoker.invoke(new LlmCallSpec(
                profile,
                request.unitId(),
                request.runId(),
                systemPrompt,
                USER_PROMPT,
                null,
                null));
        return new ReportOutput(call.content(), call);
    }

    /**
     * 报告请求。
     *
     * @param promptId      提示词标识
     * @param promptVersion 提示词版本（v1 / v1.0）
     * @param unitId        关联单元 id（用量记账维度，可为空）
     * @param runId         流水线运行号，可为空
     * @param modelProfile  fast / strong，默认 fast
     * @param variables     模板变量
     */
    public record ReportRequest(
            String promptId,
            String promptVersion,
            String unitId,
            String runId,
            String modelProfile,
            Map<String, Object> variables) {
    }

    /**
     * 报告输出。
     *
     * @param text 报告正文
     * @param call 调用用量
     */
    public record ReportOutput(String text, LlmCallResult call) {
    }
}
