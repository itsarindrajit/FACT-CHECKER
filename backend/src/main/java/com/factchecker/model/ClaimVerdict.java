package com.factchecker.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClaimVerdict {

    private int claimId;
    private String claim;
    private String category;
    private Verdict verdict;
    private String explanation;
    private String sourceUrl;
    private double confidence;
}
