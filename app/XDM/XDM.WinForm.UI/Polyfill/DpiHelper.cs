// Licensed to the .NET Foundation under one or more agreements.
// The .NET Foundation licenses this file to you under the MIT license.
// See the LICENSE file in the project root for more information.

using System;
using System.Diagnostics;
using System.Diagnostics.CodeAnalysis;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;
using Microsoft.Win32;

namespace XDM.WinForm.UI
{
    /// <summary>
    ///  Helper class for scaling coordinates and images according to current DPI scaling set in Windows for the primary screen.
    /// </summary>
    internal static partial class DpiHelper
    {
        // The default(100) and max(225) text scale factor is value what Settings display text scale
        // applies and also clamps the text scale factor value between 100 and 225 value.
        // See https://docs.microsoft.com/windows/uwp/design/input/text-scaling.
        internal const short MinTextScaleValue = 100;
        internal const short MaxTextScaleValue = 225;
        internal const float MinTextScaleFactorValue = 1.00f;
        internal const float MaxTextScaleFactorValue = 2.25f;

        internal const double LogicalDpi = 96.0;
        private static InterpolationMode s_interpolationMode;

        // Backing field, indicating that we will need to send a PerMonitorV2 query in due course.
        private static bool s_perMonitorAware;

        internal static int DeviceDpi { get; private set; }

        static DpiHelper() => Initialize();

        private static void Initialize()
        {
            s_interpolationMode = InterpolationMode.Invalid;
            s_perMonitorAware = GetPerMonitorAware();
            DeviceDpi = GetDeviceDPI();
        }

        private static int GetDeviceDPI()
        {
            // This will only change when the first call to set the process DPI awareness is made. Multiple calls to
            // set the DPI have no effect after making the first call. Depending on what the DPI awareness settings are
            // we'll get either the actual DPI of the primary display at process startup or the default LogicalDpi;

            if (!OsVersion.IsWindows10_1607OrGreater)
            {
                using var dc = User32.GetDcScope.ScreenDC;
                return Gdi32.GetDeviceCaps(dc, Gdi32.DeviceCapability.LOGPIXELSX);
            }

            // This avoids needing to create a DC
            return (int)User32.GetDpiForSystem();
        }

        private static bool GetPerMonitorAware()
        {
            if (!OsVersion.IsWindows10_1607OrGreater)
            {
                return false;
            }

            HRESULT result = SHCore.GetProcessDpiAwareness(
                IntPtr.Zero,
                out SHCore.PROCESS_DPI_AWARENESS processDpiAwareness);

            Debug.Assert(result.Succeeded(), $"Failed to get ProcessDpi HRESULT: {result}");
            Debug.Assert(Enum.IsDefined(typeof(SHCore.PROCESS_DPI_AWARENESS), processDpiAwareness));

            return result.Succeeded() && processDpiAwareness switch
            {
                SHCore.PROCESS_DPI_AWARENESS.UNAWARE => false,
                SHCore.PROCESS_DPI_AWARENESS.SYSTEM_AWARE => false,
                SHCore.PROCESS_DPI_AWARENESS.PER_MONITOR_AWARE => true,
                _ => true
            };
        }

        /// <summary>
        ///  Returns a boolean to specify if we should enable processing of WM_DPICHANGED and related messages
        /// </summary>
        internal static bool IsPerMonitorV2Awareness
        {
            get
            {
                if (s_perMonitorAware)
                {
                    // We can't cache this value because different top level windows can have different DPI awareness context
                    // for mixed mode applications.
                    IntPtr dpiAwareness = User32.GetThreadDpiAwarenessContext();
                    return User32.AreDpiAwarenessContextsEqual(dpiAwareness, User32.DPI_AWARENESS_CONTEXT.PER_MONITOR_AWARE_V2);
                }
                else
                {
                    return false;
                }
            }
        }

        /// <summary>
        ///  Indicates, if rescaling becomes necessary, either because we are not 96 DPI or we're PerMonitorV2Aware.
        /// </summary>
        internal static bool IsScalingRequirementMet => IsScalingRequired || s_perMonitorAware;

