package com.incquerylabs.massifwrapper.rest.server.services

import io.vertx.codegen.annotations.ProxyGen
import io.vertx.codegen.annotations.VertxGen
import com.incquerylabs.massifwrapper.rest.server.data.ServerStatusMessage
import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.impl.ConcurrentHashSet

const val MASSIF_WRAPPER_SERVER_STATUS_SERVICE_ADDRESS = "massif-wrapper-server-status-service"

@ProxyGen
@VertxGen
interface MassifWrapperServerStatus {

    fun registerClientConnection(conn: String)

    fun deregisterClientConnection(conn : String)

    fun getServerStatus(result: Handler<AsyncResult<ServerStatusMessage>>)
}

class MassifWrapperServerStatusImpl : MassifWrapperServerStatus {

    val serverStatus = ServerStatusAccumulator()

    override fun registerClientConnection(conn: String) {
        serverStatus.clientConnections.add(conn)
    }

    override fun deregisterClientConnection(conn: String) {
        serverStatus.clientConnections.remove(conn)
    }

    override fun getServerStatus(result: Handler<AsyncResult<ServerStatusMessage>>) {
        result.handle(Future.succeededFuture(serverStatus.toMessage()))
    }

}

data class ServerStatusAccumulator (
        val clientConnections: MutableSet<String> = ConcurrentHashSet<String>(),
        val serverExceptions: MutableSet<String> = ConcurrentHashSet<String>()
        ) {
    fun toMessage(): ServerStatusMessage {
        return ServerStatusMessage(
                clientConnections.size,
                serverExceptions.toList()
        )
    }
}
