using System;
using System.Threading;
using XDM.Core.Lib.Downloader;

namespace XDM.Core.Lib.Common.MediaProcessor
{
    public abstract class BaseMediaProcessor
    {
        protected readonly ProgressResultEventArgs progressResult = new ProgressResultEventArgs();
        public abstract MediaProcessingResult MergeAudioVideStream(string file1, string file2, string outfile, CancelFlag cancellationToken, out long outFileSize);
        public abstract MediaProcessingResult MergeHLSAudioVideStream(string segmentListFile, string outfile, CancelFlag cancellationToken, out long outFileSize);
        
        public virtual event EventHandler<ProgressResultEventArgs> ProgressChanged;

        protected void UpdateProgress(int progress)
        {
            progressResult.Progress = progress;
            ProgressChanged?.Invoke(this, progressResult);
        }
    }

    public enum MediaProcessingResult
    {
        Success,
        AppNotFound,
        Failed
    }
}
