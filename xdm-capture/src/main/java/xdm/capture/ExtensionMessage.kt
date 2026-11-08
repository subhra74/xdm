// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.capture

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * One captured request: what the desktop extension posts to `/download`, `/media` and the tab
 * messages, and what the Android browser builds from its own hooks.
 */
@Serializable
data class ExtensionMessage(
    val url: String? = null,
    val cookie: String? = null,
    val requestHeaders: MutableMap<String, List<String>>? = null,
    val responseHeaders: MutableMap<String, List<SafeStr>>? = null,
    val filename: String? = null,
    val file: String? = null,
    val method: String? = null,
    val userAgent: String? = null,
    val tabUrl: String? = null,
    val tabId: String? = null,
    val tabTitle: String? = null,
    val referer: String? = null,
    val fileSize: Long? = null,
    val mimeType: String? = null,
    val vid: Long? = null,
    /** webRequest's resource type for a captured `/media` request ("media", "xmlhttprequest", ...); null from older extensions. */
    val resourceType: String? = null,
)

@Serializable
@JvmInline // Required for inline classes in JVM
value class SafeStr(@Serializable(with = IntAsStringSerializer::class) val value: String)

object IntAsStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("IntAsString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }

    override fun deserialize(decoder: Decoder): String {
        return when (val value = decoder.decodeSerializableValue(JsonElement.serializer())) {
            is JsonPrimitive -> value.content  // Handles both int and string input
            else -> throw SerializationException("Expected primitive for string value")
        }
    }
}
