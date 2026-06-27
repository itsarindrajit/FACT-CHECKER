package com.factchecker.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactCheckReport {

    private String url;
    private String transcript;
    private List<ClaimVerdict> verdicts;
    private double truthScore;
    private Instant analyzedAt;

    /**
     * Calculates the overall truth score as a percentage (0-100).
     * TRUE = 1.0, MISLEADING = 0.5, FALSE = 0.0, weighted by confidence.
     */
    public static double calculateTruthScore(List<ClaimVerdict> verdicts) {
        if (verdicts == null || verdicts.isEmpty()) return 0.0;

        double totalWeight = 0;
        double truthSum = 0;

        for (ClaimVerdict v : verdicts) {
            double weight = v.getConfidence();
            double score = switch (v.getVerdict()) {
                case TRUE -> 1.0;
                case MISLEADING -> 0.5;
                case FALSE -> 0.0;
            };
            truthSum += score * weight;
            totalWeight += weight;
        }

        return totalWeight > 0 ? Math.round((truthSum / totalWeight) * 100.0) : 0.0;
    }
}
