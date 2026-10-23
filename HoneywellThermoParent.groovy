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
 *
 * csteele: v2.0.21  Discover and initialize thermostat children without clearing existing state.
 *                    Derive collision-free child numbers and delete only the selected thermostat,
 *                    its exact outdoor children, and its own Parent state entries. Give newly
 *                    created component children unambiguous numbered names without duplicating
 *                    "Thermostat" when it is already present in the Parent display name.
 * csteele: v2.0.20  Store thermostat setpoint limits and Emergency Heat capability per child so
 *                    one thermostat's status cannot affect commands sent to another thermostat.
 *                    Commands tolerate capabilities being unknown until that child is refreshed.
 * csteele: v2.0.19  Retain one Parent-owned TCC session across child refreshes and commands to reduce
 *                    unnecessary logins and Honeywell rate-limit pressure. Cookie count remains an
 *                    empirical confidence check; valid thermostat data confirms the session. Login
 *                    contention defers instead of blocking or starting an unlocked second login.
 *                    Session rejection is generation-aware, and failed POSTs retain unsent settings.
 *                    Async status errors no longer read missing response data or discard a session
 *                    merely because a request timed out or Honeywell returned a human-readable page.
 *                    Status GETs close their HTTP connection so a poll after a long idle interval
 *                    does not attempt to reuse a stale keep-alive connection.
 *                    Transient status failures wait for the normal poll instead of adding another
 *                    request; only explicit authentication rejection gets one 60-second retry.
 * csteele: v2.0.18  Added preference for EmergencyHeat to use either 0 or 4 to support more thermostat models
 *                    isEmergencyHeatAllowed added to state. per bdunbar analysis
 * csteele: v2.0.17  componentInitialize in C or F
 * csteele: v2.0.16  Added Quick Reference Link
 *                    Merged lgk retry on status error logic
 * csteele: v2.0.15  Added a style to Username/pw parameter
 * csteele: v2.0.14  refactored setStatus to only send non-null values, eg. only changed values.
 * csteele: v2.0.13  Updated minimum cookie count to be a variable: minCookieCount.
 * csteele: v2.0.12  Updated supportedThermostatModes and supportedThermostatFanModes to add double quotes to support HE platform version 2.3.3.x
 * csteele: v2.0.11  populate thermostatSetoint attribute with most recent heat or cool setpoint
 *                    added componentInitialize
 * csteele: v2.0.10  removed number typing on setCoolingSetpoint and setHeatingSetpoint used by Thermostat Controller app
 * csteele: v2.0.9   corrected log.info lines to be qualified by descTextEnable vs debugOutput.
 *                    round curTemp to 2 places.
 *                    preset supportedThermostatFanModes & supportedThermostatModes used by Thermostat Controller app.
 * csteele: v2.0.8   corrected "refresh()" in "refreshFromRunin" to be "componentDoRefresh()"
 * csteele: v2.0.7   corrected "nextChild" to correctly increment as a number, not asci increment
 *                    used the same split("[-_]") string to prevent future typos
 * csteele: v2.0.6   added Delete Outdoor Child and Delete Thermostat
 *                    fixed cron string to run the whole hour (x mod y / y)
 * csteele: v2.0.5   login returns true/false allowing refresh to retry login
 * csteele: v2.0.4   refactored "getStatus" and "getHumidityStatus" to minimize try/catch scope
 *                    reorganized methods to clump Fan methods together, to clump Mode methods together, etc.
 *                    "getHumidityStatus" using asynchttpGet
 *                    refactored all the set heat/cool/up/down
 * csteele: v2.0.3   refactored "setStatus" to accumulate UI button clicks
 *                    clarified some status/log messages
 *                    added both Outdoor Child devices
 * csteele: v2.0.2   put childParamMap into "state"
 * csteele: v2.0.1   Put setLastRunningMode into Child
 * csteele: v2.0.0   Initial Commit
 *
 * Forked from:
 * csteele: v1.3.20  Added "emergency/auxiliary" heat.
 *			     added fanOperatingState Attribute.
**/

/*
	Driver overview.
		The choice was made to have the child driver do very little. The UI is centered in the child driver, but it dispatches
		everything to the parent. There the UI is processed and an Event is sent back to the child to be displayed on 
		the child's Current States column. Each Child driver has a unique id that it passes to the parent (cd) to keep each 
		child action separate from other child devices. 
		The child device specific data is kept in three Maps: state.deviceSetting, state.childParamMap,
		and state.childStatus.

		This parent driver is organized into the three major categories of actions. 
			UI methods, all beginning with "component" 
			setStatus, which is communications TO the Thermostat.
			getStatus, which is communications FROM the Thermostat. 
		Every UI button sets a value into deviceSetting[], which is cleared when those values are sent via setStatus. 
		The childParameterMap holds the unique preferences of each child thermostat.
	
*/

import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

 public static String version()	{  return "v2.0.21"  }
 public static String tccSite() 	{  return "mytotalconnectcomfort.com"  }
 public static String type() 		{  return "Thermostat"  }

@Field static Map<String, Map> modeMap4 = [auto:5, cool:3, heat:1, off:2, 'emergency heat':4]
@Field static Map<String, Map> modeMap0 = [auto:5, cool:3, heat:1, off:2, 'emergency heat':0]
@Field static Map<String, Map> fanMap = [auto:0, on:1, circulate:2, followSchedule:3] 

// Cookies and session metadata are owned by the Parent so every thermostat child can reuse
// one TCC login. The static semaphore is coordination only; session retention does not depend
// on static fields surviving a driver reload or hub reboot.
@Field static ConcurrentHashMap<String, Semaphore> tccLoginLocks = new ConcurrentHashMap<String, Semaphore>()
@Field static final Integer loginBackoffSeconds = 60
@Field static final Integer loginDeferredSeconds = 2
@Field static final Integer minCookieCount = 8
@Field static final Integer statusRequestTimeoutSeconds = 10

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
	   input name: "username", type: "text", title: "<b>Username</b>", description: "<i>Your Total Comfort User Name</i><p>", required: true
	   input name: "password", type: "password", title: "<b>Password</b>", description: "<i>Your Total Comfort password</i><p>",required: true
	   input name: "quickref", type: "hidden", title:"<a href='https://www.hubitatcommunity.com/QuikRef/honeywellThermoDriverInfo/index.html' target='_blank'>Quick Reference ${version()}</a>"
	   input name: "debugOutput", type: "bool", title: "Enable debug logging?", defaultValue: true
	   input name: "descTextEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
	}
}



