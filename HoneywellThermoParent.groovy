/**
 * IMPORT URL: 
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
 * csteele: v2.0.2   put childParamMap into "state"
 * csteele: v2.0.1   Put setLastRunningMode into Child
 * csteele: v2.0.0   Initial Commit
 *
 * Forked from:
 * csteele: v1.3.20  Added "emergency/auxiliary" heat.
 *				added fanOperatingState Attribute.
**/

import groovy.transform.Field

 public static String version()	{  return "v2.0.2"  }
 public static String tccSite() 	{  return "mytotalconnectcomfort.com"  }
 public static String type() 		{  return "Thermostat"  }

@Field static Map<String, Map> modeMap = [auto:5, cool:3, heat:1, off:2, 'emergency heat':4]
@Field static Map<String, Map> fanMap = [auto:0, on:1, circulate:2, followSchedule:3] 


metadata {
	definition (name: "Honeywell Thermo Parent", namespace: "csteele", author: "Eric Thomas, lg kahn, C Steele") {
		command "addThermostat"

/* -= Attribute List =-
 	[thermostatFanMode, humidifierLowerLimit, supportedThermostatFanModes, supportedThermostatModes, followSchedule, humidifierSetPoint, thermostatSetpoint, 
 	coolingSetpoint, humidifierUpperLimit, outdoorHumidity, temperature, outdoorTemperature, humidifierStatus, lastUpdate, thermostatMode, fanOperatingState, 
 	thermostatOperatingState, heatingSetpoint, humidity, temperature]

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



void logsOff(){
	log.warn "debug logging disabled..."
	device.updateSetting("debugOutput",[value:"false",type:"bool"])
}

void updated(){
	log.info "updated..."
	log.warn "debug logging is: ${debugOutput == true}"
	log.warn "description logging is: ${descTextEnable == true}"
	log.info "Count of Children: $numChild"
	if (debugOutput) runIn(1800,logsOff)
}

// parse events into attributes
void parse(String description) {
	//parse nothing, ever, probably...
}


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
	
	if (descTextEnable) log.info "ChildParams: $cDNI, $dev, $hH, $eOT, $eH, $sPH, $pI, -${state.childParamMap."${dniParts[1]}".honeywelldevice}-"
}



void installed() { initialize() }
void initialize(){
	def cd = getChildDevices()?.findAll { it.deviceNetworkId > "${device.id}-${type()}"}
//	cd.each { deleteChildDevice(it.deviceNetworkId) }
	cd = getChildDevice("${thisId}-${type}_0") // gets list of children
	if (!cd) {
		state.childParamMap = [:]
		cd = createChild("0")
		state.nextChild = "1"
	}
}

void addThermostat() {
	createChild(state.nextChild)
	state.nextChild++
	log.debug "addThermostat: $state.nextChild"
}
def createChild(String numChild) {
	String thisId = device.id
	log.debug "createChild: ${thisId}-${type()}_$numChild, $cd"
	state.childParamMap << [	"$numChild":		[childDNI: null, honeywelldevice: null, haveHumidifier: null, enableOutdoorTemps: null, enableHumidity: null, setPermHold: null, pollIntervals: null]]
	def cd = addChildDevice("csteele", "Honeywell WiFi ${type()} Component", "${thisId}-${type()}_$numChild", [name: "${device.displayName} ${type()}", isComponent: true])
	return cd 
}

void componentRefresh(cd)
{
	if (debugOutput) log.debug "Refresh request from device ${cd.displayName}. This will refresh all component devices."
	getStatus(cd)
}



//child device methods
void componentDoRefresh(cd, Boolean fromUnauth = false) {
	if (debugOutput) log.debug "received Refresh request from ${cd.displayName} to Honeywell TCC 'refresh', units: = °${location.temperatureScale}, fromUnauth = $fromUnauth"
	login(fromUnauth)
	getHumidifierStatus(cd, fromUnauth)
	getStatus(cd)
}

// Thermostat mode section
void componentOff(cd) {
	if (debugOutput) log.info "received off request from ${cd.displayName}"
	setThermostatMode('off',cd)
}

void componentAuto(cd) {
	if (debugOutput) log.info "received Auto request from ${cd.displayName}"
	setThermostatMode('auto',cd)
}

void componentCool(cd) {
	if (debugOutput) log.info "received Cool request from ${cd.displayName}"
	setThermostatMode('cool',cd)
}

void componentHeat(cd) {
	if (debugOutput) log.info "received Heat request from ${cd.displayName}"
	setThermostatMode('heat',cd)
}

void componentEmergencyHeat(cd) {
	if (debugOutput) log.info "received Emergency Heat request from ${cd.displayName}"
	if (isEmergencyHeatAllowed) {
		if (debugOutput) log.debug "Set Emergency/Auxiliary Heat On"
		setThermostatMode('emergency heat', cd)
	}
}

void setThermostatMode(mode, cd) {
	if (debugOutput) log.debug "setThermostatMode: $mode"
	deviceDataInit(null) 	 // reset all params, then set individually

	device.data.SystemSwitch = modeMap.find{ mode == it.key }?.value
	setStatus(cd)
	
	if(device.data.SetStatus==1)
	{
		getChildDevice(cd.deviceNetworkId).parse([[name:"thermostatMode", value:mode, descriptionText:"${cd.displayName} was Set to $mode"]])
	    	cd.setLastRunningMode(mode) 
	}
}

// end of section

// Heat/Cool Set/Up/Down section
void componentSetThermostatFanMode(cd, val) {
	if (debugOutput) log.info "received Fan Mode request from ${cd.displayName}"
	getChildDevice(cd.deviceNetworkId).parse([[name:"fanmode", value:val, descriptionText:"${cd.displayName} Fan is ${val}"]])
}

void componentSetThermostatMode(cd, val) {
	if (debugOutput) log.info "received Thermostat Mode request from ${cd.displayName}"
	getChildDevice(cd.deviceNetworkId).parse([[name:"mode", value:val, descriptionText:"${cd.displayName} Thermostat is ${val}"]])
}

void componentSetCoolingSetpoint(cd, val) {
	if (debugOutput) log.info "received Cooling Setpoint request from ${cd.displayName}"
	deviceDataInit(state.childParamMap."${dniParts[1]}".setPermHold) 	 // reset all params, then set individually
	setStatus(cd)
	getChildDevice(cd.deviceNetworkId).parse([[name:"coolingSetpoint", value:val, descriptionText:"${cd.displayName} Cooling Setpoint is ${val}", unit:"°"]])
}

void componentSetHeatingSetpoint(cd, val) {
	if (debugOutput) log.info "received Heating Setpoint request from ${cd.displayName}"
	deviceDataInit(state.childParamMap."${dniParts[1]}".setPermHold) 	 // reset all params, then set individually
	setStatus(cd)
	getChildDevice(cd.deviceNetworkId).parse([[name:"heatingSetpoint", value:val, descriptionText:"${cd.displayName} Heating Setpoint is ${val}", unit:"°"]])
}

void componentHeatLevelDown(cd) {
	if (location.temperatureScale == "F")  {
		val = cd.currentValue("heatingSetpoint") - 1
	} else {
		val = cd.currentValue("heatingSetpoint") - 0.5
	}
	componentSetHeatingSetpoint(cd, val)
}

void componentHeatLevelUp(cd) {
	if (location.temperatureScale == "F")  {
		val = cd.currentValue("heatingSetpoint") + 1
	} else {
		val = cd.currentValue("heatingSetpoint") + 0.5
	}
	componentSetHeatingSetpoint(cd, val)
}

void componentCoolLevelDown(cd) {
	if (location.temperatureScale == "F")  {
		val = cd.currentValue("coolingSetpoint") - 1
	} else { 
		val = cd.currentValue("coolingSetpoint") - 0.5
	}
	componentSetCoolingSetpoint(cd, val)
}

void componentCoolLevelUp(cd) {
	if (location.temperatureScale == "F")  {
		val = cd.currentValue("coolingSetpoint") + 1
	} else { 
		val = cd.currentValue("coolingSetpoint") + 0.5
	}
	componentSetCoolingSetpoint(cd, val)
}
// end of section


// Fan mode section
void componentFanAuto(cd) {
	if (debugOutput) log.info "received FanAuto request from ${cd.displayName}"
	setThermostatFanMode('auto',cd)
}

void componentFanCirculate(cd) {
	if (debugOutput) log.info "received Circulate request from ${cd.displayName}"
	setThermostatFanMode('circulate',cd)
}

void componentFanOn(cd) {
	if (debugOutput) log.info "received FanOn request from ${cd.displayName}"
	setThermostatFanMode('on',cd)
}

def setThermostatFanMode(mode, cd) { 
	if (debugOutput) log.debug "setThermostatFanMode: $mode"
	deviceDataInit(null)  	 // reset all params, then set individually
	def fanMode = null
	
	device.data.FanMode = fanMap.find{ mode == it.key }?.value
	setStatus(cd)
	
	if(device.data.SetStatus==1)
	{
	//    sendEvent(name: 'thermostatFanMode', value: mode)   
		getChildDevice(cd.deviceNetworkId).parse([[name:"thermostatFanMode", value:mode, descriptionText:"${cd.displayName} was Set to $mode"]])
	}
}
// end of section


void componentSetFollowSchedule(cd) {
	deviceDataInit(0) 	 // reset all params, then set individually
	if (debugOutput) log.info "received Set Follow Schedule request from ${cd.displayName}"
	setStatus(cd)
	
	if(device.data.SetStatus==1)
	{
		getChildDevice(cd.deviceNetworkId).parse([[name:"mode", value:"off", descriptionText:"${cd.displayName} was Set to Follow Schedule"]])
	}
}



List<String> configure() {
	log.warn "configure..."
	runIn(1800,logsOff)
}




def getStatus(cd) {
	String[] dniParts = cd.deviceNetworkId.split("_")
	if (debugOutput) log.debug "enable outside temps = ${state.childParamMap."${dniParts[1]}".enableOutdoorTemps}"
	def today = new Date()
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

def getStatusHandler(resp, data) {
	try {
		def cdd = data["cd"]
		def cd = getChildDevice(cdd.deviceNetworkId)
		String[] dniParts = cd.deviceNetworkId.split("_")
		if(resp.getStatus() == 200 || resp.getStatus() == 207) {
			def setStatusResult = parseJson(resp.data)
		
			if (debugOutput) { 
			    log.debug "Request was successful, $resp.status"
			    log.debug "data: $setStatusResult, rdata: $data"
			    log.debug "ld: $setStatusResult.latestData.uiData"
			    log.debug "ld: $setStatusResult.latestData.fanData"
			}
		
			def curTemp = setStatusResult.latestData.uiData.DispTemperature
			def switchPos = setStatusResult.latestData.uiData.SystemSwitchPosition
			def coolSetPoint = setStatusResult.latestData.uiData.CoolSetpoint
			def heatSetPoint = setStatusResult.latestData.uiData.HeatSetpoint
			def statusCool = setStatusResult.latestData.uiData.StatusCool
			def statusHeat = setStatusResult.latestData.uiData.StatusHeat
			def Boolean hasIndoorHumid= setStatusResult.latestData.uiData.IndoorHumiditySensorAvailable
			def curHumidity = setStatusResult.latestData.uiData.IndoorHumidity
			def Boolean hasOutdoorHumid = setStatusResult.latestData.uiData.OutdoorHumidityAvailable
			def Boolean hasOutdoorTemp = setStatusResult.latestData.uiData.OutdoorTemperatureAvailable
			def Boolean isScheduleCapable = setStatusResult.latestData.uiData.ScheduleCapable
			def curOutdoorHumidity = setStatusResult.latestData.uiData.OutdoorHumidity
			def curOutdoorTemp = setStatusResult.latestData.uiData.OutdoorTemperature
			// EquipmentOutputStatus = 0 off 1 heating 2 cooling
			def equipmentStatus = setStatusResult.latestData.uiData.EquipmentOutputStatus	
			def holdTime = setStatusResult.latestData.uiData.TemporaryHoldUntilTime
			def vacationHoldMode = setStatusResult.latestData.uiData.IsInVacationHoldMode
			def vacationHold = setStatusResult.latestData.uiData.VacationHold
			def Boolean isEmergencyHeatAllowed = setStatusResult.latestData.uiData.SwitchEmergencyHeatAllowed
			
			def fanMode = setStatusResult.latestData.fanData.fanMode
			def fanIsRunning = setStatusResult.latestData.fanData.fanIsRunning
		
			if (debugOutput) {
				log.debug "got holdTime = $holdTime"
				log.debug "got Vacation Hold = $vacationHoldMode"
				log.debug "got scheduleCapable = $isScheduleCapable"
				log.debug "got Emergency Heat = $isEmergencyHeatAllowed"
			}
			
			if (holdTime != 0) {
				if (debugOutput) log.debug "sending temporary hold"
				getChildDevice(cd.deviceNetworkId).parse([[name:"followSchedule", value:"TemporaryHold", descriptionText:"${cd.displayName} was Set to Temporary Hold"]])
			}
		
			if (vacationHoldMode == true) {
				if (debugOutput) log.debug "sending vacation hold"
				getChildDevice(cd.deviceNetworkId).parse([[name:"followSchedule", value:"VacationHold", descriptionText:"${cd.displayName} was Set to Vacation Hold"]])
			}
		
			if (vacationHoldMode == false && holdTime == 0 && isScheduleCapable == true ) {
				if (debugOutput) log.debug "Sending following schedule"
				getChildDevice(cd.deviceNetworkId).parse([[name:"followSchedule", value:"FollowingSchedule", descriptionText:"${cd.displayName} was Set to Following Schedule"]])
			}
		
			if (hasIndoorHumid == false) { curHumidity = 0 }
		
			// set fan and operating state
			def fanState = "idle"
		
			if (fanIsRunning) {
				fanState = "on";
			} 
		
			def operatingState = [ 0: 'idle', 1: 'heating', 2: 'cooling' ][equipmentStatus] ?: 'idle'
		
			if ((state.childParamMap."${dniParts[1]}".haveHumidifier != 'Yes') && (fanIsRunning == true) && (equipmentStatus == 0))
			{ 
			    operatingState = "fan only"
		
			} else if ((state.childParamMap."${dniParts[1]}".haveHumidifier == 'Yes')  && (fanIsRunning == true) && (equipmentStatus == 0) && (fanMode == 0)) {
			    operatingState = "Humidifying"
			}
		
			logInfo("Get Operating State: $operatingState - Fan to $fanState")
			
			//fan mode 0=auto, 2=circ, 1=on, 3=followSched
			
			n = [ 0: 'auto', 2: 'circulate', 1: 'on', 3: 'followSchedule' ][fanMode]
			getChildDevice(cd.deviceNetworkId).parse([[name:"thermostatFanMode", value:n, descriptionText:"${cd.displayName} Fan was Set to $mode"]])
		
			n = [ 1: 'heat', 2: 'off', 3: 'cool', 5: 'auto', 4: 'emergency heat' ][switchPos] ?: 'auto'
			getChildDevice(cd.deviceNetworkId).parse([[name:"temperature", value:curTemp, descriptionText:"${cd.displayName} was Set to $curTemp", unit: "°${location.temperatureScale}"]])
			getChildDevice(cd.deviceNetworkId).parse([[name:"thermostatMode", value:n, descriptionText:"${cd.displayName} was Set to $n"]])
			cd.setLastRunningMode(n) // lastRunningMode in the Child
		
			//Send events 
			getChildDevice(cd.deviceNetworkId).parse([[name:"thermostatOperatingState", value:operatingState, descriptionText:"${cd.displayName} was Set to $operatingState"]])
			getChildDevice(cd.deviceNetworkId).parse([[name:"fanOperatingState", value:fanState, descriptionText:"${cd.displayName} was Set to $fanState"]])
			getChildDevice(cd.deviceNetworkId).parse([[name:"coolingSetpoint", value:coolSetPoint, descriptionText:"${cd.displayName} was Set to $coolSetPoint", unit:"°${location.temperatureScale}"]])
			getChildDevice(cd.deviceNetworkId).parse([[name:"heatingSetpoint", value:heatSetPoint, descriptionText:"${cd.displayName} was Set to $nheatSetPoint", unit:"°${location.temperatureScale}"]])
			getChildDevice(cd.deviceNetworkId).parse([[name:"humidity", value:curHumidity as Integer, descriptionText:"${cd.displayName} was Set to $curHumidity", unit:"%"]])
		
			if (state.childParamMap."${dniParts[1]}".haveHumidifier == 'Yes') {
				// kludge to figure out if humidifier is on, fan has to be auto, and if fan is on but not heat/cool and we have enabled the humidifyer it should be humidifying"
				// if (debugOutput)
			    if (debugOutput) log.debug "fanIsRunning = $fanIsRunning, equip status = $equipmentStatus, fanMode = $fanMode, temp = $curTemp, humidity = $curHumidity"
			     
			 	if ((fanIsRunning == true) && (equipmentStatus == 0) && (fanMode == 0))  
				{
					if (debugOutput) log.debug "Humidifier is On"
			   		//sendEvent(name: 'humidifierStatus', value: "Humidifying")
					getChildDevice(cd.deviceNetworkId).parse([[name:"humidifierStatus", value:"Humidifying", descriptionText:"${cd.displayName} was Set to $Humidifying"]])
				}
				else
				{
					if (debugOutput) log.debug "Humidifier is Off"
					//sendEvent(name: 'humidifierStatus', value: "Idle")   
					getChildDevice(cd.deviceNetworkId).parse([[name:"humidifierStatus", value:"Idle", descriptionText:"${cd.displayName} was Set to $Idle"]])
				}
			}
		
			def now = new Date().format('MM/dd/yyyy h:mm a', location.timeZone)
			getChildDevice(cd.deviceNetworkId).parse([[name:"lastUpdate", value:now, descriptionText:"${cd.displayName} was Set to $now"]])
			
			if (state.childParamMap."${dniParts[1]}".enableOutdoorTemps == "Yes") {
		
				if (state.childParamMap."${dniParts[1]}".hasOutdoorHumid) {
					setOutdoorHumidity(curOutdoorHumidity)
					getChildDevice(cd.deviceNetworkId).parse([[name:"outdoorHumidity", value:curOutdoorHumidity as Integer, descriptionText:"${cd.displayName} was Set to $curOutdoorHumidity", unit:"%"]])
			    }
			
			    if (state.childParamMap."${dniParts[1]}".hasOutdoorTemp) {
					setOutdoorTemperature(curOutdoorTemp)
					getChildDevice(cd.deviceNetworkId).parse([[name:"outdoorTemperature", value:curOutdoorTemp as Integer, descriptionText:"${cd.displayName} was Set to $curOutdoorTemp", unit:"°${location.temperatureScale}"]])
			    }
			}
			getChildDevice(cd.deviceNetworkId).parse([[name:"TCCstatus", value:"success", descriptionText:"${cd.displayName} TCC transaction: success"]])
		
		} else {
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
/*    asynchttpGet("getHumidStatusHandler", params)
}

def getHumidStatusHandler(resp, data) {
	if(resp.getStatus() == 200 || resp.getStatus() == 207) {
        if (debugOutput) log.debug "GetHumidity Request was successful, $resp.status"
*/
	def CancelLine = [:]
	def Number HumLevel
	def Number HumMin
	def Number HumMax
	try {
		httpGet(params) { response ->
			if (debugOutput) log.debug "GetHumidity Request was successful, $response.status"
			if (debugOutput) log.debug "response = $response.data"
			
			//  if (debugOutput) log.debug "ld = $response.data.latestData"
			//  if (debugOutput) log.debug "humdata = $response.data.latestData.humData"
			
			logInfo("lowerLimit: ${response.data.latestData.humData.lowerLimit}")        
			logInfo("upperLimit: ${response.data.humData.upperLimit}")        
			logInfo("SetPoint: ${response.data.humData.Setpoint}")        
			logInfo("DeviceId: ${response.data.humData.DeviceId}")        
			logInfo("IndoorHumidity: ${response.data.humData.IndoorHumidity}")        
			
			def data = response.getData().toString()
			  
			data.split("\n").each {
				//if (debugOutput) log.debug "working on \"${it}\""
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
				
			    		//log.debug "p20 = $p20"
			    		// log.debug "p21 = $p21"
			    		//log.debug "p22 = $p22"
				
			    		HumLevel = p21.toInteger()
			    		HumMin = p20.toInteger()
				
			    		def pair3 = p2.split("%")
			    		//log.debug "pair3 = $pair3"
			    		def p30 = pair3[0]
			    		// log.debug "p30 = $p30"
				
			    		HumMax = p30.toInteger() 
				
			    		if (debugOutput) {
			    			log.debug "-----------------------"
			    			log.debug "Got current humidifier level = $HumLevel"
			    			log.debug "Got Current humidifier Min = $HumMin"
			    			log.debug "Got Current humidifier Max= $HumMax"
			    		}
				}
			}
        
     	//Send events 
	//		(name: 'humidifierStatus', value: HumStatus)
		getChildDevice(cd.deviceNetworkId).parse([[name:"humidifierSetPoint", value:HumLevel as Integer, descriptionText:"${cd.displayName} was Set to $HumLevel", unit:"%"]])
		getChildDevice(cd.deviceNetworkId).parse([[name:"humidifierUpperLimit", value:HumMax as Integer, descriptionText:"${cd.displayName} was Set to $HumMin", unit:"%"]])
		getChildDevice(cd.deviceNetworkId).parse([[name:"humidifierLowerLimit", value:HumMin as Integer, descriptionText:"${cd.displayName} was Set to $HumMin", unit:"%"]])
		}
	} 
	catch (e) {
		log.error "Something went wrong: $e"
		def String eStr = e.toString()
		def pair = eStr.split(" ")
		def p1 = pair[0]
		def p2 = pair[1]
		  
		if ((p2 == "Unauthorized") || (p2 == "Read")) {
			if (fromUnauth) {
				if (debugOutput) log.debug "2nd Unauthorized failure ... giving up!"
				getChildDevice(cd.deviceNetworkId).parse([[name:"TCCstatus", value:"failed", descriptionText:"${cd.displayName} TCC transaction: failed"]])
			} else {
				if (debugOutput) log.debug "Scheduling a retry in 5 minutes due to Unauthorized!"
				def pData = [cd:[cd]]
				runIn(300, refreshFromRunin, pData)
			}
		}
	}
	getChildDevice(cd.deviceNetworkId).parse([[name:"TCCstatus", value:"success", descriptionText:"${cd.displayName} TCC transaction: success"]])

}


