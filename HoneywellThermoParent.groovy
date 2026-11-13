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
 * csteele: v2.0.23  Match Honeywell's SystemSwitch enum: decode 0 as Emergency Heat, decode both
 *                    4 (auto heat) and 5 (auto cool) as Auto, send Emergency Heat as 0, and send
 *                    Auto as 4. Preserve the prior displayed mode and warn when Honeywell returns
 *                    an unsupported switch value. Enforce each child's Emergency Heat capability
 *                    for every command path and ignore the retired Zero/Four child preference.
 * csteele: v2.0.22  Retry one transient status GET or command POST after 60 seconds while retaining
 *                    the existing TCC session. Only explicit authentication rejection can discard
 *                    the session and trigger a login. Log caught status-response details at debug
 *                    level so expected Honeywell timeouts do not create red error-log entries.
 *                   Add selectable Debug logging time.
 *                   Mask Honeywell Device IDs in driver-owned diagnostics while retaining the full
 *                    values in preferences, Parent state, and HTTP requests.
 *                   Isolate command accumulation, immutable in-flight delivery, bounded retries,
 *                    retained failures, and acknowledgements per thermostat child and command ID.
 *                   Give every child refresh a generation so newer manual or scheduled refreshes
 *                    invalidate stale callbacks and queued retries without affecting other children.
 *                   Correct the legacy humidifier parser's CancelMin response-line type and
 *                    identify its setpoint, upper-limit, and lower-limit events accurately.
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
		The child device specific data is kept in five Maps: state.deviceSetting, state.childParamMap,
		state.childStatus, state.commandState, and state.refreshState.

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

 public static String version()	{  return "v2.0.23"  }
 public static String tccSite() 	{  return "mytotalconnectcomfort.com"  }
 public static String type() 		{  return "Thermostat"  }

@Field static Map<String, Integer> modeMap = [auto:4, cool:3, heat:1, off:2, 'emergency heat':0]
@Field static Map<String, Map> fanMap = [auto:0, on:1, circulate:2, followSchedule:3] 

// Cookies and session metadata are owned by the Parent so every thermostat child can reuse
// one TCC login. The static semaphore is coordination only; session retention does not depend
// on static fields surviving a driver reload or hub reboot.
@Field static ConcurrentHashMap<String, Semaphore> tccLoginLocks = new ConcurrentHashMap<String, Semaphore>()
@Field static ConcurrentHashMap<String, Semaphore> tccCommandStateLocks = new ConcurrentHashMap<String, Semaphore>()
@Field static ConcurrentHashMap<String, Boolean> tccActiveCommandAttempts = new ConcurrentHashMap<String, Boolean>()
@Field static final Integer loginBackoffSeconds = 60
@Field static final Integer loginDeferredSeconds = 2
@Field static final Integer transientRequestRetrySeconds = 60
@Field static final Integer maxTransientRequestRetries = 1
@Field static final Integer maxCommandAuthenticationRetries = 1
@Field static final Integer commandAccumulationMillis = 1600
@Field static final Integer commandFollowupSeconds = 1
@Field static final Integer commandStateSchemaVersion = 2
@Field static final Integer minCookieCount = 8
@Field static final Integer statusRequestTimeoutSeconds = 10

metadata {
	definition (name: "Honeywell Thermo Parent", namespace: "csteele", author: "C Steele, Eric Thomas, lg kahn", singleThreaded: true) {
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
	   input "debugOutput", "bool",
	   	title: "Enable debug logging", 
	   	required: false,
	   	defaultValue: false,
	   	width: 2

	   input "debugTimeout", "enum", required: false, defaultValue: "0", title: "Automatic debug Log Disable Timeout?", width: 3,  \
	   	options: [ "0":"None", "1800":"30 Minutes", "3600":"60 Minutes", "86400":"1 Day" ]

	   input name: "descTextEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
	}
}




