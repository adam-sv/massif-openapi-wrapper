package com.incquerylabs.massifwrapper.rest.server.data

data class MassifWrapperServerConfiguration(
        val apiMountPoint: String = "/massifwrapper",
        val restAPIPort: Int = 8234,
        val useRMIConnection: Boolean = false,
        val matlabRMIAddress: String = "127.0.0.1",
        val matlabRMIPort: Int = 1098,
        val matlabRMIServiceName: String = "MatlabModelProvider",
        val tempModelFileLocation: String = "massif-wrapper/rest-server/",
        val mdlTempModelName: String = "temp_mdl",
        val emfTempModelName: String = "temp_emf",
        val scriptTempName: String = "temp_script"
)

enum class MassifManagerEvents {
    NONE,
    MDL2EMF,
    EMF2MDL,
    FETCH,
    RUNSCRIPT,
    RUNCOMMAND
}

data class MassifManagerMessage(
        val event: MassifManagerEvents = MassifManagerEvents.NONE,
        val data: Any = Any(),
        val replyAddress: String = "undefined"
)
