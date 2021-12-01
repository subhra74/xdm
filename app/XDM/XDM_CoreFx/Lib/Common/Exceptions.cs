using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Text;


namespace XDM.Core.Lib.Common
{
    public class DownloadException : Exception
    {
        public ErrorCode ErrorCode { get; }

        public DownloadException(ErrorCode errorCode)
        {
            this.ErrorCode = errorCode;
        }

        public DownloadException(ErrorCode errorCode, string message) : base(message)
        {
            this.ErrorCode = errorCode;
        }

        public DownloadException(ErrorCode errorCode, string message, Exception innerException) : base(message, innerException)
        {
            this.ErrorCode = errorCode;
        }
    }

    public class NonRetriableException : DownloadException
    {
        public NonRetriableException(ErrorCode errorCode, string message) : base(errorCode, message) { }

        public NonRetriableException(ErrorCode errorCode, string message, Exception innerException) :
            base(errorCode, message, innerException)
        { }
    }

    public class AssembleFailedException : DownloadException
    {
        public AssembleFailedException(ErrorCode errorCode) : base(errorCode) { }
        public AssembleFailedException(ErrorCode errorCode, Exception e) : base(errorCode, e.Message) { }
    }

    public class HttpException : Exception
    {
        public HttpException() { }
        public HttpException(string message, Exception? innerException, HttpStatusCode statusCode) : base(message, innerException)
        {
            this.StatusCode = statusCode;
        }
        public HttpStatusCode StatusCode { get; set; }
    }

    public class TextRedirectException : Exception
    {
        public Uri RedirectUri { get; set; }
        public TextRedirectException(Uri uri)
        {
            this.RedirectUri = uri;
        }
    }
}