void updated(){
	log.info "updated..."
	log.warn "debug logging is: ${debugOutput == true}"
	log.warn "description logging is: ${descTextEnable == true}"
	clearTccSession() // credentials may have changed
}

// parse events into attributes
void parse(String description) {
	//parse nothing, ever, probably...
}


void installed() { initialize() }
void initialize(){
	ensureStateMaps()
	List thermostats = thermostatChildren()
	if (!thermostats) {
		createChild("0")
		thermostats = thermostatChildren()
	}
	thermostats.each { cd -> ensureChildMaps(childKey(cd)) }
	state.nextChild = nextAvailableChildNumber()
	if (debugOutput) log.debug "Initialized thermostat children: ${thermostats*.deviceNetworkId}; next child: ${state.nextChild}"
}

void addThermostat() {
	String childNumber = nextAvailableChildNumber()
	createChild(childNumber)
	state.nextChild = (childNumber.toInteger() + 1).toString()
	if (debugOutput) log.debug "Added thermostat child $childNumber; next child: ${state.nextChild}"
}

def createChild(String numChild) {
	String childNumber = numChild.toString()
	String dni = "${device.id}-${type()}_${childNumber}"
	def existing = getChildDevice(dni)
	if (existing) {
		ensureChildMaps(childNumber)
		log.warn "Thermostat child $dni already exists; returning the existing child."
		return existing
	}
	ensureChildMaps(childNumber)
	String childBaseName = thermostatChildBaseName()
	String childName = "${childBaseName} ${childNumber}"
	return addChildDevice("csteele", "Honeywell WiFi ${type()} Component", dni, [name: childName, label: childName, isComponent: true])
}

def createOutdoorChild(cd, oType) {
	String dni = "${cd.id}-${oType}"
	def existing = getChildDevice(dni)
	if (existing) return existing
	return addChildDevice("hubitat", "Generic Component $oType Sensor", dni, [name: "Outdoor $oType", isComponent: true])
}


void logsOff(){
	log.warn "debug logging disabled..."
	device.updateSetting("debugOutput",[value:"false",type:"bool"])
}


//
// child (component) device methods
//
void componentDoRefresh(cd, Boolean fromUnauth = false) {
	if (debugOutput) log.debug "received Refresh request from ${cd.displayName} to Honeywell TCC 'refresh', unit: = °${location.temperatureScale}, fromUnauth = $fromUnauth"
	Map session = getTccSession(cd)
	if (session.status != "ready") {
		Integer delay = (session.retryAfter ?: loginBackoffSeconds) as Integer
		if (session.status == "deferred") {
			if (debugOutput) log.debug "${cd.displayName}: another TCC login is in progress; retrying refresh in ${delay} seconds"
			scheduleRefreshRetry(cd, delay, fromUnauth)
		} else if (!fromUnauth) {
			if (debugOutput) log.debug "${cd.displayName}: TCC login unavailable; retrying refresh in ${delay} seconds"
			getChildDevice(cd.deviceNetworkId).parse([[name:"TCCstatus", value:"retry login", descriptionText:"${cd.displayName} TCC transaction: retry login"]])
			scheduleRefreshRetry(cd, delay, true)
		} else {
			getChildDevice(cd.deviceNetworkId).parse([[name:"TCCstatus", value:"failed", descriptionText:"${cd.displayName} TCC transaction: failed"]])
		}
		return
	}
	getHumidifierStatus(cd, session.cookies)
	getStatus(cd, fromUnauth, session)
}

void componentDeleteThermostatChild(cd) {
	String childNumber
	String thermostatDni = cd?.deviceNetworkId?.toString()
	try {
		childNumber = childKey(cd)
	} catch (e) {
		log.warn "Refusing to delete a device that is not one of this Parent's thermostat children: ${thermostatDni}"
		return
	}
	componentDeleteOutdoorChild(cd.id)
	deleteChildDevice(thermostatDni)
	state.deviceSetting = (state.deviceSetting ?: [:]).findAll { it.key.toString() != childNumber }
	state.childParamMap = (state.childParamMap ?: [:]).findAll { it.key.toString() != childNumber }
	state.childStatus = (state.childStatus ?: [:]).findAll { it.key.toString() != childNumber }
	state.nextChild = nextAvailableChildNumber()
	if (debugOutput) log.debug "Deleted thermostat child ${thermostatDni} and its exact state entries; next child: ${state.nextChild}"
}

void componentDeleteOutdoorChild(id) {
	String childDeviceId = (id instanceof Number || id instanceof CharSequence) ? id.toString() : id.id.toString()
	["Humidity", "Temperature"].each { String sensorType ->
		String dni = "${childDeviceId}-${sensorType}"
		if (getChildDevice(dni)) deleteChildDevice(dni)
	}
}

// a version of refresh for those Outdoor sensors to use.
void componentRefresh(cd) { 
	log.info "${cd.displayName} Component Refresh button pushed."
}

