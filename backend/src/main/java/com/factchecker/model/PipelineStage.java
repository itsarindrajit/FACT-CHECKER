package com.factchecker.model;

public enum PipelineStage {
    VALIDATING_URL("Validating URL"),
    DOWNLOADING_AUDIO("Downloading Audio"),
    TRANSCRIBING("Transcribing"),
    EXTRACTING_CLAIMS("Extracting Claims"),
    SEARCHING_WEB("Searching Web"),
    FINALIZING_REPORT("Finalizing Report"),
    COMPLETE("Complete"),
    ERROR("Error");

    private final String displayName;

    PipelineStage(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
