
definition(
    name: "InControl",
    namespace: "hubitat",
    author: "Garrett Cook",
    description: "InControl",
    category: "Convenience",
    singleInstance: true,
	singleThreaded: true
) {
	capability "Initialize"
	capability "VoltageMeasurement"
	capability "PressureMeasurement"
	capability "Lock"
	capability "Alarm"
	capability "TimedSession" /* preconditioning */
	capability "Battery"
	capability "LevelPreset"
	capability "Actuator"
	command "authenticateUser"
	command "registerDevice"
	command "login"
	// command "refreshToken"
	command "lock"
	command "unlock"
	command "start", [[name: "Start Preconditioning"]]
	command "stop", [[name: "Stop Preconditioning"]]
	command "siren", [[name: "honkBlink"]]
	command "off", [[name: "alarmOff"]]
	command "chargeProfile", [[name: "command*", type:"ENUM", constraints:["START","STOP","SET_PERMANENT_MAX_SOC","SET_ONE_OFF_MAX_SOC"]]]
	command "vehicleStatus"
	command "healthstatus", [[name: "Wake vehicle"]]
	command "refreshStatus", [[name: "Wake vehicle, then obtain vehicleStatus"]]

	attribute "BATTERY_VOLTAGE", "NUMBER"
	attribute "lastUpdatedTime", "Date"
	attribute "TYRE_PRESSURE_FRONT_LEFT", "NUMBER"
	attribute "TYRE_PRESSURE_FRONT_RIGHT", "NUMBER"
	attribute "TYRE_PRESSURE_REAR_LEFT", "NUMBER"
	attribute "TYRE_PRESSURE_REAR_RIGHT", "NUMBER"
	attribute "alarm", "ENUM", ["off", "siren"]
	attribute "lock", "ENUM", ["locked", "unlocked"]
	attribute "sessionStatus", "ENUM", ["stopped", "canceled", "running", "paused", "unknown"]
	attribute "RDL", "String"
	attribute "ECC", "String"
	attribute "EV_CHARGING_STATUS", "String"
	attribute "battery", "NUMBER" /* EV_STATE_OF_CHARGE */
	attribute "levelPreset", "NUMBER" /* MAX_SOC_VALUE */
	attribute "EV_PERMANENT_MAX_SOC_VALUE", "NUMBER"
	attribute "EV_ONE_OFF_MAX_VALUE", "NUMBER"
	attribute "EV_ONE_OFF_MAX_SOC_CHARGE_SETTING_CHOICE", "String"
	attribute "EV_CHARGING_RATE_MILES_PER_HOUR", "NUMBER"
	attribute "EV_MINUTES_TO_FULLY_CHARGED", "NUMBER"
	attribute "EV_RANGE_ON_BATTERY_MILES", "NUMBER"
	attribute "EV_PRECONDITION_OPERATING_STATUS", "NUMBER"
	attribute "WINDOW_FRONT_LEFT_STATUS", "String"
	attribute "WINDOW_FRONT_RIGHT_STATUS", "NUMBER"
	attribute "WINDOW_REAR_LEFT_STATUS", "NUMBER"
	attribute "WINDOW_REAR_RIGHT_STATUS", "NUMBER"
}

preferences {
    section("InControl Auth") {
        input "username", "text", title: "Username", required: true
        input "password", "password", title: "Password", required: true
        input "pin", "password", title: "PIN"
        // input "deviceId", "text", title: "X-Device-Id", required: true
        input "vin", "text", title: "VIN", required: true
        input "target_temperature", "number", title: "Preconditioning Target Temperature", description: "(In Celsius x 10)"
        input "PERMANENT_MAX_SOC", "number", title: "Daily Charge Limit", defaultValue: 70
        input "ONE_OFF_MAX_SOC", "number", title: "Only-Once Charge Limit", defaultValue: 100
    }
	input name: "infoLogging", type: "bool", title: "Enable info logging", description: ""
	input name: "debugLogging", type: "bool", title: "Enable debug logging", description: ""
	input name: "refreshInterval", type: "number", title: "Refresh Interval", description: "Frequency (in minutes) to refresh Vehicle Status, after last refresh", defaultValue: 60
	input name: "delayRetrieval", type: "number", title: "Retrieval Delay", description: "Delay (in seconds) before retrieving Vehicle Status, after Refresh Status -> Healthstatus ping", defaultValue: 15
}

