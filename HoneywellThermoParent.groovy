/**
 * IMPORT URL: https://raw.githubusercontent.com/HubitatCommunity/HoneywellThermoParent/main/HoneywellThermoParent.groovy
 *
 *  Total Comfort API
 *   
 *  Based on Code by Eric Thomas, Edited by Bob Jase, and C Steele
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *	  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *
 *
 * csteele: v2.0.4   refactored "getStatus" and "getHumidityStatus" to minimize try/catch scope
 *			     reorganized methods to clump Fan methods together, to clump Mode methods together, etc.
 *			     "getHumidityStatus" using asynchttpGet
 *			     refactored all the set heat/cool/up/down 
 * csteele: v2.0.3   refactored "setStatus" to accumulate UI button clicks
 *			     clarified some status/log messages
 *			     added both Outdoor Child devices
 * csteele: v2.0.2   put childParamMap into "state"
 * csteele: v2.0.1   Put setLastRunningMode into Child
 * csteele: v2.0.0   Initial Commit
 *
 * Forked from:
 * csteele: v1.3.20  Added "emergency/auxiliary" heat.
 *			     added fanOperatingState Attribute.
**/

import groovy.transform.Field

 public static String version()	{  return "v2.0.4"  }
 public static String tccSite() 	{  return "mytotalconnectcomfort.com"  }
 public static String type() 		{  return "Thermostat"  }

@Field static Map<String, Map> modeMap = [auto:5, cool:3, heat:1, off:2, 'emergency heat':4]
@Field static Map<String, Map> fanMap = [auto:0, on:1, circulate:2, followSchedule:3] 

metadata {
	definition (name: "Honeywell Thermo Parent", namespace: "csteele", author: "C Steele, Eric Thomas, lg kahn") {
		command "addThermostat"

/* -= Attribute List =-
 	[coolingSetpoint, fanOperatingState, followSchedule, heatingSetpoint, humidifierLowerLimit, humidifierSetPoint, humidifierStatus, 
 	humidifierUpperLimit, humidity, lastUpdate, outdoorHumidity, outdoorTemperature, supportedThermostatFanModes, supportedThermostatModes, 
 	temperature, temperature, thermostatFanMode, thermostatMode, thermostatOperatingState, thermostatSetpoint]

   -= Command List =-
 	[auto, cool, coolLevelDown, coolLevelUp, emergencyHeat, fanAuto, fanCirculate, fanOn, heat, heatLevelDown, heatLevelUp, off, 
 	poll, refresh, setCoolingSetpoint, setFollowSchedule, setHeatingSetpoint, setThermostatFanMode, setThermostatMode]

*/	
	}

	preferences {
	   input name: "username", type: "text", title: "Username", description: "Your Total Comfort User Name", required: true
	   input name: "password", type: "password", title: "Password", description: "Your Total Comfort password",required: true
	   input name: "debugOutput", type: "bool", title: "Enable debug logging?", defaultValue: true
	   input name: "descTextEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
	}
}



void updated(){
	log.info "updated..."
	log.warn "debug logging is: ${debugOutput == true}"
	log.warn "description logging is: ${descTextEnable == true}"
	if (debugOutput) runIn(1800,logsOff)
}

// parse events into attributes
void parse(String description) {
	//parse nothing, ever, probably...
}


void installed() { initialize() }
void initialize(){
	def cd = getChildDevices()?.findAll { it.deviceNetworkId > "${device.id}-${type()}"}
	cd = getChildDevice("${device.id}-${type}_0") // gets list of children
	if (!cd) {
		state.childParamMap = [:]
		state.deviceSetting = [:]
		cd = createChild("0")
		state.nextChild = "1"
	}
}

void addThermostat() {
	createChild(state.nextChild)
	state.nextChild++
	//log.debug "addThermostat: $state.nextChild"
}

def createChild(String numChild) {
	//log.debug "createChild: ${device.id}-${type()}_$numChild, $cd"
	state.childParamMap << [ "$numChild": [childDNI: null, honeywelldevice: null, haveHumidifier: null, enableOutdoorTemps: null, enableHumidity: null, setPermHold: null, pollIntervals: null]]
	def cd = addChildDevice("csteele", "Honeywell WiFi ${type()} Component", "${device.id}-${type()}_$numChild", [name: "${device.displayName} ${type()}", isComponent: true])
	state.deviceSetting << [ "$numChild": [SystemSwitch: null, StatusHeat: null, StatusCool: null, HeatSetpoint: null, CoolSetpoint: null, HeatNextPeriod: null, CoolNextPeriod: null, FanMode: null, TemporaryHoldUntilTime: null, VacationHold: null]]
	return cd 
}