def setStatus(cd) {
	
	device.data.SetStatus = 0
	
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
	        SystemSwitch: device.data.SystemSwitch,
	        HeatSetpoint: device.data.HeatSetpoint,
	        CoolSetpoint: device.data.CoolSetpoint,
	        HeatNextPeriod: device.data.HeatNextPeriod,
	        CoolNextPeriod: device.data.CoolNextPeriod,
	        StatusHeat: device.data.StatusHeat,
	        StatusCool: device.data.StatusCool,
	        fanMode: device.data.FanMode,
	        DisplayUnits: location.temperatureScale,
	        TemporaryHoldUntilTime: device.data.TemporaryHoldUntilTime,
	        VacationHold: device.data.VacationHold
	    ],
	  timeout: 10
	]
	
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

/*
    if (debugOutput) log.debug "params = $params"
    asynchttpPost("setStatusHandler", params) 
}    


def setStatusHandler(resp, data) {
	//log.debug "data was passed successfully"
	//log.debug "status of post call is: ${resp.status}"

	if(resp.getStatus() == 408) {if (debugOutput) log.debug "TCC Request timed out, $resp.status"}
	if(resp.getStatus() == 200 || resp.getStatus() == 207) {
		def setStatusResult = resp.data
		if (debugOutput) log.debug "Request was successful, $resp.status"
		device.data.SetStatus = 1
	} else { if (descTextEnable) log.info "TCC setStatus failed" }
*/
}

