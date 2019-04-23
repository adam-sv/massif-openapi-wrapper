package com.incquerylabs.massifwrapper.rest.server

import com.incquerylabs.massifwrapper.rest.server.data.MassifWrapperServerConfiguration
import com.incquerylabs.massifwrapper.rest.server.services.MASSIF_WRAPPER_SERVER_STATUS_SERVICE_ADDRESS
import com.incquerylabs.massifwrapper.rest.server.services.MassifWrapperServerStatus
import com.incquerylabs.massifwrapper.rest.server.services.MassifWrapperServerStatusImpl
import com.incquerylabs.massifwrapper.rest.server.verticles.MassifWrapperServer
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.core.logging.LoggerFactory
import io.vertx.serviceproxy.ServiceBinder
import java.io.File
import java.nio.file.Files
import java.net.URLDecoder

fun main(args: Array<String>) {

    val logger = LoggerFactory.getLogger("com.incquerylabs.matlab.wrapper.rest.server.ServerRunnerKt")!!
    val vertx = Vertx.vertx()
    val classLoader = MassifWrapperServer::class.java.classLoader

    val configfile = if (args.isNotEmpty())
        File(args[0])
    else if (classLoader.getResource("massif-wrapper-config.json") != null){
        File(URLDecoder.decode(classLoader.getResource("massif-wrapper-config.json").file, "UTF-8")!!);
    } else {
        File("")
    }

    val config = if (configfile.exists()) {
        val content = String(Files.readAllBytes(configfile.toPath()))
        JsonObject(content).mapTo(MassifWrapperServerConfiguration::class.java)
    } else {
        logger.warn("Config file ${configfile} does not exist!")
        MassifWrapperServerConfiguration()
    }

    ServiceBinder(vertx).setAddress(MASSIF_WRAPPER_SERVER_STATUS_SERVICE_ADDRESS).register(MassifWrapperServerStatus::class.java, MassifWrapperServerStatusImpl())

    val server = MassifWrapperServer(config)

    vertx.deployVerticle(server, { deploy ->
        try {
            if (deploy.failed()) {
                error("Deploy failed: ${deploy.cause().message}\n${deploy.cause().printStackTrace()}")
            } else {
                logger.info("Deploy successful")
            }
        } catch (e: Exception){
            e.printStackTrace()
        }
    })
}