def getChildNamespace() { "hubitat" }
def getServer() { "prod-row.jlrmotor.com" }

def installed() {
}

def updated() {
}

def getUsername() {
	if(parent) {
		return "${parent.getUsername()}"
	} else {
    	return username
	}
}

def getPassword() {
	if(parent) {
		return "${parent.getPassword()}"
	} else {
    	return password
	}
}

def initialize() {
}

def getDefaultHeaders() {
    def headers = [
		'Content-Type': 'application/json',
        'Authorization': 'Basic YXM6YXNwYXNz',
        'X-Device-Id': device.deviceNetworkId,
        'Connection': 'close',
    ]
	// 'Connection': 'keep-alive',

    return headers
}

private authenticateUser() {
    displayDebugLog("authenticateUser()")

    def authenticateParams = [
		uri: "https://ifas.${server}",
		path: "/ifas/jlr/tokens",
		requestContentType: 'application/json',
		headers: [
			'Content-Type': 'application/json',
			'Authorization': 'Basic YXM6YXNwYXNz',
			'X-Device-Id': device.deviceNetworkId,
			'Connection': 'close',
		],
		body: [
			'grant_type': 'password',
			'password': password,
			'username': username,
		]
    ]
               
	try {
		httpPost(authenticateParams) { authenticateResp ->
			if (authenticateResp.status != 200) {
				throw new Exception("Did not receive successful response status code.  Received code: ${authenticateResp.status}")
			} else {
				def jsonResponseData = authenticateResp.data
				displayDebugLog("Received data: ${jsonResponseData}")
				state.AccessToken = authenticateResp.data.access_token
				state.AuthToken = authenticateResp.data.authorization_token
				state.RefreshToken = authenticateResp.data.refresh_token
				state.TokenExpiresIn = authenticateResp.data.expires_in
			}
		}
	} catch(Exception ex) {
		log.error(ex.toString() + ', ' + ex.getMessage())
		return false
	}
	return true
}

/* No real point to using this, since the refreshToken doesn't work with authenticateCommand, etc */
private refreshToken() {
    displayDebugLog("refreshToken()")

    def refreshParams = [
		uri: "https://ifas.${server}",
		path: "/ifas/jlr/tokens",
		requestContentType: 'application/json',
		headers: [
			'Content-Type': 'application/json',
			'Authorization': 'Basic YXM6YXNwYXNz',
			'X-Device-Id': device.deviceNetworkId,
			'Connection': 'close',
		],
		body: [
			'grant_type': 'refresh_token',
			'refresh_token': state.RefreshToken,
		]
    ]
        
	try {
		httpPost(refreshParams) { refreshResp ->
			if (refreshResp.status != 200) {
				throw new Exception("Did not receive successful response status code.  Received code: ${refreshResp.status}")
			} else {
				def jsonResponseData = refreshResp.data
				displayDebugLog("Received data: ${jsonResponseData}")
				state.AccessToken = refreshResp.data.access_token
				state.AuthToken = refreshResp.data.authorization_token
				state.RefreshToken = refreshResp.data.refresh_token
				state.TokenExpiresIn = refreshResp.data.expires_in
			}
		}
	} catch(Exception ex) {
		log.error(ex.toString() + ', ' + ex.getMessage())
		return false
	}
	return true
}

private registerDevice() {
    displayDebugLog("registerDevice()")

    def registrationParams = [
		uri: "https://ifop.${server}",
		path: "/ifop/jlr/users/${username}/clients",
		requestContentType: 'application/json',
		headers: [
			'Content-Type': 'application/json',
			'Authorization': 'Basic YXM6YXNwYXNz',
			'X-Device-Id': device.deviceNetworkId,
			'Connection': 'close',
		],
		body: [
			"access_token": state.AccessToken,
			"authorization_token": state.AuthToken,
			"expires_in": state.TokenExpiresIn,
			"deviceID": device.deviceNetworkId
		]
    ]
             
	try {
		httpPost(registrationParams) { registrationResp ->
			if (registrationResp.status != 204) {
				throw new Exception("Did not receive successful response status code.  Received code: ${registrationResp.status}")
			}
		}
	} catch(Exception ex) {
		log.error(ex.toString() + ', ' + ex.getMessage())
		return false
	}
	return true
}

