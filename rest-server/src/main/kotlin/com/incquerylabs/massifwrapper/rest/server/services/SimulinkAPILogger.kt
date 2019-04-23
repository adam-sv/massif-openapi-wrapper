package com.incquerylabs.massifwrapper.rest.server.services

import hu.bme.mit.massif.simulink.api.util.ISimulinkAPILogger
import io.vertx.core.logging.Logger
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets

class SimulinkAPILogger(logger: Logger) : ISimulinkAPILogger {
    protected val logger = logger

    override fun debug(p0: String?) {
       // if (logger.isDebugEnabled) {
            logger.debug("SimulinkAPILogger Debug: ${p0}")
       // }
    }

    override fun error(p0: String?, p1: Throwable?) {
        logger.error("SimulinkAPILogger Error: ${p0}")
        val outputStream = ByteArrayOutputStream()
        val printStream = PrintStream(outputStream, true, StandardCharsets.UTF_8.name())
        p1!!.printStackTrace(printStream)
    }

    override fun error(p0: String?) {
        logger.error("SimulinkAPILogger Error: ${p0}")
    }

    override fun isDebugging(): Boolean {
        return logger.isDebugEnabled
    }

    override fun warning(p0: String?) {
        logger.warn(p0)
    }
}