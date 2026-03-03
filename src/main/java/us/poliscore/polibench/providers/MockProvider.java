package us.poliscore.polibench.providers;

import us.poliscore.model.TrackedIssue;
import us.poliscore.model.bill.StructuralAnalysis;
import us.poliscore.polibench.models.ModelRequest;
import us.poliscore.polibench.models.ModelResponse;
import us.poliscore.polibench.models.Pillar;

import java.util.ArrayList;
import java.util.List;

public class MockProvider implements AiProvider {
    private final List<ModelRequest> pendingRequests = new ArrayList<>();

    @Override
    public String getModelId() {
        return "mock";
    }

    @Override
    public void generateBatchFile(List<ModelRequest> requests, String batchFileOutputPath) throws Exception {
        pendingRequests.clear();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try (java.io.PrintWriter out = new java.io.PrintWriter(new java.io.FileWriter(batchFileOutputPath))) {
            for (ModelRequest req : requests) {
                pendingRequests.add(req);

                // Construct the messages array
                java.util.List<java.util.Map<String, String>> messages = new java.util.ArrayList<>();
                messages.add(java.util.Map.of("role", "system", "content", req.getSystemPrompt()));
                messages.add(java.util.Map.of("role", "user", "content", req.getUserPrompt()));

                // Construct the body of the API request
                java.util.Map<String, Object> body = new java.util.HashMap<>();
                body.put("model", "mock-model");
                body.put("messages", messages);
                body.put("temperature", 0.0);

                // Construct the request row using the same JSONL shape as the live provider
                java.util.Map<String, Object> batchRequest = new java.util.HashMap<>();
                batchRequest.put("custom_id", req.getRequestId());
                batchRequest.put("method", "POST");
                batchRequest.put("url", "/v1/chat/completions");
                batchRequest.put("body", body);

                out.println(mapper.writeValueAsString(batchRequest));
            }
        }
    }

    @Override
    public String submitBatch(String batchFileOutputPath) throws Exception {
        System.out.println("[MockProvider] Simulating batch submission for " + batchFileOutputPath);
        return "mock_batch_" + System.currentTimeMillis();
    }

    @Override
    public boolean isBatchComplete(String batchId) throws Exception {
        System.out.println("[MockProvider] Simulating batch completion check for " + batchId);
        // Instant completion for the mock driver
        return true;
    }

    @Override
    public List<ModelResponse> fetchBatchResults(String batchId) throws Exception {
        System.out.println("[MockProvider] Simulating result fetching for " + batchId);
        List<ModelResponse> responses = new ArrayList<>();
        for (ModelRequest request : pendingRequests) {
            responses.add(new ModelResponse(request.getRequestId(),
                    buildMockResponseContent(request),
                    10,
                    10));
        }
        return responses;
    }

    @Override
    public List<ModelResponse> parseBatchResults(String batchResultInputPath) throws Exception {
        System.out.println("[MockProvider] Simulating offline file parse for " + batchResultInputPath);
        return new ArrayList<>();
    }

    @Override
    public double calculateEstimatedCost(int promptTokens, int expectedCompletionTokens) {
        return 0.0;
    }

