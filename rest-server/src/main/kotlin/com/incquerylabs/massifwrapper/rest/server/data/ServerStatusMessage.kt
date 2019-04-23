package com.incquerylabs.massifwrapper.rest.server.data

import io.vertx.codegen.annotations.DataObject
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

@DataObject
data class ServerStatusMessage(
        val concurrentClientConnections: Int = 0,
        val serverExceptions: List<String> = mutableListOf()
) {
    constructor(json: JsonObject) : this(
            json.getInteger("concurrentClientConnections", 0),
            json.getJsonArray("serverExceptions", JsonArray()).map { it.toString() }
    )
    fun toJson(): JsonObject {
        return JsonObject.mapFrom(this)
    }
}