def createOutdoorChild(cd, oType) {
	cd = addChildDevice("hubitat", "Generic Component $oType Sensor", "${cd.id}-$oType", [name: "Outdoor $oType", isComponent: true])
	//log.debug "createOutdoorChild: $cd.deviceNetworkId, $cd.displayName"
	return cd
}


void logsOff(){
	log.warn "debug logging disabled..."
	device.updateSetting("debugOutput",[value:"false",type:"bool"])
}


//
// child (component) device methods
//
void componentDoRefresh(cd, Boolean fromUnauth = false) {
	if (debugOutput) log.debug "received Refresh request from ${cd.displayName} to Honeywell TCC 'refresh', units: = °${location.temperatureScale}, fromUnauth = $fromUnauth"
	login(fromUnauth)
	getHumidifierStatus(cd, fromUnauth)
	getStatus(cd)
}

// a version of refresh for those Outdoor sensors to use.
void componentRefresh(cd) { 
	log.info "Component Refresh button pushed."
}

// Thermostat mode section
void componentOff(cd) {
	if (debugOutput) log.info "received off request from ${cd.displayName}"
	setThermostatMode(cd, 'off')
}

void componentAuto(cd) {
	if (debugOutput) log.info "received Auto request from ${cd.displayName}"
	setThermostatMode(cd, 'auto')
}

void componentCool(cd) {
	if (debugOutput) log.info "received Cool request from ${cd.displayName}"
	setThermostatMode(cd, 'cool')
}

void componentHeat(cd) {
	if (debugOutput) log.info "received Heat request from ${cd.displayName}"
	setThermostatMode(cd, 'heat')
}

void componentEmergencyHeat(cd) {
	if (debugOutput) log.info "received Emergency Heat request from ${cd.displayName}"
	if (isEmergencyHeatAllowed) {
		if (debugOutput) log.debug "Set Emergency/Auxiliary Heat On"
		setThermostatMode(cd, 'emergency heat')
	}
}

void setThermostatMode(cd, mode) {
	if (debugOutput) log.debug "setThermostatMode: $mode"

	String[] dniParts = cd.deviceNetworkId.split("_")
	state.deviceSetting."${dniParts[1]}".SystemSwitch = modeMap.find{ mode == it.key }?.value
	setStatus(cd)

	if(device.data.SetStatus==1)
	{
		getChildDevice(cd.deviceNetworkId).parse([[name:"thermostatMode", value:mode, descriptionText:"${cd.displayName} Thermostat was Set to $mode"]])
	    	cd.setLastRunningMode(mode) 
	}
}

void componentSetThermostatMode(cd, mode) {
	if (debugOutput) log.info "received Thermostat Mode request from ${cd.displayName}"
	setThermostatMode(cd, mode)
}
// end of section


// Heat/Cool Set/Up/Down section
void componentSetCoolingSetpoint(cd, float val) {
	if (debugOutput) log.info "received Cooling Setpoint request from ${cd.displayName}: $val"
	float valIn = val // for limits check
	val = ensureRange( val.toFloat(), state.coolLowerSetptLimit.toFloat(), state.coolUpperSetptLimit.toFloat() )
	if (valIn != val) log.warn "SetPoint limited due to: out of range" 
	String[] dniParts = cd.deviceNetworkId.split("_")
	deviceSettingInitDB(cd, state.childParamMap."${dniParts[1]}".setPermHold) 	 // reset all params, then set individually
	state.deviceSetting."${dniParts[1]}".CoolSetpoint = val
	setStatus(cd)
	getChildDevice(cd.deviceNetworkId).parse([[name:"coolingSetpoint", value:val, descriptionText:"${cd.displayName} Cooling Setpoint is ${val}", unit:"°"]])
}

void componentSetHeatingSetpoint(cd, float val) {
	if (debugOutput) log.info "received Heating Setpoint request from ${cd.displayName}: $val"
	float valIn = val // for limits check
	val = ensureRange( val.toFloat(), state.heatLowerSetptLimit.toFloat(), state.heatUpperSetptLimit.toFloat() )
	if (valIn != val) log.warn "SetPoint limited due to: out of range" 
	String[] dniParts = cd.deviceNetworkId.split("_")
	deviceSettingInitDB(cd, state.childParamMap."${dniParts[1]}".setPermHold) 	 // reset all params, then set individually
	state.deviceSetting."${dniParts[1]}".HeatSetpoint = val
	setStatus(cd)
	getChildDevice(cd.deviceNetworkId).parse([[name:"heatingSetpoint", value:val, descriptionText:"${cd.displayName} Heating Setpoint is ${val}", unit:"°"]])
}

void componentHeatLevelDown(cd) {
	float val = (location.temperatureScale == "F") ? cd.currentValue("heatingSetpoint") - 1 : cd.currentValue("heatingSetpoint") - 0.5
	componentSetHeatingSetpoint(cd, val)
}