void componentInitialize(cd) { 
	cool_sp = location.temperatureScale == 'F' ? 75.0 : 23.8
	heat_sp = location.temperatureScale == 'F' ? 68.0 : 20.0
	log.info "${cd.displayName} Component Initialized."
	getChildDevice(cd.deviceNetworkId).parse([[name:"supportedThermostatFanModes", value: ["\"auto\"", "\"circulate\"", "\"on\""], descriptionText:"${cd.displayName} Supported Fan Modes defined"]])
	getChildDevice(cd.deviceNetworkId).parse([[name:"supportedThermostatModes", value: ["\"auto\"", "\"cool\"", "\"emergency heat\"", "\"heat\"", "\"off\""], descriptionText:"${cd.displayName} Supported Modes defined"]])
	getChildDevice(cd.deviceNetworkId).parse([[name:"coolingSetpoint", value: cool_sp, unit:"°${location.temperatureScale}", descriptionText:"${cd.displayName} Cooling Setpoint: 75"]])
	getChildDevice(cd.deviceNetworkId).parse([[name:"heatingSetpoint", value : heat_sp, unit:"°${location.temperatureScale}", descriptionText:"${cd.displayName} Heating Setpoint: 68"]])
	getChildDevice(cd.deviceNetworkId).parse([[name:"hysteresis", value: 0.5, unit:"°${location.temperatureScale}", descriptionText:"${cd.displayName} Hysteresis: 0.5"]])
	getChildDevice(cd.deviceNetworkId).parse([[name:"temperature", value: heat_sp, unit:"°${location.temperatureScale}", descriptionText:"${cd.displayName} Temperature: 68"]])
	getChildDevice(cd.deviceNetworkId).parse([[name:"thermostatFanMode", value: 'auto', descriptionText:"${cd.displayName} Thermostat Fan Mode: Auto"]])
	getChildDevice(cd.deviceNetworkId).parse([[name:"thermostatMode", value: 'off', descriptionText:"${cd.displayName} Thermostat Mode: Off"]])
	getChildDevice(cd.deviceNetworkId).parse([[name:"thermostatOperatingState", value: 'idle', descriptionText:"${cd.displayName} thermostatOperatingState: Idle"]])
	getChildDevice(cd.deviceNetworkId).parse([[name:"thermostatSetpoint", value: heat_sp, unit:"°${location.temperatureScale}", descriptionText:"${cd.displayName} thermostatSetpoint: 68"]])
	getChildDevice(cd.deviceNetworkId).parse([[name:"emergencyHeatCommand", value: 4, descriptionText:"${cd.displayName} emergencyHeatCommand"]])
}

// Thermostat mode section
void componentOff(cd) {
	if (descTextEnable) log.info "received off request from ${cd.displayName}"
	setThermostatMode(cd, 'off')
}

void componentAuto(cd) {
	if (descTextEnable) log.info "received Auto request from ${cd.displayName}"
	setThermostatMode(cd, 'auto')
}

void componentCool(cd) {
	if (descTextEnable) log.info "received Cool request from ${cd.displayName}"
	setThermostatMode(cd, 'cool')
}

void componentHeat(cd) {
	if (descTextEnable) log.info "received Heat request from ${cd.displayName}"
	setThermostatMode(cd, 'heat')
}

void componentEmergencyHeat(cd) {
	if (descTextEnable) log.info "received Emergency Heat request from ${cd.displayName}"
	Boolean allowed = emergencyHeatAllowed(cd)
	if (allowed == true) {
		if (debugOutput) log.debug "Set Emergency/Auxiliary Heat On"
		setThermostatMode(cd, 'emergency heat')
	} else if (allowed == null) {
		log.warn "${cd.displayName}: Emergency Heat capability is not known yet. Refresh this thermostat and try again."
	} else {
		log.warn "${cd.displayName}: this thermostat reports that Emergency Heat is not allowed."
	}
}

void setThermostatMode(cd, mode) {
	if (debugOutput) log.debug "setThermostatMode: $mode"

	String[] dniParts = cd.deviceNetworkId.split("[-_]")
	if (state.childParamMap."${dniParts[2]}".emergencyHeatCommand == "0") {
		state.deviceSetting."${dniParts[2]}".SystemSwitch = modeMap0.find{ mode == it.key }?.value }
	else {
		state.deviceSetting."${dniParts[2]}".SystemSwitch = modeMap4.find{ mode == it.key }?.value }
	setStatus(cd)

	if(device.data.SetStatus==1)
	{
		getChildDevice(cd.deviceNetworkId).parse([[name:"thermostatMode", value:mode, descriptionText:"${cd.displayName} Thermostat was Set to $mode"]])
	    	cd.setLastRunningMode(mode) 
	}
}

void componentSetThermostatMode(cd, mode) {
	if (descTextEnable) log.info "received Thermostat Mode request from ${cd.displayName}"
	setThermostatMode(cd, mode)
}
// end of section


// Heat/Cool Set/Up/Down section
void componentSetCoolingSetpoint(cd, val) {
	if (descTextEnable) log.info "received Cooling Setpoint request from ${cd.displayName}: $val"
	float valIn = val // for limits check
	val = limitSetpoint(cd, val, "cool")
	if (valIn != val) log.warn "SetPoint limited due to: out of range" 
	String[] dniParts = cd.deviceNetworkId.split("[-_]")
	deviceSettingInitDB(cd, state.childParamMap."${dniParts[2]}".setPermHold) 	 // reset all params, then set individually
	state.deviceSetting."${dniParts[2]}".CoolSetpoint = val
	setStatus(cd)
	getChildDevice(cd.deviceNetworkId).parse([[name:"coolingSetpoint", value:val, descriptionText:"${cd.displayName} Cooling Setpoint is ${val}", unit:"°${location.temperatureScale}"]])
	getChildDevice(cd.deviceNetworkId).parse([[name:"thermostatSetpoint", value:val, descriptionText:"${cd.displayName} Thermostat Setpoint is ${val}", unit:"°${location.temperatureScale}"]])
}

void componentSetHeatingSetpoint(cd, val) {
	if (descTextEnable) log.info "received Heating Setpoint request from ${cd.displayName}: $val"
	float valIn = val // for limits check
	val = limitSetpoint(cd, val, "heat")
	if (valIn != val) log.warn "SetPoint limited due to: out of range" 
	String[] dniParts = cd.deviceNetworkId.split("[-_]")
	deviceSettingInitDB(cd, state.childParamMap."${dniParts[2]}".setPermHold) 	 // reset all params, then set individually
	state.deviceSetting."${dniParts[2]}".HeatSetpoint = val
	setStatus(cd)
	getChildDevice(cd.deviceNetworkId).parse([[name:"heatingSetpoint", value:val, descriptionText:"${cd.displayName} Heating Setpoint is ${val}", unit:"°${location.temperatureScale}"]])
	getChildDevice(cd.deviceNetworkId).parse([[name:"thermostatSetpoint", value:val, descriptionText:"${cd.displayName} Thermostat Setpoint is ${val}", unit:"°${location.temperatureScale}"]])
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
	if (descTextEnable) log.info "received Fan Mode request from ${cd.displayName}"
	setThermostatFanMode(cd, mode)
}

