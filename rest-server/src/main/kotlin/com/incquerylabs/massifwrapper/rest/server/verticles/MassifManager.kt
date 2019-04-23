package com.incquerylabs.massifwrapper.rest.server.verticles


import br.com.embraer.massif.commandevaluation.client.MatlabClient
import com.incquerylabs.massifwrapper.rest.server.data.MassifManagerEvents
import com.incquerylabs.massifwrapper.rest.server.data.MassifManagerMessage
import com.incquerylabs.massifwrapper.rest.server.data.MassifWrapperServerConfiguration
import com.incquerylabs.massifwrapper.rest.server.services.SimulinkAPILogger
import hu.bme.mit.massif.communication.ICommandEvaluator
import hu.bme.mit.massif.communication.command.MatlabCommandFactory
import hu.bme.mit.massif.communication.commandevaluation.CommandEvaluatorImpl
import hu.bme.mit.massif.simulink.api.ModelObject
import hu.bme.mit.massif.simulink.api.Importer
import hu.bme.mit.massif.simulink.api.Exporter
import hu.bme.mit.massif.simulink.api.util.ImportMode
import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.eventbus.EventBus
import io.vertx.core.json.JsonObject
import io.vertx.core.logging.LoggerFactory
import io.vertx.kotlin.core.json.array
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.io.File
import hu.bme.mit.massif.communication.matlabengine.MatlabEngineEvaluator
import hu.bme.mit.massif.simulink.cli.util.CLIInitializationUtil
import java.nio.file.FileSystems

import org.eclipse.viatra.query.runtime.api.ViatraQueryEngineOptions;
import org.eclipse.viatra.query.runtime.localsearch.matcher.integration.LocalSearchEMFBackendFactory;
import org.eclipse.viatra.query.runtime.rete.matcher.ReteBackendFactory;

const val MASSIF_WRAPPER_MASSIF_MANAGER_ADDRESS = "massif-wrapper-manager-service"

class MassifManager (config: MassifWrapperServerConfiguration) : AbstractVerticle() {
    protected val config = config

    protected val logger = LoggerFactory.getLogger(javaClass)!!
    protected val simulinkLogger = SimulinkAPILogger(logger)
    protected lateinit var eventBus: EventBus
    protected lateinit var commandEvaluator: ICommandEvaluator
    protected lateinit var commandFactory: MatlabCommandFactory
    protected lateinit var mdlModel: ModelObject
    protected lateinit var importer: Importer
    protected lateinit var exporter: Exporter
    protected lateinit var matlabClient: MatlabClient
    protected lateinit var tempMdlFile: File
    protected lateinit var tempEMFFile: File
    protected lateinit var tempScriptFile: File
    protected var oldSysOut = System.out

    override fun start(startFuture: Future<Void>) {
        super.start()
        eventBus = vertx.eventBus()
        vertx.executeBlocking<Void>(Handler { f ->
            eventBus.consumer<JsonObject>(MASSIF_WRAPPER_MASSIF_MANAGER_ADDRESS, { message ->
                val managerMessage = message.body().mapTo(MassifManagerMessage::class.java)

                val data = managerMessage.data as LinkedHashMap<String, String>
                handleEvent(managerMessage.event, data, managerMessage.replyAddress)
            })
            val massifFuture = startMassif()
            massifFuture.map {
                f.complete()
            }.otherwise {
                f.fail(it)
            }
        },startFuture)
    }

    private fun handleEvent(event: MassifManagerEvents, data: LinkedHashMap<String, String>, replyAddress: String) {
        if (logger.isDebugEnabled) {
            logger.trace("Handle massif manager event $event")
        }
        try {
            val matlablog = setSysOut() // MatlabEngine uses System.out to log, so we have to redirect it
            when (event) {

                MassifManagerEvents.MDL2EMF -> { // Simulink 2 EMF
                    clearWorkspace()
                    tempMdlFile.writeText(data.get("model")!!)
                    importer.traverseAndCreateEMFModel(ImportMode.FLATTENING); //TODO import mode should be wired to API...
                    importer.saveEMFModel(config.tempModelFileLocation + config.emfTempModelName);
                    sendReply(replyAddress, config.tempModelFileLocation + config.emfTempModelName + ".simulink", matlablog.toString())
                }

                MassifManagerEvents.EMF2MDL -> { // EMF 2 Simulink
                    clearWorkspace()
                    tempEMFFile.writeText(data.get("model")!!)
                    var separator = FileSystems.getDefault().separator
                    val loadedModel = exporter.loadSimulinkModel(tempEMFFile.toPath().parent.toAbsolutePath().toString() + separator + tempEMFFile.nameWithoutExtension)
                    exporter.export(loadedModel, commandFactory)
                    exporter.saveSimulinkModel(tempMdlFile.toPath().parent.toString() + separator + tempMdlFile.nameWithoutExtension,"mdl")
                    sendReply(replyAddress, config.tempModelFileLocation + config.mdlTempModelName + ".mdl", matlablog.toString())
                }

                MassifManagerEvents.FETCH -> { // Returns the model from MATLAB as EMF
                    importer.traverseAndCreateEMFModel(ImportMode.FLATTENING);
                    importer.saveEMFModel(config.tempModelFileLocation + config.emfTempModelName);
                    sendReply(replyAddress, config.tempModelFileLocation + config.emfTempModelName + ".simulink", matlablog.toString())
                }

                MassifManagerEvents.RUNSCRIPT -> {
                    tempScriptFile.writeText(data.get("script")!!)
                    val run = commandFactory.run()
                    run.addParam(config.tempModelFileLocation + config.scriptTempName + ".m")
                    run.execute()
                    sendReply(replyAddress, JsonObject().put("code", "200").put("message", "Script executed successfully").put("matlablog", matlablog.toString()))
                }

                MassifManagerEvents.RUNCOMMAND -> {
                    val commandName = data.get("command") as String
                    val outputArgCount = data.get("outputArgumentCount") as Int
                    val parameters = data.get("parameters") as ArrayList<Any>
                    val command = commandFactory.customCommand(commandName, outputArgCount)
                    parameters.forEach {
                        if (it is Double){
                            command.addParam(it as Double)
                        }
                        else if (it is String){
                            command.addParam(it as String)
                         }
                    }
                    command.execute()
                    sendReply(replyAddress, JsonObject().put("code", "200").put("message", "Command executed successfully").put("matlablog", matlablog.toString()))
                }
                MassifManagerEvents.NONE -> sendErrorReply(replyAddress,"NONE event should not be sent")
            }
            resetSysOut() // Reset to the original sysout
        } catch (ex : Exception) {
            resetSysOut()
            sendExceptionReply(replyAddress, ex)
        }
    }

