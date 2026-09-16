package com.tof.manycourse.api

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class MemoryCookieJar : CookieJar {
    private val store = LinkedHashMap<String, MutableList<Cookie>>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val list = store.getOrPut(url.host) { mutableListOf() }
        cookies.forEach { c ->
            list.removeAll { it.name == c.name && it.path == c.path }
            list.add(c)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val list = store[url.host] ?: return emptyList()
        val now = System.currentTimeMillis()
        list.removeAll { it.expiresAt < now }
        return list.filter { it.matches(url) }
    }

    @Synchronized
    fun valueOf(url: String, name: String): String? {
        val u = url.toHttpUrlOrNull() ?: return null
        return loadForRequest(u).firstOrNull { it.name == name }?.value
    }
}