/*
 *  Zooz Remote Switch ZEN34 	v1.0.1
 *
 *  Changelog:
 *
 *    1.0.1 (11/07/2020)
 *      - Fixed issue with SmartThings converting string attributes into dates if they start or end with specific numbers like 34.
 *
 *    1.0 (11/06/2020)
 *      - Initial Release
 *
 *
 *  Copyright 2020 Zooz
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
*/


import groovy.transform.Field

@Field static Map commandClassVersions = [
	0x20: 1,	// Basic
	0x26: 3,	// Switch Multilevel (4)	
	0x55: 1,	// Transport Service
	0x59: 1,	// AssociationGrpInfo
	0x5A: 1,	// DeviceResetLocally
	0x5B: 1,	// CentralScene (3)
	0x5E: 2,	// ZwaveplusInfo
	0x6C: 1,	// Supervision
	0x70: 2,	// Configuration
	0x72: 2,	// ManufacturerSpecific
	0x73: 1,	// Powerlevel
	0x7A: 2,	// Firmware Update Md (3)
	0x80: 1,	// Battery
	0x84: 2,	// WakeUp
	0x85: 2,	// Association
	0x86: 1,	// Version (2)
	0x8E: 2,	// MultiChannelAssociation (3)
	0x9F: 1		// Security 2
]

@Field static int wakeUpInterval = 12180

@Field static Map ledModeOptions = [0:"LED always off", 1:"LED on when button is pressed [DEFAULT]", 2:"LED always on in specified upper paddle color", 3:"LED always on in specified lower paddle color"]

@Field static Map ledColorOptions = [0:"White", 1:"Blue", 2:"Green", 3:"Red", 4:"Magenta", 5:"Yellow", 6:"Cyan"]

@Field static Map associationGroups = [2:"associationGroupTwo", 3:"associationGroupThree"]

@Field static Map debugLoggingOptions = [0:"Disabled", 1:"Enabled [DEFAULT]"]


metadata {
	definition (
		name:"Zooz Remote Switch ZEN34", 
		namespace:"Zooz", 
		author: "Kevin LaFramboise (krlaframboise)", 
		ocfDeviceType: "x.com.st.d.remotecontroller",
		mnmn: "SmartThingsCommunity",
		vid: "954e5fd3-f282-3f6a-b286-f5dd392fd295"
	) {
		capability "Sensor"
		capability "Battery"
		capability "Button"
		capability "Refresh"
		capability "Configuration"
		capability "Health Check"
		capability "platemusic11009.firmware"		
		capability "platemusic11009.associationGroupTwo"
		capability "platemusic11009.associationGroupThree"
		capability "platemusic11009.syncStatus"

		attribute "lastCheckIn", "string"

		fingerprint mfr: "0312", prod: "0004", model: "F001", deviceJoinName: "Zooz Remote Switch"
		fingerprint mfr: "027A", prod: "0004", model: "F001", deviceJoinName: "Zooz Remote Switch"
	}

	simulator { }

	preferences {
		configParams.each {
			createEnumInput("configParam${it.num}", "${it.name}:", it.value, it.options)
		}
		createEnumInput("debugOutput", "Debug Logging", 1, debugLoggingOptions)
	
		input "assocInstructions", "paragraph",
			title: "Device Associations",
			description: "Associations are an advance feature that allow you to establish direct communication between Z-Wave devices.  To make this motion sensor control another Z-Wave device, get that device's Device Network Id from the My Devices section of the IDE and enter the id below.  Group 2 and Group 3 supports up to 10 associations and you can use commas to separate the device network ids.",
			required: false

		input "assocDisclaimer", "paragraph",
			title: "WARNING",
			description: "If you add a device's Device Network ID to the list below and then remove that device from SmartThings, you MUST come back and remove it from the list below.  Failing to do this will substantially increase the number of z-wave messages being sent by this device and could affect the stability of your z-wave mesh.",
			required: false

		input "group2AssocDNIs", "string",
			title: "Enter Device Network IDs for Group 2 On/Off Association:",
			required: false

		input "group3AssocDNIs", "string",
			title: "Enter Device Network IDs for Group 3 Dimming Association:",
			required: false
	}
}