void componentFanAuto(cd) {
	if (descTextEnable) log.info "received Fan Auto request from ${cd.displayName}"
	setThermostatFanMode(cd, 'auto')
}

void componentFanCirculate(cd) {
	if (descTextEnable) log.info "received Fan Circulate request from ${cd.displayName}"
	setThermostatFanMode(cd, 'circulate')
}

void componentFanOn(cd) {
	if (descTextEnable) log.info "received Fan On request from ${cd.displayName}"
	setThermostatFanMode(cd, 'on')
}

def setThermostatFanMode(cd, mode) { 
	if (debugOutput) log.debug "setThermostatFanMode: $mode"
	def fanMode = null
	String[] dniParts = cd.deviceNetworkId.split("[-_]")
	state.deviceSetting."${dniParts[2]}".FanMode = fanMap.find{ mode == it.key }?.value
	setStatus(cd)

	if(device.data.SetStatus==1)
	{
		getChildDevice(cd.deviceNetworkId).parse([[name:"thermostatFanMode", value:mode, descriptionText:"${cd.displayName} Fan was Set to $mode"]])
	}
}
// end of section


void componentSetFollowSchedule(cd) {
	if (descTextEnable) log.info "received Set Follow Schedule request from ${cd.displayName}"
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

	getStatus(cd, fromUnauth)

	Purpose: Acquire settings from the Thermostat

	Notes: JSON is returned and then "sendEvents" (via Child's parse) puts the data into the UI. 
	       
   ------------------------------------------------------------------ */

def getStatus(cd, Boolean fromUnauth = false, Map session = null) {
	String[] dniParts = cd.deviceNetworkId.split("[-_]")
	if (debugOutput) log.debug "enable outside temps = ${state.childParamMap."${dniParts[2]}".enableOutdoorTemps}"
	def today = new Date()
	getChildDevice(cd.deviceNetworkId).parse([[name:"TCCstatus", value:"begin", descriptionText:"${cd.displayName} TCC transaction: begin"]])
	//if (debugOutput) log.debug "https://${tccSite()}/portal/Device/CheckDataSession/${settings.honeywelldevice}?_=$today.time"

	def params = [
	    uri: "https://${tccSite()}/portal/Device/CheckDataSession/${state.childParamMap."${dniParts[2]}".honeywelldevice}",
	    headers: [
	        'Accept': '*/*', // */ comment
	        'DNT': '1',
	        'Cache': 'false',
	        'dataType': 'json',
	        'Accept-Encoding': 'plain',
	        'Cache-Control': 'max-age=0',
	        'Accept-Language': 'en-US,en,q=0.8',
		        'Connection': 'close',
	        'Referer': "https://${tccSite()}/portal",
	        'X-Requested-With': 'XMLHttpRequest',
	        'User-Agent': 'Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/28.0.1500.95 Safari/537.36',
	        'Cookie': session?.cookies
	    ],
		  timeout: statusRequestTimeoutSeconds
	]

	def pData = [dni:cd.deviceNetworkId, fromUnauth:fromUnauth, sid:session?.sid]
	if (debugOutput) log.debug "sending getStatus request: ${params.uri}"
	asynchttpGet("getStatusHandler", params, pData)
}

def getStatusHandler (resp, data) {
	def cd = getChildDevice(data?.dni)
	if (!cd) return
	Boolean sessionRejected = false
	try {
		if (resp == null) throw new IllegalStateException("async request returned no response")
		Integer responseStatus = resp.getStatus()
		sessionRejected = responseStatus in [302, 401, 403]
		if (resp.hasError()) {
			String errorMessage = resp.getErrorMessage()
			throw new IllegalStateException("async request failed (HTTP ${responseStatus})${errorMessage ? ": ${errorMessage}" : ""}")
		}

		if (responseStatus == 200 || responseStatus == 207) {
			String responseText = resp.getData()?.toString()?.trim()
			if (!responseText) throw new IllegalStateException("response contained no data")
			if (responseText.startsWith("<")) {
				throw new IllegalStateException("response contained a human-readable page instead of thermostat data")
			}
			Map setStatusResult = parseJson(responseText)
			if (!setStatusResult?.latestData?.uiData) {
				throw new IllegalStateException("response did not contain thermostat data")
			}
			confirmTccSession(data?.sid)
			//if (debugOutput) { 
			//    log.debug "Request was successful, $resp.status"
			//    log.debug "data: $setStatusResult, rdata: $data"
			//    log.debug "ld: $setStatusResult.latestData.uiData"
			//    log.debug "ld: $setStatusResult.latestData.fanData"
			//}
			getStatusDistrib(cd, setStatusResult)
			return
		}
		else {
			throw new IllegalStateException("HTTP ${responseStatus}")
		}
	    }
	catch (e) {
		if (sessionRejected) invalidateTccSession(data?.sid)
		if (debugOutput) log.error "getStatus response invalid for ${cd.displayName}: $e"
		if (sessionRejected && data?.fromUnauth != true) {
			if (descTextEnable) log.info "TCC session rejected for ${cd.displayName}; retrying login in 60 seconds"
			getChildDevice(cd.deviceNetworkId).parse([[name:"TCCstatus", value:"retry login", descriptionText:"${cd.displayName} TCC transaction: retry login"]])
			scheduleRefreshRetry(cd, 60, true)
		} else {
			getChildDevice(cd.deviceNetworkId).parse([[name:"TCCstatus", value:"failed", descriptionText:"${cd.displayName} TCC transaction: failed"]])
			if (sessionRejected) {
				log.warn "${cd.displayName}: status failed after authentication retry; waiting until the next poll."
			} else {
				log.warn "${cd.displayName}: transient TCC status failure; waiting until the next poll."
			}
		}
	    }
}


def getStatusDistrib(cd, Map decodedResult) {

	Float curTemp = decodedResult.latestData.uiData.DispTemperature
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
	String childNumber = childKey(cd)
	Map thermostatStatus = childStatus(cd)
	thermostatStatus.isEmergencyHeatAllowed = decodedResult.latestData.uiData.SwitchEmergencyHeatAllowed
	thermostatStatus.heatLowerSetptLimit = decodedResult.latestData.uiData.HeatLowerSetptLimit
	thermostatStatus.heatUpperSetptLimit = decodedResult.latestData.uiData.HeatUpperSetptLimit
	thermostatStatus.coolLowerSetptLimit = decodedResult.latestData.uiData.CoolLowerSetptLimit
	thermostatStatus.coolUpperSetptLimit = decodedResult.latestData.uiData.CoolUpperSetptLimit
	state.childStatus[childNumber] = thermostatStatus
	removeLegacyGlobalCapabilities()

	String[] dniParts = cd.deviceNetworkId.split("[-_]") // which child 'owns this'?
	
	if (holdTime != 0) { getChildDevice(cd.deviceNetworkId).parse([[name:"followSchedule", value:"TemporaryHold", descriptionText:"${cd.displayName} was Set to Temporary Hold"]]) }
	if (vacationHoldMode == true) { getChildDevice(cd.deviceNetworkId).parse([[name:"followSchedule", value:"VacationHold", descriptionText:"${cd.displayName} was Set to Vacation Hold"]]) }
	if (vacationHoldMode == false && holdTime == 0 && isScheduleCapable == true ) { getChildDevice(cd.deviceNetworkId).parse([[name:"followSchedule", value:"FollowingSchedule", descriptionText:"${cd.displayName} was Set to Following Schedule"]]) }


	Integer fanMode = decodedResult.latestData.fanData.fanMode
	Boolean fanIsRunning = decodedResult.latestData.fanData.fanIsRunning

	// set fan and operating state
	String fanState = "idle"
	if (fanIsRunning) { fanState = "on" } 

	String operatingState = [ 0: 'idle', 1: 'heating', 2: 'cooling' ][equipmentStatus] ?: 'idle'

	if ((state.childParamMap."${dniParts[2]}".haveHumidifier != 'Yes') && (fanIsRunning == true) && (equipmentStatus == 0)) { 
	    operatingState = "fan only"
	} 
	else if ((state.childParamMap."${dniParts[2]}".haveHumidifier == 'Yes')  && (fanIsRunning == true) && (equipmentStatus == 0) && (fanMode == 0)) {
	    operatingState = "Humidifying"
	}

	logInfo("Get Operating State: $operatingState - Fan to $fanState")

	n = [ 0: 'auto', 2: 'circulate', 1: 'on', 3: 'followSchedule' ][fanMode]
	getChildDevice(cd.deviceNetworkId).parse([[name:"thermostatFanMode", value:n, descriptionText:"${cd.displayName} Fan was Set to $n"]])

	// 'emergency heat' can be a four or zero.
	n = [ 1: 'heat', 2: 'off', 3: 'cool', 5: 'auto', 4: 'emergency heat', 0: 'emergency heat' ][switchPos] ?: 'auto' 
	getChildDevice(cd.deviceNetworkId).parse([[name:"temperature", value:curTemp.round(2), descriptionText:"${cd.displayName} Temperature was Set to $curTemp", unit: "°${location.temperatureScale}"]])
	getChildDevice(cd.deviceNetworkId).parse([[name:"thermostatMode", value:n, descriptionText:"${cd.displayName} Mode was Set to $n"]])
	cd.setLastRunningMode(n) // lastRunningMode in the Child

	//Send events 
	if (hasIndoorHumid == false) { curHumidity = 0 }
	getChildDevice(cd.deviceNetworkId).parse([[name:"thermostatOperatingState", value:operatingState, descriptionText:"${cd.displayName} Op State was Set to $operatingState"]])
	getChildDevice(cd.deviceNetworkId).parse([[name:"fanOperatingState", value:fanState, descriptionText:"${cd.displayName} Fan was Set to $fanState"]])
	getChildDevice(cd.deviceNetworkId).parse([[name:"coolingSetpoint", value:coolSetPoint, descriptionText:"${cd.displayName} Cooling was Set to $coolSetPoint", unit:"°${location.temperatureScale}"]])
	getChildDevice(cd.deviceNetworkId).parse([[name:"heatingSetpoint", value:heatSetPoint, descriptionText:"${cd.displayName} Heating was Set to $heatSetPoint", unit:"°${location.temperatureScale}"]])
	getChildDevice(cd.deviceNetworkId).parse([[name:"humidity", value:curHumidity as Integer, descriptionText:"${cd.displayName} Humidity was Set to $curHumidity", unit:"%"]])

	if (state.childParamMap."${dniParts[2]}".haveHumidifier == 'Yes') {
		// kludge to figure out if humidifier is on, fan has to be auto, and if fan is on but not heat/cool and we have enabled the humidifyer it should be humidifying"
	     
	 	if ((fanIsRunning == true) && (equipmentStatus == 0) && (fanMode == 0)) {
			getChildDevice(cd.deviceNetworkId).parse([[name:"humidifierStatus", value:"Humidifying", descriptionText:"${cd.displayName} Humidifier was Set to Humidifying"]])
		} 
		else {
			getChildDevice(cd.deviceNetworkId).parse([[name:"humidifierStatus", value:"Idle", descriptionText:"${cd.displayName} Humidifier was Set to Idle"]])
		}
	}

	def now = new Date().format('MM/dd/yyyy h:mm a', location.timeZone)
	getChildDevice(cd.deviceNetworkId).parse([[name:"lastUpdate", value:now, descriptionText:"${cd.displayName} Last Update was Set to $now"]])
	
	if (state.childParamMap."${dniParts[2]}".enableOutdoorTemps == "Yes") {
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


def getHumidifierStatus(cd, String cookies) {
	String[] dniParts = cd.deviceNetworkId.split("[-_]")
	if (debugOutput)  log.debug "in get humid status enable humidity = ${state.childParamMap."${dniParts[2]}".enableHumidity}"
	if (state.childParamMap."${dniParts[2]}".haveHumidifier == 'No') return
	
	def params = [
	  uri: "https://${tccSite()}/portal/Device/CheckDataSession/${state.childParamMap."${dniParts[2]}".honeywelldevice}",
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
	        'Cookie': cookies
        ],
	  timeout: 10
    ]

	if (debugOutput) log.debug "sending gethumidStatus request: ${params.uri}"
	def pData = [dni:cd.deviceNetworkId]
	asynchttpGet("getHumidStatusHandler", params, pData)
}


def getHumidStatusHandler(resp, data) {
	if(resp.getStatus() == 200 || resp.getStatus() == 207) {
		if (debugOutput) log.debug "GetHumidity Request was successful, $resp.status"
		def cdd = getChildDevice(data?.dni)
		if (!cdd) return
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
	    		def p30 = pair3[0]
		
	    		HumMax = p30.toInteger() 
		}
	}
        
     	//Send events via Child
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
	def cdd = data?.dni ?: data["cd"]
	if (cdd instanceof List) cdd = cdd[0]
	Integer authRetry = (data?.authRetry ?: 0) as Integer
	def cd = getChildDevice(cdd)
	if (!cd) return
	String[] dniParts = cd.deviceNetworkId.split("[-_]")
	// Snapshot only the settings currently waiting to be sent. A retry after another
	// handler has already sent them must not submit an empty control request.
	def deltaStates = state.deviceSetting."${dniParts[2]}".findAll{it.value != null}
	if (!deltaStates) return

	device.data.SetStatus = 0
	getChildDevice(cd.deviceNetworkId).parse([[name:"TCCstatus", value:"begin", descriptionText:"${cd.displayName} TCC transaction: begin"]])

	Map session = getTccSession(cd)
	if (session.status != "ready") {
		if (session.status == "deferred") {
			Integer delay = (session.retryAfter ?: loginDeferredSeconds) as Integer
			if (debugOutput) log.debug "${cd.displayName}: another TCC login is in progress; retrying settings in ${delay} seconds"
			runIn(delay, "settingsAccumWait", [overwrite:false, data:[dni:cd.deviceNetworkId, authRetry:authRetry]])
			getChildDevice(cd.deviceNetworkId).parse([[name:"TCCstatus", value:"retry login", descriptionText:"${cd.displayName} TCC transaction: retry login"]])
		} else {
			getChildDevice(cd.deviceNetworkId).parse([[name:"TCCstatus", value:"failed", descriptionText:"${cd.displayName} TCC transaction: failed"]])
		}
		return // retain the unsent settings for a later attempt
	}
	if (debugOutput) log.debug "Honeywell TCC 'setStatus'"
	def today = new Date()
	deltaStates["DeviceID"] = state.childParamMap."${dniParts[2]}".honeywelldevice
	deltaStates["DisplayUnits"] = location.temperatureScale
	if (debugOutput) log.debug "setStatus body: $deltaStates"

	
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
	        'Referer': "https://${tccSite()}/portal/Device/Control/${state.childParamMap."${dniParts[2]}".honeywelldevice}",
	        'X-Requested-With': 'XMLHttpRequest',
	        'User-Agent': 'Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/28.0.1500.95 Safari/537.36',
	        'Cookie': session.cookies
	    ],
	    body: deltaStates,
	  timeout: 10
	]

	Boolean sent = false
	Boolean authRejected = false
	try {
		httpPost(params) {
		    resp ->
			def setStatusResult = resp.data
			if (debugOutput) log.debug "Request was successful, $resp.status"
			device.data.SetStatus = 1
			sent = true
			getChildDevice(cd.deviceNetworkId).parse([[name:"TCCstatus", value:"success", descriptionText:"${cd.displayName} TCC transaction: success"]])
		}
	} 
	catch (e) {
		if (e instanceof groovyx.net.http.HttpResponseException && e.statusCode in [302, 401, 403]) {
			authRejected = true
			invalidateTccSession(session.sid)
		}
		log.error "Something went wrong: $e"
		if (authRejected && authRetry < 1) {
			runIn(loginBackoffSeconds, "settingsAccumWait", [overwrite:false, data:[dni:cd.deviceNetworkId, authRetry:authRetry + 1]])
			getChildDevice(cd.deviceNetworkId).parse([[name:"TCCstatus", value:"retry login", descriptionText:"${cd.displayName} TCC transaction: retry login"]])
		} else {
			getChildDevice(cd.deviceNetworkId).parse([[name:"TCCstatus", value:"failed", descriptionText:"${cd.displayName} TCC transaction: failed"]])
		}
	}

	// Clear only the values in the successful snapshot. Commands that arrived while
	// the POST was in progress remain pending for their own scheduled send.
	if (sent) clearSentDeviceSettings(cd, deltaStates)
}



