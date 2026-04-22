import {User} from '../../features/settings/user-management/user.service';
import {PublicAppSettings} from '../service/app-settings.service';
import {AppVersion} from '../service/version.service';
import {MenuCountsResponse} from '../service/menu-counts.service';
import {Library} from '../../features/book/model/library.model';
import {Shelf} from '../../features/book/model/shelf.model';

export interface AppBootstrapResponse {
  user: User;
  publicSettings: PublicAppSettings;
  version: AppVersion;
  menuCounts: MenuCountsResponse;
  libraries: Library[];
  shelves: Shelf[];
}
