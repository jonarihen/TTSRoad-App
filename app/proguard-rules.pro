-keep class dk.perspektiva.ttsroad.data.** { *; }
-keep class dk.perspektiva.ttsroad.player.HistorySnapshot { *; }
-keep class dk.perspektiva.ttsroad.player.PendingProgress { *; }
-keep class dk.perspektiva.ttsroad.widget.NowPlayingSnapshot { *; }
-keep class dk.perspektiva.ttsroad.update.UpdateManager$GithubRelease { *; }
-keep class dk.perspektiva.ttsroad.update.UpdateManager$GithubAsset { *; }
-keep class kotlin.Metadata { *; }
# SessionPlaybackErrors forwards Player.Listener callbacks through a java.lang.reflect.Proxy, by method name.
-keepclassmembers interface androidx.media3.common.Player$Listener { *; }