private login() {
    displayDebugLog("login()")

    def loginParams = [
		uri: "https://if9.${server}",
		path: "/if9/jlr/users?loginName=${username}",
		requestContentType: 'application/json',
		headers: [
			'Content-Type': 'application/json',
			// 'Accept': 'application/vnd.wirelesscar.ngtp.if9.User-v3+json',
			'X-Device-Id': device.deviceNetworkId,
			'Connection': 'close',
			'Authorization': 'Bearer ' + state.AccessToken
		]
    ]
               
	try {
		httpGet(loginParams) { loginResp ->
			if (loginResp.status != 200) {
				throw new Exception("Did not receive successful response status code.  Received code: ${loginResp.status}")
			} else {
				def jsonResponseData = loginResp.data
				displayDebugLog("Received data: ${jsonResponseData}")
				state.UserID = jsonResponseData.userId
			}
		}
	} catch(Exception ex) {
		log.error(ex.toString() + ', ' + ex.getMessage())
		return false
	}
	return true
}

private attributes() {
    displayDebugLog("attributes()")

    def attributesParams = [
		uri: "https://if9.${server}",
		path: "/if9/jlr/vehicles/" + vin + "/attributes",
		requestContentType: 'application/json',
		headers: [
			'Content-Type': 'application/json',
			// 'Accept': 'application/vnd.ngtp.org.VehicleAttributes-v4+json',
			'X-Device-Id': device.deviceNetworkId,
			'Authorization': 'Bearer ' + state.AccessToken
		]
    ]
               
	httpGet(attributesParams) { attributesResp ->
		if (attributesResp.status != 200) {
			log.warn("Did not receive successful response status code.  Received code: ${attributesResp.status}")
		} else {
			def jsonResponseData = attributesResp.data
			displayDebugLog("Received data: ${jsonResponseData}")
		}
	}
}

private getServiceStatus(customerServiceId, canRunAgain = true) {
	displayInfoLog("Checking service status...")
	displayDebugLog("customerServiceId(${customerServiceId})")
	// countOfIterations++;
	def customerServiceParams = [
		uri: "https://if9.${server}",
		path: "/if9/jlr/vehicles/" + vin + "/services/" + customerServiceId,
		requestContentType: 'application/json',
		headers: [
			'Content-Type': 'application/json',
			'Accept': 'application/vnd.wirelesscar.ngtp.if9.ServiceStatus-v4+json',
			'X-Device-Id': device.deviceNetworkId,
			'Authorization': 'Bearer ' + state.AccessToken
		]
	]
			   
	httpGet(customerServiceParams) { customerServiceResp ->
		if (customerServiceResp.status != 200) {
			log.warn("Did not receive successful response status code.  Received code: ${customerServiceResp.status}")
		} else {
			def data = "${customerServiceResp.data}"
			def jsonResponseData = new groovy.json.JsonSlurper().parseText(data)
			state.customerServiceId = jsonResponseData.status
			displayDebugLog("customerService data received: ${jsonResponseData}")
			if (state.customerServiceId == 'Started' || state.customerServiceId == 'MessageDelivered' || state.customerServiceId == 'Running') {
				sendEvent(name: jsonResponseData.serviceType, value: jsonResponseData.status, descriptionText: jsonResponseData.serviceType + " service", displayed: true)
				// if (state.customerServiceId == 'Started' || state.customerServiceId == 'MessageDelivered' || state.customerServiceId == 'Running')
				runIn(2, 'getServiceStatus', [data: customerServiceId])
			} else {
				sendEvent(name: jsonResponseData.serviceType, value: jsonResponseData.status + ": " + jsonResponseData.failureDescription, descriptionText: jsonResponseData.serviceType + " service", displayed: true)
				if (canRunAgain == true && state.customerServiceId == "Failed" && jsonResponseData.failureDescription != "serviceAlreadyRunning" && jsonResponseData.failureDescription != "vehiclePowerModeNotCorrect" && jsonResponseData.serviceParameters == [["key":"PRECONDITIONING","value":"START"]]) {
					pauseExecution(10000)
					authenticateCommand('preconditioning_START', false)
				}
			}
		}
	}
	// if (state.customerServiceId != 'Successful' && state.customerServiceId != 'Failed')
	// if (state.customerServiceId == 'Started' || state.customerServiceId == 'MessageDelivered' || state.customerServiceId == 'Running')
		// runIn(2, 'getServiceStatus', [data: customerServiceId])
}

