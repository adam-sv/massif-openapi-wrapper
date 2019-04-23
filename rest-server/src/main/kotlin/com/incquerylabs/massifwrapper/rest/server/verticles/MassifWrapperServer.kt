package com.incquerylabs.massifwrapper.rest.server.verticles

import com.incquerylabs.massifwrapper.rest.server.data.MassifManagerEvents
import com.incquerylabs.massifwrapper.rest.server.data.MassifManagerMessage
import com.incquerylabs.massifwrapper.rest.server.data.MassifWrapperServerConfiguration
import com.incquerylabs.massifwrapper.rest.server.services.MASSIF_WRAPPER_SERVER_STATUS_SERVICE_ADDRESS
import com.incquerylabs.massifwrapper.rest.server.services.MassifWrapperServerStatus
import io.vertx.core.AbstractVerticle
import io.vertx.core.CompositeFuture
import io.vertx.core.DeploymentOptions
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.eventbus.EventBus
import io.vertx.core.http.HttpServer
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.ResponseContentTypeHandler
import io.vertx.ext.web.handler.ResponseTimeHandler
import io.vertx.core.eventbus.Message
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.handler.StaticHandler
import io.vertx.serviceproxy.ServiceProxyBuilder
import java.util.*

class MassifWrapperServer (
        val configuration: MassifWrapperServerConfiguration
): AbstractVerticle(){
    private lateinit var massifManagerId: String
    private lateinit var baseRouter: Router

    protected lateinit var eventBus: EventBus
    protected val logger = LoggerFactory.getLogger(javaClass)!!
    protected lateinit var serverStatus : MassifWrapperServerStatus

    override fun start(startFuture: Future<Void>) {

        serverStatus = ServiceProxyBuilder(vertx).setAddress(MASSIF_WRAPPER_SERVER_STATUS_SERVICE_ADDRESS).build(MassifWrapperServerStatus::class.java)

        vertx.executeBlocking<Void>(Handler { f ->
            eventBus = vertx.eventBus()

            val server = vertx.createHttpServer()
            server.connectionHandler { conn ->
                serverStatus.registerClientConnection(conn.remoteAddress().toString())
                conn.closeHandler {
                    serverStatus.deregisterClientConnection(conn.remoteAddress().toString())
                }
            }

            baseRouter = Router.router(vertx);
            // Serve the static pages
            val staticRouter = Router.router(vertx)
            staticRouter.route().handler(StaticHandler.create())
            baseRouter.mountSubRouter("/static", staticRouter)

            val apiRouter = createRestApiRouter()
            baseRouter.mountSubRouter(configuration.apiMountPoint, apiRouter)
            logger.info("Routers mounted.")

            val massifFuture = initializeMassif()


            val serverFuture = Future.future<HttpServer>()
            server
                    .requestHandler {
                        if (logger.isTraceEnabled) {
                            logger.trace("Request $it")
                        }
                        baseRouter.accept(it)
                    }
                    .listen(configuration.restAPIPort, serverFuture)

            val serverListeningFuture = CompositeFuture.all(massifFuture,serverFuture)
            serverListeningFuture.map {
                logger.info("IncQuery Massif-Matlab wrapper web API started listening on ${configuration.restAPIPort}")
                f.complete()
            }.otherwise {
                f.fail(it)
            }

        }, startFuture)

    }

    private fun initializeMassif(): Future<Unit> {
        logger.info("Initializing Massif...")

        val future = Future.future<String>()
        val massifManager = MassifManager(configuration)
        val options = DeploymentOptions().setWorker(true)
        vertx.deployVerticle(massifManager, options, future)

        return future.map {
            massifManagerId = it
        }.otherwise {
            throw IllegalStateException("Could not deploy massif", it)
        }
    }

    private fun createRestApiRouter(): Router {
        val apiRouter = Router.router(vertx)
        apiRouter.let {
            it.route().handler(BodyHandler.create())
            it.route().handler(ResponseTimeHandler.create())
            it.route().failureHandler(this::failureHandler)
            it.route().handler(ResponseContentTypeHandler.create())
            val contentTypeJson = "application/json"
            //massif
            it.post("/loadMDL") //mdl2emf
                    .produces(contentTypeJson)
                    .handler(this::mdl2emfHandler)
            it.post("/loadEMF")
                    .produces(contentTypeJson)
                    .handler(this::emf2mdlHandler)
            //matlab
            it.get("/fetch")
                    .produces(contentTypeJson)
                    .handler(this::massifFetch)
            it.post("/runscript")
                    .produces(contentTypeJson)
                    .handler(this::scriptHandler)
            it.post("/runcommand")
                    .produces(contentTypeJson)
                    .handler(this::commandHandler)
            // server management
            it.get("/status")
                    .produces(contentTypeJson)
                    .handler(this::serverStatus)
        }
        return apiRouter
    }

    protected fun invokeService(address: String, replyAddress: String, requestJson: JsonObject, reply: Future<Message<JsonObject>>) {
        val consumer = eventBus.consumer<JsonObject>(replyAddress)
        consumer.handler { message ->
            consumer.unregister()
            val body = message.body()
            if (logger.isDebugEnabled) {
                logger.debug("Received reply: ${body.encodePrettily()}")
            }
            reply.tryComplete(message)
        }
        // TODO timeout to fail future
        eventBus.send(address, requestJson)
    }

    private fun createFuture(routingContext: RoutingContext): Future<Message<JsonObject>> {
        val replyFuture = Future.future<Message<JsonObject>>()

        replyFuture.map { message ->
            replyWithServiceMessage(message, routingContext)
        }.otherwise {
            routingContext.fail(it)
        }

        return replyFuture
    }
    private fun mdl2emfHandler(routingContext: RoutingContext){
        val body = routingContext.bodyAsJson
        val replyFuture = createFuture(routingContext)
        massifMdl2Emf(body as JsonObject, replyFuture)
    }

    private fun emf2mdlHandler(routingContext: RoutingContext){
        val body = routingContext.bodyAsJson
        val replyFuture = createFuture(routingContext)
        massifEmf2Mdl(body, replyFuture)
    }

    private fun massifFetch(routingContext: RoutingContext){
        val replyFuture = createFuture(routingContext)
        massifFetch(replyFuture)
    }

    private fun scriptHandler(routingContext: RoutingContext){
        val body = routingContext.bodyAsJson
        val replyFuture = createFuture(routingContext)
        matlabRunScript(body, replyFuture)
    }

    private fun commandHandler(routingContext: RoutingContext){
        val bodyAsJson = routingContext.bodyAsJson
        val replyFuture = createFuture(routingContext)
        matlabRunCommand(bodyAsJson, replyFuture)
    }

    private fun massifMdl2Emf(bodyAsJson: JsonObject, replyFuture: Future<Message<JsonObject>>){
        logger.info("Execute MDL2EMF transformation and loading model into matlab")
        val replyAddress = UUID.randomUUID().toString()
        val message = MassifManagerMessage(MassifManagerEvents.MDL2EMF, bodyAsJson, replyAddress)
        val requestJson = JsonObject.mapFrom(message)
        invokeService(MASSIF_WRAPPER_MASSIF_MANAGER_ADDRESS, replyAddress, requestJson, replyFuture)
    }

    private fun massifEmf2Mdl(bodyAsJson: JsonObject, replyFuture: Future<Message<JsonObject>>){
        logger.info("Execute EMF2MDL transformation and loading model into matlab")
        val replyAddress = UUID.randomUUID().toString()
        val message = MassifManagerMessage(MassifManagerEvents.EMF2MDL, bodyAsJson, replyAddress)
        val requestJson = JsonObject.mapFrom(message)
        invokeService(MASSIF_WRAPPER_MASSIF_MANAGER_ADDRESS, replyAddress, requestJson, replyFuture)
    }

    private fun massifFetch(replyFuture: Future<Message<JsonObject>>){
        logger.info("Execute model fetch")
        val replyAddress = UUID.randomUUID().toString()
        val message = MassifManagerMessage(MassifManagerEvents.FETCH, LinkedHashMap<String, String>(), replyAddress)
        val requestJson = JsonObject.mapFrom(message)
        invokeService(MASSIF_WRAPPER_MASSIF_MANAGER_ADDRESS, replyAddress, requestJson, replyFuture)
    }

    private fun matlabRunScript(bodyAsJson: JsonObject, replyFuture: Future<Message<JsonObject>>){
        logger.info("Execute script")
        val replyAddress = UUID.randomUUID().toString()
        val message = MassifManagerMessage(MassifManagerEvents.RUNSCRIPT, bodyAsJson, replyAddress)
        val requestJson = JsonObject.mapFrom(message)
        invokeService(MASSIF_WRAPPER_MASSIF_MANAGER_ADDRESS, replyAddress, requestJson, replyFuture)
    }

    private fun matlabRunCommand(bodyAsJson: JsonObject, replyFuture: Future<Message<JsonObject>>){
        logger.info("Execute command")
        val replyAddress = UUID.randomUUID().toString()
        val message = MassifManagerMessage(MassifManagerEvents.RUNCOMMAND, bodyAsJson, replyAddress)
        val requestJson = JsonObject.mapFrom(message)
        invokeService(MASSIF_WRAPPER_MASSIF_MANAGER_ADDRESS, replyAddress, requestJson, replyFuture)
    }

    private fun serverStatus(routingContext: RoutingContext) {
        serverStatus.getServerStatus(Handler {
            if(it.succeeded()) {
                val status = it.result()
                if(status.serverExceptions.isEmpty()) {
                    routingContext.response().setStatusCode(200).end(
                            JsonObject.mapFrom(status).encodePrettily()
                    )
                } else {
                    routingContext.response().setStatusCode(500).end(
                            JsonObject.mapFrom(status).encodePrettily()
                    )
                }
            } else {
                routingContext.fail(it.cause())
            }
        })
    }

    protected fun convertToOneLiner(failureMessage: String) = failureMessage.replace("\n", "\\n").replace("\r", "\\r")

    protected fun failureHandler(routingContext: RoutingContext) {
        val failure = routingContext.failure()
        logger.error("Handle failure", failure)
        if(routingContext.response().closed()){
            logger.warn("Response already closed")
        } else {
            try {
                val failureMessage = failure.message ?: failure.toString()
                val message = convertToOneLiner(failureMessage)
                when (failure) {
                    is NoSuchElementException -> routingContext.response().setStatusMessage(message).setStatusCode(404).end()
                    else -> routingContext.response().setStatusMessage(message).setStatusCode(500).end()
                }
                logger.error("Failure response sent")
            } catch (e: Exception) {
                routingContext.response().setStatusMessage("Error while sending failure response").setStatusCode(500).end()
            }
        }
    }

    protected fun replyWithServiceMessage(message: Message<JsonObject>, routingContext: RoutingContext) {
        val body = message.body()
        replyWithServiceMessage(body, routingContext)
    }

    protected fun replyWithServiceMessage(serviceMessageBody: JsonObject, routingContext: RoutingContext) {
        try {
            when {
                serviceMessageBody.containsKey("result") -> {
                    val responseBody = serviceMessageBody.getJsonObject("result").encodePrettily()
                    routingContext.response()
                            .setStatusCode(200)
                            .end(responseBody)
                }
                serviceMessageBody.containsKey("error") -> {
                    val errorJson = serviceMessageBody.getJsonObject("error")
                    val responseBody = errorJson.getJsonObject("details").encodePrettily()
                    routingContext.response()
                            .setStatusMessage(convertToOneLiner(errorJson.getString("message", "Unknown error")))
                            .setStatusCode(errorJson.getInteger("code", 200))
                            .end(responseBody)
                }
                else -> routingContext.fail(IllegalStateException("Reply JSON format is not correct: ${serviceMessageBody.encodePrettily()}"))
            }
        } catch (e: Exception) {
            if(routingContext.response().closed()){
                logger.warn("Response already closed")
            } else {
                routingContext.fail(IllegalStateException("Could not send reply", e))
            }
        }
    }
}