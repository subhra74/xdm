using System.Collections.Generic;
using XDM.Core;

#if !NET5_0_OR_GREATER
using XDM.Compatibility;
#endif

namespace XDM.Core
{
    internal static class ErrorMessages
    {
        private static Dictionary<string, string> errorMessages;
        static ErrorMessages()
        {
            var errPrefix = "ERR_";
            errorMessages = new()
            {
                [errPrefix + ErrorCode.Generic] = "Download error",
                [errPrefix + ErrorCode.NonResumable] = "Download is not resumable",
                [errPrefix + ErrorCode.AssemblingFailed] = "Download assembling failed",
                [errPrefix + ErrorCode.MaxRetryFailed] = "Download error",
                [errPrefix + ErrorCode.InvalidResponse] = "Invalid response from server",
                [errPrefix + ErrorCode.FFmpegNotFound] = "FFmpeg not found",
                [errPrefix + ErrorCode.FFmpegError] = "FFmpeg error",
                [errPrefix + ErrorCode.DiskError] = "Disk is either full or readonly",
                [errPrefix + ErrorCode.SessionExpired] = "Session expired"
            };
        }

        public static void SetErrorMessages(Dictionary<string, string> errors)
        {
            errorMessages = errors;
        }

        public static string GetLocalizedErrorMessage(ErrorCode errorCode)
        {
            var errPrefix = "ERR_";
            return errorMessages.GetValueOrDefault(errPrefix + errorCode, "Download error");
        }
    }
}
