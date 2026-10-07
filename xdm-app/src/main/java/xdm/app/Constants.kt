package xdm.app

const val CONFIG_DIR = ".xdman"
const val CONFIG_FILE = "config.json"
const val XDM_WINDOW_TITLE = "Xtreme Download Manager"


/**
 * Start without opening the main window; XDM sits in the system tray until it is asked for. Passed
 * by the login entry [xdm.app.utils.AutoStart] writes, so starting with the machine is unobtrusive.
 */
const val MINIMIZED_FLAG = "--minimized"
