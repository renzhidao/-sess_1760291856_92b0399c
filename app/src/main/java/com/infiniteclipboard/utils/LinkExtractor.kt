// 文件: app/src/main/java/com/infiniteclipboard/utils/LinkExtractor.kt
package com.infiniteclipboard.utils

import java.util.regex.Pattern

object LinkExtractor {
    private val URL_PATTERN = Pattern.compile(
        "(?i)\\b(?:(?:https?|ftp)://|www\\.)[\\w\\-+&@#/%?=~|!:,.;]*[\\w\\-+&@#/%=~|]"
    )

    fun extract(text: String): List<String> {
        val links = LinkedHashSet<String>()
        val m = URL_PATTERN.matcher(text)
        while (m.find()) {
            var url = m.group()
            if (url.startsWith("www.", true)) url = "http://$url"
            links.add(url)
        }
        return links.toList()
    }
}