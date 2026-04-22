import {BookLoreUser} from '../../settings/user-management/user.service';
import {PublicAppSettings} from '../service/app-settings.service';
import {AppVersion} from '../service/version.service';
import {MenuCountsResponse} from '../service/menu-counts.service';

export interface AppBootstrapResponse {
  user: BookLoreUser;
  publicSettings: PublicAppSettings;
  version: AppVersion;
  menuCounts: MenuCountsResponse;
}