void createEnumInput(String name, String title, Integer defaultVal, Map options) {
	input name, "enum",
		title: title,
		required: false,
		defaultValue: defaultVal.toString(),
		options: options
}


def installed() {
	logDebug "installed()..."
	
	state.refreshAll = true

	initialize()

	return []
}


def updated() {
	if (!isDuplicateCommand(state.lastUpdated, 2000)) {
		state.lastUpdated = new Date().time

		logDebug "updated()..."

		initialize()

		if (pendingChanges) {
			logForceWakeupMessage "The setting changes will be sent to the device the next time it wakes up."
		}

		refreshSyncStatus()		
	}
	return []
}

void initialize() {
	state.debugLoggingEnabled = (safeToInt(settings?.debugOutput, 1) != 0)
	
	if (!device.currentValue("supportedButtonValues")) {
		sendEvent(name:"supportedButtonValues", value: ["down","down_hold","down_2x","down_3x","down_4x","down_5x","up","up_hold","up_2x","up_3x","up_4x","up_5x"].encodeAsJSON(), displayed:false)
	}

	if (!device.currentValue("numberOfButtons")) {
		sendEvent(name:"numberOfButtons", value:1, displayed:false)
	}

	if (!device.currentValue("button")) {
		sendButtonEvent("up")
	}
	
	if (device.currentValue("associationGroupTwo") == null) {
		sendEvent(name: "associationGroupTwo", value: "")
	}
	
	if (device.currentValue("associationGroupThree") == null) {
		sendEvent(name: "associationGroupThree", value: "")
	}
}


def configure() {
	logDebug "configure()..."

	sendCommands(getConfigureCmds())
}

List<String> getConfigureCmds() {
	List<String> cmds = []

	int changes = pendingChanges
	if (changes) {
		log.warn "Syncing ${changes} Change(s)"
	}

	if (state.refreshAll || !device.currentValue("firmwareVersion")) {
		logDebug "Requesting Version Report"
		cmds << versionGetCmd()
	}
	
	if (state.refreshAll || !device.currentValue("battery")) {
		logDebug "Requesting Battery Report"
		cmds << batteryGetCmd()
	}

	if (state.wakeUpInterval != wakeUpInterval) {
		logDebug "Setting Wake Up Interval to ${wakeUpInterval} Seconds"
		cmds << wakeUpIntervalSetCmd(wakeUpInterval)
		cmds << wakeUpIntervalGetCmd()
	}

	configParams.each { param ->
		Integer storedVal = getParamStoredValue(param.num)
		if (state.refreshAll || storedVal != param.value) {
			logDebug "Changing ${param.name}(#${param.num}) from ${storedVal} to ${param.value}"
			cmds << configSetCmd(param, param.value)
			cmds << configGetCmd(param)
		}
	}
	
	cmds += getConfigureAssocsCmds()
	
	state.refreshAll = false
	
	return cmds
}


private getConfigureAssocsCmds() {
	List<String> cmds = []
	
	associationGroups.each { group, name ->
		boolean changes = false
		
		def stateNodeIds = state["${name}NodeIds"]
		def settingNodeIds = getAssocDNIsSettingNodeIds(group)
		
		def newNodeIds = settingNodeIds?.findAll { !(it in stateNodeIds) }
		if (newNodeIds) {
			logDebug "Adding Nodes ${newNodeIds} to Association Group ${group}"
			cmds << associationSetCmd(group, newNodeIds)
			changes = true
		}

		def oldNodeIds = stateNodeIds?.findAll { !(it in settingNodeIds) }
		if (oldNodeIds) {
			logDebug "Removing Nodes ${oldNodeIds} from Association Group ${group}"
			cmds << associationRemoveCmd(group, oldNodeIds)
			changes = true
		}

		if (changes || state.refreshAll) {
			cmds << associationGetCmd(group)
		}
	}
	return cmds
}

