package com.shijie.accessibility.ai;

import java.util.Map;
import java.util.Optional;
import java.util.List;

/** External model operations require explicit consent in their calling service. */
public interface AiProvider {
    boolean enabled();
    String provider();
    String model();
    String unavailableReason();
    Optional<Suggestion> suggest(Request request);
    default Optional<IssueExplanation> explainIssue(IssueRequest request) { return Optional.empty(); }
    default Optional<RepairSuggestion> generateRepair(RepairRequest request) { return Optional.empty(); }

    record Request(String ruleId, String pagePath, String selector, Map<String, Object> evidence,
                   String screenshotDataUrl) {}
    record Suggestion(String summary, String confidenceBand, List<String> reasons,
                      String recommendedAction, String candidateText, boolean reviewRequired) {}
    record IssueRequest(String source, String title, String description, Map<String,Object> evidence,
                        List<Map<String,Object>> relatedCases) {}
    record IssueExplanation(String phenomenon, List<String> possibleCauses, List<String> evidenceBasis,
                            List<String> fixSuggestions, List<String> verificationSteps, boolean reviewRequired) {}
    record RepairRequest(String title, String description, Object evidence, String filePath, String content) {}
    record RepairSuggestion(String content, String summary, String reason, String benefit, String prevention) {}
}
