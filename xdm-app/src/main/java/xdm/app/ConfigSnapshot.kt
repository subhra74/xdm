package xdm.app

import kotlin.reflect.KMutableProperty1

/**
 * Every settable [IAppConfig] value at one moment, for an editor that stays open while the rest of the
 * app keeps changing the config (Settings, now that it is not modal): it writes every value it shows,
 * so on its own it would undo a change made elsewhere meanwhile, like the download-complete window's
 * "don't show again" or a folder a download dialog remembered. [keepChangedElsewhere] puts those back.
 *
 * The values are listed by hand in [PROPERTIES], not found by reflection: the native image has no
 * reflection metadata for [IAppConfig]. A new config `var` must be added there; `ConfigSnapshotTest`
 * fails, naming it, until it is (or until it is excluded there as derived from another value).
 */
class ConfigSnapshot private constructor(private val values: List<Any?>) {

    /**
     * Called on the snapshot taken when the editor loaded its values, after it wrote them over [config].
     * Every value the editor left as it found it, but which [beforeWrite] (taken just before the
     * editor wrote) shows was changed meanwhile, gets that newer value back. Where both changed one,
     * the editor's value stays: the user just chose it.
     */
    fun keepChangedElsewhere(config: IAppConfig, beforeWrite: ConfigSnapshot) {
        PROPERTIES.forEachIndexed { i, prop ->
            val loaded = values[i]
            val elsewhere = beforeWrite.values[i]
            if (elsewhere != loaded && prop.get(config) == loaded) prop.setAny(config, elsewhere)
        }
    }

    /** One config property; [setAny] takes the value back from the untyped snapshot. */
    private class Prop<T>(private val property: KMutableProperty1<IAppConfig, T>) {
        val name: String get() = property.name
        fun get(config: IAppConfig): T = property.get(config)

        @Suppress("UNCHECKED_CAST")
        fun setAny(config: IAppConfig, value: Any?) = property.set(config, value as T)
    }

    companion object {
        /**
         * The interface's `var`s, the inherited core ones included. Left out: `showDownloadCompleteWindow`,
         * a view of [IAppConfig.downloadCompleteNotification] (restoring both could fight).
         */
        private val PROPERTIES: List<Prop<*>> = listOf(
            Prop(IAppConfig::downloadCompleteNotification), Prop(IAppConfig::runVirusScan),
            Prop(IAppConfig::runCommand), Prop(IAppConfig::showDownloadProgressWindow),
            Prop(IAppConfig::defaultDownloadFolder), Prop(IAppConfig::tempFolder),
            Prop(IAppConfig::autoRenameOnConflict), Prop(IAppConfig::shutdownAfterAllDone),
            Prop(IAppConfig::maxParallelDownloads), Prop(IAppConfig::rememberNone), Prop(IAppConfig::noneFolder),
            Prop(IAppConfig::folderHistory), Prop(IAppConfig::sortKey), Prop(IAppConfig::sortAscending),
            Prop(IAppConfig::categories), Prop(IAppConfig::minVideoSize), Prop(IAppConfig::lang),
            Prop(IAppConfig::theme), Prop(IAppConfig::uiScale), Prop(IAppConfig::themeMigrated),
            Prop(IAppConfig::notifiedUpdateVersion), Prop(IAppConfig::speedLimiterEnabled),
            Prop(IAppConfig::speedLimit), Prop(IAppConfig::startDownloadAutomatically),
            Prop(IAppConfig::overwriteExistingFiles), Prop(IAppConfig::fileExtensions),
            Prop(IAppConfig::defFileExtensions), Prop(IAppConfig::videoExtensions),
            Prop(IAppConfig::defVideoExtensions), Prop(IAppConfig::blockedHosts), Prop(IAppConfig::defBlockedHosts),
            Prop(IAppConfig::getServerTime), Prop(IAppConfig::maxSegments), Prop(IAppConfig::maxRetries),
            Prop(IAppConfig::useProxy), Prop(IAppConfig::socksProxy), Prop(IAppConfig::proxyHost),
            Prop(IAppConfig::proxyPort), Prop(IAppConfig::proxyUser), Prop(IAppConfig::proxyPass),
            Prop(IAppConfig::haltAfterDownload), Prop(IAppConfig::keepAwake), Prop(IAppConfig::runOnStartup),
            Prop(IAppConfig::customCommand), Prop(IAppConfig::virusScannerPath), Prop(IAppConfig::virusScannerArgs),
            Prop(IAppConfig::ignoreCertErrors), Prop(IAppConfig::readTimeoutSeconds),
            Prop(IAppConfig::skipDuplicateManifests), Prop(IAppConfig::detectSubtitles),
            Prop(IAppConfig::saveSubtitlesAsSrt), Prop(IAppConfig::markDownloadedFiles),
            Prop(IAppConfig::batchAsOneFromBrowser), Prop(IAppConfig::batchAsOneFromClipboard),
            Prop(IAppConfig::nativeWayland), Prop(IAppConfig::swingFileChooser), Prop(IAppConfig::forceIpv4),
            Prop(IAppConfig::commandRules), Prop(IAppConfig::listSelectionMode), Prop(IAppConfig::playSound),
            Prop(IAppConfig::completeSoundFile), Prop(IAppConfig::failedSoundFile),
            Prop(IAppConfig::torrentSeedAfterFinish), Prop(IAppConfig::torrentSeedRatioLimit),
            Prop(IAppConfig::torrentSeedTimeLimitMinutes), Prop(IAppConfig::torrentProgressMore),
            Prop(IAppConfig::torrentPort), Prop(IAppConfig::torrentPortMapping), Prop(IAppConfig::torrentDht),
            Prop(IAppConfig::torrentLsd), Prop(IAppConfig::torrentEncryption), Prop(IAppConfig::torrentDownloadLimit),
            Prop(IAppConfig::torrentUploadLimit), Prop(IAppConfig::torrentClient),
            Prop(IAppConfig::torrentKeepSession), Prop(IAppConfig::torrentAddTrackers), Prop(IAppConfig::torrentTrackerList), Prop(IAppConfig::torrentPex),
            Prop(IAppConfig::ytDlpPath), Prop(IAppConfig::ffmpegPath), Prop(IAppConfig::denoPath),
            Prop(IAppConfig::ytDlpExtraArgs), Prop(IAppConfig::useFfmpegMuxer),
        )

        /** The names of the properties a snapshot covers; for the test that checks none is missing. */
        val propertyNames: List<String> get() = PROPERTIES.map { it.name }

        fun of(config: IAppConfig) = ConfigSnapshot(PROPERTIES.map { it.get(config) })
    }
}