private vehicleStatus(canRunAgain = true) {
	displayDebugLog("vehicleStatus()")
	
	if(refreshInterval != null) {
		unschedule("vehicleStatus")
		runIn(refreshInterval * 60, "vehicleStatus") /* runs again X minutes from now, overwriting previous scheduled event involving this same function */
	}

    def vehicleStatusParams = [
		uri: "https://if9.${server}",
		path: "/if9/jlr/vehicles/" + vin + "/status",
		// path: "/if9/jlr/vehicles/" + vin + "/status?includeInactive=true",
		requestContentType: 'application/json',
		headers: [
			'Content-Type': 'application/json',
			'Accept': 'application/vnd.ngtp.org.if9.healthstatus-v2+json',
			// 'Accept': 'application/vnd.ngtp.org.if9.healthstatus-v3+json',
			'X-Device-Id': device.deviceNetworkId,
			'Authorization': 'Bearer ' + state.AccessToken
		]
		// body: [
			// "access_token": state.AccessToken,
			// // "authorization_token": state.AuthToken,
			// // "expires_in": 86400,
			// "deviceID": device.deviceNetworkId
		// ]
    ]
               
	try {
		httpGet(vehicleStatusParams) { vehicleStatusResp ->
			if (vehicleStatusResp.status != 200) {
				log.warn("Did not receive successful response status code.  Received code: ${vehicleStatusResp.status}")
			} else {
				def data = "${vehicleStatusResp.data}"
				def jsonResponseData = new groovy.json.JsonSlurper().parseText(data)
				def responseValue
				displayDebugLog("${jsonResponseData}")
				sendEvent(name: "lastUpdatedTime", value: jsonResponseData.lastUpdatedTime, displayed: true)
				responseValue = jsonResponseData.vehicleStatus.find {it.key == 'EV_STATE_OF_CHARGE'}
				sendEvent(name: "battery", value: responseValue.value, descriptionText: "Traction Battery", displayed: true, unit: "%")
				responseValue = jsonResponseData.vehicleStatus.find {it.key == 'EV_PERMANENT_MAX_SOC_VALUE'}
				sendEvent(name: "EV_PERMANENT_MAX_SOC_VALUE", value: responseValue.value, displayed: true)
				responseValue = jsonResponseData.vehicleStatus.find {it.key == 'EV_ONE_OFF_MAX_VALUE'}
				sendEvent(name: "EV_ONE_OFF_MAX_VALUE", value: responseValue.value, displayed: true)
				responseValue = jsonResponseData.vehicleStatus.find {it.key == 'EV_ONE_OFF_MAX_SOC_CHARGE_SETTING_CHOICE'}
				sendEvent(name: "EV_ONE_OFF_MAX_SOC_CHARGE_SETTING_CHOICE", value: responseValue.value, displayed: true)
				if (responseValue.value == 'CLEAR') {
					sendEvent(name: "levelPreset", value: PERMANENT_MAX_SOC, displayed: true)
				} else {
					sendEvent(name: "levelPreset", value: ONE_OFF_MAX_SOC, displayed: true)
				}
				responseValue = jsonResponseData.vehicleStatus.find {it.key == 'EV_CHARGING_STATUS'}
				sendEvent(name: "EV_CHARGING_STATUS", value: responseValue.value, displayed: true)
				responseValue = jsonResponseData.vehicleStatus.find {it.key == 'EV_CHARGING_RATE_MILES_PER_HOUR'}
				sendEvent(name: "EV_CHARGING_RATE_MILES_PER_HOUR", value: responseValue.value, displayed: true)
				responseValue = jsonResponseData.vehicleStatus.find {it.key == 'EV_MINUTES_TO_FULLY_CHARGED'}
				sendEvent(name: "EV_MINUTES_TO_FULLY_CHARGED", value: responseValue.value, displayed: true)
				// responseValue = jsonResponseData.vehicleStatus.find {it.key == 'EV_CHARGE_FAULT'}
				// state.EV_CHARGE_FAULT = responseValue.value
				responseValue = jsonResponseData.vehicleStatus.find {it.key == 'EV_RANGE_ON_BATTERY_MILES'}
				sendEvent(name: "EV_RANGE_ON_BATTERY_MILES", value: responseValue.value, displayed: true, unit: "mi")
				responseValue = jsonResponseData.vehicleStatus.find {it.key == 'BATTERY_VOLTAGE'}
				sendEvent(name: "BATTERY_VOLTAGE", value: responseValue.value, descriptionText: "Starter Battery", displayed: true, unit: "V")
				responseValue = jsonResponseData.vehicleStatus.find {it.key == 'DOOR_IS_ALL_DOORS_LOCKED'}
				if (responseValue.value == 'TRUE') {
					sendEvent(name: "lock", value: 'locked', displayed: true)
				} else if (responseValue.value == 'FALSE') {
					sendEvent(name: "lock", value: 'unlocked', displayed: true)
				} else {
					sendEvent(name: "lock", value: 'unknown', displayed: true)
				}
				responseValue = jsonResponseData.vehicleStatus.find {it.key == 'EV_PRECONDITION_OPERATING_STATUS'}
				sendEvent(name: "EV_PRECONDITION_OPERATING_STATUS", value: responseValue.value, displayed: true)
				if (responseValue.value == 'STARTUP' || responseValue.value == 'PRECLIM') {
					sendEvent(name: "sessionStatus", value: 'running', displayed: true)
				} else if (responseValue.value == 'OFF') {
					sendEvent(name: "sessionStatus", value: 'stopped', displayed: true)
				} else {
					sendEvent(name: "sessionStatus", value: 'unknown', displayed: true)
				}
				responseValue = jsonResponseData.vehicleStatus.find {it.key == 'EV_PRECONDITION_REMAINING_RUNTIME_MINUTES'}
				// sendEvent(name: "EV_PRECONDITION_REMAINING_RUNTIME_MINUTES", value: responseValue.value, displayed: true)
				sendEvent(name: "timeRemaining", value: responseValue.value, displayed: true)
				responseValue = jsonResponseData.vehicleStatus.find {it.key == 'WINDOW_FRONT_LEFT_STATUS'}
				sendEvent(name: "WINDOW_FRONT_LEFT_STATUS", value: responseValue.value, displayed: true)
				responseValue = jsonResponseData.vehicleStatus.find {it.key == 'WINDOW_FRONT_RIGHT_STATUS'}
				sendEvent(name: "WINDOW_FRONT_RIGHT_STATUS", value: responseValue.value, displayed: true)
				responseValue = jsonResponseData.vehicleStatus.find {it.key == 'WINDOW_REAR_LEFT_STATUS'}
				sendEvent(name: "WINDOW_REAR_LEFT_STATUS", value: responseValue.value, displayed: true)
				responseValue = jsonResponseData.vehicleStatus.find {it.key == 'WINDOW_REAR_RIGHT_STATUS'}
				sendEvent(name: "WINDOW_REAR_RIGHT_STATUS", value: responseValue.value, displayed: true)
				responseValue = jsonResponseData.vehicleStatus.find {it.key == 'TYRE_PRESSURE_FRONT_LEFT'}
				sendEvent(name: "TYRE_PRESSURE_FRONT_LEFT", value: Math.round(responseValue.value.toInteger() * 10 / 68.9476) / 10, descriptionText: "Front Left Tire", displayed: true, unit: "psi")
				responseValue = jsonResponseData.vehicleStatus.find {it.key == 'TYRE_PRESSURE_FRONT_RIGHT'}
				sendEvent(name: "TYRE_PRESSURE_FRONT_RIGHT", value: Math.round(responseValue.value.toInteger() * 10 / 68.9476) / 10, descriptionText: "Front Right Tire", displayed: true, unit: "psi")
				responseValue = jsonResponseData.vehicleStatus.find {it.key == 'TYRE_PRESSURE_REAR_LEFT'}
				sendEvent(name: "TYRE_PRESSURE_REAR_LEFT", value: Math.round(responseValue.value.toInteger() * 10 / 68.9476) / 10, descriptionText: "Rear Left Tire", displayed: true, unit: "psi")
				responseValue = jsonResponseData.vehicleStatus.find {it.key == 'TYRE_PRESSURE_REAR_RIGHT'}
				sendEvent(name: "TYRE_PRESSURE_REAR_RIGHT", value: Math.round(responseValue.value.toInteger() * 10 / 68.9476) / 10, descriptionText: "Rear Right Tire", displayed: true, unit: "psi")
			}
		}
	} catch(Exception ex) {
		log.error(ex.toString() + ', ' + ex.getMessage())
		if (canRunAgain == true) {
			if (login() == true) {
				vehicleStatus(false)
			} else if (registerDevice() == true) {
				if(login() == true) {
					vehicleStatus(false)
				}
			} else if (authenticateUser() == true) {
				if (registerDevice() == true) {
					if(login() == true) {
						vehicleStatus(false)
					}
				}
			}
		}
	}
}

