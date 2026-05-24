package xdm.app

import xdm.app.ui.components.MessageBox
import java.net.Authenticator
import java.net.PasswordAuthentication

class DefaultAuthenticator : Authenticator() {
    override fun getPasswordAuthentication(): PasswordAuthentication? {
        val isProxy = requestorType == RequestorType.PROXY
        val message = "$requestingSite  $requestingPrompt"
        val auth = MessageBox.showAuth("XDM", message, isProxy)
        if (auth != null) {
            return PasswordAuthentication(auth.userName, auth.password.toCharArray())
        }
        return null
    }
}