        /// <summary>
        ///  Returns the ratio of <see cref="DeviceDpi"/> to <see cref="LogicalDpi"/>.
        /// </summary>
        internal static double LogicalToDeviceUnitsScalingFactor => DeviceDpi / LogicalDpi;

        private static InterpolationMode InterpolationMode
        {
            get
            {
                if (s_interpolationMode == InterpolationMode.Invalid)
                {
                    int dpiScalePercent = (int)Math.Round(LogicalToDeviceUnitsScalingFactor * 100);

                    // We will prefer NearestNeighbor algorithm for 200, 300, 400, etc zoom factors, in which each pixel become a 2x2, 3x3, 4x4, etc rectangle.
                    // This produces sharp edges in the scaled image and doesn't cause distortions of the original image.
                    // For any other scale factors we will prefer a high quality resizing algorithm. While that introduces fuzziness in the resulting image,
                    // it will not distort the original (which is extremely important for small zoom factors like 125%, 150%).
                    // We'll use Bicubic in those cases, except on reducing (zoom < 100, which we shouldn't have anyway), in which case Linear produces better
                    // results because it uses less neighboring pixels.
                    if ((dpiScalePercent % 100) == 0)
                    {
                        s_interpolationMode = InterpolationMode.NearestNeighbor;
                    }
                    else if (dpiScalePercent < 100)
                    {
                        s_interpolationMode = InterpolationMode.HighQualityBilinear;
                    }
                    else
                    {
                        s_interpolationMode = InterpolationMode.HighQualityBicubic;
                    }
                }

                return s_interpolationMode;
            }
        }

        private static Bitmap ScaleBitmapToSize(Bitmap logicalImage, Size deviceImageSize)
        {
            Bitmap deviceImage;
            deviceImage = new Bitmap(deviceImageSize.Width, deviceImageSize.Height, logicalImage.PixelFormat);

            using (Graphics graphics = Graphics.FromImage(deviceImage))
            {
                graphics.InterpolationMode = InterpolationMode;

                RectangleF sourceRect = new RectangleF(0, 0, logicalImage.Size.Width, logicalImage.Size.Height);
                RectangleF destRect = new RectangleF(0, 0, deviceImageSize.Width, deviceImageSize.Height);

                // Specify a source rectangle shifted by half of pixel to account for GDI+ considering the source origin the center of top-left pixel
                // Failing to do so will result in the right and bottom of the bitmap lines being interpolated with the graphics' background color,
                // and will appear black even if we cleared the background with transparent color.
                // The apparition of these artifacts depends on the interpolation mode, on the dpi scaling factor, etc.
                // E.g. at 150% DPI, Bicubic produces them and NearestNeighbor is fine, but at 200% DPI NearestNeighbor also shows them.
                sourceRect.Offset(-0.5f, -0.5f);

                graphics.DrawImage(logicalImage, destRect, sourceRect, GraphicsUnit.Pixel);
            }

            return deviceImage;
        }

        public static Bitmap CreateScaledBitmap(Bitmap logicalImage, int deviceDpi = 0)
        {
            Size deviceImageSize = LogicalToDeviceUnits(logicalImage.Size, deviceDpi);
            return ScaleBitmapToSize(logicalImage, deviceImageSize);
        }

        /// <summary>
        ///  Returns whether scaling is required when converting between logical-device units,
        ///  if the application opted in the automatic scaling in the .config file.
        /// </summary>
        public static bool IsScalingRequired => DeviceDpi != LogicalDpi;

        /// <summary>
        /// Retrieve the text scale factor, which is set via Settings > Display > Make Text Bigger.
        /// The settings are stored in the registry under HKCU\Software\Microsoft\Accessibility in (DWORD)TextScaleFactor.
        /// </summary>
        /// <returns>The scaling factor in the range [1.0, 2.25].</returns>
        /// <seealso href="https://docs.microsoft.com/windows/uwp/design/input/text-scaling">Windows Text scaling</seealso>
        public static float GetTextScaleFactor()
        {
            short textScaleValue = MinTextScaleValue;
            try
            {
                RegistryKey? key = Registry.CurrentUser.OpenSubKey("Software\\Microsoft\\Accessibility");
                if (key is not null && key.GetValue("TextScaleFactor") is int _textScaleValue)
                {
                    textScaleValue = (short)_textScaleValue;
                }
            }
            catch
            {
                // Failed to read the registry for whatever reason.
#if DEBUG
                throw;
#endif
            }

            // Restore the text scale if it isn't the default value in the valid text scale factor value
            if (textScaleValue > MinTextScaleValue && textScaleValue <= MaxTextScaleValue)
            {
                return (float)textScaleValue / MinTextScaleValue;
            }

            return MinTextScaleFactorValue;
        }

