import {AppBookSummary} from '../../book/model/app-book.model';

export interface AppDashboardResponse {
  scrollers: Record<string, AppBookSummary[]>;
}
