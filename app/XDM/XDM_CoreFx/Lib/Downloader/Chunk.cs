using System;

namespace XDM.Core.Lib.Common
{
    public class Chunk
    {
        public Uri Uri { get; set; }
        public long Size { get; set; }
        public long Offset { get; set; }
        public string Id { get; set; }
        public ChunkState ChunkState { get; set; }
        public long Downloaded { get; set; }

        public Chunk()
        {
            this.ChunkState = ChunkState.Ready;
        }
    }

    public enum ChunkState
    {
        Ready, FailedFatal, FailedTransient, InProgress, Finished
    }
}
