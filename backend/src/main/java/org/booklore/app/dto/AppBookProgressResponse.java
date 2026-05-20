package org.booklore.app.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppBookProgressResponse {
    private Float readProgress;
    private String readStatus;
    private Instant lastReadTime;
    private AppBookDetail.EpubProgress epubProgress;
    private AppBookDetail.PdfProgress pdfProgress;
    private AppBookDetail.CbxProgress cbxProgress;
    private AppBookDetail.AudiobookProgress audiobookProgress;
    private AppBookDetail.KoreaderProgress koreaderProgress;
}