private authenticateCommand(function, canRunAgain = true) {
	def serviceName
	def serviceParameters = null
	def command = function
	def contentType = 'application/vnd.wirelesscar.ngtp.if9.StartServiceConfiguration-v2+json'
	def accept = null
	def runGetServiceStatus = false
    displayDebugLog("authenticateCommand()")
	switch (function) {
		case 'healthstatus':
			content = [
				"serviceName": 'VHS',
				"pin": pin
			]
			break
		case 'honkBlink':
			content = [
				"serviceName": 'HBLF',
				"pin": vin[-4..-1]
			]
			break
		case 'lock':
			content = [
				"serviceName": 'RDL',
				"pin": pin
			]
			// contentType = 'application/vnd.wirelesscar.ngtp.if9.PhevService-v1+json;charset=utf-8'
			contentType = 'application/vnd.wirelesscar.ngtp.if9.StartServiceConfiguration-v3+json'
			// accept = 'application/vnd.wirelesscar.ngtp.if9.ServiceStatus-v5+json'
			runGetServiceStatus = true
			break
		case 'unlock':
			content = [
				"serviceName": 'RDU',
				"pin": pin
			]
			// contentType = 'application/vnd.wirelesscar.ngtp.if9.PhevService-v1+json;charset=utf-8'
			contentType = 'application/vnd.wirelesscar.ngtp.if9.StartServiceConfiguration-v3+json'
			// accept = 'application/vnd.wirelesscar.ngtp.if9.ServiceStatus-v5+json'
			runGetServiceStatus = true
			break
		case 'alarmOff':
			content = [
				"serviceName": 'ALOFF',
				"pin": pin
			]
			break
		case 'preconditioning_START':
			if (target_temperature == null || target_temperature > 285 || target_temperature < 155) {
				log.error("Preconditioning Target Temperature is invalid or not set")
				return false
			}
			command = 'preconditioning'
			content = [
				"serviceName": 'ECC',
				"pin": vin[-4..-1]
			]
			serviceParameters = [["key":"PRECONDITIONING","value":"START"],["key":"TARGET_TEMPERATURE_CELSIUS","value":target_temperature]]
			contentType = 'application/vnd.wirelesscar.ngtp.if9.PhevService-v1+json;charset=utf-8'
			accept = 'application/vnd.wirelesscar.ngtp.if9.ServiceStatus-v5+json'
			runGetServiceStatus = true
			break
		case 'preconditioning_STOP':
			command = 'preconditioning'
			content = [
				"serviceName": 'ECC',
				"pin": vin[-4..-1]
			]
			serviceParameters = [["key":"PRECONDITIONING","value":"STOP"]]
			contentType = 'application/vnd.wirelesscar.ngtp.if9.PhevService-v1+json;charset=utf-8'
			accept = 'application/vnd.wirelesscar.ngtp.if9.ServiceStatus-v5+json'
			runGetServiceStatus = true
			break
		case 'chargeProfile_START':
			command = 'chargeProfile'
			content = [
				"serviceName": 'CP',
				"pin": vin[-4..-1]
			]
			serviceParameters = [["key":"CHARGE_NOW_SETTING","value":"FORCE_ON"]]
			contentType = 'application/vnd.wirelesscar.ngtp.if9.PhevService-v1+json;charset=utf-8'
			break
		case 'chargeProfile_STOP':
			command = 'chargeProfile'
			content = [
				"serviceName": 'CP',
				"pin": vin[-4..-1]
			]
			serviceParameters = [["key":"CHARGE_NOW_SETTING","value":"FORCE_OFF"]]
			contentType = 'application/vnd.wirelesscar.ngtp.if9.PhevService-v1+json;charset=utf-8'
			break
		case 'chargeProfile_SET_PERMANENT_MAX_SOC':
			if (PERMANENT_MAX_SOC == null) {
				log.error("Daily Charge Limit is invalid or not set")
				return false
			}
			command = 'chargeProfile'
			content = [
				"serviceName": 'CP',
				"pin": vin[-4..-1]
			]
			serviceParameters = [["key":"SET_PERMANENT_MAX_SOC","value":PERMANENT_MAX_SOC]]
			contentType = 'application/vnd.wirelesscar.ngtp.if9.PhevService-v1+json;charset=utf-8'
			break
		case 'chargeProfile_SET_ONE_OFF_MAX_SOC':
			if (ONE_OFF_MAX_SOC == null) {
				log.error("Only-Once Charge Limit is invalid or not set")
				return false
			}
			command = 'chargeProfile'
			content = [
				"serviceName": 'CP',
				"pin": vin[-4..-1]
			]
			serviceParameters = [["key":"SET_ONE_OFF_MAX_SOC","value":ONE_OFF_MAX_SOC]]
			contentType = 'application/vnd.wirelesscar.ngtp.if9.PhevService-v1+json;charset=utf-8'
			break
	}

    def authenticateCommandParams = [
		uri: "https://if9.${server}",
		path: "/if9/jlr/vehicles/" + vin + "/users/${state.UserID}/authenticate",
		requestContentType: 'application/json',
		headers: [
			// 'Content-Type': 'application/vnd.wirelesscar.ngtp.if9.AuthenticateRequest-v2+json; charset=utf-8',
			'Content-Type': 'application/json',
			'X-Device-Id': device.deviceNetworkId,
			'Connection': 'close',
			'Authorization': 'Bearer ' + state.AccessToken
		],
		body: content
    ]
               
	try {
		httpPost(authenticateCommandParams) { authenticateCommandResp ->
			if (authenticateCommandResp.status != 200) {
				log.warn("Did not receive successful response status code.  Received code: ${authenticateCommandResp.status}.")
				if (canRunAgain == true) {
					if (login() == true) {
						authenticateCommand(function, false)
					} else if (registerDevice() == true) {
						if(login() == true) {
							authenticateCommand(function, false)
						}
					} else if (authenticateUser() == true) {
						if (registerDevice() == true) {
							if(login() == true) {
								authenticateCommand(function, false)
							}
						}
					}
				}
			} else {
				// def jsonResponseData = authenticateCommandResp
				state.CommandToken = authenticateCommandResp.data.token
				displayInfoLog("Command authentication successful")
				def performCommandParams = [
					uri: "https://if9.${server}",
					path: "/if9/jlr/vehicles/" + vin + "/" + command,
					requestContentType: 'application/json',
					headers: [
						'Content-Type': contentType,
						'X-Device-Id': device.deviceNetworkId,
						'Connection': 'close',
						'Authorization': 'Bearer ' + state.AccessToken,
						'accept': accept
					],
					body: [
						"token": state.CommandToken,
						"serviceParameters": serviceParameters
					]
				]
						   
				try {
					httpPost(performCommandParams) { performCommandResp ->
						if (performCommandResp.status != 202) {
							displayDebugLog("${state.CommandToken}")
							log.warn("Did not receive successful response status code.  Received code: ${performCommandResp.status}")
							if (canRunAgain == true) {
								if (login() == true) {
									authenticateCommand(function, false)
								} else if (registerDevice() == true) {
									if(login() == true) {
										authenticateCommand(function, false)
									}
								} else if (authenticateUser() == true) {
									if (registerDevice() == true) {
										if(login() == true) {
											authenticateCommand(function, false)
										}
									}
								}
							}
						} else {
							displayInfoLog("'" + function + "' command sent successfully")
							try {
								def data = "${performCommandResp.data}"
								def jsonResponseData = new groovy.json.JsonSlurper().parseText(data)
                                customerServiceId = jsonResponseData.customerServiceId
							} catch(Exception ex) {
								jsonResponseData = performCommandResp.data
                                customerServiceId = jsonResponseData.customerServiceId
							}
							// if (runGetServiceStatus && canRunAgain == true)
							if (runGetServiceStatus)
								getServiceStatus(customerServiceId, false)
						}
					}
				} catch(Exception ex) {
					log.error(ex.toString() + ', ' + ex.getMessage())
					if (canRunAgain == true) {
						pauseExecution(1000) /* wait before sending the next command */
						if (login() == true) {
							authenticateCommand(function, false)
						} else if (registerDevice() == true) {
							if(login() == true) {
								authenticateCommand(function, false)
							}
						} else if (authenticateUser() == true) {
							if (registerDevice() == true) {
								if(login() == true) {
									authenticateCommand(function, false)
								}
							}
						}
					}
				}
			}
		}
	} catch(Exception ex) {
		log.error(ex.toString() + ', ' + ex.getMessage())
		if (canRunAgain == true) {
			if (login() == true) {
				authenticateCommand(function, false)
			} else if (registerDevice() == true) {
				if(login() == true) {
					authenticateCommand(function, false)
				}
			} else if (authenticateUser() == true) {
				if (registerDevice() == true) {
					if(login() == true) {
						authenticateCommand(function, false)
					}
				}
			}
		}
	}
}


