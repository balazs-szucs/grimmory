import {Book, BookType, ReadStatus} from './book.model';

export interface AppPageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
  hasPrevious: boolean;
}

export interface AppBookSummary {
  id: number;
  title: string;
  authors: string[];
  thumbnailUrl: string | null;
  readStatus: ReadStatus | null;
  personalRating: number | null;
  seriesName: string | null;
  seriesNumber: number | null;
  libraryId: number;
  addedOn: string | null;
  lastReadTime: string | null;
  readProgress: number | null;
  primaryFileType: string | null;
  coverUpdatedOn: string | null;
  audiobookCoverUpdatedOn: string | null;
  isPhysical: boolean | null;
  publishedDate: string | null;
  pageCount: number | null;
  ageRating: number | null;
  contentRating: string | null;
  metadataMatchScore: number | null;
  fileSizeKb: number | null;
}

export interface AppFilterOptions {
  authors: CountedOption[];
  languages: LanguageOption[];
  readStatuses: CountedOption[];
  fileTypes: CountedOption[];
  categories: CountedOption[];
  publishers: CountedOption[];
  series: CountedOption[];
  tags: CountedOption[];
  moods: CountedOption[];
  narrators: CountedOption[];
  ageRatings: CountedOption[];
  contentRatings: CountedOption[];
  matchScores: CountedOption[];
  publishedYears: CountedOption[];
  fileSizes: CountedOption[];
  personalRatings: CountedOption[];
  amazonRatings: CountedOption[];
  goodreadsRatings: CountedOption[];
  hardcoverRatings: CountedOption[];
  lubimyczytacRatings: CountedOption[];
  ranobedbRatings: CountedOption[];
  audibleRatings: CountedOption[];
  pageCounts: CountedOption[];
  shelfStatuses: CountedOption[];
  comicCharacters: CountedOption[];
  comicTeams: CountedOption[];
  comicLocations: CountedOption[];
  comicCreators: CountedOption[];
  shelves: CountedOption[];
  libraries: CountedOption[];
}

export interface CountedOption {
  name: string;
  count: number;
}

export interface LanguageOption {
  code: string;
  label: string;
  count: number;
}

export interface AppBookFilters {
  libraryId?: number;
  shelfId?: number;
  magicShelfId?: number;
  unshelved?: boolean;
  status?: string[];
  search?: string;
  fileType?: string[];
  minRating?: number;
  maxRating?: number;
  authors?: string[];
  language?: string[];
  series?: string[];
  category?: string[];
  publisher?: string[];
  tag?: string[];
  mood?: string[];
  narrator?: string[];
  ageRating?: string[];
  contentRating?: string[];
  matchScore?: string[];
  publishedDate?: string[];
  fileSize?: string[];
  personalRating?: string[];
  amazonRating?: string[];
  goodreadsRating?: string[];
  hardcoverRating?: string[];
  lubimyczytacRating?: string[];
  ranobedbRating?: string[];
  audibleRating?: string[];
  pageCount?: string[];
  shelfStatus?: string[];
  comicCharacter?: string[];
  comicTeam?: string[];
  comicLocation?: string[];
  comicCreator?: string[];
  shelves?: string[];
  libraries?: string[];
  filterMode?: 'and' | 'or' | 'not';
}

export interface AppBookSort {
  field: string;
  dir: 'asc' | 'desc';
}

export interface AppBookDetail extends AppBookSummary {
  subtitle: string | null;
  description: string | null;
  categories: string[];
  publisher: string | null;
  publishedDate: string | null;
  pageCount: number | null;
  isbn13: string | null;
  language: string | null;
  goodreadsRating: number | null;
  goodreadsReviewCount: number | null;
  libraryName: string;
  shelves: AppShelfSummary[];
  fileTypes: string[];
  files: AppBookFile[];
  epubProgress: EpubProgress | null;
  pdfProgress: PdfProgress | null;
  cbxProgress: CbxProgress | null;
  audiobookProgress: AudiobookProgress | null;
  koreaderProgress: KoreaderProgress | null;
}

export interface AppShelfSummary {
  id: number;
  name: string;
  color: string | null;
}

export interface AppBookFile {
  id: number;
  fileName: string;
  fileSizeKb: number;
  bookType: string;
}

export interface EpubProgress {
  cfi: string;
  href: string;
  percentage: number;
  updatedAt: string;
}

export interface PdfProgress {
  page: number;
  percentage: number;
  updatedAt: string;
}

export interface CbxProgress {
  page: number;
  percentage: number;
  updatedAt: string;
}

export interface AudiobookProgress {
  positionMs: number;
  trackIndex: number;
  percentage: number;
  updatedAt: string;
}

export interface KoreaderProgress {
  percentage: number;
  device: string;
  deviceId: string;
  lastSyncTime: string;
}

export function mapAppBookToBook(summary: AppBookSummary | AppBookDetail): Book {
  const isDetail = 'libraryName' in summary;
  const detail = isDetail ? summary as AppBookDetail : null;

  return {
    id: summary.id,
    libraryId: summary.libraryId,
    libraryName: detail?.libraryName || '',
    readStatus: (summary.readStatus as ReadStatus) ?? ReadStatus.UNSET,
    personalRating: summary.personalRating ?? 0,
    addedOn: summary.addedOn || undefined,
    lastReadTime: summary.lastReadTime || undefined,
    isPhysical: summary.isPhysical ?? false,
    fileSizeKb: summary.fileSizeKb ?? undefined,
    metadataMatchScore: summary.metadataMatchScore,
    metadata: {
      bookId: summary.id,
      title: summary.title,
      subtitle: detail?.subtitle || undefined,
      authors: summary.authors ?? [],
      seriesName: summary.seriesName,
      seriesNumber: summary.seriesNumber,
      coverUpdatedOn: summary.coverUpdatedOn,
      audiobookCoverUpdatedOn: summary.audiobookCoverUpdatedOn,
      publishedDate: summary.publishedDate ?? undefined,
      pageCount: summary.pageCount,
      description: detail?.description || undefined,
      categories: detail?.categories || [],
      publisher: detail?.publisher || undefined,
      isbn13: detail?.isbn13 || undefined,
      language: detail?.language || undefined,
      goodreadsRating: detail?.goodreadsRating,
      goodreadsReviewCount: detail?.goodreadsReviewCount,
    },
    primaryFile: summary.primaryFileType
      ? {
          id: 0,
          bookId: summary.id,
          bookType: summary.primaryFileType as BookType,
          extension: summary.primaryFileType.toLowerCase()
        }
      : undefined,
    pdfProgress: detail?.pdfProgress
      ? {page: detail.pdfProgress.page, percentage: detail.pdfProgress.percentage}
      : null,
    epubProgress: detail?.epubProgress
      ? {cfi: detail.epubProgress.cfi, percentage: detail.epubProgress.percentage}
      : null,
    cbxProgress: detail?.cbxProgress
      ? {page: detail.cbxProgress.page, percentage: detail.cbxProgress.percentage}
      : null,
    audiobookProgress: detail?.audiobookProgress
      ? {
          positionMs: detail.audiobookProgress.positionMs,
          trackIndex: detail.audiobookProgress.trackIndex,
          percentage: detail.audiobookProgress.percentage
        }
      : null,
    shelves: detail?.shelves ? detail.shelves.map(s => ({id: s.id, name: s.name})) : [],
  } as unknown as Book;
}
