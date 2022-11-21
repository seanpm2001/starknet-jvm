package com.swmansion.starknet.data

import com.swmansion.starknet.crypto.StarknetCurve
import com.swmansion.starknet.data.types.Felt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class TypedData(
    val types: Map<String, List<Type>>,
    val primaryType: String,
    val domainJSON: String,
    val messageJSON: String,
) {
    private val domain: JsonObject by lazy { Json.parseToJsonElement(domainJSON).jsonObject }
    private val message: JsonObject by lazy { Json.parseToJsonElement(messageJSON).jsonObject }

    @Serializable
    data class Type(val name: String, val type: String)

    private fun getDependencies(typeName: String): List<String> {
        val deps = mutableListOf(typeName)
        val toVisit = mutableListOf(typeName)

        while (toVisit.isNotEmpty()) {
            val type = toVisit.removeFirst()
            val params = types[type] ?: return emptyList()

            for (param in params) {
                val typeStripped = stripPointer(param.type)

                if (types.containsKey(typeStripped) && !deps.contains(typeStripped)) {
                    deps.add(typeStripped)
                    toVisit.add(typeStripped)
                }
            }
        }

        return deps
    }

    private fun encodeType(type: String): String {
        val deps = getDependencies(type)

        val sorted = deps.subList(1, deps.size).sorted().toTypedArray()
        val newDeps = listOf(deps[0], *sorted)

        val result = newDeps.joinToString("") { dependency ->
            "$dependency(${types[dependency]?.map { "${it.name}:${it.type}" }?.joinToString(",")})"
        }

        return result
    }

    private fun valueFromPrimitive(primitive: JsonPrimitive): Felt {
        if (primitive.isString) {
            val decimal = primitive.content.toLongOrNull()

            if (decimal != null) {
                return Felt(decimal)
            }

            return try {
                Felt.fromHex(primitive.content)
            } catch (e: Exception) {
                Felt.fromShortString(primitive.content)
            }
        }

        return Felt(primitive.long)
    }

    private fun encodeValue(typeName: String, value: JsonElement): Pair<String, Felt> {
        if (types.containsKey(typeName)) {
            return typeName to getStructHash(typeName, value as JsonObject)
        }

        if (types.containsKey(stripPointer(typeName))) {
            val array = value as JsonArray
            val hashes = array.map { struct -> getStructHash(stripPointer(typeName), struct as JsonObject) }
            val hash = StarknetCurve.pedersenOnElements(hashes)

            return typeName to hash
        }

        if (typeName == "felt*") {
            val array = value as JsonArray
            val feltArray = array.map { valueFromPrimitive(it.jsonPrimitive) }
            val hash = StarknetCurve.pedersenOnElements(feltArray)

            return typeName to hash
        }

        return "felt" to valueFromPrimitive(value.jsonPrimitive)
    }

    private fun encodeData(typeName: String, data: JsonObject): List<Felt> {
        val values = mutableListOf<Felt>()

        for (param in types.getValue(typeName)) {
            val encodedValue = encodeValue(param.type, data.getValue(param.name))
            values.add(encodedValue.second)
        }

        return values
    }

    fun getTypeHash(typeName: String): Felt {
        return selectorFromName(encodeType(typeName))
    }

    private fun getStructHash(typeName: String, data: JsonObject): Felt {
        val encodedData = encodeData(typeName, data)

        return StarknetCurve.pedersenOnElements(getTypeHash(typeName), *encodedData.toTypedArray())
    }

    private fun stripPointer(value: String): String {
        return value.removeSuffix("*")
    }

    fun getStructHash(typeName: String, data: String): Felt {
        val encodedData = encodeData(typeName, Json.parseToJsonElement(data).jsonObject)

        return StarknetCurve.pedersenOnElements(getTypeHash(typeName), *encodedData.toTypedArray())
    }

    fun getMessageHash(accountAddress: Felt): Felt {
        return StarknetCurve.pedersenOnElements(
            Felt.fromShortString("StarkNet Message"),
            getStructHash("StarkNetDomain", domain),
            accountAddress,
            getStructHash(primaryType, message),
        )
    }
}