void updated(){
	log.info "updated..."
	log.warn "debug logging is: ${debugOutput == true}"
	log.warn "description logging is: ${descTextEnable == true}"
	unschedule("logsOff")
	Integer debugDisableSeconds = configuredDebugTimeoutSeconds()
	if (debugOutput && debugDisableSeconds > 0) runIn(debugDisableSeconds, "logsOff", [overwrite:true])
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

private String maskedHoneywellDeviceId(value) {
	if (value == null) return "<unset>"
	String raw = value.toString().trim()
	if (!raw) return "<unset>"
	if (raw.length() <= 3) return "xxx"
	return "xxx${raw.substring(raw.length() - 3)}"
}

private def honeywellDeviceIdFor(cd) {
	if (!cd) return null
	return state.childParamMap?.get(childKey(cd))?.honeywelldevice
}

private Map sanitizedTccCommandBody(Map body) {
	Map sanitized = [:]
	body?.each { key, value ->
		sanitized[key] = key?.toString()?.equalsIgnoreCase("DeviceID") ? maskedHoneywellDeviceId(value) : value
	}
	return sanitized
}

private String sanitizedTccLogText(value, deviceId = null) {
	if (value == null) return "unknown failure"
	String sanitized = value.toString()
	List<String> knownIds = []
	if (deviceId != null) knownIds << deviceId.toString().trim()
	if (state.childParamMap instanceof Map) {
		state.childParamMap.values().each { childParams ->
			if (childParams instanceof Map && childParams.honeywelldevice != null)
				knownIds << childParams.honeywelldevice.toString().trim()
		}
	}
	knownIds.findAll { it }.unique().sort { a, b -> b.length() <=> a.length() }.each { String raw ->
		String masked = maskedHoneywellDeviceId(raw)
		if (raw.length() > 3) {
			sanitized = sanitized.replace(raw, masked)
		} else {
			// Avoid replacing every occurrence of a short numeric ID in status codes or timestamps.
			sanitized = sanitized.replace("/${raw}", "/${masked}")
			sanitized = sanitized.replace("DeviceID:${raw}", "DeviceID:${masked}")
			sanitized = sanitized.replace("DeviceID=${raw}", "DeviceID=${masked}")
		}
	}
	// Catch a Device ID embedded in an endpoint even if an asynchronous response arrives
	// after that child's configured ID has changed.
	sanitized = sanitized.replaceAll(/(?i)(CheckDataSession|Control)\/([^\/\s?]+)/) { full, endpoint, raw ->
		return "${endpoint}/${maskedHoneywellDeviceId(raw)}"
	}
	return sanitized
}

private String sanitizedTccFailure(e, deviceId = null) {
	String failureType = e?.class?.simpleName ?: "Exception"
	String detail = e?.message ?: e?.toString()
	return "${failureType}: ${sanitizedTccLogText(detail, deviceId)}"
}

private Integer configuredDebugTimeoutSeconds() {
	try {
		return (settings?.debugTimeout ?: "0").toString().toInteger()
	} catch (ignored) {
		log.warn "Invalid debug logging timeout '${settings?.debugTimeout}'; automatic disable is not scheduled."
		return 0
	}
}


//
// child (component) device methods
//
void componentDoRefresh(cd, Boolean fromUnauth = false, Integer transientRetry = 0, refreshGeneration = null) {
	String childNumber = childKey(cd)
	Long generation = refreshGeneration == null ? beginRefreshGeneration(childNumber) : (refreshGeneration as Long)
	if (!refreshGenerationIsCurrent(childNumber, generation)) {
		if (debugOutput) log.debug "Ignoring stale refresh generation ${generation} for ${cd.displayName}"
		return
	}
	if (debugOutput) log.debug "received Refresh request from ${cd.displayName} to Honeywell TCC 'refresh', unit: = °${location.temperatureScale}, fromUnauth = $fromUnauth, transientRetry = $transientRetry, generation = $generation"
	Map session = getTccSession(cd)
	if (session.status != "ready") {
		Integer delay = (session.retryAfter ?: loginBackoffSeconds) as Integer
		if (session.status == "deferred") {
			if (debugOutput) log.debug "${cd.displayName}: another TCC login is in progress; retrying refresh in ${delay} seconds"
			scheduleRefreshRetry(cd, delay, fromUnauth, transientRetry, generation)
		} else if (!fromUnauth) {
			if (debugOutput) log.debug "${cd.displayName}: TCC login unavailable; retrying refresh in ${delay} seconds"
			getChildDevice(cd.deviceNetworkId).parse([[name:"TCCstatus", value:"retry login", descriptionText:"${cd.displayName} TCC transaction: retry login"]])
			scheduleRefreshRetry(cd, delay, true, transientRetry, generation)
		} else {
			finishRefreshGeneration(childNumber, generation, "failed")
			getChildDevice(cd.deviceNetworkId).parse([[name:"TCCstatus", value:"failed", descriptionText:"${cd.displayName} TCC transaction: failed"]])
		}
		return
	}
	Long requestToken = getStatus(cd, fromUnauth, session, transientRetry, generation) as Long
	if (requestToken == null) return
	try {
		getHumidifierStatus(cd, session.cookies, generation, requestToken)
	} catch (e) {
		log.warn "${cd.displayName}: optional humidifier status request could not be started: ${sanitizedTccFailure(e, honeywellDeviceIdFor(cd))}"
	}
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
	state.commandState = (state.commandState ?: [:]).findAll { it.key.toString() != childNumber }
	state.refreshState = (state.refreshState ?: [:]).findAll { it.key.toString() != childNumber }
	tccCommandStateLocks.remove("${device.id}:${childNumber}")
	String activePrefix = "${device.id}:${childNumber}:"
	tccActiveCommandAttempts.keySet().findAll { it.startsWith(activePrefix) }.each { tccActiveCommandAttempts.remove(it) }
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
	setThermostatMode(cd, 'emergency heat')
}

void setThermostatMode(cd, mode) {
	if (debugOutput) log.debug "setThermostatMode: $mode"

	if (mode == 'emergency heat') {
		Boolean allowed = emergencyHeatAllowed(cd)
		if (allowed == null) {
			log.warn "${cd.displayName}: Emergency Heat capability is not known yet. Refresh this thermostat and try again."
			return
		}
		if (allowed == false) {
			log.warn "${cd.displayName}: this thermostat reports that Emergency Heat is not allowed."
			return
		}
		if (debugOutput) log.debug "Set Emergency/Auxiliary Heat On"
	}

	Integer systemSwitch = modeMap[mode]
	if (systemSwitch == null) {
		log.warn "${cd.displayName}: unsupported thermostat mode '$mode'"
		return
	}
	queueTccCommand(cd,
		[SystemSwitch:systemSwitch],
		[thermostatMode:[value:mode, descriptionText:"${cd.displayName} Thermostat was Set to $mode", setLastRunningMode:true]])
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
	Map commandValues = [CoolSetpoint:val]
	def permanentHold = state.childParamMap."${dniParts[2]}".setPermHold
	if (permanentHold != null) commandValues += [StatusHeat:permanentHold, StatusCool:permanentHold]
	queueTccCommand(cd, commandValues)
	getChildDevice(cd.deviceNetworkId).parse([[name:"coolingSetpoint", value:val, descriptionText:"${cd.displayName} Cooling Setpoint is ${val}", unit:"°${location.temperatureScale}"]])
	getChildDevice(cd.deviceNetworkId).parse([[name:"thermostatSetpoint", value:val, descriptionText:"${cd.displayName} Thermostat Setpoint is ${val}", unit:"°${location.temperatureScale}"]])
}

void componentSetHeatingSetpoint(cd, val) {
	if (descTextEnable) log.info "received Heating Setpoint request from ${cd.displayName}: $val"
	float valIn = val // for limits check
	val = limitSetpoint(cd, val, "heat")
	if (valIn != val) log.warn "SetPoint limited due to: out of range" 
	String[] dniParts = cd.deviceNetworkId.split("[-_]")
	Map commandValues = [HeatSetpoint:val]
	def permanentHold = state.childParamMap."${dniParts[2]}".setPermHold
	if (permanentHold != null) commandValues += [StatusHeat:permanentHold, StatusCool:permanentHold]
	queueTccCommand(cd, commandValues)
	getChildDevice(cd.deviceNetworkId).parse([[name:"heatingSetpoint", value:val, descriptionText:"${cd.displayName} Heating Setpoint is ${val}", unit:"°${location.temperatureScale}"]])
	getChildDevice(cd.deviceNetworkId).parse([[name:"thermostatSetpoint", value:val, descriptionText:"${cd.displayName} Thermostat Setpoint is ${val}", unit:"°${location.temperatureScale}"]])
}

void componentHeatLevelDown(cd) {
	Float base = pendingSetpointBase(cd, "HeatSetpoint", "heatingSetpoint")
	if (base == null) return
	float val = (location.temperatureScale == "F") ? base - 1 : base - 0.5
	componentSetHeatingSetpoint(cd, val)
}

void componentHeatLevelUp(cd) {
	Float base = pendingSetpointBase(cd, "HeatSetpoint", "heatingSetpoint")
	if (base == null) return
	float val = (location.temperatureScale == "F") ? base + 1 : base + 0.5
	componentSetHeatingSetpoint(cd, val)
}

void componentCoolLevelDown(cd) {
	Float base = pendingSetpointBase(cd, "CoolSetpoint", "coolingSetpoint")
	if (base == null) return
	float val = (location.temperatureScale == "F") ? base - 1 : base - 0.5
	componentSetCoolingSetpoint(cd, val)
}

void componentCoolLevelUp(cd) {
	Float base = pendingSetpointBase(cd, "CoolSetpoint", "coolingSetpoint")
	if (base == null) return
	float val = (location.temperatureScale == "F") ? base + 1 : base + 0.5
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
	def fanMode = fanMap.find{ mode == it.key }?.value
	if (fanMode == null) {
		log.warn "${cd.displayName}: unsupported fan mode '$mode'"
		return
	}
	queueTccCommand(cd,
		[FanMode:fanMode],
		[thermostatFanMode:[value:mode, descriptionText:"${cd.displayName} Fan was Set to $mode"]])
}
// end of section


void componentSetFollowSchedule(cd) {
	if (descTextEnable) log.info "received Set Follow Schedule request from ${cd.displayName}"
	queueTccCommand(cd, [StatusHeat:0, StatusCool:0])
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

def getStatus(cd, Boolean fromUnauth = false, Map session = null, Integer transientRetry = 0, refreshGeneration = null) {
	String[] dniParts = cd.deviceNetworkId.split("[-_]")
	String childNumber = dniParts[2]
	Long generation = refreshGeneration == null ? beginRefreshGeneration(childNumber) : (refreshGeneration as Long)
	if (!refreshGenerationIsCurrent(childNumber, generation)) return
	Long requestToken = beginStatusRequest(childNumber, generation)
	if (requestToken == null) return
	def honeywellDeviceId = state.childParamMap."${dniParts[2]}".honeywelldevice
	if (debugOutput) log.debug "enable outside temps = ${state.childParamMap."${dniParts[2]}".enableOutdoorTemps}"
	getChildDevice(cd.deviceNetworkId).parse([[name:"TCCstatus", value:"begin", descriptionText:"${cd.displayName} TCC transaction: begin"]])
	//if (debugOutput) log.debug "sending getStatus request for Device ID ${maskedHoneywellDeviceId(honeywellDeviceId)}"

	def params = [
	    uri: "${tccBaseUrl()}/portal/Device/CheckDataSession/${honeywellDeviceId}",
	    headers: [
	        'Accept': '*/*', // */ comment
	        'DNT': '1',
	        'Cache': 'false',
	        'dataType': 'json',
	        'Accept-Encoding': 'plain',
	        'Cache-Control': 'max-age=0',
	        'Accept-Language': 'en-US,en,q=0.8',
		        'Connection': 'close',
	        'Referer': "${tccBaseUrl()}/portal",
	        'X-Requested-With': 'XMLHttpRequest',
	        'User-Agent': 'Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/28.0.1500.95 Safari/537.36',
	        'Cookie': session?.cookies
	    ],
		  timeout: statusRequestTimeoutSeconds
	]

	def pData = [dni:cd.deviceNetworkId, fromUnauth:fromUnauth, sid:session?.sid, transientRetry:transientRetry, refreshGeneration:generation, requestToken:requestToken]
	if (debugOutput) log.debug "sending getStatus request for Device ID ${maskedHoneywellDeviceId(honeywellDeviceId)}"
	asynchttpGet("getStatusHandler", params, pData)
	return requestToken
}

def getStatusHandler (resp, data) {
	def cd = getChildDevice(data?.dni)
	if (!cd) return
	String childNumber = childKey(cd)
	Long generation = (data?.refreshGeneration ?: 0) as Long
	Long requestToken = (data?.requestToken ?: 0) as Long
	if (!claimStatusCallback(childNumber, generation, requestToken)) {
		if (debugOutput) log.debug "Ignoring stale or duplicate getStatus callback generation ${generation}, request ${requestToken} for ${cd.displayName}"
		return
	}
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
			//if (debugOutput) log.debug "getStatus request was successful, $resp.status"
			getStatusDistrib(cd, setStatusResult)
			finishRefreshGeneration(childNumber, generation, "complete")
			return
		}
		else {
			throw new IllegalStateException("HTTP ${responseStatus}")
		}
	    }
	catch (e) {
		if (sessionRejected) invalidateTccSession(data?.sid)
		if (debugOutput) log.debug "getStatus response invalid for ${cd.displayName}: ${sanitizedTccFailure(e, honeywellDeviceIdFor(cd))}"
		Integer transientRetry = (data?.transientRetry ?: 0) as Integer
		if (sessionRejected && data?.fromUnauth != true) {
			if (descTextEnable) log.info "TCC session rejected for ${cd.displayName}; retrying login in 60 seconds"
			getChildDevice(cd.deviceNetworkId).parse([[name:"TCCstatus", value:"retry login", descriptionText:"${cd.displayName} TCC transaction: retry login"]])
			scheduleRefreshRetry(cd, loginBackoffSeconds, true, transientRetry, generation)
		} else if (!sessionRejected && transientRetry < maxTransientRequestRetries) {
			log.warn "${cd.displayName}: transient TCC status failure; retrying in ${transientRequestRetrySeconds} seconds."
			getChildDevice(cd.deviceNetworkId).parse([[name:"TCCstatus", value:"retry request", descriptionText:"${cd.displayName} TCC transaction: retry request"]])
			scheduleRefreshRetry(cd, transientRequestRetrySeconds, data?.fromUnauth == true, transientRetry + 1, generation)
		} else {
			finishRefreshGeneration(childNumber, generation, "failed")
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

	Integer switchPosition = switchPos == null ? null : switchPos as Integer
	String thermostatMode = [0:'emergency heat', 1:'heat', 2:'off', 3:'cool', 4:'auto', 5:'auto'][switchPosition]
	getChildDevice(cd.deviceNetworkId).parse([[name:"temperature", value:curTemp.round(2), descriptionText:"${cd.displayName} Temperature was Set to $curTemp", unit: "°${location.temperatureScale}"]])
	if (thermostatMode != null) {
		getChildDevice(cd.deviceNetworkId).parse([[name:"thermostatMode", value:thermostatMode, descriptionText:"${cd.displayName} Mode was Set to $thermostatMode"]])
		cd.setLastRunningMode(thermostatMode) // lastRunningMode in the Child
	} else {
		log.warn "${cd.displayName}: unhandled SystemSwitchPosition ${switchPos}; retaining the previous thermostat mode."
	}

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


def getHumidifierStatus(cd, String cookies, refreshGeneration = null, statusRequestToken = null) {
	String[] dniParts = cd.deviceNetworkId.split("[-_]")
	String childNumber = dniParts[2]
	Long generation = refreshGeneration == null ? beginRefreshGeneration(childNumber) : (refreshGeneration as Long)
	if (!refreshGenerationIsCurrent(childNumber, generation)) return
	def honeywellDeviceId = state.childParamMap."${dniParts[2]}".honeywelldevice
	if (debugOutput)  log.debug "in get humid status enable humidity = ${state.childParamMap."${dniParts[2]}".enableHumidity}"
	if (state.childParamMap."${dniParts[2]}".haveHumidifier == 'No') return
	
	def params = [
	  uri: "${tccBaseUrl()}/portal/Device/CheckDataSession/${honeywellDeviceId}",
        headers: [
            'Accept': '*/*', // */ comment
            'DNT': '1',
            'dataType': 'json',
            'cache': 'false',
            'Accept-Encoding': 'plain',
            'Cache-Control': 'max-age=0',
            'Accept-Language': 'en-US,en,q=0.8',
            'Connection': 'keep-alive',
	            'Host': "${humidifierHost()}",
	            'Referer': "${tccBaseUrl()}/portal/",
            'X-Requested-With': 'XMLHttpRequest',
            'User-Agent': 'Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/28.0.1500.95 Safari/537.36',
	        'Cookie': cookies
        ],
		  timeout: 10
	    ]

	if (debugOutput) log.debug "sending gethumidStatus request for Device ID ${maskedHoneywellDeviceId(honeywellDeviceId)}"
	def pData = [dni:cd.deviceNetworkId, refreshGeneration:generation, requestToken:statusRequestToken]
	asynchttpGet("getHumidStatusHandler", params, pData)
}


def getHumidStatusHandler(resp, data) {
	def cdd = getChildDevice(data?.dni)
	if (!cdd) return
	Long generation = (data?.refreshGeneration ?: 0) as Long
	Long requestToken = (data?.requestToken ?: 0) as Long
	if (!refreshRequestIsCurrent(childKey(cdd), generation, requestToken)) {
		if (debugOutput) log.debug "Ignoring stale humidifier callback generation ${generation}, request ${requestToken} for ${cdd.displayName}"
		return
	}
	if(resp.getStatus() == 200 || resp.getStatus() == 207) {
		if (debugOutput) log.debug "GetHumidity Request was successful, $resp.status"
		def response = resp.getData().toString()
		getHumidifierDistrib (cdd, response)
	}
}

def getHumidifierDistrib (cd, resp) {
	String CancelLine = ""
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
	getChildDevice(cd.deviceNetworkId).parse([[name:"humidifierSetPoint", value:HumLevel as Integer, descriptionText:"${cd.displayName} Humidifier Setpoint was Set to $HumLevel", unit:"%"]])
	getChildDevice(cd.deviceNetworkId).parse([[name:"humidifierUpperLimit", value:HumMax as Integer, descriptionText:"${cd.displayName} Humidifier Upper Limit was Set to $HumMax", unit:"%"]])
	getChildDevice(cd.deviceNetworkId).parse([[name:"humidifierLowerLimit", value:HumMin as Integer, descriptionText:"${cd.displayName} Humidifier Lower Limit was Set to $HumMin", unit:"%"]])
	
	getChildDevice(cd.deviceNetworkId).parse([[name:"TCCstatus", value:"success", descriptionText:"${cd.displayName} TCC transaction: success"]])
}


/* ------------------------------------------------------------------

	Command delivery isolation

	Each child has three distinct command areas:
	  - state.deviceSetting[key]: live values still accumulating from UI commands
	  - state.commandState[key].inFlight: one immutable snapshot being delivered/retried
	  - state.commandState[key].failed: a bounded-out snapshot retained for a later user action

	A command ID guards every retry and completion. A response can settle only the exact
	snapshot that created it, and never clears values that arrived while it was in flight.

   ------------------------------------------------------------------ */

private void queueTccCommand(cd, Map values, Map acknowledgedEvents = [:]) {
	String key = childKey(cd)
	Integer token = withCommandStateLock(key) { Map commandState ->
		restoreFailedCommandLocked(key, commandState)
		Map pending = state.deviceSetting[key]
		values?.each { name, value -> pending[name] = value }
		Map pendingEvents = commandState["pendingEvents"] ?: [:]
		acknowledgedEvents?.each { name, eventData ->
			pendingEvents[name] = eventData instanceof Map ? new LinkedHashMap(eventData) : eventData
		}
		commandState["pendingEvents"] = pendingEvents
		commandState["sendToken"] = ((commandState["sendToken"] ?: 0) as Integer) + 1
		state.deviceSetting[key] = pending
		state.commandState[key] = commandState
		return commandState["sendToken"] as Integer
	}
	scheduleCommandAccumulation(cd, token, commandAccumulationMillis)
}

private void scheduleCommandAccumulation(cd, Integer token, Integer delayMillis) {
	runInMillis(delayMillis, "settingsAccumWait", [overwrite:false, data:[dni:cd.deviceNetworkId, token:token]])
}

private void scheduleCommandFollowup(cd, Integer token) {
	runIn(commandFollowupSeconds, "settingsAccumWait", [overwrite:false, data:[dni:cd.deviceNetworkId, token:token]])
}

void settingsAccumWait(Map data) {
	def cd = getChildDevice(data?.dni)
	if (!cd) return
	Map started = beginCommandSnapshot(cd, data?.token)
	if (started["status"] != "ready") return
	deliverCommandSnapshot(cd, started["commandId"])
}

void retryTccCommand(Map data) {
	def cd = getChildDevice(data?.dni)
	if (!cd) return
	deliverCommandSnapshot(cd, data?.commandId)
}

private Map beginCommandSnapshot(cd, token) {
	String key = childKey(cd)
	return withCommandStateLock(key) { Map commandState ->
		if (token != null && "${token}" != "${commandState['sendToken']}") return [status:"stale"]
		if (commandState["inFlight"] instanceof Map) return [status:"busy"]

		Map pending = state.deviceSetting[key]
		Map fields = new LinkedHashMap(pending.findAll { name, value -> value != null })
		if (!fields) return [status:"empty"]

		Long commandId = ((commandState["nextCommandId"] ?: 0) as Long) + 1L
		Map body = new LinkedHashMap(fields)
		body.DeviceID = state.childParamMap[key].honeywelldevice
		body.DisplayUnits = location.temperatureScale
		Map events = copyCommandEvents(commandState["pendingEvents"] as Map)
		Map snapshot = [id:commandId, commandValues:fields, body:body, events:events,
			authRetry:0, transientRetry:0, createdAt:now()]

		fields.each { name, value -> pending[name] = null }
		commandState["pendingEvents"] = [:]
		commandState["nextCommandId"] = commandId
		commandState["inFlight"] = snapshot
		state.deviceSetting[key] = pending
		state.commandState[key] = commandState
		return [status:"ready", commandId:commandId]
	}
}

private void deliverCommandSnapshot(cd, commandId) {
	String key = childKey(cd)
	Map snapshot = claimCommandAttempt(key, commandId)
	if (!snapshot) return

	getChildDevice(cd.deviceNetworkId).parse([[name:"TCCstatus", value:"begin", descriptionText:"${cd.displayName} TCC transaction: begin"]])
	Map session = getTccSession(cd)
	if (session.status != "ready") {
		Integer delay = (session.retryAfter ?: loginBackoffSeconds) as Integer
		if (session.status in ["deferred", "backoff"]) {
				if (debugOutput) log.debug "${cd.displayName}: TCC login is deferred; retrying command ${snapshot['id']} in ${delay} seconds"
				releaseCommandAttempt(key, snapshot["id"])
				scheduleCommandRetry(cd, snapshot["id"], delay)
			getChildDevice(cd.deviceNetworkId).parse([[name:"TCCstatus", value:"retry login", descriptionText:"${cd.displayName} TCC transaction: retry login"]])
			} else if (reserveCommandRetry(key, snapshot["id"], "authRetry", maxCommandAuthenticationRetries)) {
				log.warn "${cd.displayName}: TCC login unavailable; retrying command ${snapshot['id']} in ${delay} seconds."
				scheduleCommandRetry(cd, snapshot["id"], delay)
			getChildDevice(cd.deviceNetworkId).parse([[name:"TCCstatus", value:"retry login", descriptionText:"${cd.displayName} TCC transaction: retry login"]])
		} else {
				log.error "TCC command ${snapshot['id']} failed for ${cd.displayName}: login unavailable after retry; command retained."
				finishCommandFailure(cd, snapshot["id"])
		}
		return
	}

	Map body = new LinkedHashMap(snapshot["body"] as Map)
	def honeywellDeviceId = body.DeviceID
	if (debugOutput) {
		log.debug "Honeywell TCC 'setStatus' command ${snapshot['id']}"
		log.debug "setStatus body: ${sanitizedTccCommandBody(body)}"
	}
	Map params = [
		uri: "${tccBaseUrl()}/portal/Device/SubmitControlScreenChanges",
		headers: [
			'Accept': 'application/json, text/javascript, */*; q=0.01', // */ comment
			'DNT': '1',
			'Accept-Encoding': 'gzip,deflate,sdch',
			'Cache-Control': 'max-age=0',
			'Accept-Language': 'en-US,en,q=0.8',
			'Connection': 'keep-alive',
			'Host': "${tccHost()}",
			'Referer': "${tccBaseUrl()}/portal/Device/Control/${honeywellDeviceId}",
			'X-Requested-With': 'XMLHttpRequest',
			'User-Agent': 'Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/28.0.1500.95 Safari/537.36',
			'Cookie': session.cookies
		],
		body: body,
		timeout: 10
	]

	try {
		httpPost(params) { resp ->
			if (debugOutput) log.debug "Command ${snapshot['id']} request was successful, ${resp.status}"
		}
		finishCommandSuccess(cd, snapshot["id"])
	} catch (e) {
		Integer httpStatus = tccHttpStatus(e)
		Boolean authRejected = httpStatus in [302, 401, 403]
		if (authRejected) invalidateTccSession(session.sid)

		if (authRejected && reserveCommandRetry(key, snapshot["id"], "authRetry", maxCommandAuthenticationRetries)) {
			if (debugOutput) log.debug "TCC command ${snapshot['id']} authentication rejected for ${cd.displayName}: ${sanitizedTccFailure(e, honeywellDeviceId)}"
			scheduleCommandRetry(cd, snapshot["id"], loginBackoffSeconds)
			getChildDevice(cd.deviceNetworkId).parse([[name:"TCCstatus", value:"retry login", descriptionText:"${cd.displayName} TCC transaction: retry login"]])
		} else if (!authRejected && reserveCommandRetry(key, snapshot["id"], "transientRetry", maxTransientRequestRetries)) {
			if (debugOutput) log.debug "TCC command ${snapshot['id']} transient failure for ${cd.displayName}: ${sanitizedTccFailure(e, honeywellDeviceId)}"
			log.warn "${cd.displayName}: transient TCC command ${snapshot['id']} failure; retrying in ${transientRequestRetrySeconds} seconds."
			scheduleCommandRetry(cd, snapshot["id"], transientRequestRetrySeconds)
			getChildDevice(cd.deviceNetworkId).parse([[name:"TCCstatus", value:"retry request", descriptionText:"${cd.displayName} TCC transaction: retry request"]])
		} else {
			log.error "TCC command ${snapshot['id']} failed for ${cd.displayName}: ${sanitizedTccFailure(e, honeywellDeviceId)}; command retained."
			finishCommandFailure(cd, snapshot["id"])
		}
	}
}

private void scheduleCommandRetry(cd, commandId, Integer delaySeconds) {
	runIn(delaySeconds, "retryTccCommand", [overwrite:false, data:[dni:cd.deviceNetworkId, commandId:commandId]])
}



/* ------------------------------------------------------------------

	TCC session retention

	Purpose: Keep one Parent-owned session for all thermostat children. Cookie
	         count remains an empirical confidence check because Honeywell does
	         not reliably return a known cookie set. Valid thermostat data is the
	         final confirmation that a candidate session works.

   ------------------------------------------------------------------ */

private String tccBaseUrl() {
	String baseUrl = "https://${tccSite()}"
	return baseUrl.replaceAll('/+$', '')
}

private String tccHost() {
	return tccBaseUrl().replaceFirst(/^https?:\/\//, '').tokenize('/')[0]
}

private String humidifierHost() {
	String host = 'rs.alarmnet.com'
	return host
}

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
		uri: "${tccBaseUrl()}/portal/",
		headers: [
			'Content-Type': 'application/x-www-form-urlencoded',
			'Accept': 'application/json, text/javascript, */*; q=0.01', // */
			'Accept-Encoding': 'sdch',
			'Host': "${tccHost()}",
			'DNT': '1',
			'Origin': "${tccBaseUrl()}/portal/",
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
		log.warn "${cd.displayName}: TCC login failed: ${sanitizedTccFailure(e)}"
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
// params passed in from each Child: DNI, honeywelldevice, haveHumidifier, enableOutdoorTemps, enableHumidity, setPermHold, pollIntervals
void setParams(cDNI, dev, hH, eOT, eH, sPH, pI) {
	String childNumber = childKeyFromDni(cDNI)
	ensureChildMaps(childNumber)
	state.childParamMap[childNumber].childDNI 			= cDNI
	state.childParamMap[childNumber].honeywelldevice 		= dev
	state.childParamMap[childNumber].haveHumidifier 		= hH
	state.childParamMap[childNumber].enableOutdoorTemps 	= eOT
	state.childParamMap[childNumber].enableHumidity		= eH
	state.childParamMap[childNumber].setPermHold 		= sPH
	state.childParamMap[childNumber].pollIntervals 		= pI
	state.childParamMap[childNumber].remove("emergencyHeatCommand")

	if (debugOutput) log.debug "ChildParams: $cDNI, ${maskedHoneywellDeviceId(dev)}, $hH, $eOT, $eH, $sPH, $pI"
}

// Compatibility for a v2.0.9 or older Child during a staggered/manual update. The former
// Zero/Four value is intentionally ignored because Honeywell defines Emergency Heat as zero.
void setParams(cDNI, dev, hH, eOT, eH, sPH, pI, ignoredEmergencyHeatCommand) {
	setParams(cDNI, dev, hH, eOT, eH, sPH, pI)
}

private Semaphore commandStateLock(String childNumber) {
	String lockKey = "${device.id}:${childNumber}"
	tccCommandStateLocks.putIfAbsent(lockKey, new Semaphore(1))
	return tccCommandStateLocks.get(lockKey)
}

private def withCommandStateLock(String childNumber, Closure work) {
	Semaphore lock = commandStateLock(childNumber)
	lock.acquireUninterruptibly()
	try {
		ensureChildMaps(childNumber)
		return work.call(state.commandState[childNumber] as Map)
	} finally {
		lock.release()
	}
}

private Map copyCommandEvents(Map source) {
	Map copy = [:]
	(source ?: [:]).each { name, eventData ->
		copy[name] = eventData instanceof Map ? new LinkedHashMap(eventData) : eventData
	}
	return copy
}

private String activeCommandAttemptKey(String childNumber, commandId) {
	return "${device.id}:${childNumber}:${commandId}"
}

private void restoreFailedCommandLocked(String key, Map commandState) {
	Map failed = commandState["failed"] instanceof Map ? commandState["failed"] as Map : null
	if (!failed) return
	Map pending = state.deviceSetting[key]
	(failed["commandValues"] ?: [:]).each { name, value ->
		if (pending[name] == null) pending[name] = value
	}
	Map pendingEvents = commandState["pendingEvents"] ?: [:]
	(failed["events"] ?: [:]).each { name, eventData ->
		if (!pendingEvents.containsKey(name))
			pendingEvents[name] = eventData instanceof Map ? new LinkedHashMap(eventData) : eventData
	}
	commandState["pendingEvents"] = pendingEvents
	commandState["failed"] = null
	state.deviceSetting[key] = pending
}

private Map claimCommandAttempt(String key, commandId) {
	return withCommandStateLock(key) { Map commandState ->
		Map inFlight = commandState["inFlight"] instanceof Map ? commandState["inFlight"] as Map : null
		if (!inFlight || "${inFlight['id']}" != "${commandId}") return null
		String activeKey = activeCommandAttemptKey(key, commandId)
		if (tccActiveCommandAttempts.putIfAbsent(activeKey, true) != null) return null
		Map claimed = new LinkedHashMap(inFlight)
		claimed["body"] = new LinkedHashMap(inFlight["body"] as Map)
		claimed["commandValues"] = new LinkedHashMap(inFlight["commandValues"] as Map)
		claimed["events"] = copyCommandEvents(inFlight["events"] as Map)
		return claimed
	}
}

private void releaseCommandAttempt(String key, commandId) {
	tccActiveCommandAttempts.remove(activeCommandAttemptKey(key, commandId))
}

private Boolean reserveCommandRetry(String key, commandId, String counterName, Integer maximum) {
	return withCommandStateLock(key) { Map commandState ->
		Map inFlight = commandState["inFlight"] instanceof Map ? commandState["inFlight"] as Map : null
		if (!inFlight || "${inFlight['id']}" != "${commandId}") return false
		Integer used = (inFlight[counterName] ?: 0) as Integer
		if (used >= maximum) return false
		inFlight[counterName] = used + 1
		commandState["inFlight"] = inFlight
		state.commandState[key] = commandState
		tccActiveCommandAttempts.remove(activeCommandAttemptKey(key, commandId))
		return true
	}
}

private void finishCommandSuccess(cd, commandId) {
	String key = childKey(cd)
	tccActiveCommandAttempts.remove(activeCommandAttemptKey(key, commandId))
	Map result = withCommandStateLock(key) { Map commandState ->
		Map inFlight = commandState["inFlight"] instanceof Map ? commandState["inFlight"] as Map : null
		if (!inFlight || "${inFlight['id']}" != "${commandId}") return [settled:false]
		Map events = copyCommandEvents(inFlight["events"] as Map)
		commandState["inFlight"] = null
		state.commandState[key] = commandState
		Boolean hasPending = (state.deviceSetting[key] as Map).values().any { value -> value != null }
		return [settled:true, events:events, hasPending:hasPending, token:(commandState["sendToken"] ?: 0) as Integer]
	}
	if (result["settled"] != true) return
	emitAcknowledgedCommandEvents(cd, result["events"] as Map)
	getChildDevice(cd.deviceNetworkId).parse([[name:"TCCstatus", value:"success", descriptionText:"${cd.displayName} TCC transaction: success"]])
	if (result["hasPending"] == true) {
		if (debugOutput) log.debug "Command ${commandId} completed with newer pending values; scheduling the next command in ${commandFollowupSeconds} second."
		scheduleCommandFollowup(cd, result["token"] as Integer)
	}
}

private void finishCommandFailure(cd, commandId) {
	String key = childKey(cd)
	tccActiveCommandAttempts.remove(activeCommandAttemptKey(key, commandId))
	Map result = withCommandStateLock(key) { Map commandState ->
		Map inFlight = commandState["inFlight"] instanceof Map ? commandState["inFlight"] as Map : null
		if (!inFlight || "${inFlight['id']}" != "${commandId}") return [settled:false]
		Map pending = state.deviceSetting[key]
		Map pendingEvents = commandState["pendingEvents"] ?: [:]
		Map previous = commandState["failed"] instanceof Map ? commandState["failed"] as Map : [:]
		Map retainedFields = [:]
		(previous["commandValues"] ?: [:]).each { name, value ->
			if (pending[name] == null) retainedFields[name] = value
		}
		(inFlight["commandValues"] ?: [:]).each { name, value ->
			if (pending[name] == null) retainedFields[name] = value
		}
		Map retainedEvents = [:]
		(previous["events"] ?: [:]).each { name, eventData ->
			if (!pendingEvents.containsKey(name))
				retainedEvents[name] = eventData instanceof Map ? new LinkedHashMap(eventData) : eventData
		}
		(inFlight["events"] ?: [:]).each { name, eventData ->
			if (!pendingEvents.containsKey(name))
				retainedEvents[name] = eventData instanceof Map ? new LinkedHashMap(eventData) : eventData
		}
		commandState["failed"] = (retainedFields || retainedEvents) ? [commandValues:retainedFields, events:retainedEvents, failedAt:now()] : null
		commandState["inFlight"] = null
		state.commandState[key] = commandState
		Boolean hasPending = (state.deviceSetting[key] as Map).values().any { value -> value != null }
		return [settled:true, hasPending:hasPending, token:(commandState["sendToken"] ?: 0) as Integer]
	}
	if (result["settled"] != true) return
	getChildDevice(cd.deviceNetworkId).parse([[name:"TCCstatus", value:"failed", descriptionText:"${cd.displayName} TCC transaction: failed"]])
	if (result["hasPending"] == true) {
		if (debugOutput) log.debug "Command ${commandId} failed with newer pending values; scheduling the next command in ${commandFollowupSeconds} second."
		scheduleCommandFollowup(cd, result["token"] as Integer)
	}
}

private void emitAcknowledgedCommandEvents(cd, Map events) {
	(events ?: [:]).each { String name, Map eventData ->
		Map event = [name:name, value:eventData["value"]]
		if (eventData["descriptionText"]) event["descriptionText"] = eventData["descriptionText"]
		if (eventData["unit"]) event["unit"] = eventData["unit"]
		getChildDevice(cd.deviceNetworkId).parse([event])
		if (eventData["setLastRunningMode"] == true) cd.setLastRunningMode(eventData["value"])
	}
}

private Float pendingSetpointBase(cd, String fieldName, String attributeName) {
	String key = childKey(cd)
	def value = withCommandStateLock(key) { Map commandState ->
		def pending = state.deviceSetting[key][fieldName]
		if (pending != null) return pending
		Map inFlight = commandState["inFlight"] instanceof Map ? commandState["inFlight"] as Map : null
		if (inFlight?.get("commandValues")?.get(fieldName) != null) return inFlight["commandValues"][fieldName]
		Map failed = commandState["failed"] instanceof Map ? commandState["failed"] as Map : null
		return failed?.get("commandValues")?.get(fieldName)
	}
	if (value == null) value = cd.currentValue(attributeName)
	Float numeric = toFloatOrNull(value)
	if (numeric == null) log.warn "${cd.displayName}: current ${attributeName} is unknown. Refresh the thermostat and try again."
	return numeric
}

private Integer tccHttpStatus(e) {
	try {
		if (e?.statusCode != null) return e.statusCode as Integer
	} catch (ignored) { }
	try {
		if (e?.response?.status != null) return e.response.status as Integer
	} catch (ignored) { }
	String message = e?.message?.toString() ?: ""
	def matcher = message =~ /(?i)(?:status code:\s*|HTTP\s*)(\d{3})/
	return matcher.find() ? matcher.group(1).toInteger() : null
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
	if (state.commandState == null) state.commandState = [:]
	if (state.refreshState == null) state.refreshState = [:]
}

private void ensureChildMaps(String childNumber) {
	ensureStateMaps()
	if (state.childParamMap[childNumber] == null) {
		state.childParamMap[childNumber] = [childDNI: null, honeywelldevice: null, haveHumidifier: null, enableOutdoorTemps: null, enableHumidity: null, setPermHold: null, pollIntervals: null]
	}
	if (state.deviceSetting[childNumber] == null) {
		state.deviceSetting[childNumber] = [SystemSwitch: null, StatusHeat: null, StatusCool: null, HeatSetpoint: null, CoolSetpoint: null, HeatNextPeriod: null, CoolNextPeriod: null, FanMode: null, TemporaryHoldUntilTime: null, VacationHold: null]
	}
	if (state.childStatus[childNumber] == null) {
		state.childStatus[childNumber] = [isEmergencyHeatAllowed: null, heatLowerSetptLimit: null, heatUpperSetptLimit: null, coolLowerSetptLimit: null, coolUpperSetptLimit: null]
	}
	if (state.refreshState[childNumber] == null) {
		state.refreshState[childNumber] = [nextGeneration:0L, activeGeneration:null, retryToken:0L, requestToken:0L, phase:"idle"]
	} else if (state.refreshState[childNumber]["requestToken"] == null) {
		Map refreshState = state.refreshState[childNumber] as Map
		refreshState["requestToken"] = 0L
		state.refreshState[childNumber] = refreshState
	}
	if (state.commandState[childNumber] == null) {
		state.commandState[childNumber] = [schemaVersion:commandStateSchemaVersion, nextCommandId:0L, sendToken:0, pendingEvents:[:], inFlight:null, failed:null]
	} else {
		Map commandState = state.commandState[childNumber]
		if (((commandState["schemaVersion"] ?: 0) as Integer) < commandStateSchemaVersion) {
			Map orphaned = commandState["inFlight"] instanceof Map ? commandState["inFlight"] as Map : null
			if (orphaned) {
				Map pending = state.deviceSetting[childNumber]
				Map orphanedValues = (orphaned.get("commandValues") ?: orphaned.get("fields") ?: [:]) as Map
				orphanedValues.each { name, value ->
					if (pending[name] == null) pending[name] = value
				}
				Map pendingEvents = commandState["pendingEvents"] instanceof Map ? commandState["pendingEvents"] as Map : [:]
				(orphaned["events"] ?: [:]).each { name, eventData ->
					if (!pendingEvents.containsKey(name))
						pendingEvents[name] = eventData instanceof Map ? new LinkedHashMap(eventData) : eventData
				}
				state.deviceSetting[childNumber] = pending
				commandState["pendingEvents"] = pendingEvents
				commandState["inFlight"] = null
				tccActiveCommandAttempts.remove(activeCommandAttemptKey(childNumber, orphaned["id"]))
				if (debugOutput) log.debug "Recovered orphaned command ${orphaned['id']} for child ${childNumber} after command-state upgrade"
			}
			commandState["schemaVersion"] = commandStateSchemaVersion
		}
		if (commandState["nextCommandId"] == null) commandState["nextCommandId"] = 0L
		if (commandState["sendToken"] == null) commandState["sendToken"] = 0
		if (!(commandState["pendingEvents"] instanceof Map)) commandState["pendingEvents"] = [:]
		state.commandState[childNumber] = commandState
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

private Long beginRefreshGeneration(String childNumber) {
	ensureChildMaps(childNumber)
	Map refreshState = state.refreshState[childNumber] as Map
	Long generation = ((refreshState["nextGeneration"] ?: 0) as Long) + 1L
	refreshState["nextGeneration"] = generation
	refreshState["activeGeneration"] = generation
	refreshState["phase"] = "preparing"
	state.refreshState[childNumber] = refreshState
	return generation
}

private Boolean refreshGenerationIsCurrent(String childNumber, generation) {
	if (generation == null) return false
	ensureChildMaps(childNumber)
	def activeGeneration = state.refreshState[childNumber]["activeGeneration"]
	return activeGeneration != null && (activeGeneration as Long) == (generation as Long)
}

private Boolean refreshRequestIsCurrent(String childNumber, generation, requestToken) {
	if (!refreshGenerationIsCurrent(childNumber, generation) || requestToken == null) return false
	def activeRequestToken = state.refreshState[childNumber]["requestToken"]
	return activeRequestToken != null && (activeRequestToken as Long) == (requestToken as Long)
}

private void finishRefreshGeneration(String childNumber, generation, String phase) {
	if (!refreshGenerationIsCurrent(childNumber, generation)) return
	Map refreshState = state.refreshState[childNumber] as Map
	refreshState["phase"] = phase
	state.refreshState[childNumber] = refreshState
}

private Long beginStatusRequest(String childNumber, generation) {
	if (!refreshGenerationIsCurrent(childNumber, generation)) return null
	Map refreshState = state.refreshState[childNumber] as Map
	Long requestToken = ((refreshState["requestToken"] ?: 0) as Long) + 1L
	refreshState["requestToken"] = requestToken
	refreshState["phase"] = "requesting"
	state.refreshState[childNumber] = refreshState
	return requestToken
}

private Boolean claimStatusCallback(String childNumber, generation, requestToken) {
	if (!refreshGenerationIsCurrent(childNumber, generation)) return false
	Map refreshState = state.refreshState[childNumber] as Map
	if (refreshState["phase"] != "requesting") return false
	if (refreshState["requestToken"] == null || (refreshState["requestToken"] as Long) != (requestToken as Long)) return false
	refreshState["phase"] = "processing"
	state.refreshState[childNumber] = refreshState
	return true
}

private Long reserveRefreshRetry(String childNumber, generation) {
	if (!refreshGenerationIsCurrent(childNumber, generation)) return null
	Map refreshState = state.refreshState[childNumber] as Map
	Long retryToken = ((refreshState["retryToken"] ?: 0) as Long) + 1L
	refreshState["retryToken"] = retryToken
	refreshState["phase"] = "retry_wait"
	state.refreshState[childNumber] = refreshState
	return retryToken
}

private Boolean claimRefreshRetry(String childNumber, generation, retryToken) {
	if (!refreshGenerationIsCurrent(childNumber, generation)) return false
	Map refreshState = state.refreshState[childNumber] as Map
	if (refreshState["phase"] != "retry_wait") return false
	if (refreshState["retryToken"] == null || (refreshState["retryToken"] as Long) != (retryToken as Long)) return false
	refreshState["phase"] = "preparing"
	state.refreshState[childNumber] = refreshState
	return true
}

private void scheduleRefreshRetry(cd, Integer delay, Boolean fromUnauth, Integer transientRetry = 0, refreshGeneration = null) {
	String childNumber = childKey(cd)
	Long generation = refreshGeneration == null ? beginRefreshGeneration(childNumber) : (refreshGeneration as Long)
	Long retryToken = reserveRefreshRetry(childNumber, generation)
	if (retryToken == null) {
		if (debugOutput) log.debug "Not scheduling stale refresh retry generation ${generation} for ${cd.displayName}"
		return
	}
	runIn(delay, "refreshFromRunin", [overwrite:false, data:[dni:cd.deviceNetworkId, fromUnauth:fromUnauth, transientRetry:transientRetry, refreshGeneration:generation, retryToken:retryToken]])
}

def refreshFromRunin(data) {
	def cdd = data?.dni ?: data?.cd
	if (cdd instanceof List) cdd = cdd[0]
	def cd = getChildDevice(cdd)
	if (!cd) return
	String childNumber = childKey(cd)
	Long generation = (data?.refreshGeneration ?: 0) as Long
	Long retryToken = (data?.retryToken ?: 0) as Long
	if (!claimRefreshRetry(childNumber, generation, retryToken)) {
		if (debugOutput) log.debug "Ignoring stale or duplicate refresh retry generation ${generation}, token ${retryToken} for ${cd.displayName}"
		return
	}
	if (debugOutput) log.debug "Calling refresh after TCC failure for ${cd.displayName}; generation ${generation}, retry token ${retryToken}"
	componentDoRefresh(cd, data?.fromUnauth == true, (data?.transientRetry ?: 0) as Integer, generation)
}


private logInfo (msg) {
	if (settings?.descTextEnable || settings?.descTextEnable == null) log.info "$msg"
}


def getThisCopyright(){"&copy; 2022 C Steele "}
