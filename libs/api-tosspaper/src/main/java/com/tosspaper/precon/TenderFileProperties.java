package com.tosspaper.precon;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "file.tender")
public class TenderFileProperties {

    private List<String> allowedContentTypes = List.of(
            "application/pdf",
            "image/png",
            "image/jpeg"
    );

    private Map<String, List<String>> contentTypeExtensions = Map.of(
            "application/pdf", List.of("pdf"),
            "image/png", List.of("png"),
            "image/jpeg", List.of("jpg", "jpeg")
    );

    private long maxFileSize = 209715200L; // 200MB

    private int maxFileNameLength = 255;
}
