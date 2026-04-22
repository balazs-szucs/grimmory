import {AppBookDetail} from './app-book.model';
import {CbxViewerSetting, EbookViewerSetting, PdfViewerSetting} from './book.model';
import {NewPdfReaderSetting} from '../../settings/user-management/user.service';

export interface AppBookContextResponse {
  book: AppBookDetail;
  pdfSettings: PdfViewerSetting | null;
  newPdfSettings: NewPdfReaderSetting | null;
  ebookSettings: EbookViewerSetting | null;
  cbxSettings: CbxViewerSetting | null;
}
