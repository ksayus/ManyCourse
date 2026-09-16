package com.tof.manycourse.gr_api

import com.tof.manycourse.api.HttpMethod
import org.json.JSONObject

class GRClassAPI {
    val httpMethod = HttpMethod()

    fun loginSystem(account: String, password: String, school_request_url: String): String {
        val jsonSting = JSONObject().apply {
            put("account", account)
            put("password", password)
        }

        val request = httpMethod.post(school_request_url, jsonSting)

        return request
    }
}