Boolean refractory = false
void loginRefractory() { refactory = false }

/* ------------------------------------------------------------------

	login

	Purpose: Login to Honeywell's Total Connect Comfort API

	Notes: Collects the cookies the site sends for subsequent interactions:
	       getHumidifierStatus() getStatus() each rely on login and the cookies
	       componentDoRefresh() setStatus() call login themselves
	       
	       login does not use the Honewell Device ID

   ------------------------------------------------------------------ */

def login(Boolean fromUnauth = false) {
	if (debugOutput) log.debug "Honeywell TCC 'login'"
	if (refactory) return 	// we've loged in in the past 600ms
	refractory = true
	runInMillis( 600, loginRefractory)
	
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
	
	            allCookies = allCookies + it.value + ';'
	
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
				def pData = [cd:[cd]]
				runIn(300, refreshFromRunin, pData)
			}
		}
	}
}	


// initialize the device values. Each method overwrites it's specific value
def deviceDataInit(val) { 	 // reset all params, then set individually
	device.data.SystemSwitch = null 
	device.data.HeatSetpoint = null
	device.data.CoolSetpoint = null
	device.data.HeatNextPeriod = null
	device.data.CoolNextPeriod = null
	device.data.FanMode = null
	device.data.StatusHeat=val
	device.data.StatusCool=val
	device.data.TemporaryHoldUntilTime=null
	device.data.VacationHold=null

//    device.data.unit = "°${location.temperatureScale}"

}


def refreshFromRunin()
{ 
	def cd = data["cd"]
	log.debug "Calling refresh after Unauthorize failure!"
	refresh(cd, true)
}


private logInfo (msg) {
	if (settings?.descTextEnable || settings?.descTextEnable == null) log.info "$msg"
}


def getThisCopyright(){"&copy; 2022 C Steele "}
