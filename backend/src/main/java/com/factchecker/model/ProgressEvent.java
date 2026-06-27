package com.factchecker.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProgressEvent {

    private PipelineStage stage;
    private String message;
    private FactCheckReport report;  // Only populated on COMPLETE stage
    private String error;            // Only populated on ERROR stage

    public static ProgressEvent stage(PipelineStage stage, String message) {
        return ProgressEvent.builder()
                .stage(stage)
                .message(message)
                .build();
    }

    public static ProgressEvent complete(FactCheckReport report) {
        return ProgressEvent.builder()
                .stage(PipelineStage.COMPLETE)
                .message("Analysis complete")
                .report(report)
                .build();
    }

    public static ProgressEvent error(String error) {
        return ProgressEvent.builder()
                .stage(PipelineStage.ERROR)
                .message("An error occurred")
                .error(error)
                .build();
    }
}