        /// <summary>
        /// scale logical pixel to the factor
        /// </summary>
        public static int ConvertToGivenDpiPixel(int value, double pixelFactor)
        {
            var scaledValue = (int)Math.Round(value * pixelFactor);
            return scaledValue == 0 ? 1 : scaledValue;
        }

        /// <summary>
        ///  Transforms a horizontal or vertical integer coordinate from logical to device units
        ///  by scaling it up for current DPI and rounding to nearest integer value
        /// </summary>
        /// <param name="value">value in logical units</param>
        /// <returns>value in device units</returns>
        public static int LogicalToDeviceUnits(int value, int devicePixels = 0)
        {
            if (devicePixels == 0)
            {
                return (int)Math.Round(LogicalToDeviceUnitsScalingFactor * value);
            }

            double scalingFactor = devicePixels / LogicalDpi;
            return (int)Math.Round(scalingFactor * value);
        }

        /// <summary>
        ///  Returns a new Padding with the input's
        ///  dimensions converted from logical units to device units.
        /// </summary>
        /// <param name="logicalPadding">Padding in logical units</param>
        /// <returns>Padding in device units</returns>
        public static Padding LogicalToDeviceUnits(Padding logicalPadding, int deviceDpi = 0)
        {
            return new Padding(
                LogicalToDeviceUnits(logicalPadding.Left, deviceDpi),
                LogicalToDeviceUnits(logicalPadding.Top, deviceDpi),
                LogicalToDeviceUnits(logicalPadding.Right, deviceDpi),
                LogicalToDeviceUnits(logicalPadding.Bottom, deviceDpi));
        }

        /// <summary>
        ///  Transforms a horizontal integer coordinate from logical to device units
        ///  by scaling it up  for current DPI and rounding to nearest integer value
        /// </summary>
        /// <param name="value">The horizontal value in logical units</param>
        /// <returns>The horizontal value in device units</returns>
        public static int LogicalToDeviceUnitsX(int value)
        {
            return LogicalToDeviceUnits(value, 0);
        }

        /// <summary>
        ///  Transforms a vertical integer coordinate from logical to device units
        ///  by scaling it up  for current DPI and rounding to nearest integer value
        /// </summary>
        /// <param name="value">The vertical value in logical units</param>
        /// <returns>The vertical value in device units</returns>
        public static int LogicalToDeviceUnitsY(int value)
        {
            return LogicalToDeviceUnits(value, 0);
        }

        /// <summary>
        ///  Returns a new Size with the input's
        ///  dimensions converted from logical units to device units.
        /// </summary>
        /// <param name="logicalSize">Size in logical units</param>
        /// <returns>Size in device units</returns>
        public static Size LogicalToDeviceUnits(Size logicalSize, int deviceDpi = 0)
        {
            return new Size(LogicalToDeviceUnits(logicalSize.Width, deviceDpi),
                            LogicalToDeviceUnits(logicalSize.Height, deviceDpi));
        }

        /// <summary>
        ///  Create and return a new bitmap scaled to the specified size.
        /// </summary>
        /// <param name="logicalImage">The image to scale from logical units to device units</param>
        /// <param name="targetImageSize">The size to scale image to</param>

        public static Bitmap? CreateResizedBitmap(Bitmap? logicalImage, Size targetImageSize)
        {
            if (logicalImage is null)
            {
                return null;
            }

            return ScaleBitmapToSize(logicalImage, targetImageSize);
        }

