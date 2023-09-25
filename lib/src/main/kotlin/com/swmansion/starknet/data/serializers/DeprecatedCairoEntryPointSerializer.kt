package com.swmansion.starknet.data.serializers

import com.swmansion.starknet.data.types.DeprecatedCairoEntryPoint
import com.swmansion.starknet.extensions.toFelt
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

@OptIn(ExperimentalSerializationApi::class)
@Serializer(forClass = DeprecatedCairoEntryPoint::class)
object DeprecatedCairoEntryPointSerializer : KSerializer<DeprecatedCairoEntryPoint> {
    override fun deserialize(decoder: Decoder): DeprecatedCairoEntryPoint {
        val input = decoder as? JsonDecoder ?: throw SerializationException("Expected JsonInput for ${decoder::class}")

        val jsonObject = input.decodeJsonElement().jsonObject

        // This accepts both Int and Felt for offset,
        // as compiled contract code uses Int, but the RPC spec requires Felt.
        val offset = jsonObject.getValue("offset").jsonPrimitive.content.toBigIntegerOrNull()?.toFelt
            ?: jsonObject.getValue("offset").jsonPrimitive.content.toFelt
        val selector = jsonObject.getValue("selector").jsonPrimitive.content.toFelt

        return DeprecatedCairoEntryPoint(
            offset = offset,
            selector = selector,
        )
    }

    override val descriptor: SerialDescriptor
        get() = PrimitiveSerialDescriptor("DeprecatedCairoEntryPoint", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: DeprecatedCairoEntryPoint) {
        val jsonObject = buildJsonObject {
            put("offset", value.offset.hexString())
            put("selector", value.selector.hexString())
        }
        val output = encoder as? JsonEncoder ?: throw SerializationException("")

        output.encodeJsonElement(jsonObject)
    }
}