void componentHeatLevelUp(cd) {
	float val = (location.temperatureScale == "F") ? cd.currentValue("heatingSetpoint") + 1 : cd.currentValue("heatingSetpoint") + 0.5
	componentSetHeatingSetpoint(cd, val)
}

void componentCoolLevelDown(cd) {
	float val = (location.temperatureScale == "F") ? cd.currentValue("coolingSetpoint") - 1 : cd.currentValue("coolingSetpoint") - 0.5
	componentSetCoolingSetpoint(cd, val)
}

void componentCoolLevelUp(cd) {
	float val = (location.temperatureScale == "F") ? cd.currentValue("coolingSetpoint") + 1 : cd.currentValue("coolingSetpoint") + 0.5
	componentSetCoolingSetpoint(cd, val)
}
// end of section


// Fan mode section
void componentSetThermostatFanMode(cd, mode) {
	if (debugOutput) log.info "received Fan Mode request from ${cd.displayName}"
	setThermostatFanMode(cd, mode)
}

void componentFanAuto(cd) {
	if (debugOutput) log.info "received Fan Auto request from ${cd.displayName}"
	setThermostatFanMode(cd, 'auto')
}

void componentFanCirculate(cd) {
	if (debugOutput) log.info "received Fan Circulate request from ${cd.displayName}"
	setThermostatFanMode(cd, 'circulate')
}

void componentFanOn(cd) {
	if (debugOutput) log.info "received Fan On request from ${cd.displayName}"
	setThermostatFanMode(cd, 'on')
}

def setThermostatFanMode(cd, mode) { 
	if (debugOutput) log.debug "setThermostatFanMode: $mode"
	def fanMode = null

	state.deviceSetting."${dniParts[1]}".FanMode = fanMap.find{ mode == it.key }?.value
	setStatus(cd)

	if(device.data.SetStatus==1)
	{
		getChildDevice(cd.deviceNetworkId).parse([[name:"thermostatFanMode", value:mode, descriptionText:"${cd.displayName} Fan was Set to $mode"]])
	}
}
// end of section


void componentSetFollowSchedule(cd) {
	if (debugOutput) log.info "received Set Follow Schedule request from ${cd.displayName}"
	deviceSettingInitDB(cd, 0) 	 // reset all params, then set individually
	setStatus(cd)
	
	if(device.data.SetStatus==1)
	{
		getChildDevice(cd.deviceNetworkId).parse([[name:"mode", value:"off", descriptionText:"${cd.displayName} was Set to Follow Schedule"]])
	}
}


void setOutdoorTemperature(cd, value){
	def cdd = getChildDevices()?.findAll { it.deviceNetworkId == "${cd.id}-Temperature"}
	if (!cdd) { createOutdoorChild(cd, "Temperature") }
	String unit = "°${location.temperatureScale}"
	def cdx = getChildDevice("${cd.id}-Temperature")
	cdx.parse([[name:"temperature", value:value, descriptionText:"${cdx.displayName} is ${value}${unit}.", unit: unit]])
}

void setOutdoorHumidity(cd, value){
	def cdd = getChildDevices()?.findAll { it.deviceNetworkId == "${cd.id}-Humidity"}
	if (!cdd) { createOutdoorChild(cd, "Humidity") } 
	def cdx = getChildDevice("${cd.id}-Humidity")  
	cdx.parse([[name:"humidity", value:value, descriptionText:"${cdx.displayName} is ${value}%.", unit:"%"]])
}


//
// Thermostat Communication methods
//

/* ------------------------------------------------------------------

	getStatus(cd)

	Purpose: Acquire settings from the Thermostat

	Notes: JSON is returned and then "sendEvents" (via Child's parse) puts the data into the UI. 
	       
   ------------------------------------------------------------------ */

def getStatus(cd) {
	String[] dniParts = cd.deviceNetworkId.split("_")
	if (debugOutput) log.debug "enable outside temps = ${state.childParamMap."${dniParts[1]}".enableOutdoorTemps}"
	def today = new Date()
	getChildDevice(cd.deviceNetworkId).parse([[name:"TCCstatus", value:"begin", descriptionText:"${cd.displayName} TCC transaction: begin"]])
	//if (debugOutput) log.debug "https://${tccSite()}/portal/Device/CheckDataSession/${settings.honeywelldevice}?_=$today.time"

	def params = [
	    uri: "https://${tccSite()}/portal/Device/CheckDataSession/${state.childParamMap."${dniParts[1]}".honeywelldevice}",
	    headers: [
	        'Accept': '*/*', // */ comment
	        'DNT': '1',
	        'Cache': 'false',
	        'dataType': 'json',
	        'Accept-Encoding': 'plain',
	        'Cache-Control': 'max-age=0',
	        'Accept-Language': 'en-US,en,q=0.8',
	        'Connection': 'keep-alive',
	        'Referer': "https://${tccSite()}/portal",
	        'X-Requested-With': 'XMLHttpRequest',
	        'User-Agent': 'Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/28.0.1500.95 Safari/537.36',
	        'Cookie': device.data.cookiess
	    ],
	  timeout: 10
	]

	def pData = [cd:[cd]]
	if (debugOutput) log.debug "sending getStatus request $params"
	asynchttpGet("getStatusHandler", params, pData)
}

