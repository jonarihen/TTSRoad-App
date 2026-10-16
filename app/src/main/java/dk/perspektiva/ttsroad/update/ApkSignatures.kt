package dk.perspektiva.ttsroad.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * Who signed an APK: its package name, the SHA-256 digests of the certificates signing it now, and
 * every certificate it has proven it may rotate from (the current ones included).
 */
internal data class SigningIdentity(
    val packageName: String,
    val signers: Set<String>,
    val history: Set<String> = signers,
)

/**
 * Why a downloaded update must not reach the installer, or null when it may (#369).
 *
 * Android refuses an update signed with a different key anyway, but only after the person has
 * already been through the unknown-sources prompt, and with a system error that does not say what
 * went wrong. Checking first lets the app say it plainly and keep the file away from the installer.
 *
 * The rule mirrors the platform's: the same set of current signers, or — for a single signer — a
 * new APK whose rotation history includes the installed app's certificate.
 */
internal fun updateSignerProblem(installed: SigningIdentity?, candidate: SigningIdentity?): String? = when {
    candidate == null || candidate.signers.isEmpty() ->
        "The downloaded file is not a readable, signed Android app, so it was not installed."
    installed == null || installed.signers.isEmpty() ->
        "Could not read this app's own signature to compare the update with, so it was not installed."
    candidate.packageName != installed.packageName ->
        "The download is a different app (${candidate.packageName}), so it was not installed."
    candidate.signers == installed.signers -> null
    installed.signers.size == 1 && candidate.signers.size == 1 &&
        candidate.history.containsAll(installed.signers) -> null
    else ->
        "This update is signed with a different key than the app on this phone. Android would " +
            "refuse to install it over this version, so it was not installed."
}

/** Default verifier: compares the APK at [apk] with the installed app. */
internal fun verifyUpdateApk(context: Context, apk: File): String? {
    val pm = context.packageManager
    return updateSignerProblem(
        installed = installedSigningIdentity(pm, context.packageName),
        candidate = archiveSigningIdentity(pm, apk),
    )
}

internal fun certificateDigest(signature: Signature): String = certificateDigest(signature.toByteArray())

internal fun certificateDigest(certificate: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(certificate).joinToString("") { "%02x".format(it) }

private fun installedSigningIdentity(pm: PackageManager, packageName: String): SigningIdentity? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        signingIdentityOf(pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES))
            ?.let { return@runCatching it }
    }
    @Suppress("DEPRECATION")
    signingIdentityOf(pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES))
}.getOrNull()

private fun archiveSigningIdentity(pm: PackageManager, apk: File): SigningIdentity? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        signingIdentityOf(pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES))
            ?.let { return@runCatching it }
    }
    @Suppress("DEPRECATION")
    signingIdentityOf(pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES))
}.getOrNull()

private fun signingIdentityOf(info: PackageInfo?): SigningIdentity? {
    info ?: return null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.signingInfo?.let { signing ->
            return if (signing.hasMultipleSigners()) {
                val signers = signing.apkContentsSigners.orEmpty().map(::certificateDigest).toSet()
                SigningIdentity(info.packageName, signers)
            } else {
                // Oldest first, the current certificate last.
                val history = signing.signingCertificateHistory.orEmpty().map(::certificateDigest)
                SigningIdentity(info.packageName, setOfNotNull(history.lastOrNull()), history.toSet())
            }.takeIf { it.signers.isNotEmpty() }
        }
    }
    @Suppress("DEPRECATION")
    val signers = info.signatures.orEmpty().map(::certificateDigest).toSet()
    return SigningIdentity(info.packageName, signers).takeIf { signers.isNotEmpty() }
}