/* ------------------------------------------------------------------

	TCC session retention

	Purpose: Keep one Parent-owned session for all thermostat children. Cookie
	         count remains an empirical confidence check because Honeywell does
	         not reliably return a known cookie set. Valid thermostat data is the
	         final confirmation that a candidate session works.

   ------------------------------------------------------------------ */

private String tccSessionKey() { device.id.toString() }

private Map tccSessionSnapshot() {
	String cookies = device.getDataValue("cookiess") ?: device.data?.cookiess
	if (!cookies) return null
	Map meta = (state.tccSession instanceof Map) ? state.tccSession : [:]
	if (!meta.sid) {
		meta = [sid:"legacy-${now()}", created:now(), confirmed:false, cookieCount:cookies.split(";", -1).length - 1]
		state.tccSession = meta
	}
	return [cookies:cookies, sid:meta.sid, confirmed:meta.confirmed == true, cookieCount:meta.cookieCount]
}

private Map getTccSession(cd) {
	Map session = tccSessionSnapshot()
	if (session) return [status:"ready"] + session

	String key = tccSessionKey()
	tccLoginLocks.putIfAbsent(key, new Semaphore(1))
	Semaphore lock = tccLoginLocks.get(key)
	if (!lock.tryAcquire()) return [status:"deferred", retryAfter:loginDeferredSeconds]

	try {
		session = tccSessionSnapshot()
		if (session) return [status:"ready"] + session // another child completed login

		Long failedAt = state.tccLoginFailedAt as Long
		if (failedAt) {
			Long remaining = (loginBackoffSeconds * 1000L) - (now() - failedAt)
			if (remaining > 0) {
				Integer seconds = Math.max(1, ((remaining + 999L) / 1000L) as Integer)
				return [status:"backoff", retryAfter:seconds]
			}
		}

		Map candidate = performTccLogin(cd)
		if (!candidate) {
			state.tccLoginFailedAt = now()
			return [status:"failed", retryAfter:loginBackoffSeconds]
		}

		String sid = now().toString()
		device.updateDataValue("cookiess", candidate.cookies)
		state.tccSession = [sid:sid, created:now(), confirmed:false, cookieCount:candidate.cookieCount, authCookieSeen:candidate.authCookieSeen]
		state.tccLoginFailedAt = null
		return [status:"ready", cookies:candidate.cookies, sid:sid, confirmed:false, cookieCount:candidate.cookieCount]
	} finally {
		lock.release()
	}
}

