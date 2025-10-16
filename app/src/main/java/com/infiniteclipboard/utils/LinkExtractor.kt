// 文件: app/src/main/java/com/infiniteclipboard/utils/LinkExtractor.kt
package com.infiniteclipboard.utils

import java.util.regex.Pattern

object LinkExtractor {

    private val URL_PATTERN = Pattern.compile(
        "(?i)\\b(?:https?://|www\\.)[-A-Za-z0-9+&@#/%?=~_|!:,.;]*[-A-Za-z0-9+&@#/%=~_|]"
    )

    fun extractLinks(text: String): List<String> {
        val links = mutableListOf<String>()
        val matcher = URL_PATTERN.matcher(text)
        while (matcher.find()) {
            var url = matcher.group()
            if (url.startsWith("www.", ignoreCase = true)) {
                url = "http://$url"
            }
            if (!links.contains(url)) {
                links.add(url)
            }
        }
        return links
    }

    fun hasLinks(text: String): Boolean {
        return URL_PATTERN.matcher(text).find()
    }
}