package br.com.softextv.player

import android.net.Uri
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.WebView
import org.json.JSONObject

/** Sends credentials only to the exact origin configured on the TV card. */
class WebPageLogin {
    private var lastCredentials = ""
    private var attempted = false
    private var generation = 0

    private fun sameOrigin(first: String, second: String): Boolean = runCatching {
        val a = Uri.parse(first)
        val b = Uri.parse(second)
        fun port(uri: Uri) = if (uri.port >= 0) uri.port else if (uri.scheme == "https") 443 else 80
        a.scheme in listOf("http", "https") && a.scheme == b.scheme &&
            !a.host.isNullOrBlank() && a.host.equals(b.host, ignoreCase = true) && port(a) == port(b)
    }.getOrDefault(false)

    private fun prepare(target: String, credentials: String?): JSONObject? {
        if (credentials.isNullOrBlank()) {
            lastCredentials = ""
            attempted = false
            generation++
            return null
        }
        val key = target + credentials
        if (key != lastCredentials) {
            lastCredentials = key
            attempted = false
        }
        return runCatching { JSONObject(credentials) }.getOrNull()?.takeIf {
            it.optString("username").isNotBlank() && it.optString("password").isNotEmpty()
        }
    }

    fun httpAuth(view: WebView, handler: HttpAuthHandler, host: String, target: String, credentials: String?) {
        val login = prepare(target, credentials)
        if (login == null || attempted || !sameOrigin(view.url.orEmpty(), target) ||
            !Uri.parse(target).host.equals(host, ignoreCase = true)) {
            handler.cancel()
            return
        }
        attempted = true
        handler.proceed(login.getString("username"), login.getString("password"))
    }

    fun pageFinished(view: WebView, url: String, target: String, credentials: String?) {
        val login = prepare(target, credentials) ?: return
        if (!sameOrigin(url, target) || !sameOrigin(view.url.orEmpty(), target)) return
        CookieManager.getInstance().flush()
        // Re-arm after reaching the requested dashboard, allowing session renewal.
        val reachedTarget = Uri.parse(url).path == Uri.parse(target).path
        if (attempted) {
            if (reachedTarget) {
                view.evaluateJavascript("!!document.querySelector('input[type=password]')") { result ->
                    if (result == "false") attempted = false
                }
            }
            return
        }
        val script = Regex("__LOGIN__|__TARGET__").replace(SCRIPT) {
            if (it.value == "__LOGIN__") login.toString() else JSONObject.quote(target)
        }
        val currentGeneration = ++generation
        fun poll(remaining: Int) {
            if (generation != currentGeneration || attempted || view.url != url || remaining <= 0) return
            view.evaluateJavascript(script) { result ->
                if (generation != currentGeneration) return@evaluateJavascript
                if (result == "\"submitted\"") attempted = true
                else if (result == "\"waiting\"") view.postDelayed({ poll(remaining - 1) }, 250)
            }
        }
        poll(80)
    }

    companion object {
        internal val SCRIPT = """
            (function() {
                const credentials = __LOGIN__;
                const target = new URL(__TARGET__);
                if (location.origin !== target.origin || window.__softexLoginStarted) return 'stopped';
                    const visible = el => el && !el.disabled && el.getClientRects().length > 0;
                    const password = Array.from(document.querySelectorAll('input[type="password"]')).find(visible);
                    if (!password) return 'waiting';
                    const form = password.closest('form');
                    if (!form || (form.action && new URL(form.action, location.href).origin !== target.origin)) return 'stopped';
                    const username = Array.from(form.querySelectorAll('input[name="user"], input[name="username"], input[name="login"], input[autocomplete="username"], input[type="email"], input[type="text"]')).find(visible);
                    const submit = Array.from(form.querySelectorAll('button[type="submit"], input[type="submit"], button:not([type])')).find(el => el.getClientRects().length > 0);
                    if (!username || !submit) return 'waiting';
                    if (submit.formAction && new URL(submit.formAction, location.href).origin !== target.origin) return 'stopped';
                    window.__softexLoginStarted = true;
                    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
                    function fill(input, value) {
                        setter.call(input, value);
                        input.dispatchEvent(new Event('input', {bubbles: true}));
                        input.dispatchEvent(new Event('change', {bubbles: true}));
                    }
                    fill(username, credentials.username);
                    fill(password, credentials.password);
                    setTimeout(function() { if (location.origin === target.origin) submit.click(); }, 150);
                    return 'submitted';
            })();
        """.trimIndent()
    }
}
