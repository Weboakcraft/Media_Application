package com.oakcraft.lumen.blocker

import java.util.concurrent.atomic.AtomicReference

/**
 * Decides whether a hostname should be answered or refused.
 *
 * Matching follows hosts-file semantics: a domain matches if it, or any of its parent
 * domains, is on the list. `ads.example.com` is blocked by an entry for `example.com`.
 * Lookups walk at most one label at a time, so the cost is proportional to the number
 * of dots in the name rather than the size of the list.
 *
 * The whole rule set is swapped atomically, which means a list refresh never leaves
 * the resolver reading a half-built set.
 */
class BlocklistEngine {

    private data class Rules(
        val blocked: Set<String>,
        val allowed: Set<String>
    )

    private val rules = AtomicReference(Rules(emptySet(), emptySet()))

    val size: Int get() = rules.get().blocked.size

    fun replace(blocked: Set<String>, allowed: Set<String>) {
        rules.set(Rules(blocked, allowed))
    }

    /**
     * @return true when the query should be refused.
     */
    fun isBlocked(host: String): Boolean {
        if (host.isEmpty()) return false
        val current = rules.get()
        if (current.blocked.isEmpty()) return false

        val name = host.lowercase().trimEnd('.')

        // An explicit allow on the name or any parent wins over every blocklist.
        if (matches(name, current.allowed)) return false
        return matches(name, current.blocked)
    }

    private fun matches(name: String, set: Set<String>): Boolean {
        if (set.isEmpty()) return false
        if (set.contains(name)) return true

        var index = name.indexOf('.')
        while (index in 0 until name.length - 1) {
            if (set.contains(name.substring(index + 1))) return true
            index = name.indexOf('.', index + 1)
        }
        return false
    }
}