def getStatusHandler (resp, data) {
	try {
		def cdd = data["cd"]
		def cd = getChildDevice(cdd.deviceNetworkId)
		String[] dniParts = cd.deviceNetworkId.split("_")
		if (resp.getStatus() == 200 || resp.getStatus() == 207) {
			Map setStatusResult = parseJson(resp.data)
			getStatusDistrib(cd, setStatusResult)
		}
		else {
			if (descTextEnable) log.info "TCC getStatus failed for ${cd.displayName}"
			getChildDevice(cd.deviceNetworkId).parse([[name:"TCCstatus", value:"failed", descriptionText:"${cd.displayName} TCC transaction: failed"]])
		}
    }
	catch (e) {
		log.error "getStatus response invalid: $e"
		if (descTextEnable) log.info "TCC getStatus failed for ${cd.displayName}"
		getChildDevice(cd.deviceNetworkId).parse([[name:"TCCstatus", value:"failed", descriptionText:"${cd.displayName} TCC transaction: failed"]])
    }	
}


def getStatusDistrib(cd, Map decodedResult) {

	def curTemp = decodedResult.latestData.uiData.DispTemperature
	def switchPos = decodedResult.latestData.uiData.SystemSwitchPosition
	def coolSetPoint = decodedResult.latestData.uiData.CoolSetpoint
	def heatSetPoint = decodedResult.latestData.uiData.HeatSetpoint
	def statusCool = decodedResult.latestData.uiData.StatusCool
	def statusHeat = decodedResult.latestData.uiData.StatusHeat
	Boolean hasIndoorHumid = decodedResult.latestData.uiData.IndoorHumiditySensorAvailable
	def curHumidity = decodedResult.latestData.uiData.IndoorHumidity
	Boolean hasOutdoorHumid = decodedResult.latestData.uiData.OutdoorHumidityAvailable
	Boolean hasOutdoorTemp = decodedResult.latestData.uiData.OutdoorTemperatureAvailable
	Boolean isScheduleCapable = decodedResult.latestData.uiData.ScheduleCapable
	def curOutdoorHumidity = decodedResult.latestData.uiData.OutdoorHumidity
	def curOutdoorTemp = decodedResult.latestData.uiData.OutdoorTemperature
	// EquipmentOutputStatus = 0 off 1 heating 2 cooling
	def equipmentStatus = decodedResult.latestData.uiData.EquipmentOutputStatus	
	def holdTime = decodedResult.latestData.uiData.TemporaryHoldUntilTime
	def vacationHoldMode = decodedResult.latestData.uiData.IsInVacationHoldMode
	def vacationHold = decodedResult.latestData.uiData.VacationHold
	Boolean isEmergencyHeatAllowed = decodedResult.latestData.uiData.SwitchEmergencyHeatAllowed

	String[] dniParts = cd.deviceNetworkId.split("_") // which child 'owns this'?
	state.heatLowerSetptLimit = decodedResult.latestData.uiData.HeatLowerSetptLimit 
	state.heatUpperSetptLimit = decodedResult.latestData.uiData.HeatUpperSetptLimit 
	state.coolLowerSetptLimit = decodedResult.latestData.uiData.CoolLowerSetptLimit 
	state.coolUpperSetptLimit = decodedResult.latestData.uiData.CoolUpperSetptLimit 
	
	if (holdTime != 0) { getChildDevice(cd.deviceNetworkId).parse([[name:"followSchedule", value:"TemporaryHold", descriptionText:"${cd.displayName} was Set to Temporary Hold"]]) }
	if (vacationHoldMode == true) { getChildDevice(cd.deviceNetworkId).parse([[name:"followSchedule", value:"VacationHold", descriptionText:"${cd.displayName} was Set to Vacation Hold"]]) }
	if (vacationHoldMode == false && holdTime == 0 && isScheduleCapable == true ) { getChildDevice(cd.deviceNetworkId).parse([[name:"followSchedule", value:"FollowingSchedule", descriptionText:"${cd.displayName} was Set to Following Schedule"]]) }


	Integer fanMode = decodedResult.latestData.fanData.fanMode
	Boolean fanIsRunning = decodedResult.latestData.fanData.fanIsRunning

	// set fan and operating state
	String fanState = "idle"
	if (fanIsRunning) { fanState = "on" } 

	String operatingState = [ 0: 'idle', 1: 'heating', 2: 'cooling' ][equipmentStatus] ?: 'idle'

	if ((state.childParamMap."${dniParts[1]}".haveHumidifier != 'Yes') && (fanIsRunning == true) && (equipmentStatus == 0)) { 
	    operatingState = "fan only"
	} 
	else if ((state.childParamMap."${dniParts[1]}".haveHumidifier == 'Yes')  && (fanIsRunning == true) && (equipmentStatus == 0) && (fanMode == 0)) {
	    operatingState = "Humidifying"
	}

	logInfo("Get Operating State: $operatingState - Fan to $fanState")

	n = [ 0: 'auto', 2: 'circulate', 1: 'on', 3: 'followSchedule' ][fanMode]
	getChildDevice(cd.deviceNetworkId).parse([[name:"thermostatFanMode", value:n, descriptionText:"${cd.displayName} Fan was Set to $n"]])

	n = [ 1: 'heat', 2: 'off', 3: 'cool', 5: 'auto', 4: 'emergency heat' ][switchPos] ?: 'auto'
	getChildDevice(cd.deviceNetworkId).parse([[name:"temperature", value:curTemp, descriptionText:"${cd.displayName} Temperature was Set to $curTemp", unit: "°${location.temperatureScale}"]])
	getChildDevice(cd.deviceNetworkId).parse([[name:"thermostatMode", value:n, descriptionText:"${cd.displayName} Mode was Set to $n"]])
	cd.setLastRunningMode(n) // lastRunningMode in the Child

	//Send events 
	if (hasIndoorHumid == false) { curHumidity = 0 }
	getChildDevice(cd.deviceNetworkId).parse([[name:"thermostatOperatingState", value:operatingState, descriptionText:"${cd.displayName} Op State was Set to $operatingState"]])
	getChildDevice(cd.deviceNetworkId).parse([[name:"fanOperatingState", value:fanState, descriptionText:"${cd.displayName} Fan was Set to $fanState"]])
	getChildDevice(cd.deviceNetworkId).parse([[name:"coolingSetpoint", value:coolSetPoint, descriptionText:"${cd.displayName} Cooling was Set to $coolSetPoint", unit:"°${location.temperatureScale}"]])
	getChildDevice(cd.deviceNetworkId).parse([[name:"heatingSetpoint", value:heatSetPoint, descriptionText:"${cd.displayName} Heating was Set to $heatSetPoint", unit:"°${location.temperatureScale}"]])
	getChildDevice(cd.deviceNetworkId).parse([[name:"humidity", value:curHumidity as Integer, descriptionText:"${cd.displayName} Humidity was Set to $curHumidity", unit:"%"]])

	if (state.childParamMap."${dniParts[1]}".haveHumidifier == 'Yes') {
		// kludge to figure out if humidifier is on, fan has to be auto, and if fan is on but not heat/cool and we have enabled the humidifyer it should be humidifying"
		//if (debugOutput) log.debug "fanIsRunning = $fanIsRunning, equip status = $equipmentStatus, fanMode = $fanMode, temp = $curTemp, humidity = $curHumidity"
	     
	 	if ((fanIsRunning == true) && (equipmentStatus == 0) && (fanMode == 0)) {
			getChildDevice(cd.deviceNetworkId).parse([[name:"humidifierStatus", value:"Humidifying", descriptionText:"${cd.displayName} Humidifier was Set to Humidifying"]])
		} 
		else {
			getChildDevice(cd.deviceNetworkId).parse([[name:"humidifierStatus", value:"Idle", descriptionText:"${cd.displayName} Humidifier was Set to Idle"]])
		}
	}

	def now = new Date().format('MM/dd/yyyy h:mm a', location.timeZone)
	getChildDevice(cd.deviceNetworkId).parse([[name:"lastUpdate", value:now, descriptionText:"${cd.displayName} Last Update was Set to $now"]])
	
	if (state.childParamMap."${dniParts[1]}".enableOutdoorTemps == "Yes") {
		if (hasOutdoorHumid) {
			setOutdoorHumidity(cd, curOutdoorHumidity)
			def cdx = getChildDevice("${cd.id}-Humidity")
			getChildDevice(cdx.deviceNetworkId).parse([[name:"outdoorHumidity", value:curOutdoorHumidity as Integer, descriptionText:"${cdx.displayName} Outdoor Humidity was Set to $curOutdoorHumidity", unit:"%"]])
		}
	
		if (hasOutdoorTemp) {
			setOutdoorTemperature(cd, curOutdoorTemp)
			def cdx = getChildDevice("${cd.id}-Temperature")
			getChildDevice(cdx.deviceNetworkId).parse([[name:"outdoorTemperature", value:curOutdoorTemp as Integer, descriptionText:"${cdx.displayName} Outdoor Temperature was Set to $curOutdoorTemp", unit:"°${location.temperatureScale}"]])
		}
	}
	getChildDevice(cd.deviceNetworkId).parse([[name:"TCCstatus", value:"success", descriptionText:"${cd.displayName} TCC transaction: success"]])		
}


