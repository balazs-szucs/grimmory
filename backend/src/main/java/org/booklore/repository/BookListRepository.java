package org.booklore.repository;

import org.booklore.model.dto.BookListItemDTO;
import org.booklore.model.entity.BookEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BookListRepository extends JpaRepository<BookEntity, Long> {

    @Query(value = """
        SELECT
            b.id AS id,
            m.title AS title,
            m.subtitle AS subtitle,
            m.series_name AS seriesName,
            m.series_number AS seriesNumber,
            STRING_AGG(DISTINCT a.name, ', ' ORDER BY a.name) AS authors,
            STRING_AGG(DISTINCT t.name, ', ' ORDER BY t.name) AS tags,
            STRING_AGG(DISTINCT cat.name, ', ' ORDER BY cat.name) AS categories,
            b.book_cover_hash AS bookCoverHash,
            b.library_id AS libraryId,
            l.name AS libraryName,
            m.publisher AS publisher,
            m.published_date AS publishedDate,
            m.language AS language,
            m.rating AS rating,
            ubp.read_status AS readStatus,
            ubp.last_read_time AS lastReadTime,
            ubp.epub_progress_percent AS epubProgressPercent,
            ubp.pdf_progress_percent AS pdfProgressPercent,
            ubp.cbx_progress_percent AS cbxProgressPercent,
            ubp.kobo_progress_percent AS koboProgressPercent,
            (SELECT bf2.book_type
             FROM book_file bf2
             WHERE bf2.book_id = b.id
               AND bf2.is_book = true
             ORDER BY bf2.id
             LIMIT 1) AS primaryBookType,
            b.is_physical AS isPhysical
        FROM book b
        JOIN library l ON l.id = b.library_id
        LEFT JOIN book_metadata m ON m.book_id = b.id
        LEFT JOIN book_metadata_author_mapping bam ON bam.book_id = b.id
        LEFT JOIN author a ON a.id = bam.author_id
        LEFT JOIN book_metadata_tag_mapping btm ON btm.book_id = b.id
        LEFT JOIN tag t ON t.id = btm.tag_id
        LEFT JOIN book_metadata_category_mapping bcm ON bcm.book_id = b.id
        LEFT JOIN category cat ON cat.id = bcm.category_id
        LEFT JOIN user_book_progress ubp
               ON ubp.book_id = b.id AND ubp.user_id = :userId
        WHERE (b.deleted IS NULL OR b.deleted = false)
          AND b.library_id IN (:libraryIds)
        GROUP BY
            b.id, m.title, m.subtitle, m.series_name, m.series_number,
            b.book_cover_hash, b.library_id, l.name,
            m.publisher, m.published_date, m.language, m.rating,
            ubp.read_status, ubp.last_read_time,
            ubp.epub_progress_percent, ubp.pdf_progress_percent,
            ubp.cbx_progress_percent, ubp.kobo_progress_percent,
            b.is_physical
        ORDER BY m.title
        """,
        nativeQuery = true)
    List<BookListItemDTO> findBooksForListView(
        @Param("userId") Long userId,
        @Param("libraryIds") List<Long> libraryIds
    );
}
