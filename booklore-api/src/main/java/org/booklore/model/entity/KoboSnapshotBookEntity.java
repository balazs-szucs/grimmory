package org.booklore.model.entity;


import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "kobo_library_snapshot_book")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KoboSnapshotBookEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "snapshot_id", nullable = false)
    private KoboLibrarySnapshotEntity snapshot;

    @Column(name = "book_id", nullable = false)
    private Long bookId;

    @Column(name = "file_hash")
    private String fileHash;

    @Column(name = "metadata_updated_at")
    private Instant metadataUpdatedAt;

    @Column(name = "cover_hash")
    private String coverHash;

    @Column(name = "book_file_size")
    private Long bookFileSize;

    @Column(name = "read_progress_last_modified")
    private Instant readProgressLastModified;

    @Column(nullable = false)
    @Builder.Default
    private boolean synced = false;
}