List<Integer> getAssocDNIsSettingNodeIds(int group) {
	String assocSetting = settings["group${group}AssocDNIs"] ?: ""
	
	List<Integer> nodeIds = convertHexListToIntList(assocSetting?.split(","))

	if (assocSetting && !nodeIds) {
		log.warn "'${assocSetting}' is not a valid value for the 'Device Network Ids for Association Group ${group}' setting.  All z-wave devices have a 2 character Device Network Id and if you're entering more than 1, use commas to separate them."
	}
	else if (nodeIds?.size() >  10) {
		log.warn "The 'Device Network Ids for Association Group ${group}' setting contains more than 10 Ids so only the first 10 will be associated."
	}

	return nodeIds
}


def ping() {
	logDebug "ping()..."
}


def refresh() {
	logDebug "refresh()..."
	
	refreshSyncStatus()
	state.refreshAll = true
	
	logForceWakeupMessage "The next time the device wakes up, the sensor data will be requested."
	return []
}


List<String> sendCommands(List<String> cmds, Integer delay=500) {
	if (cmds) {
		def actions = []
		cmds.each {
			actions << new physicalgraph.device.HubAction(it)
		}
		sendHubCommand(actions, delay)
	}
	return []
}


String associationSetCmd(int group, nodes) {
	return secureCmd(zwave.associationV2.associationSet(groupingIdentifier: group, nodeId: nodes))
}

String associationRemoveCmd(int group, nodes) {
	return secureCmd(zwave.associationV2.associationRemove(groupingIdentifier: group, nodeId: nodes))
}

String associationGetCmd(int group) {
	return secureCmd(zwave.associationV2.associationGet(groupingIdentifier: group))
}

String wakeUpIntervalSetCmd(seconds) {
	return secureCmd(zwave.wakeUpV2.wakeUpIntervalSet(seconds:seconds, nodeid:zwaveHubNodeId))
}

String wakeUpIntervalGetCmd() {
	return secureCmd(zwave.wakeUpV2.wakeUpIntervalGet())
}

String wakeUpNoMoreInfoCmd() {
	return secureCmd(zwave.wakeUpV2.wakeUpNoMoreInformation())
}

String versionGetCmd() {
	return secureCmd(zwave.versionV1.versionGet())
}

String batteryGetCmd() {
	return secureCmd(zwave.batteryV1.batteryGet())
}

String centralSceneConfigSetCmd() {
	// return secureCmd(zwave.centralSceneV3.centralSceneConfigurationSet(slowRefresh: 1))	
	return secureRawCmd("5B0480") // Central Scene V3 Not Supported
}

String centralSceneConfigGetCmd() {
	// return secureCmd(zwave.centralSceneV3.centralSceneConfigurationGet())
	return secureRawCmd("5B05") // Central Scene V3 Not Supported
}

String configSetCmd(Map param, Integer value) {
	return secureCmd(zwave.configurationV1.configurationSet(parameterNumber: param.num, size: param.size, scaledConfigurationValue: value))
}

String configGetCmd(Map param) {
	return secureCmd(zwave.configurationV1.configurationGet(parameterNumber: param.num))
}

String secureCmd(cmd) {
	if (joinedSecure) {
		return zwave.securityV1.securityMessageEncapsulation().encapsulate(cmd).format()
	}
	else {
		return cmd.format()
	}
}

String secureRawCmd(String cmd) {
	if (joinedSecure) {
		return "988100${cmd}"
	}
	else {
		return cmd
	}
}

boolean getJoinedSecure() {
	return zwaveInfo?.zw?.contains("s") || ("0x98" in device.rawDescription?.split(" "))
}


def parse(String description) {
	def cmd = zwave.parse(description, commandClassVersions)
	if (cmd) {
		zwaveEvent(cmd)		
	}
	else {
		log.warn "Unable to parse: $description"
	}

	updateLastCheckIn()
	return []
}

void updateLastCheckIn() {
	if (!isDuplicateCommand(state.lastCheckInTime, 60000)) {
		state.lastCheckInTime = new Date().time

		sendEvent(name: "lastCheckIn", value: convertToLocalTimeString(new Date()), displayed: false)
	}
}

