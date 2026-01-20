package com.tosspaper.aiengine.splitters;

import com.google.common.hash.Hashing;
import com.tosspaper.models.domain.FileObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility for splitting PDF documents into individual pages.
 * Useful for processing large PDFs by sending individual pages to Chunkr.
 */
@Component
public class PDFPageSplitter {
    
    /**
     * Split a PDF document into individual pages.
     * 
     * @param pdfData the PDF file data as byte array
     * @return list of byte arrays, each representing a single page
     * @throws IOException if PDF processing fails
     */
    public FileObject getPage(byte[] pdfData, int index) throws IOException {

        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(pdfData))) {
            PDDocument singlePageDoc = new PDDocument();
            singlePageDoc.addPage(document.getPage(index));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            singlePageDoc.save(baos);
            singlePageDoc.close();
            return FileObject.builder()
                    .content(baos.toByteArray())
                    .contentType("application/pdf")
                    .sizeBytes(baos.toByteArray().length)
                    .checksum(Hashing.sha256().hashBytes(baos.toByteArray()).toString())
                    .build();
        }
    }
    
    /**
     * Get the number of pages in a PDF document.
     * 
     * @param pdfData the PDF file data as byte array
     * @return number of pages
     * @throws IOException if PDF processing fails
     */
    public int getPageCount(byte[] pdfData) throws IOException {
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(pdfData))) {
            return document.getNumberOfPages();
        }
    }
}
