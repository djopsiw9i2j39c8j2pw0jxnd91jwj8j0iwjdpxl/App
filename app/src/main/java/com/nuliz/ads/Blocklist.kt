package com.nuliz.ads

import android.content.Context

class Blocklist private constructor(private val domains: HashMap<String, Category>) {

    val size get() = domains.size

    fun match(host: String): Category? {
        var h = host
        while (true) {
            domains[h]?.let { return it }
            val dot = h.indexOf('.')
            if (dot < 0) return null
            h = h.substring(dot + 1)
        }
    }

    companion object {
        @Volatile
        private var cached: Blocklist? = null

        fun get(context: Context): Blocklist =
            cached ?: synchronized(this) { cached ?: load(context).also { cached = it } }

        private fun load(context: Context): Blocklist {
            val map = HashMap<String, Category>(300_000)
            for (category in Category.entries) {
                context.assets.open("blocklists/${category.file}").bufferedReader().useLines { lines ->
                    lines.map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .forEach { map[it] = category }
                }
            }
            return Blocklist(map)
        }
    }
}