        /// <summary>
        ///  Creating bitmap from Icon resource
        /// </summary>
        public static Bitmap GetBitmapFromIcon(Type t, string name)
        {
            Icon b = new Icon(t, name);
            Bitmap bitmap = b.ToBitmap();
            b.Dispose();
            return bitmap;
        }

        /// <summary>
        ///  Create a new bitmap scaled for the device units.
        ///  When displayed on the device, the scaled image will have same size as the original image would have when displayed at 96dpi.
        /// </summary>
        /// <param name="logicalBitmap">The image to scale from logical units to device units</param>
        public static void ScaleBitmapLogicalToDevice(ref Bitmap logicalBitmap, int deviceDpi = 0)
        {
            if (logicalBitmap is null)
            {
                return;
            }

            Bitmap deviceBitmap = CreateScaledBitmap(logicalBitmap, deviceDpi);
            if (deviceBitmap is not null)
            {
                logicalBitmap.Dispose();
                logicalBitmap = deviceBitmap;
            }
        }

        /// <summary>
        ///  Indicates whether the first (Parking)Window has been created. From that moment on,
        ///  we will not be able nor allowed to change the Process' DpiMode.
        /// </summary>
        internal static bool FirstParkingWindowCreated { get; set; }

        /// <summary>
        ///  Gets the DPI awareness.
        /// </summary>
        /// <returns>The thread's/process' current HighDpi mode</returns>
        internal static HighDpiMode GetWinformsApplicationDpiAwareness()
        {
            // For Windows 10 RS2 and above
            if (OsVersion.IsWindows10_1607OrGreater)
            {
                IntPtr dpiAwareness = User32.GetThreadDpiAwarenessContext();

                if (User32.AreDpiAwarenessContextsEqual(dpiAwareness, User32.DPI_AWARENESS_CONTEXT.SYSTEM_AWARE))
                {
                    return HighDpiMode.SystemAware;
                }

                if (User32.AreDpiAwarenessContextsEqual(dpiAwareness, User32.DPI_AWARENESS_CONTEXT.UNAWARE))
                {
                    return HighDpiMode.DpiUnaware;
                }

                if (User32.AreDpiAwarenessContextsEqual(dpiAwareness, User32.DPI_AWARENESS_CONTEXT.PER_MONITOR_AWARE_V2))
                {
                    return HighDpiMode.PerMonitorV2;
                }

                if (User32.AreDpiAwarenessContextsEqual(dpiAwareness, User32.DPI_AWARENESS_CONTEXT.PER_MONITOR_AWARE))
                {
                    return HighDpiMode.PerMonitor;
                }

                if (User32.AreDpiAwarenessContextsEqual(dpiAwareness, User32.DPI_AWARENESS_CONTEXT.UNAWARE_GDISCALED))
                {
                    return HighDpiMode.DpiUnawareGdiScaled;
                }
            }
            else if (OsVersion.IsWindows8_1OrGreater)
            {
                SHCore.GetProcessDpiAwareness(IntPtr.Zero, out SHCore.PROCESS_DPI_AWARENESS processDpiAwareness);
                switch (processDpiAwareness)
                {
                    case SHCore.PROCESS_DPI_AWARENESS.UNAWARE:
                        return HighDpiMode.DpiUnaware;
                    case SHCore.PROCESS_DPI_AWARENESS.SYSTEM_AWARE:
                        return HighDpiMode.SystemAware;
                    case SHCore.PROCESS_DPI_AWARENESS.PER_MONITOR_AWARE:
                        return HighDpiMode.PerMonitor;
                }
            }
            else
            {
                // Available on Vista and higher
                return User32.IsProcessDPIAware() ? HighDpiMode.SystemAware : HighDpiMode.DpiUnaware;
            }

            // We should never get here, except someone ported this with force to < Windows 7.
            return HighDpiMode.DpiUnaware;
        }