String convertToLocalTimeString(dt) {
	try {
		def timeZoneId = location?.timeZone?.ID
		if (timeZoneId) {
			return dt.format("MM/dd/yyyy hh:mm:ss a", TimeZone.getTimeZone(timeZoneId))
		}
		else {
			return "$dt"
		}
	}
	catch (ex) {
		return "$dt"
	}
}


void zwaveEvent(physicalgraph.zwave.commands.wakeupv2.WakeUpIntervalReport cmd) {
	logTrace "$cmd"

	runIn(3, refreshSyncStatus)

	state.wakeUpInterval = cmd.seconds

	// Set the Health Check interval so that it can be skipped twice plus 5 minutes.
	def checkInterval = ((cmd.seconds * 2) + (5 * 60))

	sendEvent(name: "checkInterval", value: checkInterval, displayed: false, data: [protocol: "zwave", hubHardwareId: device.hub.hardwareID])
}


void zwaveEvent(physicalgraph.zwave.commands.wakeupv2.WakeUpNotification cmd) {
	logDebug "Device Woke Up..."
	
	def cmds = getConfigureCmds()
	if (cmds) {
		cmds << "delay 1000"
	}
	cmds << wakeUpNoMoreInfoCmd()

	sendCommands(cmds)
}


void zwaveEvent(physicalgraph.zwave.commands.configurationv2.ConfigurationReport cmd) {
	logTrace "${cmd}"
	
	runIn(3, refreshSyncStatus)

	Map param = configParams.find { it.num == cmd.parameterNumber }
	if (param) {
		int val = cmd.scaledConfigurationValue

		setParamStoredValue(param.num, val)

		logDebug "${param.name}(#${param.num}) = ${val}"
	}
	else {
		logDebug "Parameter #${cmd.parameterNumber} = ${cmd.scaledConfigurationValue}"
	}
}


void zwaveEvent(physicalgraph.zwave.commands.associationv2.AssociationReport cmd) {
	logTrace "$cmd"
	
	runIn(3, refreshSyncStatus)
	
	logDebug "Group ${cmd.groupingIdentifier} Association: ${cmd.nodeId}"
	
	String name = associationGroups.get(safeToInt(cmd.groupingIdentifier))
	if (name) {		
		state["${name}NodeIds"] = cmd.nodeId

		def dnis = convertIntListToHexList(cmd.nodeId)?.join(", ") ?: ""				
		if (dnis) {
			dnis = "[${dnis}]" // wrapping it with brackets prevents ST from attempting to convert the value into a date.
		}
		sendEventIfNew(name, dnis, false)
	}
}


void zwaveEvent(physicalgraph.zwave.commands.versionv1.VersionReport cmd) {
	String subVersion = String.format("%02d", cmd.applicationSubVersion)
	String fullVersion = "${cmd.applicationVersion}.${subVersion}"
	
	logDebug "Firmware Version: ${fullVersion}"

	sendEventIfNew("firmwareVersion", fullVersion.toBigDecimal())
}


void zwaveEvent(physicalgraph.zwave.commands.batteryv1.BatteryReport cmd) {
	def val = (cmd.batteryLevel == 0xFF ? 1 : cmd.batteryLevel)

	if (val > 100) {
		val = 100
	}
	
	logDebug "Battery is ${val}%"
	sendEvent(name:"battery", value:val, unit:"%", isStateChange: true)
}


void zwaveEvent(physicalgraph.zwave.commands.centralscenev1.CentralSceneNotification cmd){
	if (state.lastSequenceNumber != cmd.sequenceNumber) {
		state.lastSequenceNumber = cmd.sequenceNumber

		String paddle = (cmd.sceneNumber == 1) ? "up" : "down"
		String btnVal
		switch (cmd.keyAttributes){
			case 0:
				btnVal = paddle
				break
			case 1:
				logDebug "${paddle}_released is not supported by SmartThings"
				btnVal = paddle + "_released"
				break
			case 2:
				btnVal = paddle + "_hold"
				break
			case { it >= 3 && it <= 7}:
				btnVal = paddle + "_${cmd.keyAttributes - 1}x"
				break
			default:
				logDebug "keyAttributes ${cmd.keyAttributes} not supported"
		}

		if (btnVal) {
			sendButtonEvent(btnVal)
		}
	}
}