def getHumidifierStatus(cd, Boolean fromUnauth = false) {
	String[] dniParts = cd.deviceNetworkId.split("_")
	if (debugOutput)  log.debug "in get humid status enable humidity = ${state.childParamMap."${dniParts[1]}".enableHumidity}"
	if (state.childParamMap."${dniParts[1]}".haveHumidifier == 'No') return
	
	def params = [
	  uri: "https://${tccSite()}/portal/Device/CheckDataSession/${state.childParamMap."${dniParts[1]}".honeywelldevice}",
        headers: [
            'Accept': '*/*', // */ comment
            'DNT': '1',
            'dataType': 'json',
            'cache': 'false',
            'Accept-Encoding': 'plain',
            'Cache-Control': 'max-age=0',
            'Accept-Language': 'en-US,en,q=0.8',
            'Connection': 'keep-alive',
            'Host': 'rs.alarmnet.com',
            'Referer': "https://${tccSite()}/portal/",
            'X-Requested-With': 'XMLHttpRequest',
            'User-Agent': 'Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/28.0.1500.95 Safari/537.36',
            'Cookie': device.data.cookiess
        ],
	  timeout: 10
    ]

    if (debugOutput) log.debug "sending gethumidStatus request: $params"
	def pData = [cd:[cd]]
	asynchttpGet("getHumidStatusHandler", params, pData)
}


