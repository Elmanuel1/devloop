package com.tosspaper.precon;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ExternalId(
        @JsonProperty("externalTaskId") String externalTaskId,
        @JsonProperty("externalFileId") String externalFileId
) {}
