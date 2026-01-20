package com.tosspaper.aiengine.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

/**
 * Configuration properties for the Virtual Filesystem (VFS).
 * The VFS provides isolated storage for document comparison data per company.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "app.vfs")
public class VfsProperties {

    /**
     * Root directory for VFS storage.
     * Structure: {root}/companies/{companyId}/pos/{poNumber}/...
     */
    @NotBlank(message = "VFS root directory must be specified")
    private String root = "/app/data/storage";

    /**
     * Whether to create directories automatically if they don't exist.
     */
    private boolean autoCreateDirectories = true;
}