private def healthstatus() {
	authenticateCommand('healthstatus')
}
def lock() {
	authenticateCommand('lock')
	// refreshStatus()
}
def unlock() {
	authenticateCommand('unlock')
	// refreshStatus()
}
def start() {
	authenticateCommand('preconditioning_START')
	// unschedule("refreshStatus")
	// runIn(delayRetrieval, "refreshStatus") /* runs again X seconds from now, accounting for extra time needed for preconditioning feedback and overwriting previous scheduled event involving this same function */
}
def stop() {
	authenticateCommand('preconditioning_STOP')
	// unschedule("refreshStatus")
	// runIn(delayRetrieval, "refreshStatus") /* runs again X seconds from now, accounting for extra time needed for preconditioning feedback and overwriting previous scheduled event involving this same function */
}
private def chargeProfile(value) {
	authenticateCommand('chargeProfile_' + value)
	// refreshStatus()
}
def siren() {
	authenticateCommand('honkBlink')
}
def off() {
	authenticateCommand('alarmOff')
}

private def refreshStatus() {
	authenticateCommand('healthstatus')
	if(delayRetrieval != null) {
		unschedule("vehicleStatus")
		runIn(delayRetrieval, "vehicleStatus") /* runs again X seconds from now, overwriting previous scheduled event involving this same function */
	}
}

private def displayDebugLog(message) {
	if (debugLogging)
		log.debug "${device.displayName}: ${message}"
}

private def displayInfoLog(message) {
	if (infoLogging)
		log.info "${device.displayName}: ${message}"
}
