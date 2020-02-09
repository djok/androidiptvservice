package com.bytecoders.iptvservicecommunicator.protocol

import com.bytecoders.iptvservicecommunicator.protocol.api.Message
import com.bytecoders.iptvservicecommunicator.protocol.api.MessageEndpointInformation
import com.bytecoders.iptvservicecommunicator.protocol.api.MessagePlayListConfig
import kotlinx.serialization.ImplicitReflectionSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.parse
import kotlinx.serialization.stringify

@UseExperimental(ImplicitReflectionSerializer::class)
class MessageParser {
    private val iptvAPIModule = SerializersModule {
        polymorphic(Message::class) {
            MessageEndpointInformation::class with MessageEndpointInformation.serializer()
            MessagePlayListConfig::class with MessagePlayListConfig.serializer()
        }
    }

    private val jsonSerializer = Json(context = iptvAPIModule)

    internal fun serializeMessage(message: Message): String = jsonSerializer.stringify(message)
    internal fun parseMessage(message: String): Message? = jsonSerializer.parse(message)
}