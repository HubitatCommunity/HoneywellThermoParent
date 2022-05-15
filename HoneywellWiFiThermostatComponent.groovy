/**
 * IMPORT URL: https://raw.githubusercontent.com/HubitatCommunity/HoneywellThermoParent/main/HoneywellWiFiThermostatComponent.groovy
 *
 *  Total Comfort API
 *   
 *  Based on Generic Component Code by Hubitat/Mike Maxwell and edited by C Steele
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 * 
 * csteele: v2.0.4   added Delete Outdoor Children and Delete Thermostat
 * csteele: v2.0.3   corrected PermHold to be a code value
 * csteele: v2.0.2   corrected logic errors in Poll
 *			   renamed 
 *			   adjusted Schedule() to be from the current minute.
 *				as in:  0 20/30 * ? * * == "At second :00, every 30 minutes starting at minute :20, of every hour",
 *				assuming Poll was started at minute 20 of the current hour
 * csteele: v2.0.1   Put setLastRunningMode into Child
 * csteele: v2.0.0   Initial Commit
 *
 * Forked from:
 * csteele: v1.3.20  Added "emergency/auxiliary" heat.
 *                    added fanOperatingState Attribute.
**/

 public static String version()     {  return "v2.0.4"  }


metadata {
    definition(name: "Honeywell WiFi Thermostat Component", namespace: "csteele", author: "CSteele", component: true, importUrl: "https://raw.githubusercontent.com/HubitatCommunity/HoneywellThermoParent/main/HoneywellWiFiThermostatComponent.groovy") {
        capability "Thermostat"
        capability "Refresh"
        capability "Actuator"
        capability "Polling"
        capability "Temperature Measurement"
        capability "Sensor"
        capability "Relative Humidity Measurement"

        attribute  "outdoorHumidity",    "number"
        attribute  "outdoorTemperature", "number"
        attribute  "lastUpdate",         "string"
        attribute  "followSchedule",     "string"
        attribute  "fanOperatingState",  "string"
        
        attribute "humidifierStatus", "string"
        attribute "humidifierSetPoint", "number"
        attribute "humidifierUpperLimit", "number"
        attribute "humidifierLowerLimit", "number"
        
        attribute "TCCstatus", "string"

        command    "heatLevelUp"
        command    "heatLevelDown"
        command    "coolLevelUp"
        command    "coolLevelDown"
        command    "setFollowSchedule"
        command	 "setLastRunningMode"	// does nothing in this UI
        command    "caution_deleteOutdoorDevices"
        command    "caution_deleteThisThermostat"

/* -= Attribute List =-
 	[thermostatFanMode, humidifierLowerLimit, supportedThermostatFanModes, supportedThermostatModes, followSchedule, humidifierSetPoint, thermostatSetpoint, 
 	coolingSetpoint, humidifierUpperLimit, outdoorHumidity, temperature, outdoorTemperature, humidifierStatus, lastUpdate, thermostatMode, fanOperatingState, 
 	thermostatOperatingState, heatingSetpoint, humidity, temperature, TCCstatus]

   -= Command List =-
 	[auto, cool, coolLevelDown, coolLevelUp, emergencyHeat, fanAuto, fanCirculate, fanOn, heat, heatLevelDown, heatLevelUp, off, 
 	poll, refresh, setCoolingSetpoint, setFollowSchedule, setHeatingSetpoint, setThermostatFanMode, setThermostatMode]

*/	
    }

    preferences {
       input name: "honeywelldevice", type: "text", title: "Device ID", description: "Your Device ID", required: true
       input name: "haveHumidifier", type: "enum", title: "Do you have an optional whole house steam humidifier and want to enable it?", options: ["Yes", "No"], required: true, defaultValue: "No"
       input name: "enableOutdoorTemps", type: "enum", title: "Do you have the optional outdoor temperature sensor and want to enable it?", options: ["Yes", "No"], required: false, defaultValue: "No"
       input name: "enableHumidity", type: "enum", title: "Do you have the optional Humidity sensor and want to enable it?", options: ["Yes", "No"], required: false, defaultValue: "No"
       input name: "setPermHold", type: "enum", title: "Will Setpoints be temporary or permanent?", options: ["Temporary", "Permanent"], required: false, defaultValue: "Temporary"
       input name: "pollIntervals", type: "enum", title: "Set the Poll Interval.", options: [0:"off", 60:"1 minute", 120:"2 minutes", 180:"3 minutes", 300:"5 minutes",600:"10 minutes",900:"15 minutes",1800:"30 minutes",3600:"60 minutes"], required: true, defaultValue: "600"
       input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

void updated() {
	log.info "Updated ${this.device}"
	log.warn "description logging is: ${txtEnable == true}"
	log.debug "device: $device.deviceNetworkId"
	runInMillis( 200, parentUpdate)
	runInMillis( 400, poll)
}

void parentUpdate() {
	if (setPermHold == "Permanent") { PermHoldCode = 2 } else { PermHoldCode = 1 }
	String cd = device.deviceNetworkId
	parent.setParams(cd, honeywelldevice, haveHumidifier, enableOutdoorTemps, enableHumidity, PermHoldCode, pollIntervals)
}


void poll() {
	if (txtEnable) log.debug "received Poll request from ${this.displayName ?: (this.device.label ?: this.device.name)} Poll Interval: $pollIntervals"
	unschedule(refresh) // maybe poll interval is now zero
	// build out a cron string for pollInterval options 1 min -- 60 min
	if (pollIntervals > "0") { 
	    def boutNow = new Date(new Date().time)
	    Integer pIminute = pollIntervals.toInteger() / 60 // find minutes
	    Integer pIhour = pIminute / 60 // find hours

	    String pIntMinute = (pIminute == 60 || pIminute == 0) ? "${boutNow.minutes}" : "${boutNow.minutes % pIminute}/$pIminute"
	    String pIntHour = (pIhour < 1) ? "*" : "*/$pIhour"

      	schedule("0 $pIntMinute $pIntHour ? * *", refresh) 
	}

	// clicking Poll means do at least one refresh, even if there's no poll interval
	refresh()
}

void installed() {
	log.info "Installed..."
	device.updateSetting("txtEnable",[type:"bool",value:true])
}

void parse(String description) { log.warn "parse(String description) not implemented" }

void parse(List description) {
	description.each {
            if (txtEnable) log.info it.descriptionText
            sendEvent(it)
    }
}

///*
void caution_createOutdoorDevices() {
    parent?.createOutdoorDevices(this.device)
}

///*/
void caution_deleteThisThermostat() {
    parent?.componentDeleteThermostatChild(this.device)
}

void caution_deleteOutdoorDevices() {
    parent?.componentDeleteOutdoorChild(this.device.id)
}

void deleteOutdoorDevices() {
    parent?.componentDeleteOutdoorChild(this.device.id)
}

void setFollowSchedule() {
    parent?.componentSetFollowSchedule(this.device)
}

void off() {
    parent?.componentOff(this.device)
}

void refresh() {
    parent?.componentDoRefresh(this.device)
}

void auto() {
    parent?.componentAuto(this.device)
}

void cool() {
    parent?.componentCool(this.device)
}

void emergencyHeat() {
    parent?.componentEmergencyHeat(this.device)
}

void fanAuto() {
    parent?.componentFanAuto(this.device)
}

void fanCirculate() {
    parent?.componentFanCirculate(this.device)
}

void fanOn() {
    parent?.componentFanOn(this.device)
}

void heat() {
    parent?.componentHeat(this.device)
}

void setCoolingSetpoint(temperature) {
    parent?.componentSetCoolingSetpoint(this.device,temperature)
}

void setHeatingSetpoint(temperature) {
    parent?.componentSetHeatingSetpoint(this.device, temperature)
}

void setThermostatFanMode(fanmode) {
    parent?.componentSetThermostatFanMode(this.device, fanmode)
}

void setThermostatMode(thermostatmode) {
    parent?.componentSetThermostatMode(this.device, thermostatmode)
}

void coolLevelDown() {
    parent?.componentCoolLevelDown(this.device)
}

void coolLevelUp() {
    parent?.componentCoolLevelUp(this.device)
}

void heatLevelDown() {
    parent?.componentHeatLevelDown(this.device)
}

void heatLevelUp() {
    parent?.componentHeatLevelUp(this.device)
}


/*
	device UI: do nothing 
	"lrM" must be exposed as a Command to the UI so that Parent can call it. 
	That puts a button on the UI that really shouldn't be pushed. Overloads 
	lrM to capture the button push, while Parent will call lrM with (mode).
*/

def setLastRunningMode() { log.info "lrM button pushed." }
def setLastRunningMode (mode) {
	String lrm = getDataValue("lastRunningMode")
	if (mode.contains("auto") || mode.contains("off") && lrm != "heat") { updateDataValue("lastRunningMode", "heat") }
	 else { updateDataValue("lastRunningMode", mode) }
}
