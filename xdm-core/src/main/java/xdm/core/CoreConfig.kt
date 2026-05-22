package xdm.core

interface CoreConfig {
    var speedLimit: Int
    var speedLimiterEnabled: Boolean
    var maxSegments: Int
    var maxRetries: Int
    var useProxy: Boolean
    var proxyHost: String
    var proxyPort: Int
    var proxyUser: String
    var proxyPass: String
}