def getHumidStatusHandler(resp, data) {
	if(resp.getStatus() == 200 || resp.getStatus() == 207) {
		if (debugOutput) log.debug "GetHumidity Request was successful, $resp.status"
		def cdd = data["cd"]
		def response = resp.getData().toString()
		getHumidifierDistrib (cdd, response)
	}
}

def getHumidifierDistrib (cd, resp) {
	Map CancelLine = [:]
	Integer HumLevel = 0
	Integer HumMin   = 1
	Integer HumMax   =99
	resp.split("\n").each {
		if (it.contains("CancelMin")) {
	    		CancelLine = it.trim()
	    		def pair = CancelLine.split(" ");
	    		if (debugOutput)   log.debug "got cancel min line: $CancelLine"
		
	    		def p0 = pair[0]
	    		def p1 = pair[1]
	    		def p2 = pair[2]
		
	    		def pair2 = p1.split("%")
	    		def p20 = pair2[0]
	    		def p21 = pair2[1]
	    		def p22 = pair2[2]
				
	    		HumLevel = p21.toInteger()
	    		HumMin = p20.toInteger()
		
	    		def pair3 = p2.split("%")
	    		//log.debug "pair3 = $pair3"
	    		def p30 = pair3[0]
	    		// log.debug "p30 = $p30"
		
	    		HumMax = p30.toInteger() 
		
		}
	}
        
     	//Send events 
	getChildDevice(cd.deviceNetworkId).parse([[name:"humidifierSetPoint", value:HumLevel as Integer, descriptionText:"${cd.displayName} Humidifier was Set to $HumLevel", unit:"%"]])
	getChildDevice(cd.deviceNetworkId).parse([[name:"humidifierUpperLimit", value:HumMax as Integer, descriptionText:"${cd.displayName} Humidifier was Set to $HumMin", unit:"%"]])
	getChildDevice(cd.deviceNetworkId).parse([[name:"humidifierLowerLimit", value:HumMin as Integer, descriptionText:"${cd.displayName} Humidifier was Set to $HumMin", unit:"%"]])
	
	getChildDevice(cd.deviceNetworkId).parse([[name:"TCCstatus", value:"success", descriptionText:"${cd.displayName} TCC transaction: success"]])
}


/* ------------------------------------------------------------------

	setStatus(cd)

	Purpose: Send accumulated settings to the Thermostat

	Notes: To accumulate, each new UI button push restarts an accumulation timer (runInMillis) 
	       waiting 1.6 seconds after each click. 
	       
	       login does not use the Honeywell Device ID

   ------------------------------------------------------------------ */

void setStatus(cd) {
	def pData = [data:['cd':[cd.deviceNetworkId]]]
	runInMillis( 1600, settingsAccumWait, pData )
}

