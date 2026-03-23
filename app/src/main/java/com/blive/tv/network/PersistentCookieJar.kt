package com.blive.tv.network

import com.blive.tv.data.model.AuthCookie
import com.blive.tv.utils.AppRuntime
import com.blive.tv.utils.TokenManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class PersistentCookieJar : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val mapped = cookies.map { it.toAuthCookie() }
        TokenManager.updateCookies(AppRuntime.appContext, mapped)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return TokenManager.getCookieJar(AppRuntime.appContext)
            ?.cookies
            .orEmpty()
            .mapNotNull { it.toOkHttpCookie() }
            .filter { it.matches(url) }
    }

    private fun Cookie.toAuthCookie(): AuthCookie {
        return AuthCookie(
            name = name,
            value = value,
            domain = domain,
            path = path,
            hostOnly = hostOnly,
            persistent = persistent,
            httpOnly = httpOnly,
            secure = secure,
            expiresAt = expiresAt.takeIf { persistent }
        )
    }

    private fun AuthCookie.toOkHttpCookie(): Cookie? {
        if (name.isBlank() || value.isBlank() || domain.isBlank()) return null
        val builder = Cookie.Builder()
            .name(name)
            .value(value)
            .path(path.ifBlank { "/" })
            .apply {
                if (hostOnly) {
                    hostOnlyDomain(domain.trimStart('.'))
                } else {
                    domain(domain.trimStart('.'))
                }
                if (secure) secure()
                if (httpOnly) httpOnly()
                if (persistent) {
                    expiresAt(expiresAt ?: System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000)
                }
            }
        return runCatching { builder.build() }.getOrNull()
    }
}