private void confirmTccSession(sid) {
	Map meta = (state.tccSession instanceof Map) ? state.tccSession : null
	if (meta?.sid && "${meta.sid}" == "${sid}") {
		meta.confirmed = true
		meta.confirmedAt = now()
		state.tccSession = meta
	}
}

private void invalidateTccSession(sid = null) {
	Map meta = (state.tccSession instanceof Map) ? state.tccSession : null
	if (sid == null || !meta?.sid || "${meta.sid}" == "${sid}") clearTccSession()
}

private void clearTccSession() {
	device.updateDataValue("cookiess", "")
	state.tccSession = null
	state.tccLoginFailedAt = null
}

private Map performTccLogin(cd) {
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
		body: [timeOffset: '240', UserName: "${settings.username}", Password: "${settings.password}", RememberMe: 'false'],
		timeout: 15
	]

	List<String> cookies = []
	List<String> cookieNames = []
	try {
		httpPost(params) { response ->
			if (debugOutput) log.debug "TCC login response: ${response.status}"
			response.getHeaders('Set-Cookie').each { header ->
				String raw = header.value
				String nameValue = raw.split(';', 2)[0].trim()
				Integer equalsAt = nameValue.indexOf('=')
				if (equalsAt < 1) return
				String cookieName = nameValue.substring(0, equalsAt).trim()
				String cookieValue = nameValue.substring(equalsAt + 1).trim()
				if (!cookieValue || tccCookieExpired(raw)) return
				cookies << nameValue
				cookieNames << cookieName
			}
		}
	} catch (e) {
		log.warn "${cd.displayName}: TCC login failed: ${e.message ?: e}"
		return null
	}

	Integer cookieCount = cookies.size()
	Boolean authCookieSeen = cookieNames.any { it.startsWith('.ASPXAUTH') }
	if (debugOutput) log.debug "TCC login returned ${cookieCount} usable cookies; names: ${cookieNames}; auth cookie present: ${authCookieSeen}"
	if (cookieCount < minCookieCount) {
		if (debugOutput) log.debug "TCC login candidate rejected: expected at least ${minCookieCount} cookies"
		return null
	}
	return [cookies:cookies.join(';') + ';', cookieCount:cookieCount, authCookieSeen:authCookieSeen]
}

