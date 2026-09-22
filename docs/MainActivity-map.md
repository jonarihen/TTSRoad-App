# `MainActivity.kt` composable map

`MainActivity.kt` is ~9.8k lines. **Do not read it whole** — it will exhaust an agent's context
before any work gets done. Use this map to find a starting line, then read a window around it.

**Line numbers drift with every edit.** Treat them as a hint, not a fact: confirm with

```bash
grep -n "fun TheName" app/src/main/java/dk/perspektiva/ttsroad/MainActivity.kt
```

Regenerate this table after a large refactor:

```bash
grep -n "^@Composable" -A3 app/src/main/java/dk/perspektiva/ttsroad/MainActivity.kt \
  | grep -E "fun [A-Z]" \
  | sed -E 's/^([0-9]+)-(private |internal |public )?fun ([A-Za-z0-9_]+).*/\1 \3/' \
  | sort -n
```

Screens that already live in their own file are **not** listed here: `NewChaptersScreen.kt`,
`ListeningStatsScreen.kt`, `ServerLogsScreen.kt`, `AccountSecuritySettings.kt`,
`ServerStorageSettings.kt`, `RecentListeningSettings.kt`, `NotificationPermission.kt`.

| Composable | Line |
| --- | --- |
| `TtsRoadApp` | 421 |
| `UpdateOverlay` | 521 |
| `LoginScreen` | 579 |
| `AppTopBar` | 722 |
| `PrimaryNavigationBar` | 752 |
| `AppBottomBar` | 808 |
| `MainScaffold` | 821 |
| `LibraryScreen` | 1091 |
| `RefreshablePane` | 1252 |
| `FictionScreen` | 1284 |
| `ChapterListControls` | 2099 |
| `ChapterBulkSheet` | 2155 |
| `PlayerTitleBlock` | 2293 |
| `BookmarkMarkerLane` | 2339 |
| `PlayerActionButton` | 2399 |
| `PlayerIconAction` | 2430 |
| `PlayerScreenBody` | 2483 |
| `PlayerScreen` | 2854 |
| `SleepTimerOption` | 3448 |
| `LastHeardBanner` | 3486 |
| `SettingsSectionHeader` | 3590 |
| `ListeningScreen` | 3602 |
| `ListeningScreenBody` | 3645 |
| `SettingsRootScreen` | 3753 |
| `SettingsScreen` | 3778 |
| `BookmarksScreen` | 4728 |
| `BookmarkCard` | 4845 |
| `PronunciationReportsScreen` | 4894 |
| `PronunciationReportsBody` | 4993 |
| `PronunciationReportCard` | 5069 |
| `QueueScreen` | 5124 |
| `QueueRow` | 5345 |
| `QueueWhenEmptyCard` | 5423 |
| `DevicesScreen` | 5461 |
| `DeviceCard` | 5618 |
| `DeviceDetail` | 5675 |
| `ConfirmDialog` | 5688 |
| `MiniPlayerBar` | 5722 |
| `PlaybackErrorBanner` | 5810 |
| `TransportIconButton` | 5830 |
| `ContinueHero` | 5891 |
| `HorizontalChapterRail` | 5953 |
| `HorizontalFictionRail` | 5979 |
| `ChapterTile` | 5998 |
| `FictionTile` | 6054 |
| `ChapterRow` | 6105 |
| `FictionBookmarksSection` | 6270 |
| `StaleDownloadsNotice` | 6337 |
| `FictionsScreen` | 6382 |
| `FictionSortSheet` | 6723 |
| `BrowseScopeTabs` | 6781 |
| `TagFilterBar` | 6837 |
| `TagFilterSheet` | 6892 |
| `SourceFilterBar` | 6970 |
| `SourceFilterSheet` | 7023 |
| `AddFictionSection` | 7120 |
| `AddFictionSheet` | 7316 |
| `FictionGridCard` | 7665 |
| `FictionDetailHeader` | 7759 |
| `ShareUrlRow` | 8046 |
| `AudiobookExportListItem` | 8071 |
| `FictionNotificationSettingsSheet` | 8130 |
| `FictionMaintenanceSheet` | 8255 |
| `FictionPollScopeSheet` | 8431 |
| `ProductionMeta` | 8504 |
| `FictionEditScreen` | 8530 |
| `VoicePickerSheet` | 9010 |
| `VoicePickerContent` | 9029 |
| `VoiceChoiceRow` | 9135 |
| `MetadataField` | 9183 |
| `CoverFill` | 9214 |
| `CoverThumb` | 9239 |
| `EmptyCard` | 9265 |
| `ReaderScreen` | 9286 |
| `ReaderPage` | 9559 |
| `ReaderParagraph` | 9643 |
| `ReaderSettingsSheet` | 9715 |
| `ReaderOptionChip` | 9802 |
| `LoadingPane` | 9828 |
| `ErrorPane` | 9840 |
| `PlaybackSkipsSetting` | 9867 |
| `SettingsItem` | 9902 |
