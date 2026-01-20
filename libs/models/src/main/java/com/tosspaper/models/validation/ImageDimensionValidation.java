package com.tosspaper.models.validation;

import com.tosspaper.models.domain.FileObject;
import com.tosspaper.models.properties.FileProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Set;

/**
 * Validates image dimensions to exclude UI elements (e.g., signature icons, logos, banners)
 * that are not valid supply chain documents.
 *
 * Checks:
 * - Minimum width and height (filters tiny icons)
 * - Minimum area (filters small logos)
 * - Aspect ratio (filters extreme banners/strips)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageDimensionValidation implements FileValidation {
    
    private static final Set<String> IMAGE_CONTENT_TYPES = Set.of(
        "image/png",
        "image/jpeg",
        "image/jpg",
        "image/gif",
        "image/webp"
    );
    
    private final FileProperties fileProperties;
    
    @Override
    public ValidationResult validate(FileObject fileObject) {
        String contentType = fileObject.getContentType();
        
        // Early exit: skip validation for non-image files
        if (!isImageContentType(contentType)) {
            return ValidationResult.valid();
        }
        
        // Early exit: skip if file has no content
        if (fileObject.getContent() == null || fileObject.getContent().length == 0) {
            return ValidationResult.valid();
        }
        
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(fileObject.getContent()));
            
            // If image reading fails, log warning but don't block processing
            if (image == null) {
                log.warn("Failed to read image dimensions for file: {} (content type: {})", 
                    fileObject.getFileName(), contentType);
                return ValidationResult.valid();
            }
            
            int width = image.getWidth();
            int height = image.getHeight();

            // Early exit: skip validation if dimensions are invalid
            if (width <= 0 || height <= 0) {
                log.warn("Invalid image dimensions for file: {} (width: {}, height: {})",
                    fileObject.getFileName(), width, height);
                return ValidationResult.valid();
            }

            // Check minimum dimensions (filters out small icons)
            int minWidth = fileProperties.getMinImageWidth();
            int minHeight = fileProperties.getMinImageHeight();

            if (width < minWidth || height < minHeight) {
                String violation = String.format(
                    "Image dimensions %dx%d are below minimum %dx%d - likely a signature icon",
                    width, height, minWidth, minHeight);
                log.debug("Image validation failed for '{}': {}", fileObject.getFileName(), violation);
                return ValidationResult.invalid(violation);
            }

            // Check minimum area (filters out logos that pass dimension check)
            long minArea = fileProperties.getMinImageArea();
            long actualArea = (long) width * height;

            if (actualArea < minArea) {
                String violation = String.format(
                    "Image area %d pixels (%dx%d) is below minimum %d pixels - likely a signature element",
                    actualArea, width, height, minArea);
                log.debug("Image validation failed for '{}': {}", fileObject.getFileName(), violation);
                return ValidationResult.invalid(violation);
            }

            double aspectRatio = (double) width / height;
            double minAspectRatio = fileProperties.getMinAspectRatio();
            double maxAspectRatio = fileProperties.getMaxAspectRatio();

            // Validate aspect ratio against configured limits
            if (aspectRatio < minAspectRatio || aspectRatio > maxAspectRatio) {
                String violation = String.format(
                    "Image aspect ratio %.2f:1 is outside acceptable range (%.2f:1 to %.2f:1). " +
                    "Dimensions: %dx%d. This appears to be a UI element rather than a document.",
                    aspectRatio, minAspectRatio, maxAspectRatio, width, height);
                log.debug("Image validation failed for '{}': {}", fileObject.getFileName(), violation);
                return ValidationResult.invalid(violation);
            }

            log.trace("Image validation passed for '{}': {}x{}, aspect ratio {}",
                fileObject.getFileName(), width, height, String.format("%.2f", aspectRatio));
            return ValidationResult.valid();
            
        } catch (IOException e) {
            // Log error but don't block processing for corrupted images
            log.warn("Error reading image dimensions for file: {} - {}", 
                fileObject.getFileName(), e.getMessage());
            return ValidationResult.invalid("error reading image dimensions");
        }
    }
    
    private boolean isImageContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        String lowerContentType = contentType.toLowerCase();
        return IMAGE_CONTENT_TYPES.contains(lowerContentType);
    }
}