void settingsAccumWait(data) {
	def cdd = data["cd"]
	def cd = getChildDevice(cdd)
	device.data.SetStatus = 0
	getChildDevice(cd.deviceNetworkId).parse([[name:"TCCstatus", value:"begin", descriptionText:"${cd.displayName} TCC transaction: begin"]])

	login()
	if (debugOutput) log.debug "Honeywell TCC 'setStatus'"
	def today = new Date()
	String[] dniParts = cd.deviceNetworkId.split("_")
	
	def params = [
	    uri: "https://${tccSite()}/portal/Device/SubmitControlScreenChanges",
	    headers: [
	        'Accept': 'application/json, text/javascript, */*; q=0.01', // */ comment
	        'DNT': '1',
	        'Accept-Encoding': 'gzip,deflate,sdch',
	        'Cache-Control': 'max-age=0',
	        'Accept-Language': 'en-US,en,q=0.8',
	        'Connection': 'keep-alive',
	        'Host': "${tccSite()}",
	        'Referer': "https://${tccSite()}/portal/Device/Control/${state.childParamMap."${dniParts[1]}".honeywelldevice}",
	        'X-Requested-With': 'XMLHttpRequest',
	        'User-Agent': 'Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/28.0.1500.95 Safari/537.36',
	        'Cookie': device.data.cookiess
	    ],
	    body: [
	        DeviceID: "${state.childParamMap."${dniParts[1]}".honeywelldevice}",
	        DisplayUnits: location.temperatureScale,
	        SystemSwitch: state.deviceSetting."${dniParts[1]}".SystemSwitch,
	        HeatSetpoint: state.deviceSetting."${dniParts[1]}".HeatSetpoint,
	        CoolSetpoint: state.deviceSetting."${dniParts[1]}".CoolSetpoint,
	        HeatNextPeriod: state.deviceSetting."${dniParts[1]}".HeatNextPeriod,
	        CoolNextPeriod: state.deviceSetting."${dniParts[1]}".CoolNextPeriod,
	        StatusHeat: state.deviceSetting."${dniParts[1]}".StatusHeat,
	        StatusCool: state.deviceSetting."${dniParts[1]}".StatusCool,
	        fanMode: state.deviceSetting."${dniParts[1]}".FanMode,
	        TemporaryHoldUntilTime: state.deviceSetting."${dniParts[1]}".TemporaryHoldUntilTime,
	        VacationHold: state.deviceSetting."${dniParts[1]}".VacationHold
	    ],
	  timeout: 10
	]

// beginning httpPost secton 
	if (debugOutput) log.debug "params = $params"
	try {
		httpPost(params) {
		    resp ->
			def setStatusResult = resp.data
			if (debugOutput) log.debug "Request was successful, $resp.status"
			device.data.SetStatus = 1
			getChildDevice(cd.deviceNetworkId).parse([[name:"TCCstatus", value:"success", descriptionText:"${cd.displayName} TCC transaction: success"]])
		}
	} 
	catch (e) {
		log.error "Something went wrong: $e"
		getChildDevice(cd.deviceNetworkId).parse([[name:"TCCstatus", value:"failed", descriptionText:"${cd.displayName} TCC transaction: failed"]])
	}

	// prepare for the next cycle by clearing all the values just sent.
	deviceSettingInitDB(cd, null)
}


Boolean refractory
void loginRefractory() { refractory = false }

/* ------------------------------------------------------------------

	login

	Purpose: Login to Honeywell's Total Connect Comfort API

	Notes: Collects the cookies the site sends for subsequent interactions:
	       getHumidifierStatus() getStatus() each rely on login and the cookies
	       componentDoRefresh() setStatus() call login themselves
	       
	       login does not use the Honeywell Device ID

   ------------------------------------------------------------------ */

