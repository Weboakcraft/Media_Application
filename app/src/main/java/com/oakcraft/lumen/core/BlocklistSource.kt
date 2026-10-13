package com.oakcraft.lumen.core

/**
 * A remote list of domains to block. All of these publish in either `hosts` format
 * (`0.0.0.0 example.com`) or one-domain-per-line; the parser handles both.
 */
data class BlocklistSource(
    val id: String,
    val title: String,
    val description: String,
    val url: String
) {
    companion object {
        val ALL: List<BlocklistSource> = listOf(
            BlocklistSource(
                id = "stevenblack",
                title = "StevenBlack — ads & trackers",
                description = "Widely used consolidated hosts file. A sensible default on its own.",
                url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
            ),
            BlocklistSource(
                id = "adguard-dns",
                title = "AdGuard DNS filter",
                description = "Maintained for DNS-level filtering, so it has few false positives.",
                url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_1.txt"
            ),
            BlocklistSource(
                id = "easyprivacy",
                title = "EasyPrivacy (DNS subset)",
                description = "Tracking and telemetry endpoints rather than display ads.",
                url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_11.txt"
            ),
            BlocklistSource(
                id = "malware",
                title = "Malware domains",
                description = "Known phishing and malware hosts. Low risk of breaking sites.",
                url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_8.txt"
            )
        )

        val DEFAULT_IDS: Set<String> = setOf("stevenblack", "malware")

        fun byId(id: String): BlocklistSource? = ALL.firstOrNull { it.id == id }
    }
}