void sendButtonEvent(String value) {
	String desc = "${device.displayName} ${value}"
	logDebug(desc)

	sendEvent(name: "button", value: value, data:[buttonNumber: 1], isStateChange: true, descriptionText: desc)
}


void zwaveEvent(physicalgraph.zwave.Command cmd) {
	logDebug "Unhandled zwaveEvent: $cmd"
}


List<Map> getConfigParams() {
	return [
		ledModeParam,
		upperPaddleLedColorParam,
		lowerPaddleLedColorParam
	]
}

Map getLedModeParam() {
	return getParam(1, "LED Indicator Mode", 1, 1, ledModeOptions)
}

Map getUpperPaddleLedColorParam() {
	return getParam(2, "Upper Paddled LED Indicator Color", 1, 1,  setDefaultOption(ledColorOptions, 1))
}

Map getLowerPaddleLedColorParam() {
	return getParam(3, "Lower Paddle LED Indicator Color", 1, 0, setDefaultOption(ledColorOptions, 0))
}

Map getParam(Integer num, String name, Integer size, Integer defaultVal, Map options) {
	Integer val = safeToInt((settings ? settings["configParam${num}"] : null), defaultVal)

	return [num: num, name: name, size: size, value: val, options: options]
}

Map setDefaultOption(Map options, int defaultVal) {
	return options.collectEntries { (it.key == defaultVal) ? [it.key, "${it.value} [DEFAULT]"] : it }
}


Integer getParamStoredValue(Integer paramNum, Integer defaultVal=null) {
	return safeToInt(state["configVal${paramNum}"] , defaultVal)
}

void setParamStoredValue(Integer paramNum, Integer value) {
	state["configVal${paramNum}"] = value
}


void refreshSyncStatus() {
	int changes = pendingChanges
	sendEventIfNew("syncStatus", (changes ?  "${changes} Pending Change(s)<br>Tap Up x 7" : "Synced"), false)
}


int getPendingChanges() {
	int configChanges = safeToInt(configParams.count { it.value != getParamStoredValue(it.num) })
	int pendingWakeUpInterval = (state.wakeUpInterval != wakeUpInterval ? 1 : 0)
	int pendingAssocs = (getConfigureAssocsCmds()?.size() ? 1 : 0)
		
	return (configChanges + pendingWakeUpInterval + pendingAssocs)
}


void logForceWakeupMessage(String msg) {
	log.warn "${msg}  You can force the device to wake up immediately by tapping the upper paddle 7x."
}


void sendEventIfNew(String name, value, boolean displayed=true) {
	String desc = "${device.displayName}: ${name} is ${value}"
	if (device.currentValue(name) != value) {
		logDebug(desc)
		sendEvent(name: name, value: value, descriptionText: desc, displayed: displayed)
	}
}


List<String> convertIntListToHexList(List<Integer> intList) {
	List<String> hexList = []
	intList?.each {
		hexList.add(Integer.toHexString(it).padLeft(2, "0").toUpperCase())
	}
	return hexList
}

List<Integer> convertHexListToIntList(String[] hexList) {
	List<Integer> intList = []

	hexList?.each {
		try {
			it = it.trim()
			intList.add(Integer.parseInt(it, 16))
		}
		catch (e) { }
	}
	return intList
}


Integer safeToInt(val, Integer defaultVal=0) {
	if ("${val}"?.isInteger()) {
		return "${val}".toInteger()
	}
	else if ("${val}".isDouble()) {
		return "${val}".toDouble()?.round()
	}
	else {
		return  defaultVal
	}
}


boolean isDuplicateCommand(lastExecuted, allowedMil) {
	!lastExecuted ? false : (lastExecuted + allowedMil > new Date().time)
}


void logDebug(String msg) {
	if (state.debugLoggingEnabled) {
		log.debug(msg)
	}
}


void logTrace(String msg) {
	 // log.trace(msg)
}