private Boolean tccCookieExpired(String raw) {
	String expires = raw.split(';').find { it.trim().toLowerCase().startsWith('expires=') }
	if (!expires) return false
	String value = expires.substring(expires.indexOf('=') + 1).trim()
	try {
		return new Date(value) < new Date()
	} catch (e) {
		if (debugOutput) log.debug "TCC cookie expiry could not be parsed; treating its lifetime as unknown"
		return false
	}
}

// Value setting Section
//
// params passed in from each Child: DNI, honeywelldevice, haveHumidifier, enableOutdoorTemps, enableHumidity, setPermHold, pollIntervals, emergencyHeatCommand
void setParams(cDNI, dev, hH, eOT, eH, sPH, pI, eHC) {
	String childNumber = childKeyFromDni(cDNI)
	ensureChildMaps(childNumber)
	state.childParamMap[childNumber].childDNI 			= cDNI
	state.childParamMap[childNumber].honeywelldevice 		= dev
	state.childParamMap[childNumber].haveHumidifier 		= hH
	state.childParamMap[childNumber].enableOutdoorTemps 	= eOT
	state.childParamMap[childNumber].enableHumidity		= eH
	state.childParamMap[childNumber].setPermHold 		= sPH
	state.childParamMap[childNumber].pollIntervals 		= pI
	state.childParamMap[childNumber].emergencyHeatCommand 	= eHC

	if (debugOutput) log.debug "ChildParams: $cDNI, $dev, $hH, $eOT, $eH, $sPH, $pI, $eHC, -${state.childParamMap[childNumber].honeywelldevice}-"
}

// initialize the device values. Each method overwrites it's specific value
def deviceSettingInitDB(cd, val) { 	 // reset all params, then set individually
	String[] dniParts = cd.deviceNetworkId.split("[-_]")

	state.deviceSetting."${dniParts[2]}".StatusHeat = val
	state.deviceSetting."${dniParts[2]}".StatusCool = val

	// don't clear multiple times
	if ( val == null ) {
		state.deviceSetting."${dniParts[2]}".SystemSwitch = null 
		state.deviceSetting."${dniParts[2]}".HeatSetpoint = null
		state.deviceSetting."${dniParts[2]}".CoolSetpoint = null
		state.deviceSetting."${dniParts[2]}".HeatNextPeriod = null
		state.deviceSetting."${dniParts[2]}".CoolNextPeriod = null
		state.deviceSetting."${dniParts[2]}".FanMode = null
		state.deviceSetting."${dniParts[2]}".TemporaryHoldUntilTime=null
		state.deviceSetting."${dniParts[2]}".VacationHold=null
	}
}
// end of section

