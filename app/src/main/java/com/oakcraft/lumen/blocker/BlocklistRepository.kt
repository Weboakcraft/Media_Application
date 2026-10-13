package com.oakcraft.lumen.blocker

import android.content.Context
import android.util.Log
import com.oakcraft.lumen.core.BlocklistSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Downloads blocklists, normalises them to bare domains, and keeps the merged result
 * on disk so the tunnel can start instantly without touching the network.
 */
class BlocklistRepository(private val context: Context) {

    private val cacheFile: File get() = File(context.filesDir, "blocklist.txt")

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    data class RefreshResult(
        val domains: Int,
        val failedSources: List<String>
    )

    /**
     * Fetches each selected source and rewrites the on-disk list.
     *
     * A source that fails is reported rather than aborting the refresh, so one dead URL
     * cannot leave the user with no protection at all.
     */
    suspend fun refresh(sourceIds: Set<String>): RefreshResult = withContext(Dispatchers.IO) {
        val domains = HashSet<String>(200_000)
        val failed = mutableListOf<String>()

        for (id in sourceIds) {
            val source = BlocklistSource.byId(id) ?: continue
            try {
                download(source.url) { reader ->
                    reader.lineSequence().forEach { line ->
                        parseLine(line)?.let(domains::add)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Blocklist source failed: ${source.id}", e)
                failed += source.title
            }
        }

        if (domains.isNotEmpty()) {
            // Write to a sibling file first so an interrupted refresh cannot corrupt
            // the list the tunnel is relying on.
            val temp = File(context.filesDir, "blocklist.tmp")
            temp.bufferedWriter().use { writer ->
                domains.forEach { writer.append(it).append('\n') }
            }
            if (!temp.renameTo(cacheFile)) {
                temp.copyTo(cacheFile, overwrite = true)
                temp.delete()
            }
        }

        RefreshResult(domains = domains.size, failedSources = failed)
    }

    /** Reads the cached list, or an empty set when no refresh has happened yet. */
    suspend fun loadCached(): Set<String> = withContext(Dispatchers.IO) {
        if (!cacheFile.exists()) return@withContext emptySet()
        try {
            cacheFile.bufferedReader().use { reader ->
                reader.lineSequence()
                    .filter { it.isNotBlank() }
                    .toHashSet()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read cached blocklist", e)
            emptySet()
        }
    }

    suspend fun cachedCount(): Int = loadCached().size

    private fun download(url: String, consume: (BufferedReader) -> Unit) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/plain")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} for $url")
            val body = response.body ?: error("Empty body for $url")
            consume(body.charStream().buffered())
        }
    }

    companion object {
        private const val TAG = "BlocklistRepository"

        /**
         * Normalises one line of a blocklist to a domain, or null if it carries no
         * domain we can act on at the DNS layer.
         *
         * Three shapes are accepted:
         *  - hosts entries, `0.0.0.0 ads.example.com`
         *  - plain domains, one per line
         *  - AdGuard network rules, `||ads.example.com^`
         *
         * AdGuard rules with modifiers or paths are skipped: those depend on the request
         * context, which a DNS answer cannot distinguish. Exception rules (`@@`) are
         * skipped too — treating them as blocks would be exactly backwards.
         */
        fun parseLine(rawLine: String): String? {
            var line = rawLine.trim()
            if (line.isEmpty()) return null
            if (line.startsWith("#") || line.startsWith("!") || line.startsWith(";")) return null
            if (line.startsWith("@@")) return null

            // Trim trailing comments.
            val hash = line.indexOf('#')
            if (hash > 0) line = line.substring(0, hash).trim()
            if (line.isEmpty()) return null

            if (line.startsWith("||")) {
                val end = line.indexOf('^')
                if (end <= 2) return null
                // Anything after the separator is a modifier or path we cannot honour.
                val tail = line.substring(end + 1)
                if (tail.isNotEmpty() && tail != "|") return null
                val host = line.substring(2, end)
                if (host.contains('/') || host.contains('*')) return null
                return validate(host)
            }

            val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
            val candidate = when {
                parts.size >= 2 && parts[0] in HOSTS_PREFIXES -> parts[1]
                parts.size == 1 -> parts[0]
                else -> return null
            }
            return validate(candidate)
        }

        private val HOSTS_PREFIXES = setOf("0.0.0.0", "127.0.0.1", "::1", "::")

        private val SKIP_NAMES = setOf(
            "localhost", "localhost.localdomain", "local",
            "broadcasthost", "ip6-localhost", "ip6-loopback",
            "ip6-localnet", "ip6-mcastprefix", "ip6-allnodes",
            "ip6-allrouters", "ip6-allhosts", "0.0.0.0"
        )

        private fun validate(input: String): String? {
            val domain = input.trim().trimEnd('.').lowercase()
            if (domain.length !in 2..253) return null
            if (domain in SKIP_NAMES) return null
            if (!domain.contains('.')) return null

            var previousWasDot = true
            for (ch in domain) {
                val ok = ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_'
                if (!ok) return null
                if (ch == '.' && previousWasDot) return null
                previousWasDot = ch == '.'
            }
            if (previousWasDot) return null
            return domain
        }
    }
}