    private String buildMockResponseContent(ModelRequest request) {
        boolean expectedPass = request.getTask() != null && "PASS".equalsIgnoreCase(request.getTask().getExpected());
        StructuralAnalysis focusPillar = toStructuralAnalysis(request.getTask() != null ? request.getTask().getPillar() : null);
        String billText = request.getTask() != null ? request.getTask().getBillText() : request.getUserPrompt();

        StringBuilder response = new StringBuilder();
        response.append("Neutral Summary:\n");
        response.append("This benchmark bill proposes a targeted policy intervention affecting public administration and social outcomes. ");
        response.append("The text provided describes the core mechanism, affected actors, and likely implementation approach in summary form.\n\n");

        response.append("Bill Title:\n");
        response.append("Mock Benchmark Bill\n\n");

        response.append("Structural Analysis:\n");
        for (StructuralAnalysis pillar : StructuralAnalysis.values()) {
            boolean pass = pillar == focusPillar ? expectedPass : defaultPassForNonFocusPillar(pillar, expectedPass);
            response.append(pillar.getNumber()).append(". ").append(pillar.getDisplayName()).append(":\n");
            response.append(structuralExplanation(pillar, billText, pass)).append(" ");
            response.append(pass ? "<PASS>" : "<FAIL>").append("\n\n");
        }

        response.append("Impact Analysis:\n");
        response.append("1. Baseline:\n");
        response.append("The relevant baseline is current law and ordinary institutional practice. CURRENT_LAW\n");
        response.append("2. Affected Parties:\n");
        response.append("The direct effects fall on the populations, agencies, or firms named in the bill text. NARROW\n");
        response.append("3. Directionality:\n");
        response.append(expectedPass ? "NET_BENEFICIAL\n" : "NET_HARMFUL\n");
        response.append("4. Effect Magnitude (per affected person):\n");
        response.append("The per-person effect is meaningful but not uniformly transformative in this simplified benchmark scenario. MODERATE\n");
        response.append("5. Temporal Horizon:\n");
        response.append("Most effects would emerge in the near to medium term after implementation begins. IMMEDIATE (0–5 years)\n");
        response.append("6. Risk Structure:\n");
        response.append("The dominant risks are policy-design risks rather than irreversible tail events in this benchmark scenario. REVERSIBLE_EFFECTS\n");
        response.append("7. Reversibility:\n");
        response.append("The policy appears reversible through ordinary legislative or administrative changes. EASILY_REVERSIBLE\n");
        response.append("8. Summary:\n");
        response.append("The main policy effects depend on whether the bill's design aligns with the problem it is trying to solve and whether institutions can carry it out without introducing disproportionate harms or instability.\n\n");

        response.append("Long Report:\n");
        response.append("This mock interpretation summarizes how a policy analyst might evaluate the bill under the full PoliScore prompt. ");
        response.append("It focuses on structural quality rather than ideology and highlights the main mechanism, implementation assumptions, and likely tradeoffs. ");
        response.append("The bill's strongest and weakest dimensions depend on whether the intervention matches the underlying problem, whether the evidence base is credible, and whether the proposal can be executed without avoidable governance or risk failures.\n\n");

        response.append("Confidence:\n");
        response.append("72\n\n");

        response.append("Impact Stats:\n");
        for (TrackedIssue issue : TrackedIssue.values()) {
            response.append(issue.getName()).append(": ").append(defaultIssueScore(issue, expectedPass)).append("\n");
        }
        response.append("\n");

        response.append("Rating:\n");
        response.append(expectedPass ? "35" : "-35").append("\n\n");

        response.append("Casual Report:\n");
        response.append("This mock response says the bill has a clear policy shape and then checks whether the design is actually sound. ");
        response.append("The key question is whether the proposal matches the problem it wants to solve without creating bigger implementation or governance problems. ");
        response.append("That is why the structural analysis sections are the main signal used by PoliBench.\n\n");

        response.append("Short Report:\n");
        response.append("This mock bill interpretation follows the BillPrompt structure and gives a parseable policy analysis output. ");
        response.append("It includes all required sections, a complete structural analysis, issue scores, and a rating. ");
        response.append("The benchmark then parses that output and checks the requested pillar directly. ");
        response.append("That keeps the mock path close to the real parser-based evaluation flow.\n");

        return response.toString();
    }

    private StructuralAnalysis toStructuralAnalysis(Pillar pillar) {
        if (pillar == null) {
            return StructuralAnalysis.PRECISION;
        }

        return switch (pillar) {
            case PRECISION -> StructuralAnalysis.PRECISION;
            case EVIDENCE -> StructuralAnalysis.EVIDENCE;
            case FEASIBILITY -> StructuralAnalysis.FEASIBILITY;
            case BUDGET -> StructuralAnalysis.BUDGET;
            case FAIRNESS -> StructuralAnalysis.FAIRNESS;
            case GOVERNANCE -> StructuralAnalysis.GOVERNANCE;
            case RISK -> StructuralAnalysis.RISK;
        };
    }

    private boolean defaultPassForNonFocusPillar(StructuralAnalysis pillar, boolean expectedPass) {
        return expectedPass && (pillar == StructuralAnalysis.FEASIBILITY || pillar == StructuralAnalysis.GOVERNANCE);
    }

    private String structuralExplanation(StructuralAnalysis pillar, String billText, boolean pass) {
        return switch (pillar) {
            case PRECISION -> pass
                    ? "The bill text describes a mechanism that plausibly connects the intervention to the stated problem."
                    : "The bill text suggests a weak or poorly targeted causal link between the intervention and the stated problem.";
            case EVIDENCE -> pass
                    ? "The proposal appears consistent with a policy design that could plausibly rest on meaningful empirical support."
                    : "The proposal does not show a convincing empirical basis for believing the intervention will work as intended.";
            case FEASIBILITY -> pass
                    ? "The administrative burden appears manageable under ordinary institutional constraints."
                    : "The implementation burden appears likely to exceed realistic administrative or logistical capacity.";
            case BUDGET -> pass
                    ? "The design does not obviously create reckless long-term fiscal obligations in this benchmark scenario."
                    : "The design raises concerns about waste, distortion, or unsustainable resource commitments.";
            case FAIRNESS -> pass
                    ? "The distribution of benefits and burdens appears defensible on the face of the bill text."
                    : "The bill appears to concentrate burdens or exclusions in a way that is difficult to justify structurally.";
            case GOVERNANCE -> pass
                    ? "The proposal appears compatible with ordinary oversight, accountability, and transparency safeguards."
                    : "The proposal appears vulnerable to weak oversight, concentrated discretion, or abuse.";
            case RISK -> pass
                    ? "The bill does not obviously introduce severe fragility or perverse incentives in this simplified scenario."
                    : "The bill appears likely to create unintended consequences, fragility, or incentive problems.";
        };
    }

    private String defaultIssueScore(TrackedIssue issue, boolean expectedPass) {
        if (issue == TrackedIssue.OverallBenefitToSociety) {
            return expectedPass ? "22" : "-22";
        }

        if (issue == TrackedIssue.Government || issue == TrackedIssue.EconomicsAndCommerce) {
            return expectedPass ? "10" : "-10";
        }

        return "N/A";
    }
}
