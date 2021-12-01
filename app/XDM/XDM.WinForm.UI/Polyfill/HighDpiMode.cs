namespace XDM.WinForm.UI
{
    public enum HighDpiMode
    {
        /// <summary>
        ///  The window does not scale for DPI changes and always assumes a scale factor of 100%.
        /// </summary>
        DpiUnaware,

        /// <summary>
        ///  The window queries for the DPI of the primary monitor once and uses this for the application on all monitors.
        /// </summary>
        SystemAware,

        /// <summary>
        ///  The window checks for DPI when it's created and adjusts scale factor when the DPI changes.
        /// </summary>
        PerMonitor,

        /// <summary>
        ///  Similar to <see cref="PerMonitor"/>, but enables child window DPI change notification, improved scaling of comctl32 controls and dialog scaling.
        /// </summary>
        PerMonitorV2,

        /// <summary>
        ///  Similar to <see cref="DpiUnaware"/>, but improves the quality of GDI/GDI+ based content.
        /// </summary>
        DpiUnawareGdiScaled
    }
}
