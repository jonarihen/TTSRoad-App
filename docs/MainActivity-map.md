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
| `TtsRoadApp` | 418 |
| `UpdateOverlay` | 518 |
| `LoginScreen` | 576 |
| `AppTopBar` | 719 |
| `PrimaryNavigationBar` | 749 |
| `AppBottomBar` | 805 |
| `MainScaffold` | 818 |
| `LibraryScreen` | 1088 |
| `RefreshablePane` | 1249 |
| `FictionScreen` | 1281 |
| `ChapterListControls` | 2096 |
| `ChapterBulkSheet` | 2152 |
| `PlayerTitleBlock` | 2290 |
| `BookmarkMarkerLane` | 2336 |
| `PlayerActionButton` | 2396 |
| `PlayerIconAction` | 2427 |
| `PlayerScreenBody` | 2480 |
| `PlayerScreen` | 2851 |
| `SleepTimerOption` | 3445 |
| `LastHeardBanner` | 3483 |
| `SettingsSectionHeader` | 3587 |
| `ListeningScreen` | 3599 |
| `ListeningScreenBody` | 3642 |
| `SettingsRootScreen` | 3750 |
| `SettingsScreen` | 3775 |
| `BookmarksScreen` | 4725 |
| `BookmarkCard` | 4842 |
| `PronunciationReportsScreen` | 4891 |
| `PronunciationReportsBody` | 4990 |
| `PronunciationReportCard` | 5066 |
| `QueueScreen` | 5121 |
| `QueueRow` | 5342 |
| `QueueWhenEmptyCard` | 5420 |
| `DevicesScreen` | 5458 |
| `DeviceCard` | 5615 |
| `DeviceDetail` | 5672 |
| `ConfirmDialog` | 5685 |
| `MiniPlayerBar` | 5719 |
| `PlaybackErrorBanner` | 5807 |
| `TransportIconButton` | 5827 |
| `ContinueHero` | 5888 |
| `HorizontalChapterRail` | 5950 |
| `HorizontalFictionRail` | 5976 |
| `ChapterTile` | 5995 |
| `FictionTile` | 6051 |
| `ChapterRow` | 6102 |
| `FictionBookmarksSection` | 6267 |
| `StaleDownloadsNotice` | 6334 |
| `FictionsScreen` | 6379 |
| `FictionSortSheet` | 6666 |
| `BrowseScopeTabs` | 6724 |
| `TagFilterBar` | 6780 |
| `TagFilterSheet` | 6835 |
| `AddFictionSection` | 6933 |
| `AddFictionSheet` | 7129 |
| `FictionGridCard` | 7478 |
| `FictionDetailHeader` | 7572 |
| `ShareUrlRow` | 7859 |
| `AudiobookExportListItem` | 7884 |
| `FictionNotificationSettingsSheet` | 7943 |
| `FictionMaintenanceSheet` | 8068 |
| `FictionPollScopeSheet` | 8244 |
| `ProductionMeta` | 8317 |
| `FictionEditScreen` | 8343 |
| `VoicePickerSheet` | 8823 |
| `VoicePickerContent` | 8842 |
| `VoiceChoiceRow` | 8948 |
| `MetadataField` | 8996 |
| `CoverFill` | 9027 |
| `CoverThumb` | 9052 |
| `EmptyCard` | 9078 |
| `ReaderScreen` | 9099 |
| `ReaderPage` | 9372 |
| `ReaderParagraph` | 9456 |
| `ReaderSettingsSheet` | 9528 |
| `ReaderOptionChip` | 9615 |
| `LoadingPane` | 9641 |
| `ErrorPane` | 9653 |
| `PlaybackSkipsSetting` | 9680 |
| `SettingsItem` | 9715 |