        /// <summary>
        ///  Sets the DPI awareness. If not available on the current OS, it falls back to the next possible.
        /// </summary>
        /// <returns>true/false - If the process DPI awareness is successfully set, returns true. Otherwise false.</returns>
        internal static bool SetWinformsApplicationDpiAwareness(HighDpiMode highDpiMode)
        {
            bool success = false;

            if (OsVersion.IsWindows10_1703OrGreater)
            {
                // SetProcessIntPtr needs Windows 10 RS2 and above
                IntPtr rs2AndAboveDpiFlag;
                switch (highDpiMode)
                {
                    case HighDpiMode.SystemAware:
                        rs2AndAboveDpiFlag = User32.DPI_AWARENESS_CONTEXT.SYSTEM_AWARE;
                        break;
                    case HighDpiMode.PerMonitor:
                        rs2AndAboveDpiFlag = User32.DPI_AWARENESS_CONTEXT.PER_MONITOR_AWARE;
                        break;
                    case HighDpiMode.PerMonitorV2:
                        // Necessary for RS1, since this SetProcessIntPtr IS available here.
                        rs2AndAboveDpiFlag = User32.IsValidDpiAwarenessContext(User32.DPI_AWARENESS_CONTEXT.PER_MONITOR_AWARE_V2)
                            ? User32.DPI_AWARENESS_CONTEXT.PER_MONITOR_AWARE_V2
                            : User32.DPI_AWARENESS_CONTEXT.SYSTEM_AWARE;
                        break;
                    case HighDpiMode.DpiUnawareGdiScaled:
                        // Let's make sure, we do not try to set a value which has been introduced in later Windows releases.
                        rs2AndAboveDpiFlag = User32.IsValidDpiAwarenessContext(User32.DPI_AWARENESS_CONTEXT.UNAWARE_GDISCALED)
                            ? User32.DPI_AWARENESS_CONTEXT.UNAWARE_GDISCALED
                            : User32.DPI_AWARENESS_CONTEXT.UNAWARE;
                        break;
                    default:
                        rs2AndAboveDpiFlag = User32.DPI_AWARENESS_CONTEXT.UNAWARE;
                        break;
                }

                success = User32.SetProcessDpiAwarenessContext(rs2AndAboveDpiFlag);
            }
            else if (OsVersion.IsWindows8_1OrGreater)
            {
                // 8.1 introduced SetProcessDpiAwareness
                SHCore.PROCESS_DPI_AWARENESS dpiFlag;
                switch (highDpiMode)
                {
                    case HighDpiMode.DpiUnaware:
                    case HighDpiMode.DpiUnawareGdiScaled:
                        dpiFlag = SHCore.PROCESS_DPI_AWARENESS.UNAWARE;
                        break;
                    case HighDpiMode.SystemAware:
                        dpiFlag = SHCore.PROCESS_DPI_AWARENESS.SYSTEM_AWARE;
                        break;
                    case HighDpiMode.PerMonitor:
                    case HighDpiMode.PerMonitorV2:
                        dpiFlag = SHCore.PROCESS_DPI_AWARENESS.PER_MONITOR_AWARE;
                        break;
                    default:
                        dpiFlag = SHCore.PROCESS_DPI_AWARENESS.SYSTEM_AWARE;
                        break;
                }

                success = SHCore.SetProcessDpiAwareness(dpiFlag) == HRESULT.S_OK;
            }
            else
            {
                // Vista or higher has SetProcessDPIAware
                SHCore.PROCESS_DPI_AWARENESS dpiFlag = (SHCore.PROCESS_DPI_AWARENESS)(-1);
                switch (highDpiMode)
                {
                    case HighDpiMode.DpiUnaware:
                    case HighDpiMode.DpiUnawareGdiScaled:
                        // We can return, there is nothing to set if we assume we're already in DpiUnaware.
                        return true;
                    case HighDpiMode.SystemAware:
                    case HighDpiMode.PerMonitor:
                    case HighDpiMode.PerMonitorV2:
                        dpiFlag = SHCore.PROCESS_DPI_AWARENESS.SYSTEM_AWARE;
                        break;
                }

                if (dpiFlag == SHCore.PROCESS_DPI_AWARENESS.SYSTEM_AWARE)
                {
                    success = User32.SetProcessDPIAware();
                }
            }

            // Need to reset as our DPI will change if this was the first call to set the DPI context for the process.
            Initialize();

            return success;
        }
    }
}