    protected fun clearWorkspace(){ // Clears temp files, and closes Simulink models, also creates new exporter/importer
        importer = Importer(mdlModel, simulinkLogger) // TODO Massif should provide a way to clear the importer and exporter
        exporter = Exporter(simulinkLogger)
        tempMdlFile.delete()
        tempEMFFile.delete()
        val closeAllCommand = commandFactory.customCommand("bdclose", 0)
        closeAllCommand.addParam("all")
        closeAllCommand.execute()
    }

    protected fun startMassif() : Future<String>{
        val future = Future.future<String>()
        try {
            // Generic setup
            CLIInitializationUtil.setupEnvironment()

            // https://www.eclipse.org/viatra/documentation/releases.html#_dependency_updates_in_query_runtime
            // https://github.com/IncQueryLabs/massif-wrapper/issues/15
            ViatraQueryEngineOptions.setSystemDefaultBackends(ReteBackendFactory.INSTANCE, ReteBackendFactory.INSTANCE, LocalSearchEMFBackendFactory.INSTANCE);

            if(config.useRMIConnection == true){ // RMI based Massif
                matlabClient = MatlabClient(config.matlabRMIAddress, config.matlabRMIPort, config.matlabRMIServiceName)
                commandEvaluator = CommandEvaluatorImpl(matlabClient)
            } else { // Matlab Engine based Massif
                commandEvaluator = MatlabEngineEvaluator(logger.isDebugEnabled)
            }
            commandFactory = MatlabCommandFactory(commandEvaluator)
            commandEvaluator.evaluateCommand("massif = massif_functions()", 0);
            // Set up the importer
            mdlModel = ModelObject(config.mdlTempModelName , commandEvaluator)
            tempMdlFile = File(config.tempModelFileLocation + config.mdlTempModelName + ".mdl")
            val modelpath = config.tempModelFileLocation
            //create addpath command manually, since .addpath sets outputargumentcount to 1 which results in erroneous behaviour
            val addModelPath = commandFactory.addPath()
            addModelPath.addParam(modelpath)
            addModelPath.execute()
            mdlModel.loadPath = modelpath
            importer = Importer(mdlModel, simulinkLogger)

            //Set up the exporter
            tempEMFFile = File(config.tempModelFileLocation + config.emfTempModelName + ".simulink")
            exporter = Exporter(simulinkLogger)

            //Set up the script
            tempScriptFile = File(config.tempModelFileLocation + config.scriptTempName + ".m")

        }catch (e: Exception){
            future.fail(e)
            return future
        }
        future.complete()
        return future
    }


    protected fun sendReply(replyAddress: String, modelPath: String, matlablog: String){
        val savedModel = File(modelPath)
        val model =  savedModel.readText()
        sendReply(replyAddress, createResponseFromModel(model, matlablog))
    }

    protected  fun createResponseFromModel (model: String, matlablog: String): JsonObject{
        val response = JsonObject()
                .put("code", 200)
                .put("message", "Model transformation was successful")
                .put("matlablog", matlablog)
                .put("model", model)
        return response
    }

    protected fun sendReply(replyAddress: String, responseJson: JsonObject) {
        eventBus.send(replyAddress, JsonObject().put("result", responseJson))
    }

    protected fun sendExceptionReply(replyAddress: String, cause: Throwable) {
        logger.error("Sending exception reply", cause)
        val outputStream = ByteArrayOutputStream()
        val printStream = PrintStream(outputStream, true, StandardCharsets.UTF_8.name())
        cause.printStackTrace(printStream)
        val trace = String(outputStream.toByteArray(), StandardCharsets.UTF_8)
        val details = json {
            obj("trace" to array(trace.trim().replace('\t',' ').split(System.lineSeparator())))
        }
        var message: String? = cause.message
        if (message == null) {
            message = cause.toString()
        }
        val errorJson = JsonObject()
                .put("message", message)
                .put("code", 500)
                .put("details", details)
        eventBus.send(replyAddress, JsonObject().put("error", errorJson))
    }

    protected fun sendErrorReply(replyAddress: String, errorMessage: String, errorCode: Int = 500, details: JsonObject = JsonObject()) {
        logger.error("Sending error reply $errorMessage - $errorCode")
        if(logger.isDebugEnabled) {
            logger.debug(details)
        }
        val errorJson = JsonObject()
                .put("message", "NONE event should not be sent")
                .put("code", 500)
                .put("details", details)
        eventBus.send(replyAddress, JsonObject().put("error", errorJson))
    }

    protected fun setSysOut():ByteArrayOutputStream {
        val outputStream = ByteArrayOutputStream()
        oldSysOut = System.out
        System.setOut(PrintStream(outputStream));
        return outputStream
    }

    protected fun resetSysOut(){
        System.setOut(oldSysOut)
    }
}