def login(Boolean fromUnauth = false) {
	if (refractory) return 	// we've done a login in the past 600ms
	refractory = true
	runInMillis( 600, loginRefractory)
	if (debugOutput) log.debug "Honeywell TCC 'login'"
	
	Map params = [
		uri: "https://${tccSite()}/portal/",
		headers: [
			'Content-Type': 'application/x-www-form-urlencoded',
			'Accept': 'application/json, text/javascript, */*; q=0.01', // */
			'Accept-Encoding': 'sdch',
			'Host': "${tccSite()}",
			'DNT': '1',
			'Origin': "https://${tccSite()}/portal/",
			'User-Agent': 'Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/28.0.1500.95 Safari/537.36'
		],
		body: [timeOffset: '240', UserName: "${settings.username}", Password: "${settings.password}", RememberMe: 'false']
	]
	
	// log.debug "Params: $params.headers $params.body"
	device.data.cookiess = ''
	
	try {
	    httpPost(params) { 
	        response ->
	        if (debugOutput) log.debug "Request was successful, $response.status" // ${response.getHeaders()}"
	        String allCookies = ""
	
	        response.getHeaders('Set-Cookie').each {
	            String cookie = it.value.split(';|,')[0]
	            Boolean skipCookie = false
	            def expireParts = it.value.split('expires=')
	
	            try {
	            	def cookieSegments = it.value.split(';')
	            	for (int i = 0; i < cookieSegments.length; i++) {
	            		def cookieSegment = cookieSegments[i]
	            		String cookieSegmentName = cookieSegment.split('=')[0]
			
	            		if (cookieSegmentName.trim() == "expires") {
	            			String expiration = cookieSegment.split('=')[1]
				
	            			Date expires = new Date(expiration)
	            			Date newDate = new Date() // right now
				
	            			if (expires < newDate) {
	            			    skipCookie = true
	            			}
				
	            		}
	            	}
	            } catch (e) {
	                if (debugOutput) log.debug "!error when checking expiration date: $e ($expiration) [$expireParts.length] {$it.value}"
	            }
	
	            allCookies += it.value + ';'
	
	            if (cookie != ".ASPXAUTH_TH_A=") {
	                if (it.value.split('=')[1].trim() != "") {
	                    if (!skipCookie) {
	                        if (debugOutput) log.debug "Adding cookie to collection: $cookie"
	                        device.data.cookiess = device.data.cookiess + cookie + ';'
	                    }
	                }
	            }
	        }
	        //log.debug "cookies: $device.data.cookiess"
	    }
	} catch (e) {
		log.warn "Something went wrong during login: $e"
		def String eStr = e.toString()
		def pair = eStr.split(" ")
		def p1 = pair[0]
		def p2 = pair[1]

		if ((p2 == "Unauthorized") || (p2 == "Read"))
		{
			if (fromUnauth)
			{
				if (debugOutput) log.debug "2nd Unauthorized failure ... giving up!"
				getChildDevice(cd.deviceNetworkId).parse([[name:"TCCstatus", value:"failed", descriptionText:"${cd.displayName} TCC transaction: failed"]])
			}
			else
			{
				if (debugOutput) log.debug "Scheduling a retry in 5 minutes due to Unauthorized!"
				def pData = [data:['cd':[cd.deviceNetworkId]]]
				runIn(300, refreshFromRunin, pData)
			}
		}
	}
}	

// Value setting Section
//
// params passed in from each Child: DNI, honeywelldevice, haveHumidifier, enableOutdoorTemps, enableHumidity, setPermHold, pollIntervals
void setParams(cDNI, dev, hH, eOT, eH, sPH, pI) {
	String[] dniParts = cDNI.split("_")
	state.childParamMap."${dniParts[1]}".childDNI 			= cDNI
	state.childParamMap."${dniParts[1]}".honeywelldevice 		= dev
	state.childParamMap."${dniParts[1]}".haveHumidifier 		= hH
	state.childParamMap."${dniParts[1]}".enableOutdoorTemps 	= eOT
	state.childParamMap."${dniParts[1]}".enableHumidity		= eH
	state.childParamMap."${dniParts[1]}".setPermHold 		= sPH
	state.childParamMap."${dniParts[1]}".pollIntervals 		= pI

	if (debugOutput) log.debug "ChildParams: $cDNI, $dev, $hH, $eOT, $eH, $sPH, $pI, -${state.childParamMap."${dniParts[1]}".honeywelldevice}-"
}

// initialize the device values. Each method overwrites it's specific value
def deviceSettingInitDB(cd, val) { 	 // reset all params, then set individually
	String[] dniParts = cd.deviceNetworkId.split("_")

	state.deviceSetting."${dniParts[1]}".StatusHeat = val
	state.deviceSetting."${dniParts[1]}".StatusCool = val

	// don't clear multiple times
	if ( val == null ) {
		state.deviceSetting."${dniParts[1]}".SystemSwitch = null 
		state.deviceSetting."${dniParts[1]}".HeatSetpoint = null
		state.deviceSetting."${dniParts[1]}".CoolSetpoint = null
		state.deviceSetting."${dniParts[1]}".HeatNextPeriod = null
		state.deviceSetting."${dniParts[1]}".CoolNextPeriod = null
		state.deviceSetting."${dniParts[1]}".FanMode = null
		state.deviceSetting."${dniParts[1]}".TemporaryHoldUntilTime=null
		state.deviceSetting."${dniParts[1]}".VacationHold=null
	}
}
// end of section


float ensureRange(float value, float min, float max) {
   return Math.min(Math.max(value, min), max);
}

def refreshFromRunin(data)
{ 
	def cdd = data["cd"]
	def cd = getChildDevice(cdd)
	if (debugOutput) log.debug "Calling refresh after Unauthorize failure!"
	refresh(cd, true)
}


private logInfo (msg) {
	if (settings?.descTextEnable || settings?.descTextEnable == null) log.info "$msg"
}


def getThisCopyright(){"&copy; 2022 C Steele "}