private void clearSentDeviceSettings(cd, Map sent) {
	String[] dniParts = cd.deviceNetworkId.split("[-_]")
	Map current = state.deviceSetting."${dniParts[2]}"
	if (!current) return
	sent.each { String name, value ->
		if (name in ["DeviceID", "DisplayUnits"]) return
		if (current[name] == value) current[name] = null
	}
}


private String thermostatPrefix() {
	return "${device.id}-${type()}_"
}

private String thermostatChildBaseName() {
	String parentName = device.displayName?.toString()?.trim() ?: "Honeywell"
	String childType = type()
	parentName = parentName.replaceFirst(/(?i)\s+Parent$/, "").trim()
	if (!parentName) parentName = "Honeywell"
	return parentName.toLowerCase().endsWith(childType.toLowerCase()) ? parentName : "${parentName} ${childType}"
}

private List thermostatChildren() {
	String prefix = thermostatPrefix()
	return getChildDevices()?.findAll { it.deviceNetworkId?.startsWith(prefix) } ?: []
}

private void ensureStateMaps() {
	if (state.childParamMap == null) state.childParamMap = [:]
	if (state.deviceSetting == null) state.deviceSetting = [:]
	if (state.childStatus == null) state.childStatus = [:]
}

private void ensureChildMaps(String childNumber) {
	ensureStateMaps()
	if (state.childParamMap[childNumber] == null) {
		state.childParamMap[childNumber] = [childDNI: null, honeywelldevice: null, haveHumidifier: null, enableOutdoorTemps: null, enableHumidity: null, setPermHold: null, pollIntervals: null, emergencyHeatCommand: null]
	}
	if (state.deviceSetting[childNumber] == null) {
		state.deviceSetting[childNumber] = [SystemSwitch: null, StatusHeat: null, StatusCool: null, HeatSetpoint: null, CoolSetpoint: null, HeatNextPeriod: null, CoolNextPeriod: null, FanMode: null, TemporaryHoldUntilTime: null, VacationHold: null]
	}
	if (state.childStatus[childNumber] == null) {
		state.childStatus[childNumber] = [isEmergencyHeatAllowed: null, heatLowerSetptLimit: null, heatUpperSetptLimit: null, coolLowerSetptLimit: null, coolUpperSetptLimit: null]
	}
}

private Integer nonNegativeInteger(value) {
	if (value == null) return null
	try {
		Integer parsed = value.toString().toInteger()
		return parsed >= 0 ? parsed : null
	} catch (ignored) {
		return null
	}
}

private String nextAvailableChildNumber() {
	Set<Integer> used = [] as Set
	thermostatChildren().each { cd ->
		Integer childNumber = nonNegativeInteger(childKey(cd))
		if (childNumber != null) used << childNumber
	}
	Integer minimum = used ? used.max() + 1 : 0
	Integer saved = nonNegativeInteger(state.nextChild)
	Integer candidate = saved == null ? minimum : Math.max(saved, minimum)
	while (getChildDevice("${thermostatPrefix()}${candidate}")) candidate++
	return candidate.toString()
}

private String childKey(cd) {
	return childKeyFromDni(cd?.deviceNetworkId)
}

private String childKeyFromDni(value) {
	String dni = value?.toString()
	String prefix = thermostatPrefix()
	if (!dni?.startsWith(prefix) || dni.length() <= prefix.length()) {
		throw new IllegalArgumentException("Device ${dni} is not a thermostat child of ${device.id}")
	}
	return dni.substring(prefix.length())
}

private Map childStatus(cd) {
	String key = childKey(cd)
	ensureChildMaps(key)
	return state.childStatus[key]
}

private Boolean emergencyHeatAllowed(cd) {
	def allowed = childStatus(cd).isEmergencyHeatAllowed
	return allowed == null ? null : allowed == true
}

private Float limitSetpoint(cd, val, String which) {
	Float requested = val.toFloat()
	Map thermostatStatus = childStatus(cd)
	String lowerName = which == "heat" ? "heatLowerSetptLimit" : "coolLowerSetptLimit"
	String upperName = which == "heat" ? "heatUpperSetptLimit" : "coolUpperSetptLimit"
	Float lower = toFloatOrNull(thermostatStatus[lowerName])
	Float upper = toFloatOrNull(thermostatStatus[upperName])
	Float limited = requested
	if (lower != null) limited = Math.max(limited, lower)
	if (upper != null) limited = Math.min(limited, upper)
	if (lower == null || upper == null) {
		String setpointName = which == "heat" ? "heating" : "cooling"
		log.warn "${cd.displayName}: ${setpointName} setpoint limits are not known yet; refresh this thermostat to enable local range limiting."
	}
	return limited
}

private Float toFloatOrNull(value) {
	if (value == null) return null
	try {
		return value.toString().toFloat()
	} catch (ignored) {
		return null
	}
}

private void removeLegacyGlobalCapabilities() {
	["isEmergencyHeatAllowed", "heatLowerSetptLimit", "heatUpperSetptLimit", "coolLowerSetptLimit", "coolUpperSetptLimit"].each { String name ->
		state.remove(name)
	}
}

private void scheduleRefreshRetry(cd, Integer delay, Boolean fromUnauth) {
	runIn(delay, "refreshFromRunin", [overwrite:false, data:[dni:cd.deviceNetworkId, fromUnauth:fromUnauth]])
}

def refreshFromRunin(data) {
	def cdd = data?.dni ?: data?.cd
	if (cdd instanceof List) cdd = cdd[0]
	def cd = getChildDevice(cdd)
	if (!cd) return
	if (debugOutput) log.debug "Calling refresh after TCC failure for ${cd.displayName}"
	componentDoRefresh(cd, data?.fromUnauth == true)
}


private logInfo (msg) {
	if (settings?.descTextEnable || settings?.descTextEnable == null) log.info "$msg"
}


def getThisCopyright(){"&copy; 2022 C Steele "}
