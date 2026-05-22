package xdm.core

import java.net.Proxy

interface CoreConfig {
    var speedLimit: Int
    var speedLimiterEnabled: Boolean
    var maxSegments: Int
    var maxRetries: Int
    var useProxy: Boolean
    var socksProxy: Boolean
    var proxyHost: String
    var proxyPort: Int
    var proxyUser: String
    var proxyPass: String
    fun toProxy(): Proxy?
}